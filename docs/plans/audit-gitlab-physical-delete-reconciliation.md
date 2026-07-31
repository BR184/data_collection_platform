# GitLab 物理删除同步覆盖审计与修复交接

## 进度与中间物

- 状态：目标版本代码与本地验证已完成；工作单元继续保留到内网发布、全量补偿和真实删除链路验收完成。
- 已完成：统一根实体与可变关系目录，补齐 `notes`、Issue/MR metrics、System Hook 根实体删除、复合范围事实影响、Issue/MR 原子事实替换及 GitLab 16.11 标签事件字段/整数 action 契约。
- 验证完成：同步/事实核心回归 93 项、Java 21 主/测试源码编译、Checkstyle、SpotBugs、Flyway 112 份迁移门禁、事实字段、文本空白和差异检查通过；只读现场 schema/取值确认 `resource_label_events.action` 为 `integer` 且 `add=1/remove=2`。
- 交付物：本文件是后续修复的交接规格；`docs/architecture.md` 仍是已落地同步模型的权威事实，不能被本调查中的待修项覆盖。
- 本地验证：只读访问 `gitlab-data-web-1` 的 `gitlabhq_production`，确认 PostgreSQL 14.11、GitLab CE 镜像 16.11.10；本地平台配置为 `RECOMMENDED`。未把本机数据量或运行状态当作内网验收结果。
- 当前阻塞：无本轮代码阻塞。完整 Spring 集成测试受并行工作单元 `schema.sql` 引用缺失表阻断；运行产物位置门禁受根目录既有 8 个 `.tmp-*.log` 阻断。上线前须在内网 GitLab 复核 schema，并执行本报告的删除场景验收。

## 恢复线索

- 当前阶段：代码完成，等待内网发布与真实删除链路验收。
- 首条命令：`git diff --check`；发布验收按本文“部署与现场验收”执行。
- 前置工作：标签关系漂移修复已实现于当前工作树；权威架构说明见 [architecture.md](../architecture.md)。

## 目标与边界

### 用户目标

按已确认调查结果直接实现目标版本：所有已消费且可物理删除的 GitLab 实体或关系都通过统一权威范围收敛 ODS，并使 Issue、MR、集成测试事实及统计投影在同一发布链路删除旧结果；不得在页面或统计查询层增加兼容过滤。

### 成功标准

1. `notes`、`merge_request_metrics`、`issue_metrics` 及已声明人员/标签关系都能由父对象或自身增量派生完整范围，空集合会清理 ODS active 行。
2. System Hook 的评论和根实体删除使用权威范围；`resource_label_events` 使用 GitLab CE 16.11 的 `issue_id/merge_request_id` 字段，不再读取不存在字段。
3. 事实影响可从 `lookup_scope_json` 和 tombstone 根实体恢复父目标；最后一条关系删除仍会触发对应事实刷新。
4. Issue/MR 定向事实发布先清理目标旧事实及从属成员，再写入当前仍存在的事实；空来源是有效删除结果。
5. 定向测试、同步回归、Java 编译、静态检查、迁移校验和工作树门禁通过；不修改 GitLab 源库，不执行真实同步或事实重建。

### 明确禁止

- 不修改 GitLab 源库，不执行同步、全量补偿或事实重建。
- 不为某个页面、项目、标签或单个 Issue 添加特例。
- 不把 GitLab 通用资料或命名推测写成已验证的现场事实。
- 不保留旧的单行 `updated_at` upsert 路径作为关系删除的兼容回退。

## 约束与背景

