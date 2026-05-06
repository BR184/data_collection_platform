# Flyway 迁移治理规则

本文档用于约束数据库迁移的后续维护方式，避免 `schema.sql`、Flyway 和共享环境之间继续漂移。

## 基本原则

1. 主配置以 Flyway 作为建库和升级入口。
2. `schema.sql` 只保留为本地兼容和静态比对基准，不作为生产初始化入口。
3. 已经在共享库执行过的 Flyway 迁移文件视为不可变。
4. 生产修复走新的前向迁移，不通过手工改库补结构。
5. 结构迁移和数据回填分开提交，避免一次迁移同时承担 DDL 和大量 DML。

## 已执行迁移的处理

如果某个迁移版本已经在共享库执行过，后续发现内容需要调整：

- 开发库或临时测试库：可以统一执行 `flyway repair`，但必须在团队内同步。
- 准生产和生产库：优先新增下一版迁移修复，不直接修改旧迁移。
- 说明性注释变更：已执行后也不再改旧文件，放到新文档或新迁移说明中。

## 大表索引策略

新库可以由 Flyway 正常创建索引。已有大数据量旧库需要单独评估：

- 普通业务索引可以随结构迁移创建。
- `GIN`、`trgm`、表达式索引和覆盖大表的索引需要评估执行时间和锁影响。
- 必要时拆出独立运维脚本，使用 `CREATE INDEX CONCURRENTLY`。
- `CREATE INDEX CONCURRENTLY` 不能运行在普通 Flyway 事务迁移中，需单独设计执行入口和失败重试方式。

## 必跑检查

修改 schema 或迁移后必须运行：

```powershell
python scripts/check_schema_flyway_drift.py
python scripts/check_flyway_migration_immutability.py
cd backend
mvn -q -Dtest=FlywayMigrationSmokeTest test
```

CI 中也会执行同类检查，确保新增表、索引、扩展和最终字段集合不再漂移。

新增迁移后需要先完成评审，再执行：

```powershell
python scripts/check_flyway_migration_immutability.py --update
```

该命令只用于锁定新增迁移。已经在共享库执行过的旧迁移不应通过更新清单来绕过 checksum 风险。
