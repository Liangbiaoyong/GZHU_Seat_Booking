import argparse
import json
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import urlencode

import requests
from playwright.sync_api import sync_playwright

BASE_HOME = "https://libbooking.gzhu.edu.cn/#/ic/home"
USERINFO_API = "https://libbooking.gzhu.edu.cn/ic-web/auth/userInfo"
RESERVE_QUERY_BASE = "https://libbooking.gzhu.edu.cn/ic-web/reserve"


@dataclass
class SessionState:
    token: str
    cookies: dict[str, str]
    user_info: dict[str, Any]


class FlowError(Exception):
    pass


def is_frequency_blocked(message: str) -> bool:
    lower = (message or "").lower()
    keywords = ["请求频繁", "操作频繁", "请稍后", "too frequent", "too many", "rate"]
    return any(k.lower() in lower for k in keywords)


def now_ms() -> int:
    return int(time.time() * 1000)


def login_and_get_session(username: str, password: str, headless: bool, timeout_ms: int) -> SessionState:
    def try_login_once(aggressive_return_home: bool) -> tuple[str, dict[str, str]]:
        token_holder = {"token": ""}

        with sync_playwright() as p:
            browser = p.chromium.launch(headless=headless)
            context = browser.new_context()
            page = context.new_page()
            page.set_default_timeout(timeout_ms)

            def on_request(request):
                try:
                    headers = request.headers
                except Exception:
                    return
                token = headers.get("token", "")
                if token:
                    token_holder["token"] = token

            page.on("request", on_request)

            page.goto(BASE_HOME, wait_until="domcontentloaded")
            page.wait_for_timeout(2500)

            user = page.locator("#un")
            pwd = page.locator("#pd")
            login_btn = page.locator("#index_login_btn")

            if user.count() == 0 or pwd.count() == 0 or login_btn.count() == 0:
                browser.close()
                raise FlowError("自动登录失败：未找到统一认证登录表单元素")

            user.first.fill(username)
            pwd.first.fill(password)
            login_btn.first.click()

            page.wait_for_timeout(7000)
            page.goto(BASE_HOME, wait_until="domcontentloaded")
            page.wait_for_timeout(3500)

            if aggressive_return_home:
                page.goto(BASE_HOME, wait_until="domcontentloaded")
                page.wait_for_timeout(2800)

            if not token_holder["token"]:
                try:
                    token = page.evaluate("() => sessionStorage.getItem('token') || localStorage.getItem('token') || ''")
                except Exception:
                    token = ""
                if token:
                    token_holder["token"] = token

            cookies_list = context.cookies()
            cookie_dict = {
                item.get("name", ""): item.get("value", "")
                for item in cookies_list
                if item.get("name") and item.get("domain", "").endswith("gzhu.edu.cn")
            }

            browser.close()
            return token_holder["token"], cookie_dict

    last_error = ""
    for aggressive in (True, False, True):
        try:
            token, cookie_dict = try_login_once(aggressive)
            if not cookie_dict:
                last_error = "自动登录失败：未获取到 Cookie"
                continue
            if not token:
                last_error = "自动登录失败：未获取到 token"
                continue

            session = requests.Session()
            for k, v in cookie_dict.items():
                session.cookies.set(k, v)

            headers = {
                "accept": "application/json, text/plain, */*",
                "lan": "1",
                "token": token,
                "user-agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36 Edg/145.0.0.0",
            }
            resp = session.get(USERINFO_API, headers=headers, timeout=30)
            try:
                body = resp.json()
            except Exception:
                body = {"raw": resp.text}

            if resp.status_code != 200 or body.get("code") not in (0, "0"):
                last_error = f"会话校验失败: http={resp.status_code}, body={body}"
                continue

            data = body.get("data") or {}
            return SessionState(token=token, cookies=cookie_dict, user_info=data)
        except Exception as exc:
            last_error = str(exc)

    raise FlowError(last_error or "自动登录失败")


