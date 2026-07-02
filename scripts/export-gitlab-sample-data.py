#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
内网 GitLab 数据库完整采样导出脚本
用途：导出 GitLab 所有表的采样数据，保留外键关系，用于外网 GitLab 导入
要求：单文件，控制大小，保持外键关联完整性
"""

import sys
import psycopg2
from psycopg2.extras import RealDictCursor
from datetime import datetime
import json
import gzip
import os
from collections import defaultdict

# =============================================================================
# 配置区域
# =============================================================================

# 内网 GitLab 数据库配置
DB_CONFIG = {
    'host': '172.22.10.233',
    'port': 5432,
    'database': 'gitlabhq_production',
    'user': 'qaflex_reader',
    'password': 'huayun123'
}

# 导出配置
MAX_ROWS_PER_TABLE = 5000  # 每张表的最大采样行数
TARGET_SIZE_MB = 50  # 目标文件大小（MB）- 增加到 50MB 以包含更多表
OUTPUT_FILE = 'gitlab_full_sample.sql.gz'  # 输出文件（压缩）

# 需要排除的表（系统表、日志表、临时表）
EXCLUDED_TABLES = {
    # PostgreSQL 系统表
    'pg_stat_statements',
    'pg_buffercache',
    # GitLab 大型日志表
    'audit_events',
    'web_hook_logs',
    'ci_build_trace_sections',
    'ci_build_trace_chunks',
    'routes',  # 路由缓存表，会自动重建
}

# 关键表优先级（这些表会优先导出更多数据）
HIGH_PRIORITY_TABLES = {
    'projects': 200,
    'users': 300,
    'namespaces': 200,
    'issues': 5000,
    'merge_requests': 3000,
    'notes': 8000,
    'labels': 500,
    'milestones': 300,
    'members': 1000,
}

# =============================================================================
# 工具函数
# =============================================================================

def get_db_connection(password):
    """建立数据库连接"""
    config = DB_CONFIG.copy()
    config['password'] = password
    try:
        conn = psycopg2.connect(**config)
        return conn
    except psycopg2.Error as e:
        print(f"❌ 数据库连接失败: {e}")
        sys.exit(1)

def get_all_tables(cursor):
    """获取所有用户表"""
    cursor.execute("""
        SELECT tablename
        FROM pg_tables
        WHERE schemaname = 'public'
        ORDER BY tablename
    """)
    tables = [row['tablename'] for row in cursor.fetchall()]
    # 排除指定表
    tables = [t for t in tables if t not in EXCLUDED_TABLES]
    return tables

def get_table_info(cursor, table_name):
    """获取表的列信息"""
    cursor.execute("""
        SELECT column_name, data_type, is_nullable
        FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = %s
        ORDER BY ordinal_position
    """, (table_name,))
    return cursor.fetchall()

def get_table_primary_key(cursor, table_name):
    """获取表的主键列"""
    cursor.execute("""
        SELECT a.attname
        FROM pg_index i
        JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
        WHERE i.indrelid = %s::regclass AND i.indisprimary
    """, (table_name,))
    result = cursor.fetchone()
    return result['attname'] if result else 'id'

def get_table_count(cursor, table_name):
    """获取表的行数（快速估算）"""
    try:
        cursor.execute("SELECT reltuples::bigint as count FROM pg_class WHERE relname = %s", (table_name,))
        result = cursor.fetchone()
        return int(result['count']) if result and result.get('count') else 0
    except:
        return 0

def get_foreign_keys(cursor):
    """获取所有外键关系"""
    cursor.execute("""
        SELECT
            tc.table_name as from_table,
            kcu.column_name as from_column,
            ccu.table_name as to_table,
            ccu.column_name as to_column
        FROM information_schema.table_constraints AS tc
        JOIN information_schema.key_column_usage AS kcu
            ON tc.constraint_name = kcu.constraint_name
            AND tc.table_schema = kcu.table_schema
        JOIN information_schema.constraint_column_usage AS ccu
            ON ccu.constraint_name = tc.constraint_name
            AND ccu.table_schema = tc.table_schema
        WHERE tc.constraint_type = 'FOREIGN KEY'
            AND tc.table_schema = 'public'
    """)

    fk_map = defaultdict(list)
    for row in cursor.fetchall():
        fk_map[row['from_table']].append({
            'from_column': row['from_column'],
            'to_table': row['to_table'],
            'to_column': row['to_column']
        })
    return fk_map

def topological_sort(tables, fk_map):
    """拓扑排序表，确保依赖表先导出"""
    # 构建依赖图
    depends_on = defaultdict(set)
    for table in tables:
        if table in fk_map:
            for fk in fk_map[table]:
                to_table = fk['to_table']
                if to_table in tables and to_table != table:
                    depends_on[table].add(to_table)

    # Kahn 算法
    in_degree = {table: 0 for table in tables}
    for table in tables:
        for dep in depends_on[table]:
            in_degree[dep] += 1

    queue = [table for table in tables if in_degree[table] == 0]
    result = []

    while queue:
        # 优先处理高优先级表
        queue.sort(key=lambda t: (HIGH_PRIORITY_TABLES.get(t, 0), t), reverse=True)
        table = queue.pop(0)
        result.append(table)

        for other_table in tables:
            if table in depends_on[other_table]:
                in_degree[other_table] -= 1
                if in_degree[other_table] == 0:
                    queue.append(other_table)

    # 处理循环依赖
    if len(result) < len(tables):
        remaining = [t for t in tables if t not in result]
        result.extend(remaining)

    return result

def escape_sql_value(value):
    """转义 SQL 值"""
    if value is None:
        return 'NULL'
    elif isinstance(value, bool):
        return 'true' if value else 'false'
    elif isinstance(value, (int, float)):
        return str(value)
    elif isinstance(value, datetime):
        return f"'{value.isoformat()}'"
    elif isinstance(value, dict) or isinstance(value, list):
        json_str = json.dumps(value, ensure_ascii=False).replace("'", "''")
        return f"'{json_str}'::jsonb"
    else:
        str_value = str(value).replace("'", "''")
        return f"'{str_value}'"

def generate_insert_statement(table_name, columns, row):
    """生成 INSERT 语句"""
    column_names = ', '.join([col['column_name'] for col in columns])
    values = ', '.join([escape_sql_value(row.get(col['column_name'])) for col in columns])
    return f"INSERT INTO {table_name} ({column_names}) VALUES ({values});\n"

def export_table_sample(cursor, table_name, limit):
    """导出表的采样数据"""
    print(f"  📊 {table_name}", end='', flush=True)

    columns = get_table_info(cursor, table_name)
    if not columns:
        print(f" ⚠️  无法获取列信息")
        return [], 0

    total_count = get_table_count(cursor, table_name)
    pk_column = get_table_primary_key(cursor, table_name)

    # 检查主键列是否存在
    column_names = [col['column_name'] for col in columns]
    if pk_column not in column_names:
        pk_column = column_names[0]

    try:
        # 使用主键倒序获取最新数据
        query = f"SELECT * FROM {table_name} ORDER BY {pk_column} DESC LIMIT {limit}"
        cursor.execute(query)
        rows = cursor.fetchall()

        statements = []
        for row in rows:
            stmt = generate_insert_statement(table_name,
                                            [{'column_name': col['column_name']} for col in columns],
                                            dict(row))
            statements.append(stmt)

        exported_count = len(statements)
        print(f" → {exported_count:,}/{total_count:,} 行")
        return statements, exported_count

    except psycopg2.Error as e:
        print(f" ❌ {e}")
        return [], 0

# =============================================================================
# 主导出流程
# =============================================================================

def main():
    print("=" * 80)
    print("GitLab 完整数据库采样导出工具")
    print("=" * 80)
    print()

    password = DB_CONFIG.get('password') or os.environ.get('GITLAB_DB_PASSWORD')
    if not password:
        password = input("请输入数据库密码: ").strip()

    print("\n🔗 连接到内网数据库...")
    conn = get_db_connection(password)
    cursor = conn.cursor(cursor_factory=RealDictCursor)
    print("✅ 数据库连接成功\n")

    # 获取所有表
    print("🔍 扫描数据库表...")
    all_tables = get_all_tables(cursor)
    print(f"   找到 {len(all_tables)} 张表\n")

    # 获取外键关系
    print("🔗 分析外键关系...")
    fk_map = get_foreign_keys(cursor)
    print(f"   找到 {sum(len(v) for v in fk_map.values())} 个外键约束\n")

    # 拓扑排序
    print("📋 规划导出顺序...")
    sorted_tables = topological_sort(all_tables, fk_map)
    print(f"   导出顺序已确定\n")

    # 准备导出
    all_statements = []
    total_exported = 0
    estimated_size = 0
    table_count = 0

    # 文件头部
    header = f"""-- GitLab Full Sample Data Export
