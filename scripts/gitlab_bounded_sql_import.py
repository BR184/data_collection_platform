#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 GitLab 小样本目录包导入独立、空的 GitLab 16.11.10 数据库。

导入器只读取清单登记的表和列，自行构造参数化 INSERT；不执行数据包中的任何 SQL。
目标必须带本工具初始化标记且与源库不同库；所有数据写入在单事务内完成，失败整体回滚。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Mapping, Sequence

import psycopg2
from psycopg2.extras import Json, execute_values

from gitlab_sample_contract import (
    BOOTSTRAP_NAMESPACE_ID,
    BOOTSTRAP_ROUTE_ID,
    BOOTSTRAP_USER_ID,
    REFERENCE_TABLES,
    TABLE_ORDER,
    TABLE_SPECS,
    TOOL_VERSION,
    PackageError,
    canonical_json,
    load_package,
    quote_ident,
    sha256_bytes,
    table_spec,
)
from gitlab_sample_db import column_types, connect, inspect_schema, server_identity

MARKER_TABLE = "dcp_sample_import_target"
JOURNAL_TABLE = "dcp_sample_import_journal"
MARKER_KEY = "default"
SENSITIVE_TABLES = (
    "web_hooks",
    "integrations",
    "services",
    "personal_access_tokens",
    "keys",
    "identities",
    "sessions",
    "oauth_access_tokens",
    "oauth_applications",
    "ci_runner_tokens",
)


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(2)


