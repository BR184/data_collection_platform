#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
GitLab 数据导入到平台 ODS 表
处理类型转换和表结构差异
"""

import sys
import psycopg2
from psycopg2.extras import execute_batch
import re
import json

# 数据库配置
DB_CONFIG = {
    'host': 'localhost',
    'port': 15432,
    'database': 'qaflex',
    'user': 'qaflex',
    'password': 'change_this_password'
}

# 表名映射
TABLE_MAPPING = {
    'projects': 'ods_gitlab_cc_projects',
    'users': 'ods_gitlab_cc_users',
    'namespaces': 'ods_gitlab_cc_namespaces',
    'members': 'ods_gitlab_cc_members',
    'issues': 'ods_gitlab_cc_issues',
    'issue_assignees': 'ods_gitlab_cc_issue_assignees',
    'issue_metrics': 'ods_gitlab_cc_issue_metrics',
    'label_links': 'ods_gitlab_cc_label_links',
    'labels': 'ods_gitlab_cc_labels',
    'merge_requests': 'ods_gitlab_cc_merge_requests',
    'merge_request_metrics': 'ods_gitlab_cc_merge_request_metrics',
    'merge_request_assignees': 'ods_gitlab_cc_merge_request_assignees',
    'notes': 'ods_gitlab_cc_notes',
    'events': 'ods_gitlab_cc_events',
    'milestones': 'ods_gitlab_cc_milestones',
}

def parse_insert_statement(line):
    """解析 INSERT 语句"""
    match = re.match(r'INSERT INTO (\w+) \((.*?)\) VALUES \((.*)\);', line)
    if not match:
        return None, None, None

    table_name = match.group(1)
    columns = [c.strip() for c in match.group(2).split(',')]
    values_str = match.group(3)

    return table_name, columns, values_str

def parse_values(values_str):
    """解析 VALUES 部分（简化版，处理常见情况）"""
    values = []
    current = ''
    in_string = False
    escape_next = False

    for char in values_str:
        if escape_next:
            current += char
            escape_next = False
            continue

        if char == '\\':
            escape_next = True
            current += char
            continue

        if char == "'" and not escape_next:
            in_string = not in_string
            current += char
            continue

        if char == ',' and not in_string:
            values.append(current.strip())
            current = ''
            continue

        current += char

    if current:
        values.append(current.strip())

    return values

def convert_value(value_str):
    """转换值到 Python 类型"""
    value_str = value_str.strip()

    if value_str == 'NULL':
        return None

    if value_str == 'true':
        return True

    if value_str == 'false':
        return False

    # JSONB 转换
    if value_str.endswith('::jsonb'):
        json_str = value_str[:-7].strip()
        if json_str.startswith("'") and json_str.endswith("'"):
            json_str = json_str[1:-1].replace("''", "'")
        try:
            return json.loads(json_str)
        except:
            return json_str

    # 字符串
    if value_str.startswith("'") and value_str.endswith("'"):
        return value_str[1:-1].replace("''", "'")

    # 数字
    try:
        if '.' in value_str:
            return float(value_str)
        return int(value_str)
    except:
        return value_str

def import_data_batch(conn, table_name, columns, rows):
    """批量导入数据"""
    if not rows:
        return 0

    ods_table = TABLE_MAPPING.get(table_name)
    if not ods_table:
        print(f"  ⚠️  跳过未映射的表: {table_name}")
        return 0

    # 生成 INSERT 语句
    placeholders = ','.join(['%s'] * len(columns))
    column_names = ','.join(columns)
    insert_sql = f"INSERT INTO {ods_table} ({column_names}) VALUES ({placeholders}) ON CONFLICT DO NOTHING"

    try:
        with conn.cursor() as cursor:
            execute_batch(cursor, insert_sql, rows, page_size=1000)
        conn.commit()
        return len(rows)
    except Exception as e:
        conn.rollback()
        print(f"  ❌ 导入失败 {ods_table}: {e}")
        return 0

def main():
    print("=" * 80)
    print("GitLab 数据导入到 ODS 表")
    print("=" * 80)
    print()

    # 连接数据库
    print("🔗 连接数据库...")
    try:
        conn = psycopg2.connect(**DB_CONFIG)
        print("✅ 连接成功\n")
    except Exception as e:
        print(f"❌ 连接失败: {e}")
        sys.exit(1)

    # 读取并处理 SQL 文件
    sql_file = 'data.sql'
    print(f"📖 读取文件: {sql_file}\n")

    current_table = None
    current_columns = None
    batch_rows = []
    total_imported = 0
    line_count = 0

    try:
        with open(sql_file, 'r', encoding='utf-8') as f:
            for line in f:
                line_count += 1

                if line_count % 10000 == 0:
                    print(f"  处理行数: {line_count:,}, 已导入: {total_imported:,}", end='\r', flush=True)

                line = line.strip()

                if not line or line.startswith('--') or line.startswith('SET') or line in ('BEGIN;', 'COMMIT;'):
                    continue

                table_name, columns, values_str = parse_insert_statement(line)

                if not table_name:
                    continue

                # 检查是否切换表
                if table_name != current_table:
                    # 导入上一批数据
                    if batch_rows and current_table:
                        imported = import_data_batch(conn, current_table, current_columns, batch_rows)
                        total_imported += imported
                        print(f"\n  ✅ {TABLE_MAPPING.get(current_table, current_table)}: {imported} 行")

                    current_table = table_name
                    current_columns = columns
                    batch_rows = []

                # 解析值
                try:
                    values = parse_values(values_str)
                    converted_values = [convert_value(v) for v in values]
                    batch_rows.append(converted_values)

                    # 每 1000 行提交一次
                    if len(batch_rows) >= 1000:
                        imported = import_data_batch(conn, current_table, current_columns, batch_rows)
                        total_imported += imported
                        batch_rows = []

                except Exception as e:
                    print(f"\n  ⚠️  解析失败 (行 {line_count}): {e}")
                    continue

        # 导入最后一批
        if batch_rows and current_table:
            imported = import_data_batch(conn, current_table, current_columns, batch_rows)
            total_imported += imported
            print(f"\n  ✅ {TABLE_MAPPING.get(current_table, current_table)}: {imported} 行")

        print(f"\n\n✅ 导入完成!")
        print(f"   总计导入: {total_imported:,} 行")
        print()

    except Exception as e:
        print(f"\n❌ 错误: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)
    finally:
        conn.close()

if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        print("\n\n⚠️  用户中断")
        sys.exit(1)