-- Generated: {datetime.now().isoformat()}
-- Source: {DB_CONFIG['host']}:{DB_CONFIG['port']}/{DB_CONFIG['database']}
-- Total Tables: {len(sorted_tables)}
--
-- 导入方法：
-- 1. 解压: gunzip {OUTPUT_FILE}
-- 2. 创建数据库: createdb gitlab_sample
-- 3. 导入: psql -d gitlab_sample -f gitlab_full_sample.sql
--

SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

-- 临时禁用触发器和外键检查
SET session_replication_role = replica;

BEGIN;

"""
    all_statements.append(header)
    estimated_size += len(header.encode('utf-8'))

    # 导出数据
    print("📦 开始导出数据...\n")

    for table_name in sorted_tables:
        # 检查文件大小
        if estimated_size > TARGET_SIZE_MB * 1024 * 1024:
            print(f"\n⚠️  已达到目标大小 {TARGET_SIZE_MB}MB，停止导出")
            print(f"   已导出 {table_count}/{len(sorted_tables)} 张表")
            break

        # 确定采样行数
        limit = HIGH_PRIORITY_TABLES.get(table_name, MAX_ROWS_PER_TABLE)

        statements, count = export_table_sample(cursor, table_name, limit)

        if statements:
            table_header = f"\n-- Table: {table_name} ({count} rows)\n"
            all_statements.append(table_header)
            all_statements.extend(statements)

            total_exported += count
            table_count += 1
            for stmt in statements:
                estimated_size += len(stmt.encode('utf-8'))

    # 文件尾部
    footer = """
