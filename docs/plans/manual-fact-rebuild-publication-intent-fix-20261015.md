# 手工全量事实重建提交失败调查与修复

## 进度与中间物

- 阶段：本地修复及定向验证完成；按用户最新要求不再扩跑，等待用户内网验收，不打包部署。
- 已读：当前提交入口、来源发布状态服务、原始建表迁移、现有测试及发布清单；INSERT 未提供 NOT NULL 镜像运行列，既有测试 DDL 遗漏该列约束。
- 已完成：原始发布类在 PG16 空状态表、三族已有真实镜像 514/READY 两条路径均报 SQLState 23502，日志 `.tmp/manual-fact-rebuild-fix-20261015/released-reproduction.log`。
- 本轮变更：状态服务仅新增契约注释并显式写入未知镜像 NULL；新增 `V20261015_01` 迁移及其唯一锁条目，已有 153 份迁移不变；新增真实迁移测试 9 项，原轻量资格夹具补同名列；长期规则归 D-26/架构引用，现场处置归升级 runbook。
- 验证：修复前完整迁移回归 8 项中 2 失败/4 错误（核心为 23502），0 跳过；修复后新测试 9 + 原资格 16 + 原提交入口 28，**53/0/0/0**。新增测试采用实际默认来源；随后 flyway-test 的原构建服务 19 + 迁移 smoke 20，**39/0/0/0**，共 92 项。未跑全量或黄金，没有把这些定向检查称为全套或内网完整重建。
- 原包独立闭环：保持发布类不变，仅给自有 PG 加新迁移后，首次登记成功且 BLOCKED/无假镜像，已有三族 READY/514 保持，日志 `released-after-migration.log`。
- 证据：`.tmp/manual-fact-rebuild-fix-20261015/{before-fix-tests,after-fix-tests,flyway-profile-smoke-fixed-harness}.log`。profile smoke 首次因隔离 POM 少编译 `IntegrationTestFlywayConfiguration` 导致上下文失败；只补隔离测试工具配置后 39 项通过，未修改共享测试配置或业务实现来掩盖失败。
- 用户范围（2026-10-15）：基本测试及路径跑通即可，内网实际重建由用户验证；本轮不再追加测试、全量套件或发布演练。保留活动计划供内网验收交接，完成后再归档当前事实并删除本计划。
- 收尾：迁移锁 154 项、破坏性迁移检查、smoke 目录检查、运行产物/工作树产物/空白检查与 diff 检查均通过；AGENTS.md 27,110 bytes，无需压缩。四份实现/迁移/测试与隔离已测副本 SHA-256 一致。自有 PG 容器及匿名测试卷已按创建 ID/名称核验后删除，完成的临时探针源码已删除，日志及正式回归保留；未清理其它栈或编译共享 target。

## 恢复线索

- 首条命令：`git status --short -- backend/src/main/java/com/data/collection/platform/service/sync/SyncFactPublicationStateService.java backend/src/test/java/com/data/collection/platform/service/sync`，确认原有共享改动。
- 制品目录：`D:/projects/data_collection_platform_deploy/qaflex-update-20261015T100047Z-c691de911db4`；临时验证目录 `.tmp/manual-fact-rebuild-fix-20261015/`。
- 相关历史：`release-refresh-failure-investigation-20261013.md` 为旧包镜像刷新问题；本次是重建提交阶段，不能复用其根因。

## 目标与边界

- 核实截图的非空错误与“同步后重试可自愈”判断，修复已确认的手工重建提交缺陷并建立真实数据库回归。
- 已有就绪状态和真实镜像身份不得伪造或降级；缺少镜像证据不得凭空变 READY；完整重建意图及修订 CAS 语义保持。
- 不部署、不重新打包、不改内网数据，不修截图顺带提及的前端 UUID 问题；无授权不运行黄金或更新快照，不提交混合改动。

## 约束与背景

