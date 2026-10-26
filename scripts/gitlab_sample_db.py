#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""导出/导入共用的数据库检查函数：连接、身份、结构与列白名单。"""

from __future__ import annotations

from typing import Any, Sequence

import psycopg2

from gitlab_sample_contract import (
    TABLE_ORDER,
    TABLE_SPECS,
    PackageError,
    compute_schema_fingerprint,
    resolve_schema_version,
    table_spec,
)


def connect(connect_timeout: int, application_name: str) -> Any:
    """使用 libpq 环境变量连接，不把凭据写入命令行。"""
    try:
        return psycopg2.connect(connect_timeout=connect_timeout, application_name=application_name)
    except psycopg2.Error as error:
        raise PackageError(f"Unable to connect to PostgreSQL: {error}") from error


def server_identity(cur: Any) -> dict[str, Any]:
    cur.execute(
        """
        select current_database(),
               coalesce(inet_server_addr()::text, 'local'),
               coalesce(inet_server_port(), 0),
               current_user
        """
    )
    database, address, port, role = cur.fetchone()
    from gitlab_sample_contract import sha256_bytes

    return {
        "database": database,
        "server_fingerprint": sha256_bytes(f"{address}:{port}".encode("utf-8")),
        "identity_fingerprint": sha256_bytes(f"{address}:{port}:{database}".encode("utf-8")),
        "role": role,
    }


def inspect_schema(cur: Any) -> dict[str, Any]:
    cur.execute(
        """
        select table_name, column_name, data_type, is_nullable
          from information_schema.columns
         where table_schema = 'public'
           and table_name = any(%s)
         order by table_name, ordinal_position
        """,
        (list(TABLE_ORDER),),
    )
    columns = [
        {
            "table_name": table_name,
            "column_name": column_name,
            "data_type": data_type,
            "is_nullable": is_nullable,
        }
        for table_name, column_name, data_type, is_nullable in cur.fetchall()
    ]
    present = {row["table_name"] for row in columns}
    missing = [table for table in TABLE_ORDER if table not in present]
    if missing:
        restricted: list[str] = []
        absent: list[str] = []
        for table in missing:
            cur.execute("select to_regclass(%s) is not null", (f"public.{table}",))
            (restricted if cur.fetchone()[0] else absent).append(table)
        if restricted:
            raise PackageError(
                "Current role lacks access to required GitLab tables: " + ", ".join(restricted)
            )
        raise PackageError(f"Required GitLab tables are missing: {absent}")
    by_table: dict[str, set[str]] = {}
    for row in columns:
        by_table.setdefault(row["table_name"], set()).add(row["column_name"])
    for table, spec in TABLE_SPECS.items():
        required = set(spec.key_columns)
        if spec.columns:
            required |= set(spec.columns)
        absent = sorted(required - by_table.get(table, set()))
        if absent:
            raise PackageError(f"Table {table} is missing required columns: {absent}")
    cur.execute("select max(version), count(*) from schema_migrations")
    max_version, count = cur.fetchone()
    if not max_version:
        raise PackageError("schema_migrations is empty")
    version = resolve_schema_version(str(max_version))
    return {
        "gitlab_version": version,
        "schema_migrations_max": str(max_version),
        "schema_migrations_count": int(count),
        "fingerprint": compute_schema_fingerprint(columns),
    }


def table_columns(cur: Any, table: str) -> list[str]:
    cur.execute(
        """
        select column_name
          from information_schema.columns
         where table_schema = 'public'
           and table_name = %s
         order by ordinal_position
        """,
        (table,),
    )
    columns = [row[0] for row in cur.fetchall()]
    if not columns:
        raise PackageError(f"Table {table} does not exist")
    keep = [column for column in columns if table_spec(table).keep(column)]
    missing_keys = [column for column in table_spec(table).key_columns if column not in keep]
    if missing_keys:
        raise PackageError(f"Table {table} keeps no key columns: {missing_keys}")
    return keep


def column_types(cur: Any, table: str, columns: Sequence[str]) -> dict[str, str]:
    cur.execute(
        """
        select column_name, data_type
          from information_schema.columns
         where table_schema = 'public'
           and table_name = %s
           and column_name = any(%s)
        """,
        (table, list(columns)),
    )
    return {name: data_type for name, data_type in cur.fetchall()}