- 数据链：GitLab 源库 -> ODS 镜像 -> `issue_fact`/`merge_request_fact`/`integration_test_fact` -> 页面、统计和导出。
- 增量同步通过来源 `updated_at` 读取仍存在且有变动的行；物理删除的行不再出现，不能仅凭空结果识别删除。
- 已落地基线：`AUTHORITATIVE` 任务按非空复合 `lookupScope` 读取父对象下当前完整关系集合，在同一事务中替换 ODS active 集合，缺失关系写入 `mirror_deleted=true`；空集合表示完整清空。
- 全量/补偿采用 `FULL_RECONCILE` 的扫描与删除对账；成功后必须发布全量事实刷新。
- 历史脏数据恢复顺序：部署 -> 一次成功 `FULL_COMPENSATION_SCAN` -> 等待事实刷新 -> 按 ODS、事实、统计三层验收。

## 证据与根因

### 已修复的标签关系问题

1. GitLab 移除 Issue 的“一级缺陷”标签时删除的是 `label_links` 关系，而非标签对象。
2. 旧同步只按 `label_links.updated_at` 读取存在行，物理删除后 ODS 留下 active 旧关系。
3. 事实层据此保留 `issue_fact.severity_level=LEVEL1`，统计继续计入一级缺陷。
4. 标签颜色读取的当前 ODS 标签信息与严重程度读取的事实层版本不同，因此可出现颜色变化但统计仍保留的分层不一致。
5. 仅重建事实无效：事实构建仍会读取陈旧 ODS 关系。

### 已验证的当前保护

`AuthoritativeRelationCatalog` 当前声明：

- `issues` -> `issue_assignees`
- `issues` -> `label_links`，范围 `(target_type=Issue, target_id)`
- `merge_requests` -> `merge_request_assignees`
- `merge_requests` -> `merge_request_reviewers`
- `merge_requests` -> `label_links`，范围 `(target_type=MergeRequest, target_id)`
- `resource_label_events` -> `label_links`：代码虽有声明，但错误假设事件包含
  `resource_type/resource_id`；现场 GitLab CE 16.11 实际为 `issue_id/merge_request_id`，
  因此该触发路径**尚未生效，属于待修缺口**，不能计入当前保护。

前五类关系由父资源增量或 System Hook 派生 `AUTHORITATIVE` 任务；缺失关系仍进入事实影响范围。
标签事件必须先按本报告的实际字段映射修正，才能参与该链路。

## 调查方法与步骤

1. 从 GitLab source table catalog、Flyway ODS schema、同步任务规划和事实构建读取中建立“实际使用表”全集。
2. 对每张表检查来源主键、`updated_at`、软删除字段、父键、唯一约束和 GitLab 事件替代来源。
3. 将可变关系与当前 `AuthoritativeRelationCatalog` 逐项比对，确认完整集合的最小正确范围和类型隔离。
4. 追踪每张风险表变更后的 ODS tombstone、事实影响和统计/页面消费者，防止只修镜像不刷新派生数据。
5. 使用仓库测试、schema 守卫和本地只读数据库元数据验证结论；对于无法从本地得到的 GitLab 现场 schema，输出可直接执行的只读 SQL。
6. 给出一次性目标版本修复步骤、迁移策略、测试矩阵和发布补偿步骤。

## 决策记录

- 已选：关系删除使用“父对象完整集合替换”，普通实体删除使用“主键集合全量对账”。原因是两类删除的最小可验证范围不同。
- 已否决：在统计页过滤旧严重程度。原因是只掩盖投影，ODS 和事实仍错误。
- 已否决：仅为 `label_links` 增加标签专用清理。原因是同样的物理删除风险存在于其他可变关系。
- 待定：未进入当前平台来源目录的 GitLab 表是否属于业务范围；必须先确认其被镜像或被事实/API 消费，不能为了通用 GitLab 全库预先实现。

## 接口契约

- 当前契约：`AUTHORITATIVE` 任务必须携带非空、规范化复合 `lookupScope`；其来源结果代表该范围的完整当前集合。
- 当前契约：`PRECISE` 只 upsert 返回行，不能被用于删除推断。
- 新增接口：本调查阶段无新增 API、表结构或外部协议。
- 后续修复若新增关系，必须在 `AuthoritativeRelationCatalog` 声明 `parentTable`、`parentKey`、`childTable`、`childLookupColumn`、固定类型限定和父行限定列，并为“空集合删除全部关系”和“类型隔离”添加测试。

