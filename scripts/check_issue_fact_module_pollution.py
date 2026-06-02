#!/usr/bin/env python3
"""Check whether issue_fact.module_names still contains cross-field values.

This is a read-only data quality check for environments that already have a
PostgreSQL platform database. It uses psql instead of a Python DB driver so it
works in offline installs that only ship the PostgreSQL client tools.
"""

from __future__ import annotations

import argparse
import csv
import os
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import parse_qs, urlparse


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_PSQL = ROOT / "tools/postgresql-17.9/pgsql/bin/psql.exe"

SUMMARY_SQL = """
select
  count(*)::bigint as total_issue_fact_rows,
  count(*) filter (where nullif(btrim(coalesce(module_names, '')), '') is not null)::bigint as rows_with_module_names
from issue_fact;
"""

POLLUTION_SQL = """
with module_values as (
  select
      coalesce(source_instance, '') as source_instance,
      project_id,
      issue_iid,
      title,
      btrim(value) as module_value
  from issue_fact
  cross join lateral regexp_split_to_table(coalesce(module_names, ''), ',') as value
  where nullif(btrim(coalesce(module_names, '')), '') is not null
), classified as (
  select
      *,
      case
        when module_value ~ '^\\d+$' then 'pure_number'
        when module_value in ('前端', '后端', '客户端', '服务端') then 'layer_or_role'
        when module_value in ('未识别模块', '缺失模块', '未设定模块') then 'placeholder_module'
        when module_value ~ '^(模块|module|工具箱|项目|客户|状态|分支|版本|里程碑|严重程度|优先级|测试阶段|阶段|类别|分类|缺陷状态|延期原因|原因)[：:].+'
          then 'cross_field_prefix'
        when module_value ~ '^CC[0-9]{4}R[0-9].*客户$' then 'customer_like'
        when module_value ~ '^[A-Za-z]{1,6}[0-9]{3,}R[0-9].*$' then 'release_or_customer_like'
        else null
      end as reason
  from module_values
  where module_value <> ''
)
select
    reason,
    module_value,
    count(*)::bigint as issue_count,
    string_agg(
      concat_ws(
        '',
        nullif(source_instance, ''),
        case when source_instance <> '' then ':' else '' end,
        coalesce(project_id::text, '?'),
        '!',
        coalesce(issue_iid::text, '?')
      ),
      '; ' order by source_instance, project_id, issue_iid
    ) as sample_issue_refs,
    min(title) as sample_title
from classified
where reason is not null
group by reason, module_value
order by issue_count desc, reason, module_value
limit :limit;
"""


@dataclass(frozen=True)
class ConnectionOptions:
    host: str
    port: str
    database: str
    username: str
    password: str
    schema: str | None


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Check issue_fact.module_names for cross-field pollution.",
    )
    parser.add_argument("--dsn", default=os.getenv("FACT_CHECK_DSN") or os.getenv("DATASOURCE_URL") or "")
    parser.add_argument("--host", default=os.getenv("DATASOURCE_HOST") or "")
    parser.add_argument("--port", default=os.getenv("DATASOURCE_PORT") or "")
    parser.add_argument("--database", default=os.getenv("DATASOURCE_DATABASE") or "")
    parser.add_argument("--username", default=os.getenv("DATASOURCE_USERNAME") or "")
    parser.add_argument("--password", default=os.getenv("DATASOURCE_PASSWORD") or "")
    parser.add_argument("--schema", default=os.getenv("DATASOURCE_SCHEMA") or "")
    parser.add_argument("--limit", type=int, default=50, help="Maximum suspicious groups to print.")
    parser.add_argument("--psql", default=os.getenv("PSQL") or "", help="Path to psql executable.")
    parser.add_argument("--print-sql", action="store_true", help="Print the pollution SQL and exit.")
    return parser.parse_args()


def connection_options(args: argparse.Namespace) -> ConnectionOptions:
    dsn = normalize_dsn(args.dsn)
    parsed = urlparse(dsn) if dsn else None
    query = parse_qs(parsed.query) if parsed else {}
    schema = args.schema or first_query_value(query, "currentSchema") or None
    return ConnectionOptions(
        host=args.host or (parsed.hostname if parsed else None) or "localhost",
        port=args.port or (str(parsed.port) if parsed and parsed.port else "") or "15432",
        database=args.database or (parsed.path.lstrip("/") if parsed else "") or "qaflex",
        username=args.username or (parsed.username if parsed else None) or "qaflex",
        password=args.password or (parsed.password if parsed else None) or "",
        schema=schema,
    )


def normalize_dsn(value: str) -> str:
    value = value.strip()
    if value.startswith("jdbc:"):
        return value.removeprefix("jdbc:")
    return value


def first_query_value(query: dict[str, list[str]], key: str) -> str:
    values = query.get(key) or []
    return values[0] if values else ""


def find_psql(explicit_path: str) -> str | None:
    if explicit_path:
        return explicit_path
    found = shutil.which("psql")
    if found:
        return found
    if DEFAULT_PSQL.exists():
        return str(DEFAULT_PSQL)
    return None


def run_psql(psql: str, options: ConnectionOptions, sql: str, limit: int) -> list[dict[str, str]]:
    env = os.environ.copy()
    env.setdefault("PGCONNECT_TIMEOUT", os.getenv("FACT_CHECK_CONNECT_TIMEOUT_SECONDS", "5"))
    env.setdefault("PGCLIENTENCODING", "UTF8")
    if options.password:
        env["PGPASSWORD"] = options.password
    if options.schema:
        env["PGOPTIONS"] = f"-c search_path={options.schema},public"
    command = [
        psql,
        "-X",
        "-w",
        "-v",
        "ON_ERROR_STOP=1",
        "-v",
        f"limit={max(1, limit)}",
        "--csv",
        "-h",
        options.host,
        "-p",
        options.port,
        "-U",
        options.username,
        "-d",
        options.database,
    ]
    completed = subprocess.run(command, input=sql, check=False, capture_output=True, env=env, text=True, encoding="utf-8")
    if completed.returncode != 0:
        raise RuntimeError((completed.stderr or completed.stdout).strip())
    return list(csv.DictReader(completed.stdout.splitlines()))


def main() -> int:
    args = parse_args()
    if args.print_sql:
        print(POLLUTION_SQL.strip())
        return 0

    psql = find_psql(args.psql)
    if not psql:
        print("psql was not found. Load scripts/dev-env.ps1 or pass --psql.", file=sys.stderr)
        return 2

    options = connection_options(args)
    print(
        "checking issue_fact.module_names "
        f"host={options.host} port={options.port} database={options.database} user={options.username}"
        + (f" schema={options.schema}" if options.schema else ""),
        flush=True,
    )

    try:
        summary = run_psql(psql, options, SUMMARY_SQL, args.limit)
        rows = run_psql(psql, options, POLLUTION_SQL, args.limit)
    except RuntimeError as error:
        print(str(error), file=sys.stderr)
        return 2

    if summary:
        first = summary[0]
        print(
            "issue_fact rows="
            f"{first.get('total_issue_fact_rows', '0')} "
            f"rows_with_module_names={first.get('rows_with_module_names', '0')}"
        )

    if not rows:
        print("No suspicious module_names values found.")
        return 0

    print(f"Suspicious module_names values: {len(rows)} group(s)")
    for row in rows:
        print(
            f"- {row['reason']}: {row['module_value']} "
            f"count={row['issue_count']} refs={row.get('sample_issue_refs') or '-'}"
        )
        if row.get("sample_title"):
            print(f"  sample_title={row['sample_title']}")
    print("Run fact rebuild after normalization fixes, then rerun this check.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