def positive_int(value: str) -> int:
    try:
        parsed = int(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError(f"not an integer: {value}") from error
    if parsed <= 0:
        raise argparse.ArgumentTypeError("must be > 0")
    return parsed


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Import a verified GitLab sample package into an empty target.")
    parser.add_argument("--package", default="", help="Package directory produced by the exporter.")
    parser.add_argument("--target-label", required=True, help="Operator-provided target label; must match the marker.")
    parser.add_argument("--target-token-env", default="DCP_TARGET_TOKEN", help="Environment variable holding the target marker token.")
    parser.add_argument("--init-target", action="store_true", help="Create the target ownership marker and exit.")
    parser.add_argument("--replace-existing", action="store_true", help="Replace the previous import recorded in the journal.")
    parser.add_argument("--report", default="import-report.json")
    parser.add_argument("--connect-timeout", type=positive_int, default=15)
    parser.add_argument("--batch-size", type=positive_int, default=500)
    parser.add_argument("--statement-timeout-ms", type=positive_int, default=300_000)
    parser.add_argument("--lock-timeout-ms", type=positive_int, default=10_000)
    parser.add_argument("--max-runtime-seconds", type=positive_int, default=600)
    args = parser.parse_args()
    if args.init_target and args.package:
        parser.error("--init-target and --package are mutually exclusive")
    if not args.init_target and not args.package:
        parser.error("--package is required unless --init-target is used")
    if args.replace_existing and args.init_target:
        parser.error("--replace-existing is only valid with --package")
    if args.batch_size > 2000:
        parser.error("--batch-size must be <= 2000")
    return args


def target_token(args: argparse.Namespace) -> str:
    token = os.getenv(args.target_token_env, "")
    if not token:
        raise PackageError(f"Target token is not available in environment variable {args.target_token_env}")
    if len(token) < 16:
        raise PackageError("Target token must be at least 16 characters")
    return token


def token_hash(token: str) -> str:
    return sha256_bytes(token.encode("utf-8"))


def control_tables_exist(cur: Any) -> tuple[bool, bool]:
    cur.execute(
        "select to_regclass(%s) is not null, to_regclass(%s) is not null",
        (f"public.{MARKER_TABLE}", f"public.{JOURNAL_TABLE}"),
    )
    marker, journal = cur.fetchone()
    return bool(marker), bool(journal)


def ensure_control_tables(cur: Any) -> None:
    cur.execute(
        f"""
        create table if not exists {quote_ident(MARKER_TABLE)} (
            marker_key text primary key,
            target_label text not null,
            token_sha256 char(64) not null,
            identity_fingerprint char(64) not null,
            server_fingerprint char(64) not null,
            schema_fingerprint char(64) not null,
            schema_migrations_max text not null,
            initialized_at timestamptz not null,
            last_package_id text,
            imported_at timestamptz,
            tool_version text not null
        )
        """
    )
    cur.execute(
        f"""
        create table if not exists {quote_ident(JOURNAL_TABLE)} (
            package_id text not null,
            table_name text not null,
            key_text text not null,
            primary key (package_id, table_name, key_text)
        )
        """
    )


def read_marker(cur: Any) -> dict[str, Any] | None:
    cur.execute(
        "select column_name from information_schema.columns "
        "where table_schema = 'public' and table_name = %s",
        (MARKER_TABLE,),
    )
    columns = {row[0] for row in cur.fetchall()}
    if "identity_fingerprint" not in columns:
        raise PackageError("Target marker schema is outdated; rebuild the target before importing")
    cur.execute(
        f"select target_label, token_sha256, identity_fingerprint, server_fingerprint, "
        f"schema_fingerprint, schema_migrations_max, last_package_id, imported_at, "
        f"initialized_at, tool_version "
        f"from {quote_ident(MARKER_TABLE)} where marker_key = %s",
        (MARKER_KEY,),
    )
    row = cur.fetchone()
    if not row:
        return None
    return {
        "target_label": row[0],
        "token_sha256": row[1],
        "identity_fingerprint": row[2],
        "server_fingerprint": row[3],
        "schema_fingerprint": row[4],
        "schema_migrations_max": row[5],
        "last_package_id": row[6],
        "imported_at": row[7],
        "initialized_at": row[8],
        "tool_version": row[9],
    }


def init_target(
    cur: Any,
    args: argparse.Namespace,
    identity: Mapping[str, Any],
    schema: Mapping[str, Any],
) -> None:
    marker_exists, journal_exists = control_tables_exist(cur)
    if marker_exists or journal_exists:
        raise PackageError("Target control tables already exist; rebuild the target before initializing")
    ensure_control_tables(cur)
    if read_marker(cur):
        raise PackageError("Target marker already exists; refusing to overwrite it")
    cur.execute(
        f"insert into {quote_ident(MARKER_TABLE)} "
        "(marker_key, target_label, token_sha256, identity_fingerprint, server_fingerprint, "
        "schema_fingerprint, schema_migrations_max, initialized_at, tool_version) "
        "values (%s, %s, %s, %s, %s, %s, %s, current_timestamp, %s)",
        (
            MARKER_KEY,
            args.target_label,
            token_hash(target_token(args)),
            identity["identity_fingerprint"],
            identity["server_fingerprint"],
            schema["fingerprint"],
            schema["schema_migrations_max"],
            TOOL_VERSION,
        ),
    )
    print(f"target marker initialized: label={args.target_label}, database={identity['database']}")


def verify_marker(
    cur: Any,
    args: argparse.Namespace,
    identity: Mapping[str, Any],
    schema: Mapping[str, Any],
) -> dict[str, Any]:
    marker = read_marker(cur)
    if not marker:
        raise PackageError("Target marker is missing; run --init-target on the intended disposable target first")
    if marker["target_label"] != args.target_label:
        raise PackageError("Target label does not match the initialized marker")
    if marker["token_sha256"] != token_hash(target_token(args)):
        raise PackageError("Target token does not match the initialized marker")
    if marker["identity_fingerprint"] != identity["identity_fingerprint"]:
        raise PackageError("Target identity does not match the initialized marker")
    if marker["server_fingerprint"] != identity["server_fingerprint"]:
        raise PackageError("Target server does not match the initialized marker")
    if marker["schema_fingerprint"] != schema["fingerprint"]:
        raise PackageError("Target schema fingerprint does not match the initialized marker")
    if marker["schema_migrations_max"] != schema["schema_migrations_max"]:
        raise PackageError("Target schema migration does not match the initialized marker")
    return marker


def table_count(cur: Any, table: str) -> int:
    cur.execute(f"select count(*) from {quote_ident(table)}")
    return int(cur.fetchone()[0])


def non_bootstrap_count(cur: Any, table: str) -> int:
    key = table_spec(table).key_columns
    if len(key) != 1:
        raise PackageError(f"Unsupported bootstrap key for {table}")
    cur.execute(
        f"select count(*) from {quote_ident(table)} where {quote_ident(key[0])} <> %s",
        (1,),
    )
    return int(cur.fetchone()[0])


def inspect_target_state(cur: Any) -> dict[str, Any]:
    violations: list[dict[str, Any]] = []
    for table in TABLE_ORDER:
        if table in REFERENCE_TABLES:
            continue
        if table in ("users", "user_details", "namespaces", "routes"):
            count = non_bootstrap_count(cur, table)
        else:
            count = table_count(cur, table)
        if count:
            violations.append({"table": table, "count": count})
    for table in SENSITIVE_TABLES:
        cur.execute("select to_regclass(%s) is not null", (f"public.{table}",))
        if not cur.fetchone()[0]:
            continue
        count = table_count(cur, table)
        if count:
            violations.append({"table": table, "count": count, "reason": "sensitive/external table is not empty"})
    return {"violations": violations}


def previous_journal(cur: Any, package_id: str) -> list[tuple[str, str]]:
    cur.execute(
        f"select table_name, key_text from {quote_ident(JOURNAL_TABLE)} where package_id = %s",
        (package_id,),
    )
    return [(row[0], row[1]) for row in cur.fetchall()]


def delete_previous_import(cur: Any, package_id: str) -> int:
    entries = previous_journal(cur, package_id)
    if not entries:
        raise PackageError(f"No import journal exists for previous package {package_id}; refusing to replace")
    by_table: dict[str, list[list[Any]]] = {}
    for table, key_text in entries:
        if table not in TABLE_SPECS:
            raise PackageError(f"Journal references unknown table: {table}")
        values = json.loads(key_text)
        if not isinstance(values, list) or len(values) != len(table_spec(table).key_columns):
            raise PackageError(f"Invalid journal key for {table}")
        by_table.setdefault(table, []).append(values)
    deleted = 0
    for table in reversed(TABLE_ORDER):
        keys = by_table.get(table, [])
        if not keys:
            continue
        columns = table_spec(table).key_columns
        column_sql = ", ".join(quote_ident(column) for column in columns)
        for start in range(0, len(keys), 200):
            batch = keys[start : start + 200]
            placeholders = ", ".join(
                "(" + ", ".join(["%s"] * len(columns)) + ")" for _ in batch
            )
            params = [value for key in batch for value in key]
            cur.execute(
                f"delete from {quote_ident(table)} where ({column_sql}) in ({placeholders})",
                tuple(params),
            )
            deleted += cur.rowcount
    cur.execute(f"delete from {quote_ident(JOURNAL_TABLE)} where package_id = %s", (package_id,))
    return deleted


def fetch_target_row(cur: Any, table: str, row: Mapping[str, Any]) -> dict[str, Any] | None:
    keys = table_spec(table).key_columns
    where = " and ".join(f"{quote_ident(key)} = %s" for key in keys)
    cur.execute(
        f"select * from {quote_ident(table)} where {where}",
        tuple(row[key] for key in keys),
    )
    result = cur.fetchone()
    if result is None:
        return None
    columns = [description[0] for description in cur.description]
    return dict(zip(columns, result))


def adapt_value(value: Any, data_type: str) -> Any:
    if value is None:
        return None
    if data_type in ("json", "jsonb"):
        return Json(value)
    return value


def journal_rows(cur: Any, package_id: str, table: str, rows: Sequence[Mapping[str, Any]]) -> None:
    if not rows:
        return
    keys = table_spec(table).key_columns
    values = [
        (package_id, table, canonical_json([row[key] for key in keys]))
        for row in rows
    ]
    execute_values(
        cur,
        f"insert into {quote_ident(JOURNAL_TABLE)} (package_id, table_name, key_text) values %s",
        values,
        page_size=500,
    )


def fetch_target_row_by(cur: Any, table: str, where: str, params: Sequence[Any]) -> dict[str, Any] | None:
    cur.execute(f"select * from {quote_ident(table)} where {where}", tuple(params))
    result = cur.fetchone()
    if result is None:
        return None
    columns = [description[0] for description in cur.description]
    return dict(zip(columns, result))


def resolve_reference_row(
    cur: Any,
    table: str,
    row: Mapping[str, Any],
    reference_maps: dict[str, dict[int, int]],
) -> tuple[dict[str, Any] | None, str]:
    """返回 (要插入的行, 处理结果)。引用种子行按稳定业务键映射目标 ID。"""
    source_id = int(row["id"])
    if table == "organizations":
        existing = fetch_target_row_by(cur, table, "path = %s", (row.get("path"),))
        if existing:
            reference_maps.setdefault(table, {})[source_id] = int(existing["id"])
            return None, "adopted"
        candidate = dict(row)
        if fetch_target_row(cur, table, row):
            cur.execute(f"select coalesce(max(id), 0) + 1 from {quote_ident(table)}")
            candidate["id"] = int(cur.fetchone()[0])
        reference_maps.setdefault(table, {})[source_id] = int(candidate["id"])
        return candidate, "inserted"
    if table == "work_item_types":
        where = "namespace_id is not distinct from %s and name = %s and base_type = %s"
        existing = fetch_target_row_by(
            cur,
            table,
            where,
            (row.get("namespace_id"), row.get("name"), row.get("base_type")),
        )
        if existing:
            reference_maps.setdefault(table, {})[source_id] = int(existing["id"])
            return None, "adopted"
        candidate = dict(row)
        if fetch_target_row(cur, table, row):
            cur.execute(f"select coalesce(max(id), 0) + 1 from {quote_ident(table)}")
            candidate["id"] = int(cur.fetchone()[0])
        reference_maps.setdefault(table, {})[source_id] = int(candidate["id"])
        return candidate, "inserted"
    if table == "member_roles":
        existing = fetch_target_row_by(
            cur,
            table,
            "name = %s and namespace_id is not distinct from %s",
            (row.get("name"), row.get("namespace_id")),
        )
        if existing:
            reference_maps.setdefault(table, {})[source_id] = int(existing["id"])
            return None, "adopted"
        candidate = dict(row)
        if fetch_target_row(cur, table, row):
            cur.execute(f"select coalesce(max(id), 0) + 1 from {quote_ident(table)}")
            candidate["id"] = int(cur.fetchone()[0])
        reference_maps.setdefault(table, {})[source_id] = int(candidate["id"])
        return candidate, "inserted"
    raise PackageError(f"Unsupported reference table: {table}")


def insert_table(
    cur: Any,
    package_id: str,
    table: str,
    rows: Sequence[Mapping[str, Any]],
    columns: Sequence[str],
    batch_size: int,
    reference_maps: dict[str, dict[int, int]],
) -> dict[str, int]:
    if not rows:
        return {"inserted": 0, "adopted": 0, "skipped": 0}
    types = column_types(cur, table, columns)
    missing = sorted(set(columns) - set(types))
    if missing:
        raise PackageError(f"Target table {table} is missing package columns: {missing}")
    inserted_rows: list[Mapping[str, Any]] = []
    adopted = 0
    skipped = 0
    for source_row in rows:
        row = dict(source_row)
        if table == "issues":
            source_type = row.get("work_item_type_id")
            if source_type is not None:
                mapped = reference_maps.get("work_item_types", {}).get(int(source_type))
                if mapped is None:
                    raise PackageError(f"Issue {row.get('id')} references an unmapped work item type")
                row["work_item_type_id"] = mapped
        if table == "namespaces" and row.get("organization_id") is not None:
            mapped = reference_maps.get("organizations", {}).get(int(row["organization_id"]))
            if mapped is None:
                raise PackageError(f"Namespace {row.get('id')} references an unmapped organization")
            row["organization_id"] = mapped
        if table == "members" and row.get("member_role_id") is not None:
            mapped = reference_maps.get("member_roles", {}).get(int(row["member_role_id"]))
            if mapped is None:
                raise PackageError(f"Member {row.get('id')} references an unmapped member role")
            row["member_role_id"] = mapped
        key = tuple(row[column] for column in table_spec(table).key_columns)
        if table in REFERENCE_TABLES:
            candidate, outcome = resolve_reference_row(cur, table, row, reference_maps)
            if outcome == "adopted":
                adopted += 1
                continue
            assert candidate is not None
            inserted_rows.append(candidate)
            continue
        if table == "users" and key == (BOOTSTRAP_USER_ID,):
            existing = fetch_target_row(cur, table, row)
            if not existing or existing.get("username") != "root" or not existing.get("admin"):
                raise PackageError("Target bootstrap user id=1 is not the expected initialized administrator")
            skipped += 1
            continue
        if table == "user_details" and key == (BOOTSTRAP_USER_ID,):
            existing = fetch_target_row(cur, table, row)
            if existing:
                skipped += 1
                continue
        if table == "namespaces" and key == (BOOTSTRAP_NAMESPACE_ID,):
            existing = fetch_target_row(cur, table, row)
            if existing and existing.get("path") == "root" and existing.get("type") == "User":
                skipped += 1
                continue
            raise PackageError("Target bootstrap namespace id=1 is not the expected root user namespace")
        if table == "routes" and key == (BOOTSTRAP_ROUTE_ID,):
            existing = fetch_target_row(cur, table, row)
            if (
                existing
                and existing.get("source_type") == "Namespace"
                and int(existing.get("source_id", 0)) == BOOTSTRAP_NAMESPACE_ID
            ):
                skipped += 1
                continue
            raise PackageError("Target bootstrap route id=1 is not the expected root namespace route")
        inserted_rows.append(row)
    if inserted_rows:
        columns_sql = ", ".join(quote_ident(column) for column in columns)
        values = [
            [adapt_value(row[column], types[column]) for column in columns]
            for row in inserted_rows
        ]
        for start in range(0, len(values), batch_size):
            execute_values(
                cur,
                f"insert into {quote_ident(table)} ({columns_sql}) values %s",
                values[start : start + batch_size],
                page_size=batch_size,
            )
    journal_rows(cur, package_id, table, inserted_rows)
    return {"inserted": len(inserted_rows), "adopted": adopted, "skipped": skipped}


def ensure_support_rows(cur: Any) -> dict[str, int]:
    """补齐 GitLab 直接导入时不会自动创建的关联行；不覆盖已有行。"""
    created: dict[str, int] = {}
    statements = {
        "user_details": (
            "insert into user_details (user_id) "
            "select u.id from users u where not exists (select 1 from user_details d where d.user_id = u.id)"
        ),
        "user_preferences": (
            "insert into user_preferences (user_id, created_at, updated_at) "
            "select u.id, current_timestamp, current_timestamp from users u "
            "where not exists (select 1 from user_preferences p where p.user_id = u.id)"
        ),
        "namespace_details": (
            "insert into namespace_details (namespace_id) "
            "select n.id from namespaces n "
            "where not exists (select 1 from namespace_details d where d.namespace_id = n.id)"
        ),
        "namespace_settings": (
            "insert into namespace_settings (namespace_id, created_at, updated_at) "
            "select n.id, current_timestamp, current_timestamp from namespaces n "
            "where not exists (select 1 from namespace_settings s where s.namespace_id = n.id)"
        ),
        "project_settings": (
            "insert into project_settings (project_id, created_at, updated_at) "
            "select p.id, current_timestamp, current_timestamp from projects p "
            "where not exists (select 1 from project_settings s where s.project_id = p.id)"
        ),
        "project_ci_cd_settings": (
            "insert into project_ci_cd_settings (project_id, group_runners_enabled) "
            "select p.id, false from projects p "
            "where not exists (select 1 from project_ci_cd_settings s where s.project_id = p.id)"
        ),
    }
    for table, sql in statements.items():
        cur.execute(sql)
        created[table] = cur.rowcount
    return created


def quote_sequence_name(name: str) -> str:
    return ".".join(quote_ident(part) for part in name.split("."))


def reset_sequence_for_table(cur: Any, table: str, column: str) -> bool:
    """把单个序列的事务安全重置为当前最大编号之后；无序列返回 False。"""
    cur.execute("select pg_get_serial_sequence(%s, %s)", (table, column))
    sequence = cur.fetchone()[0]
    if not sequence:
        return False
    cur.execute("select seqmin from pg_sequence where seqrelid = %s::regclass", (sequence,))
    minimum_row = cur.fetchone()
    sequence_min = int(minimum_row[0]) if minimum_row and minimum_row[0] is not None else 1
    cur.execute(f"select max({quote_ident(column)}) from {quote_ident(table)}")
    maximum = cur.fetchone()[0]
    restart_with = sequence_min if maximum is None else max(int(maximum) + 1, sequence_min)
    cur.execute(
        f"alter sequence {quote_sequence_name(sequence)} restart with %s",
        (restart_with,),
    )
    return True


def reset_sequences(cur: Any) -> None:
    """用事务可回滚的 ALTER SEQUENCE RESTART 校正序列，避免 setval 越过回滚边界。"""
    cur.execute(
        """
        select table_name, column_name
          from information_schema.columns
         where table_schema = 'public'
           and table_name = any(%s)
           and column_default like 'nextval(%%'
        """,
        (list(TABLE_ORDER),),
    )
    for table, column in cur.fetchall():
        reset_sequence_for_table(cur, table, column)


def verify_foreign_keys(cur: Any) -> list[dict[str, Any]]:
    cur.execute(
        """
        select conrelid::regclass::text as child_table,
               conname,
               conkey,
               confrelid::regclass::text as parent_table,
               confkey,
               convalidated
          from pg_constraint
         where contype = 'f'
           and conrelid::regclass::text = any(%s)
         order by child_table, conname
        """,
        (list(TABLE_ORDER),),
    )
    constraints = cur.fetchall()
    checked: list[dict[str, Any]] = []
    for child_table, name, conkey, parent_table, confkey, validated in constraints:
        cur.execute(
            "select attname from pg_attribute where attrelid = %s::regclass and attnum = any(%s) order by array_position(%s::smallint[], attnum)",
            (child_table, conkey, conkey),
        )
        child_columns = [row[0] for row in cur.fetchall()]
        cur.execute(
            "select attname from pg_attribute where attrelid = %s::regclass and attnum = any(%s) order by array_position(%s::smallint[], attnum)",
            (parent_table, confkey, confkey),
        )
        parent_columns = [row[0] for row in cur.fetchall()]
        if len(child_columns) != len(parent_columns):
            raise PackageError(f"Cannot inspect foreign key {name}")
        non_null = " and ".join(f"c.{quote_ident(column)} is not null" for column in child_columns)
        join = " and ".join(
            f"p.{quote_ident(parent)} = c.{quote_ident(child)}"
            for child, parent in zip(child_columns, parent_columns)
        )
        cur.execute(
            f"select count(*) from {quote_ident(child_table)} c "
            f"where {non_null} and not exists ("
            f"select 1 from {quote_ident(parent_table)} p where {join})"
        )
        orphans = int(cur.fetchone()[0])
        checked.append({"constraint": name, "child_table": child_table, "parent_table": parent_table, "orphans": orphans, "validated": bool(validated)})
        if orphans:
            raise PackageError(f"Foreign key {name} on {child_table} has {orphans} orphan rows")
    return checked


def scalar(cur: Any, sql: str, params: Sequence[Any] = ()) -> int:
    cur.execute(sql, tuple(params))
    return int(cur.fetchone()[0])


def verify_polymorphic(cur: Any) -> list[dict[str, Any]]:
    checks = [
        (
            "notes.noteable",
            "select count(*) from notes where noteable_type <> 'Issue' or noteable_id not in (select id from issues)",
        ),
        (
            "label_links.target",
            "select count(*) from label_links where target_type <> 'Issue' or target_id not in (select id from issues)",
        ),
        (
            "members.source",
            "select count(*) from members where source_type not in ('Project','Namespace') "
            "or (source_type='Project' and source_id not in (select id from projects)) "
            "or (source_type='Namespace' and source_id not in (select id from namespaces))",
        ),
        (
            "routes.source",
            "select count(*) from routes where source_type not in ('Project','Namespace') "
            "or (source_type='Project' and source_id not in (select id from projects)) "
            "or (source_type='Namespace' and source_id not in (select id from namespaces))",
        ),
        (
            "resource_events.issue",
            "select count(*) from ("
            "select issue_id, merge_request_id, epic_id from resource_label_events "
            "union all select issue_id, merge_request_id, null from resource_milestone_events "
            "union all select issue_id, merge_request_id, epic_id from resource_state_events "
            "union all select issue_id, null, null from resource_weight_events"
            ") e where (case when issue_id is not null then 1 else 0 end + "
            "case when merge_request_id is not null then 1 else 0 end + "
            "case when epic_id is not null then 1 else 0 end) <> 1 "
            "or issue_id is null or issue_id not in (select id from issues)",
        ),
    ]
    results = []
    for name, sql in checks:
        count = scalar(cur, sql)
        results.append({"check": name, "violations": count})
        if count:
            raise PackageError(f"Polymorphic association check failed for {name}: {count} rows")
    return results


def reduce_external_features(cur: Any, project_ids: Sequence[int]) -> dict[str, Any]:
    if not project_ids:
        return {}
    cur.execute(
        "update projects set shared_runners_enabled = false, mirror = false where id = any(%s)",
        (list(project_ids),),
    )
    cur.execute(
        "update project_features set builds_access_level = 0 where project_id = any(%s)",
        (list(project_ids),),
    )
    cur.execute(
        "update project_settings set emails_enabled = false where project_id = any(%s)",
        (list(project_ids),),
    )
    cur.execute(
        "update namespace_settings set emails_enabled = false"
    )
    cur.execute(
        "update application_settings set signup_enabled = false where coalesce(signup_enabled, true) = true"
    )
    return {
        "shared_runners_disabled": True,
        "ci_builds_disabled": True,
        "project_emails_disabled": True,
        "namespace_emails_disabled": True,
        "signup_disabled": True,
    }


def foreign_table_counts(cur: Any) -> dict[str, int]:
    counts: dict[str, int] = {}
    for table in SENSITIVE_TABLES:
        cur.execute("select to_regclass(%s) is not null", (f"public.{table}",))
        if cur.fetchone()[0]:
            counts[table] = table_count(cur, table)
    return counts


def imported_row_count(cur: Any, package_id: str, table: str) -> int:
    cur.execute(
        f"select count(*) from {quote_ident(JOURNAL_TABLE)} where package_id = %s and table_name = %s",
        (package_id, table),
    )
    return int(cur.fetchone()[0])


def write_report(path: Path, report: Mapping[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main() -> int:
    args = parse_args()
    started = time.monotonic()
    try:
        target_token(args)
    except PackageError as error:
        fail(str(error))
    if args.init_target:
        conn = None
        try:
            conn = connect(args.connect_timeout, "gitlab_bounded_sql_import")
            cur = conn.cursor()
            identity = server_identity(cur)
            schema = inspect_schema(cur)
            state = inspect_target_state(cur)
            if state["violations"]:
                raise PackageError(
                    "Target is not an empty test GitLab; refusing to initialize. "
                    f"Violations: {state['violations'][:10]}"
                )
            init_target(cur, args, identity, schema)
            conn.commit()
            report = {
                "status": "TARGET_INITIALIZED",
                "tool_version": TOOL_VERSION,
                "target": identity,
                "schema": schema,
                "target_label": args.target_label,
                "initialized_at": datetime.now(timezone.utc).isoformat(),
            }
            write_report(Path(args.report), report)
            print(json.dumps(report, ensure_ascii=False, indent=2))
            return 0
        except PackageError as error:
            if conn is not None:
                conn.rollback()
            fail(str(error))
        except Exception as error:
            if conn is not None:
                conn.rollback()
            fail(str(error))
        finally:
            if conn is not None:
                conn.close()
    package = Path(args.package)
    try:
        manifest, rows = load_package(package)
    except PackageError as error:
        fail(str(error))
    source_identity = manifest.get("source") or {}
    if not source_identity.get("identity_fingerprint"):
        fail("Package source identity is missing")
    if not manifest.get("selection", {}).get("issue_ids"):
        fail("Package contains no selected issues")
    if (manifest.get("checks") or {}).get("association_check") != "PASS":
        fail("Package association check is not PASS")
    conn = None
    try:
        conn = connect(args.connect_timeout, "gitlab_bounded_sql_import")
        conn.autocommit = False
        cur = conn.cursor()
        cur.execute("set local statement_timeout = %s", (args.statement_timeout_ms,))
        cur.execute("set local lock_timeout = %s", (args.lock_timeout_ms,))
        cur.execute("set local idle_in_transaction_session_timeout = %s", (args.max_runtime_seconds * 1000,))
        identity = server_identity(cur)
        if identity["identity_fingerprint"] == source_identity.get("identity_fingerprint"):
            raise PackageError("Source and target are the same database")
        marker_exists, journal_exists = control_tables_exist(cur)
        if not marker_exists or not journal_exists:
            raise PackageError("Target marker is missing; run --init-target on the intended disposable target first")
        schema = inspect_schema(cur)
        marker = verify_marker(cur, args, identity, schema)
        expected_schema = manifest.get("schema") or {}
        if schema["schema_migrations_max"] != expected_schema.get("schema_migrations_max"):
            raise PackageError(
                f"Target schema migration {schema['schema_migrations_max']} does not match package "
                f"{expected_schema.get('schema_migrations_max')}"
            )
        if schema["fingerprint"] != expected_schema.get("fingerprint"):
            raise PackageError("Target schema fingerprint does not match the package")
        state = inspect_target_state(cur)
        previous_package = marker.get("last_package_id")
        if state["violations"]:
            if not args.replace_existing:
                raise PackageError(
                    "Target is not an empty test GitLab; refusing to import. "
                    f"Violations: {state['violations'][:10]}"
                )
            if not previous_package:
                raise PackageError("Target is not empty and has no previous import journal; refusing to replace")
            deleted = delete_previous_import(cur, previous_package)
            state = inspect_target_state(cur)
            if state["violations"]:
                raise PackageError(f"Previous import cleanup left non-bootstrap rows: {state['violations'][:10]}")
            print(f"previous import removed: {deleted} rows")
        elif previous_package:
            if not args.replace_existing:
                raise PackageError("Target marker records a previous import; use --replace-existing to replace it")
            delete_previous_import(cur, previous_package)
        package_id = str(manifest["package_id"])
        if previous_package and previous_package == package_id and not args.replace_existing:
            raise PackageError("This package was already imported; use --replace-existing to import it again")
        table_entries = {entry["name"]: entry for entry in manifest["tables"]}
        results: dict[str, dict[str, int]] = {}
        reference_maps: dict[str, dict[int, int]] = {}
        for table in TABLE_ORDER:
            entry = table_entries[table]
            results[table] = insert_table(
                cur,
                package_id,
                table,
                rows[table],
                entry["columns"],
                args.batch_size,
                reference_maps,
            )
            if time.monotonic() > started + args.max_runtime_seconds:
                raise PackageError("Import runtime limit exceeded")
        support_rows = ensure_support_rows(cur)
        reset_sequences(cur)
        project_ids = sorted({int(row["id"]) for row in rows["projects"]})
        features = reduce_external_features(cur, project_ids)
        foreign_keys = verify_foreign_keys(cur)
        polymorphic = verify_polymorphic(cur)
        external_tables = foreign_table_counts(cur)
        for table, count in external_tables.items():
            if count:
                raise PackageError(f"External/security-sensitive table {table} is not empty after import: {count}")
        cur.execute(
            f"update {quote_ident(MARKER_TABLE)} set last_package_id = %s, imported_at = current_timestamp, tool_version = %s "
            "where marker_key = %s",
            (package_id, TOOL_VERSION, MARKER_KEY),
        )
        journal_counts = {
            table: imported_row_count(cur, package_id, table) for table in TABLE_ORDER
        }
        conn.commit()
        report = {
            "status": "IMPORTED",
            "tool_version": TOOL_VERSION,
            "package_id": package_id,
            "target": identity,
            "target_label": args.target_label,
            "schema": schema,
            "rows": results,
            "support_rows": support_rows,
            "reference_maps": {table: {str(source): target for source, target in values.items()} for table, values in reference_maps.items()},
            "journal_counts": journal_counts,
            "foreign_key_checks": foreign_keys,
            "polymorphic_checks": polymorphic,
            "external_features": features,
            "external_table_counts": external_tables,
            "email_configuration": "operator must keep gitlab_rails['gitlab_email_enabled'] and smtp disabled in the test instance",
            "elapsed_seconds": round(time.monotonic() - started, 3),
        }
        write_report(Path(args.report), report)
        print(json.dumps({"status": "IMPORTED", "package_id": package_id, "rows": results}, ensure_ascii=False, indent=2))
        return 0
    except Exception as error:
        if conn is not None:
            conn.rollback()
        fail(str(error))
        return 2
    finally:
        if conn is not None:
            conn.close()


if __name__ == "__main__":
    raise SystemExit(main())