## 风险与假设

- 高风险：仅用来源表名或 GitLab 社区习惯推断现场表结构；必须以平台来源配置及现场 schema 为准。
- 高风险：父对象本次未命中增量时，关系物理删除不会通过日常增量被发现；由全量补偿覆盖。
- 高风险：若关系删除未纳入事实影响范围，ODS 已正确 tombstone 仍可能留下旧事实。
- 假设待验证：现有 GitLab source table catalog 已包含所有会进入事实和页面的来源表。

## 调查报告

以下内容记录实施前的调查基线与根因证据；当前实现状态以本文件“进度与中间物”及 `docs/architecture.md` 为准。

### 实施前审计结论

本次既有修复已经正确覆盖四类直接关系：Issue 指派人、Issue/MR 标签、MR 指派人和 MR 审核人。它不是“所有推荐表均已防物理删除”的完整方案。

已确认的待修缺口如下：

1. `notes` 已进入 Issue、MR 和集成测试事实，但没有完整集合替换。
2. `merge_request_metrics` 已进入 MR 事实，但没有完整集合替换。
3. `resource_label_events` 使用了当前 GitLab CE schema 中不存在的列，标签事件不会派生关系替换任务。
4. System Hook 对根实体和未声明表使用 `PRECISE`；来源查询为空时只完成空 upsert，不会把本地行标记删除。
5. Issue/MR 的定向事实构建只 upsert 当前仍存在的来源行，空来源目标不会删除旧事实；集成测试事实已有目标删除逻辑，三类事实行为不一致。

因此，当前实现能解决“删除标签/取消指派且父 Issue/MR 被本次同步或 Hook 读取”的场景，不能宣称已覆盖所有物理删除。

### 现场 GitLab schema 证据

本机 `gitlab-data-web-1` 的 GitLab CE 16.11.10（PostgreSQL 14.11）只读元数据确认：

- `issue_assignees` 主键为 `(issue_id,user_id)`；`merge_request_assignees` 和 `merge_request_reviewers` 只有 `created_at`，没有 `updated_at`。
- `label_links` 有 `id,label_id,target_id,target_type,created_at,updated_at`，`label_id -> labels(id)` 为 `ON DELETE CASCADE`。
- `merge_request_metrics.merge_request_id -> merge_requests(id)`、`issue_metrics.issue_id -> issues(id)`、Issue/MR 指派人与审核人到父对象的外键均为 `ON DELETE CASCADE`。
- `notes` 使用多态 `noteable_type,noteable_id`，没有 Issue/MR 外键；删评论后没有可供 `updated_at` 增量读取的 tombstone。
- 实际 `resource_label_events` 列为 `issue_id,merge_request_id,epic_id,label_id,...`，没有 `resource_type` 或 `resource_id`。当前目录和事实 SQL 对后两列的假设在该 GitLab 版本不成立。
- `projects` 有 `marked_for_deletion_at`，但 Issue、MR、标签、评论和关系表没有统一 `deleted_at`；不能把 GitLab 逻辑删除作为同步前提。

### 当前事实消费者与表级结论

