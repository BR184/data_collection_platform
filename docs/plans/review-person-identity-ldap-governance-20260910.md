# 评审数据人名治理方案（以 LDAP 稳定人员标识为权威源）

> 工作单元计划（决策级）。**当前状态：方案完善、已落档，未实现**（用户要求先不实现）。
> 证据基线：`docs/plans/ldap-person-identity-investigation-20260909.md`（LDAP 目录实测、种子校验、跨模块人员来源全景、标签组耦合量化）。本方案取代并删除旧的 `review-person-name-alias-mapping-20260909.md`（其权威源为 GitLab 镜像 + 花名册 XLSX，已被 LDAP 目录取代；其有效的工程机制已并入本文）。

## 进度与中间物

- 状态：**方案定稿待批准实现**。方向（以 LDAP 为中心）、种子（17 组，LDAP 校验）、注入点、跨模块边界、标签组耦合口径、门禁影响均已确定；未动任何生产代码。
- 已完成：调查报告（LDAP 目录 270 人实测：264/266 ACTIVE、real_name 全局唯一 0 重名、离职已移除、user_id/工号双稳定ID、33 名在职者 GitLab 镜像缺失）；写入路径完整性核实（正式/快照各唯一门面，无 Excel 导入写入口——但见「决策记录」待复核项）；跨模块人员来源全景（系统测试/代码走查/客户问题/BI/质量看板均与评审解耦）。
- 变更清单：**尚无生产代码/迁移改动**（仅诊断脚本 + 调查报告 + 本计划 + `.tmp/` 验证产物）。
- 当前进行点：等用户批准（含「决策记录」中标注“待用户确认”的两项）后按「方案与步骤」实现。

## 恢复线索

- 当前阶段：方案定稿、未实现。
- 恢复后首条命令（重跑评审侧人名诊断，本地）：
  `$env:PGPASSWORD='change_this_password'; tools/postgresql-17.9/pgsql/bin/psql.exe -h 127.0.0.1 -p 15432 -U qaflex -d qaflex -f scripts/audit-review-person-names.sql`
- LDAP 目录核对：查 `platform_ldap_users`（`.tmp/query-ldap-*.sql` 为可复现脚本）。
- 既有可参照机制：客户名别名 `IssueCustomerNameAliasService`；下拉框选项设置 D-09（系统设置页 + 权限种子 + endpoint-catalog 登记 + `reviewDataSourceVersion()` 指纹）；LDAP 集成 `LdapPlatformClient`/`PlatformIdentityService`。

## 目标与边界

- 用户原始需求：评审数据页人名下拉有脏数据（错字/同音）、过期数据（离职被误选）；同事已用脏名建了评审与问题数据；生产默认常开兼容模式，脏名随老平台同步回流。用户新思路：**以 LDAP 的稳定人员ID为标准**根治重名/脏数据；调查出的问题名默认种子预置、管理员可改；过期账号不管。
- 成功标准：
  1. 评审下拉候选、列表、详情、导出显示的人名统一为 LDAP 权威正名（`real_name`）。
  2. 兼容快照每次重刷后人名仍为 canonical（不被老平台脏名污染）。
  3. 管理员可在系统设置页维护别名（alias→LDAP 人员），保存后历史数据（快照+正式）自动重算生效。
  4. 搜索/按人名筛选按 canonical 命中一致。
  5. 标签组 person 维度对评审页的按人筛选覆盖 LDAP 全部在职正名（含 GitLab 缺失的 33 人）。
- 明确不做 / 边界：
  - **仅治理评审数据域**。系统测试、代码走查、客户问题、BI、质量看板的人员数据**保持原样**（来自各自 GitLab 事实，与评审解耦；见调查报告 §9.1）。
  - 不做离职账号 state 过滤（用户明确）；离职者的历史评审名按解析链"原样保留"或由别名映射到在职正名（如 徐昊→徐昊2）。
  - 本期不引入 HR 花名册/飞书 open_id 自动导入；LDAP 目录即权威源。
  - 不动老平台 Mongo 源。

## 约束与背景

