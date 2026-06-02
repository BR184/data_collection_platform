from __future__ import annotations

from dataclasses import dataclass
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


@dataclass
class SqlState:
    tables: set[str]
    indexes: dict[str, str]
    extensions: set[str]
    columns: dict[str, set[str]]


def norm_name(name: str) -> str:
    return name.strip().strip('"').lower()


def norm_relation_name(name: str) -> str:
    return norm_name(name.split(".")[-1])


def split_statements(sql: str) -> list[str]:
    statements: list[str] = []
    buffer: list[str] = []
    quote: str | None = None
    dollar_quote: str | None = None
    index = 0
    while index < len(sql):
        if dollar_quote:
            if sql.startswith(dollar_quote, index):
                buffer.append(dollar_quote)
                index += len(dollar_quote)
                dollar_quote = None
            else:
                buffer.append(sql[index])
                index += 1
            continue

        ch = sql[index]
        if quote:
            buffer.append(ch)
            if ch == quote:
                quote = None
            index += 1
            continue

        dollar_match = re.match(r"\$[A-Za-z_]*\$", sql[index:])
        if dollar_match:
            dollar_quote = dollar_match.group(0)
            buffer.append(dollar_quote)
            index += len(dollar_quote)
            continue

        if ch in {'"', "'"}:
            quote = ch
            buffer.append(ch)
        elif ch == ";":
            statement = "".join(buffer).strip()
            if statement:
                statements.append(statement)
            buffer = []
        else:
            buffer.append(ch)
        index += 1

    tail = "".join(buffer).strip()
    if tail:
        statements.append(tail)
    return statements


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


def parse_create_table(statement: str) -> tuple[str, set[str]] | None:
    table_pattern = re.compile(r"(?is)\bcreate\s+table\s+(?:if\s+not\s+exists\s+)?([\"\w.]+)\s*\(")
    skip = {"constraint", "primary", "unique", "foreign", "check", "exclude"}
    match = table_pattern.search(statement)
    if not match:
        return None

    table = norm_relation_name(match.group(1))
    start = match.end()
    depth = 1
    quote: str | None = None
    index = start
    while index < len(statement) and depth > 0:
        ch = statement[index]
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

    body = statement[start : index - 1]
    columns: set[str] = set()
    for item in split_top_level(body):
        tokens = item.split(None, 1)
        if not tokens:
            continue
        column = norm_name(tokens[0])
        if column and column not in skip:
            columns.add(column)
    return table, columns


def parse_relation_list(names: str) -> list[str]:
    names = re.sub(r"(?is)\s+(?:cascade|restrict)\s*$", "", names.strip())
    return [norm_relation_name(item) for item in split_top_level(names) if item.strip()]


def final_state(paths: list[Path]) -> SqlState:
    tables: set[str] = set()
    indexes: dict[str, str] = {}
    extensions: set[str] = set()
    columns: dict[str, set[str]] = {}
    sql = strip_comments(read_sql(paths))

    for statement in split_statements(sql):
        create_table = parse_create_table(statement)
        if create_table:
            table, create_columns = create_table
            tables.add(table)
            columns.setdefault(table, set()).update(create_columns)
            continue

        if match := re.search(
            r"(?is)\bcreate\s+extension\s+(?:if\s+not\s+exists\s+)?([\"\w]+)",
            statement,
        ):
            extensions.add(norm_name(match.group(1)))
            continue

        if match := re.search(
            r"(?is)\bcreate\s+(?:unique\s+)?index\s+(?:concurrently\s+)?(?:if\s+not\s+exists\s+)?([\"\w]+)\s+on\s+([\"\w.]+)\b",
            statement,
        ):
            indexes[norm_name(match.group(1))] = norm_relation_name(match.group(2))
            continue

        if match := re.search(r"(?is)\bdrop\s+index\s+(?:concurrently\s+)?(?:if\s+exists\s+)?(.+)$", statement):
            for index_name in parse_relation_list(match.group(1)):
                indexes.pop(index_name, None)
            continue

        if match := re.search(r"(?is)\bdrop\s+table\s+(?:if\s+exists\s+)?(.+)$", statement):
            for table in parse_relation_list(match.group(1)):
                tables.discard(table)
                columns.pop(table, None)
                for index_name, index_table in list(indexes.items()):
                    if index_table == table:
                        indexes.pop(index_name, None)
            continue

        if match := re.search(
            r"(?is)\balter\s+table\s+(?:if\s+exists\s+)?([\"\w.]+)\s+rename\s+to\s+([\"\w]+)\s*$",
            statement,
        ):
            old_name = norm_relation_name(match.group(1))
            new_name = norm_relation_name(match.group(2))
            if old_name in tables:
                tables.remove(old_name)
                tables.add(new_name)
            if old_name in columns:
                columns[new_name] = columns.pop(old_name)
            for index_name, index_table in list(indexes.items()):
                if index_table == old_name:
                    indexes[index_name] = new_name
            continue

        if match := re.search(r"(?is)\balter\s+table\s+(?:if\s+exists\s+)?([\"\w.]+)\s+(.+)$", statement):
            table = norm_relation_name(match.group(1))
            actions = match.group(2)
            table_columns = columns.setdefault(table, set())
            for add_match in re.finditer(
                r"(?is)(?:^|,)\s*add\s+column\s+(?:if\s+not\s+exists\s+)?([\"\w]+)\b",
                actions,
            ):
                table_columns.add(norm_name(add_match.group(1)))
            for drop_match in re.finditer(
                r"(?is)(?:^|,)\s*drop\s+column\s+(?:if\s+exists\s+)?([\"\w]+)\b",
                actions,
            ):
                table_columns.discard(norm_name(drop_match.group(1)))
            for rename_match in re.finditer(
                r"(?is)(?:^|,)\s*rename\s+column\s+([\"\w]+)\s+to\s+([\"\w]+)\b",
                actions,
            ):
                old_column = norm_name(rename_match.group(1))
                new_column = norm_name(rename_match.group(2))
                if old_column in table_columns:
                    table_columns.remove(old_column)
                    table_columns.add(new_column)

    return SqlState(tables=tables, indexes=indexes, extensions=extensions, columns=columns)


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
    schema_state = final_state([SCHEMA_SQL])
    migration_state = final_state(migrations)
    checks = [
        compare_set("tables", schema_state.tables, migration_state.tables),
        compare_set("indexes", set(schema_state.indexes), set(migration_state.indexes)),
        compare_set("extensions", schema_state.extensions, migration_state.extensions),
        compare_columns(schema_state.columns, migration_state.columns),
    ]
    return 0 if all(checks) else 1


if __name__ == "__main__":
    sys.exit(main())
