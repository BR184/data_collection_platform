# label_links 同步失败修复与 20001 重新打包计划

## 进度与中间物

- 状态：阶段 5 进行中。修复已无冲突落到最新 `origin/main@3b87d7c3`，主工作树的冲突与用户改动保持不变；最终代码状态的完整工程门禁已通过，当前提交推送后按规范重新打包并单独生成部署说明 Markdown。
- 现场证据：内网 20001 使用后端/前端镜像 `20260803T082443Z-2ab4bea80a7b`；全量同步仅 `label_links` 任务失败，其他同步入口因此无法形成可信的标签事实结果。
- 本地复现条件：同版本隔离栈运行于 `29080/29181/29532`，配置库为空；本地 GitLab 16.11 数据源可通过 `127.0.0.1:15434` 访问，`label_links` 共 3392 行，其中 Issue 2481 行、MergeRequest 911 行。
- 验证状态：当前发布镜像以真实 GitLab 16.11 数据和 `RECOMMENDED` 23 表配置稳定复现；全量运行 `8` 为 `PARTIAL_SUCCESS`，170 个任务中 16 个第一页因 `MirrorRowChange` 对含 null 行调用 `Map.copyOf` 抛 `NullPointerException`。`label_links` 在本地数据恰无触发 null 并成功，现场由该表触发属于同一数据值契约缺陷。
- 当前进行点：提交推送根因修复，并以当前现场镜像为直接基线生成、审计和演练更新包。
- 最终工程验证：后端 214 个测试类、961 项测试零失败零错误、1 项环境条件跳过；前端 105 个文件、374 项测试，ESLint、TypeScript、生产构建和高危依赖审计通过；Checkstyle、SpotBugs、118 份 Flyway 迁移、API/事实字段/前端边界/标签组/测试卫生/Profile 覆盖、工作树产物/运行产物/文本空白、同步 dry-run 和 25 项打包器测试全部通过。

## 恢复线索

- 当前阶段：阶段 5，根因修复、真实同步/事实链路和完整发布门禁已通过，提交后生成正式更新包。
- 恢复后建议首条命令：`git status --short --branch`，然后执行不带事实重建参数的增量更新包 `--plan-only`。
- 上一份计划：`docs/plans/verify-package-deploy-latest-to-20001.md`；同步机制计划：`docs/plans/implement-incremental-delete-detection-targeted-refresh.md`；发布规则：`deploy/intranet-offline-packaging-standard.md`。

## 目标与边界

- 用户需求：修复当前仅 `label_links` 同步且失败的问题，生成新的内网更新包、完整 `docker-compose.yml`、`.env` 与部署命令。
- 成功标准：真实 GitLab 16.11 数据上 `label_links` 的增量扫描、手动单表刷新、物理删除对账、精准事实与投影发布均成功；相关自动化测试、完整工程门禁、离线包审计、隔离升级/回滚/再升级全部通过；最终镜像、Compose 和 `.env` 使用同一 release ID。
- 明确禁止：不删除 ODS、数据库或 volume；不执行 `docker compose down -v`；不通过忽略失败、重试或全量补偿掩盖根因；不在有冲突的主工作树修改；不保留错误旧路径的兼容分支。

## 约束与背景

- 后端为 Java 21、Spring Boot、MyBatis-Plus、PostgreSQL 16/Flyway；前端为 Vue 3、TypeScript、Vite。
- 新同步权威链路是 `SCAN -> RECONCILE -> 版本化 outbox -> 精准事实/投影发布`；`FULL_COMPENSATION_SCAN` 仅用于基线、反熵和灾难恢复，不能成为本缺陷的日常回退。
- `label_links` 是 `Issue`/`MergeRequest` 多态关系表，真实源主键 `id` 为 integer；任何修复必须保持其他推荐表、其他事实类型和页面统计行为不变。
- 现场 PostgreSQL 宿主端口使用 `15433`，容器内端口保持 `5432`，沿用既有 external volume 和 Compose project。

## 证据与根因