- 指定包目标 Flyway `20261013.12`，dirty main 制品，不以当前 HEAD 代替实际包。
- `submitManualFullFactRebuild` 在登记意图之后才提交 FACT_REFRESH，登记 SQL 失败可以导致完全没有新运行/任务记录。
- 原始迁移要求 `latest_mirror_run_id` 为真实 `sync_runs` 外键且 NOT NULL；手工意图入口又允许首次无发布记录的事实族进入 BLOCKED，存在数据模型边界需经复现确认。

## 证据与根因

- 指定归档 SHA-256 `72443746ed4fe18b3115bf71c8d782f869a6fef48470784e547d7cf2a66f39ed` 与包外校验一致；原始 JAR `312cb572ec00ec0441f2651d9b27cfaf8ed0a2b7d2926779ff295bab3280984c` 与清单一致。从未启动的临时容器复制 JAR 后已删除该临时容器，仅原始类连接自有 PG16。
- `requestFullPublication` 的 INSERT 省略 latest_mirror_run_id；迁移 V20260810_01 要求此列非空/真实运行外键。PG 在处理 ON CONFLICT 前校验待插入行，因而已有状态也失败；截图“同步补行后能走 UPDATE 即自愈”判断被原类实测反例否决，不能建议重试代替修复。
- 提交入口先登记意图、后提交 FACT_REFRESH，异常发生在任务创建之前，与无新运行/任务记录相符。现场 4 次失败和 run 514 的实际日志未独立连接查询，不能扩大为已核实全部现场因果。
- 测试漏检：既有 `SyncFactPublicationStateServiceIntegrationTest` 自建状态表省略了镜像运行列和外键/非空约束；新增测试使用全量真实迁移，避免重复弱化模型。

## 方案与步骤

1. 校验归档、镜像和原始 JAR，提取原类与迁移，用独立 PG 复现。
2. 按真实迁移建立失败回归，覆盖首次登记、已存在 READY/BLOCKED、重复请求、无伪造镜像身份及正常同步补齐。
3. 根据意图先于首次镜像这一现有契约选择最小一致模型修复；不采用假运行 ID、删除状态或放松就绪判据。
4. 隔离验证后同步长期文档，执行迁移锁与仓库门禁；发布前黄金/出包另按既定流程。

## 决策记录

- 首先验证 PostgreSQL 的约束/冲突顺序；不直接建议用户再次点重建，也不把空状态行等同于没有完整 ODS。
- 已选方案：意图可以早于镜像完成，仅为 `BLOCKED AND full_publication_requested` 允许 latest_mirror_run_id 为 NULL；新增迁移 drop not null 后用 CHECK 限定该例外，其余状态仍需要真实外键。来源依赖表镜像 ID 继续非空，READY 不获豁免。入口显式插入 NULL，已有记录更新仍只改意图/修订，不覆盖真实镜像身份或就绪状态。
- 否决：用 FACT_REFRESH 运行号冒充镜像、填 0/最大任意运行、删状态、无条件允许所有状态缺镜像，或仅改成 UPDATE 从而丢失首次意图。

## 接口契约

- 暂无新增 HTTP 接口；`POST /api/facts/rebuild` 仍提交当前来源全量重建请求。
- 函数签名不变；新增 `V20261015_01__manual_fact_rebuild_pending_mirror.sql`，只调整来源发布状态的未知镜像表达，SHA-256 `6cc1b27e854977aed07ce419cf6ac921676db81b07473fbd58f7da21d6db0435` 已以追加单条方式登记迁移锁。完整 154 条迁移链通过；已有行内容不变、真实镜像外键及依赖表非空保留、缺镜像意图不能提前结算均已验证。

## 风险与假设

- 当前相关文件混有他人未提交改动，使用最小补丁并隔离编译验证。
- 内网 run 514、autovacuum、读数据集大表等细节仅来自截图，未独立连线核实；不据此认定首次状态缺失的现场时间因果。
- 新代码和迁移尚未打包或部署。原 `20261015T100047Z-c691de911db4` 不含新迁移，重复点击仍不能消除本缺陷；含修复的新包经既定发布流程进入目标实例后，用户再验收返回运行号及后续完整构建/发布。其它触发入口未改，正常镜像终态补齐及既有提交逻辑的回归通过，不把定向检查外推为目标容量或全链路通过。
