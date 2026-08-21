# 进度与中间物

- 状态：生产修复、回归验证、更新包生成和 30001 应用部署已完成；业务数据链路等待现场补齐源配置后验收。
- 已完成：确认全新部署的 `V20260727_02` 在 `issue_fact` 为空时只能创建 325 目录，现有运行期协调器在无范围组时直接返回；确认事实查询存在里程碑缺失时静默写空值的回退。
- 已完成文件/变更：运行期目录初始化与受控补成员、业务键归一化、`V20260821_01` 空目录升级迁移、里程碑源结构守卫、删除空值 SQL fallback；同步修复工作树中一个已删除方法的残留调用。
- 测试：协调器 3 项、迁移升级 1 项、目录/业务键 6 项、源结构与事实 SQL 10 项、Flyway 20 项、事实构建 3 项通过；Java 21 编译、Checkstyle、SpotBugs、后端全量 1164 项（失败 0、错误 0、跳过 1）通过；四项仓库门禁通过。API 漂移检查仍有工作树既有的 6 个已删除 BI 路由映射缺项，本工作单元未处理。
- 当前阻塞：30001 当前库没有 `gitlab_sync_configs`、`sys_table_registry` 或任何 ODS 表，`issue_fact=0`；无法在不人为写入/同步源数据的前提下执行 `issue` 事实重建。

## 恢复线索

- 当前阶段：等待 30001 恢复 GitLab 源配置并同步 ODS 后执行 issue 事实重建验收。
- 恢复后建议执行的首条命令：`docker compose --env-file .env.example -f docker-compose.yml exec -T postgres psql -U qaflex -d qaflex -Atc "select count(*) from gitlab_sync_configs; select count(*) from sys_table_registry;"`。
- 上一份计划或对应 commit：`docs/plans/code-audit-refactor-20260821.md` 为并行审计工作单元；本缺陷以当前 `main` 工作树及 30001 现行镜像为发布基线。

## 目标与边界

- 目标：修复 325 客户问题里程碑目录在全新部署和事实重建后为空、默认为空及已添加范围没有匹配成员的问题。
- 成功标准：非空 325 事实首次发布后目录可用；标准 CC 版本的空格/大小写变体归入同一业务键；已有人工配置只补缺失成员，不自动创建额外组、不启用停用组、不移动或覆盖人工成员；里程碑源结构缺失时事实构建在写入前明确失败。
- 禁止行为：不修改 `issue_fact` 以外的业务事实口径，不动态把事实值混入查询候选，不从系统测试阶段推导客户里程碑，不直接修改内网数据库，不回退用户已有工作树改动。

## 约束与背景

- 后端使用 Java 21、Spring Boot、MyBatis-Plus、PostgreSQL/Flyway；事实与目录由 `issue_fact`、`issue_scope_catalogs`、`issue_scope_groups`、`issue_scope_members` 表承载。
- 325 客户问题范围维度是 `MILESTONE`，事实匹配字段是 `issue_fact.milestone_title`；目录是查询唯一来源。
- Flyway 在事实同步前运行，因此全新空库迁移不能依赖未来事实；运行期事实发布必须覆盖首次事实到达场景。

## 证据与根因

- `V20260727_02__migrate_issue_scope_catalog_data.sql` 从 `issue_fact` 初始化 325 组和成员；全新包执行时事实为空，结果只有目录。
- `CustomerIssueMilestoneCatalogReconciliationService` 当前只读取启用组，无组时返回 0，不会建立初始目录。
- `CustomerIssueMilestoneCatalogService` 只读取管理目录；因此空目录直接导致所有客户问题页面候选和默认值为空。
- `GitlabFactSourceSqlProvider` 的回退 SQL 在里程碑表或字段不可用时写入 `'' as milestone_title`，而 `GitlabSourceSchemaGuard` 未校验里程碑表和 `milestone_id`，会把源结构错误伪装成成功事实。

## 方案与步骤

1. 先补目录首次初始化、人工配置保护、业务键归一化、源结构契约和迁移契约测试。
2. 修改运行期协调器：锁定启用目录；无任何组时从非空事实值创建初始组和成员；已有组时仅补启用同键组成员。
3. 新增只在目录无组时生效的 Flyway 数据迁移，修复已有事实但目录为空的部署。
4. 强化里程碑源结构校验，删除空里程碑回退 SQL 和对应死分支。
5. 更新架构/进度事实，执行定向测试、Java 编译、Checkstyle、SpotBugs、Flyway/文本/diff 门禁并审查差异。已完成。
6. 基于 30001 现行 Compose 只加载并重建 backend/frontend，保留 PostgreSQL 容器/卷和 LDAP；完成后检查健康、迁移、行数守恒、CSRF、页面和日志。已完成；因源配置缺失未执行事实重建。

## 决策记录

- 已选：运行期和迁移各覆盖一类状态。迁移修复已有事实库，运行期覆盖全新空包首次事实发布。
- 已选：以 `CustomerIssueMilestoneIdentity.businessKey()` 作为唯一归一化规则；标准 CC 版本忽略空白和大小写，非标准值只裁剪首尾空白并按不区分大小写匹配。
- 已选：目录已有任意组即视为人工配置边界，运行期不创建新组、不启用停用组；避免自动逻辑覆盖管理员意图。
- 已否决：继续保留里程碑缺失时的空字符串回退；该方案会静默破坏事实数据并掩盖源结构问题。
- 已选：30001 使用保数据更新包，仅更新 backend/frontend；不删除或重建 PostgreSQL volume，不操作 LDAP Compose。
- 已选：现场实际 Compose 与标准更新包模板在容器名/卷定义上不一致，因此部署时保留现场 Compose 结构，仅原子替换两个应用镜像引用；避免更新包模板引入新的 PostgreSQL 身份。

## 接口契约

- 不新增 HTTP API、表字段或对外协议。
- 运行期 `CustomerIssueMilestoneCatalogReconciliationService.reconcilePublishedFactValues()` 继续返回本次新增成员数；新增初始组时返回新增成员总数。
- 新增 Flyway 迁移只写 325 `MILESTONE` 目录的组和成员，且仅在该目录没有任何组时执行。

## 风险与假设

- 假设事实构建前已完成 ODS 里程碑同步；若源表/字段缺失，新的预检会拒绝事实构建而不写入空里程碑。
- 多来源事实共享 325 目录，运行期按项目聚合所有未删除事实值；目录表以 PostgreSQL 目录锁和唯一约束保护初始化。
- 旧部署若已存在人工组，新增迁移不回填未纳入组的事实值；管理员仍需按目录契约明确纳入范围，运行期只补同键成员。
- 发布风险：30001 当前 `issue_fact=0`、目录组/成员均为 0；验收必须确认 ODS 仍有项目 325 里程碑并记录事实重建运行成功，否则不得将页面结果归因于代码修复。
- 已验证：更新包 `qaflex-update-20260821T064536Z-3223b8388269.tar.gz` 归档 SHA-256 为 `dafa29bcfe0647d9ac7ee262c460c182f33748178f6e57878cb12fc5f7373664`；30001 Flyway 为 `20260821.01`，PostgreSQL 容器 ID 未变，保护表行数差异为空，backend/frontend 均 healthy，HTTP/浏览器未登录态可用。
- 已验证：本地 18080 已以 `SPRING_FLYWAY_ENABLED=false` 重启到最新代码，18181 页面加载、未登录态和控制台检查通过；本地事实与 325 目录行数保持不变。
