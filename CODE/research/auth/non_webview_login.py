#!/usr/bin/env python3
import argparse
import json
import re
import subprocess
import tempfile
import time
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlencode, urljoin, urlparse

import requests
from bs4 import BeautifulSoup

CAS_LOGIN_URL = "https://newcas.gzhu.edu.cn/cas/login"
SERVICE_URL = "http://libbooking.gzhu.edu.cn/authcenter/doAuth/4edbd40b8d1b4ef8970355950765d41f"
AUTHCENTER_TO_LOGIN = "http://libbooking.gzhu.edu.cn/authcenter/toLoginPage"
DES_JS_URL = "https://newcas.gzhu.edu.cn/cas/comm/js/des.js"
USERINFO_API = "https://libbooking.gzhu.edu.cn/ic-web/auth/userInfo"
AUTH_ADDRESS_API = (
    "https://libbooking.gzhu.edu.cn/ic-web/auth/address"
    "?finalAddress=https:%2F%2Flibbooking.gzhu.edu.cn"
    "&errPageUrl=https:%2F%2Flibbooking.gzhu.edu.cn%2F%23%2Ferror"
    "&manager=false&consoleType=16"
)
DEFAULT_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"
)


@dataclass
class Hop:
    method: str
    url: str
    status: int
    location: str


class LoginError(RuntimeError):
    pass


def _discover_to_login_page_url(session: requests.Session) -> tuple[str, dict[str, Any]]:
    trace: dict[str, Any] = {}

    pre_userinfo = session.get(
        USERINFO_API,
        headers={"accept": "application/json, text/plain, */*", "lan": "1", "user-agent": DEFAULT_UA},
        timeout=30,
    )
    trace["pre_userinfo_status"] = pre_userinfo.status_code
    trace["pre_userinfo_body"] = pre_userinfo.text[:300]

    auth_addr_resp = session.get(
        AUTH_ADDRESS_API,
        headers={"accept": "application/json, text/plain, */*", "lan": "1", "user-agent": DEFAULT_UA},
        timeout=30,
    )
    trace["auth_address_status"] = auth_addr_resp.status_code
    trace["auth_address_body"] = auth_addr_resp.text[:800]

    if auth_addr_resp.status_code == 200:
        try:
            obj = auth_addr_resp.json()
            data_url = obj.get("data") if isinstance(obj, dict) else None
            if isinstance(data_url, str) and data_url.startswith("http"):
                trace["mode"] = "auth-address"
                return data_url, trace
        except Exception:
            pass

    trace["mode"] = "fallback"
    return AUTHCENTER_TO_LOGIN, trace


def _service_login_url(service_url: str) -> str:
    return f"{CAS_LOGIN_URL}?{urlencode({'service': service_url})}"


def _extract_form_inputs(html: str) -> dict[str, str]:
    soup = BeautifulSoup(html, "html.parser")
    form = soup.find("form", {"id": "loginForm"}) or soup.find("form")
    if form is None:
        raise LoginError("未找到登录表单 loginForm")

    values: dict[str, str] = {}
    for inp in form.find_all("input"):
        raw_name = inp.get("name")
        name = str(raw_name or "").strip()
        if not name:
            continue
        values[name] = str(inp.get("value", "") or "")
    return values


def _extract_error_text(html: str) -> str:
    soup = BeautifulSoup(html, "html.parser")
    candidates = [
        "#errormsg",
        "#msg",
        ".errors",
        ".error",
        ".alert-danger",
        "#robot-msg",
    ]
    for selector in candidates:
        node = soup.select_one(selector)
        if node:
            text = " ".join(node.get_text(" ", strip=True).split())
            if text and text not in {"Hi，你好呀~", "Hi,你好呀~"}:
                return text

    whole = " ".join(soup.get_text(" ", strip=True).split())
    for pat in [
        r"(账号[^\s]{0,12}密码[^\s]{0,12}(错误|为空|不正确))",
        r"(验证码[^\s]{0,10}(错误|失效|为空))",
        r"(用户[^\s]{0,8}(不存在|未登录))",
        r"(登录[^\s]{0,10}(失败|异常))",
    ]:
        m = re.search(pat, whole)
        if m:
            return m.group(1)

    title = soup.title.string.strip() if soup.title and soup.title.string else ""
    if title:
        return f"页面标题: {title}"
    return ""