| 来源表 | 当前使用字段/用途 | 删除风险与当前结论 |
| --- | --- | --- |
| `issues` | `id,iid,project_id,title,description,author_id,milestone_id,created_at,updated_at,closed_at,state_id`；Issue/集成测试事实主对象 | 直接删除后整行消失并级联子关系。`FULL_RECONCILE` 最终可发现；System Hook 精确空查和定向事实删除未完整处理。 |
| `projects` | Issue/MR 项目名称、路径、命名空间 | 项目删除会级联 Issue/MR 等；维表更新会触发全量事实回退，物理删除仍依赖精确实体 tombstone 或全量对账。 |
| `users` | 作者、指派人、审核人、合并人名称 | 同上，维表删除会影响多类事实。 |
| `namespaces` | MR owner/path | 维表；当前依赖全量对账处理物理删除。 |
| `milestones` | Issue 里程碑标题 | 项目/群组删除可级联；事实 SQL 有缺表 fallback，物理删除仍依赖全量对账。 |
| `labels` | 标签标题、颜色和所有标签派生字段 | 删标签会级联 `label_links`。若父 Issue/MR 未更新，日常增量可能不知情；当前没有标签对象精确 tombstone。 |
| `label_links` | Issue/MR 标签集合、严重等级、模块、状态、修复时间 | 已修复：按 `(target_type,target_id)` 完整替换；但标签事件映射错误，不能把该事件路径当作已覆盖。 |
| `issue_assignees` | Issue 处理人/指派人 | 已修复：按 `issue_id` 完整替换。 |
| `notes` | Issue 备注、修复人、响应模板；MR 走查日期/人员 | 未修复，高风险。现有 Hook 只按 `notes.id` 做 `PRECISE`，空结果不清 ODS。 |
| `resource_label_events` | 仅在字段契约满足时作为 Issue 修复时间事件来源 | 实际 CE schema 不符合代码契约，当前事件分支不可用，必须按 `issue_id`/`merge_request_id` 映射或明确禁用。 |
| `merge_requests` | MR 主对象、标题、分支、作者、合并人、项目 | 与 `issues` 相同：实体空查和定向事实删除未完整处理。 |
| `merge_request_metrics` | `merge_request_id,merged_at,added_lines` 等 MR 指标 | 未修复。MR 更新/Hook 目前只做 `PRECISE`，空查询会遗留旧指标。 |
| `merge_request_assignees` | MR 指派人 | 已修复：按 `merge_request_id` 完整替换。 |
| `merge_request_reviewers` | MR 审核人 | 已修复：按 `merge_request_id` 完整替换。 |

`GitlabFactSourceSqlProvider` 是实际事实 SQL 证据；`GitlabSourceSchemaGuard` 和 `GitlabFactRefreshRequirements` 给出事实构建前置表清单。`resource_label_events` 不在必须源表清单中，因为当前代码会在字段不完整时回退到 `label_links`。

### 全部推荐表与未消费表

当前配置为 `RECOMMENDED`。除上表外，推荐集合还有：

| 表 | 实际关系/删除特征 | 当前业务影响与策略 |
| --- | --- | --- |
| `user_details` | `user_id` 一对一子表，删用户时级联，无标准 `updated_at` | 未进入事实；全量对账保底，未来消费前必须声明作用域。 |
| `members` | `source_type,source_id,user_id` 成员关系，可删除，`source_id` 单列不跨类型唯一 | 未进入事实；Project Hook 现只带 `source_id`，不能直接升级为权威范围，必须带 `source_type`。 |
| `issue_metrics` | `issue_id` 一对一子表，删 Issue 时级联 | 未进入事实；建议随 Issue 父对象纳入完整范围，避免未来继承旧风险。 |
| `ci_pipelines` | 项目/MR 相关实体 | 未进入事实；全量对账和精确实体删除能力即可。 |
| `ci_builds` | 流水线/阶段子记录，有级联删除 | 未进入事实；同上。 |
| `deployments` | `project_id,environment_id` 子记录 | 未进入事实；同上。 |
| `environments` | `project_id,merge_request_id` 子记录 | 未进入事实；未来消费时按项目完整集合定义边界。 |
| `events` | 项目/群组审计记录 | 未进入事实；不假设永久保留，由全量对账清理。 |
| `todos` | 用户/项目/目标关联记录，可级联删除 | 未进入事实；未来消费前定义完整范围。 |

System Hook 还会规划 `merge_trains`、`releases`，但它们不在 `RECOMMENDED` 白名单中，常规配置下会被任务规划器跳过。后续修复应删除无效 Hook 目标，或将其纳入有字段、用途和删除策略的正式来源契约。

