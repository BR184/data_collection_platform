# 表单 v6 发布门禁资料准备（N5 技术验收后）

> 状态（2026-11-03，吸收审核回执 `4812dbec` 后修订）：N5 技术验收通过（输入 `415e2889`，R01—R05 关闭），本文件只整理发布门禁所需材料。T1 已补齐两处人员顺序与 16 表规划依据；T2 已改为“真实创建消费 → 暂停调度 → 命令保留真实入队”，数量重算为 62 份；T3 已修正校验算法、排空顺序、显式开关、应有域左连接与原值恢复。见文末入口。**未运行黄金/update、未改夹具/快照/掩码、未合入 main、未正式出包、未授予真实权限、未启用生产。** 黄金执行、夹具变更、main 合入、出包与生产启用仍待各自批准。

## 进度与中间物

- 阶段：发布门禁资料准备。首条恢复命令：`git status --short --branch && git log -1`；前序计划见 [N5 集成与发布准备](unified-form-v6-n5-integration-release-20261102.md)，技术通过回执为 `76dfb8bb`。
- 技术输入：功能分支 `表单多版本与填写功能开发-20261026`，HEAD `415e288933f0921b2bf3fad47c9139352bf5b166`；main `f74fdef75ea2e1f093c131ceb8c6eee490d397ef`；两者在 `origin` 与 `gitlab25` 回读一致。
- 已冻结验证：最终默认套件 2316 项、0 失败、0 错误、1 条件跳过，392 份 XML；日志 SHA-256 `8e08852064534ed29606d06c0f4ed96078d12e2cfd26ec99090885aa9ea9a888`；174 条迁移锁通过；前端沿用已批准证据。
- 已完成盘点：端点目录、快照 1A/8D/135M、19 个缺失用例、夹具/清单、迁移与升级入口、权限与外部前置。盘点脚本与中间结果在 `.tmp/n5-release-prep-20261103/`（不提交）。
- 进行点：T1/T2/T3 资料已按回执修订，待审核方复审；最终源码固定为分支 HEAD `56004c2e`（资料提交 `30341633` 不作为黄金源码输入），生成前需直接验证 `56004c2e` 的候选服务修复。本阶段不执行任何发布动作。

## 原始需求、成功标准与禁止行为

- 原始需求：记录留原版、新建用当前版、旧记录按原版维护、相同业务字段跨版统计、按版本 Excel 导出；Issue 按启用点 T 分流 FORM/COMMENT，MR 在 T_mr 前继续评论。
- 成功标准：发布范围固定且可追溯；黄金新增/删除/变更逐项可审；升级与回退步骤可执行；外部启用前置明确到“缺什么、谁负责、卡住哪一步”；不把技术验收当作发布批准。
- 禁止：运行黄金或 update、手工改快照/夹具/掩码、把未解释差异改成通过；合入 main、强制推送、正式出包、源码留档、生产启用、真实权限授予、真实 GitLab 写回；改已执行迁移；重构批准 UI；自行设计新模板模型、版本绑定解耦或退役恢复。

## 固定发布范围（只读核对）

- 代码范围：`415e2889`（技术通过输入）至 main `f74fdef7` 的差异，加上功能分支自身提交；共领先 main 148 个提交，merge-base 为 `84e7c023`。main 若前进，只列真实新增差异并重新评估，不擅自更换候选或重放无关功能。
- 读模型范围：分支保留 main `59cd5587` 删除的发布读取基线——39 个 `service/read` 文件、18 条 `V20261012_01`—`V20261015_03` 迁移及对应前端读取帧。合入 main 会恢复该模型，属于本候选的**有意范围**，不是自动合并噪声。
- 迁移范围：main 140 条 → 分支 174 条，新增 34 条（18 条发布读取 + 16 条表单 v6/S1/集成）；140 条共享迁移 checksum 不变，新增迁移全部前向。
- 依赖与配置：`backend/pom.xml`、`frontend/package.json/lock` 无新增依赖；新增配置绑定为 `platform.form-adoption.issueEnabledAt`（未配置不接管）与 `platform.read-fallback.*`（前台等待/租约/回收策略初值）。部署侧只新增 `deploy/read-publication-upgrade-runbook.md`、`deploy/runbooks/form-migration-out-of-order-upgrade.md` 与 `deploy/inspect-fact-refresh.sql`。
- 权限：`form.record.create/edit_own/delete_own` 按迁移授予既有角色；`form.template.manage` 授予 SUPER_ADMIN/ADMIN；`form.record.export` **只登记，不授予任何角色**；旧 `code_review.form.*` 退役。导出权不得由查看权或管理员身份推导。
- 制品：本阶段不构建正式包；出包提交须从双远端一致的已验证 main 生成，包分支在真正出包后按实际 `source.commit` 留档。

