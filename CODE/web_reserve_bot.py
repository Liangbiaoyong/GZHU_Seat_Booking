import argparse
import json
import time
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path

import requests
from playwright.sync_api import sync_playwright

BASE_HOME = "https://libbooking.gzhu.edu.cn/#/ic/home"
RESERVE_API = "https://libbooking.gzhu.edu.cn/ic-web/reserve"
USERINFO_API = "https://libbooking.gzhu.edu.cn/ic-web/auth/userInfo"


@dataclass
class SessionState:
    token: str
    cookies: dict
    user_info: dict


class ReserveBotError(Exception):
    pass


def build_datetime_str(target_date: datetime, hm: str) -> str:
    return f"{target_date.strftime('%Y-%m-%d')} {hm}:00"


def login_and_get_session(username: str, password: str, headless: bool = True, timeout_ms: int = 30000) -> SessionState:
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
            tk = headers.get("token")
            if tk:
                token_holder["token"] = tk

        page.on("request", on_request)

        page.goto(BASE_HOME, wait_until="domcontentloaded")
        page.wait_for_timeout(2500)

        user = page.locator("#un")
        pwd = page.locator("#pd")
        if user.count() == 0 or pwd.count() == 0:
            browser.close()
            raise ReserveBotError("未找到统一认证登录输入框，可能页面结构变更")

        user.first.fill(username)
        pwd.first.fill(password)

        login_btn = page.locator("#index_login_btn")
        if login_btn.count() == 0:
            browser.close()
            raise ReserveBotError("未找到登录按钮 #index_login_btn")

        login_btn.first.click()
        page.wait_for_timeout(7000)
        page.goto(BASE_HOME, wait_until="domcontentloaded")
        page.wait_for_timeout(3500)

        cookies_list = context.cookies()
        cookie_dict = {
            c.get("name", ""): c.get("value", "")
            for c in cookies_list
            if c.get("name") and c.get("domain", "").endswith("gzhu.edu.cn")
        }

        if not token_holder["token"]:
            try:
                token_val = page.evaluate("() => sessionStorage.getItem('token') || localStorage.getItem('token') || ''")
                if token_val:
                    token_holder["token"] = token_val
            except Exception:
                pass

        browser.close()

    if not cookie_dict:
        raise ReserveBotError("登录后未获取到 Cookie")
    if not token_holder["token"]:
        raise ReserveBotError("登录后未捕获到 token，请重试")

    headers = {
        "accept": "application/json, text/plain, */*",
        "lan": "1",
        "token": token_holder["token"],
        "user-agent": "Mozilla/5.0",
    }
    session = requests.Session()
    for k, v in cookie_dict.items():
        session.cookies.set(k, v)

    resp = session.get(USERINFO_API, headers=headers, timeout=30)
    data = resp.json() if resp.content else {}
    if resp.status_code != 200 or data.get("code") not in (0, "0"):
        raise ReserveBotError(f"获取用户信息失败: status={resp.status_code}, body={data}")

    user_info = data.get("data") or {}
    if not user_info.get("accNo"):
        raise ReserveBotError(f"用户信息缺少 accNo: {user_info}")

    return SessionState(token=token_holder["token"], cookies=cookie_dict, user_info=user_info)


def do_reserve(session_state: SessionState, dev_id: int, begin_dt: str, end_dt: str, captcha: str = "") -> dict:
    session = requests.Session()
    for k, v in session_state.cookies.items():
        session.cookies.set(k, v)

    acc_no = int(session_state.user_info["accNo"])

    payload = {
        "sysKind": 8,
        "appAccNo": acc_no,
        "memberKind": 1,
        "resvMember": [acc_no],
        "resvBeginTime": begin_dt,
        "resvEndTime": end_dt,
        "testName": "",
        "captcha": captcha,
        "resvProperty": 0,
        "resvDev": [int(dev_id)],
        "memo": "",
    }

    headers = {
        "accept": "application/json, text/plain, */*",
        "content-type": "application/json;charset=UTF-8",
        "origin": "https://libbooking.gzhu.edu.cn",
        "lan": "1",
        "token": session_state.token,
        "user-agent": "Mozilla/5.0",
    }

    resp = session.post(RESERVE_API, headers=headers, json=payload, timeout=30)
    body = {}
    try:
        body = resp.json()
    except Exception:
        body = {"raw": resp.text}

    return {
        "status_code": resp.status_code,
        "request": {
            "url": RESERVE_API,
            "headers": {k: v for k, v in headers.items() if k.lower() != "token"} | {"token": "***"},
            "payload": payload,
        },
        "response": body,
    }


def save_cookie_jar(path: Path, state: SessionState) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(
            {
                "created_at": int(time.time() * 1000),
                "token": state.token,
                "cookies": state.cookies,
                "user_info": state.user_info,
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Lib booking automation bot")
    sub = parser.add_subparsers(dest="cmd", required=True)

    login_p = sub.add_parser("login-session", help="登录并导出实时 cookie/token")
    login_p.add_argument("--username", required=True)
    login_p.add_argument("--password", required=True)
    login_p.add_argument("--cookie-jar", default="OUTPUT/captcha_session_latest.json")
    login_p.add_argument("--headed", action="store_true")

    once_p = sub.add_parser("reserve-once", help="执行一次预约")
    once_p.add_argument("--username", required=True)
    once_p.add_argument("--password", required=True)
    once_p.add_argument("--start", required=True, help="如 20:00")
    once_p.add_argument("--end", required=True, help="如 22:15")
    once_p.add_argument("--dev-id", required=True, type=int)
    once_p.add_argument("--captcha", default="")
    once_p.add_argument("--date", default="", help="默认明天，格式 YYYY-MM-DD")
    once_p.add_argument("--cookie-jar", default="OUTPUT/captcha_session_latest.json")
    once_p.add_argument("--result-file", default="OUTPUT/reserve_once_result.json")
    once_p.add_argument("--headed", action="store_true")

    return parser.parse_args()


def main() -> None:
    args = parse_args()

    if args.cmd == "login-session":
        state = login_and_get_session(args.username, args.password, headless=not args.headed)
        save_cookie_jar(Path(args.cookie_jar), state)
        print(json.dumps({"ok": True, "cookie_jar": args.cookie_jar, "accNo": state.user_info.get("accNo")}, ensure_ascii=False))
        return

    if args.cmd == "reserve-once":
        target_date = datetime.strptime(args.date, "%Y-%m-%d") if args.date else datetime.now() + timedelta(days=1)
        begin_dt = build_datetime_str(target_date, args.start)
        end_dt = build_datetime_str(target_date, args.end)

        state = login_and_get_session(args.username, args.password, headless=not args.headed)
        save_cookie_jar(Path(args.cookie_jar), state)

        result = do_reserve(
            session_state=state,
            dev_id=args.dev_id,
            begin_dt=begin_dt,
            end_dt=end_dt,
            captcha=args.captcha,
        )

        result["meta"] = {
            "target_date": target_date.strftime("%Y-%m-%d"),
            "start": args.start,
            "end": args.end,
            "dev_id": args.dev_id,
            "acc_no": state.user_info.get("accNo"),
        }

        result_path = Path(args.result_file)
        result_path.parent.mkdir(parents=True, exist_ok=True)
        result_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
