import argparse
import json
import re
import time
import traceback
from pathlib import Path
from urllib.parse import urljoin, urlparse

import requests
from playwright.sync_api import sync_playwright

BASE_URL = "https://libbooking.gzhu.edu.cn/#/ic/home"


def safe_name(value: str) -> str:
    return re.sub(r"[^a-zA-Z0-9._-]", "_", value)


def save_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def save_binary(path: Path, content: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)


def extract_script_urls(html: str, page_url: str) -> list[str]:
    srcs = re.findall(r'<script[^>]+src=["\']([^"\']+)["\']', html, flags=re.IGNORECASE)
    return [urljoin(page_url, src) for src in srcs]


def try_download_scripts(script_urls: list[str], output_dir: Path, cookies: dict[str, str]) -> list[dict]:
    session = requests.Session()
    for key, value in cookies.items():
        session.cookies.set(key, value)

    saved = []
    for index, url in enumerate(script_urls, start=1):
        try:
            resp = session.get(url, timeout=30)
            suffix = Path(urlparse(url).path).suffix or ".js"
            filename = f"static_script_{index:03d}{suffix}"
            target = output_dir / "scripts_static" / filename
            save_binary(target, resp.content)
            saved.append(
                {
                    "url": url,
                    "status": resp.status_code,
                    "path": str(target),
                    "content_type": resp.headers.get("content-type", ""),
                }
            )
        except Exception as exc:
            saved.append({"url": url, "error": str(exc)})
    return saved


def pick_first_visible(page, selectors: list[str]):
    for selector in selectors:
        locator = page.locator(selector)
        try:
            if locator.count() > 0 and locator.first.is_visible():
                return locator.first
        except Exception:
            continue
    return None


def main() -> None:
    parser = argparse.ArgumentParser(description="Capture libbooking web pages and JS resources.")
    parser.add_argument("--username", required=True)
    parser.add_argument("--password", required=True)
    parser.add_argument("--output-root", default="OUTPUT/captures")
    parser.add_argument("--headless", action="store_true")
    parser.add_argument("--timeout-ms", type=int, default=30000)
    args = parser.parse_args()

    ts = int(time.time() * 1000)
    run_dir = Path(args.output_root) / f"capture_{ts}"
    run_dir.mkdir(parents=True, exist_ok=True)

    captured_requests = []
    captured_js = []

    summary = {
        "timestamp": ts,
        "run_dir": str(run_dir),
        "login_clicked": False,
        "final_url": "",
        "cookies": [],
        "local_storage": {},
        "captured_request_count": 0,
        "captured_js_count": 0,
        "static_script_count": 0,
        "error": None,
    }

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=args.headless)
        context = browser.new_context()
        page = context.new_page()
        page.set_default_timeout(args.timeout_ms)

        def on_request(request):
            entry = {
                "method": request.method,
                "url": request.url,
                "headers": request.headers,
            }
            if request.method.upper() in {"POST", "PUT", "PATCH"}:
                try:
                    entry["post_data"] = request.post_data
                except Exception:
                    entry["post_data"] = None
            captured_requests.append(entry)

        def on_response(response):
            url = response.url
            try:
                headers = response.headers
            except Exception:
                headers = {}
            content_type = headers.get("content-type", "")
            is_js = ("javascript" in content_type.lower()) or urlparse(url).path.endswith(".js")
            if not is_js:
                return
            try:
                body = response.body()
                parsed = urlparse(url)
                basename = Path(parsed.path).name or "script.js"
                target_name = safe_name(f"{len(captured_js)+1:03d}_{basename}")
                target = run_dir / "scripts_runtime" / target_name
                save_binary(target, body)
                captured_js.append(
                    {
                        "url": url,
                        "content_type": content_type,
                        "status": response.status,
                        "path": str(target),
                    }
                )
            except Exception as exc:
                captured_js.append({"url": url, "error": str(exc)})

        page.on("request", on_request)
        page.on("response", on_response)

        static_scripts = []
        try:
            page.goto(BASE_URL, wait_until="domcontentloaded")
            page.wait_for_timeout(2500)

            login_html = page.content()
            save_text(run_dir / "login_page.html", login_html)

            user_input = pick_first_visible(
                page,
                [
                    "#un",
                    "input[type='text']",
                    "input[type='tel']",
                    "input[name='username']",
                    "input[name='userName']",
                    "input[placeholder*='账号']",
                    "input[placeholder*='学号']",
                ],
            )
            pass_input = pick_first_visible(
                page,
                [
                    "#pd",
                    "input[type='password']",
                    "input[name='password']",
                    "input[placeholder*='密码']",
                ],
            )

            if user_input and pass_input:
                user_input.fill(args.username)
                pass_input.fill(args.password)

                login_button = pick_first_visible(
                    page,
                    [
                        "#index_login_btn",
                        "button:has-text('登录')",
                        "button:has-text('统一认证登录')",
                        "button[type='submit']",
                        "input[type='submit']",
                        ".el-button--primary",
                    ],
                )
                if login_button:
                    login_button.click()
                    summary["login_clicked"] = True

            page.wait_for_timeout(6000)

            current_url = page.url
            summary["final_url"] = current_url
            logged_html = page.content()
            save_text(run_dir / "post_login_page.html", logged_html)

            page.goto("https://libbooking.gzhu.edu.cn/#/ic/dev/101267074", wait_until="domcontentloaded")
            page.wait_for_timeout(3500)
            seat_html = page.content()
            save_text(run_dir / "seat_page_101267074.html", seat_html)

            cookies_list = context.cookies()
            cookies_dict = {
                c.get("name", ""): c.get("value", "")
                for c in cookies_list
                if c.get("name")
            }
            summary["cookies"] = cookies_list

            try:
                local_storage = page.evaluate(
                    """
                    () => {
                        const result = {};
                        for (let i = 0; i < localStorage.length; i++) {
                            const k = localStorage.key(i);
                            result[k] = localStorage.getItem(k);
                        }
                        return result;
                    }
                    """
                )
            except Exception:
                local_storage = {}
            summary["local_storage"] = local_storage

            static_scripts = try_download_scripts(
                extract_script_urls(login_html, current_url), run_dir, cookies_dict
            )
        except Exception as exc:
            summary["error"] = {
                "message": str(exc),
                "traceback": traceback.format_exc(),
            }
            save_text(run_dir / "capture_error.txt", traceback.format_exc())
        finally:
            summary["captured_request_count"] = len(captured_requests)
            summary["captured_js_count"] = len(captured_js)
            summary["static_script_count"] = len(static_scripts)

            save_text(run_dir / "requests.json", json.dumps(captured_requests, ensure_ascii=False, indent=2))
            save_text(run_dir / "runtime_js_index.json", json.dumps(captured_js, ensure_ascii=False, indent=2))
            save_text(run_dir / "static_js_index.json", json.dumps(static_scripts, ensure_ascii=False, indent=2))
            save_text(run_dir / "session_snapshot.json", json.dumps(summary, ensure_ascii=False, indent=2))

            browser.close()

    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