## 黄金审阅清单（只读盘点，未执行）

### 1. 端点目录

| 项 | main | 目标分支 | 净变化 |
| --- | --- | --- | --- |
| 总数 | 200 | 220 | +20 |
| READ | 82 | 89 | +7 |
| WRITE | 36 | 43 | +7（新增 10、删除 3） |
| EXPORT | 16 | 16 | 0 |
| OPTIONS | 12 | 13 | +1 |
| EXCLUDED | 54 | 59 | +5 |

新增 23 个：

- 表单 v6 共 17 个：7 个 READ（`reviewer-candidates`、`templates`、`template-scopes/{scopeId}`、`publications/{publicationId}`、`binding-options`、`version-milestones`、`admin/projects`）与 10 个 WRITE（`records`、`records/{id}`、`records/{id}/void`、`template-scopes`、`draft`、`preview`、`publications`、`retire`、删除范围、`recover`）。
- 读取会话 4 个：`POST /api/read-views`、`renew`、`fork`、`DELETE`（EXCLUDED，见第 4 节）。
- 按版导出 1 个：`GET /api/collect-forms/exports/records`（EXCLUDED，pending-approval）。
- 代码走查读取上下文 1 个：`GET /api/code-review/illegal-records/context`（OPTIONS，登记并新增 1 份快照）。

删除 3 个：`POST /api/collect-forms/save`、`delete`、`update-record`，对应 6 份旧快照删除；另 2 份来自 `detail`/`notification-payload` 用例变更，合计 8 份（见第 2 节）。

变更 2 个（仅用例身份变化）：`GET /api/collect-forms/detail` 由 `missing-record` 改为 `missing-scope`；`GET /api/collect-forms/notification-payload` 由 `missing-record` 改为 `unregistered-source`。旧用例快照删除，新用例快照待生成。

### 2. 快照差异（main → 目标分支）

`git diff f74fdef7 HEAD -- backend/src/test/resources/golden-baseline/snapshots`：**1 新增、8 删除、135 修改，共 144 份**。

- 新增 1 份：`code-review/get___illegal-records_context__default.json`。该文件是共同祖先已有、main `59cd5587` 删除、分支恢复的读取上下文快照，不是本轮临时生成；需按“恢复读取基线”审阅，非法页数据源仍保持老平台。
- 删除 8 份：3 个旧写入口的 6 份（`save` 2 份、`delete` 2 份、`update-record` 2 份）随端点删除；`detail`/`notification-payload` 的 2 份旧 `missing-record` 随用例身份变化删除。其中 `post___save__golden-save.json` 在 main 也被修改过，但端点已删除，删除属于 v6 替换的语义决定，不是静默覆盖。
- 修改 135 份：严格应用目录掩码、对象键序与读取帧来源核验后，为 **66 份仅读取帧、38 份掩码/键序后等价、31 份业务/结构差异**；31 份全部已归因（人员顺序与表数量/同步按 `3fd8d2bc` 归因，其余无单一提交来源者按协议/冻结数据集/端点契约归因），未归因业务差异为 0。逐文件字段、原因、既有掩码、处理与预期见 [T1 快照判定](unified-form-v6-t1-snapshot-adjudication-20261103.md)。旧盘点“81 份仅读取帧 / 54 份业务差异”是未应用目录掩码与读取帧来源规范化的粗算，已被严格口径取代。
  - main 已批准业务值（`d3aa90f0`、`eb9b2f8b`、`b07e1985`、`cbca4eeb`）必须在合并后保留，不能整文件取分支旧快照覆盖。
  - 读发布协议差异（`readView`、`publishedReadRequired`、`issue_fact` 物理发布列）按真实来源核验后保留，不递归删除版本/时间字段。
  - 人员/选项顺序已按 T1 第 3.1/3.2 节的实际 SQL、冻结排序输入与 tie-breaker 给出有序结果；评审数据筛选项已用冻结夹具重放至与 head 逐元素相等，缺陷原因统计板按 `business_order_id, id` 与首现去重给出有序指纹。成员集合相同只证明没有增删。
  - CAT 两页预期 `HTTP 409/READ_VIEW_UNAVAILABLE`，目录必须显式声明，不伪装成功。
  - 表数量/同步消息 6→16 已按 T1 第 3.3 节核定为 3 个事实任务 + 6 个基础投影 + 7 个客户里程碑范围组投影；main 为 3+3，差异由 `3fd8d2bc` 引入。
