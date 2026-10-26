#!/usr/bin/env python3
"""只读核对GitLab元数据、表权限和索引；不选样、不读取议题或评论、不写源库。"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from gitlab_bounded_sql_export import read_only_transaction
from gitlab_sample_contract import TABLE_ORDER, PackageError
from gitlab_sample_db import connect, inspect_schema, server_identity


def inspect_source() -> dict:
    """用一个最多30秒语句的只读RR事务核查元数据，退出时始终回滚并关闭连接。"""
    connection = connect(10, "dcp_sample_metadata_preflight")
    try:
        args = argparse.Namespace(statement_timeout_ms=30000, lock_timeout_ms=2000, max_runtime_seconds=60)
        cursor = read_only_transaction(connection, args)
        try:
            identity = server_identity(cursor)
            schema = inspect_schema(cursor)
            cursor.execute("select tablename, indexname, indexdef from pg_indexes where schemaname='public' and tablename=any(%s) order by tablename,indexname", (list(TABLE_ORDER),))
            indexes = [{"table": t, "name": n, "definition": d} for t, n, d in cursor.fetchall()]
            cursor.execute("select name, has_table_privilege(current_user, 'public.' || name, 'SELECT') from unnest(%s::text[]) as name", (list(TABLE_ORDER) + ["schema_migrations"],))
            permissions = dict(cursor.fetchall())
            if not all(permissions.values()):
                raise PackageError("源账号缺少所需SELECT权限")
            return {"status": "METADATA_ONLY", "identity": identity, "schema": schema, "select_permissions": permissions, "indexes": indexes, "business_rows_read": 0, "sampling_approved": False}
        finally:
            cursor.close()
    finally:
        try:
            connection.rollback()
        finally:
            connection.close()


def main() -> int:
    """输出元数据报告；结构、权限、连接或本地报告写入失败返回非零。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", required=True)
    args = parser.parse_args()
    try:
        report = inspect_source()
        with Path(args.report).open("x", encoding="utf-8") as output:
            json.dump(report, output, ensure_ascii=False, indent=2)
            output.write("\n")
        print("元数据核查完成；尚未批准或执行业务取数。请审阅版本、schema、权限、索引和读取预算。")
        return 0
    except (PackageError, OSError) as error:
        print(f"预检失败：{error}")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
