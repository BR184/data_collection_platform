from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCHEMA_SQL = ROOT / "backend/src/main/resources/schema.sql"
MIGRATION_DIR = ROOT / "backend/src/main/resources/db/migration"
CONTRACT_DOC = ROOT / "docs/fact-field-contract.md"


FIELD_FAMILIES = {
    "search": ["search_text", "search_compact", "search_spell", "search_initials"],
    "title_search": [
        "title_search_text",
        "title_search_compact",
        "title_search_spell",
        "title_search_initials",
    ],
    "module_search": [
        "module_search_text",
        "module_search_compact",
        "module_search_spell",
        "module_search_initials",
    ],
    "milestone_search": [
        "milestone_search_text",
        "milestone_search_compact",
        "milestone_search_spell",
        "milestone_search_initials",
    ],
    "author_search": [
        "author_search_text",
        "author_search_compact",
        "author_search_spell",
        "author_search_initials",
    ],
    "assignee_search": [
        "assignee_search_text",
        "assignee_search_compact",
        "assignee_search_spell",
        "assignee_search_initials",
    ],
    "phase_search": [
        "phase_search_text",
        "phase_search_compact",
        "phase_search_spell",
        "phase_search_initials",
    ],
    "owner_search": [
        "owner_search_text",
        "owner_search_compact",
        "owner_search_spell",
        "owner_search_initials",
    ],
}


REQUIRED_COLUMNS = {
    "review_records": [
        *FIELD_FAMILIES["search"],
        *FIELD_FAMILIES["title_search"],
    ],
    "issue_fact": [
        "category",
        "reason_category",
        "is_illegal",
        "illegal_reason",
        "is_legacy",
        "resolve_sla_days",
        *FIELD_FAMILIES["search"],
        *FIELD_FAMILIES["title_search"],
        *FIELD_FAMILIES["module_search"],
        *FIELD_FAMILIES["milestone_search"],
        *FIELD_FAMILIES["author_search"],
        *FIELD_FAMILIES["assignee_search"],
        *FIELD_FAMILIES["phase_search"],
    ],
    "merge_request_fact": [
        *FIELD_FAMILIES["search"],
        *FIELD_FAMILIES["owner_search"],
    ],
    "integration_test_fact": [
        "project_id",
        "testing_phase",
        "module_name",
        "function_name",
        "executor",
        "execute_case",
        "pass_case",
        "not_pass_case",
        "not_pass_case_now",
        "problem_case",
        "exception_count",
        "pass_rate",
        "parse_status",
        "validation_reason",
        "legal",
    ],
}

REQUIRED_DOC_TOKENS = [
    "review_records",
    "issue_fact",
    "merge_request_fact",
    "integration_test_fact",
    "phase_search_*",
    "category",
    "reason_category",
    "resolve_sla_days",
    "parse_status",
    "validation_reason",
]


def strip_comments(sql: str) -> str:
    sql = re.sub(r"/\*.*?\*/", "", sql, flags=re.S)
    return re.sub(r"--.*", "", sql)


def read_sql(paths: list[Path]) -> str:
    return "\n".join(path.read_text(encoding="utf-8") for path in paths)


def norm_name(name: str) -> str:
    return name.strip().strip('"').lower()


def split_top_level(body: str) -> list[str]:
    parts: list[str] = []
    buffer: list[str] = []
    depth = 0
    quote: str | None = None
    for ch in body:
        if quote:
            buffer.append(ch)
            if ch == quote:
                quote = None
            continue
        if ch in {'"', "'"}:
            quote = ch
            buffer.append(ch)
        elif ch == "(":
            depth += 1
            buffer.append(ch)
        elif ch == ")":
            depth -= 1
            buffer.append(ch)
        elif ch == "," and depth == 0:
            parts.append("".join(buffer).strip())
            buffer = []
        else:
            buffer.append(ch)
    tail = "".join(buffer).strip()
    if tail:
        parts.append(tail)
    return parts


def parse_create_columns(sql: str) -> dict[str, set[str]]:
    sql = strip_comments(sql)
    result: dict[str, set[str]] = {}
    table_pattern = re.compile(
        r"(?is)\bcreate\s+table\s+(?:if\s+not\s+exists\s+)?([\"\w.]+)\s*\("
    )
    skip = {"constraint", "primary", "unique", "foreign", "check", "exclude"}
    for match in table_pattern.finditer(sql):
        table = norm_name(match.group(1).split(".")[-1])
        start = match.end()
        depth = 1
        quote: str | None = None
        index = start
        while index < len(sql) and depth > 0:
            ch = sql[index]
            if quote:
                if ch == quote:
                    quote = None
            elif ch in {'"', "'"}:
                quote = ch
            elif ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
            index += 1
        columns = result.setdefault(table, set())
        for item in split_top_level(sql[start : index - 1]):
            tokens = item.split(None, 1)
            if not tokens:
                continue
            column = norm_name(tokens[0])
            if column and column not in skip:
                columns.add(column)
    return result


def parse_alter_columns(sql: str) -> dict[str, set[str]]:
    sql = strip_comments(sql)
    result: dict[str, set[str]] = {}
    for match in re.finditer(
        r"(?is)\balter\s+table\s+([\"\w.]+)\s+add\s+column\s+(?:if\s+not\s+exists\s+)?([\"\w]+)\b",
        sql,
    ):
        table = norm_name(match.group(1).split(".")[-1])
        result.setdefault(table, set()).add(norm_name(match.group(2)))
    return result


def final_columns(paths: list[Path]) -> dict[str, set[str]]:
    sql = read_sql(paths)
    columns = parse_create_columns(sql)
    for table, added_columns in parse_alter_columns(sql).items():
        columns.setdefault(table, set()).update(added_columns)
    return columns


def check_columns(label: str, columns: dict[str, set[str]]) -> bool:
    ok = True
    print(f"{label}: checking {len(REQUIRED_COLUMNS)} fact tables")
    for table, required in REQUIRED_COLUMNS.items():
        actual = columns.get(table, set())
        missing = sorted(set(required) - actual)
        print(f"  {table}: required={len(required)} actual={len(actual)} missing={len(missing)}")
        for column in missing:
            print(f"    MISSING {column}")
        ok = ok and not missing
    return ok


def check_doc() -> bool:
    text = CONTRACT_DOC.read_text(encoding="utf-8")
    missing = [token for token in REQUIRED_DOC_TOKENS if token not in text]
    print(f"fact-field-contract doc: tokens={len(REQUIRED_DOC_TOKENS)} missing={len(missing)}")
    for token in missing:
        print(f"  MISSING {token}")
    return not missing


def main() -> int:
    migrations = sorted(MIGRATION_DIR.glob("*.sql"))
    checks = [
        check_columns("schema.sql", final_columns([SCHEMA_SQL])),
        check_columns("flyway", final_columns(migrations)),
        check_doc(),
    ]
    return 0 if all(checks) else 1


if __name__ == "__main__":
    sys.exit(main())