- 结论：黄金执行时不能“整树取分支快照”。应按 T1 每文件处理结论合并实现、保留 main 已批准值、按 T2 生成表单成功样本，再严格 compare；144 份存量差异不作为整批 update 范围。

### 3. 待生成快照（既有 19 个用例 + T2 成功样本）

既有 19 个用例均为空平台上的确定性校验/空态用例，不写入业务数据；写端点附带表快照用于证明“失败路径零写入”，共 19 份响应 + 16 份表快照。T2 真实成功样本另行新增 6 份响应 + 21 份表快照；**本轮合计需生成 62 份（25 响应 + 37 表）**，并另有 8 份既有快照预期更新（6 份种子/基线运行影响 + 2 份 refresh-one 表快照），逐项见 [T2](unified-form-v6-t2-success-fixture-20261103.md)。

| 端点 | 用例 | 响应快照 | 表快照 |
| --- | --- | --- | --- |
| `GET /api/collect-forms/detail` | `missing-scope` | `get___detail__missing-scope.json` | — |
| `GET /api/collect-forms/notification-payload` | `unregistered-source` | `get___notification-payload__unregistered-source.json` | — |
| `GET /api/collect-forms/reviewer-candidates` | `default` | `get___reviewer-candidates__default.json` | — |
| `POST /api/collect-forms/records` | `missing-scope` | `post___records__missing-scope.json` | `form_record_audit_logs`、`form_change_heads` |
| `PUT /api/collect-forms/records/{id}` | `missing-scope` | `put___records_{id}__missing-scope.json` | `form_record_audit_logs`、`form_change_heads` |
| `POST /api/collect-forms/records/{id}/void` | `missing-scope` | `post___records_{id}_void__missing-scope.json` | `form_record_audit_logs`、`form_change_heads` |
| `GET /api/collect-forms/templates` | `default` | `get___templates__default.json` | — |
| `GET /api/collect-forms/template-scopes/{scopeId}` | `missing-scope` | `get___template-scopes_{scopeId}__missing-scope.json` | — |
| `POST /api/collect-forms/template-scopes` | `unknown-template` | `post___template-scopes__unknown-template.json` | `form_template_scopes`、`form_template_scope_drafts` |
| `PUT /api/collect-forms/template-scopes/{scopeId}/draft` | `missing-scope` | `put___template-scopes_{scopeId}_draft__missing-scope.json` | `form_template_scope_drafts` |
| `POST /api/collect-forms/template-scopes/{scopeId}/preview` | `missing-scope` | `post___template-scopes_{scopeId}_preview__missing-scope.json` | `form_template_preview_evidence` |
| `POST /api/collect-forms/template-scopes/{scopeId}/publications` | `missing-scope` | `post___template-scopes_{scopeId}_publications__missing-scope.json` | `form_template_publications`、`form_template_versions` |
| `POST /api/collect-forms/template-scopes/{scopeId}/retire` | `missing-scope` | `post___template-scopes_{scopeId}_retire__missing-scope.json` | `form_template_scopes` |
| `DELETE /api/collect-forms/template-scopes/{scopeId}` | `missing-scope` | `delete___template-scopes_{scopeId}__missing-scope.json` | `form_template_scopes`、`form_template_scope_drafts` |
| `GET /api/collect-forms/publications/{publicationId}` | `missing-publication` | `get___publications_{publicationId}__missing-publication.json` | — |
| `POST /api/collect-forms/publications/{publicationId}/recover` | `missing-publication` | `post___publications_{publicationId}_recover__missing-publication.json` | `form_template_publications` |
| `GET /api/collect-forms/binding-options` | `default` | `get___binding-options__default.json` | — |
| `GET /api/collect-forms/version-milestones` | `unknown-source` | `get___version-milestones__unknown-source.json` | — |
| `GET /api/collect-forms/admin/projects` | `unknown-source` | `get___admin_projects__unknown-source.json` | — |