### 已确认的代码根因链

1. `GitlabWhitelistService` 选择推荐表，但不为每张表声明删除语义。
2. `SyncRunTablePlanningService` 只为 `AuthoritativeRelationCatalog` 中的关系派生 `AUTHORITATIVE` 任务；未声明表维持普通增量。
3. `PRECISE` 与 `AUTHORITATIVE` 都查询来源范围；前者只 upsert 返回行，空集合不删除 ODS。后者调用 `replaceAuthoritativeScope`，将范围内来源缺失的 active 行写为 `mirror_deleted=true`。
4. 当前目录没有 `notes` 或 `merge_request_metrics`；物理删除后增量没有行，Hook 也只产生 `PRECISE`，因此 ODS 和事实均可陈旧。
5. `FactRefreshImpactScopeService` 对 `notes` 和 `merge_request_metrics` 的目标查询要求子行仍 active。即使未来将它们 tombstone，最后一条子行被删仍会找不到父对象；必须从 `lookup_scope_json` 和 tombstone 身份解析影响范围。
6. `FactBuildService.rebuildIssueFactsByTargets` 与 `rebuildMergeRequestFactsByTargets` 对空来源结果只返回 0，不删除旧事实；`IntegrationTestFactBuildService.rebuildFactsByTargets` 已先删除目标事实，可作为正确参考。
7. 来源读取是原样 `select *`，没有字段转换；因此 `resource_label_events.resource_type/resource_id` 的分支会无范围或走 SQL fallback，不能静默视为成功。

## 已实施的目标版本修复规格

### 统一模型

保留现有 `AUTHORITATIVE` 执行器和 `replaceAuthoritativeScope`，不要为评论、指标、标签或页面各写一套删除逻辑。任何需要从空来源结果清理 ODS 的范围都使用同一个模型：来源查询结果是该非空复合范围的完整当前集合，本地范围中来源缺失的 active 行在同一事务写 tombstone。范围可以只有主键 `id`。

`PRECISE` 只能表示“更新来源明确返回的记录”，不得再用于承诺删除收敛的目标。

### 必须新增或修正的范围

1. `issues` -> `notes`：`(noteable_type='Issue',noteable_id=issues.id)`。
2. `merge_requests` -> `notes`：`(noteable_type='MergeRequest',noteable_id=merge_requests.id)`。
3. `notes` -> `notes`：每条增量评论派生其父对象完整范围，确保评论新增/编辑不依赖父 Issue/MR 更新时间；派生 `AUTHORITATIVE` 任务不得递归派生。
4. `merge_requests` -> `merge_request_metrics`：`merge_request_id=merge_requests.id`；空集合合法。
5. `issues` -> `issue_metrics`：`issue_id=issues.id`；虽暂无事实消费者，也应与父对象保持一致。
6. System Hook 的 `note` 事件直接提交 `notes(noteable_type,noteable_id)` 完整范围，而不是只提交 `notes(id)`。
7. System Hook 的 Issue/MR/用户/项目/流水线实体按主键注册 `AUTHORITATIVE` 范围。来源空结果必须 tombstone 本地实体。
8. 删除不存在的 `resource_type/resource_id` 映射，改为声明式的 `issue_id -> label_links(target_type='Issue',target_id=issue_id)` 和 `merge_request_id -> label_links(target_type='MergeRequest',target_id=merge_request_id)`。`epic_id` 未被当前事实使用，不纳入本轮业务范围。
9. 如处理 `members`，完整范围必须是 `(source_type,source_id)`，绝不能只用 `source_id`。

### 事实删除与影响范围

