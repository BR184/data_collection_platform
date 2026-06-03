#!/usr/bin/env python3
"""Compare old-platform integration rows with new-platform facts.

The comparison reads the old MySQL materialized table
``spider_integration_data`` and the new PostgreSQL ``integration_test_fact``
table through Docker CLIs. It compares stable business keys and reports both
fact-level gaps and page-visible gaps, so synced-but-hidden rows are explicit.
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

KEY_FIELDS = ["sourceInstance", "testingPhase", "issueIid", "moduleName", "functionName"]
COMPARE_FIELDS = [
    "title",
    "executor",
    "executeCase",
    "passCase",
    "notPassCase",
    "notPassCaseNow",
    "problemCase",
    "exceptionCount",
    "passRate",
    "legal",
]


@dataclass(frozen=True)
class CommandResult:
    command: list[str]
    stdout: str
    stderr: str
    returncode: int


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Compare old-platform spider_integration_data to new-platform integration_test_fact."
    )
    parser.add_argument("--mysql-container", default="spidergitdata-mysql")
    parser.add_argument("--mysql-db", default="gitlab_spider")
    parser.add_argument("--mysql-user", default="root")
    parser.add_argument("--mysql-password", default="root")
    parser.add_argument("--platform-container", default="dcp-local-test-postgres-1")
    parser.add_argument("--platform-db", default="qaflex")
    parser.add_argument("--platform-user", default="qaflex")
    parser.add_argument("--source-instance", default="cc")
    parser.add_argument("--testing-phase", required=True)
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


def sql_string(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def issue_iid_from_reference(reference: Any) -> int | None:
    if reference is None:
        return None
    match = re.search(r"\d+", str(reference))
    if not match:
        return None
    return int(match.group(0))


def old_rows(args: argparse.Namespace) -> list[dict[str, Any]]:
    where = f"testing_phase = {sql_string(args.testing_phase)}"
    sql = f"""
SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT(
  'sourceInstance', {sql_string(args.source_instance)},
  'testingPhase', testing_phase,
  'projectId', project_id,
  'issueIid', issue_iid,
  'reference', issuable_reference,
  'moduleName', module_name,
  'functionName', cc_function,
  'title', issue_title,
  'executor', executor,
  'executeCase', execute_case,
  'passCase', pass_case,
  'notPassCase', not_pass_case,
  'notPassCaseNow', not_pass_case_now,
  'problemCase', problem_case,
  'exceptionCount', exception,
  'passRate', pass_rate
)), JSON_ARRAY())
FROM (
  SELECT
    id,
    null as project_id,
    CAST(REGEXP_REPLACE(COALESCE(issuable_reference, ''), '[^0-9]', '') AS UNSIGNED) AS issue_iid,
    issuable_reference,
    issue_title,
    module_name,
    testing_phase,
    cc_function,
    executor,
    execute_case,
    pass_case,
    not_pass_case,
    not_pass_case_now,
    problem_case,
    exception,
    pass_rate
  FROM {args.mysql_db}.spider_integration_data
  WHERE {where}
  ORDER BY issue_iid, module_name, cc_function, id
) t;
"""
    rows = mysql_query(args, sql)
    for row in rows:
        row["issueIid"] = row.get("issueIid") or issue_iid_from_reference(row.get("reference"))
        row["legal"] = old_legal(row)
    return rows


def new_rows(args: argparse.Namespace, page_visible: bool) -> list[dict[str, Any]]:
    filters = [
        f"lower(coalesce(source_instance, 'default')) = {sql_string(normalize_source_instance(args.source_instance))}",
        f"testing_phase = {sql_string(args.testing_phase)}",
        "deleted = false",
    ]
    if page_visible:
        filters.append("nullif(btrim(coalesce(module_name, '')), '') is not null")
    where = " and ".join(filters)
    sql = f"""