- 已确认镜像和容器健康，端口冲突不是当前同步失败根因。
- 已确认源表结构和索引符合 GitLab 16.11：`id`、`label_id`、`target_id`、`target_type`、`created_at`、`updated_at`。
- 当前代码具备 integer 主键存在性查询、多态目标解析、`DELETE_ONLY`、同任务续页 `RECONCILE` 和版本化 outbox；故障发生在镜像 upsert 返回行转换为变化快照时，早于事实解析与删除对账。
- 根因：`MirrorRowChange` 使用 `Map.copyOf(before/after)` 建立不可变快照，但该 JDK API 禁止 key/value 为 null；GitLab nullable 列被 JSON/JDBC 正确保留为 null 后，在值对象构造处无信息错误为 `NullPointerException`。修复必须保留 null 和插入顺序，同时继续防止调用者修改快照。

## 方案与步骤

1. 已将现有 GitLab DIRECT 配置复制到隔离发布库，并先后以单表和 `RECOMMENDED` 23 表运行收集任务行与完整堆栈。
2. 在镜像存储测试层加入可稳定失败的 nullable 来源行回归用例，覆盖真实 `applyBatch -> MirrorRowChange` 阶段。
3. 修复唯一权威变化快照实现，使其以防御性副本保留 null 和不可变性；不增加表名特判、回退扫描或双轨兼容。
4. 执行定向测试和真实数据链路，验证增量同步、手动单表刷新、删除对账、tombstone、事实 outbox、Issue/MR 事实与无关数据不变。
5. 执行完整后端/前端测试、静态分析、迁移和仓库门禁；更新架构/进度中确有长期价值的事实并删除已完成活动计划。
6. 完整门禁通过后提交推送，以 `20260803T082443Z-2ab4bea80a7b` 为直接基线生成不声明事实重建的新包；本次恢复必须在部署后执行一次全量同步，不能先在不完整 ODS 上单独重建事实。
7. 完成包审计、隔离升级/回滚/再升级，并生成匹配 release ID 的 20001 Compose、`.env` 与单独部署说明 Markdown。

## 决策记录

- 已选在真实 GitLab 16.11 数据上复现，避免依据页面摘要猜测错误。
- 已选隔离 worktree 和隔离发布栈，保护主工作树及本地开发数据。
- 已选新包直接基于现场当前目标镜像 `20260803T082443Z-2ab4bea80a7b`，不退回 20260721 基线。
- 已选发布清单不声明独立事实重建；部署后全量同步负责补齐旧发布漏写的 nullable 行，成功后由既有链路自动提交全量事实刷新。否决先基于不完整 ODS 重建事实。
- 已否决重新删除镜像数据后全量同步；该做法会破坏现场数据且无法保证日常同步正确。
- 已确认修复仅位于共享镜像变化值对象，不涉及迁移；发布清单不声明独立事实重建，恢复数据由部署后一次全量同步及其自动全量事实发布完成。

## 接口契约

- 当前不新增产品 API；同步任务、`RECONCILE` 续页和 outbox 对外语义保持不变。
- 若根因要求调整内部 SQL/类型契约，将直接更新权威实现、全部调用者和测试，不保留旧实现别名或回退。
- 发布接口继续使用 `scripts/package_intranet_offline.py` 和包内 `backup.sh`、`upgrade.sh`、`rollback.sh`；实例 `.env` 与 Compose 在标准包外独立交付。

## 风险与假设

- 内网未导出完整错误栈，但当前发布镜像已在真实 GitLab 16.11 数据上稳定复现同类 nullable 行故障，根因与修复均有失败前后证据。
- `label_links` 同时影响 Issue 和 MergeRequest，测试只覆盖 Issue 会留下 MR 回归风险。
- 同步修复可能改变事实重建范围；必须通过无关项目 generation、不产生 `FULL_EPOCH` 和保护表行数守恒验证精准性。
- `.env` 含实例配置，不进入 Git 或标准归档；生成时必须扫描并避免携带真实凭据以外的无关敏感信息。
