#!/usr/bin/env python3
"""Compare old-platform issue rows with new-platform issue facts.

The script shells through Docker instead of using local database drivers so it
can run in the same offline tooling profile as the rest of the smoke suite.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]


@dataclass(frozen=True)
class CommandResult:
    command: list[str]
    stdout: str
    stderr: str
    returncode: int


FIELD_DEFAULTS = {
    "moduleName": "未设定模块",
    "testingPhase": "未设定测试阶段",
    "severityLevel": "未设定严重程度",
    "urgency": "未设定紧急程度",
    "bugStatus": "未设定议题状态",
}

STATUS_ALIASES = {
    "opened": "OPEN",
    "open": "OPEN",
    "closed": "CLOSE",
    "close": "CLOSE",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Compare old-platform spider_issue_data to new-platform issue_fact.")
    parser.add_argument("--mysql-container", default="spidergitdata-mysql")
    parser.add_argument("--mysql-db", default="gitlab_spider")
    parser.add_argument("--mysql-user", default="root")
    parser.add_argument("--mysql-password", default="root")
    parser.add_argument("--platform-container", default="dcp-local-test-postgres-1")
    parser.add_argument("--platform-db", default="qaflex")
    parser.add_argument("--platform-user", default="qaflex")
    parser.add_argument("--source-instance", default="cc")
    parser.add_argument("--project-id", type=int, required=True)
    parser.add_argument("--output", default="", help="Optional JSON report path.")
    parser.add_argument("--max-diffs", type=int, default=20, help="Maximum diff rows printed to stdout.")
    return parser.parse_args()


def run(command: list[str]) -> CommandResult:
    completed = subprocess.run(command, text=True, capture_output=True, encoding="utf-8", errors="replace")
    return CommandResult(command, completed.stdout.strip(), completed.stderr.strip(), completed.returncode)


def require_success(result: CommandResult) -> str:
    if result.returncode != 0:
        joined = " ".join(result.command)
        raise RuntimeError(f"Command failed ({result.returncode}): {joined}\n{result.stderr}\n{result.stdout}")
    return result.stdout


def parse_json_output(output: str) -> list[dict[str, Any]]:
    if not output:
        return []
    return json.loads(output)


def mysql_query(args: argparse.Namespace, sql: str) -> list[dict[str, Any]]:
    command = [
        "docker",
        "exec",
        args.mysql_container,
        "mysql",
        f"-u{args.mysql_user}",
        f"-p{args.mysql_password}",
        "--default-character-set=utf8mb4",
        "-N",
        "-B",
        "-e",
        sql,
    ]
    return parse_json_output(require_success(run(command)))


def platform_query(args: argparse.Namespace, sql: str) -> list[dict[str, Any]]:
    command = [
        "docker",
        "exec",
        args.platform_container,
        "psql",
        "-U",
        args.platform_user,
        "-d",
        args.platform_db,
        "-t",
        "-A",
        "-c",
        sql,
    ]
    return parse_json_output(require_success(run(command)))


def issue_iid_from_reference(reference: Any) -> int | None:
    if reference is None:
        return None
    match = re.search(r"\d+", str(reference))
    if not match:
        return None
    return int(match.group(0))


def old_rows(args: argparse.Namespace) -> list[dict[str, Any]]:
    sql = f"""
SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT(
  'projectId', project_id,
  'issueIid', issue_iid,
  'reference', issuable_reference,
  'title', issue_title,
  'moduleName', module_name,
  'testingPhase', testing_phase,
  'severityLevel', severity_level,
  'urgency', urgency,
  'status', status,
  'bugStatus', bug_status,
  'assignee', assignee,
  'author', author,
  'milestone', milestone
)), JSON_ARRAY())
FROM (
  SELECT
    project_id,
    CAST(REGEXP_REPLACE(COALESCE(issuable_reference, ''), '[^0-9]', '') AS UNSIGNED) AS issue_iid,
    issuable_reference,
    issue_title,
    module_name,
    testing_phase,
    severity_level,
    urgency,
    status,
    bug_status,
    assignee,
    author,
    milestone
  FROM {args.mysql_db}.spider_issue_data
  WHERE project_id = '{args.project_id}'
  ORDER BY issue_iid
) t;
"""
    rows = mysql_query(args, sql)
    for row in rows:
        row["issueIid"] = row.get("issueIid") or issue_iid_from_reference(row.get("reference"))
    return rows


def new_rows(args: argparse.Namespace) -> list[dict[str, Any]]:
    sql = f"""
