from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCHEMA_SQL = ROOT / "backend/src/main/resources/schema.sql"
MIGRATION_DIR = ROOT / "backend/src/main/resources/db/migration"


def strip_comments(sql: str) -> str:
    sql = re.sub(r"/\*.*?\*/", "", sql, flags=re.S)
    return re.sub(r"--.*", "", sql)


def read_sql(paths: list[Path]) -> str:
    return "\n".join(path.read_text(encoding="utf-8") for path in paths)


def norm_name(name: str) -> str:
    return name.strip().strip('"').lower()


def parse_tables(sql: str) -> set[str]:
    sql = strip_comments(sql)
    return {
        norm_name(match.group(1).split(".")[-1])
        for match in re.finditer(
            r"(?is)\bcreate\s+table\s+(?:if\s+not\s+exists\s+)?([\"\w.]+)\s*\(",
            sql,
        )
    }


def parse_indexes(sql: str) -> set[str]:
    sql = strip_comments(sql)
    return {
        norm_name(match.group(1))
        for match in re.finditer(
            r"(?is)\bcreate\s+(?:unique\s+)?index\s+(?:if\s+not\s+exists\s+)?([\"\w]+)\s+on\b",
            sql,
        )
    }


def parse_extensions(sql: str) -> set[str]:
    sql = strip_comments(sql)
    return {
        norm_name(match.group(1))
        for match in re.finditer(
            r"(?is)\bcreate\s+extension\s+(?:if\s+not\s+exists\s+)?([\"\w]+)",
            sql,
        )
    }


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
        body = sql[start : index - 1]
        columns = result.setdefault(table, set())
        for item in split_top_level(body):
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


def compare_set(label: str, expected: set[str], actual: set[str]) -> bool:
    missing = sorted(expected - actual)
    extra = sorted(actual - expected)
    print(f"{label}: schema={len(expected)} flyway={len(actual)} missing={len(missing)} extra={len(extra)}")
    for item in missing:
        print(f"  MISSING {item}")
    for item in extra:
        print(f"  EXTRA {item}")
    return not missing and not extra


def compare_columns(expected: dict[str, set[str]], actual: dict[str, set[str]]) -> bool:
    ok = True
    table_names = sorted(set(expected) | set(actual))
    for table in table_names:
        missing = sorted(expected.get(table, set()) - actual.get(table, set()))
        extra = sorted(actual.get(table, set()) - expected.get(table, set()))
        if missing or extra:
            ok = False
            print(f"columns:{table}: missing={missing} extra={extra}")
    print(f"columns: tables={len(table_names)} ok={ok}")
    return ok


def main() -> int:
    migrations = sorted(MIGRATION_DIR.glob("*.sql"))
    schema_sql = read_sql([SCHEMA_SQL])
    migration_sql = read_sql(migrations)
    checks = [
        compare_set("tables", parse_tables(schema_sql), parse_tables(migration_sql)),
        compare_set("indexes", parse_indexes(schema_sql), parse_indexes(migration_sql)),
        compare_set("extensions", parse_extensions(schema_sql), parse_extensions(migration_sql)),
        compare_columns(final_columns([SCHEMA_SQL]), final_columns(migrations)),
    ]
    return 0 if all(checks) else 1


if __name__ == "__main__":
    sys.exit(main())
