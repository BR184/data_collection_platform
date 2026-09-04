#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""一次性抽取黄金基线回归测试夹具并冻结。

产出（全部为测试资源；冻结后不得修改，重新运行即生成新基线，必须经用户确认并更新
docs/decisions.md 决策记录）：

  backend/src/test/resources/golden-baseline/fixtures/gitlab-schema.sql.gz       GitLab 源库结构
  backend/src/test/resources/golden-baseline/fixtures/gitlab-source-slice.sql.gz GitLab 源库数据切片
  backend/src/test/resources/golden-baseline/fixtures/platform-seed.sql.gz       平台侧种子数据
  backend/src/test/resources/golden-baseline/baseline-manifest.json              冻结清单（行数 + SHA-256）

数据来源（全部只读）：
  GitLab 源库：容器 gitlab-data-web-1 的 gitlabhq_full_import_test。结构经 docker exec pg_dump；
    数据切片经 socket proxy 15434（用户 gitlab-psql）复用 scripts/gitlab_bounded_sql_export.py。
  平台库：本地开发栈 qaflex-dev-postgres-15432（凭据读 backend/.env.local）。

平台种子只包含运行期产生且被读路径消费的业务数据；迁移种子（权限、标签组、目录等）
不在其中，由测试运行时 Flyway 迁移自行建立。
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

import psycopg2

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

from gitlab_bounded_sql_export import adapt_row, quote_ident  # noqa: E402

GITLAB_CONTAINER = "gitlab-data-web-1"
GITLAB_DATABASE = "gitlabhq_full_import_test"
GITLAB_PROXY_HOST = "127.0.0.1"
GITLAB_PROXY_PORT = 15434
GITLAB_PROXY_USER = "gitlab-psql"
GITLAB_SOURCE_INSTANCE = "cc"

SLICE_ARGS = {
    # mini_gitlab_repo：完整依赖闭包（含 resource_label_events / resource_state_events /
    # milestone 事件等事实链路关键表）；mirror_sample 不闭包这些表，不能用于基线。
    "profile": "mini_gitlab_repo",
    "project-ids": "9,325",
    "issue-limit": "1200",
    "merge-request-limit": "1500",
    "notes-per-parent": "4",
    "note-limit": "8000",
    "event-limit": "4000",
    "member-limit": "2000",
    "todo-limit": "0",
    "ci-limit": "0",
    "diffs-per-mr": "1",
    "diff-file-limit": "1500",
    "diff-commit-limit": "1500",
    "max-compressed-mb": "20",
    "source-instance": GITLAB_SOURCE_INSTANCE,
}

PLATFORM_SEED_TABLES = [
    "code_review_match_mode_records",
    "legacy_mysql_imported_rows",
    "legacy_mongo_imported_collections",
    "legacy_mongo_imported_documents",
    "review_data_match_mode_reports",
    "review_data_match_mode_problem_details",
    "review_data_match_mode_descriptions",
    "review_data_match_mode_contents",
    "review_data_match_mode_edit_links",
    "review_data_match_mode_problem_edit_links",
    "review_records",
    "review_record_experts",
    "review_problem_items",
    "review_record_descriptions",
    "review_record_contents",
    "code_review_external_metrics",
    # 迁移播种/配置表一律不抽取（golden 基线使用全新库的迁移种子值）：
    # - code_review_match_mode_db_settings、code_review_dgm_gitlab_project_source_settings、
    #   bi_cat_mirror_configs 为 singleton 配置表（V20260702_06/V20260707_02/V20260806_01）；
    # - label_groups（V20260622_07 系统默认标签组）、issue_scope_catalogs/groups/members
    #   （V20260727_02 数据迁移 + V20260821_01 bootstrap）、platform_role_permissions
    #   （V20260720_01/V20260804_01 默认权限映射）均为迁移播种，开发库内容即迁移产物；
    # - collect_form_records 在开发库为空表，collect-forms 端点基线为空态快照。
]

BATCH_ROWS = 500

# 事实链路关键表：切片导出后必须包含，缺失即失败（防止闭包缺口静默进入基线）。
REQUIRED_SLICE_TABLES = [
    "projects",
    "namespaces",
    "users",
    "issues",
    "issue_metrics",
    "issue_assignees",
    "merge_requests",
    "merge_request_metrics",
    "merge_request_assignees",
    "labels",
    "label_links",
    "resource_label_events",
    "resource_state_events",
    "resource_milestone_events",
    "milestones",
    "notes",
    "events",
]


