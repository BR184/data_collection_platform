#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 GitLab PostgreSQL 只读导出一份有界、可校验的小样本目录包。

只支持已在本地核实的 GitLab 16.11.10 结构。连接信息只从 libpq 环境变量读取，
不提供命令行密码参数；数据包由 manifest.json、CHECKSUMS.sha256 和 data/*.jsonl 组成。
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence
from zoneinfo import ZoneInfo

import psycopg2

from gitlab_sample_db import connect, inspect_schema, server_identity, table_columns
from gitlab_sample_contract import (
    DEFAULT_BATCH_SIZE,
    DEFAULT_BATCH_SLEEP_MS,
    DEFAULT_LOCK_TIMEOUT_MS,
    DEFAULT_MAX_ISSUES,
    DEFAULT_MAX_RUNTIME_SECONDS,
    DEFAULT_MAX_TOTAL_BYTES,
    DEFAULT_MAX_TOTAL_ROWS,
    DEFAULT_STATEMENT_TIMEOUT_MS,
    DEFAULT_TIMEZONE,
    DEFAULT_WINDOW_DAYS,
    FORMAT,
    MAX_ISSUES_HARD_LIMIT,
    MAX_TOTAL_BYTES_HARD_LIMIT,
    MAX_TOTAL_ROWS_HARD_LIMIT,
    SANITIZATION_MANIFEST,
    SANITIZATION_SUMMARY,
    TABLE_ORDER,
    TOOL_VERSION,
    PackageError,
    SelectionError,
    encode_value,
    quote_ident,
    sanitize_user_row,
    table_spec,
    write_package,
)


EXIT_FAILURE = 2
# 模块级时钟便于测试用可控时间覆盖总时限边界。
clock = time.monotonic
USER_REFERENCE_COLUMNS: dict[str, tuple[str, ...]] = {
    "projects": ("creator_id",),
    "namespaces": ("owner_id",),
    "issues": ("author_id", "updated_by_id", "last_edited_by_id", "closed_by_id"),
    "notes": ("author_id", "updated_by_id", "resolved_by_id"),
    "members": ("user_id", "created_by_id"),
    "resource_label_events": ("user_id",),
    "resource_state_events": ("user_id",),
    "resource_milestone_events": ("user_id",),
    "resource_weight_events": ("user_id",),
}
FILTER_COLUMNS: dict[str, tuple[str, str]] = {
    # table -> (filter column, selected source table)
    "issue_assignees": ("issue_id", "issues"),
    "project_authorizations": ("project_id", "projects"),
    "user_details": ("user_id", "users"),
}


@dataclass
class ReadBudget:
    """限制关联闭包与导出数据的总行数和总字节数。"""

    max_rows: int
    max_bytes: int
    rows: int = 0
    bytes: int = 0

    def add_row(self, table: str) -> None:
        self.rows += 1
        if self.rows > self.max_rows:
            raise SelectionError(
                f"Total row budget exceeded while reading {table}: "
                f"rows={self.rows}, limit={self.max_rows}"
            )

    def add_bytes(self, table: str, count: int) -> None:
        self.bytes += count
        if self.bytes > self.max_bytes:
            raise SelectionError(
                f"Total byte budget exceeded while exporting {table}: "
                f"bytes={self.bytes}, limit={self.max_bytes}"
            )

    def summary(self) -> dict[str, int]:
        return {"rows": self.rows, "bytes": self.bytes}


def fail(message: str, code: int = EXIT_FAILURE) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(code)


def positive_int(value: str) -> int:
    try:
        parsed = int(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError(f"not an integer: {value}") from error
    if parsed <= 0:
        raise argparse.ArgumentTypeError("must be > 0")
    return parsed


def non_negative_int(value: str) -> int:
    try:
        parsed = int(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError(f"not an integer: {value}") from error
    if parsed < 0:
        raise argparse.ArgumentTypeError("must be >= 0")
    return parsed


def parse_csv_ints(value: str) -> list[int]:
    if not value.strip():
        return []
    result: list[int] = []
    for part in value.split(","):
        part = part.strip()
        if not part:
            continue
        if not part.isdigit():
            raise argparse.ArgumentTypeError(f"expected comma separated integer ids, got: {value}")
        number = int(part)
        if number <= 0:
            raise argparse.ArgumentTypeError("ids must be positive")
        if number not in result:
            result.append(number)
    return result


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Export a bounded, read-only GitLab sample package.")
    parser.add_argument("--project-ids", default="325", help="Comma separated project ids; empty only allowed with --issue-ids.")
    parser.add_argument("--issue-ids", default="", help="Explicit global issue ids; bypasses the time window.")
    parser.add_argument("--issue-iids", default="", help="Explicit issue iids; requires exactly one project.")
    parser.add_argument("--since", default="", help="Inclusive ISO-8601 window start; default now-60d.")
    parser.add_argument("--until", default="", help="Inclusive ISO-8601 window end; default now.")
    parser.add_argument("--timezone", default=DEFAULT_TIMEZONE)
    parser.add_argument("--max-issues", type=positive_int, default=DEFAULT_MAX_ISSUES)
    parser.add_argument("--max-total-rows", type=positive_int, default=DEFAULT_MAX_TOTAL_ROWS)
    parser.add_argument("--max-total-bytes", type=positive_int, default=DEFAULT_MAX_TOTAL_BYTES)
    parser.add_argument("--output", required=True)
    parser.add_argument("--batch-size", type=positive_int, default=DEFAULT_BATCH_SIZE)
    parser.add_argument("--batch-sleep-ms", type=non_negative_int, default=DEFAULT_BATCH_SLEEP_MS)
    parser.add_argument("--statement-timeout-ms", type=positive_int, default=DEFAULT_STATEMENT_TIMEOUT_MS)
    parser.add_argument("--lock-timeout-ms", type=positive_int, default=DEFAULT_LOCK_TIMEOUT_MS)
    parser.add_argument("--max-runtime-seconds", type=positive_int, default=DEFAULT_MAX_RUNTIME_SECONDS)
    parser.add_argument("--connect-timeout", type=positive_int, default=15)
    parser.add_argument("--source-instance", default="cc", help="Human readable source label stored in the manifest.")
    args = parser.parse_args()
    if args.batch_size > 1000:
        parser.error("--batch-size must be <= 1000")
    if args.max_issues > MAX_ISSUES_HARD_LIMIT:
        parser.error(f"--max-issues must be <= {MAX_ISSUES_HARD_LIMIT}")
    if args.max_total_rows > MAX_TOTAL_ROWS_HARD_LIMIT:
        parser.error(f"--max-total-rows must be <= {MAX_TOTAL_ROWS_HARD_LIMIT}")
    if args.max_total_bytes > MAX_TOTAL_BYTES_HARD_LIMIT:
        parser.error(f"--max-total-bytes must be <= {MAX_TOTAL_BYTES_HARD_LIMIT}")
    if bool(args.issue_ids) and bool(args.issue_iids):
        parser.error("--issue-ids and --issue-iids are mutually exclusive")
    return args


def read_only_transaction(conn: Any, args: argparse.Namespace) -> Any:
    conn.set_session(isolation_level="REPEATABLE READ", readonly=True, autocommit=False)
    cur = conn.cursor()
    cur.execute("select current_setting('transaction_read_only')")
    if cur.fetchone()[0] not in ("on", "true"):
        raise PackageError("Source transaction is not read-only")
    cur.execute("set local statement_timeout = %s", (args.statement_timeout_ms,))
    cur.execute("set local lock_timeout = %s", (args.lock_timeout_ms,))
    cur.execute("set local idle_in_transaction_session_timeout = %s", (args.max_runtime_seconds * 1000,))
    return cur


def select_ids(cur: Any, sql: str, params: Sequence[Any] = ()) -> set[int]:
    cur.execute(sql, tuple(params))
    return {int(row[0]) for row in cur.fetchall() if row[0] is not None}


def select_id_rows(cur: Any, sql: str, params: Sequence[Any] = ()) -> list[tuple[Any, ...]]:
    cur.execute(sql, tuple(params))
    return list(cur.fetchall())


def parse_moment(value: str, timezone_name: str) -> datetime:
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise PackageError(f"Invalid ISO-8601 time: {value!r}") from error
    zone = ZoneInfo(timezone_name)
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=zone)
    return parsed.astimezone(zone)


def resolve_window(args: argparse.Namespace, now: datetime) -> dict[str, Any]:
    zone = ZoneInfo(args.timezone)
    until = parse_moment(args.until, args.timezone) if args.until else now.astimezone(zone)
    since = (
        parse_moment(args.since, args.timezone)
        if args.since
        else until - timedelta(days=DEFAULT_WINDOW_DAYS)
    )
    if since > until:
        raise PackageError("--since must not be after --until")
    if (until - since).days > 180:
        raise PackageError("Time window must not exceed 180 days")
    # GitLab 的 issues.created_at/updated_at 是无时区 UTC 墙上时间。
    def utc_naive(value: datetime) -> datetime:
        return value.astimezone(timezone.utc).replace(tzinfo=None)

    return {
        "since": since.isoformat(),
        "until": until.isoformat(),
        "timezone": args.timezone,
        "db_since_utc": utc_naive(since),
        "db_until_utc": utc_naive(until),
    }


def select_projects(cur: Any, project_ids: Sequence[int]) -> list[tuple[Any, ...]]:
    if not project_ids:
        raise PackageError("At least one project id is required for window selection")
    rows = select_id_rows(
        cur,
        "select id, namespace_id, project_namespace_id, creator_id, path, pending_delete from projects where id = any(%s)",
        (list(project_ids),),
    )
    found = {int(row[0]) for row in rows}
    missing = sorted(set(project_ids) - found)
    if missing:
        raise SelectionError(f"Projects not found: {missing}")
    pending = [int(row[0]) for row in rows if len(row) > 5 and row[5]]
    if pending:
        raise SelectionError(f"Projects are marked pending_delete: {pending}")
    return rows


def select_root_issues(
    cur: Any,
    args: argparse.Namespace,
    project_ids: Sequence[int],
    window: Mapping[str, Any] | None,
    deadline: float,
) -> tuple[list[dict[str, Any]], str]:
    if args.issue_ids or args.issue_iids:
        if check_deadline(deadline):
            raise SelectionError("Export runtime limit exceeded during issue selection")
        if args.issue_ids:
            ids = parse_csv_ints(args.issue_ids)
            rows = select_id_rows(
                cur,
                "select id, project_id, iid, updated_at from issues where id = any(%s)",
                (ids,),
            )
            found = {int(row[0]) for row in rows}
            missing = sorted(set(ids) - found)
            if missing:
                raise SelectionError(f"Issue ids not found: {missing}")
            mode = "explicit_ids"
        else:
            iids = parse_csv_ints(args.issue_iids)
            if len(project_ids) != 1:
                raise PackageError("--issue-iids requires exactly one --project-ids value")
            rows = select_id_rows(
                cur,
                "select id, project_id, iid, updated_at from issues where project_id = %s and iid = any(%s)",
                (project_ids[0], iids),
            )
            found = {int(row[2]) for row in rows}
            missing = sorted(set(iids) - found)
            if missing:
                raise SelectionError(f"Issue iids not found in project {project_ids[0]}: {missing}")
            mode = "explicit_iids"
        if project_ids and any(int(row[1]) not in set(project_ids) for row in rows):
            raise SelectionError("Explicit issues belong to projects outside --project-ids; pass the matching project ids")
    else:
        assert window is not None
        if check_deadline(deadline):
            raise SelectionError("Export runtime limit exceeded during issue selection")
        tighten_statement_timeout(cur, args, deadline)
        cur.execute(
            """
            select count(*)
              from issues
             where project_id = any(%s)
               and (
                    (created_at >= %s and created_at <= %s)
                 or (updated_at >= %s and updated_at <= %s)
               )
            """,
            (
                list(project_ids),
                window["db_since_utc"],
                window["db_until_utc"],
                window["db_since_utc"],
                window["db_until_utc"],
            ),
        )
        total = int(cur.fetchone()[0])
        if total == 0:
            raise SelectionError("No root issues match the requested project/time window")
        if total > args.max_issues:
            raise SelectionError(
                f"Selection has {total} issues, above --max-issues={args.max_issues}; "
                "narrow the window or raise the limit explicitly"
            )
        tighten_statement_timeout(cur, args, deadline)
        rows = select_id_rows(
            cur,
            """
            select id, project_id, iid, updated_at
              from issues
             where project_id = any(%s)
               and (
                    (created_at >= %s and created_at <= %s)
                 or (updated_at >= %s and updated_at <= %s)
               )
             order by updated_at desc nulls last, id desc
            """,
            (
                list(project_ids),
                window["db_since_utc"],
                window["db_until_utc"],
                window["db_since_utc"],
                window["db_until_utc"],
            ),
        )
        mode = "window"
    if not rows:
        raise SelectionError("No issues selected")
    if len(rows) > args.max_issues:
        raise SelectionError(f"Explicit selection has {len(rows)} issues, above --max-issues={args.max_issues}")
    issues = [
        {"id": int(row[0]), "project_id": int(row[1]), "iid": int(row[2]), "updated_at": row[3]}
        for row in rows
    ]
    issues.sort(key=lambda row: row["id"])
    return issues, mode


def check_deadline(deadline: float) -> bool:
    return clock() > deadline


def tighten_statement_timeout(cur: Any, args: argparse.Namespace, deadline: float) -> None:
    """临近总时限时收紧单条查询超时，避免单条慢查询越过总运行预算。"""
    remaining_ms = int((deadline - clock()) * 1000)
    if remaining_ms < args.statement_timeout_ms:
        cur.execute("set local statement_timeout = %s", (max(1, remaining_ms),))


def paged_rows(
    cur: Any,
    sql: str,
    params: Sequence[Any],
    key_columns: Sequence[str],
    key_indices: Sequence[int],
    table: str,
    args: argparse.Namespace,
    budget: ReadBudget,
    deadline: float,
) -> Iterable[tuple[Any, ...]]:
    """按主键/业务键 keyset 分页读取闭包查询，并计入总行预算。"""
    keys_sql = ", ".join(quote_ident(column) for column in key_columns)
    last: list[Any] | None = None
    while True:
        if check_deadline(deadline):
            raise SelectionError(f"Export runtime limit exceeded while reading {table}")
        tighten_statement_timeout(cur, args, deadline)
        query = sql
        query_params = list(params)
        if last is not None:
            placeholders = ", ".join(["%s"] * len(key_columns))
            query += f" and ({keys_sql}) > ({placeholders})"
            query_params.extend(last)
        query += f" order by {keys_sql} limit %s"
        cur.execute(query, tuple(query_params + [args.batch_size]))
        batch = cur.fetchall()
        if not batch:
            break
        for row in batch:
            budget.add_row(table)
            yield tuple(row)
        last = [batch[-1][index] for index in key_indices]
        if len(batch) < args.batch_size:
            break
        sleep_between_batches(args)


def sleep_between_batches(args: argparse.Namespace) -> None:
    if args.batch_sleep_ms:
        time.sleep(args.batch_sleep_ms / 1000)


def add_namespaces(
    cur: Any,
    selected: dict[str, set[int]],
    namespace_ids: Iterable[int],
    args: argparse.Namespace,
    budget: ReadBudget,
    deadline: float,
) -> None:
    frontier = {int(value) for value in namespace_ids if value is not None}
    selected["namespaces"] |= frontier
    while frontier:
        next_frontier: set[int] = set()
        for row in paged_rows(
            cur,
            """
            select id, parent_id, organization_id, file_template_project_id,
                   custom_project_templates_group_id, push_rule_id
              from namespaces
             where id = any(%s)
            """,
            (list(frontier),),
            ("id",),
            (0,),
            "namespaces",
            args,
            budget,
            deadline,
        ):
            namespace_id, parent_id, organization_id, template_project_id, template_group_id, push_rule_id = row
            selected["namespaces"].add(int(namespace_id))
            if organization_id is not None:
                selected["organizations"].add(int(organization_id))
            if template_project_id is not None or template_group_id is not None or push_rule_id is not None:
                raise SelectionError(
                    "Namespace "
                    f"{namespace_id} references export-scope-external template/push-rule rows "
                    f"(project={template_project_id}, group={template_group_id}, push_rule={push_rule_id})"
                )
            if parent_id is not None and int(parent_id) not in selected["namespaces"]:
                next_frontier.add(int(parent_id))
        frontier = next_frontier


def add_user_refs(
    cur: Any,
    selected: dict[str, set[int]],
    table: str,
    columns: Sequence[str],
    args: argparse.Namespace,
    budget: ReadBudget,
    deadline: float,
) -> None:
    ids = selected.get(table, set())
    if not ids:
        return
    key = table_spec(table).key_columns
    if len(key) != 1:
        raise PackageError(f"add_user_refs requires a single key column: {table}")
    present = set(table_columns(cur, table))
    for column in columns:
        if column not in present:
            continue
        for row in paged_rows(
            cur,
            f"select distinct {quote_ident(column)} from {quote_ident(table)} "
            f"where {quote_ident(key[0])} = any(%s) and {quote_ident(column)} is not null",
            (list(ids),),
            (column,),
            (0,),
            table,
            args,
            budget,
            deadline,
        ):
            if row[0] is not None:
                selected["users"].add(int(row[0]))


def collect_closure(
    cur: Any,
    selected: dict[str, set[int]],
    issues: Sequence[Mapping[str, Any]],
    project_ids: Sequence[int],
    args: argparse.Namespace,
    budget: ReadBudget,
    deadline: float,
) -> None:
    def add_ns(ids: Iterable[int]) -> None:
        add_namespaces(cur, selected, ids, args, budget, deadline)

    selected["projects"] |= {int(value) for value in project_ids}
    selected["issues"] |= {int(issue["id"]) for issue in issues}
    project_namespace_ids: set[int] = set()
    for row in paged_rows(
        cur,
        """
        select id, namespace_id, project_namespace_id, creator_id
          from projects
         where id = any(%s)
        """,
        (list(selected["projects"]),),
        ("id",),
        (0,),
        "projects",
        args,
        budget,
        deadline,
    ):
        project_id, namespace_id, project_namespace_id, creator_id = row
        selected["projects"].add(int(project_id))
        project_namespace_ids.add(int(namespace_id))
        if project_namespace_id is not None:
            project_namespace_ids.add(int(project_namespace_id))
        if creator_id is not None:
            selected["users"].add(int(creator_id))
    add_ns(project_namespace_ids)

    issue_rows = list(
        paged_rows(
            cur,
            "select id, project_id, namespace_id, work_item_type_id, milestone_id, "
            "moved_to_id, duplicated_to_id, promoted_to_epic_id, tmp_epic_id, sprint_id "
            "from issues where id = any(%s)",
            (list(selected["issues"]),),
            ("id",),
            (0,),
            "issues",
            args,
            budget,
            deadline,
        )
    )
    issue_ids = {int(row[0]) for row in issue_rows}
    if issue_ids != selected["issues"]:
        raise SelectionError("Selected issue rows disappeared during the read transaction")
    for (
        issue_id,
        project_id,
        namespace_id,
        work_item_type_id,
        milestone_id,
        moved_to,
        duplicated,
        promoted,
        tmp_epic,
        sprint_id,
    ) in issue_rows:
        if int(project_id) not in selected["projects"]:
            selected["projects"].add(int(project_id))
        if namespace_id is not None:
            project_namespace_ids.add(int(namespace_id))
        if work_item_type_id is not None:
            selected["work_item_types"].add(int(work_item_type_id))
        if milestone_id is not None:
            selected["milestones"].add(int(milestone_id))
        for association in (moved_to, duplicated):
            if association is not None and int(association) not in selected["issues"]:
                raise SelectionError(
                    f"Issue {issue_id} references issue {association} outside the selected set"
                )
        for association, label in ((promoted, "promoted_to_epic_id"), (tmp_epic, "tmp_epic_id"), (sprint_id, "sprint_id")):
            if association is not None:
                raise SelectionError(
                    f"Issue {issue_id} uses {label}={association}, which is outside the verified export scope"
                )
    add_ns(project_namespace_ids)
    if selected["issues"]:
        for row in paged_rows(
            cur,
            "select issue_id, user_id from issue_assignees where issue_id = any(%s)",
            (list(selected["issues"]),),
            ("issue_id", "user_id"),
            (0, 1),
            "issue_assignees",
            args,
            budget,
            deadline,
        ):
            if row[1] is not None:
                selected["users"].add(int(row[1]))
        for row in paged_rows(
            cur,
            "select id from issue_metrics where issue_id = any(%s)",
            (list(selected["issues"]),),
            ("id",),
            (0,),
            "issue_metrics",
            args,
            budget,
            deadline,
        ):
            selected["issue_metrics"].add(int(row[0]))
        for row in paged_rows(
            cur,
            "select id from label_links where target_type = 'Issue' and target_id = any(%s)",
            (list(selected["issues"]),),
            ("id",),
            (0,),
            "label_links",
            args,
            budget,
            deadline,
        ):
            selected["label_links"].add(int(row[0]))

    while True:
        before = {table: len(ids) for table, ids in selected.items()}
        add_ns(set(selected["namespaces"]))
        if selected["work_item_types"]:
            for row in paged_rows(
                cur,
                "select id, namespace_id from work_item_types where id = any(%s)",
                (list(selected["work_item_types"]),),
                ("id",),
                (0,),
                "work_item_types",
                args,
                budget,
                deadline,
            ):
                if row[1] is not None:
                    add_ns({int(row[1])})
        if selected["member_roles"]:
            for row in paged_rows(
                cur,
                "select id, namespace_id from member_roles where id = any(%s)",
                (list(selected["member_roles"]),),
                ("id",),
                (0,),
                "member_roles",
                args,
                budget,
                deadline,
            ):
                if row[1] is not None:
                    add_ns({int(row[1])})
        # 项目/组标签全部导出，便于导入后新增或摘除测试标签。
        if selected["projects"]:
            for row in paged_rows(
                cur,
                "select id, project_id, group_id from labels where project_id = any(%s)",
                (list(selected["projects"]),),
                ("id",),
                (0,),
                "labels",
                args,
                budget,
                deadline,
            ):
                selected["labels"].add(int(row[0]))
                if row[2] is not None:
                    add_ns({int(row[2])})
        if selected["namespaces"]:
            for row in paged_rows(
                cur,
                "select id, group_id from labels where group_id = any(%s)",
                (list(selected["namespaces"]),),
                ("id",),
                (0,),
                "labels",
                args,
                budget,
                deadline,
            ):
                selected["labels"].add(int(row[0]))
                if row[1] is not None:
                    add_ns({int(row[1])})
        if selected["projects"]:
            for row in paged_rows(
                cur,
                "select id from milestones where project_id = any(%s)",
                (list(selected["projects"]),),
                ("id",),
                (0,),
                "milestones",
                args,
                budget,
                deadline,
            ):
                selected["milestones"].add(int(row[0]))
        if selected["namespaces"]:
            for row in paged_rows(
                cur,
                "select id, group_id from milestones where group_id = any(%s)",
                (list(selected["namespaces"]),),
                ("id",),
                (0,),
                "milestones",
                args,
                budget,
                deadline,
            ):
                selected["milestones"].add(int(row[0]))
                if row[1] is not None:
                    add_ns({int(row[1])})
        if selected["milestones"]:
            for row in paged_rows(
                cur,
                "select id, project_id, group_id from milestones where id = any(%s)",
                (list(selected["milestones"]),),
                ("id",),
                (0,),
                "milestones",
                args,
                budget,
                deadline,
            ):
                if row[1] is not None:
                    selected["projects"].add(int(row[1]))
                if row[2] is not None:
                    add_ns({int(row[2])})
        if selected["labels"]:
            for row in paged_rows(
                cur,
                "select id, project_id, group_id from labels where id = any(%s)",
                (list(selected["labels"]),),
                ("id",),
                (0,),
                "labels",
                args,
                budget,
                deadline,
            ):
                if row[1] is not None:
                    selected["projects"].add(int(row[1]))
                if row[2] is not None:
                    add_ns({int(row[2])})
        # 议题的全部评论，不按评论时间或条数截断。
        if selected["issues"]:
            for row in paged_rows(
                cur,
                "select id, namespace_id, author_id, updated_by_id, resolved_by_id "
                "from notes where noteable_type = 'Issue' and noteable_id = any(%s)",
                (list(selected["issues"]),),
                ("id",),
                (0,),
                "notes",
                args,
                budget,
                deadline,
            ):
                selected["notes"].add(int(row[0]))
                if row[1] is not None:
                    add_ns({int(row[1])})
                for user_id in (row[2], row[3], row[4]):
                    if user_id is not None:
                        selected["users"].add(int(user_id))
        if selected["notes"]:
            for row in paged_rows(
                cur,
                "select id, note_id from system_note_metadata where note_id = any(%s)",
                (list(selected["notes"]),),
                ("id",),
                (0,),
                "system_note_metadata",
                args,
                budget,
                deadline,
            ):
                selected["system_note_metadata"].add(int(row[0]))
        if selected["issues"]:
            for row in paged_rows(
                cur,
                """
                select id, label_id, user_id
                  from resource_label_events
                 where issue_id = any(%s)
                """,
                (list(selected["issues"]),),
                ("id",),
                (0,),
                "resource_label_events",
                args,
                budget,
                deadline,
            ):
                selected["resource_label_events"].add(int(row[0]))
                if row[1] is not None:
                    selected["labels"].add(int(row[1]))
                if row[2] is not None:
                    selected["users"].add(int(row[2]))
            for row in paged_rows(
                cur,
                "select id, user_id from resource_state_events where issue_id = any(%s)",
                (list(selected["issues"]),),
                ("id",),
                (0,),
                "resource_state_events",
                args,
                budget,
                deadline,
            ):
                selected["resource_state_events"].add(int(row[0]))
                if row[1] is not None:
                    selected["users"].add(int(row[1]))
            for row in paged_rows(
                cur,
                "select id, milestone_id, user_id from resource_milestone_events where issue_id = any(%s)",
                (list(selected["issues"]),),
                ("id",),
                (0,),
                "resource_milestone_events",
                args,
                budget,
                deadline,
            ):
                selected["resource_milestone_events"].add(int(row[0]))
                if row[1] is not None:
                    selected["milestones"].add(int(row[1]))
                if row[2] is not None:
                    selected["users"].add(int(row[2]))
            for row in paged_rows(
                cur,
                "select id, user_id from resource_weight_events where issue_id = any(%s)",
                (list(selected["issues"]),),
                ("id",),
                (0,),
                "resource_weight_events",
                args,
                budget,
                deadline,
            ):
                selected["resource_weight_events"].add(int(row[0]))
                if row[1] is not None:
                    selected["users"].add(int(row[1]))
        if selected["projects"]:
            for row in paged_rows(
                cur,
                "select id from project_features where project_id = any(%s)",
                (list(selected["projects"]),),
                ("id",),
                (0,),
                "project_features",
                args,
                budget,
                deadline,
            ):
                selected["project_features"].add(int(row[0]))
            for row in paged_rows(
                cur,
                "select id from project_states where project_id = any(%s)",
                (list(selected["projects"]),),
                ("id",),
                (0,),
                "project_states",
                args,
                budget,
                deadline,
            ):
                selected["project_states"].add(int(row[0]))
            for row in paged_rows(
                cur,
                "select user_id, project_id from project_authorizations where project_id = any(%s)",
                (list(selected["projects"]),),
                ("user_id", "project_id"),
                (0, 1),
                "project_authorizations",
                args,
                budget,
                deadline,
            ):
                selected["users"].add(int(row[0]))
                selected["projects"].add(int(row[1]))
        if selected["projects"] or selected["namespaces"]:
            conditions: list[str] = []
            params: list[Any] = []
            if selected["projects"]:
                conditions.append("(source_type = 'Project' and source_id = any(%s))")
                params.append(list(selected["projects"]))
            if selected["namespaces"]:
                conditions.append("(source_type = 'Namespace' and source_id = any(%s))")
                params.append(list(selected["namespaces"]))
            for row in paged_rows(
                cur,
                "select id from routes where (" + " or ".join(conditions) + ")",
                tuple(params),
                ("id",),
                (0,),
                "routes",
                args,
                budget,
                deadline,
            ):
                selected["routes"].add(int(row[0]))
        if selected["projects"] or selected["namespaces"]:
            conditions = []
            params = []
            if selected["projects"]:
                conditions.append("(source_type = 'Project' and source_id = any(%s))")
                params.append(list(selected["projects"]))
            if selected["namespaces"]:
                conditions.append("(source_type = 'Namespace' and source_id = any(%s))")
                params.append(list(selected["namespaces"]))
            for row in paged_rows(
                cur,
                """
                select id, source_type, source_id, user_id, member_namespace_id, member_role_id
                  from members
                 where ((source_type = 'Project' and source_id = any(%s))
                    or (source_type = 'Namespace' and source_id = any(%s)))
                """,
                (list(selected["projects"]), list(selected["namespaces"])),
                ("id",),
                (0,),
                "members",
                args,
                budget,
                deadline,
            ):
                member_id, source_type, source_id, user_id, member_namespace_id, member_role_id = row
                selected["members"].add(int(member_id))
                if user_id is not None:
                    selected["users"].add(int(user_id))
                if member_namespace_id is not None:
                    add_ns({int(member_namespace_id)})
                if member_role_id is not None:
                    selected["member_roles"].add(int(member_role_id))
                if source_type == "Project":
                    selected["projects"].add(int(source_id))
                elif source_type == "Namespace":
                    add_ns({int(source_id)})
        for table, columns in USER_REFERENCE_COLUMNS.items():
            if table in selected:
                add_user_refs(cur, selected, table, columns, args, budget, deadline)
        if selected["users"]:
            found: set[int] = set()
            for row in paged_rows(
                cur,
                "select id from users where id = any(%s)",
                (list(selected["users"]),),
                ("id",),
                (0,),
                "users",
                args,
                budget,
                deadline,
            ):
                found.add(int(row[0]))
            missing = selected["users"] - found
            if missing:
                raise SelectionError(f"Referenced users are missing from users: {sorted(missing)[:20]}")
        if check_deadline(deadline):
            raise SelectionError("Export runtime limit exceeded during dependency closure")
        after = {table: len(ids) for table, ids in selected.items()}
        if after == before:
            break
    if not selected["issues"]:
        raise SelectionError("No issues selected")
    if check_deadline(deadline):
        raise SelectionError("Export runtime limit exceeded before row export")


def fetch_table_rows(
    cur: Any,
    selected: dict[str, set[int]],
    table: str,
    args: argparse.Namespace,
    budget: ReadBudget,
    deadline: float,
) -> list[dict[str, Any]]:
    spec = table_spec(table)
    columns = table_columns(cur, table)
    column_sql = ", ".join(quote_ident(column) for column in columns)
    filtered = FILTER_COLUMNS.get(table)
    if filtered:
        filter_column, source_table = filtered
        filter_values = sorted(selected.get(source_table, set()))
        if not filter_values:
            return []
        base_where = f"{quote_ident(filter_column)} = any(%s)"
        base_params: list[Any] = [filter_values]
        cursor_columns = list(spec.key_columns)
    else:
        ids = selected.get(table, set())
        if not ids:
            return []
        base_where = f"{quote_ident('id')} = any(%s)"
        base_params = [sorted(ids)]
        cursor_columns = ["id"]
    params: list[Any] = list(base_params)
    if cursor_columns == ["id"]:
        keyset_sql = f"and {quote_ident('id')} > %s"
        params.append(0)
    else:
        placeholders = ", ".join(["%s"] * len(cursor_columns))
        keyset_sql = (
            f"and ({', '.join(quote_ident(column) for column in cursor_columns)}) > "
            f"({placeholders})"
        )
        params.extend([0] * len(cursor_columns))
    rows: list[dict[str, Any]] = []
    while True:
        if check_deadline(deadline):
            raise SelectionError(f"Export runtime limit exceeded while reading {table}")
        tighten_statement_timeout(cur, args, deadline)
        order_sql = ", ".join(quote_ident(column) for column in cursor_columns)
        sql = (
            f"select {column_sql} from {quote_ident(table)} "
            f"where {base_where} {keyset_sql} order by {order_sql} limit %s"
        )
        cur.execute(sql, tuple(params + [args.batch_size]))
        batch = cur.fetchall()
        if not batch:
            break
        for raw in batch:
            row = {column: raw[index] for index, column in enumerate(columns)}
            if table == "users":
                row = sanitize_user_row(row)
            budget.add_row(table)
            budget.add_bytes(
                table,
                len(
                    json.dumps(
                        {column: encode_value(row[column]) for column in columns},
                        ensure_ascii=False,
                    ).encode("utf-8")
                )
                + 1,
            )
            rows.append(row)
        if check_deadline(deadline):
            raise SelectionError(f"Export runtime limit exceeded after reading {table}")
        last = batch[-1]
        if cursor_columns == ["id"]:
            params = list(base_params) + [last[columns.index("id")]]
        else:
            params = list(base_params) + [last[columns.index(column)] for column in cursor_columns]
        if len(batch) < args.batch_size:
            break
        sleep_between_batches(args)
    return rows


def rows_by_key(rows: Sequence[Mapping[str, Any]], key_columns: Sequence[str]) -> set[tuple[Any, ...]]:
    return {tuple(row[column] for column in key_columns) for row in rows}


def require_subset(
    rows: Sequence[Mapping[str, Any]],
    table: str,
    column: str,
    allowed: set[Any],
    nullable: bool = True,
) -> None:
    missing: set[Any] = set()
    for row in rows:
        value = row.get(column)
        if value is None:
            if not nullable:
                missing.add("<null>")
            continue
        if value not in allowed:
            missing.add(value)
    if missing:
        raise SelectionError(
            f"Association check failed for {table}.{column}: "
            f"missing {sorted(missing, key=str)[:20]}"
        )


def validate_associations(rows: Mapping[str, Sequence[Mapping[str, Any]]]) -> None:
    project_ids = {row["id"] for row in rows["projects"]}
    namespace_ids = {row["id"] for row in rows["namespaces"]}
    organization_ids = {row["id"] for row in rows.get("organizations", [])}
    user_ids = {row["id"] for row in rows["users"]}
    issue_ids = {row["id"] for row in rows["issues"]}
    milestone_ids = {row["id"] for row in rows.get("milestones", [])}
    label_ids = {row["id"] for row in rows.get("labels", [])}
    work_item_type_ids = {row["id"] for row in rows.get("work_item_types", [])}
    note_ids = {row["id"] for row in rows.get("notes", [])}
    member_role_ids = {row["id"] for row in rows.get("member_roles", [])}
    require_subset(rows["namespaces"], "namespaces", "organization_id", organization_ids)
    require_subset(rows.get("work_item_types", []), "work_item_types", "namespace_id", namespace_ids)
    require_subset(rows.get("member_roles", []), "member_roles", "namespace_id", namespace_ids)
    require_subset(rows["issues"], "issues", "project_id", project_ids, nullable=False)
    require_subset(rows["issues"], "issues", "namespace_id", namespace_ids, nullable=False)
    require_subset(rows["issues"], "issues", "author_id", user_ids, nullable=False)
    require_subset(rows["issues"], "issues", "work_item_type_id", work_item_type_ids, nullable=False)
    require_subset(rows["issues"], "issues", "milestone_id", milestone_ids)
    require_subset(rows["notes"], "notes", "project_id", project_ids, nullable=False)
    require_subset(rows["notes"], "notes", "namespace_id", namespace_ids)
    require_subset(rows["notes"], "notes", "author_id", user_ids, nullable=False)
    require_subset(rows["notes"], "notes", "updated_by_id", user_ids)
    require_subset(rows["notes"], "notes", "resolved_by_id", user_ids)
    require_subset(rows.get("label_links", []), "label_links", "target_id", issue_ids, nullable=False)
    require_subset(rows.get("label_links", []), "label_links", "label_id", label_ids)
    for table in ("resource_label_events", "resource_state_events", "resource_milestone_events", "resource_weight_events"):
        require_subset(rows.get(table, []), table, "issue_id", issue_ids, nullable=False)
    require_subset(rows.get("resource_label_events", []), "resource_label_events", "label_id", label_ids)
    require_subset(rows.get("resource_label_events", []), "resource_label_events", "user_id", user_ids)
    require_subset(rows.get("resource_state_events", []), "resource_state_events", "user_id", user_ids)
    require_subset(rows.get("resource_milestone_events", []), "resource_milestone_events", "milestone_id", milestone_ids)
    require_subset(rows.get("resource_milestone_events", []), "resource_milestone_events", "user_id", user_ids)
    require_subset(rows.get("members", []), "members", "user_id", user_ids)
    require_subset(rows.get("members", []), "members", "member_namespace_id", namespace_ids)
    require_subset(rows.get("members", []), "members", "member_role_id", member_role_ids)
    require_subset(rows.get("project_authorizations", []), "project_authorizations", "project_id", project_ids, nullable=False)
    require_subset(rows.get("project_authorizations", []), "project_authorizations", "user_id", user_ids, nullable=False)
    require_subset(rows.get("issue_assignees", []), "issue_assignees", "issue_id", issue_ids, nullable=False)
    require_subset(rows.get("issue_assignees", []), "issue_assignees", "user_id", user_ids, nullable=False)
    require_subset(rows.get("issue_metrics", []), "issue_metrics", "issue_id", issue_ids, nullable=False)
    require_subset(rows.get("system_note_metadata", []), "system_note_metadata", "note_id", note_ids, nullable=False)
    require_subset(rows.get("labels", []), "labels", "project_id", project_ids)
    require_subset(rows.get("labels", []), "labels", "group_id", namespace_ids)
    require_subset(rows.get("milestones", []), "milestones", "project_id", project_ids)
    require_subset(rows.get("milestones", []), "milestones", "group_id", namespace_ids)
    require_subset(rows.get("project_features", []), "project_features", "project_id", project_ids, nullable=False)
    require_subset(rows.get("project_states", []), "project_states", "project_id", project_ids, nullable=False)
    for row in rows.get("routes", []):
        if row["source_type"] == "Project":
            require_subset([row], "routes", "source_id", project_ids, nullable=False)
        elif row["source_type"] == "Namespace":
            require_subset([row], "routes", "source_id", namespace_ids, nullable=False)
        else:
            raise SelectionError(f"Unexpected route source_type: {row['source_type']}")
    visited_notes = {row["id"] for row in rows["notes"]}
    if visited_notes != note_ids:
        raise SelectionError("Note closure mismatch")
    if len({row["id"] for row in rows["notes"]}) != len(rows["notes"]):
        raise SelectionError("Duplicate note ids in package")


def build_manifest(
    args: argparse.Namespace,
    source: Mapping[str, Any],
    schema: Mapping[str, Any],
    window: Mapping[str, Any] | None,
    issues: Sequence[Mapping[str, Any]],
    mode: str,
    rows: Mapping[str, Sequence[Mapping[str, Any]]],
    checks: Mapping[str, Any],
) -> dict[str, Any]:
    issue_ids = [int(issue["id"]) for issue in issues]
    issue_iids: dict[str, list[int]] = {}
    for issue in issues:
        issue_iids.setdefault(str(issue["project_id"]), []).append(int(issue["iid"]))
    selection: dict[str, Any] = {
        "mode": mode,
        "project_ids": sorted({int(issue["project_id"]) for issue in issues}),
        "issue_ids": issue_ids,
        "issue_iids": {key: sorted(value) for key, value in sorted(issue_iids.items())},
        "max_issues": args.max_issues,
        "root_issues_only": True,
        "time_window": None,
    }
    if window:
        selection["time_window"] = {key: value for key, value in window.items() if not key.startswith("db_")}
    manifest = {
        "format": FORMAT,
        "tool_version": TOOL_VERSION,
        "created_at": datetime.now(ZoneInfo(args.timezone)).isoformat(timespec="seconds"),
        "source": {
            "instance_label": args.source_instance,
            "database": source["database"],
            "role": source["role"],
            "server_fingerprint": source["server_fingerprint"],
            "identity_fingerprint": source["identity_fingerprint"],
        },
        "schema": dict(schema),
        "selection": selection,
        "runtime": {
            "batch_size": args.batch_size,
            "batch_sleep_ms": args.batch_sleep_ms,
            "statement_timeout_ms": args.statement_timeout_ms,
            "lock_timeout_ms": args.lock_timeout_ms,
            "max_runtime_seconds": args.max_runtime_seconds,
            "max_total_rows": args.max_total_rows,
            "max_total_bytes": args.max_total_bytes,
            "single_connection": True,
            "single_worker": True,
            "isolation": "REPEATABLE READ READ ONLY",
        },
        "checks": dict(checks),
        "sanitization": {**SANITIZATION_MANIFEST, "notes": SANITIZATION_SUMMARY},
        "table_counts": {table: len(rows.get(table, [])) for table in TABLE_ORDER},
    }
    return manifest


def main() -> int:
    args = parse_args()
    started = clock()
    deadline = started + args.max_runtime_seconds
    conn = None
    try:
        now = datetime.now(ZoneInfo(args.timezone))
        window = None if (args.issue_ids or args.issue_iids) else resolve_window(args, now)
        project_ids = parse_csv_ints(args.project_ids)
        if args.issue_ids and not project_ids:
            project_ids = []
        conn = connect(args.connect_timeout, "gitlab_bounded_sql_export")
        budget = ReadBudget(args.max_total_rows, args.max_total_bytes)
        try:
            cur = read_only_transaction(conn, args)
            source = server_identity(cur)
            schema = inspect_schema(cur)
            if project_ids:
                select_projects(cur, project_ids)
            issues, mode = select_root_issues(cur, args, project_ids, window, deadline)
            if not project_ids:
                project_ids = sorted({int(issue["project_id"]) for issue in issues})
                select_projects(cur, project_ids)
            selected = {table: set() for table in TABLE_ORDER}
            collect_closure(cur, selected, issues, project_ids, args, budget, deadline)
            rows: dict[str, list[dict[str, Any]]] = {}
            columns: dict[str, list[str]] = {}
            for table in TABLE_ORDER:
                columns[table] = table_columns(cur, table)
                rows[table] = fetch_table_rows(cur, selected, table, args, budget, deadline)
            validate_associations(rows)
            within_runtime = not check_deadline(deadline)
            checks = {
                "issue_count": len(rows["issues"]),
                "note_count": len(rows["notes"]),
                "notes_all_history": True,
                "label_link_count": len(rows["label_links"]),
                "user_count": len(rows["users"]),
                "association_check": "PASS",
                "source_transaction_read_only": True,
                "completed_within_runtime": within_runtime,
                **budget.summary(),
            }
            manifest = build_manifest(args, source, schema, window, issues, mode, rows, checks)
            # 读取完成后立即释放只读快照；包序列化与校验不再延长源事务。
            conn.rollback()
            if not within_runtime:
                raise SelectionError("Export runtime limit exceeded before package write")

            def ensure_deadline() -> None:
                if check_deadline(deadline):
                    raise SelectionError("Export runtime limit exceeded while writing package")

            output = write_package(
                Path(args.output),
                manifest,
                rows,
                columns,
                deadline_check=ensure_deadline,
                byte_limit=args.max_total_bytes,
            )
            if check_deadline(deadline):
                import shutil

                shutil.rmtree(output, ignore_errors=True)
                raise SelectionError("Export runtime limit exceeded after package write")
        finally:
            if conn is not None and not conn.closed:
                conn.rollback()
            if conn is not None:
                conn.close()
        print(f"package written: {output}")
        print(f"issues={len(rows['issues'])} notes={len(rows['notes'])} tables={len(TABLE_ORDER)}")
        return 0
    except (PackageError, SelectionError, psycopg2.Error) as error:
        fail(str(error))
        return EXIT_FAILURE
    finally:
        elapsed = clock() - started
        print(f"elapsed: {elapsed:.1f}s", file=sys.stderr)


if __name__ == "__main__":
    raise SystemExit(main())