SELECT COALESCE(JSON_AGG(ROW_TO_JSON(t) ORDER BY "issueIid", "moduleName", "functionName")::text, '[]')
FROM (
  SELECT
    source_instance AS "sourceInstance",
    testing_phase AS "testingPhase",
    project_id AS "projectId",
    issue_iid AS "issueIid",
    issuable_reference AS "reference",
    module_name AS "moduleName",
    function_name AS "functionName",
    title,
    executor,
    execute_case AS "executeCase",
    pass_case AS "passCase",
    not_pass_case AS "notPassCase",
    not_pass_case_now AS "notPassCaseNow",
    problem_case AS "problemCase",
    exception_count AS "exceptionCount",
    pass_rate AS "passRate",
    legal
  FROM integration_test_fact
  WHERE {where}
) t;
"""
    return platform_query(args, sql)


def normalize_source_instance(raw: str) -> str:
    normalized = re.sub(r"[^a-z0-9_]", "_", (raw or "").strip().lower())
    normalized = re.sub(r"_+", "_", normalized).strip("_")
    return normalized or "default"


def old_legal(row: dict[str, Any]) -> bool:
    execute_case = to_int(row.get("executeCase"))
    pass_case = to_int(row.get("passCase"))
    not_pass_case_now = to_int(row.get("notPassCaseNow"))
    values = [
        row.get("executeCase"),
        row.get("passCase"),
        row.get("notPassCase"),
        row.get("notPassCaseNow"),
        row.get("problemCase"),
        row.get("exceptionCount"),
    ]
    if any(to_int(value) is None or to_int(value) < 0 for value in values):
        return False
    return execute_case == pass_case + not_pass_case_now


def to_int(value: Any) -> int | None:
    if value is None or value == "":
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def row_key(row: dict[str, Any]) -> tuple[str, ...]:
    return tuple(normalize_value(field, row.get(field)) for field in KEY_FIELDS)


def normalize_value(field: str, value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    if field == "sourceInstance":
        return normalize_source_instance(str(value))
    return str(value).strip()


def normalize_compare_value(field: str, value: Any) -> str:
    if field in {
        "executeCase",
        "passCase",
        "notPassCase",
        "notPassCaseNow",
        "problemCase",
        "exceptionCount",
    }:
        parsed = to_int(value)
        return "" if parsed is None else str(parsed)
    if field == "passRate":
        if value is None or value == "":
            return ""
        try:
            return f"{float(value):.2f}"
        except (TypeError, ValueError):
            return str(value).strip()
    if field == "legal":
        if isinstance(value, bool):
            return "true" if value else "false"
        return str(value).strip().lower()
    return normalize_value(field, value)


def keyed(rows: list[dict[str, Any]]) -> dict[tuple[str, ...], dict[str, Any]]:
    result: dict[tuple[str, ...], dict[str, Any]] = {}
    for row in rows:
        key = row_key(row)
        if key[0] and key[1] and key[2]:
            result.setdefault(key, row)
    return result


def compare(old: list[dict[str, Any]], new: list[dict[str, Any]]) -> dict[str, Any]:
    old_by_key = keyed(old)
    new_by_key = keyed(new)
    old_keys = set(old_by_key)
    new_keys = set(new_by_key)
    changed: list[dict[str, Any]] = []
    field_counts: dict[str, int] = {}
    for key in sorted(old_keys & new_keys):
        old_row = old_by_key[key]
        new_row = new_by_key[key]
        diffs = []
        for field in COMPARE_FIELDS:
            old_value = normalize_compare_value(field, old_row.get(field))
            new_value = normalize_compare_value(field, new_row.get(field))
            if old_value != new_value:
                diffs.append({"field": field, "old": old_value, "new": new_value})
                field_counts[field] = field_counts.get(field, 0) + 1
        if diffs:
            changed.append({
                **{field: normalize_value(field, old_row.get(field)) for field in KEY_FIELDS},
                "diffs": diffs,
            })
    return {
        "missingInNew": [format_key(key) for key in sorted(old_keys - new_keys)],
        "extraInNew": [format_key(key) for key in sorted(new_keys - old_keys)],
        "changed": changed,
        "fieldMismatchCounts": field_counts,
    }


def format_key(key: tuple[str, ...]) -> dict[str, str]:
    return dict(zip(KEY_FIELDS, key, strict=True))


def build_report(
    args: argparse.Namespace,
    old: list[dict[str, Any]],
    new_all: list[dict[str, Any]],
    new_visible: list[dict[str, Any]],
) -> dict[str, Any]:
    fact_diff = compare(old, new_all)
    visible_diff = compare(old, new_visible)
    new_all_by_key = keyed(new_all)
    new_visible_keys = set(keyed(new_visible))
    hidden_after_sync = [format_key(key) for key in sorted(set(new_all_by_key) - new_visible_keys)]
    fact_passed = not fact_diff["missingInNew"] and not fact_diff["extraInNew"] and not fact_diff["changed"]
    visible_passed = (
        not visible_diff["missingInNew"] and not visible_diff["extraInNew"] and not visible_diff["changed"]
    )
    return {
        "source": {
            "mysqlContainer": args.mysql_container,
            "mysqlDb": args.mysql_db,
            "platformContainer": args.platform_container,
            "platformDb": args.platform_db,
            "sourceInstance": normalize_source_instance(args.source_instance),
            "testingPhase": args.testing_phase,
        },
        "counts": {
            "oldPlatform": len(old),
            "newFacts": len(new_all),
            "newPageVisibleFacts": len(new_visible),
            "newHiddenAfterSync": len(hidden_after_sync),
        },
        "factComparison": {"passed": fact_passed, **fact_diff},
        "pageVisibleComparison": {"passed": visible_passed, **visible_diff},
        "hiddenAfterSync": hidden_after_sync,
        "passed": fact_passed and visible_passed,
    }


def printable_report(report: dict[str, Any], max_diffs: int) -> dict[str, Any]:
    printable = json.loads(json.dumps(report, ensure_ascii=False))
    for section_name in ["factComparison", "pageVisibleComparison"]:
        section = printable[section_name]
        for key in ["missingInNew", "extraInNew", "changed"]:
            rows = section[key]
            section[key] = rows[:max_diffs]
            section[f"{key}Truncated"] = max(0, len(rows) - max_diffs)
    hidden_rows = printable["hiddenAfterSync"]
    printable["hiddenAfterSync"] = hidden_rows[:max_diffs]
    printable["hiddenAfterSyncTruncated"] = max(0, len(hidden_rows) - max_diffs)
    return printable


def main() -> int:
    args = parse_args()
    old = old_rows(args)
    new_all = new_rows(args, page_visible=False)
    new_visible = new_rows(args, page_visible=True)
    report = build_report(args, old, new_all, new_visible)
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