- 平台栈：Java 21 + Spring Boot(JdbcTemplate) + MyBatis-Plus + Flyway + PostgreSQL 17.9；Vue3+TS+Element Plus；本地库 `127.0.0.1:15432/qaflex`；LDAP HTTP API `127.0.0.1:28081`。
- LDAP 权威源（已实测）：`platform_ldap_users` 提供 `user_id`(LDAP uid，稳定ID)、`employee_no`(工号，含前导零，稳定ID)、`real_name`(权威正名，**全局唯一 0 重名**)、`employment_status`(ACTIVE)。平台已有 `PlatformIdentityService.displayNameForUser(userId)→real_name`。
- LDAP 目录新鲜度缺口：`platform_ldap_users` 仅**首次登录全量同步一次** + 各人登录时 upsert 自己，**无定期全量重同步**（`synchronizeDirectoryIfNeeded` 受 `initial_full_sync_completed` 标志保护）。
- 评审人名以**姓名字符串**存储，散落 ~12 列/8 表（正式 + 兼容快照）；读取无单一口径（matchmode 仓库直读快照基表、BI 走 `review_visible_*` 视图、专家不在视图内）→ 采用**写入/入库规范化**（存储即 canonical），读路径零改动自动一致。
- 兼容快照同步 upsert 有 `where raw_payload is distinct from excluded.raw_payload` 跳过守卫 → 存量行不被定期同步重算，必须一次性回填；`raw_payload=document.toJson()` 保留原文，可幂等重算。
- 写入唯一门面（已核实，无死角）：正式侧 `ReviewDataRecordPersistenceSupport`（用户编辑 + 物化共用）；快照侧 `CodeReviewMatchModeMongoReviewSyncService` 4 方法。
- 黄金基线门禁（AGENTS.md）：下拉候选/评审产出/标签组候选/权限快照变化属"有意变更"，走"展示 diff→用户确认→`-Dgolden.update=true` 重建→审阅"。

## 证据与根因（摘要，详见调查报告）

- 下拉候选污染根因：候选 = `loadUserNames()`(GitLab 镜像，只过滤 mirror_deleted 不过滤 state → 111 离职仍在) ∪ 历史正式名 ∪ 兼容快照名 → 脏名自我污染。
- LDAP 目录质量远优：264/266 ACTIVE、real_name 全局唯一、离职移除、双稳定ID。
- LDAP real_name 全量验证种子并纠正两处：删 `李志→李志2`（LDAP 正名实为"李志"，uid lizhi2）、加 `刘敏→刘敏2`（LDAP 正名"刘敏2"，uid liumin）。
- 标签组耦合量化：LDAP 在职 33 人（含王旭3、刘敏2）在 GitLab 镜像缺失 → 评审改 LDAP 后标签组 person 若仍用 GitLab 候选会漏筛这 33 人。
- 拼音红利：部分脏名 = LDAP uid（`gaoguanmeng`→高贯孟）→ 解析链加"按 uid 精确匹配"可自动归一一批。

## 方案与步骤

### A. 人员解析服务 `PersonDirectoryService`（核心）
- 加载 LDAP 在职目录（`platform_ldap_users` where `employment_status='ACTIVE'`；admin/超级管理员等 employment_status 为空的特殊账号不纳入候选）为：`realName→userId`、`userId→realName`；加载别名表为 `alias→userId`。
- 解析链 `resolve(name)`（逐级，命中即返回该人 `real_name` 作 canonical）：
  1. `btrim(name)`；空 → 原样。
  2. **real_name 精确**匹配 LDAP 在职 → canonical。
  3. **user_id(uid) 精确**匹配 LDAP 在职 → 对应 real_name（自动解决 `gaoguanmeng` 类拼音=uid）。
  4. **别名表**匹配（alias→userId→real_name）。
  5. 都不中 → **原样保留**（不误伤离职历史名/占位垃圾，如 `关立翔`、`未填写`）。
- `canonicalize(name)` / `canonicalizeList(names)`（多值逐元素解析 + 去重保序）。