def call_reserve_query(
    session_state: SessionState,
    room_id: int,
    resv_date: str,
    sys_kind: int,
    timeout_s: int,
) -> dict[str, Any]:
    session = requests.Session()
    for k, v in session_state.cookies.items():
        session.cookies.set(k, v)

    params = {
        "roomIds": str(room_id),
        "resvDates": resv_date,
        "sysKind": str(sys_kind),
    }
    query_url = f"{RESERVE_QUERY_BASE}?{urlencode(params)}"

    headers = {
        "accept": "application/json, text/plain, */*",
        "accept-language": "en,zh-CN;q=0.9,zh;q=0.8,en-GB;q=0.7,en-US;q=0.6",
        "accept-encoding": "gzip, deflate, br, zstd",
        "cache-control": "no-cache",
        "pragma": "no-cache",
        "connection": "keep-alive",
        "host": "libbooking.gzhu.edu.cn",
        "lan": "1",
        "token": session_state.token,
        "sec-fetch-dest": "empty",
        "sec-fetch-mode": "cors",
        "sec-fetch-site": "same-origin",
        "sec-ch-ua": '"Not:A-Brand";v="99", "Microsoft Edge";v="145", "Chromium";v="145"',
        "sec-ch-ua-mobile": "?0",
        "sec-ch-ua-platform": '"Windows"',
        "user-agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36 Edg/145.0.0.0",
    }

    cookie_header = "; ".join(f"{k}={v}" for k, v in session_state.cookies.items())
    headers_full = dict(headers)
    headers_full["cookie"] = cookie_header

    resp = session.get(query_url, headers=headers_full, timeout=timeout_s)

    text = resp.text
    parsed = None
    try:
        parsed = resp.json()
    except Exception:
        parsed = {"raw": text}

    message = ""
    if isinstance(parsed, dict):
        message = str(parsed.get("message", ""))

    return {
        "status_code": resp.status_code,
        "url": query_url,
        "request_headers": {k: ("***" if k.lower() in {"token", "cookie"} else v) for k, v in headers_full.items()},
        "request_headers_full": headers_full,
        "response_headers": dict(resp.headers),
        "response_json": parsed,
        "response_text_preview": text[:600],
        "message": message,
    }


def run_with_retry(
    session_state: SessionState,
    room_id: int,
    resv_date: str,
    sys_kind: int,
    timeout_s: int,
    retries: int,
) -> dict[str, Any]:
    attempts: list[dict[str, Any]] = []

    for idx in range(retries + 1):
        result = call_reserve_query(
            session_state=session_state,
            room_id=room_id,
            resv_date=resv_date,
            sys_kind=sys_kind,
            timeout_s=timeout_s,
        )
        result["attempt"] = idx + 1
        attempts.append(result)

        should_retry = False
        if result["status_code"] >= 500:
            should_retry = True
        if is_frequency_blocked(result.get("message", "")):
            should_retry = True

        if not should_retry:
            break

        time.sleep((idx + 1) * 1.2)

    return {
        "attempts": attempts,
        "final": attempts[-1] if attempts else {},
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="自动登录后调用 reserve?roomIds=... 接口测试")
    parser.add_argument("--username", required=True)
    parser.add_argument("--password", required=True)
    parser.add_argument("--room-id", type=int, default=100647014)
    parser.add_argument("--resv-date", default="20260303", help="格式 YYYYMMDD")
    parser.add_argument("--sys-kind", type=int, default=8)
    parser.add_argument("--timeout", type=int, default=30)
    parser.add_argument("--retries", type=int, default=3)
    parser.add_argument("--headed", action="store_true")
    parser.add_argument("--output", default="OUTPUT/reserve_roomids_test_result.json")
    args = parser.parse_args()

    start = now_ms()
    result: dict[str, Any] = {
        "meta": {
            "start_ms": start,
            "room_id": args.room_id,
            "resv_date": args.resv_date,
            "sys_kind": args.sys_kind,
            "retries": args.retries,
            "headless": not args.headed,
        }
    }

    try:
        session_state = login_and_get_session(
            username=args.username,
            password=args.password,
            headless=not args.headed,
            timeout_ms=30000,
        )

        cookie_jar_path = Path("OUTPUT/captcha_session_latest.json")
        cookie_jar_path.parent.mkdir(parents=True, exist_ok=True)
        cookie_jar_path.write_text(
            json.dumps(
                {
                    "created_at": now_ms(),
                    "token": session_state.token,
                    "cookies": session_state.cookies,
                    "user_info": session_state.user_info,
                },
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )

        flow_result = run_with_retry(
            session_state=session_state,
            room_id=args.room_id,
            resv_date=args.resv_date,
            sys_kind=args.sys_kind,
            timeout_s=args.timeout,
            retries=args.retries,
        )

        result["ok"] = True
        result["session"] = {
            "accNo": session_state.user_info.get("accNo"),
            "token_len": len(session_state.token),
            "cookie_keys": sorted(list(session_state.cookies.keys())),
        }
        result["flow"] = flow_result

    except Exception as exc:
        result["ok"] = False
        result["error"] = str(exc)

    finally:
        end = now_ms()
        result["meta"]["end_ms"] = end
        result["meta"]["cost_ms"] = end - start

        output_path = Path(args.output)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")

        print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
