# 表单迁移一次性乱序升级入口

本 runbook 只用于已批准升级窗口：把目标旧库升级到表单 v6 发布目标迁移集（174 条，水位 `20261102.08`）。乱序只允许通过本入口的单次运行开启，常驻服务必须保持默认 `false`。

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
- 按实际版本集合判定旧库前缀，不能只按数量或“是否装过读发布脚本”推断：

| 旧库前缀 | 版本数 | 待执行 | 说明 |
| --- | --- | --- | --- |
| 仅 main 集 | 140 | 34（18 读取 + 16 表单） | 首次升级到读发布模型 |
| 共同祖先读发布集（`84e7c023`） | 156 | 18（`20261019_01`、`20261022_03` + 16 表单） | 曾部署读发布包但未含 main 后续两条 |
| 读发布集 + main 两条 | 158 | 16（表单 v6/S1/集成） | 已接续 main 后续迁移 |
| 其它数量/未知版本/校验和不符 | — | 停止 | 先核对身份与历史，不 repair、不删历史、不猜前缀 |

- 已执行读发布脚本的数据库保留真实历史，不要求删除或“清理”这些迁移；最终以版本集合与目标 174 条比对为准。
- 目标迁移集为 174 条，既有迁移内容与 checksum 未改动。

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

- 成功：进程退出码 `0`，日志无迁移失败，`flyway_schema_history` 增至 174 条，水位 `20261102.08`。
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
- 升级前已存在的版本 checksum/描述不变；新增低版本迁移已实际执行；旧非空数据行数/业务值守恒。
- 旧编号试验库仍按归属方处置；本入口不负责删除、恢复或修复这些库。

## 边界

- 该参数只用于本次已批准窗口；不得写入常驻配置、启动脚本默认值或 CI。
- 不修改既有迁移、不 `repair`、不改写历史、不预装新表单脚本。
- 目标库真实身份、备份和退出码记录随升级回执归档。
