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

## 销毁性迁移策略

销毁性迁移包括但不限于 `DROP TABLE`、`DROP COLUMN`、表重命名、列重命名，以及会导致旧版本代码无法读取数据的结构变更。除非是全新未发布对象，否则不允许在同一个版本里直接删除。

推荐流程：

1. 第一阶段只做兼容迁移：新增新字段/新表，保留旧字段/旧表，运行时代码同时兼容新旧结构或完成数据回填。
2. 第二阶段做灰度隔离：将旧对象 `RENAME TO *_legacy_yyyymmdd`，保留至少 2 个 release 或 2 周观察期，确认没有旧代码、报表、运维脚本继续读取。
3. 第三阶段才允许正式 `DROP`，并在迁移文件头部写明：
   - `-- destructive-migration-reviewed: <reviewer/date>`
   - `-- destructive-migration-recovery: <backup/rollback/legacy object path>`

新增 Flyway 文件中出现销毁性语句时，CI 会执行 `scripts/check_flyway_destructive_migrations.py`。该脚本只能拦截“裸 drop/rename”，不能替代人工确认观察期和回滚路径。

历史上已经执行过的破坏性迁移不回写修改；若要补说明，写在新的文档或新的迁移说明中。

## 必跑检查

修改 schema 或迁移后必须运行：

```powershell
python scripts/check_schema_flyway_drift.py
python scripts/check_flyway_migration_immutability.py
python scripts/check_flyway_profile_smoke_coverage.py
cd backend
mvn -q -Dtest=FlywayMigrationSmokeTest test
mvn -q -Dspring.profiles.active=flyway-test -Dtest=FactBuildTaskServiceTest test
```

CI 中也会执行同类检查，确保新增表、索引、扩展和最终字段集合不再漂移。

新增迁移后需要先完成评审，再执行：

```powershell
python scripts/check_flyway_migration_immutability.py --update
```

该命令只用于锁定新增迁移。已经在共享库执行过的旧迁移不应通过更新清单来绕过 checksum 风险。

## 测试初始化路径

默认测试 profile 仍保留 `schema.sql` 初始化用于兼容旧测试。为了逐步迁往 Flyway，代表性 `@SpringBootTest` 需要写入 `scripts/flyway-profile-smoke-tests.txt`，并在 `flyway-test` profile 下运行。

新增依赖数据库结构的 SpringBootTest 时，应评估是否把它加入该清单，或用现有代表性测试覆盖相同 schema 路径。