### B. 别名表 `person_name_aliases`（锚定 LDAP 稳定ID）
- 结构：`alias_name`(PK)、`ldap_user_id`(锚定稳定ID)、`canonical_name`(冗余存当前 LDAP real_name，便于查询/展示，随目录刷新更新)、`note`、`source`('seed'/'manual')、`updated_by`、`created_at`、`updated_at`。
- **锚定 user_id 的意义**：LDAP 里某人 real_name 变更时，别名仍指向正确的人（canonical 通过 user_id 重新解析），实现"以稳定ID为标准"。
- 校验（服务层）：`alias≠canonical`；`alias` 不得等于任一 LDAP 在职 real_name（否则与真人冲突）；`ldap_user_id` 必须存在于 LDAP 在职目录；禁成链（canonical 不能再是别名）、禁成环。

### C. 下拉候选源切换到 LDAP
- `ReviewDataFilterOptionService.getFilterOptions()`：评审专家/负责人/作者候选主源由 `loadUserNames()`(GitLab) 改为 **LDAP 在职 real_name**；并**并入评审数据中实际存在、但 LDAP 无法解析的历史名**（离职者/占位，经 resolve 去重），以保留对历史记录的筛选能力。
- 表单选人候选 = LDAP 在职（干净、无离职、无错字、无重名）；根治自我污染（历史脏名不再反哺为主源）。

### D. 写入/入库规范化（复用已核实的唯一门面）
1. 正式：`ReviewDataRecordPersistenceSupport` 全部人名入参 `canonicalize`——`insertRecord/updateRecord`(review_owner,author_name)、`replaceExperts`(expert_name)、`insertProblemItem/updateProblemItem`(reviewerName,ownerName)、`replaceDescriptions/ensurePrimaryDescription`(author)、`replaceContents`(reviewer_name)。物化源已 canonical，门面再规范化幂等无害。
2. 快照：`CodeReviewMatchModeMongoReviewSyncService` 4 方法（`replaceReviewReports`/`replaceReviewContents`/`replaceProblemDetails`/`replaceDescriptions`）人名值构造处 `canonicalize`（紧邻既有 `moduleName` 规范化）。
3. **Excel 导入写入路径（待复核，见「决策记录」待复核项）**：若 `review.legacy_import` 存在写入口，一并纳入规范化，否则成为绕过门面的死角。

### E. 回填与重算 `ReviewPersonNameNormalizationService`
- 快照：从 `raw_payload` 按当前解析链重算人名列（幂等，不回源 Mongo）。
- 正式：对人名列重跑 `canonicalize` UPDATE；处理 `review_record_experts.unique(review_record_id,expert_name)` 冲突（同记录 徐昊+徐昊2→合并去重、软删重复）；重建受影响 `review_records` 搜索影子字段（`refreshSearchIndex`）。
- 触发：① 首次部署一次性回填；② 每次别名保存后；③（可选）LDAP 目录刷新后。

### F. 候选缓存失效（关键正确性点）
- `getFilterOptions` 整响应经 `PageRecordSnapshotService.readOrRefresh` 缓存，sourceVersion=`reviewDataSourceVersion()`。必须把 **`person_name_aliases` + LDAP 目录版本**（如 max(updated_at)/count/sum）并入该指纹，否则改别名或刷新目录后下拉不刷新。照 D-09 把 dropdown 配置并入指纹的同一做法。列为强制测试项。

### G. 标签组 person 候选按页分源（必决项，见「决策记录」）
- 现状：`LabelValueQueryService` 的 person 维度是"镜像直取、全平台共用一份 `loadUserNames()`(GitLab)"，绑定 评审→reviewOwner、系统测试议题→assigneeName、客户问题→assigneeName。
- 方案（推荐）：把 person 候选改为 **page-aware**——评审页(`review-data-home`)用 LDAP 在职 real_name；议题/客户页维持各自 GitLab 事实来源（与它们保持原样的数据一致）。改 `LabelValueQueryService.loadOptions` 让 person 维度按 pageKey 分源。
- 效果：评审按人筛选覆盖 LDAP 全部在职（含 33 名 GitLab 缺失者）；系统测试/客户问题筛选不受影响。

### H. LDAP 目录新鲜度
- 新增**手动"刷新目录"**（系统设置页动作，用具备目录读权限的服务账号执行 `client.users()` 全量 upsert `platform_ldap_users`）+ 可选定期任务。
- 理由：全量同步需目录读权限 token，现仅依赖有权登录触发一次；作为权威下拉源必须可主动刷新。刷新后触发 §F 缓存失效 +（可选）§E 重算。

