#!/usr/bin/env python3
import argparse
import json
import time
from pathlib import Path
from typing import Any

import requests

from non_webview_login import login_non_webview

USERINFO_API = "https://libbooking.gzhu.edu.cn/ic-web/auth/userInfo"
DEFAULT_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"
)


def check_alive(token: str, cookie_header: str, timeout_sec: int) -> tuple[bool, dict[str, Any]]:
    headers = {
        "accept": "application/json, text/plain, */*",
        "lan": "1",
        "user-agent": DEFAULT_UA,
        "token": token,
        "cookie": cookie_header,
    }
    try:
        resp = requests.get(USERINFO_API, headers=headers, timeout=timeout_sec)
        body: Any
        try:
            body = resp.json()
        except Exception:
            body = {"raw": resp.text[:500]}

        ok = bool(resp.status_code == 200 and isinstance(body, dict) and body.get("code") == 0)
        detail = {
            "status": resp.status_code,
            "ok": ok,
            "body": body,
        }
        return ok, detail
    except Exception as exc:
        return False, {"status": -1, "ok": False, "error": str(exc)}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Monitor libbooking token+cookie lifetime every 30s")
    parser.add_argument("--username", required=True, help="School account")
    parser.add_argument("--password", required=True, help="School password")
    parser.add_argument("--interval", type=int, default=30, help="Polling interval seconds")
    parser.add_argument("--timeout", type=int, default=20, help="HTTP timeout seconds")
    parser.add_argument("--max-minutes", type=int, default=180, help="Maximum monitor duration minutes")
    parser.add_argument(
        "--output",
        default="CODE/research/auth/output/token_cookie_lifetime_result.json",
        help="Result json path",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    started = time.time()
    session = login_non_webview(args.username, args.password, service_url="")
    if not session.get("ok"):
        print("[ERROR] Login failed, cannot start monitor")
        print(json.dumps(session, ensure_ascii=False, indent=2))
        return 2

    token = str(session.get("token") or "").strip()
    cookie_header = str(session.get("cookie_header") or "").strip()
    if not token or not cookie_header:
        print("[ERROR] Missing token or cookie_header from login result")
        return 3

    print(f"[INFO] monitor start token_len={len(token)} cookie_len={len(cookie_header)} interval={args.interval}s")
    rounds = 0
    details: list[dict[str, Any]] = []
    max_seconds = args.max_minutes * 60

    while True:
        now = time.time()
        elapsed = now - started
        if elapsed > max_seconds:
            print("[INFO] monitor reached max duration, stop by limit")
            break

        ok, detail = check_alive(token, cookie_header, args.timeout)
        rounds += 1
        detail["round"] = rounds
        detail["elapsed_seconds"] = round(elapsed, 2)
        details.append(detail)
        print(
            f"[CHECK] round={rounds} elapsed={elapsed:.1f}s "
            f"status={detail.get('status')} ok={detail.get('ok')}"
        )

        if not ok:
            print(f"[END] token+cookie expired after {elapsed:.1f} seconds")
            break

        time.sleep(args.interval)

    result = {
        "started_at": int(started),
        "ended_at": int(time.time()),
        "survival_seconds": round(time.time() - started, 2),
        "checks": details,
        "session_snapshot": {
            "token_preview": token[:12] + "...",
            "cookie_preview": cookie_header[:48] + "...",
        },
    }

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"[INFO] result written: {output_path}")
    print(f"[INFO] final survival_seconds={result['survival_seconds']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