SELECT COALESCE(JSON_AGG(ROW_TO_JSON(t) ORDER BY "issueIid")::text, '[]')
FROM (
  SELECT
    project_id AS "projectId",
    issue_iid AS "issueIid",
    title,
    module_name AS "moduleName",
    testing_phase AS "testingPhase",
    severity_level AS "severityLevel",
    urgency,
    issue_state AS status,
    bug_status AS "bugStatus",
    assignee_name AS assignee,
    author_name AS author,
    milestone_title AS milestone
  FROM issue_fact
  WHERE source_instance = '{args.source_instance}'
    AND project_id = {args.project_id}
    AND deleted = false
) t;
"""
    return platform_query(args, sql)


def normalize_value(field: str, value: Any) -> str | None:
    if value is None:
        return FIELD_DEFAULTS.get(field)
    text = str(value).strip()
    if text == "":
        return FIELD_DEFAULTS.get(field)
    if field == "status":
        return STATUS_ALIASES.get(text.lower(), text)
    return text


def keyed(rows: list[dict[str, Any]]) -> dict[int, dict[str, Any]]:
    return {int(row["issueIid"]): row for row in rows if row.get("issueIid") is not None}


def compare_fields(old: dict[str, Any], new: dict[str, Any]) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    fields = [
        "title",
        "moduleName",
        "testingPhase",
        "severityLevel",
        "urgency",
        "status",
        "bugStatus",
        "assignee",
        "author",
        "milestone",
    ]
    strict_diffs = []
    display_diffs = []
    for field in fields:
        old_value = old.get(field)
        new_value = new.get(field)
        if old_value != new_value:
            strict_diffs.append({"field": field, "old": old_value, "new": new_value})
        normalized_old = normalize_value(field, old_value)
        normalized_new = normalize_value(field, new_value)
        if normalized_old != normalized_new:
            display_diffs.append({"field": field, "old": normalized_old, "new": normalized_new})
    return strict_diffs, display_diffs


def build_report(args: argparse.Namespace, old: list[dict[str, Any]], new: list[dict[str, Any]]) -> dict[str, Any]:
    old_by_iid = keyed(old)
    new_by_iid = keyed(new)
    old_iids = set(old_by_iid)
    new_iids = set(new_by_iid)
    common_iids = sorted(old_iids & new_iids)

    row_diffs = []
    strict_mismatch_count = 0
    display_mismatch_count = 0
    strict_field_counts: dict[str, int] = {}
    display_field_counts: dict[str, int] = {}
    for iid in common_iids:
        strict_diffs, display_diffs = compare_fields(old_by_iid[iid], new_by_iid[iid])
        if strict_diffs:
            strict_mismatch_count += 1
            for diff in strict_diffs:
                strict_field_counts[diff["field"]] = strict_field_counts.get(diff["field"], 0) + 1
        if display_diffs:
            display_mismatch_count += 1
            for diff in display_diffs:
                display_field_counts[diff["field"]] = display_field_counts.get(diff["field"], 0) + 1
        if strict_diffs or display_diffs:
            row_diffs.append(
                {
                    "issueIid": iid,
                    "strictDiffs": strict_diffs,
                    "displayDiffs": display_diffs,
                    "oldTitle": old_by_iid[iid].get("title"),
                    "newTitle": new_by_iid[iid].get("title"),
                }
            )

    identity_passed = old_iids == new_iids and len(old) == len(new)
    display_passed = identity_passed and display_mismatch_count == 0
    strict_passed = identity_passed and strict_mismatch_count == 0
    return {
        "source": {
            "mysqlContainer": args.mysql_container,
            "mysqlDb": args.mysql_db,
            "platformContainer": args.platform_container,
            "platformDb": args.platform_db,
            "sourceInstance": args.source_instance,
            "projectId": args.project_id,
        },
        "counts": {"oldPlatform": len(old), "newPlatform": len(new)},
        "identity": {
            "passed": identity_passed,
            "missingInNew": sorted(old_iids - new_iids),
            "extraInNew": sorted(new_iids - old_iids),
        },
        "fieldComparison": {
            "strictPassed": strict_passed,
            "displayNormalizedPassed": display_passed,
            "strictMismatchRows": strict_mismatch_count,
            "displayMismatchRows": display_mismatch_count,
            "strictFieldMismatchCounts": strict_field_counts,
            "displayFieldMismatchCounts": display_field_counts,
            "diffs": row_diffs,
        },
        "passed": display_passed,
    }


def printable_report(report: dict[str, Any], max_diffs: int) -> dict[str, Any]:
    printable = json.loads(json.dumps(report, ensure_ascii=False))
    diffs = printable["fieldComparison"]["diffs"]
    printable["fieldComparison"]["diffs"] = diffs[:max_diffs]
    printable["fieldComparison"]["diffsTruncated"] = max(0, len(diffs) - max_diffs)
    return printable


def main() -> int:
    args = parse_args()
    old = old_rows(args)
    new = new_rows(args)
    report = build_report(args, old, new)
    text = json.dumps(report, ensure_ascii=False, indent=2)
    if args.output:
        output_path = Path(args.output)
        if not output_path.is_absolute():
            output_path = ROOT / output_path
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(text + "\n", encoding="utf-8")
        print(f"report written: {output_path}")
    print(json.dumps(printable_report(report, args.max_diffs), ensure_ascii=False, indent=2))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