生成前提：上述 19 个校验/空态用例保留不变；成功详情、新建/编辑/作废与按版 Excel 的夹具、行数、权限与恢复方式见 [T2 成功样本与导出提案](unified-form-v6-t2-success-fixture-20261103.md)。T2 新增 6 份响应与 21 份表快照，合计 **62 份（25 响应 + 37 表）**，并需同时批准 2.1/6.5 的既有快照更新；全部经用户确认后才能生成。

### 4. EXCLUDED 理由与替代验证

| 端点 | 理由 | 替代验证 | 待确认 |
| --- | --- | --- | --- |
| `POST /api/read-views`、`renew`、`fork`、`DELETE` | 会话建立/续约/下钻复制/释放，返回随机读取身份；随机身份本身不构成排除业务产出的理由 | 业务产出由对应页面端点快照锁定；租约生命周期、权限与来源闭包由真库/HTTP 测试覆盖 | 是否接受“会话端点排除、页面端点覆盖”的边界 |
| `GET /api/collect-forms/exports/records` | pending-approval：N4 P5 新增按版导出，需要用户确认快照期望 | 版本/项目/目录 C 谓词、空版表头、作废排除与审计由隔离 PG 导出测试覆盖 | T2 提议改为 `EXPORT`：新增有值版本成功快照，复用 Excel 单元格规范化；夹具、测试期临时导出权限与审计副作用见 T2，待审核方与用户批准 |
| 既有 external-dependency 端点 | 依赖外部数据集，不生成稳定快照 | 沿用既有替代验证 | 无变化 |

### 5. 夹具与掩码

- 夹具：当前 main → 分支只新增 `fixtures/compatibility-completeness.sql.gz`（380 字节，SHA-256 `9ada5cd14b782c7a244a0e1033fd093a0005a252f1577ce6318bec983062a061`）；`baseline-manifest.json` 新增 `completeness_extension`（`version=20261014-read-publication`、`approved_by=user`、`business_fixture_unchanged=true`），只登记兼容性清单，业务行不变。T2 提议新增最小 `issue_response` 成功样本与导出夹具；未批准前不改动，兼容性清单与业务行分开。
- 掩码：当前未新增 `ignore-paths`/`ignore-columns`；删除的只是旧写入口随端点移除的掩码。T2 提议只为成功写用例的审计 `client_ip/user_agent`、`fact_change_heads.updated_at` 声明表列掩码，并给出序列校准替代部分掩码；须逐项批准，不能静默扩大掩码。
- 已知易变项：部分快照包含 `dbPort`、`lastSyncedAt`、`createdAt` 等环境/时间值，主线与分支取值不同。黄金执行前须确认现有掩码是否覆盖；未覆盖时先报差异，不直接改快照。

### 6. 需要用户确认的事项

1. 批准按 [T1](unified-form-v6-t1-snapshot-adjudication-20261103.md) 逐文件处理 144 份差异：66 份保留读取帧、38 份等价、31 份按归因处理；不得整批 update、不得覆盖 main 已批准值。
2. 批准 [T2](unified-form-v6-t2-success-fixture-20261103.md) 的最小成功夹具、6 个新用例、62 份生成范围（25 响应 + 37 表）及 8 份既有快照更新，或指定调整项。
3. 批准 8 份旧入口快照删除与 1 份读取上下文快照恢复。
4. 对按版导出端点选择“保留 EXCLUDED”或“按 T2 改为 EXPORT 并接受夹具/测试期临时权限”；正式导出授权名单仍单独由权限管理员提供。
5. 确认 T2 新增的表列掩码（审计环境列、`fact_change_heads.updated_at`）与序列校准方案；其余新增掩码另行审批。
6. 指定黄金执行窗口与操作者；执行时按仓库门禁停服、记录健康，按“批准 → 生成 → 逐份审阅 diff → 严格 compare”收敛。

### 7. 黄金执行顺序（提议，未执行）

1. 代码合并与发布候选冻结后，从干净工作树执行。
2. 运行前记录开发服务入口与版本，按资源需要停止服务；结束后按原入口恢复并验证 `/actuator/health=UP`、前端可访问。
3. 合并候选与 T1 处理完成后，先做一次严格 compare，采集实际差异与缺失快照；不得直接进入整批 update。
4. 将差异按“读取帧新增/表单新增/main 已批准/协议与人员顺序/旧入口删除”分组交用户审阅，并逐项核对 T1/T2 的处理结论。
5. 仅在批准后用 `-Dgolden.update=true` 生成；生成后逐个文件审阅 git diff，任何未获批准的文件必须恢复到 main 值或修实现，不能把 update 当整批覆盖。之后再去掉 update 严格 compare，全绿才可继续打包。
6. 若 compare 出现未解释差异，修实现，不改快照；禁止手工编辑、放宽掩码或挪入 EXCLUDED。