def _compute_rsa_with_node(des_js: str, plain_text: str) -> str:
    wrapper = (
        des_js
        + "\n"
        + "const args = process.argv.slice(2);"
        + "\n"
        + "if (typeof strEnc !== 'function') {"
        + "\n"
        + "  console.error('des.js missing strEnc'); process.exit(2);"
        + "\n"
        + "}"
        + "\n"
        + "process.stdout.write(String(strEnc(args[0], args[1], args[2], args[3])));"
    )

    with tempfile.NamedTemporaryFile("w", suffix="_cas_des_wrapper.js", delete=False, encoding="utf-8") as fp:
        fp.write(wrapper)
        script_path = fp.name

    try:
        cp = subprocess.run(
            ["node", script_path, plain_text, "1", "2", "3"],
            capture_output=True,
            text=True,
            check=False,
            timeout=30,
        )
    finally:
        try:
            Path(script_path).unlink(missing_ok=True)
        except Exception:
            pass

    if cp.returncode != 0:
        raise LoginError(f"Node DES 计算失败: rc={cp.returncode}, err={cp.stderr.strip()}")

    out = cp.stdout.strip()
    if not out or not re.fullmatch(r"[0-9A-Fa-f]+", out):
        raise LoginError(f"DES 输出异常: {out[:120]}")
    return out.upper()