COMMIT;

-- 恢复触发器和外键检查
SET session_replication_role = DEFAULT;

-- 导出完成
"""
    all_statements.append(footer)

    # 写入压缩文件
    print(f"\n💾 写入文件: {OUTPUT_FILE}")
    with gzip.open(OUTPUT_FILE, 'wt', encoding='utf-8') as f:
        for stmt in all_statements:
            f.write(stmt)

    file_size_mb = os.path.getsize(OUTPUT_FILE) / 1024 / 1024

    print(f"\n✅ 导出完成!")
    print(f"   - 导出表数: {table_count}/{len(sorted_tables)}")
    print(f"   - 总行数: {total_exported:,}")
    print(f"   - 文件大小: {file_size_mb:.2f} MB (压缩后)")
    print(f"   - 输出文件: {OUTPUT_FILE}")
    print()
    print("📌 下一步:")
    print(f"   1. 将文件传输到外网环境")
    print(f"   2. 解压: gunzip {OUTPUT_FILE}")
    print(f"   3. 创建数据库: createdb gitlab_sample")
    print(f"   4. 导入: psql -d gitlab_sample -f gitlab_full_sample.sql")
    print()

    cursor.close()
    conn.close()

if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        print("\n\n⚠️  用户中断")
        sys.exit(1)
    except Exception as e:
        print(f"\n\n❌ 错误: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)
