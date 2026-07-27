#!/usr/bin/env python3
"""Smoke-test frontend hash routes with mocked API responses.

The script is intentionally backend-independent. It opens real Vite routes in
Chromium, fulfills /api calls with minimal success payloads, and writes a JSON
report that captures blank-screen, route, console-error, and page-error issues.
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

from playwright.sync_api import Page, Route, sync_playwright


DEFAULT_ROUTES = [
    "/",
    "/quality-board/home",
    "/quality-board/rd-quality-board",
    "/quality-board/other-board",
    "/review-data/home",
    "/code-review/home",
    "/code-review/illegal-records",
    "/code-review/illegal-records/rule-config",
    "/code-review/multi-board",
    "/integration-test/home",
    "/question-metrics/home",
    "/question-metrics/multi-board",
    "/question-metrics/delay-analysis",
    "/question-metrics/illegal-records",
    "/question-metrics/defect-cause",
    "/question-metrics/phase-statistics",
    "/question-metrics/issue-search",
    "/customer-issues/home",
    "/customer-issues/illegal-records",
    "/customer-issues/defect-cause",
    "/customer-issues/cc-product-issues",
    "/customer-issues/delay-issues",
    "/customer-issues/response-efficiency",
    "/customer-issues/issue-by-function",
    "/system-settings/mirror-settings",
    "/system-settings/database-browser",
    "/external/code-review-form",
    "/not-found-smoke",
]


def envelope(data: Any) -> dict[str, Any]:
    return {"success": True, "data": data}


def option(value: str) -> dict[str, str]:
    return {"label": value, "value": value}


def empty_list(page: int = 1, size: int = 20) -> dict[str, Any]:
    return {
        "records": [],
        "total": 0,
        "page": page,
        "size": size,
        "sortField": "",
        "sortOrder": "desc",
    }


def filter_options() -> dict[str, Any]:
    return {
        "projectNames": [],
        "moduleNames": [],
        "functionNames": [],
        "testingPhases": [],
        "authorNames": [],
        "assigneeNames": [],
        "issueStates": [],
        "severityLevels": [],
        "priorityLevels": [],
        "bugStatuses": [],
        "categories": [],
        "milestoneTitles": [],
        "illegalReasons": [],
        "reasonCategories": [],
        "requestTypes": [],
        "repositoryNames": [],
        "illegalTypes": [],
        "targetBranches": [],
        "mergedBys": [],
        "reviewOwners": [],
        "reviewTypes": [],
        "reviewExperts": [],
        "problemStatuses": [],
        "reviewCategories": [],
        "problemCategories": [],
    }


def realtime_status(workspace_key: str) -> dict[str, Any]:
    return {
        "workspaceKey": workspace_key,
        "supported": True,
        "status": "READY",
        "message": "Ready",
        "refreshing": False,
        "lastSyncedAt": "2026-06-02T00:00:00",
        "mirrorStatus": "SUCCESS",
        "factStatus": "SUCCESS",
        "sourceTables": [],
        "plannedTasks": 0,
        "unsupportedTables": [],
        "factRefreshPlanned": False,
    }


def rule_explanation() -> dict[str, Any]:
    return {
        "boardKey": "smoke",
        "supported": True,
        "title": "Smoke rule",
        "version": "smoke",
        "scopeDescription": "Smoke route coverage",
        "summary": "Smoke route coverage",
        "flowSteps": [],
        "metricDefinitions": [],
    }


def statistic_board(board_key: str) -> dict[str, Any]:
    return {
        "definition": {
            "boardKey": board_key,
            "title": "Smoke Board",
            "description": "Browser route smoke board",
            "queryTitle": "Filters",
            "queryDescription": "Smoke filters",
            "rowHeaderLabel": "Module",
            "filters": [],
            "columnGroups": [
                {
                    "key": "summary",
                    "label": "Summary",
                    "columns": [
                        {
                            "key": "total",
                            "label": "Total",
                            "drilldown": True,
                            "metricType": "COUNT",
                        }
                    ],
                }
            ],
            "detailColumns": [
                {"key": "title", "label": "Title", "sortable": True, "minWidth": 160}
            ],
            "defaultPageSize": 10,
            "emptyText": "No data",
        },
        "appliedFilters": {},
        "appliedFilterGroup": None,
        "rows": [
            {
                "rowKey": "__total__",
                "rowLabel": "Total",
                "cells": [
                    {
                        "columnKey": "total",
                        "numericValue": 0,
                        "displayValue": "0",
                        "drilldown": True,
                        "detailParams": {},
                    }
                ],
            }
        ],
        "meta": {
            "generatedAt": "2026-06-02T00:00:00",
            "queryDurationMs": 1,
            "rowCount": 1,
            "columnCount": 1,
            "drilldownColumnCount": 1,
        },
    }


def mirror_config() -> dict[str, Any]:
    return {
        "id": 1,
        "name": "Smoke GitLab source",
        "enabled": True,
        "sourceEnabled": True,
        "sourceInstance": "smoke",
        "autoSyncEnabled": False,
        "sourceMode": "DIRECT",
        "whitelistMode": "RECOMMENDED",
        "whitelistTables": [],
        "dbHost": "localhost",
        "dbPort": 5432,
        "dbName": "gitlab",
        "dbUsername": "gitlab",
        "dbPassword": "",
        "compensationIntervalMinutes": 360,
        "compensationScheduleMode": "INTERVAL",
        "compensationTime": "03:30",
        "compensationWindowStart": None,
        "compensationWindowEnd": None,
        "compensationMissedWindowPolicy": "SKIP",
        "fullCompensationEnabled": True,
        "fullCompensationTime": "02:00",
        "syncThreadMode": "CPU_RATIO",
        "syncThreadValue": 0.8,
        "maxSyncThreads": 4,
        "lastFullSyncAt": None,
        "lastIncrementalSyncAt": None,
    }


def mirror_status() -> dict[str, Any]:
    return {
        "config": mirror_config(),
        "currentTask": None,
        "currentStatus": "IDLE",
        "currentMessage": "Idle",
        "currentStartedAt": None,
        "progress": None,
        "logs": [],
        "systemHookUrl": "http://localhost:18080/api/gitlab-sync/system-hook",
        "systemHookRegistration": system_hook_status(),
        "availableProcessors": 4,
        "resolvedSyncThreads": 3,
    }


def system_hook_status() -> dict[str, Any]:
    return {
        "supported": True,
        "configured": True,
        "registered": False,
        "projectId": None,
        "systemHookUrl": "http://localhost:18080/api/gitlab-sync/system-hook",
        "message": "Smoke status",
        "hooks": [],
    }


def table_diagnostics() -> dict[str, Any]:
    return {
        "configId": 1,
        "sourceInstance": "smoke",
        "generatedAt": "2026-06-02T00:00:00",
        "status": "IDLE",
        "message": "Smoke diagnostics",
        "tableCount": 0,
        "dirtyTableCount": 0,
        "pendingTaskCount": 0,
        "runningTaskCount": 0,
        "retryingTaskCount": 0,
        "failedTaskCount": 0,
        "timedOutTaskCount": 0,
        "tables": [],
    }


def api_payload(path: str) -> Any:
    if path == "/api/auth/current":
        return {
            "username": "smoke-admin",
            "displayName": "Smoke Admin",
            "role": "ADMIN",
            "authenticated": True,
        }
    if path in {"/api/auth/login", "/api/auth/logout"}:
        return api_payload("/api/auth/current")

    if path.startswith("/api/statistic-boards/"):
        parts = path.split("/")
        board_key = parts[3] if len(parts) > 3 else "smoke-board"
        if path.endswith("/status") or path.endswith("/refresh"):
            return realtime_status(board_key)
        if path.endswith("/rule-explanation"):
            return rule_explanation()
        if "/details" in path:
            return {
                "title": "Smoke details",
                "description": "Smoke details",
                "columns": [{"key": "title", "label": "Title", "sortable": True}],
                "records": [],
                "total": 0,
                "page": 1,
                "size": 10,
                "sortField": None,
                "sortOrder": None,
            }
        return statistic_board(board_key)

    if path == "/api/review-data/records/filter-options":
        return filter_options()
    if path.startswith("/api/review-data/records/gitlab-context/refresh"):
        return {
            "accepted": True,
            "jobId": 1,
            "status": "SUCCESS",
            "resourceTypes": [],
            "sourceTables": [],
            "plannedTasks": 0,
            "manualFieldsTouched": False,
            "message": "Smoke refresh",
        }
    if path.startswith("/api/review-data/records"):
        payload = empty_list()
        payload["summary"] = {
            "totalRecords": 0,
            "totalProblemItems": 0,
            "averageReviewScalePages": 0,
            "averageProblemCount": 0,
        }
        return payload

    if path == "/api/code-review/illegal-records/filter-options":
        return filter_options()
    if path == "/api/code-review/illegal-records/rule-explanation":
        return rule_explanation()
    if path == "/api/code-review/illegal-records/rule-config/preview":
        return {
            "baseTotal": 0,
            "filteredTotal": 0,
            "deltaCount": 0,
            "retainedRate": 0,
            "samples": [],
        }
    if path == "/api/code-review/illegal-records/status" or path == "/api/code-review/illegal-records/refresh":
        return realtime_status("code-review-illegal-records")
    if path.startswith("/api/code-review/illegal-records"):
        return empty_list()
    if path == "/api/code-review/multi-board/source-options":
        return [option("default")]
    if path == "/api/code-review/multi-board/overview":
        return {
            "source": "default",
            "sourceLabel": "Default",
            "mergeRequestCount": 0,
            "completedCount": 0,
            "pendingCount": 0,
            "averageCommentRate": 0,
            "totalDefectCount": 0,
            "totalAddedLines": 0,
            "defectDensityPerKloc": 0,
            "averageReviewDurationMinutes": 0,
            "averageAddedLines": 0,
            "moduleRows": [],
            "ownerRows": [],
        }

    if path == "/api/integration-tests/project-options":
        return []
    if path == "/api/integration-tests/phase-options":
        return [{"testingPhase": "2026R4", "label": "2026R4", "current": True}]
    if path == "/api/integration-tests/summary":
        return {
            "projectId": None,
            "projectName": None,
            "testingPhase": "2026R4",
            "moduleCount": 0,
            "totalIssueCount": 0,
            "factRefreshedAt": None,
            "rows": [],
        }
    if path == "/api/integration-tests/details":
        return {"records": [], "total": 0, "page": 1, "size": 20, "sortField": "", "sortOrder": "desc"}

    if path.endswith("/filter-options") and (
        path.startswith("/api/question-metrics/") or path.startswith("/api/customer-issues/")
    ):
        return filter_options()
    if path.endswith("/rule-explanation") and (
        path.startswith("/api/question-metrics/") or path.startswith("/api/customer-issues/")
    ):
        return rule_explanation()
    if path.startswith("/api/question-metrics/") or path.startswith("/api/customer-issues/"):
        return empty_list()

    if path == "/api/gitlab-sync/configs":
        return [mirror_config()]
    if path == "/api/gitlab-sync/source-health":
        return []
    if path == "/api/gitlab-sync/status":
        return mirror_status()
    if path == "/api/gitlab-sync/system-hook-registration-status":
        return system_hook_status()
    if path == "/api/gitlab-sync/whitelist-options":
        return []
    if path == "/api/gitlab-sync/table-sync-diagnostics":
        return table_diagnostics()
    if path.startswith("/api/gitlab-sync/"):
        return {"accepted": True, "status": "QUEUED", "action": "QUEUED", "message": "Smoke queued"}

    if path == "/api/database-browser/tables":
        return [
            {
                "tableName": "collect_form_records",
                "label": "Collect form records",
                "syncStatus": "SUCCESS",
                "lastSyncTime": None,
                "tableKind": "LOCAL",
                "refreshable": False,
            }
        ]
    if path == "/api/database-browser/rows":
        return {
            "tableName": "collect_form_records",
            "label": "Collect form records",
            "columns": [{"key": "id", "label": "ID", "sortable": True}],
            "rows": [],
            "total": 0,
            "page": 1,
            "size": 20,
            "sortField": None,
            "sortOrder": None,
            "keyword": None,
            "syncStatus": "SUCCESS",
            "lastSyncTime": None,
            "statusMessage": None,
            "tableKind": "LOCAL",
            "refreshable": False,
        }
    if path == "/api/database-browser/refresh":
        return {
            "accepted": True,
            "runId": 1,
            "status": "QUEUED",
            "message": "Smoke queued",
            "sourceTables": [],
            "plannedTasks": 0,
        }

    if path == "/api/collect-forms/detail":
        return None
    if path.startswith("/api/collect-forms/"):
        return {}
    if path == "/api/issue-scopes/catalogs":
        return []
    if path.startswith("/api/issue-scopes/"):
        return []

    return {}


def fulfill_api(route: Route) -> None:
    parsed = urlparse(route.request.url)
    data = api_payload(parsed.path)
    route.fulfill(
        status=200,
        content_type="application/json",
        body=json.dumps(envelope(data), ensure_ascii=False),
    )


def smoke_route(page: Page, base_url: str, route_path: str, output_dir: Path) -> dict[str, Any]:
    console_errors: list[str] = []
    page_errors: list[str] = []
    api_requests: list[str] = []
    failed_responses: list[dict[str, Any]] = []

    def on_console(message: Any) -> None:
        if message.type == "error":
            console_errors.append(message.text)

    def on_page_error(error: Any) -> None:
        page_errors.append(str(error))

    def on_response(response: Any) -> None:
        if response.status >= 400:
            failed_responses.append({"url": response.url, "status": response.status})

    page.on("console", on_console)
    page.on("pageerror", on_page_error)
    page.on("response", on_response)

    def on_route(route: Route) -> None:
        parsed = urlparse(route.request.url)
        if parsed.path.startswith("/api/"):
            api_requests.append(route.request.url)
            fulfill_api(route)
            return
        route.continue_()

    page.route("**/*", on_route)
    target = f"{base_url.rstrip('/')}/#{route_path}"
    result: dict[str, Any] = {"path": route_path, "targetUrl": target}

    try:
        page.goto(target, wait_until="networkidle", timeout=30_000)
        page.wait_for_selector("#app", state="attached", timeout=10_000)
        page.wait_for_timeout(250)
        app_text = page.locator("#app").inner_text(timeout=5_000).strip()
        body_box = page.locator("body").bounding_box()
        final_url = page.url
        result.update(
            {
                "ok": bool(app_text) and len(app_text) >= 10 and not console_errors and not page_errors,
                "finalUrl": final_url,
                "appTextLength": len(app_text),
                "apiRequestCount": len(api_requests),
                "apiRequests": api_requests,
                "failedResponses": failed_responses,
                "consoleErrors": console_errors,
                "pageErrors": page_errors,
                "bodyBox": body_box,
            }
        )
    except Exception as error:  # noqa: BLE001 - smoke report should capture any browser failure.
        screenshot = output_dir / f"failed-{safe_name(route_path)}.png"
        try:
            page.screenshot(path=str(screenshot), full_page=True)
        except Exception:
            screenshot = None
        result.update(
            {
                "ok": False,
                "error": str(error),
                "finalUrl": page.url,
                "apiRequestCount": len(api_requests),
                "apiRequests": api_requests,
                "failedResponses": failed_responses,
                "consoleErrors": console_errors,
                "pageErrors": page_errors,
                "screenshot": str(screenshot) if screenshot else None,
            }
        )
    finally:
        page.unroute("**/*", on_route)
        page.remove_listener("console", on_console)
        page.remove_listener("pageerror", on_page_error)
        page.remove_listener("response", on_response)

    return result


def safe_name(route_path: str) -> str:
    value = route_path.strip("/") or "root"
    return "".join(char if char.isalnum() else "-" for char in value)[:120]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Smoke-test frontend browser routes.")
    parser.add_argument("--base-url", default="http://localhost:18181", help="Frontend base URL.")
    parser.add_argument("--output-dir", default="", help="Report directory. Defaults to .tmp/browser-smoke-<timestamp>.")
    parser.add_argument("--route", action="append", dest="routes", help="Route path to test. Can be repeated.")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    output_dir = Path(args.output_dir or f".tmp/browser-smoke-{datetime.now().strftime('%Y%m%d-%H%M%S')}")
    output_dir.mkdir(parents=True, exist_ok=True)
    routes = args.routes or DEFAULT_ROUTES

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        context = browser.new_context(viewport={"width": 1366, "height": 900})
        results = []
        for route_path in routes:
            page = context.new_page()
            try:
                results.append(smoke_route(page, args.base_url, route_path, output_dir))
            finally:
                page.close()
        context.close()
        browser.close()

    failed = [item for item in results if not item.get("ok")]
    report = {
        "generatedAt": datetime.now().isoformat(timespec="seconds"),
        "baseUrl": args.base_url,
        "routeCount": len(results),
        "failedCount": len(failed),
        "results": results,
    }
    report_path = output_dir / "report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Wrote {report_path}")
    if failed:
        for item in failed:
            print(f"FAIL {item['path']}: {item.get('error') or item.get('consoleErrors') or item.get('pageErrors')}")
        return 1
    print(f"PASS {len(results)} route(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