def _follow_redirects(
    session: requests.Session,
    first_resp: requests.Response,
    max_hops: int,
    fixed_referer: str,
    fixed_origin: str,
) -> tuple[requests.Response, list[Hop]]:
    hops: list[Hop] = []
    resp = first_resp
    current_url = str(resp.request.url or "")

    for _ in range(max_hops):
        status = int(resp.status_code)
        location = resp.headers.get("Location", "")
        hops.append(Hop(method=str(resp.request.method or "GET"), url=current_url, status=status, location=location))

        if status not in {301, 302, 303, 307, 308} or not location:
            return resp, hops

        next_url = urljoin(current_url, location)
        next_method = "GET" if status in {301, 302, 303} else str(resp.request.method or "GET")

        req_headers = {
            "content-type": "application/x-www-form-urlencoded",
            "origin": fixed_origin,
            "referer": fixed_referer,
            "upgrade-insecure-requests": "1",
            "user-agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/145.0.7632.6 Safari/537.36",
            "sec-ch-ua": '"Not:A-Brand";v="99", "HeadlessChrome";v="145", "Chromium";v="145"',
            "sec-ch-ua-mobile": "?0",
            "sec-ch-ua-platform": '"Windows"',
            "accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        }

        if next_method == "GET":
            resp = session.get(next_url, headers=req_headers, allow_redirects=False, timeout=30)
        else:
            resp = session.request(next_method, next_url, headers=req_headers, allow_redirects=False, timeout=30)
        current_url = next_url

    raise LoginError(f"重定向超过上限({max_hops})")


def _collect_token_candidates(hops: list[Hop], final_resp: requests.Response, session: requests.Session) -> list[str]:
    candidates: list[str] = []

    for hop in hops:
        q = parse_qs(urlparse(hop.url).query)
        for key in ("token", "uniToken", "access_token"):
            for value in q.get(key, []):
                if value:
                    candidates.append(value)

    for source in [
        *hops,
        Hop(
            method=str(final_resp.request.method or "GET"),
            url=str(final_resp.request.url or ""),
            status=final_resp.status_code,
            location="",
        ),
    ]:
        q = parse_qs(urlparse(source.location).query)
        for key in ("token", "uniToken", "access_token"):
            for value in q.get(key, []):
                if value:
                    candidates.append(value)

    for key in ("token", "Token", "x-auth-token", "X-Auth-Token", "authorization", "Authorization"):
        value = final_resp.headers.get(key, "")
        if value:
            value = value.replace("Bearer ", "").strip()
            if value:
                candidates.append(value)

    for cookie in session.cookies:
        name = cookie.name.lower()
        if "token" in name or "auth" in name:
            if cookie.value:
                candidates.append(cookie.value)

    deduped: list[str] = []
    seen: set[str] = set()
    for item in candidates:
        if item in seen:
            continue
        seen.add(item)
        deduped.append(item)
    return deduped


def _validate_userinfo(session: requests.Session, token_candidates: list[str]) -> tuple[dict[str, Any] | None, str, list[dict[str, Any]]]:
    attempts: list[dict[str, Any]] = []
    base_headers = {
        "accept": "application/json, text/plain, */*",
        "lan": "1",
        "user-agent": DEFAULT_UA,
    }

    for token in token_candidates + [""]:
        headers = dict(base_headers)
        if token:
            headers["token"] = token

        try:
            resp = session.get(USERINFO_API, headers=headers, timeout=30)
            body: Any
            try:
                body = resp.json()
            except Exception:
                body = {"raw": resp.text[:500]}

            ok = bool(resp.status_code == 200 and isinstance(body, dict) and str(body.get("code")) == "0")
            attempts.append({
                "status_code": resp.status_code,
                "token_used": bool(token),
                "token_preview": (token[:16] + "...") if token else "",
                "ok": ok,
                "body": body,
            })

            if ok:
                return body, token, attempts
        except Exception as exc:
            attempts.append({
                "status_code": -1,
                "token_used": bool(token),
                "token_preview": (token[:16] + "...") if token else "",
                "ok": False,
                "error": str(exc),
            })

    return None, "", attempts


def _post_auth_warmup(session: requests.Session, login_url: str) -> list[dict[str, Any]]:
    traces: list[dict[str, Any]] = []
    headers = {
        "content-type": "application/x-www-form-urlencoded",
        "origin": "https://newcas.gzhu.edu.cn",
        "referer": login_url,
        "upgrade-insecure-requests": "1",
        "user-agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/145.0.7632.6 Safari/537.36",
        "sec-ch-ua": '"Not:A-Brand";v="99", "HeadlessChrome";v="145", "Chromium";v="145"',
        "sec-ch-ua-mobile": "?0",
        "sec-ch-ua-platform": '"Windows"',
        "accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    }

    for url in [
        "https://libbooking.gzhu.edu.cn/",
        "https://libbooking.gzhu.edu.cn/ic-web/Language/getLanList",
    ]:
        try:
            resp = session.get(url, headers=headers, timeout=30)
            traces.append({"url": url, "status": resp.status_code})
        except Exception as exc:
            traces.append({"url": url, "status": -1, "error": str(exc)})
    return traces


def login_non_webview(username: str, password: str, service_url: str, max_hops: int = 12) -> dict[str, Any]:
    if not username.strip() or not password.strip():
        raise LoginError("账号和密码不能为空")

    session = requests.Session()
    session.headers.update({"user-agent": DEFAULT_UA})

    to_login_page_url, preflight_trace = _discover_to_login_page_url(session)
    bootstrap = session.get(to_login_page_url, allow_redirects=False, timeout=30)
    bootstrap_location = bootstrap.headers.get("Location", "")

    if bootstrap.status_code in {301, 302, 303, 307, 308} and bootstrap_location:
        login_url = urljoin(to_login_page_url, bootstrap_location)
    else:
        # Fallback to legacy direct service mode when authcenter bootstrap is unavailable.
        login_url = _service_login_url(service_url)

    login_page = session.get(login_url, timeout=30)
    if login_page.status_code != 200:
        raise LoginError(f"获取登录页失败: status={login_page.status_code}")

    form_values = _extract_form_inputs(login_page.text)
    lt = form_values.get("lt", "")
    execution = form_values.get("execution", "")
    event_id = form_values.get("_eventId", "submit") or "submit"

    if not lt or not execution:
        raise LoginError("登录页缺少 lt/execution 字段")

    des_js = session.get(DES_JS_URL, timeout=30).text
    rsa = _compute_rsa_with_node(des_js, f"{username}{password}{lt}")

    payload = {
        "rsa": rsa,
        "ul": str(len(username)),
        "pl": str(len(password)),
        "lt": lt,
        "execution": execution,
        "_eventId": event_id,
    }

    for k, v in form_values.items():
        if k not in payload and k not in {"un", "pd", "username", "password"} and v:
            payload[k] = v

    post_headers = {
        "content-type": "application/x-www-form-urlencoded",
        "origin": "https://newcas.gzhu.edu.cn",
        "referer": login_url,
        "user-agent": DEFAULT_UA,
    }

    login_resp = session.post(login_url, headers=post_headers, data=payload, allow_redirects=False, timeout=30)
    final_resp, hops = _follow_redirects(
        session,
        login_resp,
        max_hops=max_hops,
        fixed_referer=login_url,
        fixed_origin="https://newcas.gzhu.edu.cn",
    )

    final_html_error = _extract_error_text(final_resp.text) if "text/html" in final_resp.headers.get("content-type", "") else ""
    token_candidates = _collect_token_candidates(hops, final_resp, session)

    final_body_preview = ""
    final_body_json: dict[str, Any] | None = None
    try:
        final_body_preview = final_resp.text[:1000]
        maybe_json = final_resp.json()
        if isinstance(maybe_json, dict):
            final_body_json = maybe_json
            data = maybe_json.get("data")
            if isinstance(data, dict):
                for key in ("token", "uniToken", "accessToken", "access_token"):
                    value = data.get(key)
                    if isinstance(value, str) and value:
                        token_candidates.insert(0, value)
    except Exception:
        pass

    # Fallback regex extraction for token-like values in auth/token payload.
    for m in re.findall(r'"(?:token|accessToken|access_token)"\s*:\s*"([^"]+)"', final_body_preview):
        token_candidates.insert(0, m)

    dedup_tokens: list[str] = []
    token_seen: set[str] = set()
    for tk in token_candidates:
        if tk in token_seen:
            continue
        token_seen.add(tk)
        dedup_tokens.append(tk)
    token_candidates = dedup_tokens

    warmup_traces = _post_auth_warmup(session, login_url=login_url)

    userinfo, token, userinfo_attempts = _validate_userinfo(session, token_candidates)

    cookies = {
        c.name: c.value
        for c in session.cookies
        if c.name and c.value
    }

    effective_token = token
    token_source = "userinfo_request_token"
    if isinstance(userinfo, dict):
        data = userinfo.get("data")
        if isinstance(data, dict):
            biz_token = data.get("token")
            if isinstance(biz_token, str) and biz_token:
                effective_token = biz_token
                token_source = "userinfo_data_token"

    result = {
        "ok": userinfo is not None,
        "preflight_trace": preflight_trace,
        "bootstrap_to_login_page": to_login_page_url,
        "bootstrap_location": bootstrap_location,
        "bootstrap_status": bootstrap.status_code,
        "service_url": service_url,
        "login_url": login_url,
        "final_url": final_resp.request.url,
        "final_status": final_resp.status_code,
        "final_response_headers": dict(final_resp.headers),
        "final_html_error": final_html_error,
        "redirect_hops": [asdict(h) for h in hops],
        "token_candidates_count": len(token_candidates),
        "warmup_traces": warmup_traces,
        "final_body_json": final_body_json,
        "final_body_preview": final_body_preview,
        "token": effective_token,
        "token_source": token_source,
        "token_preview": (effective_token[:24] + "...") if effective_token else "",
        "cookies": cookies,
        "cookies_count": len(cookies),
        "form_fields": sorted(form_values.keys()),
        "userinfo": userinfo,
        "userinfo_attempts": userinfo_attempts,
        "created_at_ms": int(time.time() * 1000),
    }

    if userinfo is None:
        short_err = final_html_error or "userInfo 校验未通过"
        result["error"] = short_err

    return result


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="GZHU libbooking CAS login without WebView")
    parser.add_argument("--username", required=True, help="统一认证账号")
    parser.add_argument("--password", required=True, help="统一认证密码")
    parser.add_argument("--service-url", default=SERVICE_URL, help="CAS service 参数")
    parser.add_argument("--max-hops", type=int, default=12, help="最大重定向跟随次数")
    parser.add_argument(
        "--output",
        default="CODE/research/auth/output/non_webview_session.json",
        help="输出 JSON 结果文件",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    out_path = Path(args.output)
    out_path.parent.mkdir(parents=True, exist_ok=True)

    try:
        result = login_non_webview(
            username=args.username,
            password=args.password,
            service_url=args.service_url,
            max_hops=args.max_hops,
        )
    except Exception as exc:
        result = {
            "ok": False,
            "error": str(exc),
            "created_at_ms": int(time.time() * 1000),
        }

    out_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
