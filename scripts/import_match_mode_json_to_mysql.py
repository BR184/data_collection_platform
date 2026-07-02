#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Import three old-platform JSON exports into MySQL tables.

Default target is the local Docker MySQL container used by the old platform:
`spidergitdata-mysql`, database `gitlab_spider`, user/password `root/root`.
Pass --host/--port to import into a remote MySQL instead.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import time
from datetime import datetime
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any, Iterable


# 兼容模式-MatchMode：短期评审演示数据导入脚本，后续外部工具接入后整体删除。
DEFAULT_INPUT_DIR = r"C:\Users\admin\Downloads\数据采集平台报错反馈"
DEFAULT_DOCKER_IMAGE = "mysql:8.0.36"
TABLE_FILES = {
    "spider_crowncad_data": "spider_crowncad_data.json",
    "review_report": "reviewReport.json",
    "problem_detail": "problemDetail.json",
}

SPIDER_COLUMNS = [
    ("id", "bigint", ("id",), "int"),
    ("added_line", "int", ("added_line",), "int"),
    ("assignee", "varchar(255)", ("assignee",), "text"),
    ("author", "varchar(255)", ("author",), "text"),
    ("code_walkthrough_date", "datetime(6)", ("code_walkthrough_date",), "datetime"),
    ("code_walkthrough_defect_density", "decimal(18,6)", ("code_walkthrough_defect_density",), "decimal"),
    ("code_walkthrough_duration", "int", ("code_walkthrough_duration",), "int"),
    ("code_walkthrough_efficiency", "decimal(18,6)", ("code_walkthrough_efficiency",), "decimal"),
    ("code_walkthrough_speed_kloc", "decimal(18,6)", ("code_walkthrough_speed_kloc",), "decimal"),
    ("code_walkthrough_speed_loc", "int", ("code_walkthrough_speed_loc",), "int"),
    ("defect_count", "int", ("defect_count",), "int"),
    ("deleted_line", "int", ("deleted_line",), "int"),
    ("issuable_reference", "varchar(128)", ("issuable_reference",), "text"),
    ("merge_request_title", "text", ("merge_request_title",), "text"),
    ("module_name", "varchar(255)", ("module_name",), "text"),
    ("project_name", "varchar(255)", ("project_name",), "text"),
    ("status", "varchar(64)", ("status",), "text"),
    ("target_branch", "varchar(255)", ("target_branch",), "text"),
    ("assigneed", "varchar(255)", ("assigneed",), "text"),
    ("merged_time", "varchar(64)", ("merged_time",), "text"),
    ("merged_local_date_time", "datetime(6)", ("merged_local_date_time",), "datetime"),
    ("merged_user_name", "varchar(255)", ("merged_user_name",), "text"),
    ("code_logic_specification_count", "int", ("code_logic_specification_count",), "int"),
    ("code_specification_count", "int", ("code_specification_count",), "int"),
    ("other_specification_count", "int", ("other_specification_count",), "int"),
    ("sonar_qube_result", "varchar(255)", ("sonar_qube_result",), "text"),
    ("commit_count", "int", ("commit_count",), "int"),
    ("commit_rate", "int", ("commit_rate",), "int"),
    ("function_name", "varchar(255)", ("function_name",), "text"),
    ("design_specification_count", "int", ("design_specification_count",), "int"),
    ("performance_specification_count", "int", ("performance_specification_count",), "int"),
    ("name", "varchar(255)", ("name",), "text"),
    ("annotation_rate", "decimal(8,2)", ("annotation_rate",), "decimal"),
    ("bug_count", "int", ("bug_count",), "int"),
    ("annotation_rate_result", "varchar(255)", ("annotation_rate_result",), "text"),
    ("bug_count_result", "varchar(255)", ("bug_count_result",), "text"),
    ("ct_code_line_count", "int", ("ct_code_line_count",), "int"),
    ("raw_payload", "json", tuple(), "json"),
]

REVIEW_REPORT_COLUMNS = [
    ("_id", "varchar(128)", ("_id",), "text"),
    ("_class", "varchar(255)", ("_class",), "text"),
    ("createTime", "datetime(6)", ("createTime", "create_time"), "datetime"),
    ("defectValue", "int", ("defectValue", "defect_value"), "int"),
    ("descriptionIds", "text", ("descriptionIds", "description_ids"), "text"),
    ("docType", "varchar(255)", ("docType", "doc_type"), "text"),
    ("moduleName", "varchar(255)", ("moduleName", "module_name"), "text"),
    ("notReachStandCause", "text", ("notReachStandCause", "not_reach_stand_cause"), "text"),
    ("problemDetailIds", "text", ("problemDetailIds", "problem_detail_ids"), "text"),
    ("projectName", "varchar(255)", ("projectName", "project_name"), "text"),
    ("reviewCharger", "varchar(255)", ("reviewCharger", "review_charger"), "text"),
    ("reviewExperts", "text", ("reviewExperts", "review_experts"), "text"),
    ("reviewTime", "datetime(6)", ("reviewTime", "review_time"), "datetime"),
    ("sourceType", "varchar(255)", ("sourceType", "source_type"), "text"),
    ("title", "text", ("title",), "text"),
    ("raw_payload", "json", tuple(), "json"),
]

