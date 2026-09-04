#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Export a bounded, dependency-aware GitLab PostgreSQL data slice to .sql.gz.

The script is intended for offline sampling from a very large GitLab database.
It exports data rows only; import into a target GitLab database that already has
the same GitLab schema.
"""

from __future__ import annotations

import argparse
import gzip
import json
import os
import re
import sys
import time
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

import psycopg2
from psycopg2.extras import Json


IDENT = re.compile(r"^[a-zA-Z_][a-zA-Z0-9_]*$")
MB = 1024 * 1024

# ===== 内网 GitLab_233(cc) 一键导出配置 =====
# 直接运行：python scripts/gitlab_bounded_sql_export.py
# 为空字符串时使用 PostgreSQL 默认 Unix socket；内网直连填 IP。
EXPORT_HOST = "172.22.10.233"
EXPORT_PORT = 5432
EXPORT_DATABASE = "gitlabhq_production"
EXPORT_USERNAME = "qaflex_reader"
EXPORT_PASSWORD = "huayun123"
EXPORT_SOURCE_INSTANCE = "cc"
EXPORT_OUTPUT = "gitlab_233_cc_sample.sql.gz"
# mirror_sample：只导采集平台常用镜像链路。
# mini_gitlab_repo：导出可在外网 GitLab 中打开项目/议题/MR/看板的微型仓库 DB 样本，不包含代码仓库磁盘对象。
EXPORT_PROFILE = "mini_gitlab_repo"

# 留空表示按最近活跃项目自动抽样；必须包含 9/325，保证系统测试和客户问题核心项目一定进样本。
EXPORT_PROJECT_IDS = ""
EXPORT_MUST_PROJECT_IDS = "9,325"

# 大表上限。父表/依赖表不按 LIMIT 截断，会按核心 issue/MR 自动闭包补齐。
EXPORT_MAX_PROJECTS = 260
EXPORT_ISSUE_LIMIT = 8000
EXPORT_MERGE_REQUEST_LIMIT = 16000
EXPORT_NOTES_PER_PARENT = 4
EXPORT_NOTE_LIMIT = 30000
EXPORT_EVENT_LIMIT = 8000
EXPORT_MEMBER_LIMIT = 5000
EXPORT_TODO_LIMIT = 0
EXPORT_CI_LIMIT = 0
EXPORT_DIFFS_PER_MR = 1
EXPORT_DIFF_FILE_LIMIT = 3000
EXPORT_DIFF_COMMIT_LIMIT = 3000
EXPORT_MAX_COMPRESSED_MB = 100
EXPORT_COMPRESS_LEVEL = 9
EXPORT_CONNECT_TIMEOUT = 30


DEFAULT_TABLE_ORDER = [
    "application_settings",
    "appearances",
    "features",
    "plans",
    "plan_limits",
    "shards",
    "work_item_types",
    "organizations",
    "organization_details",
    "organization_settings",
    "users",
    "user_details",
    "user_preferences",
    "user_statuses",
    "emails",
    "keys",
    "identities",
    "namespaces",
    "namespace_details",
    "namespace_settings",
    "namespace_statistics",
    "namespace_root_storage_statistics",
    "namespace_limits",
    "namespace_ci_cd_settings",
    "namespace_package_settings",
    "organization_users",
    "projects",
    "routes",
    "redirect_routes",
    "project_features",
    "project_settings",
    "project_statistics",
    "project_states",
    "project_repositories",
    "project_ci_cd_settings",
    "project_auto_devops",
    "project_custom_attributes",
    "project_authorizations",
    "project_group_links",
    "project_import_data",
    "project_feature_usages",
    "project_security_settings",
    "project_topics",
    "topics",
    "repository_languages",
    "programming_languages",
    "members",
    "milestones",
    "labels",
    "label_priorities",
    "boards",
    "lists",
    "board_assignees",
    "board_labels",
    "list_user_preferences",
    "issues",
    "issue_metrics",
    "issue_assignees",
    "issue_links",
    "issue_search_data",
    "issue_user_mentions",
    "issue_email_participants",
    "merge_requests",
    "merge_request_metrics",
    "merge_request_assignees",
    "merge_request_reviewers",
    "merge_requests_closing_issues",
    "merge_request_blocks",
    "merge_request_diffs",
    "merge_request_diff_details",
    "merge_request_diff_files",
    "merge_request_diff_commits",
    "merge_request_user_mentions",
    "label_links",
    "notes",
    "note_metadata",
    "system_note_metadata",
    "diff_note_positions",
    "award_emoji",
    "resource_label_events",
    "resource_state_events",
    "resource_milestone_events",
    "resource_weight_events",
    "issue_assignment_events",
    "merge_request_assignment_events",
    "timelogs",
    "events",
    "push_event_payloads",
    "todos",
    "protected_branches",
    "protected_branch_push_access_levels",
    "protected_branch_merge_access_levels",
    "protected_branch_unprotect_access_levels",
    "protected_tags",
    "protected_tag_create_access_levels",
    "protected_environments",
    "protected_environment_deploy_access_levels",
    "protected_environment_approval_rules",
    "releases",
    "release_links",
    "milestone_releases",
    "evidences",
    "web_hooks",
    "integrations",
    "ci_pipelines",
    "ci_stages",
    "ci_builds",
    "ci_builds_metadata",
    "environments",
    "deployments",
]


@dataclass
class TableInfo:
    columns: list[str]
    pk_columns: list[str]
    unique_columns: list[list[str]]

    @property
    def single_pk(self) -> str | None:
        return self.pk_columns[0] if len(self.pk_columns) == 1 else None

    @property
    def conflict_columns(self) -> list[str]:
        if self.pk_columns:
            return self.pk_columns
        return self.unique_columns[0] if self.unique_columns else []


def die(message: str, code: int = 2) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(code)


def quote_ident(name: str) -> str:
    if not IDENT.match(name):
        die(f"Unsafe SQL identifier: {name!r}")
    return '"' + name.replace('"', '""') + '"'


def placeholders(count: int) -> str:
    return ",".join(["%s"] * count)


def parse_csv_ints(value: str) -> set[int]:
    if not value.strip():
        return set()
    result: set[int] = set()
    for part in value.split(","):
        part = part.strip()
        if not part:
            continue
        if not part.isdigit():
            die(f"Expected comma separated integer ids, got: {value}")
        result.add(int(part))
    return result


def connect(args: argparse.Namespace):
    kwargs = dict(
        port=args.port,
        dbname=args.database,
        user=args.username,
        password=args.password,
        connect_timeout=args.connect_timeout,
        application_name="gitlab_bounded_sql_export",
    )
    if args.host:
        kwargs["host"] = args.host
    return psycopg2.connect(**kwargs)


def table_exists(cur, table: str) -> bool:
    cur.execute(
        """
        select exists (
          select 1
            from information_schema.tables
           where table_schema = 'public'
             and table_name = %s
        )
        """,
        (table,),
    )
    return bool(cur.fetchone()[0])


def column_exists(cur, table: str, column: str) -> bool:
    cur.execute(
        """
        select exists (
          select 1
            from information_schema.columns
           where table_schema = 'public'
             and table_name = %s
             and column_name = %s
        )
        """,
        (table, column),
    )
    return bool(cur.fetchone()[0])


def load_table_info(cur, table: str) -> TableInfo | None:
    if not table_exists(cur, table):
        return None
    cur.execute(
        """
        select column_name
          from information_schema.columns
         where table_schema = 'public'
           and table_name = %s
           and coalesce(is_generated, 'NEVER') = 'NEVER'
         order by ordinal_position
        """,
        (table,),
    )
    columns = [row[0] for row in cur.fetchall()]
    cur.execute(
        """
        select a.attname
          from pg_index i
          join pg_attribute a
            on a.attrelid = i.indrelid
           and a.attnum = any(i.indkey)
         where i.indrelid = %s::regclass
           and i.indisprimary
         order by array_position(i.indkey, a.attnum)
        """,
        (table,),
    )
    pk_columns = [row[0] for row in cur.fetchall()]
    cur.execute(
        """
        select array_agg(a.attname order by array_position(i.indkey, a.attnum)) as columns
          from pg_index i
          join pg_attribute a
            on a.attrelid = i.indrelid
           and a.attnum = any(i.indkey)
         where i.indrelid = %s::regclass
           and i.indisunique
           and i.indpred is null
           and i.indexprs is null
         group by i.indexrelid, i.indisprimary, i.indkey
         order by i.indisprimary desc, array_length(i.indkey, 1), i.indexrelid::text
        """,
        (table,),
    )
    unique_columns = [list(row[0]) for row in cur.fetchall() if row[0]]
    return TableInfo(columns=columns, pk_columns=pk_columns, unique_columns=unique_columns)


def select_ids(cur, table: str, where_sql: str, params: tuple[Any, ...], limit: int | None, order_sql: str) -> set[int]:
    if not table_exists(cur, table) or not column_exists(cur, table, "id"):
        return set()
    sql = f"select id from {quote_ident(table)} where {where_sql} {order_sql}"
    if limit and limit > 0:
        sql += " limit %s"
        params = (*params, limit)
    cur.execute(sql, params)
    return {int(row[0]) for row in cur.fetchall() if row[0] is not None}


def select_single_column_set(cur, sql: str, params: tuple[Any, ...] = ()) -> set[int]:
    cur.execute(sql, params)
    return {int(row[0]) for row in cur.fetchall() if row[0] is not None}


def add_existing_ids(cur, table: str, ids: set[int]) -> set[int]:
    if not ids or not table_exists(cur, table):
        return set()
    cur.execute(f"select id from {quote_ident(table)} where id = any(%s)", (list(ids),))
    return {int(row[0]) for row in cur.fetchall()}


def get_rows_by_ids(cur, table: str, info: TableInfo, ids: set[int]) -> list[tuple[Any, ...]]:
    if not ids or info.single_pk != "id":
        return []
    cols = ", ".join(quote_ident(c) for c in info.columns)
    sql = f"select {cols} from {quote_ident(table)} where {quote_ident(info.single_pk)} = any(%s) order by {quote_ident(info.single_pk)}"
    cur.execute(sql, (list(ids),))
    return list(cur.fetchall())


def get_rows_by_criteria(
    cur,
    table: str,
    info: TableInfo,
    criteria: list[tuple[str, tuple[Any, ...]]],
) -> list[tuple[Any, ...]]:
    if not criteria:
        return []
    cols = ", ".join(quote_ident(c) for c in info.columns)
    rows: list[tuple[Any, ...]] = []
    seen: set[tuple[str, ...]] = set()
    for where_sql, params in criteria:
        cur.execute(f"select {cols} from {quote_ident(table)} where {where_sql}", params)
        for row in cur.fetchall():
            key = tuple(repr(value) for value in row)
            if key in seen:
                continue
            seen.add(key)
            rows.append(row)
    return rows


def conflict_clause(info: TableInfo) -> str:
    columns_for_conflict = info.conflict_columns
    if not columns_for_conflict:
        return ""
    columns = ", ".join(quote_ident(column) for column in columns_for_conflict)
    return f" on conflict ({columns}) do nothing"


def adapt_value(value: Any) -> Any:
    if isinstance(value, dict):
        return Json(value)
    return value


def adapt_row(row: tuple[Any, ...]) -> tuple[Any, ...]:
    return tuple(adapt_value(value) for value in row)


def collect_parent_namespaces(cur, namespace_ids: set[int]) -> set[int]:
    if not namespace_ids or not table_exists(cur, "namespaces"):
        return set()
    result = set(namespace_ids)
    frontier = set(namespace_ids)
    while frontier:
        cur.execute(
            "select id, parent_id from namespaces where id = any(%s)",
            (list(frontier),),
        )
        next_ids: set[int] = set()
        for ns_id, parent_id in cur.fetchall():
            if ns_id is not None:
                result.add(int(ns_id))
            if parent_id is not None and int(parent_id) not in result:
                next_ids.add(int(parent_id))
        frontier = next_ids
    return result


def add_rows_by_fk(
    cur,
    selected: dict[str, set[int]],
    criteria: dict[str, list[tuple[str, tuple[Any, ...]]]],
    table: str,
    fk_col: str,
    ids: set[int],
    limit: int | None = None,
    order_sql: str | None = None,
) -> None:
    if not ids or not table_exists(cur, table) or not column_exists(cur, table, fk_col):
        return
    where_sql = f"{quote_ident(fk_col)} = any(%s)"
    params = (list(ids),)
    if column_exists(cur, table, "id"):
        selected[table] |= select_ids(cur, table, where_sql, params, limit, order_sql or "order by id")
    else:
        criteria[table].append((where_sql, params))


def add_rows_by_type_and_fk(
    cur,
    selected: dict[str, set[int]],
    criteria: dict[str, list[tuple[str, tuple[Any, ...]]]],
    table: str,
    type_col: str,
    type_value: str,
    fk_col: str,
    ids: set[int],
    limit: int | None = None,
    order_sql: str | None = None,
) -> None:
    if (
        not ids
        or not table_exists(cur, table)
        or not column_exists(cur, table, type_col)
        or not column_exists(cur, table, fk_col)
    ):
        return
    where_sql = f"{quote_ident(type_col)} = %s and {quote_ident(fk_col)} = any(%s)"
    params = (type_value, list(ids))
    if column_exists(cur, table, "id"):
        selected[table] |= select_ids(cur, table, where_sql, params, limit, order_sql or "order by id")
    else:
        criteria[table].append((where_sql, params))


def collect_distinct_values(
    cur,
    table: str,
    column: str,
    where_sql: str,
    params: tuple[Any, ...],
) -> set[int]:
    if not table_exists(cur, table) or not column_exists(cur, table, column):
        return set()
    cur.execute(
        f"select distinct {quote_ident(column)} from {quote_ident(table)} where {where_sql} and {quote_ident(column)} is not null",
        params,
    )
    return {int(row[0]) for row in cur.fetchall() if row[0] is not None}


def collect_user_refs_from_table(
    cur,
    selected: dict[str, set[int]],
    criteria: dict[str, list[tuple[str, tuple[Any, ...]]]],
    table: str,
    columns: tuple[str, ...],
) -> None:
    for column in columns:
        if selected.get(table) and column_exists(cur, table, "id"):
            selected["users"] |= collect_distinct_values(
                cur,
                table,
                column,
                "id = any(%s)",
                (list(selected[table]),),
            )
        for where_sql, params in criteria.get(table, []):
            selected["users"] |= collect_distinct_values(cur, table, column, where_sql, params)


def collect_ids_from_table(
    cur,
    selected: dict[str, set[int]],
    criteria: dict[str, list[tuple[str, tuple[Any, ...]]]],
    source_table: str,
    source_column: str,
    target_table: str,
) -> None:
    if selected.get(source_table) and column_exists(cur, source_table, "id"):
        selected[target_table] |= add_existing_ids(
            cur,
            target_table,
            collect_distinct_values(
                cur,
                source_table,
                source_column,
                "id = any(%s)",
                (list(selected[source_table]),),
            ),
        )
    for where_sql, params in criteria.get(source_table, []):
        selected[target_table] |= add_existing_ids(
            cur,
            target_table,
            collect_distinct_values(cur, source_table, source_column, where_sql, params),
        )


def collect_mini_gitlab_repo_extras(
    cur,
    args: argparse.Namespace,
    selected: dict[str, set[int]],
    criteria: dict[str, list[tuple[str, tuple[Any, ...]]]],
) -> None:
    project_ids = selected.get("projects", set())
    namespace_ids = selected.get("namespaces", set())
    issue_ids = selected.get("issues", set())
    merge_request_ids = selected.get("merge_requests", set())

    for table in ("application_settings", "appearances", "features", "plans", "plan_limits", "shards", "work_item_types"):
        selected[table] |= select_ids(cur, table, "true", (), None, "order by id")

    if namespace_ids:
        for table in (
            "namespace_details",
            "namespace_settings",
            "namespace_statistics",
            "namespace_root_storage_statistics",
            "namespace_limits",
            "namespace_ci_cd_settings",
            "namespace_package_settings",
        ):
            add_rows_by_fk(cur, selected, criteria, table, "namespace_id", namespace_ids)
        selected["organizations"] |= add_existing_ids(
            cur,
            "organizations",
            collect_distinct_values(cur, "namespaces", "organization_id", "id = any(%s)", (list(namespace_ids),)),
        )

    if project_ids:
        project_scoped_tables = (
            "project_settings",
            "project_statistics",
            "project_states",
            "project_repositories",
            "project_ci_cd_settings",
            "project_auto_devops",
            "project_custom_attributes",
            "project_authorizations",
            "project_group_links",
            "project_import_data",
            "project_feature_usages",
            "project_security_settings",
            "project_pages_metadata",
            "project_alerting_settings",
            "project_topics",
            "repository_languages",
            "label_priorities",
            "boards",
            "protected_branches",
            "protected_tags",
            "protected_environments",
            "releases",
            "web_hooks",
            "integrations",
            "push_rules",
            "remote_mirrors",
            "container_expiration_policies",
            "packages_cleanup_policies",
        )
        for table in project_scoped_tables:
            add_rows_by_fk(cur, selected, criteria, table, "project_id", project_ids)
        add_rows_by_fk(cur, selected, criteria, "redirect_routes", "source_id", project_ids)
        add_rows_by_fk(cur, selected, criteria, "project_authorizations", "project_id", project_ids)
        collect_user_refs_from_table(cur, selected, criteria, "project_authorizations", ("user_id",))
        collect_ids_from_table(cur, selected, criteria, "project_topics", "topic_id", "topics")
        collect_ids_from_table(cur, selected, criteria, "repository_languages", "programming_language_id", "programming_languages")

        if selected["project_group_links"]:
            group_ids = collect_distinct_values(
                cur,
                "project_group_links",
                "group_id",
                "id = any(%s)",
                (list(selected["project_group_links"]),),
            )
            selected["namespaces"] |= group_ids
            selected["namespaces"] |= collect_parent_namespaces(cur, group_ids)

    if selected.get("organizations"):
        for table in ("organization_details", "organization_settings", "organization_users"):
            add_rows_by_fk(cur, selected, criteria, table, "organization_id", selected["organizations"])
        collect_user_refs_from_table(cur, selected, criteria, "organization_users", ("user_id",))

    if selected.get("boards"):
        board_ids = selected["boards"]
        for table in ("lists", "board_assignees", "board_labels", "board_project_recent_visits"):
            add_rows_by_fk(cur, selected, criteria, table, "board_id", board_ids)
        collect_user_refs_from_table(cur, selected, criteria, "boards", ("user_id",))
        collect_user_refs_from_table(cur, selected, criteria, "board_assignees", ("assignee_id",))
        collect_ids_from_table(cur, selected, criteria, "board_labels", "label_id", "labels")
        collect_ids_from_table(cur, selected, criteria, "lists", "label_id", "labels")
        collect_user_refs_from_table(cur, selected, criteria, "lists", ("user_id",))

    if selected.get("lists"):
        add_rows_by_fk(cur, selected, criteria, "list_user_preferences", "list_id", selected["lists"])
        collect_user_refs_from_table(cur, selected, criteria, "list_user_preferences", ("user_id",))

    if selected.get("protected_branches"):
        protected_branch_ids = selected["protected_branches"]
        for table in (
            "protected_branch_push_access_levels",
            "protected_branch_merge_access_levels",
            "protected_branch_unprotect_access_levels",
        ):
            add_rows_by_fk(cur, selected, criteria, table, "protected_branch_id", protected_branch_ids)
            collect_user_refs_from_table(cur, selected, criteria, table, ("user_id",))

    if selected.get("protected_tags"):
        add_rows_by_fk(cur, selected, criteria, "protected_tag_create_access_levels", "protected_tag_id", selected["protected_tags"])
        collect_user_refs_from_table(cur, selected, criteria, "protected_tag_create_access_levels", ("user_id",))

    if selected.get("protected_environments"):
        protected_environment_ids = selected["protected_environments"]
        for table in ("protected_environment_deploy_access_levels", "protected_environment_approval_rules"):
            add_rows_by_fk(cur, selected, criteria, table, "protected_environment_id", protected_environment_ids)
            collect_user_refs_from_table(cur, selected, criteria, table, ("user_id",))

    if issue_ids:
        issue_scoped_tables = (
            "issue_search_data",
            "issue_user_mentions",
            "issue_email_participants",
            "issue_assignment_events",
            "issuable_severities",
            "issuable_slas",
            "issuable_resource_links",
            "resource_label_events",
            "resource_state_events",
            "resource_milestone_events",
            "resource_weight_events",
            "timelogs",
        )
        for table in issue_scoped_tables:
            add_rows_by_fk(cur, selected, criteria, table, "issue_id", issue_ids)
        if table_exists(cur, "issue_links"):
            selected["issue_links"] |= select_ids(
                cur,
                "issue_links",
                "source_id = any(%s) or target_id = any(%s)",
                (list(issue_ids), list(issue_ids)),
                None,
                "order by id",
            )
        add_rows_by_type_and_fk(cur, selected, criteria, "award_emoji", "awardable_type", "Issue", "awardable_id", issue_ids)
        collect_user_refs_from_table(
            cur,
            selected,
            criteria,
            "issue_assignment_events",
            ("user_id", "author_id"),
        )

    if merge_request_ids:
        mr_scoped_tables = (
            "merge_requests_closing_issues",
            "merge_request_blocks",
            "merge_request_user_mentions",
            "merge_request_assignment_events",
            "resource_label_events",
            "resource_state_events",
            "resource_milestone_events",
            "timelogs",
        )
        for table in mr_scoped_tables:
            add_rows_by_fk(cur, selected, criteria, table, "merge_request_id", merge_request_ids)
        add_rows_by_type_and_fk(
            cur,
            selected,
            criteria,
            "award_emoji",
            "awardable_type",
            "MergeRequest",
            "awardable_id",
            merge_request_ids,
        )
        if table_exists(cur, "merge_request_diffs"):
            cur.execute(
                """
                select id
                  from (
                        select id,
                               row_number() over (
                                 partition by merge_request_id
                                 order by created_at desc nulls last, id desc
                               ) rn
                          from merge_request_diffs
                         where merge_request_id = any(%s)
                       ) x
                 where rn <= %s
                 order by id
                """,
                (list(merge_request_ids), args.diffs_per_mr),
            )
            selected["merge_request_diffs"] |= {int(row[0]) for row in cur.fetchall()}
        collect_user_refs_from_table(
            cur,
            selected,
            criteria,
            "merge_request_assignment_events",
            ("user_id", "author_id"),
        )

    if selected.get("merge_request_diffs"):
        diff_ids = selected["merge_request_diffs"]
        add_rows_by_fk(cur, selected, criteria, "merge_request_diff_details", "merge_request_diff_id", diff_ids)
        add_rows_by_fk(cur, selected, criteria, "merge_request_diff_files", "merge_request_diff_id", diff_ids, args.diff_file_limit)
        add_rows_by_fk(cur, selected, criteria, "merge_request_diff_commits", "merge_request_diff_id", diff_ids, args.diff_commit_limit)
        collect_user_refs_from_table(cur, selected, criteria, "merge_request_diff_commits", ("commit_author_id", "committer_id"))

    if selected.get("notes"):
        note_ids = selected["notes"]
        add_rows_by_fk(cur, selected, criteria, "note_metadata", "note_id", note_ids)
        add_rows_by_fk(cur, selected, criteria, "system_note_metadata", "note_id", note_ids)
        add_rows_by_fk(cur, selected, criteria, "diff_note_positions", "note_id", note_ids)
        add_rows_by_type_and_fk(cur, selected, criteria, "award_emoji", "awardable_type", "Note", "awardable_id", note_ids)

    for table in (
        "award_emoji",
        "resource_label_events",
        "resource_state_events",
        "resource_milestone_events",
        "resource_weight_events",
        "timelogs",
        "releases",
    ):
        collect_user_refs_from_table(cur, selected, criteria, table, ("user_id", "author_id"))

    for table in ("resource_label_events", "label_priorities"):
        collect_ids_from_table(cur, selected, criteria, table, "label_id", "labels")
    for table in ("resource_milestone_events", "milestone_releases"):
        collect_ids_from_table(cur, selected, criteria, table, "milestone_id", "milestones")

    if selected.get("releases"):
        release_ids = selected["releases"]
        for table in ("release_links", "milestone_releases", "evidences"):
            add_rows_by_fk(cur, selected, criteria, table, "release_id", release_ids)


def collect(cur, args: argparse.Namespace) -> tuple[dict[str, set[int]], dict[str, list[tuple[str, tuple[Any, ...]]]]]:
    selected: dict[str, set[int]] = defaultdict(set)
    criteria: dict[str, list[tuple[str, tuple[Any, ...]]]] = defaultdict(list)
    configured_projects = parse_csv_ints(args.project_ids)
    must_projects = parse_csv_ints(args.must_project_ids)

    if configured_projects:
        selected["projects"] |= add_existing_ids(cur, "projects", configured_projects)
    else:
        order_column = "last_activity_at" if column_exists(cur, "projects", "last_activity_at") else "updated_at"
        selected["projects"] |= select_ids(
            cur,
            "projects",
            "coalesce(pending_delete, false) = false" if column_exists(cur, "projects", "pending_delete") else "true",
            (),
            args.max_projects,
            f"order by {quote_ident(order_column)} desc nulls last, id desc",
        )
        selected["projects"] |= add_existing_ids(cur, "projects", must_projects)

    project_ids = selected["projects"]
    project_filter = "project_id = any(%s)" if project_ids else "true"
    project_params: tuple[Any, ...] = (list(project_ids),) if project_ids else ()
    selected["issues"] |= select_ids(
        cur,
        "issues",
        project_filter,
        project_params,
        args.issue_limit,
        "order by updated_at desc nulls last, id desc",
    )

    mr_project_filter = (
        "(target_project_id = any(%s) or source_project_id = any(%s))" if project_ids else "true"
    )
    mr_project_params: tuple[Any, ...] = (list(project_ids), list(project_ids)) if project_ids else ()
    selected["merge_requests"] |= select_ids(
        cur,
        "merge_requests",
        mr_project_filter,
        mr_project_params,
        args.merge_request_limit,
        "order by updated_at desc nulls last, id desc",
    )

    if selected["issues"]:
        cur.execute(
            "select distinct project_id from issues where id = any(%s) and project_id is not null",
            (list(selected["issues"]),),
        )
        selected["projects"] |= {int(row[0]) for row in cur.fetchall()}

    if selected["merge_requests"]:
        cur.execute(
            """
            select distinct x.project_id
              from (
                    select target_project_id as project_id from merge_requests where id = any(%s)
                    union
                    select source_project_id as project_id from merge_requests where id = any(%s)
                   ) x
             where x.project_id is not null
            """,
            (list(selected["merge_requests"]), list(selected["merge_requests"])),
        )
        selected["projects"] |= {int(row[0]) for row in cur.fetchall()}

    if selected["projects"] and table_exists(cur, "projects"):
        namespace_cols = [c for c in ("namespace_id", "project_namespace_id") if column_exists(cur, "projects", c)]
        for col in namespace_cols:
            cur.execute(
                f"select distinct {quote_ident(col)} from projects where id = any(%s) and {quote_ident(col)} is not null",
                (list(selected["projects"]),),
            )
            selected["namespaces"] |= {int(row[0]) for row in cur.fetchall()}
        selected["namespaces"] |= collect_parent_namespaces(cur, selected["namespaces"])

        if column_exists(cur, "projects", "creator_id"):
            cur.execute(
                "select distinct creator_id from projects where id = any(%s) and creator_id is not null",
                (list(selected["projects"]),),
            )
            selected["users"] |= {int(row[0]) for row in cur.fetchall()}

    if selected["issues"]:
        for col in ("author_id", "updated_by_id", "last_edited_by_id", "closed_by_id"):
            if column_exists(cur, "issues", col):
                cur.execute(
                    f"select distinct {quote_ident(col)} from issues where id = any(%s) and {quote_ident(col)} is not null",
                    (list(selected["issues"]),),
                )
                selected["users"] |= {int(row[0]) for row in cur.fetchall()}
        if column_exists(cur, "issues", "milestone_id"):
            cur.execute(
                "select distinct milestone_id from issues where id = any(%s) and milestone_id is not null",
                (list(selected["issues"]),),
            )
            selected["milestones"] |= {int(row[0]) for row in cur.fetchall()}

    if selected["merge_requests"]:
        for col in ("author_id", "merge_user_id", "updated_by_id", "last_edited_by_id"):
            if column_exists(cur, "merge_requests", col):
                cur.execute(
                    f"select distinct {quote_ident(col)} from merge_requests where id = any(%s) and {quote_ident(col)} is not null",
                    (list(selected["merge_requests"]),),
                )
                selected["users"] |= {int(row[0]) for row in cur.fetchall()}
        if column_exists(cur, "merge_requests", "milestone_id"):
            cur.execute(
                "select distinct milestone_id from merge_requests where id = any(%s) and milestone_id is not null",
                (list(selected["merge_requests"]),),
            )
            selected["milestones"] |= {int(row[0]) for row in cur.fetchall()}

    child_specs = [
        ("issue_metrics", "issue_id", "issues"),
        ("issue_assignees", "issue_id", "issues"),
        ("merge_request_metrics", "merge_request_id", "merge_requests"),
        ("merge_request_assignees", "merge_request_id", "merge_requests"),
        ("merge_request_reviewers", "merge_request_id", "merge_requests"),
    ]
    for table, fk_col, parent_table in child_specs:
        if selected[parent_table] and table_exists(cur, table) and column_exists(cur, table, fk_col):
            where_sql = f"{quote_ident(fk_col)} = any(%s)"
            params = (list(selected[parent_table]),)
            if column_exists(cur, table, "id"):
                selected[table] |= select_ids(cur, table, where_sql, params, None, "order by id")
            else:
                criteria[table].append((where_sql, params))

    for table in ("issue_assignees", "merge_request_assignees", "merge_request_reviewers"):
        if selected[table] and table_exists(cur, table) and column_exists(cur, table, "user_id"):
            cur.execute(
                f"select distinct user_id from {quote_ident(table)} where id = any(%s) and user_id is not null",
                (list(selected[table]),),
            )
            selected["users"] |= {int(row[0]) for row in cur.fetchall()}
        if criteria[table] and table_exists(cur, table) and column_exists(cur, table, "user_id"):
            for where_sql, params in criteria[table]:
                cur.execute(
                    f"select distinct user_id from {quote_ident(table)} where {where_sql} and user_id is not null",
                    params,
                )
                selected["users"] |= {int(row[0]) for row in cur.fetchall()}

    if table_exists(cur, "label_links"):
        link_ids: set[int] = set()
        if selected["issues"]:
            link_ids |= select_ids(
                cur,
                "label_links",
                "target_type = 'Issue' and target_id = any(%s)",
                (list(selected["issues"]),),
                None,
                "order by id",
            )
        if selected["merge_requests"]:
            link_ids |= select_ids(
                cur,
                "label_links",
                "target_type = 'MergeRequest' and target_id = any(%s)",
                (list(selected["merge_requests"]),),
                None,
                "order by id",
            )
        selected["label_links"] |= link_ids
        if link_ids:
            cur.execute(
                "select distinct label_id from label_links where id = any(%s) and label_id is not null",
                (list(link_ids),),
            )
            selected["labels"] |= {int(row[0]) for row in cur.fetchall()}

    if selected["labels"] and table_exists(cur, "labels"):
        for col, target in (("project_id", "projects"), ("group_id", "namespaces")):
            if column_exists(cur, "labels", col):
                cur.execute(
                    f"select distinct {quote_ident(col)} from labels where id = any(%s) and {quote_ident(col)} is not null",
                    (list(selected["labels"]),),
                )
                selected[target] |= {int(row[0]) for row in cur.fetchall()}
        selected["namespaces"] |= collect_parent_namespaces(cur, selected["namespaces"])

    if selected["milestones"] and table_exists(cur, "milestones"):
        for col, target in (("project_id", "projects"), ("group_id", "namespaces")):
            if column_exists(cur, "milestones", col):
                cur.execute(
                    f"select distinct {quote_ident(col)} from milestones where id = any(%s) and {quote_ident(col)} is not null",
                    (list(selected["milestones"]),),
                )
                selected[target] |= {int(row[0]) for row in cur.fetchall()}
        selected["namespaces"] |= collect_parent_namespaces(cur, selected["namespaces"])

    if table_exists(cur, "notes"):
        note_ids: set[int] = set()
        if args.notes_per_parent > 0 and (selected["issues"] or selected["merge_requests"]):
            conditions: list[str] = []
            params: list[Any] = []
            if selected["issues"]:
                conditions.append("(noteable_type = 'Issue' and noteable_id = any(%s))")
                params.append(list(selected["issues"]))
            if selected["merge_requests"]:
                conditions.append("(noteable_type = 'MergeRequest' and noteable_id = any(%s))")
                params.append(list(selected["merge_requests"]))
            sql = f"""
                select id
                  from (
                        select id,
                               row_number() over (
                                 partition by noteable_type, noteable_id
                                 order by updated_at desc nulls last, created_at desc nulls last, id desc
                               ) rn
                          from notes
                         where {" or ".join(conditions)}
                       ) x
                 where rn <= %s
                 order by id
            """
            params.append(args.notes_per_parent)
            cur.execute(sql, tuple(params))
            note_ids |= {int(row[0]) for row in cur.fetchall()}
        if args.note_limit > 0 and note_ids:
            note_ids = set(sorted(note_ids)[-args.note_limit:])
        selected["notes"] |= note_ids
        if note_ids:
            for col in ("author_id", "updated_by_id", "resolved_by_id"):
                if column_exists(cur, "notes", col):
                    cur.execute(
                        f"select distinct {quote_ident(col)} from notes where id = any(%s) and {quote_ident(col)} is not null",
                        (list(note_ids),),
                    )
                    selected["users"] |= {int(row[0]) for row in cur.fetchall()}

    if args.event_limit > 0 and table_exists(cur, "events"):
        event_ids: set[int] = set()
        if selected["issues"] and column_exists(cur, "events", "target_type") and column_exists(cur, "events", "target_id"):
            event_ids |= select_ids(
                cur,
                "events",
                "target_type = 'Issue' and target_id = any(%s)",
                (list(selected["issues"]),),
                args.event_limit,
                "order by created_at desc nulls last, id desc",
            )
        if selected["merge_requests"] and column_exists(cur, "events", "target_type") and column_exists(cur, "events", "target_id"):
            event_ids |= select_ids(
                cur,
                "events",
                "target_type = 'MergeRequest' and target_id = any(%s)",
                (list(selected["merge_requests"]),),
                args.event_limit,
                "order by created_at desc nulls last, id desc",
            )
        selected["events"] |= set(sorted(event_ids)[-args.event_limit:])
        if selected["events"] and column_exists(cur, "events", "author_id"):
            cur.execute(
                "select distinct author_id from events where id = any(%s) and author_id is not null",
                (list(selected["events"]),),
            )
            selected["users"] |= {int(row[0]) for row in cur.fetchall()}

    if selected["projects"] and table_exists(cur, "members"):
        member_conditions: list[str] = []
        member_params: list[Any] = []
        if column_exists(cur, "members", "source_type") and column_exists(cur, "members", "source_id"):
            member_conditions.append("(source_type = 'Project' and source_id = any(%s))")
            member_params.append(list(selected["projects"]))
            if selected["namespaces"]:
                member_conditions.append("(source_type = 'Namespace' and source_id = any(%s))")
                member_params.append(list(selected["namespaces"]))
        if member_conditions:
            selected["members"] |= select_ids(
                cur,
                "members",
                " or ".join(member_conditions),
                tuple(member_params),
                args.member_limit,
                "order by id",
            )
            if selected["members"] and column_exists(cur, "members", "user_id"):
                cur.execute(
                    "select distinct user_id from members where id = any(%s) and user_id is not null",
                    (list(selected["members"]),),
                )
                selected["users"] |= {int(row[0]) for row in cur.fetchall()}

    if args.profile == "mini_gitlab_repo":
        collect_mini_gitlab_repo_extras(cur, args, selected, criteria)

    if selected["users"] and table_exists(cur, "user_details") and column_exists(cur, "user_details", "user_id"):
        selected["user_details"] |= select_ids(
            cur,
            "user_details",
            "user_id = any(%s)",
            (list(selected["users"]),),
            None,
            "order by id",
        )

    if selected["projects"] and table_exists(cur, "project_features") and column_exists(cur, "project_features", "project_id"):
        selected["project_features"] |= select_ids(
            cur,
            "project_features",
            "project_id = any(%s)",
            (list(selected["projects"]),),
            None,
            "order by id",
        )

    if table_exists(cur, "routes"):
        route_ids: set[int] = set()
        if selected["projects"]:
            route_ids |= select_ids(
                cur,
                "routes",
                "source_type = 'Project' and source_id = any(%s)",
                (list(selected["projects"]),),
                None,
                "order by id",
            )
        if selected["namespaces"]:
            route_ids |= select_ids(
                cur,
                "routes",
                "source_type = 'Namespace' and source_id = any(%s)",
                (list(selected["namespaces"]),),
                None,
                "order by id",
            )
        selected["routes"] |= route_ids

    optional_children = [
        ("todos", "target_id", "target_type", {"Issue": "issues", "MergeRequest": "merge_requests"}),
        ("ci_pipelines", "project_id", None, {"Project": "projects"}),
        ("ci_builds", "project_id", None, {"Project": "projects"}),
        ("environments", "project_id", None, {"Project": "projects"}),
        ("deployments", "project_id", None, {"Project": "projects"}),
    ]
    for table, fk_col, type_col, mapping in optional_children:
        if not table_exists(cur, table) or not column_exists(cur, table, fk_col):
            continue
        if table in ("ci_pipelines", "ci_builds", "environments", "deployments") and args.ci_limit <= 0:
            continue
        ids: set[int] = set()
        if type_col and column_exists(cur, table, type_col):
            for type_value, parent_table in mapping.items():
                if selected[parent_table]:
                    ids |= select_ids(
                        cur,
                        table,
                        f"{quote_ident(type_col)} = %s and {quote_ident(fk_col)} = any(%s)",
                        (type_value, list(selected[parent_table])),
                        args.todo_limit if table == "todos" else args.ci_limit,
                        "order by id desc",
                    )
        elif selected["projects"]:
            ids |= select_ids(
                cur,
                table,
                f"{quote_ident(fk_col)} = any(%s)",
                (list(selected["projects"]),),
                args.ci_limit,
                "order by id desc",
            )
        selected[table] |= ids

    return (
        {table: ids for table, ids in selected.items() if ids},
        {table: items for table, items in criteria.items() if items},
    )


def write_export(
    cur,
    selected: dict[str, set[int]],
    criteria: dict[str, list[tuple[str, tuple[Any, ...]]]],
    args: argparse.Namespace,
) -> dict[str, int]:
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    counts: dict[str, int] = {}
    max_bytes = args.max_compressed_mb * MB
    started = datetime.now().isoformat(timespec="seconds")
    manifest = {
        "created_at": started,
        "source": {
            "host": args.host,
            "port": args.port,
            "database": args.database,
            "source_instance": args.source_instance,
            "profile": args.profile,
        },
        "limits": {
            "max_projects": args.max_projects,
            "issue_limit": args.issue_limit,
            "merge_request_limit": args.merge_request_limit,
            "notes_per_parent": args.notes_per_parent,
            "note_limit": args.note_limit,
            "event_limit": args.event_limit,
            "member_limit": args.member_limit,
            "ci_limit": args.ci_limit,
            "diffs_per_mr": args.diffs_per_mr,
            "diff_file_limit": args.diff_file_limit,
            "diff_commit_limit": args.diff_commit_limit,
        },
    }
    with gzip.open(out, "wt", encoding="utf-8", newline="\n", compresslevel=args.compresslevel) as fh:
        fh.write("-- GitLab bounded SQL export. Data rows only; target schema must already exist.\n")
        fh.write("-- Generated by scripts/gitlab_bounded_sql_export.py\n")
        fh.write("-- Manifest: " + json.dumps(manifest, ensure_ascii=False, sort_keys=True) + "\n")
        fh.write("set client_encoding = 'UTF8';\n")
        fh.write("set statement_timeout = 0;\n\n")
        for table in DEFAULT_TABLE_ORDER:
            ids = selected.get(table, set())
            table_criteria = criteria.get(table, [])
            if not ids and not table_criteria:
                continue
            info = load_table_info(cur, table)
            if not info:
                print(f"skip {table}: table missing")
                continue
            rows = get_rows_by_ids(cur, table, info, ids)
            rows.extend(get_rows_by_criteria(cur, table, info, table_criteria))
            if not rows:
                continue
            column_sql = ", ".join(quote_ident(c) for c in info.columns)
            value_tpl = "(" + placeholders(len(info.columns)) + ")"
            conflict = conflict_clause(info)
            prefix = f"insert into {quote_ident(table)} ({column_sql}) values "
            fh.write(f"-- table: {table}, rows: {len(rows)}\n")
            for row in rows:
                line = cur.mogrify(prefix + value_tpl + conflict + ";\n", adapt_row(row)).decode("utf-8", errors="replace")
                fh.write(line)
            fh.write("\n")
            counts[table] = len(rows)
            fh.flush()
            size = out.stat().st_size
            print(f"exported {table}: {len(rows)} rows, gzip={size / MB:.2f} MB")
            if size > max_bytes:
                die(
                    f"Compressed export exceeded {args.max_compressed_mb} MB at table {table}. "
                    "Lower --issue-limit/--merge-request-limit/--notes-per-parent/--event-limit and rerun."
                )
        fh.write("-- Counts: " + json.dumps(counts, ensure_ascii=False, sort_keys=True) + "\n")
    final_size = out.stat().st_size
    if final_size > max_bytes:
        die(f"Compressed export is {final_size / MB:.2f} MB, over {args.max_compressed_mb} MB")
    print(f"done: {out} ({final_size / MB:.2f} MB compressed)")
    return counts


def positive_int(value: str) -> int:
    try:
        parsed = int(value)
    except ValueError:
        raise argparse.ArgumentTypeError(f"not an integer: {value}")
    if parsed < 0:
        raise argparse.ArgumentTypeError("must be >= 0")
    return parsed


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Export bounded GitLab PostgreSQL SQL gzip.")
    parser.add_argument("--host", default=EXPORT_HOST)
    parser.add_argument("--port", type=int, default=EXPORT_PORT)
    parser.add_argument("--database", default=EXPORT_DATABASE)
    parser.add_argument("--username", default=EXPORT_USERNAME)
    parser.add_argument("--password", nargs="?", const="", default=os.getenv("GITLAB_DB_PASSWORD", EXPORT_PASSWORD))
    parser.add_argument("--source-instance", default=EXPORT_SOURCE_INSTANCE)
    parser.add_argument("--profile", default=EXPORT_PROFILE, choices=("mirror_sample", "mini_gitlab_repo"))
    parser.add_argument("--output", default=EXPORT_OUTPUT)
    parser.add_argument("--project-ids", default=EXPORT_PROJECT_IDS, help="Comma separated project ids. Empty means recent projects.")
    parser.add_argument("--must-project-ids", default=EXPORT_MUST_PROJECT_IDS, help="Project ids always included when --project-ids is empty.")
    parser.add_argument("--max-projects", type=positive_int, default=EXPORT_MAX_PROJECTS)
    parser.add_argument("--issue-limit", type=positive_int, default=EXPORT_ISSUE_LIMIT)
    parser.add_argument("--merge-request-limit", type=positive_int, default=EXPORT_MERGE_REQUEST_LIMIT)
    parser.add_argument("--notes-per-parent", type=positive_int, default=EXPORT_NOTES_PER_PARENT)
    parser.add_argument("--note-limit", type=positive_int, default=EXPORT_NOTE_LIMIT)
    parser.add_argument("--event-limit", type=positive_int, default=EXPORT_EVENT_LIMIT)
    parser.add_argument("--member-limit", type=positive_int, default=EXPORT_MEMBER_LIMIT)
    parser.add_argument("--todo-limit", type=positive_int, default=EXPORT_TODO_LIMIT)
    parser.add_argument("--ci-limit", type=positive_int, default=EXPORT_CI_LIMIT)
    parser.add_argument("--diffs-per-mr", type=positive_int, default=EXPORT_DIFFS_PER_MR)
    parser.add_argument("--diff-file-limit", type=positive_int, default=EXPORT_DIFF_FILE_LIMIT)
    parser.add_argument("--diff-commit-limit", type=positive_int, default=EXPORT_DIFF_COMMIT_LIMIT)
    parser.add_argument("--max-compressed-mb", type=positive_int, default=EXPORT_MAX_COMPRESSED_MB)
    parser.add_argument("--compresslevel", type=int, default=EXPORT_COMPRESS_LEVEL, choices=range(1, 10))
    parser.add_argument("--connect-timeout", type=int, default=EXPORT_CONNECT_TIMEOUT)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    started = time.time()
    with connect(args) as conn:
        conn.set_session(readonly=True, autocommit=True)
        with conn.cursor() as cur:
            for table in ("projects", "users", "issues", "merge_requests", "label_links", "labels", "notes"):
                if not table_exists(cur, table):
                    die(f"Required GitLab table is missing: {table}")
            selected, criteria = collect(cur, args)
            if not selected.get("issues") and not selected.get("merge_requests"):
                die("No issues or merge requests selected. Check --project-ids and limits.")
            print("selected row counts:")
            for table in DEFAULT_TABLE_ORDER:
                id_count = len(selected.get(table, set()))
                criteria_count = len(criteria.get(table, []))
                if id_count or criteria_count:
                    suffix = f", criteria={criteria_count}" if criteria_count else ""
                    print(f"  {table}: {id_count}{suffix}")
            write_export(cur, selected, criteria, args)
    print(f"elapsed: {time.time() - started:.1f}s")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