def die(message: str, code: int = 2) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(code)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Extract golden baseline fixtures (one-time freeze).")
    parser.add_argument("--output-dir", default=str(ROOT / "backend/src/test/resources/golden-baseline"))
    parser.add_argument("--gitlab-container", default=GITLAB_CONTAINER)
    parser.add_argument("--skip-schema", action="store_true", help="Skip GitLab schema dump.")
    parser.add_argument("--skip-slice", action="store_true", help="Skip GitLab data slice export.")
    parser.add_argument("--skip-platform-seed", action="store_true", help="Skip platform seed export.")
    return parser.parse_args()


def load_platform_credentials() -> dict[str, str]:
    env_file = ROOT / "backend" / ".env.local"
    creds = {"host": "127.0.0.1", "port": 15432, "dbname": "qaflex", "user": "qaflex", "password": ""}
    if not env_file.exists():
        die("backend/.env.local 不存在，无法获取平台库连接信息")
    for line in env_file.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        if key == "DATASOURCE_URL":
            # jdbc:postgresql://127.0.0.1:15432/qaflex
            tail = value.split("//", 1)[-1]
            host_port, _, dbname = tail.partition("/")
            host, _, port = host_port.partition(":")
            creds.update(host=host, port=int(port or 5432), dbname=dbname or "qaflex")
        elif key == "DATASOURCE_USERNAME":
            creds["user"] = value
        elif key == "DATASOURCE_PASSWORD":
            creds["password"] = value
    return creds


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def git_commit() -> str:
    try:
        return subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()
    except subprocess.CalledProcessError:
        return "unknown"


def export_gitlab_schema(output: Path, container: str) -> None:
    print(f"[1/3] 导出 GitLab 源库结构：{container}/{GITLAB_DATABASE} ...")
    started = time.time()
    proc = subprocess.run(
        [
            "docker",
            "exec",
            "-u",
            "gitlab-psql",
            container,
            "/opt/gitlab/embedded/bin/pg_dump",
            "-h",
            "/var/opt/gitlab/postgresql",
            "-U",
            "gitlab-psql",
            "--schema-only",
            "--no-owner",
            "--no-privileges",
            GITLAB_DATABASE,
        ],
        capture_output=True,
        check=True,
    )
    if not proc.stdout:
        die("pg_dump 输出为空")
    with gzip.open(output, "wt", encoding="utf-8", newline="\n", compresslevel=9) as handle:
        handle.write(proc.stdout.decode("utf-8"))
    print(f"  完成：{output.name}（{output.stat().st_size / 1024:.0f} KB，{time.time() - started:.1f}s）")


def export_gitlab_slice(output: Path) -> dict[str, int]:
    print(f"[2/3] 导出 GitLab 数据切片（项目 {SLICE_ARGS['project-ids']}，{SLICE_ARGS['profile']}）...")
    started = time.time()
    command = [
        sys.executable,
        str(ROOT / "scripts" / "gitlab_bounded_sql_export.py"),
        "--host",
        GITLAB_PROXY_HOST,
        "--port",
        str(GITLAB_PROXY_PORT),
        "--database",
        GITLAB_DATABASE,
        "--username",
        GITLAB_PROXY_USER,
        "--password",
        "",
        "--output",
        str(output),
    ]
    for key, value in SLICE_ARGS.items():
        command += [f"--{key}", value]
    result = subprocess.run(command, cwd=ROOT, check=True)
    if result.returncode != 0:
        die("GitLab 切片导出失败")
    counts_line = ""
    with gzip.open(output, "rt", encoding="utf-8") as handle:
        for line in handle:
            if line.startswith("-- Counts:"):
                counts_line = line[len("-- Counts:") :].strip()
    counts = {key: int(value) for key, value in json.loads(counts_line).items()} if counts_line else {}
    missing = [table for table in REQUIRED_SLICE_TABLES if table not in counts or counts[table] <= 0]
    if missing:
        die(f"切片缺少事实链路关键表：{missing}（检查导出 profile 的依赖闭包）")
    print(f"  完成：{output.name}（{output.stat().st_size / 1024 / 1024:.2f} MB，{time.time() - started:.1f}s，{len(counts)} 张表有数据）")
    return counts


