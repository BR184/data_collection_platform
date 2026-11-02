# 表单迁移一次性乱序升级入口

本 runbook 只用于 N4/M01 已批准升级窗口：把真实主线旧库（140 条已执行迁移）升级到目标迁移集（167 条）。乱序只允许通过本入口的单次运行开启，常驻服务必须保持默认 `false`。

## 前置核验

在目标库执行并留存结果：

```sql
select current_database(), current_schema();
select version, description, success
  from flyway_schema_history
 where version is not null
 order by installed_rank;
select count(*) from flyway_schema_history where version is not null and success;
```

- 数据库身份与升级窗口清单一致，且已取得可恢复备份。
- 历史前缀为批准主线 140 条，水位 `20261022.03`；旧库不得预装任何分支表单/发布读取脚本。
- 目标迁移集为 167 条，既有迁移内容与 checksum 未改动。

## 执行一次

使用既有 `MigrationApplication` 的无 Web 单次迁移入口（与隔离测试同一入口），显式开启 out-of-order。
`PLATFORM_RUN_MODE=migrate` 是进入该入口的开关；缺少时进程按默认 `service` 常驻启动，不能用于一次性升级。

```bash
PLATFORM_RUN_MODE=migrate java -jar qa-flex-platform-backend.jar \
  --spring.datasource.url=jdbc:postgresql://<host>:<port>/<db> \
  --spring.datasource.username=<user> \
  --spring.datasource.password=<password> \
  --spring.flyway.enabled=true \
  --spring.flyway.out-of-order=true \
  --spring.sql.init.mode=never \
  --spring.main.banner-mode=off
```

- 成功：进程退出码 `0`，日志无迁移失败，`flyway_schema_history` 增至 167 条，水位为目标版本。
- 失败：进程非零退出；停止，不执行 `repair`、不删除或改写历史、不放宽校验；保留日志并恢复备份或提交修订方案。

## 升级后默认校验

去掉 `--spring.flyway.out-of-order=true`，用原常驻制品/Compose 启动后端（默认 `false`）：

```bash
PLATFORM_RUN_MODE=service java -jar qa-flex-platform-backend.jar \
  --spring.datasource.url=jdbc:postgresql://<host>:<port>/<db> \
  --spring.datasource.username=<user> \
  --spring.datasource.password=<password>
```

- `flyway.validate()` 通过，`/actuator/health` 为 `UP`。
- 历史前缀 140 条 checksum/描述不变；新增低版本迁移已实际执行；旧非空数据行数/业务值守恒。
- 旧编号试验库仍按归属方处置；本入口不负责删除、恢复或修复这些库。

## 边界

- 该参数只用于本次已批准窗口；不得写入常驻配置、启动脚本默认值或 CI。
- 不修改既有迁移、不 `repair`、不改写历史、不预装新表单脚本。
- 目标库真实身份、备份和退出码记录随升级回执归档。
