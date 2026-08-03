#!/usr/bin/env python3
"""Read-only real-chain API smoke checks through the frontend origin."""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime
from http.cookiejar import CookieJar
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import HTTPCookieProcessor, Request, build_opener


ROOT = Path(__file__).resolve().parents[1]

READ_ONLY_ENDPOINTS = [
    ("auth-current", "/api/auth/current"),
    ("gitlab-configs", "/api/gitlab-sync/configs"),
    ("gitlab-status", "/api/gitlab-sync/status"),
    ("database-tables", "/api/database-browser/tables"),
    ("review-records", "/api/review-data/records?page=1&size=10"),
    ("review-filter-options", "/api/review-data/records/filter-options"),
    ("code-review-records", "/api/code-review/illegal-records?page=1&size=10"),
    ("code-review-filter-options", "/api/code-review/illegal-records/filter-options"),
    ("code-review-status", "/api/code-review/illegal-records/status"),
    ("code-review-multi-board-options", "/api/code-review/multi-board/source-options"),
    ("code-review-multi-board", "/api/code-review/multi-board/overview"),
    ("system-test-defect-summary", "/api/statistic-boards/system-test-defect-summary"),
    ("system-test-delay-analysis", "/api/statistic-boards/system-test-delay-analysis"),
    ("system-test-defect-cause", "/api/statistic-boards/system-test-defect-cause"),
    ("system-test-phase-statistics", "/api/statistic-boards/system-test-phase-statistics"),
    ("system-test-issues", "/api/question-metrics/issues?page=1&size=10"),
    ("system-test-issue-options", "/api/question-metrics/issues/filter-options"),
    ("system-test-illegal-records", "/api/question-metrics/illegal-records?page=1&size=10"),
    ("customer-defect-summary", "/api/statistic-boards/customer-issue-defect-summary"),
    ("customer-defect-cause", "/api/statistic-boards/customer-issue-defect-cause"),
    ("customer-response-efficiency", "/api/statistic-boards/customer-issue-response-efficiency"),
    ("customer-by-function", "/api/statistic-boards/customer-issue-by-function"),
    ("customer-records", "/api/customer-issues/records?page=1&size=10&topic=cc-product"),
    ("customer-record-options", "/api/customer-issues/records/filter-options?topic=cc-product"),
    ("customer-illegal-records", "/api/customer-issues/illegal-records?page=1&size=10"),
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run read-only real-chain API smoke checks.")
    parser.add_argument("--base-url", default="http://localhost:18181", help="Frontend base URL.")
    parser.add_argument("--username", default="admin", help="Login username.")
    parser.add_argument("--password", default="admin123", help="Login password.")
    parser.add_argument("--output-dir", default="", help="Report directory. Defaults to .tmp/api-real-smoke-<timestamp>.")
    return parser.parse_args()


def request_json(opener: Any, base_url: str, path: str, method: str = "GET", body: Any | None = None, headers: dict[str, str] | None = None) -> tuple[int, Any, str]:
    data = None
    request_headers = dict(headers or {})
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        request_headers["Content-Type"] = "application/json"
    request = Request(f"{base_url.rstrip('/')}{path}", data=data, method=method, headers=request_headers)
    with opener.open(request, timeout=30) as response:
        raw = response.read().decode("utf-8", errors="replace")
        try:
            parsed = json.loads(raw) if raw else None
        except json.JSONDecodeError:
            parsed = None
        return response.status, parsed, raw


def csrf_token(cookies: CookieJar) -> str:
    for cookie in cookies:
        if cookie.name == "XSRF-TOKEN":
            return cookie.value
    return ""


def summarize_payload(payload: Any) -> dict[str, Any]:
    if not isinstance(payload, dict):
        return {"type": type(payload).__name__}
    data = payload.get("data")
    summary: dict[str, Any] = {
        "success": payload.get("success"),
        "code": payload.get("code"),
        "message": payload.get("message"),
        "dataType": type(data).__name__,
    }
    if isinstance(data, list):
        summary["dataCount"] = len(data)
    elif isinstance(data, dict):
        for key in ("total", "page", "size", "status", "workspaceKey", "boardKey"):
            if key in data:
                summary[key] = data.get(key)
        if isinstance(data.get("records"), list):
            summary["recordCount"] = len(data["records"])
            summary["total"] = data.get("total")
        if isinstance(data.get("rows"), list):
            summary["rowCount"] = len(data["rows"])
        if isinstance(data.get("summaryCards"), list):
            summary["summaryCardCount"] = len(data["summaryCards"])
    return summary


def main() -> int:
    args = parse_args()
    output_dir = Path(args.output_dir) if args.output_dir else ROOT / ".tmp" / f"api-real-smoke-{datetime.now():%Y%m%d-%H%M%S}"
    output_dir.mkdir(parents=True, exist_ok=True)

    cookies = CookieJar()
    opener = build_opener(HTTPCookieProcessor(cookies))
    report: dict[str, Any] = {
        "generatedAt": datetime.now().isoformat(),
        "baseUrl": args.base_url,
        "results": [],
    }

    try:
        request_json(opener, args.base_url, "/api/auth/current")
        token = csrf_token(cookies)
        headers = {"X-XSRF-TOKEN": token} if token else {}
        login_status, login_payload, _ = request_json(
            opener,
            args.base_url,
            "/api/auth/login",
            method="POST",
            body={"username": args.username, "password": args.password},
            headers=headers,
        )
        report["login"] = {"status": login_status, "csrfTokenPresent": bool(token), "summary": summarize_payload(login_payload)}
    except (HTTPError, URLError, TimeoutError, RuntimeError) as error:
        report["login"] = {"status": getattr(error, "code", 0), "error": str(error)}
        report_path = output_dir / "report.json"
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"report={report_path}")
        print(f"FAIL login {error}", file=sys.stderr)
        return 1

    failures = 0
    for name, path in READ_ONLY_ENDPOINTS:
        try:
            status, payload, raw = request_json(opener, args.base_url, path)
            ok = 200 <= status < 300 and isinstance(payload, dict) and payload.get("success") is True
            if not ok:
                failures += 1
            report["results"].append(
                {
                    "name": name,
                    "path": path,
                    "status": status,
                    "ok": ok,
                    "summary": summarize_payload(payload),
                    "rawPrefix": raw[:300] if not ok else "",
                }
            )
        except HTTPError as error:
            failures += 1
            body = error.read().decode("utf-8", errors="replace")
            report["results"].append({"name": name, "path": path, "status": error.code, "ok": False, "rawPrefix": body[:300]})
        except (URLError, TimeoutError) as error:
            failures += 1
            report["results"].append({"name": name, "path": path, "status": 0, "ok": False, "error": str(error)})

    report["endpointCount"] = len(READ_ONLY_ENDPOINTS)
    report["failedCount"] = failures
    report_path = output_dir / "report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"report={report_path}")
    if failures:
        print(f"FAIL {failures} endpoint(s)")
        for result in report["results"]:
            if not result["ok"]:
                print(f"  {result['name']}: status={result.get('status')} path={result['path']}")
        return 1
    print(f"PASS {len(READ_ONLY_ENDPOINTS)} endpoint(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