## 升级步骤（与外部启用分开）

可执行步骤、开关原值/恢复值、对象核对、读发布指针验收、逐项恢复与回滚条件以 [表单迁移乱序升级 Runbook](../../deploy/runbooks/form-migration-out-of-order-upgrade.md)（T3）为准；本节只保留目标库判定摘要。

### 1. 目标库身份与迁移前缀

升级前只读留存 `current_database()`、`current_schema()`、`flyway_schema_history` 全量版本/描述/成功标志/校验和，并与目标 174 条集合比对。常见前缀：

| 目标库前缀 | 版本数 | 待执行 | 说明 |
| --- | --- | --- | --- |
| 仅 main 集 | 140 | 34（18 读取 + 16 表单） | 第一次升级到读发布模型 |
| 共同祖先读发布集（fork `84e7c023`） | 156 | 18（`20261019_01`、`20261022_03` + 16 表单） | 曾部署读发布包但未含 main 后续两条 |
| 读发布集 + main 两条 | 158 | 16（表单 v6/S1/集成） | 已接续 main 后续迁移 |
| 其它数量/未知版本/校验和不符 | — | 停止 | 先核对身份与历史，不 repair、不删历史、不猜前缀 |

数量只是快速判据，最终必须按版本集合判定；不得把“已执行读发布脚本”当成要删除的脏历史。生产若曾部署读发布包后又回退二进制，数据库仍保留已执行迁移，升级按上表实际前缀处理。

### 2. 升级前

- 取得可恢复备份与目标库身份记录；明确升级窗口和回退负责人。
- 停止镜像/事实写入前完成一次合格同步与事实发布；记录待发布 heads/构建任务为零、无候选占用。
- 旧库若预装未知分支脚本、版本集合与目标不一致，或共享 15432/15433 无法确认归属，停止并报告。

### 3. 一次性迁移

使用既有无 Web 单次迁移入口，显式开启乱序：

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

成功判据：退出码 `0`、无迁移失败、历史总数 174、水位 `20261102.08`、共享 140 条 checksum/描述不变。失败即停止，不执行 `repair`、不删除/改写历史、不放宽校验；保留日志并恢复备份或提交修订方案。

### 4. 默认校验

去掉 `--spring.flyway.out-of-order=true`，以 `PLATFORM_RUN_MODE=service` 启动原常驻制品/Compose。默认 `false` 下 `flyway.validate()` 通过、`/actuator/health=UP`，说明乱序只用于一次性窗口，没有进入常驻配置。

### 5. 事实/投影重建与预热

- 读发布模型由服务模式后台调度逐域接管：每轮一个事实域/CAT 快照；后台总开关关闭时不接管。
- 至少对受影响域执行完整事实重建：ISSUE（客户问题记录/筛选/详情/BI/客户非法页）、MERGE_REQUEST（MR 逐记录投影与表单统计）、表单评审投影（`form_review_fact`、`code_review_formal_records`）。重建期间旧完整版本继续可读，不展示半成品。
- 等所有应有域在 `read_publication_domains` 指向 `PUBLISHED`，`read_publications` 序列/业务日正确，再恢复同步与写回。
- 预热与验收页面：客户问题记录/非法页/统计板、BI 客户问题、质量看板、表单管理（项目候选）、表单记录页；核对可下钻、可导出（获授权账号）、无“等待聚合”假空态。
- 本地小规模回归不证明现场容量或重建耗时；目标规模负载与预热时间按内网实测记录，不写死承诺。

### 6. 回退边界

- 回退必须恢复**升级前已验证的数据库备份 + 配套旧发布包**，不能只换旧二进制：迁移和读发布模型已改变结构与数据。
- 迁移失败或校验不通过时停在迁移退出口，不改历史、不 repair、不做部分回退；恢复备份后重新核对。
- 已公开历史不原地修补；失败候选按现有中止/巡检/无引用历史回收入口处理，不手工 DELETE。

## 外部启用前置（只列缺口，不执行）

