#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Import a .sql.gz created by gitlab_bounded_sql_export.py into a GitLab DB."""

from __future__ import annotations

import argparse
import gzip
import os
import re
import sys
import time
from pathlib import Path

import psycopg2


INSERT_TABLE = re.compile(r'^\s*insert\s+into\s+"?([a-zA-Z_][a-zA-Z0-9_]*)"?\s', re.IGNORECASE)

# ===== 外网 GitLab Docker Omnibus 一键导入配置 =====
# 推荐把脚本和 .sql.gz 复制到 GitLab 容器内，以 gitlab-psql 用户直接运行：
# /opt/gitlab/embedded/bin/python3 /tmp/gitlab_bounded_sql_import.py
IMPORT_FILE = "gitlab_233_cc_sample.sql.gz"
# GitLab Omnibus 默认 Unix socket 目录。外网如果是普通 PostgreSQL 直连，可改成 IP。
IMPORT_HOST = "/var/opt/gitlab/postgresql"
IMPORT_PORT = 5432
IMPORT_DATABASE = "gitlabhq_full_import_test"
IMPORT_USERNAME = "gitlab-psql"
IMPORT_PASSWORD = ""
IMPORT_CONNECT_TIMEOUT = 30
IMPORT_ANALYZE = True
IMPORT_ALLOW_PRODUCTION = False
IMPORT_DISABLE_FK_CHECKS = True


def die(message: str, code: int = 2) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(code)


def connect(args: argparse.Namespace):
    kwargs = dict(
        port=args.port,
        dbname=args.database,
        user=args.username,
        password=args.password,
        connect_timeout=args.connect_timeout,
        application_name="gitlab_bounded_sql_import",
    )
    if args.host:
        kwargs["host"] = args.host
    return psycopg2.connect(**kwargs)


def safe_rollback(conn) -> None:
    try:
        if not conn.closed:
            conn.rollback()
    except Exception:
        pass


def reset_sequences(cur, tables: set[str]) -> None:
    for table in sorted(tables):
        cur.execute(
            """
            select exists (
              select 1
                from information_schema.columns
               where table_schema = 'public'
                 and table_name = %s
                 and column_name = 'id'
            )
            """,
            (table,),
        )
        if not cur.fetchone()[0]:
            continue
        cur.execute("select pg_get_serial_sequence(%s, 'id')", (table,))
        row = cur.fetchone()
        sequence = row[0] if row else None
        if not sequence:
            continue
        cur.execute("select seqmin from pg_sequence where seqrelid = %s::regclass", (sequence,))
        sequence_min_row = cur.fetchone()
        sequence_min = int(sequence_min_row[0]) if sequence_min_row and sequence_min_row[0] is not None else 1
        cur.execute(
            f"select setval(%s, greatest((select coalesce(max(id), 0) from {quote_ident(table)}), %s), true)",
            (sequence, sequence_min),
        )


def quote_ident(name: str) -> str:
    if not re.match(r"^[a-zA-Z_][a-zA-Z0-9_]*$", name):
        die(f"Unsafe SQL identifier: {name!r}")
    return '"' + name.replace('"', '""') + '"'


def is_statement_complete(sql: str) -> bool:
    in_single_quote = False
    escape_string = False
    semicolon_at = -1
    i = 0
    while i < len(sql):
        char = sql[i]
        if in_single_quote:
            if escape_string and char == "\\":
                i += 2
                continue
            if char == "'":
                if i + 1 < len(sql) and sql[i + 1] == "'":
                    i += 2
                    continue
                in_single_quote = False
                escape_string = False
            i += 1
            continue
        if char == "'":
            in_single_quote = True
            escape_string = i > 0 and sql[i - 1] in ("E", "e")
        elif char == ";":
            semicolon_at = i
        i += 1
    return semicolon_at >= 0 and not sql[semicolon_at + 1 :].strip()


def iter_sql_statements(fh):
    buffer: list[str] = []
    statement_start = 1
    for line_no, line in enumerate(fh, start=1):
        stripped = line.strip()
        if not buffer and (not stripped or stripped.startswith("--")):
            continue
        if not buffer:
            statement_start = line_no
        buffer.append(line)
        statement = "".join(buffer)
        if is_statement_complete(statement):
            yield statement_start, statement.strip()
            buffer = []
    if buffer and "".join(buffer).strip():
        die(f"Incomplete SQL statement starting at line {statement_start}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Import bounded GitLab SQL gzip.")
    parser.add_argument("--file", default=IMPORT_FILE)
    parser.add_argument("--host", default=IMPORT_HOST)
    parser.add_argument("--port", type=int, default=IMPORT_PORT)
    parser.add_argument("--database", default=IMPORT_DATABASE)
    parser.add_argument("--username", default=IMPORT_USERNAME)
    parser.add_argument("--password", nargs="?", const="", default=os.getenv("GITLAB_DB_PASSWORD", IMPORT_PASSWORD))
    parser.add_argument("--connect-timeout", type=int, default=IMPORT_CONNECT_TIMEOUT)
    parser.add_argument("--allow-production", action="store_true", default=IMPORT_ALLOW_PRODUCTION)
    parser.add_argument("--no-disable-fk-checks", action="store_true", default=not IMPORT_DISABLE_FK_CHECKS)
    parser.add_argument("--analyze", action="store_true", default=IMPORT_ANALYZE)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    path = Path(args.file)
    if not path.exists():
        die(f"file not found: {path}")
    if args.database == "gitlabhq_production" and not args.allow_production:
        die("Refusing to import into gitlabhq_production. Use --allow-production only if you really mean it.")

    started = time.time()
    inserted = 0
    tables: set[str] = set()
    with connect(args) as conn:
        conn.autocommit = False
        with conn.cursor() as cur:
            cur.execute("select current_database(), current_user")
            db_name, user_name = cur.fetchone()
            print(f"target: database={db_name}, user={user_name}")
            if not args.no_disable_fk_checks:
                cur.execute("set session_replication_role = replica")
            cur.execute("set statement_timeout = 0")
            cur.execute("set idle_in_transaction_session_timeout = 0")
            try:
                with gzip.open(path, "rt", encoding="utf-8", errors="strict") as fh:
                    for line_no, statement in iter_sql_statements(fh):
                        if statement.lower().startswith("set "):
                            continue
                        match = INSERT_TABLE.match(statement)
                        if match:
                            tables.add(match.group(1))
                        try:
                            cur.execute(statement)
                        except Exception as exc:
                            safe_rollback(conn)
                            if not args.no_disable_fk_checks and not conn.closed:
                                conn.autocommit = True
                                with conn.cursor() as cleanup_cur:
                                    cleanup_cur.execute("set session_replication_role = origin")
                            die(f"Import failed at line {line_no}: {exc}")
                        inserted += 1
                        if inserted % 5000 == 0:
                            print(f"executed {inserted} insert statements...")
                reset_sequences(cur, tables)
                if args.analyze:
                    for table in sorted(tables):
                        cur.execute(f"analyze {quote_ident(table)}")
                if not args.no_disable_fk_checks:
                    cur.execute("set session_replication_role = origin")
                conn.commit()
            except Exception:
                safe_rollback(conn)
                raise
    print(f"done: executed={inserted}, tables={len(tables)}, elapsed={time.time() - started:.1f}s")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