1. `FactRefreshImpactScopeService` 先解析成功 `AUTHORITATIVE` 任务的 `lookup_scope_json`，从范围得到父 Issue/MR 身份；不能只通过 active 子行反查。
2. 对关系删除，父对象进入同一父镜像运行的 ISSUE/MERGE_REQUEST/INTEGRATION_TEST 事实任务，事实读取新 ODS 后改写标签、评论、人员或指标。
3. 对 tombstone 的 Issue/MR 根实体，影响范围应读取 tombstone 行中的 `project_id,iid`，不能过滤掉后返回空范围。
4. Issue 和 MR 定向事实构建要像集成测试事实一样，在同一发布事务中先删除目标范围的旧事实及从属成员，再写入仍存在的来源结果。空来源结果是“删除旧事实”的有效结果。
5. `projects,users,namespaces,labels,milestones` 等维表变更继续采用安全的全量事实回退；未收到精确事件的物理删除仍由 `FULL_COMPENSATION_SCAN` 兜底。

### 实施约束

- 扩展现有目录的“精确范围是否权威”判断，使其验证固定列和父行带入列；`notes` 必须同时有 `noteable_type,noteable_id`。
- 目录范围、Hook 目标、任务去重和 ODS 写入统一使用规范化复合 `lookup_scope_json`；禁止恢复 `lookup_column/lookup_value`。
- 不以标签名称、工作区名称、项目 ID 或页面路由决定同步行为。现有 label/workspace 专用新鲜度判断不能扩展成主修复，应以表任务全量验证状态和事实完成状态统一表达。
- 不为未消费且可能无限增长的 CI/部署/审计历史表，在每次项目更新时做项目全量集合替换；未来消费前先定义父范围和容量预算。

## 验收与测试矩阵

| 场景 | 必须断言 |
| --- | --- |
| 删除 Issue/MR 标签关联 | ODS tombstone；同类型 ID 不误伤；对应事实和统计/快照更新。 |
| 取消 Issue 指派、MR 指派、MR 审核人 | 最后一条关系可由空集合清除；父事实刷新。 |
| 删除 Issue/MR 评论 | `notes` 复合范围替换；Issue 的备注/修复人/模板字段或 MR 走查字段不保留旧值。 |
| 缺失 MR 指标 | `merge_request_metrics` tombstone；MR 指标字段不保留旧值。 |
| System Hook 删除评论 | Hook 产生复合 notes 范围；空来源仍影响父事实。 |
| System Hook 删除 Issue/MR | 精确实体空查询 tombstone ODS；目标事实和 Issue 客户成员被删除。 |
| 真实 `resource_label_events` schema | 用 `issue_id/merge_request_id` fixture 证明可派生标签范围；不支持时诊断明确失败或回退，不静默成功。 |
| `members` | 若本轮纳入，验证 `(source_type,source_id)` 类型隔离。 |
| `FULL_COMPENSATION_SCAN` | 主表、关系表和未收到 Hook 的物理删除均 tombstone，随后三类事实和快照收敛。 |

必须在隔离 GitLab 16.11 数据源执行真实“创建 -> 初始镜像 -> 源端删除 -> 自动增量或 Hook -> FACT_REFRESH”链路。根实体删除还要核对 `issue_fact_customer_members`、`issue_fact`、`merge_request_fact` 和统计快照无遗留。

## 部署与现场验收

1. 部署代码、迁移和更新后的测试；不得只发布前端或只重建事实。
2. 在内网 GitLab 只读检查 `resource_label_events` 的实际列、各表主键和生命周期字段；版本差异进入来源 profile，不允许临时 SQL 特判。
3. 执行一次成功的 `FULL_COMPENSATION_SCAN`，确认已启用推荐表走完 `SCAN -> RECONCILE`。
4. 等待其自动创建的全量 ISSUE、MERGE_REQUEST、INTEGRATION_TEST 事实刷新及统计/记录快照刷新全部成功。
5. 按 ODS tombstone -> 事实 -> 页面/导出三层，对本次 CC2026R4 Issue、评论、MR 指标和删除实体各抽样验收。
