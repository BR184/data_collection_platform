#!/usr/bin/env python3
"""Smoke-test frontend hash routes against the real backend.

Unlike browser_route_smoke.py, this script does not mock /api. It logs in
through the frontend origin so Vite's proxy, backend auth, CSRF, cookies, and
database-backed handlers are all part of the check.
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime
from pathlib import Path
from typing import Any

from browser_route_smoke import DEFAULT_ROUTES, safe_name
from playwright.sync_api import BrowserContext, Page, sync_playwright


ROOT = Path(__file__).resolve().parents[1]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Smoke-test browser routes against real backend APIs.")
    parser.add_argument("--base-url", default="http://localhost:18181", help="Frontend base URL.")
    parser.add_argument("--username", default="admin", help="Login username.")
    parser.add_argument("--password", default="admin123", help="Login password.")
    parser.add_argument("--output-dir", default="", help="Report directory. Defaults to .tmp/browser-real-smoke-<timestamp>.")
    parser.add_argument("--route", action="append", dest="routes", help="Route path to test. Can be repeated.")
    parser.add_argument("--timeout-ms", type=int, default=20000, help="Per-route navigation timeout.")
    return parser.parse_args()


def login(context: BrowserContext, base_url: str, username: str, password: str) -> dict[str, Any]:
    current = context.request.get(f"{base_url.rstrip('/')}/api/auth/current", timeout=10000)
    if not current.ok:
        return {"success": False, "status": current.status, "message": current.text()}
    csrf = ""
    for cookie in context.cookies(base_url):
        if cookie.get("name") == "XSRF-TOKEN":
            csrf = cookie.get("value", "")
            break
    if not csrf:
        return {"success": False, "status": 0, "message": "Missing XSRF-TOKEN cookie"}
    login_response = context.request.post(
        f"{base_url.rstrip('/')}/api/auth/login",
        data={"username": username, "password": password},
        headers={"X-XSRF-TOKEN": csrf},
        timeout=10000,
    )
    if not login_response.ok:
        return {"success": False, "status": login_response.status, "message": login_response.text()}
    return {"success": True, "status": login_response.status, "data": login_response.json()}


def smoke_route(page: Page, base_url: str, route_path: str, output_dir: Path, timeout_ms: int) -> dict[str, Any]:
    console_errors: list[str] = []
    page_errors: list[str] = []
    failed_responses: list[dict[str, Any]] = []

    def on_console(message: Any) -> None:
        if message.type == "error":
            console_errors.append(message.text)

    def on_page_error(error: Any) -> None:
        page_errors.append(str(error))

    def on_response(response: Any) -> None:
        url = response.url
        status = response.status
        if "/api/" in url and status >= 400:
            failed_responses.append({"url": url, "status": status})

    page.on("console", on_console)
    page.on("pageerror", on_page_error)
    page.on("response", on_response)

    target = f"{base_url.rstrip('/')}/#{route_path}"
    result: dict[str, Any] = {"path": route_path, "targetUrl": target}
    try:
        page.goto(target, wait_until="domcontentloaded", timeout=timeout_ms)
        page.wait_for_timeout(2500)
        app_text = page.locator("#app").inner_text(timeout=5000).strip()
        result.update(
            {
                "ok": bool(app_text) and not console_errors and not page_errors and not failed_responses,
                "appTextLength": len(app_text),
                "title": page.title(),
                "url": page.url,
                "consoleErrors": console_errors,
                "pageErrors": page_errors,
                "failedResponses": failed_responses,
            }
        )
    except Exception as error:  # noqa: BLE001 - report any browser failure.
        screenshot = output_dir / f"failed-{safe_name(route_path)}.png"
        try:
            page.screenshot(path=str(screenshot), full_page=True)
        except Exception:
            screenshot = None
        result.update(
            {
                "ok": False,
                "error": str(error),
                "consoleErrors": console_errors,
                "pageErrors": page_errors,
                "failedResponses": failed_responses,
                "screenshot": str(screenshot) if screenshot else "",
            }
        )
    finally:
        page.remove_listener("console", on_console)
        page.remove_listener("pageerror", on_page_error)
        page.remove_listener("response", on_response)
    return result


def main() -> int:
    args = parse_args()
    output_dir = Path(args.output_dir) if args.output_dir else ROOT / ".tmp" / f"browser-real-smoke-{datetime.now():%Y%m%d-%H%M%S}"
    output_dir.mkdir(parents=True, exist_ok=True)
    routes = args.routes or DEFAULT_ROUTES

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        context = browser.new_context(ignore_https_errors=True)
        login_result = login(context, args.base_url, args.username, args.password)
        results: list[dict[str, Any]] = []
        if login_result.get("success"):
            page = context.new_page()
            for route_path in routes:
                results.append(smoke_route(page, args.base_url, route_path, output_dir, args.timeout_ms))
            page.close()
        context.close()
        browser.close()

    report = {
        "generatedAt": datetime.now().isoformat(),
        "baseUrl": args.base_url,
        "routeCount": len(routes),
        "login": login_result,
        "results": results,
        "failedCount": len([result for result in results if not result.get("ok")]),
    }
    report_path = output_dir / "report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"report={report_path}")
    if not login_result.get("success"):
        print(f"FAIL login status={login_result.get('status')} message={login_result.get('message')}", file=sys.stderr)
        return 1
    failed = [result for result in results if not result.get("ok")]
    if failed:
        print(f"FAIL {len(failed)} route(s)")
        for result in failed:
            print(f"  {result['path']}: {result.get('error') or result.get('failedResponses') or result.get('consoleErrors')}")
        return 1
    print(f"PASS {len(results)} route(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