def primary_key_columns(cur, table: str) -> list[str]:
    cur.execute(
        """
        select a.attname
          from pg_index i
          join pg_class c on c.oid = i.indrelid
          join pg_attribute a on a.attrelid = c.oid and a.attnum = any(i.indkey)
         where c.relname = %s and i.indisprimary
         order by array_position(i.indkey, a.attnum)
        """,
        (table,),
    )
    return [row[0] for row in cur.fetchall()]


def table_columns(cur, table: str) -> list[str]:
    cur.execute(
        """
        select column_name from information_schema.columns
         where table_schema = 'public' and table_name = %s
         order by ordinal_position
        """,
        (table,),
    )
    return [row[0] for row in cur.fetchall()]


def export_platform_seed(output: Path) -> dict[str, int]:
    print("[3/3] 导出平台侧种子数据 ...")
    started = time.time()
    creds = load_platform_credentials()
    conn = psycopg2.connect(connect_timeout=15, **creds)
    counts: dict[str, int] = {}
    try:
        cur = conn.cursor()
        with gzip.open(output, "wt", encoding="utf-8", newline="\n", compresslevel=9) as handle:
            handle.write("-- Golden baseline platform seed. Deterministic ORDER BY primary key.\n")
            handle.write("set client_encoding = 'UTF8';\n")
            handle.write("set statement_timeout = 0;\n\n")
            for table in PLATFORM_SEED_TABLES:
                columns = table_columns(cur, table)
                if not columns:
                    die(f"平台库缺少表 {table}")
                order_columns = primary_key_columns(cur, table) or columns[:1]
                column_sql = ", ".join(quote_ident(c) for c in columns)
                order_sql = ", ".join(quote_ident(c) for c in order_columns)
                cur.execute(f"select {column_sql} from {quote_ident(table)} order by {order_sql}")
                rows = cur.fetchall()
                counts[table] = len(rows)
                handle.write(f"-- table: {table}, rows: {len(rows)}\n")
                if rows:
                    value_sql = "(" + ", ".join(["%s"] * len(columns)) + ")"
                    prefix = f"insert into {quote_ident(table)} ({column_sql}) values "
                    for start in range(0, len(rows), BATCH_ROWS):
                        batch = rows[start : start + BATCH_ROWS]
                        parts = [
                            cur.mogrify(value_sql, adapt_row(row)).decode("utf-8", errors="replace")
                            for row in batch
                        ]
                        handle.write(prefix + ", ".join(parts) + ";\n")
                handle.write("\n")
                handle.flush()
                print(f"  {table}: {len(rows)} rows, gzip={output.stat().st_size / 1024 / 1024:.2f} MB")
    finally:
        conn.close()
    print(f"  完成：{output.name}（{output.stat().st_size / 1024 / 1024:.2f} MB，{time.time() - started:.1f}s）")
    return counts


def main() -> int:
    args = parse_args()
    output_dir = Path(args.output_dir)
    fixtures_dir = output_dir / "fixtures"
    fixtures_dir.mkdir(parents=True, exist_ok=True)

    slice_counts: dict[str, int] = {}
    platform_counts: dict[str, int] = {}
    schema_path = fixtures_dir / "gitlab-schema.sql.gz"
    slice_path = fixtures_dir / "gitlab-source-slice.sql.gz"
    seed_path = fixtures_dir / "platform-seed.sql.gz"

    if not args.skip_schema:
        export_gitlab_schema(schema_path, args.gitlab_container)
    if not args.skip_slice:
        slice_counts = export_gitlab_slice(slice_path)
    if not args.skip_platform_seed:
        platform_counts = export_platform_seed(seed_path)

    manifest = {
        "frozen_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "repo_commit": git_commit(),
        "fixtures": {
            path.name: {"sha256": sha256_of(path), "bytes": path.stat().st_size}
            for path in (schema_path, slice_path, seed_path)
            if path.exists()
        },
        "gitlab_slice": {
            "source": f"{args.gitlab_container}:{GITLAB_DATABASE}",
            "profile": SLICE_ARGS["profile"],
            "bounds": {key: SLICE_ARGS[key] for key in sorted(SLICE_ARGS)},
            "table_counts": slice_counts,
        },
        "platform_seed": {
            "source": "qaflex-dev-postgres-15432/qaflex",
            "table_counts": platform_counts,
        },
    }
    manifest_path = output_dir / "baseline-manifest.json"
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(f"清单已写入 {manifest_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
