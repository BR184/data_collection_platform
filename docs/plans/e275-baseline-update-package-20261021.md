# 以 e275 为直接基线的保数据更新包与本地部署演练

## 进度与中间物

- 当前阶段：已确认内网直接基线为 `qaflex-update-20260930T044004Z-e275334105bb`，正在建立计划并准备正式打包；尚未生成新包、尚未启动本地演练栈。
- 已完成事实核查：e275 归档 SHA-256 与包外校验一致；e275 镜像内 `app.jar` SHA-256 与清单一致；目标 JAR（`b3c06e6b…`）与 696c→f45e 包一致；e275 迁移集合 137 项、目标 139 项，交集逐字节相同，目标仅新增 `V20260930_02`、`V20261019_01`。
- 已完成环境核查：本机已加载 e275 前后端镜像；既有 e275 演练库卷 `qaflex-pkgtest-20260918-72ab3b365acb_pgdata` 的 Flyway 停在 `20260930.01`，schema 99 张表，可直接作为本次升级起点。
- 待完成：正式构建、独立审计、隔离栈 backup→upgrade→rollback→backup→upgrade、收尾门禁与进度归档。
- 当前阻塞/风险：本地固定容器名 `qaflex-*` 被在跑的 `qaflex-upgrade-sim-20001-20261015` 占用，演练期需临时改名并恢复；不影响其端口与数据。

### 恢复线索

- 当前分支：`release/20261019-90bbb`，HEAD `b10772a2`（干净树）。
- 恢复后首条命令：`cd D:/projects/dcp-restore-20261016 && python scripts/package_intranet_offline.py --mode incremental-update --baseline-dir D:/projects/data_collection_platform_deploy/qaflex-update-20260930T044004Z-e275334105bb --require-fact-rebuild --fact-rebuild-scope all --plan-only`。
- 上一包：`qaflex-update-20261020T080451Z-f45e810ba975`（696c 基线），本任务只更换直接基线，不重跑黄金基线。

## 目标与边界

- 产出与 `qaflex-update-20261020T080451Z-f45e810ba975` 内容等价的新保数据更新包（同源码 `b10772a2`、同目标 Flyway `20261019.01`、同事实重建范围），直接基线改为内网实际运行的 e275 包。
- 按《内网离线打包标准》完成正式构建、独立审计、隔离栈升级/应用回滚/再次升级，并记录 PostgreSQL 容器 ID、Flyway、行数与备份可恢复性。
- 禁止：修改主树 18080/18181/15432 服务或数据；停止/重建任何 PostgreSQL 容器；重跑黄金基线或修改快照；推送未知远端；把本包当作 696c 基线的替代；跳过正式构建步骤。

## 约束与背景

- e275 内网实际镜像：`qa-flex-platform-backend:20260930T044004Z-e275334105bb`、`qa-flex-platform-frontend:20260930T044004Z-e275334105bb`，容器名 `qaflex-backend`/`qaflex-frontend`，端口 20001/20002。
- e275 包 Compose 声明固定容器名与外部 named volume；新包必须逐服务继承（固定名仍为固定名），否则升级会重建 PostgreSQL 容器或撞名。
- 目标源码为 90bbb 发布线：包含 ODS 时间纠正、议题状态解析修复、事实发布控制面与评审/统计变更，无法证明不影响议题与 MR 事实，事实重建范围取 `all`。
- 目标 Flyway `20261019.01` 相对 e275 `20260930.01` 是严格前向超集，可在 e275 库上连续执行。

## 证据与根因

- 内网截图：`qaflex-backend` 20002→18080、`qaflex-frontend` 20001→80，镜像均为 `…20260930T044004Z-e275334105bb`，Up 5 days；用户确认 e275 为当前直接基线。
- e275 清单：`target.images` 为上述双镜像；`flywayVersion=20260930.01`；归档 SHA-256 `9979e5e6…6204c7`。
- 迁移对比：e275 JAR 137 条、目标 JAR 139 条；仅在目标新增 `V20260930_02__fact_task_manual_disposition.sql`、`V20261019_01__source_fact_publication_pending_intent.sql`；137 条交集 SHA-256 逐一相同。
- 本机演练库：`qaflex-pkgtest-20260918-72ab3b365acb_pgdata`，Flyway `20260930.01`，99 张业务表；可作为“已应用 e275”的等价现场。

## 方案与步骤

1. 以 e275 成功更新包目录作为 `--baseline-dir`，先 `--plan-only` 核对基线镜像、容器命名模型、目标 Flyway 与事实范围。
2. 正式执行打包（不使用任何 `--skip-*`）：前端发布测试/类型检查/生产构建、后端 `-DskipTests clean package`、无缓存镜像、镜像内产物摘要、Compose、包内校验和与归档校验。
3. 独立审计：清单字段、包内外 SHA-256、归档结构、Bash 语法、打包器契约测试、禁止内容扫描。
4. 构建隔离基线栈：新建部署目录 `qaflex-e275sim-20261021`，使用 e275 Compose、独立 project/端口（20401/20402/15618）、既有 e275 演练库卷；演练前记录 PG 容器 ID、Flyway、镜像与受保护表行数。
5. 执行 `backup → upgrade → rollback → backup → upgrade`；逐项校验目标镜像健康、后端 UP、前端 200、Flyway `20261019.01`、`counts.diff` 空、备份非空且 `pg_restore --list` 可读、PG 容器 ID 不变。
6. 收尾：停止演练栈并恢复被临时改名的 20001 模拟容器名称；更新进度文档与计划，运行仓库门禁，交付归档/校验/README 路径与未验证项。

## 决策记录

- 采用 e275 包目录而非手工拼装基线副本：它是该实例最后一次成功应用的更新包，Compose 与现场镜像、固定容器名一致。
- 事实重建范围取 `all`：目标线同时触及议题与 MR 的事实/发布契约，且 e275 工作树非干净，不能以 commit diff 缩小范围。
- 本地演练复用既有 e275 演练库卷，不新建候选库、不连接 15432/15438 等共享库；演练库为空业务数据，只验证部署与迁移契约，不替代事实重建验收。
- 不重跑黄金基线：本次仅更换直接基线，源码与产出行为未变。

## 接口契约

- 包类型 `incremental-update`；目录 `qaflex-update-<新 release-id>/`；目标前后端镜像共用新 release ID。
- 清单 `baseline.deployment=qaflex-update-20260930T044004Z-e275334105bb`，基线镜像为 e275 双镜像；`flywayVersion=20261019.01`；`facts.rebuildRequired=true`、`scope=all`。
- 包内 Compose 继承 e275 的固定容器名与外部卷变量；现场 `.env` 不被包覆盖。

## 风险与假设

- 本机固定名 `qaflex-*` 被其它模拟栈占用：仅在演练期临时重命名其应用/数据库容器并在收尾恢复，不停止其容器、不改其端口与卷。
- 演练库无 ODS/事实业务数据：`counts.diff` 只证明 schema 迁移期守恒；事实重建的真实数据验收需在内网现场按 README 执行。
- 正式全量构建可能产生与 f45e 不同的 JAR/前端字节摘要（构建时间戳等），但源码、迁移集合与行为一致；以新清单为准。