| 前置 | 缺什么 | 负责人角色 | 卡住哪一步 | 当前状态 |
| --- | --- | --- | --- | --- |
| E1 CI_ROBOT 投放/认证 | 真实机器人源码/配置、稳定 Issue 链接、notification-payload 外部调用与认证样例 | 机器人维护者、接口负责人 | Issue 入口真实投放与外部契约迁移 | 未取得；本地仅有稳定路由与内部模板 |
| E2 来源与启用时点 | 目标来源项目、Issue 启用点 T、真实存量登记数据、权限清单 | 范围/业务负责人 | Issue FORM 正式接管；T 前保持 COMMENT | 未取得具体时点与清单 |
| E3/E5 候选来源与自动填充 | 真实项目/里程碑/代码分支候选、AI 自动填充流程 | 业务/数据负责人 | S1 候选与自动带出的现场数据 | 未取得 |
| E4 域名/代理/会话 | 最终域名与路径、代理 Cookie/会话策略、实际浏览器环境 | 部署/网络负责人 | 现场登录、旧链接与 401 草稿恢复 | 未取得；本地 18080/18181 |
| E6 旧写回切换 | 旧写回所有者、停写窗口、真实写回验收 | 旧写回负责人 | 延期标签写回切换；MR 继续 COMMENT | 未取得 |
| 导出权限名单 | `form.record.export` 的当前授予与默认授予名单 | 权限管理员/业务负责人 | 正式导出授权 | 当前只登记、未授予；默认清单为空，不得由查看权/管理员推导 |
| 目标库与升级窗口 | 目标实例身份、备份、停写窗口、回退负责人 | DBA/部署负责人 | 174 条一次性升级 | 未取得 |
| 黄金执行批准 | 第 6 节六项确认与执行窗口 | 用户 | 快照生成与严格 compare | 未批准 |

## 保留边界与未批准设计

- 生产 MR 在 T_mr 前继续 COMMENT；不因表单端点已存在而改写历史 MR 权威。
- 代码走查非法页数据源整页保持老平台；新增的读取上下文端点只是会话/协议能力，不改变该页业务来源。
- Issue 正式启用仍需持久权威、范围覆盖、授权/发送前复核链单独验收，不能由本地套件或导出权推导。
- 未批准设计仅列待确认，不进入本轮候选：独立新模板模型、版本绑定解耦、退役恢复。
- 本阶段不执行真实权限授予、真实 GitLab 写回、生产启用或源码留档。

## 风险、假设与敏感只读数据

- 最大风险是 144 份快照整树覆盖：会把 main 已批准的 `cbca4eeb`、质量看板与 timestamptz 变更回退。必须按 T1 逐文件处理。
- 31 份业务差异已有归因；人员顺序与表数量/同步已补 `3fd8d2bc` 归因与直接核验，其余无单一提交来源者（CAT/物理发布列/协议字段/构建消息）仍须审核方确认归类与处理预期，不得用掩码掩盖人员集合或 sourceVersion 失配。
- 快照含随机端口/时间字段，严格 compare 可能因环境值失败；T1 已逐项核对既有掩码，T2 只为成功写新增少量表列掩码并配序列校准，未批准前不得扩大。
- 目标库可能已有读发布迁移历史；不可按“140 前缀”机械升级，必须先读真实版本集合。
- 外部启用材料全部未取得；缺少只阻断对应启用步骤，不阻断本文件与代码审阅。
- 敏感只读：`gitlab_sync_configs`、角色权限表、真实 ODS/事实数据；不写入、不授权、不访问未获准环境。

## 本批资料入口（T1—T3，均待审阅）

| 资料 | 范围 | 状态 |
| --- | --- | --- |
| [T1 快照判定](unified-form-v6-t1-snapshot-adjudication-20261103.md) | 144 份差异逐文件字段/原因/掩码/处理/预期；31 份业务差异零未归因 | 资料完成，待审核方裁定 |
| [T2 成功样本与导出提案](unified-form-v6-t2-success-fixture-20261103.md) | 最小 `issue_response` 夹具、详情/新建/编辑/作废、按版 Excel、权限与恢复、19+16 缺失清单 | 提案完成，待审核方与用户批准 |
| [T3 升级 Runbook](../../deploy/runbooks/form-migration-out-of-order-upgrade.md) | 140/156/158 前缀、一次性迁移、冷启动校验、接管/重建、逐项恢复、回滚与停止条件 | 资料完成，待升级窗口批准 |

- 本批只做资料、离线分类与必要直接核验；黄金执行/update、夹具变更、main 合入、正式包、升级、真实授权分别审批。
- 未授权：新模板模型、版本绑定解耦、退役恢复、短链实施、生产启用。