### I. 系统设置页「人名别名映射」
- 后端 `PersonNameAliasController`(`/api/person-name-aliases`)：GET 列表(view)、POST 新增(manage)、PUT 改(manage)、DELETE 删(manage)；canonical 通过 LDAP 在职目录选择器选（存 `ldap_user_id`）；保存后触发 §E 重算。权限 `system.person_alias.view/manage`（仅 SUPER_ADMIN/ADMIN）。
- 权限种子迁移：照 `V20260903_01`/`V20260804_01` 授两码。
- 前端：`modules.ts`(key=person-name-alias-settings)+`router.ts`+`route-contracts.ts`；`PersonNameAliasSettingsView.vue`+`api-client/person-name-alias-api.ts`；列表+增删改，新增时校验（重复/自映射/成链/canonical 必须 LDAP 在职）。

### J. 种子清单（LDAP 校验后 17 组，alias→ldap_user_id 锚定）
| alias | canonical(LDAP real_name) | ldap_user_id |
|---|---|---|
| 刘佳琪 | 刘佳祺 | liujiaqi |
| 杨晓宇 | 杨晓雨 | yangxiaoyu |
| 白新慧 | 白欣慧 | baixinhui |
| 形胜南 | 邢胜南 | xsn |
| 陈艺辉 | 陈艺珲 | chenyihui |
| 陈家乐 | 陈嘉乐 | chenjiale |
| 孟秀萍 | 孟秀平 | mengxiuping |
| 张子瑜 | 张梓瑜 | zhangziyu |
| 张家旺 | 张佳旺 | zhangjiawang |
| 燕新睿 | 燕欣睿 | yanxinrui |
| 高杨 | 高扬 | gaoyang |
| 狄琳琳 | 狄林林 | dll |
| dilin | 狄林林 | dll |
| gaoguanmeng | 高贯孟 | gaoguanmeng |
| 徐昊 | 徐昊2 | xuh |
| 王旭 | 王旭3 | wx |
| 刘敏 | 刘敏2 | liumin |
- 明确不映射：李志（LDAP 正名即"李志"，评审数据已正确）、王琨（同人）、李伟/杨波（无评审数据）、关立翔/guanlixiang（离职、LDAP 查无→原样保留）。
- 注：`gaoguanmeng` 可由解析链第 3 步（uid 精确）自动归一，仍显式入种子以稳妥；上线前以生产 LDAP 目录复核全部 canonical 仍在职。

### K. 诊断脚本补全
- `scripts/audit-review-person-names.sql` 遗留侧扩展到 contents/problem_details/descriptions 人名列；新增"评审人名 vs LDAP 目录"对账段（列出 LDAP 无法解析的评审名 = 候选别名）。

## 决策记录

- **已选**：LDAP 目录（`platform_ldap_users`, ACTIVE）为人员权威源。理由：real_name 全局唯一、在职、离职移除、双稳定ID、已同步入库、平台已有按ID解析能力。否决：GitLab 镜像（脏/离职/缺人）、花名册 XLSX（静态）。
- **已选（关键，待用户确认）**：评审人名**规范化存储为 LDAP `real_name`**（不在评审各列改存 user_id/工号），别名表**锚定 `ldap_user_id`**。理由：real_name 全局唯一→名字即稳定键；避免跨 ~12 列/8 表 + 视图 + BI + 搜索 + 导出的大改；别名锚 user_id 已获得"ID 稳定性"（real_name 变更仍指向正确人）。**否决备选**：评审列全量改存 user_id/工号——范围大、且历史/快照源永远是姓名仍需 name→ID 解析、兼容模式每 10 分钟重刷姓名，收益不抵成本。**因用户表述"以稳定ID为标准"，此选型请用户确认。**
- **已选（待用户确认）**：标签组 person 候选**按页分源**（评审页 LDAP、议题/客户页维持 GitLab 事实）。理由：与"其他模块保持原样"一致且各页候选匹配各自数据源。否决：整体换 LDAP（议题/客户 assigneeName 会筛不中）、维持 GitLab（评审 33 人筛不中）。
- **已选**：下拉候选主源换 LDAP 在职 + 并入不可解析历史名（保留历史筛选）。
- **已选**：写入/入库规范化（存储即 canonical），非读时映射。
- **已选**：目录手动刷新（+可选定期）；别名/目录变化并入 `reviewDataSourceVersion()` 指纹。
- **待复核**：评审 Excel 导入写入路径是否存在（权限表有 `review.legacy_import`/`review.template.download`，但前次 grep 仅见导出；实施前必须定位确认）。

