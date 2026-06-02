#!/usr/bin/env python3
"""Compare local GitLab source rows with new-platform fact rows.

This is intentionally a Docker/psql wrapper instead of a Python database client
so it can run on machines without local psql, psycopg, or MySQL clients.
"""

from __future__ import annotations

import argparse
import json
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


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Compare GitLab source DB rows to new-platform fact rows.")
    parser.add_argument("--gitlab-container", default="gitlab-data-web-1")
    parser.add_argument("--gitlab-db", default="gitlabhq_production")
    parser.add_argument("--platform-container", default="dcp-local-test-postgres-1")
    parser.add_argument("--platform-db", default="qaflex")
    parser.add_argument("--platform-user", default="qaflex")
    parser.add_argument("--source-instance", default="cc")
    parser.add_argument("--project-id", action="append", type=int, dest="project_ids")
    parser.add_argument("--output", default="", help="Optional JSON report path.")
    return parser.parse_args()


def run(command: list[str]) -> CommandResult:
    completed = subprocess.run(command, text=True, capture_output=True, encoding="utf-8", errors="replace")
    return CommandResult(command, completed.stdout.strip(), completed.stderr.strip(), completed.returncode)


def require_success(result: CommandResult) -> str:
    if result.returncode != 0:
        joined = " ".join(result.command)
        raise RuntimeError(f"Command failed ({result.returncode}): {joined}\n{result.stderr}\n{result.stdout}")
    return result.stdout


def sql_json(command: list[str]) -> list[dict[str, Any]]:
    output = require_success(run(command))
    if not output:
        return []
    return json.loads(output)


def project_filter(project_ids: list[int]) -> str:
    if not project_ids:
        return "true"
    ids = ",".join(str(project_id) for project_id in project_ids)
    return f"project_id in ({ids})"


def target_project_filter(project_ids: list[int]) -> str:
    if not project_ids:
        return "true"
    ids = ",".join(str(project_id) for project_id in project_ids)
    return f"target_project_id in ({ids})"


def gitlab_query(container: str, db: str, sql: str) -> list[str]:
    return ["docker", "exec", container, "gitlab-psql", "-d", db, "-t", "-A", "-c", sql]


def platform_query(container: str, db: str, user: str, sql: str) -> list[str]:
    return ["docker", "exec", container, "psql", "-U", user, "-d", db, "-t", "-A", "-c", sql]


def keyed(rows: list[dict[str, Any]], key: str = "project_id") -> dict[str, dict[str, Any]]:
    return {str(row[key]): row for row in rows}


def compare_sets(source: dict[str, dict[str, Any]], facts: dict[str, dict[str, Any]], id_field: str) -> list[dict[str, Any]]:
    project_ids = sorted(set(source) | set(facts), key=lambda value: int(value))
    comparisons: list[dict[str, Any]] = []
    for project_id in project_ids:
        source_row = source.get(project_id, {})
        fact_row = facts.get(project_id, {})
        source_ids = set(source_row.get(id_field, []) or [])
        fact_ids = set(fact_row.get(id_field, []) or [])
        comparisons.append(
            {
                "projectId": int(project_id),
                "sourceCount": source_row.get("count", 0),
                "factCount": fact_row.get("count", 0),
                "missingInFacts": sorted(source_ids - fact_ids),
                "extraInFacts": sorted(fact_ids - source_ids),
                "passed": source_ids == fact_ids and source_row.get("count", 0) == fact_row.get("count", 0),
            }
        )
    return comparisons


def main() -> int:
    args = parse_args()
    project_ids = args.project_ids or [1, 2, 3]

    source_issue_sql = f"""
select coalesce(json_agg(row_to_json(t) order by project_id)::text, '[]')
from (
  select project_id, count(*)::int as count, array_agg(iid order by iid) as iids
  from issues
  where {project_filter(project_ids)}
  group by project_id
) t;
"""
    source_mr_sql = f"""
select coalesce(json_agg(row_to_json(t) order by project_id)::text, '[]')
from (
  select target_project_id as project_id, count(*)::int as count, array_agg(iid order by iid) as iids
  from merge_requests
  where {target_project_filter(project_ids)}
  group by target_project_id
) t;
"""
    fact_issue_sql = f"""
select coalesce(json_agg(row_to_json(t) order by project_id)::text, '[]')
from (
  select project_id, count(*)::int as count, array_agg(issue_iid order by issue_iid) as iids
  from issue_fact
  where source_instance = '{args.source_instance}' and project_id in ({",".join(str(value) for value in project_ids)})
  group by project_id
) t;
"""
    fact_mr_sql = f"""
select coalesce(json_agg(row_to_json(t) order by project_id)::text, '[]')
from (
  select project_id, count(*)::int as count, array_agg(merge_request_iid order by merge_request_iid) as iids
  from merge_request_fact
  where source_instance = '{args.source_instance}' and project_id in ({",".join(str(value) for value in project_ids)})
  group by project_id
) t;
"""

    source_issues = sql_json(gitlab_query(args.gitlab_container, args.gitlab_db, source_issue_sql))
    source_mrs = sql_json(gitlab_query(args.gitlab_container, args.gitlab_db, source_mr_sql))
    fact_issues = sql_json(platform_query(args.platform_container, args.platform_db, args.platform_user, fact_issue_sql))
    fact_mrs = sql_json(platform_query(args.platform_container, args.platform_db, args.platform_user, fact_mr_sql))

    issue_comparisons = compare_sets(keyed(source_issues), keyed(fact_issues), "iids")
    mr_comparisons = compare_sets(keyed(source_mrs), keyed(fact_mrs), "iids")
    report = {
        "source": {
            "gitlabContainer": args.gitlab_container,
            "gitlabDb": args.gitlab_db,
            "platformContainer": args.platform_container,
            "platformDb": args.platform_db,
            "sourceInstance": args.source_instance,
            "projectIds": project_ids,
        },
        "issues": issue_comparisons,
        "mergeRequests": mr_comparisons,
        "passed": all(row["passed"] for row in issue_comparisons + mr_comparisons),
    }

    text = json.dumps(report, ensure_ascii=False, indent=2)
    if args.output:
        output_path = Path(args.output)
        if not output_path.is_absolute():
            output_path = ROOT / output_path
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(text + "\n", encoding="utf-8")
        print(f"report written: {output_path}")
    print(text)
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