PROBLEM_DETAIL_COLUMNS = [
    ("_id", "varchar(128)", ("_id",), "text"),
    ("_class", "varchar(255)", ("_class",), "text"),
    ("closeTime", "date", ("closeTime", "close_time"), "date"),
    ("createTime", "datetime(6)", ("createTime", "create_time"), "datetime"),
    ("description", "text", ("description",), "text"),
    ("liablePerson", "varchar(255)", ("liablePerson", "liable_person"), "text"),
    ("position", "varchar(255)", ("position",), "text"),
    ("problemStatus", "varchar(255)", ("problemStatus", "problem_status"), "text"),
    ("problemType", "varchar(255)", ("problemType", "problem_type"), "text"),
    ("reasonForNotAccepting", "text", ("reasonForNotAccepting", "reason_for_not_accepting"), "text"),
    ("reviewer", "varchar(255)", ("reviewer",), "text"),
    ("reviewType", "varchar(255)", ("reviewType", "review_type"), "text"),
    ("suggestion", "text", ("suggestion",), "text"),
    ("updateTime", "date", ("updateTime", "update_time"), "date"),
    ("workload", "decimal(10,2)", ("workload",), "decimal"),
    ("raw_payload", "json", tuple(), "json"),
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-dir", default=DEFAULT_INPUT_DIR)
    parser.add_argument("--database", default="gitlab_spider")
    parser.add_argument("--user", default="root")
    parser.add_argument("--password", default="root")
    parser.add_argument("--host", default="", help="Remote MySQL host. Empty means use --container.")
    parser.add_argument("--port", type=int, default=3306)
    parser.add_argument("--container", default="spidergitdata-mysql")
    parser.add_argument("--docker-image", default=DEFAULT_DOCKER_IMAGE)
    parser.add_argument("--append", action="store_true", help="Append/upsert instead of replacing the three tables.")
    parser.add_argument("--no-execute", action="store_true", help="Only generate SQL.")
    parser.add_argument("--sql-file", default="", help="Optional SQL output path.")
    parser.add_argument("--batch-size", type=int, default=500)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    input_dir = Path(args.input_dir)
    records = {table: load_records(input_dir, filename) for table, filename in TABLE_FILES.items()}
    sql_path = Path(args.sql_file) if args.sql_file else Path(tempfile.gettempdir()) / "match_mode_json_import.sql"
    write_sql(sql_path, args.database, records, replace=not args.append, batch_size=args.batch_size)
    print(f"SQL generated: {sql_path}")
    for table, rows in records.items():
      print(f"{table}: {len(rows)} rows")
    if args.no_execute:
        return
    execute_sql(args, sql_path)
    print("MySQL import completed.")


def load_records(input_dir: Path, filename: str) -> list[dict[str, Any]]:
    path = input_dir / filename
    with path.open("r", encoding="utf-8") as handle:
        payload = json.load(handle)
    records = payload.get("RECORDS", [])
    if not isinstance(records, list):
        raise ValueError(f"{path} does not contain a RECORDS array")
    return [record for record in records if isinstance(record, dict)]


def write_sql(
    sql_path: Path,
    database: str,
    records: dict[str, list[dict[str, Any]]],
    replace: bool,
    batch_size: int,
) -> None:
    sql_path.parent.mkdir(parents=True, exist_ok=True)
    with sql_path.open("w", encoding="utf-8", newline="\n") as out:
        out.write("set names utf8mb4;\n")
        out.write(f"create database if not exists {quote_ident(database)} character set utf8mb4 collate utf8mb4_unicode_ci;\n")
        out.write(f"use {quote_ident(database)};\n")
        out.write("set foreign_key_checks = 0;\n")
        write_table(out, "spider_crowncad_data", SPIDER_COLUMNS, records["spider_crowncad_data"], replace, "id", batch_size)
        write_table(out, "review_report", REVIEW_REPORT_COLUMNS, records["review_report"], replace, "_id", batch_size)
        write_table(out, "problem_detail", PROBLEM_DETAIL_COLUMNS, records["problem_detail"], replace, "_id", batch_size)
        out.write("set foreign_key_checks = 1;\n")


def write_table(
    out,
    table_name: str,
    columns: list[tuple[str, str, tuple[str, ...], str]],
    rows: list[dict[str, Any]],
    replace: bool,
    primary_key: str,
    batch_size: int,
) -> None:
    if replace:
        out.write(f"drop table if exists {quote_ident(table_name)};\n")
    out.write(f"create table if not exists {quote_ident(table_name)} (\n")
    definitions = [f"  {quote_ident(name)} {column_type}" for name, column_type, _, _ in columns]
    definitions.append(f"  primary key ({quote_ident(primary_key)})")
    out.write(",\n".join(definitions))
    out.write("\n) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;\n")
    if not rows:
        return
    column_names = [name for name, _, _, _ in columns]
    update_columns = [name for name in column_names if name != primary_key]
    for batch in batches(rows, batch_size):
        out.write(f"insert into {quote_ident(table_name)} ({', '.join(quote_ident(name) for name in column_names)}) values\n")
        values = []
        for row_index, row in enumerate(batch, start=1):
            row_values = [sql_literal(column_value(row, column, row_index)) for column in columns]
            values.append("  (" + ", ".join(row_values) + ")")
        out.write(",\n".join(values))
        out.write("\non duplicate key update ")
        out.write(", ".join(f"{quote_ident(name)} = values({quote_ident(name)})" for name in update_columns))
        out.write(";\n")


def column_value(row: dict[str, Any], column: tuple[str, str, tuple[str, ...], str], row_index: int) -> Any:
    name, _, candidates, value_type = column
    if value_type == "json":
        return json.dumps(row, ensure_ascii=False)
    raw = first_value(row, *candidates)
    if name == "id" and raw is None:
        return row_index
    if name == "_id" and raw is None:
        return f"row-{row_index}"
    if value_type == "int":
        return to_int(raw)
    if value_type == "decimal":
        return to_decimal(raw)
    if value_type == "datetime":
        parsed = parse_datetime(raw)
        return parsed.strftime("%Y-%m-%d %H:%M:%S.%f") if parsed else None
    if value_type == "date":
        parsed = parse_datetime(raw)
        return parsed.strftime("%Y-%m-%d") if parsed else None
    return trim_to_none(raw)


def first_value(row: dict[str, Any], *candidates: str) -> Any:
    for candidate in candidates:
        normalized_candidate = normalize_key(candidate)
        for key, value in row.items():
            if normalize_key(key) == normalized_candidate:
                return value
    return None


def trim_to_none(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def normalize_key(value: str) -> str:
    return value.replace("_", "").lower()


def to_int(value: Any) -> int | None:
    text = trim_to_none(value)
    if text is None:
        return None
    try:
        return int(Decimal(text))
    except (InvalidOperation, ValueError):
        return None


def to_decimal(value: Any) -> Decimal | None:
    text = trim_to_none(value)
    if text is None:
        return None
    try:
        return Decimal(text)
    except (InvalidOperation, ValueError):
        return None


def parse_datetime(value: Any) -> datetime | None:
    text = trim_to_none(value)
    if text is None:
        return None
    normalized = text.replace("T", " ")
    if "." in normalized:
        head, tail = normalized.split(".", 1)
        normalized = f"{head}.{tail[:6]}"
    for fmt in (
        "%Y-%m-%d %H:%M:%S.%f",
        "%Y-%m-%d %H:%M:%S",
        "%Y-%m-%d %H:%M",
        "%Y-%m-%d",
        "%d/%m/%Y %H:%M:%S.%f",
        "%d/%m/%Y %H:%M:%S",
        "%d/%m/%Y %H:%M",
        "%d/%m/%Y",
    ):
        try:
            return datetime.strptime(normalized, fmt)
        except ValueError:
            pass
    return None


def sql_literal(value: Any) -> str:
    if value is None:
        return "null"
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, int):
        return str(value)
    text = str(value)
    return "'" + text.replace("\\", "\\\\").replace("'", "''").replace("\0", "") + "'"


def quote_ident(identifier: str) -> str:
    if "\0" in identifier:
        raise ValueError(f"Invalid identifier: {identifier!r}")
    return "`" + identifier.replace("`", "``") + "`"


def batches(rows: list[dict[str, Any]], batch_size: int) -> Iterable[list[dict[str, Any]]]:
    for index in range(0, len(rows), batch_size):
        yield rows[index : index + batch_size]


def execute_sql(args: argparse.Namespace, sql_path: Path) -> None:
    if args.host:
        command = [
            "docker",
            "run",
            "--rm",
            "-i",
            args.docker_image,
            "mysql",
            "--default-character-set=utf8mb4",
            "-h",
            args.host,
            "-P",
            str(args.port),
            "-u",
            args.user,
            f"-p{args.password}",
        ]
    else:
        subprocess.run(["docker", "start", args.container], check=True, stdout=subprocess.DEVNULL)
        wait_for_container_mysql(args)
        command = [
            "docker",
            "exec",
            "-i",
            args.container,
            "mysql",
            "--default-character-set=utf8mb4",
            "-u",
            args.user,
            f"-p{args.password}",
        ]
    with sql_path.open("rb") as handle:
        result = subprocess.run(command, stdin=handle)
    if result.returncode != 0:
        raise SystemExit(result.returncode)


def wait_for_container_mysql(args: argparse.Namespace) -> None:
    command = [
        "docker",
        "exec",
        args.container,
        "mysqladmin",
        "ping",
        "-u",
        args.user,
        f"-p{args.password}",
        "--silent",
    ]
    for _ in range(60):
        result = subprocess.run(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if result.returncode == 0:
            return
        time.sleep(1)
    raise RuntimeError(f"MySQL container is not ready: {args.container}")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        raise SystemExit(130)
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