## 接口契约

- 表 `person_name_aliases(alias_name PK, ldap_user_id, canonical_name, note, source, updated_by, created_at, updated_at)`。
- API（ApiResponse 包装）：
  - `GET /api/person-name-aliases`(view) → `[{aliasName, ldapUserId, canonicalName, note, source, updatedBy, updatedAt}]`
  - `POST /api/person-name-aliases`(manage) `{aliasName, ldapUserId, note?}`；冲突/自映射/成链/非在职 → 400/409。
  - `PUT /api/person-name-aliases/{aliasName}`(manage) `{ldapUserId, note?}`
  - `DELETE /api/person-name-aliases/{aliasName}`(manage)
  - `POST /api/person-name-aliases/refresh-directory`(manage)：触发 LDAP 目录全量重同步 + 缓存失效（可选并入重算）。
  - 写操作成功后触发 `ReviewPersonNameNormalizationService` 重算。
- 权限：`system.person_alias.view` / `system.person_alias.manage`。

## 风险与假设

- 黄金基线影响（走确认流程）：下拉候选（LDAP vs GitLab）、评审产出（canonical 名）、标签组 person 候选、新别名端点、权限码 → permission-settings/review-data/label-group 等快照变；实现后统一"展示 diff→确认→update 重建→审阅"。
- 正式数据规范化**前向不可逆**（无 raw 保留）：映射错需靠修正别名再重算；快照侧可从 raw_payload 完全回退。故别名 canonical 必须锚 LDAP 在职 user_id 且经校验。
- 多值专家映射后可能重复（徐昊+徐昊2→两个徐昊2）：回填与写入均去重 + 处理 unique 冲突。
- 内网正式数据可能有本地没有的新脏名：种子先放已确认组，内网跑补全后的诊断脚本对账 LDAP，由管理员在设置页补齐。
- 迁移顺序（同一工作单元）：① 建 `person_name_aliases` + 权限种子；② 别名种子（17 组）；③ 代码上线（解析服务 + 两门面 + 候选切换 + 标签组分源 + 目录刷新 + 重算服务）；④ 一次性回填（快照 raw_payload 重算 + 正式重规范化 + 重建搜索影子字段），在③后执行。
- 假设：本地 LDAP 目录结构与生产一致；`raw_payload` 恒含原始人名；生产上线前以生产 LDAP 复核种子 canonical 在职性与 33 人缺失情况。

## 测试

- 后端单测：解析链（real_name/uid/alias/原样四级 + 多值去重）、别名校验（自映射/成链/成环/非在职/撞真人 real_name）、两门面规范化、回填幂等 + unique 冲突合并、`reviewDataSourceVersion()` 并入别名+目录指纹、候选切换、标签组 person 按页分源、目录刷新、设置页 CRUD + 权限拦截、Excel 导入路径（若存在）。
- 前端 Vitest：api-client + 设置页草稿校验 + LDAP 人员选择器。
- 默认快速套件全绿；覆盖护栏要求新端点登记 endpoint-catalog。

## 文档收尾（实现同工作单元）

- `docs/decisions.md`：新增决策（LDAP 为人员权威源、解析链、规范化存 real_name + 别名锚 user_id、标签组 person 按页分源、目录刷新、候选缓存指纹并入），记黄金基线版本状态留痕。
- `docs/architecture.md`：补契约（`PersonDirectoryService`/`person_name_aliases`/两门面注入/候选源切换/`reviewDataSourceVersion()` 并入）。
- `docs/platform-page-business-rules.md`：补规则（评审人名规范化到 LDAP 在职正名；下拉/标签组 person 口径；搜索按正名命中；其他模块人员保持 GitLab 原样）。
- `docs/progress.md`：更新阶段/成果/下一步/验证证据。
- 完成后按 AGENTS.md 将长期价值内容归入权威文档并删除本计划与调查报告（过程细节由 Git 保留）。
