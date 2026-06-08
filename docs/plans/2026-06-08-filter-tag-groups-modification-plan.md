# 标签组筛选 — 修改方案（基于一期实现 + N1/N2/N3 二期增量）

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.
>
> **Execution environment override (PM directive 2026-06-05):** 不要建立 git worktree 进行隔离实现。本方案的所有任务直接在 `D:\projects\data_collection_platform` 主工作树（当前 `main` 分支）上完成；其余流程（任务分步、每步等待项目经理审批、验证项）保持不变。

**关联文档**

- 主方案：[2026-06-05-filter-tag-groups-implementation-plan.md](./2026-06-05-filter-tag-groups-implementation-plan.md)
- 入口清单：[../filter-tag-group-entry-inventory-20260605.md](../filter-tag-group-entry-inventory-20260605.md)

**目的**

主方案在 2026-06-08 增补了 N1/N2/N3 三条二期需求；与此同时，AI 员工已把一期 Task 6a-fix 的大部分技术债清掉了（折叠区、Flyway seed migration、`sourceInstance` 透传、自动恢复 replace、selectedCount 强制展开、ellipsis、面板内联 warning、review-data fixedFilters 回填）。本文档基于"一期实际代码"和"主方案最新文本"做差距评估，输出二期落地的修改顺序。

---

## 1. 一期已落地、可视为基线的内容

凭 commit `b3ca0969` 之前的状态判定。下列内容**不动**，作为后续二期工作的起点：

- 后端：`tag_group / tag_value / tag_value_mapping` 三表 + `TagGroupService`（启动加载 + 30 min TTL + reload 接口 + Caffeine 风格缓存）。
- 后端：`TagSelectionSqlPredicateService` 现有的 `issue / review_data` 域分支，含 `split_exact_comma / array_exact / like / eq` 四种策略。
- 后端：`IssueFactQueryService` 已替换为逗号边界匹配；`IssueFactRecordRepository.findForFilterOptions` 已透传 `sourceInstance + tagSelections`。
- 后端：评审数据 `loadRecordPage` 已透传 `sourceInstance` 进入 `appendTagSelections`（commit `b3ca0969`）。
- 后端：Flyway `V20260608_01__tag_groups_review_data_seed.sql` 已上线，`/api/tag-groups?domain=review_data` 不再依赖 demo seed。
- 前端：`<TagGroupFilter>` 组件 + 快照集合（最多 3 个 + 30 天 TTL）+ 自动恢复 + 内联 warning + selectedCount 强制展开 + 280px ellipsis。
- 前端：`ReviewDataManagementView.vue` 把 `<StatisticFilterBuilder>` 收进折叠区；标签组挂载 `:default-expanded="true"`；恢复快照同时回填 fixedFilters。
- 前端：`SystemTestIssueSearchView.vue` 标签组挂载（默认折叠依赖 selectedCount 触发）+ snapshot 自动恢复 + fixedFilters 回填。

---

## 2. 一期遗留技术债（与二期解耦但建议顺手修）

不影响二期主体，但放着不修后面会累计成本。

### 2.1 议题查询页轻量化（原 Task 6c 的微调）
**事实**：[SystemTestIssueSearchView.vue](../../frontend/src/views/SystemTestIssueSearchView.vue) 现在标签组面板每组值不截断，列表型表格挤占主屏空间。
**修改**：[TagGroupFilter.vue](../../frontend/src/components/TagGroupFilter.vue) 加 `valuesPerGroupLimit?: number` prop（议题查询页传 8，评审数据管理页不传 = `Infinity`）；每组按 `selected first + sortOrder` 排序，截断后剩余出现"展开更多 (剩余 X 个)"按钮；关键字搜索强制展开全量。
**优先级**：P3（视觉优化，非阻塞 N1/N2/N3）。

### 2.2 综合搜索 / 模块关键词的弱化决策
**事实**：议题查询页的 `comprehensive` searchType 仍作为默认；模块关键词 `module_keyword` 既可走 like 又可走标签组，存在用户路径重复。
**修改**：开一个产品决策小记附在主方案 Open Questions 下（约 30 行内），结论二选一："折叠为快速搜索兜底" 或 "直接删除并保留 issueIid 路径"。
**优先级**：P3，等 N1 通用化推进到议题查询页时一并处理。

### 2.3 自动恢复触发的 `loadRows` 时序
**事实**：`handleTagSnapshotRestored` 用 `patchQuery({...}, 'replace')`，依赖路由 watcher 触发 `loadRows`；如果用户进入页面瞬间网络抖动，可能出现"先用空 query 拉一次记录、再用恢复后的 query 拉一次"的双拉。
**修改**：在自动恢复路径内部 `await loadRows()` 之前阻断首次默认拉取（评估 `useRouteTableState.bindLoader` 是否需要支持 "等待 first restore" 的标志）。
**优先级**：P2，在 N2 落库视图前必须解决，否则视图体验下降。

---

## 3. N1 — 标签组通用化（按数据语义适配，非简单挂载）

主方案 N1 节已写明"五维适配"。下面按维度给出**实施前置**和**逐域接入顺序**。

### 3.1 通用胶水层（仅在第一个新数据域接入前完成一次）

不要把"通用化"做成"抽一个 `useTagGroupFilter` 万能 composable"。一期已有的胶水层是按需做的，不是抽不出来：

- `useReviewDataRouteController` 嵌入了"评审数据特有的 sourceInstance、filterDraft、buildReviewDataRecordQueryParams"。
- `SystemTestIssueSearchView` 的 `buildFixedFilterSnapshotQuery` 字段集合是议题域专属。

**实施步骤**

1. 抽**最小公约数** composable `useTagGroupFilterAdapter(domain, options)`：仅封装"加载标签组 + 维护 tagSelections + storageKey 派生 + auto-restore wiring + active-filter-tag 拼接"。fixedFilters 字段集合作为 `options.fixedFilterKeys: string[]` 注入；不内置任何业务字段名。
2. 每个新数据域 view 仅消费这个 adapter，自带其 fixedFilterKeys、route query 解析。
3. **禁止**在 adapter 里写"如果 domain === 'issue' 则 ...."的分支，所有差异都通过 options 显式传入。

### 3.2 数据域接入顺序（按用户重要性 + 数据复杂度）

每个数据域单独成 Task，每完成一个等 PM 审批。

| 顺序 | 数据域 | view | 难度 | 关键适配点 |
|---|---|---|---|---|
| Task A | `customer_issue` | [CustomerIssueRecordsView.vue](../../frontend/src/views/CustomerIssueRecordsView.vue) | 中 | 复用 `issue_fact`，但 scope = `CUSTOMER_PRODUCT`；标签组应聚焦"客户来源 / 缺陷分类 / 严重程度 / 延期原因"，不引入"测试阶段" |
| Task B | `system_test_illegal` | [SystemTestIllegalRecordsView.vue](../../frontend/src/views/SystemTestIllegalRecordsView.vue) | 中 | 独立"违规原因"组（`illegal_reason`），与 `category` 不重复；只展示 `is_illegal=true` 的记录子集 |
| Task C | `customer_issue_illegal` | [CustomerIssueIllegalRecordsView.vue](../../frontend/src/views/CustomerIssueIllegalRecordsView.vue) | 中 | 与 Task B 同型但 scope=客户域，确认违规原因枚举是否一致 |
| Task D | `code_review_illegal` | [CodeReviewIllegalRecordsView.vue](../../frontend/src/views/CodeReviewIllegalRecordsView.vue) | 高 | 独立 fact 表 / 维度，与议题域几乎无重叠；标签组组成需先用 SQL 跑值分布评估 |
| Task E | 多板对比型 | `SystemTestMultiBoardView.vue` / `CodeReviewMultiBoardView.vue` | 高 | 与"板切换"如何共存需要业务确认；先出产品决策小记，写不出来就不做 |

### 3.3 每个 Task 的 acceptance 模板

PR 描述必须包含五段适配论证（缺一不审批）：

1. **N1.1 数据形态**：列出该域 `groupKey -> 物化在哪张表的哪一列 -> 列形态 -> 选用的 match strategy`。
2. **N1.2 用户场景**：一句话描述用户如何使用该表，决定面板默认形态（数据治理型展开 / 数据查询型折叠 / 诊断型聚焦少量维度）。
3. **N1.3 组成评估**：附 `SELECT col, COUNT(*) ... GROUP BY col ORDER BY 2 DESC` 的输出片段，说明哪些维度入选哪些被排除。
4. **N1.4 已选条件展示**：自检 `activeFilterTags` 在三种典型选择组合下的展示。
5. **N1.5 后端语义**：`TagSelectionSqlPredicateService` 增加该域显式分支；不允许 substring 兜底；未识别 groupKey 直接跳过。

每个域至少 1 个 vitest + 1 个后端单测 + 三链路（列表/导出/筛选项）手测截图。

### 3.4 与 N3 的衔接策略

N3 已修订为"老平台标签分类完整复刻"，并前置到 N1 接入任务之前完成。N1 的 Task A-E 只需要复用 N3 已落地的 issue 域配置，不再假设未来会新增 `toolbox_names` 或做工具箱双写迁移。

- `模块：X` 与 `工具箱：X` 按老平台行为合并到同一个 `module` 标签组。
- `tag_value_mapping.source_field` 必须保留原始前缀（`模块` / `工具箱`），为未来可能的"工具箱独立"保留配置级迁移路径。
- Task A 等复用 `issue_fact` 的页面不需要再标注"待 N3 拆分"，也不需要把 `工具箱：X` 候选排除出主筛选池。

---

## 4. N2 — 持久化探索式筛选落库

**前置**：N1 的 Task A 完成（验证多域 adapter 形态稳定后再做 N2，避免 schema 来回改）。

### 4.0 产品模式修订：当前工作区 + 手动快照

原 N2 用"用户视图持久化"描述，容易把功能做成传统的固定视图管理。根据 2026-06-08 进一步讨论，真实使用场景更接近**持久化探索式筛选**：

- 用户在表格里不断增删标签组、固定字段、排序和分页大小，属于探索过程，不应每次都要求新建/切换视图。
- 用户离开页面再回来时，应恢复上一次探索到的状态，避免每天重新选择。
- 只有当某个筛选组合值得留档或复用时，用户才手动保存为快照。

因此 N2 的最终形态调整为三层：

1. **原生表格**：无用户筛选状态，或仅带系统默认轮次/里程碑，是用户回到基准数据的入口。
2. **当前工作区**：每个用户、每个数据域、每个数据源一份自动保存的筛选状态；用户修改即保存。
3. **快照**：用户手动把当前工作区另存为命名快照，用于留档、恢复和对比。

第一版不做完整"视图管理中心"，也不强制命名每一次筛选状态。历史筛选记录（撤销/重做/恢复历史状态）作为后续增强，不进入本轮 N2 主体。

### 4.1 数据模型（最终落档）

`user_table_view + user_table_view_state` 的固定视图模型调整为 `user_filter_workspace + user_filter_snapshot`。其中 workspace 是自动保存的当前状态，snapshot 是用户手动保存的命名快照。

```sql
-- V20260615_01__user_filter_workspace.sql（占位日期，按实际接入时改）
create table user_filter_workspace (
  id bigserial primary key,
  user_id varchar(64) not null,
  domain varchar(64) not null,
  context_key varchar(128) not null default 'default', -- 如 sourceInstance / 项目域 / 表格上下文
  state_json jsonb not null,             -- { tagSelections, fixedFilters, sortField, sortOrder, pageSize }
  schema_hash varchar(128),              -- 来自 /api/tag-groups 的 schemaHash
  saved_at timestamp not null default current_timestamp,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp,
  unique (user_id, domain, context_key)
);

create table user_filter_snapshot (
  id bigserial primary key,
  user_id varchar(64) not null,
  domain varchar(64) not null,
  context_key varchar(128) not null default 'default',
  snapshot_key varchar(128) not null,
  snapshot_name varchar(128) not null,
  source_workspace_id bigint references user_filter_workspace(id) on delete set null,
  state_json jsonb not null,             -- { tagSelections, fixedFilters, sortField, sortOrder, pageSize }
  schema_hash varchar(128),
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp,
  unique (user_id, domain, context_key, snapshot_key)
);
```

### 4.2 后端

- 新增 `FilterWorkspaceService + FilterWorkspaceController`，路径：
  - `GET/PUT /api/me/filter-workspaces/{domain}/{contextKey}`：加载 / 自动保存当前工作区。
  - `DELETE /api/me/filter-workspaces/{domain}/{contextKey}`：回到原生表格，清空当前工作区状态。
  - `GET/POST /api/me/filter-snapshots`：查询 / 保存命名快照。
  - `POST /api/me/filter-snapshots/{id}/restore`：把快照恢复到当前工作区。
  - `DELETE /api/me/filter-snapshots/{id}`：删除快照。
- 使用现有 `PlatformSessionAuthenticationFilter` 鉴权，`userId` 从 `SecurityContextHolder` 取。
- `state_json` 不做强 schema 校验，但加载 workspace / snapshot 时按当前 `schemaHash` 比对：不一致 = 提示但允许加载，与一期 snapshot 行为同型。
- 自动保存需要防抖，后端接口幂等 upsert，不因用户频繁增删条件产生多条记录。

### 4.3 前端

- 改造 `useTagGroupFilterAdapter`：登录用户优先从 API 加载当前工作区；未登录用户继续用 localStorage 作为兜底。
- 用户修改 `tagSelections + fixedFilters + sortField + sortOrder + pageSize` 后，前端防抖自动保存当前工作区。
- UI 主入口不叫"视图"，改为"当前工作区"。评审数据管理页先落字段型多选筛选条：每个标签组对应一个 `el-select multiple`，横向排列，支持搜索、清空、折叠标签。
- "保存快照"弹小表单：用户必须输入快照名，确认后把当前工作区保存为 snapshot；不再允许匿名时间戳快照作为主路径。
- "恢复快照"使用正式下拉/Popover 选择器，入口文案为"恢复快照"，下拉项展示 `快照名 + 保存时间 + 条件数`；恢复后覆盖当前工作区，快照本身不被修改。
- "清空标签 / 回到原生表格"都必须二次确认。清空标签只清空 `tagSelections`；回到原生表格清空当前工作区并持久化为空状态；已保存快照不受影响。
- 一期 localStorage 快照迁移：检测到本地存在旧快照时，提示"是否迁移为快照"，迁移成功后清掉本地；失败时不清本地。

### 4.4 acceptance

- `mvn test` 后端新增 workspace upsert / clear、snapshot CRUD、snapshot restore、用户隔离单测。
- 前端 vitest 覆盖：未登录走 localStorage 兜底；登录加载当前工作区；修改自动保存；保存快照；恢复快照；schemaMismatch 行为。
- 手测：A 用户登录修改评审数据筛选后刷新页面仍保留；切到 B 用户看不到 A 的工作区；A 在另一浏览器登录后看到自己的当前工作区。
- 手测：保存快照后继续修改当前工作区，快照不变；恢复快照后当前工作区被覆盖。
- 手测：保存快照必须输入名称；恢复快照能明确看到快照名和保存时间；清空标签 / 回到原生表格都会二次确认，取消时不改变当前工作区。

---

## 5. N3 — 老平台标签分类完整复刻（2026-06-08 修订）

> 原 N3 设计（fact 表新增 `toolbox_names` + 双写一周 + 历史回填）已撤销。详见主方案 [N3 节](./2026-06-05-filter-tag-groups-implementation-plan.md) 修订版。

**前置**：N1 至少完成 Task A（验证 `customer_issue` 走 `issue_fact` 链路）。N2 不是前置——本任务不涉及视图持久化。

### 5.1 复刻对象（来自老平台源码）

老平台 `D:\projects\spidergitdata-dev` 的标签分类一共 3 大类：

- **8 种中文冒号前缀标签**（`模块` / `工具箱` / `软件` / `项目` / `状态` / `测试阶段` / `严重程度` / `类别`）。
- **2 种无前缀枚举标签**（紧急程度、延期原因），裸标签命中 `UrgencyEnum` / `DelayEnum` 时识别。
- **测试阶段备路径**：label 包含 `系统测试 / 回归测试 / 集成测试` 关键字时落到 `测试阶段` 字段。

老平台行为细节：
- `工具箱：X` 与 `模块：X` 在业务展示侧**直接合并**到 `模块` 字段（`getCombinedLabelValue` 用 `&` 拼接或择一）。
- 不识别的 label 被丢弃，不进入业务字段。
- 不支持英文冒号（新平台一期增强为兼容，作为容错行为标注，不在复刻范畴内）。

### 5.2 落地形态（不动 fact 表）

只改 `tag_group / tag_value / tag_value_mapping` 三张配置表 + `IssueFactNormalizationRules` 解析层。**不动 fact 表 schema，不双写，不回填**。

#### 5.2.1 标签组配置（议题域）

新增 / 调整 7 个标签组，全部 `domain='issue'`：

| group_key | label | match_strategy | 老平台对应字段 |
|---|---|---|---|
| `module` | 模块 | `split_exact_comma` over `module_names` | 模块（合并工具箱） |
| `software` | 软件 | `eq` over 新增字段 / 现有字段 | 软件 |
| `project_label` | 项目（标签维度，非 GitLab project） | `eq` | 项目 |
| `status` | 议题状态 | `eq` over `issue_state` | 状态 |
| `phase` | 测试阶段 | `eq` over `phase_filter_value` | 测试阶段 |
| `severity` | 严重程度 | `eq` over `severity_level` | 严重程度 |
| `category` | 类别 | `eq` over `category` | 类别 |
| `urgency` | 紧急程度 | `eq` over 现有字段 | 紧急程度（裸标签） |
| `delay_cause` | 延期原因 | `eq` over `delay_cause` | 延期原因（裸标签） |

注：`software` 和 `project_label` 当前 `issue_fact` 是否有对应物化列需要**先用 SQL 跑值分布评估**——如缺列，本任务先在 mapping 层用 `legacy_label_prefix` 直接命中 `label_names` 字符串（参考 N1.1 的子表关联类策略），不为这两个组改 fact 表。

#### 5.2.2 `tag_value_mapping.source_field` 字段语义收口

为支持"未来工具箱独立"等可逆调整，`source_field` 字段必须严格存原始前缀字符串。新增四类 `source_type`：

| source_type | source_field | 用途 |
|---|---|---|
| `legacy_label_prefix` | `模块` / `工具箱` / `软件` / `项目` / `状态` / `测试阶段` / `严重程度` / `类别` | 中文前缀标签 |
| `legacy_label_full` | `null` | 无前缀枚举（紧急程度、延期原因） |
| `legacy_label_keyword` | `null` | 测试阶段备路径（关键字识别） |
| `normalized_field` | `module_name` / `severity_level` / 等 | 评审数据等结构化字段 |

`module` 组的 mapping 同时收纳 `source_field='模块'` 和 `source_field='工具箱'` 两类条目，**都指向相同的 `tag_value`**（如"草图"），从而复刻老平台合并语义。

#### 5.2.3 议题域 Flyway seed migration

新增 `V<DATE>_01__tag_groups_issue_legacy_seed.sql`：

- 幂等 upsert 上述 7-9 个标签组。
- 用 SQL 直接从 `issue_fact.label_names` 派生当前已存在的标签值（参考一期 [seed-local-tag-groups-demo.sql](../../scripts/seed-local-tag-groups-demo.sql) 的写法），按 `模块：X` / `工具箱：X` 等前缀拆分，去重后写入 `tag_value` 和 `tag_value_mapping`。
- 跳过 `未设定...` 占位值，复刻老平台 `DropDownService` 行为。

### 5.3 解析层对齐（`IssueFactNormalizationRules`）

新平台 `IssueFactNormalizationRules.normalizeModuleNames` 已经存在，但只覆盖模块前缀。本任务把它扩展为完整复刻：

- 新增 `parseLegacyLabelMap(labels) -> Map<String prefix, List<String> values>`，行为对齐老平台 `parseLabelMapByList`。
- 处理顺序：① 中文冒号前缀拆分 → ② 关键字识别（系统测试/回归测试/集成测试）→ ③ 枚举命中（紧急程度、延期原因）→ ④ 余下丢弃。
- `工具箱：X` 与 `模块：X` 都进入 `Map.get("模块")` 列表（合并行为，与老平台 `getCombinedLabelValue` 一致）。
- 单测必须断言：每条解析路径的输入输出与老平台预期完全一致（参考老平台 `ParseDocumentServiceImpl` 单测如有）。

### 5.4 后端查询

`TagSelectionSqlPredicateService` 不需要新增数据域，仍在 `issue` 域内。需要为新增的几个组添加 `eqColumn` 映射（`urgency`、`delay_cause`、`software`、`project_label`、`category` 等），不允许 substring 兜底。

### 5.5 前端

`<TagGroupFilter>` 自然支持，无需改组件本身。议题查询页 [SystemTestIssueSearchView.vue](../../frontend/src/views/SystemTestIssueSearchView.vue) 加载 `domain=issue` 后会拿到新的标签组配置，UI 自动适配。

### 5.6 acceptance

- `mvn test` 覆盖：
  - `IssueFactNormalizationRules.parseLegacyLabelMap` 对 8 类前缀 + 2 类枚举 + 关键字三套路径的输入输出与老平台对齐。
  - `工具箱：草图` 和 `模块：草图` 都 → `Map.get("模块")` 列表，合并语义。
  - 不识别的 label 被丢弃，不出现在任何业务字段中。
- `/api/tag-groups?domain=issue` 返回的标签组配置与老平台 `LabelName` 枚举内容对齐：
  - 7-9 个组（视 `software` / `project_label` 评估结果而定）。
  - 每个组的中文 label、selection_mode 与老平台 `LabelName` 默认值对齐。
- 用真实生产数据回归对比：选中 "模块=草图" 标签后查询结果与老平台一致（行数 + IID 列表 diff 为零）。
- `tag_value_mapping.source_field` 字段所有 issue 域条目都严格使用中文前缀字符串。

### 5.7 工作量评估

约 1-2 天（vs 原 N3 的 5-7 天）：

- Day 1：`IssueFactNormalizationRules` 扩展 + 单测对齐老平台 + Flyway seed migration。
- Day 2：`TagSelectionSqlPredicateService` 新增 `eqColumn` 映射 + 后端单测 + 真实数据回归对比。

不包含：
- 双写期、历史回填、监控告警（已撤销）。
- "工具箱独立"未来若需要，是另一个独立 Task，预计半天（仅改 mapping）。

---

## 6. 推荐执行顺序（2026-06-08 修订）

按依赖关系排，每完成一个 Task 等 PM 审批：

1. **2.3 自动恢复时序修复**（P2，扫尾一期）
2. **N3 老平台标签分类复刻**（1-2 天，先把数据准确性做好；不依赖 adapter，直接改议题域 seed + 解析层）
3. **3.1 通用 adapter 抽取**（N1 前置，仅做胶水，不改业务页）
4. **3.2 Task A — `customer_issue` 接入**（基于 N3 复刻好的 issue 域配置直接复用）
5. **N2 持久化探索式筛选**（4.1-4.4，当前工作区自动保存 + 手动快照 + 一次性迁移）
6. **3.2 Task B — `system_test_illegal` 接入**
7. **3.2 Task C — `customer_issue_illegal` 接入**
8. **3.2 Task D — `code_review_illegal` 接入**
9. **2.1 议题查询页轻量化** + **2.2 综合搜索决策**（与 N1 已完成的页面一并打磨）
10. **3.2 Task E — 多板对比型**（看业务确认结果，可能不做）

**变更说明**：

- **N3 提前到第 2 步**（原排在第 7 步）。理由：复刻老平台分类是数据准确性的基础，比通用化更先做完，N1 后续 Task A 等也可直接基于 N3 已完成的 issue 域配置。
- **N3 不再有双写期**，不占用 7 天日历时间。
- 总周期估计从 6-8 周降到 5-6 周。
- 原"Task A 需在 PR 描述里标注'本期暂不区分工具箱：X'" 的解耦策略**作废**，因为 N3 先于 Task A 完成，Task A 直接复用 N3 已落地的 issue 域配置即可。

---

## 7. 风险与决策点（需要 PM 拍板）

| 风险点 | 影响 | 决策建议 |
|---|---|---|
| ~~N3 双写期运维成本~~ | ~~高~~ | **已撤销**（N3 改为复刻老平台合并语义，无双写期） |
| N2 落库后用户清空筛选行为 | 中 | "回到原生表格"=清空当前工作区并持久化为空状态；不删除已保存快照 |
| Task E 多板对比是否做 | 中 | 先做 Task A-D，看业务反馈是否需要 |
| 一期 localStorage 快照迁移失败 | 低 | 失败时不清本地，让用户重试，避免数据丢失 |
| N1 通用 adapter 后续被业务页"反向定制" | 中 | 通过 lint 规则禁止 view 内部直接读写 `tag-groups:*` localStorage |
| ~~N3 历史数据回填出错~~ | ~~高~~ | **已撤销**（N3 不涉及历史数据迁移） |
| N3 与老平台行为对齐校验 | 中 | 真实生产数据回归对比时，需 DBA 配合导出老平台 / 新平台双向 IID 列表做 diff |
| 业务方未来要求"工具箱独立" | 低 | 已设计为纯配置变更（改 mapping），半天到一天即可，无 schema 风险 |

---

## 8. 不做（明确范围）

- 不做"标签组管理后台 UI"——管理员仍通过 SQL / Flyway 维护，避免 N1/N2 期间引入新交互复杂度。
- 不做"公共快照 / 团队快照"——N2 仅做个人工作区和个人快照，团队级共享留到三期。
- 不做"筛选历史撤销/重做"——历史记录是探索式筛选的自然增强，但本轮只做当前工作区和手动快照。
- 不做"标签值层级嵌套"——`tag_value` 平铺，不做父子。
- 不做"列显隐入工作区"——N2 状态仅含筛选 + 排序 + 分页，列配置留到三期。

---

## 9. 与一期方案文档的关系

本修改方案是对 [2026-06-05-filter-tag-groups-implementation-plan.md](./2026-06-05-filter-tag-groups-implementation-plan.md) 的二期落地补充，不替换原文。原文继续保留作为 N1/N2/N3 的需求源；本文只承担"基于现状代码 + 需求增量，怎么落地"的执行职责。

落地过程中如果发现原文有需要修订的口径（例如 N1 的"五维适配"需要细化），优先 patch 原文而不是在本文增量描述。

---

## 10. 2026-06-08 PM 反馈确认稿（并入主体前）

> 本节记录 2026-06-08 最新产品口径。与前文 §1-§9 冲突时，以本节为准；后续执行前应把本节确认结果反向合并到主体章节。

### 10.1 N3 完成后必须重新校核老平台规则（细节复查）

**事实根据**：N3 已经把"复刻老平台标签分类"作为目标，但目前代码的部分规则是新平台自己叠加的，未必与老平台一一对齐。已观察到的差异有：

- [IssueLabelRules.java:47](../../backend/src/main/java/com/data/collection/platform/service/IssueLabelRules.java#L47)：当前正则 `Pattern.compile("^(?:模块|module|工具箱)\\s*[:：-]\\s*(.+)$", CASE_INSENSITIVE)`，把 `模块 / module / 工具箱` 三种来源都通过同一个 capture group 收，捕获后写进同一个 `module_names`。这等价于老平台的合并行为，但**英文 `module` 是新平台增强**（老平台正则只接受中文 `模块` 和 `工具箱`）。
- [IssueLabelRules.normalizeModuleNames](../../backend/src/main/java/com/data/collection/platform/service/IssueLabelRules.java#L126)：还做了 `NON_MODULE_TOKENS` 排除、reasonCategory / severity / delay / functionLabel / testingPhase 反向排除等"防误进"逻辑。这些**老平台没有**——老平台靠 `parseLabelMapByList` 逐条按规则归位，未命中的直接丢弃，不会反向"防误进"。
- 当前 `IssueFactNormalizationRules` 还没有 N3 设计的 `parseLegacyLabelMap` 入口（按前缀拆分 → 关键字识别 → 枚举命中 → 余下丢弃的统一入口），目前是按维度分散在各 `normalizeXxx` 方法里调用 `IssueLabelRules` 的小工具。

**修订动作**：N3 落地时必须做两件配套事，避免"复刻"流于形式：

1. 写一份**老平台 vs 新平台标签解析行为差异清单**（建议放在 `docs/plans/2026-06-08-legacy-label-parity-diff.md` 或本文档增补附录）。每条差异列三栏：老平台行为 / 新平台当前行为 / 是否要改（保留增强 / 回退 / 配置项暴露）。
2. N3 的 `parseLegacyLabelMap` 单测必须**逐条引用老平台 `parseLabelMapByList` 的预期输出**，对每条差异都要有显式断言，不能写成"输出非空即可"。

**确认结论**：

- 英文 `module:` 增强**保留**，但必须标注为新平台兼容行为，不计入"老平台复刻"验收口径。
- 反向排除逻辑**保留**，作为新平台防污染增强，避免严重程度、延期原因、测试阶段、功能标签等误归入模块。
- 模块裸标签兜底**关闭 / 回退**：不再把裸 `草图`、`曲面` 这类标签直接归入模块。老平台支持的裸枚举（紧急程度、延期原因）和测试阶段关键字备路径继续保留。
- N3 差异清单中每条规则都要标注 `legacy_parity` / `compatibility_enhancement` / `pollution_guard` 三类之一，避免把增强行为误写成老平台原有行为。

### 10.2 标签组 UI 重做 — 字段型多选筛选条优先

**核心确认**：当前标签云 / 标签按钮式面板不作为评审数据管理页的最终形态。评审数据管理页先替换为字段型多选筛选条，样式参考议题查询页"高级筛选"的多个字段并列展示：

```text
[模块 v] [评审类型 v] [评审负责人 v] [问题状态 v] [问题类别 v] [评审类别 v] [保存快照] [恢复快照] [清空标签]
```

实现建议：

- 新增 `TagGroupFilterBar.vue`，不要把现有 `TagGroupFilter.vue` 强行改成两种形态混用。
- 每个标签组渲染为一个 `el-select multiple filterable clearable collapse-tags collapse-tags-tooltip`。
- 每个下拉对应一个 `groupKey`，选中值写回 `tagSelections[{ groupKey, valueKeys }]`。
- 清空某个下拉只清空对应组；页面"清空标签"清空全部 `tagSelections`，必须二次确认。
- 已选条件区域继续复用 `buildTagGroupActiveFilterTags`，关闭 chip 时清空整组。
- 搜索能力转移为每个多选下拉内置搜索，不再需要顶部全局"搜索标签值"输入框。
- 先在 `ReviewDataManagementView.vue` 接入该筛选条，替换当前标签云式 `TagGroupFilter`；议题查询页暂不切换，等 N1 adapter 和固定字段双向同步完成后再处理。

评审数据管理页这样做符合标签组定义：标签组的 `groupKey` 本质就是字段/维度，`valueKeys` 本质就是该字段下的多选值。老平台评审数据管理即便历史 UI 是单选，新平台也可以升级为多选，因为表格筛选的真实语义是 `字段 in [A, B, C]`。

### 10.2A 高级条件改为"指标与例外条件"

**问题判断**：评审数据管理页的高级条件不应再作为通用筛选入口。标签组筛选条已经覆盖了用户日常使用的业务维度筛选，例如模块、评审类型、评审负责人、问题状态、问题类别、评审类别。继续把这些字段放在高级条件里，会形成两套入口表达同一件事，且 `StatisticFilterBuilder` 的全局 `AND/OR` 模型不适合表达"同组 OR、组间 AND"的标签组语义。

**当前组合语义**：

- 固定字段、关键词、`filterGroup`、`tagSelections` 会一起进入评审列表和导出请求。
- 后端 SQL 路径中，`ReviewDataRecordReadRepository` 先拼固定字段和关键词，再 `appendFilterGroup`，最后 `appendTagSelections`；整体是 `AND` 关系。
- `filterGroup` 内部由用户选择全局 `AND / OR`；`tagSelections` 内部是同组多值 `OR`、不同组 `AND`。
- 当前筛选项接口只接收 `tagSelections + sourceInstance`，不接收 `filterGroup`，因此高级条件不会影响标签组选项收敛；这是一个体验差异点。

**确认结论**：高级条件保留，但从"通用筛选入口"改为 **"指标与例外条件"**。它只承担标签组不适合表达的数值、时间、空值、反向和文本例外条件。

**字段收口**：

- 从评审数据管理页高级条件中移除已被标签组覆盖的枚举/业务维度：`moduleName`、`reviewType`、`reviewOwner`、`problemStatus`，以及后续进入标签组的 `problemCategory`、`reviewCategory`、`reviewExpert`。
- 保留指标和例外字段：`title`、`reviewScalePages`、`problemCount`、`problemDensity`、`reviewEfficiency`、`reviewRate`、`independentReviewWorkload`、`independentReviewProblemCount`、`meetingReviewWorkload`、`meetingReviewProblemCount`、`notReachStandardReason`、`createdAt`、`reviewDate`。
- UI 文案由"高级条件"改为"指标与例外条件"或"更多指标条件"；默认折叠，放在标签组筛选条下方。

**实现注意**：

- `filterGroup` 仍需进入列表和导出请求，保证复杂指标过滤可用。
- 后端 fallback 路径目前主要用于搜索索引缺失或无法 SQL 下推的表达式；如果未来保留任何不能下推的高级条件，必须补测试确认 `tagSelections` 在 fallback 路径中不丢失。当前优先选择只保留可下推字段，降低该风险。

### 10.2B 旧标签云面板的保留范围

PM 对当前面板形态有 4 条具体不满意，逐条记录：

#### 10.2B.1 收起规则改为"任意可收起 + 默认收起"

**当前代码**（[TagGroupFilter.vue:116-122](../../frontend/src/components/TagGroupFilter.vue#L116-L122) + L198-L202）：
```js
function toggleExpanded() {
  if (selectedCount.value > 0) {
    expanded.value = true;            // 选中后强制展开，且无法手动收起
    return;
  }
  expanded.value = !expanded.value;
}
watch(selectedCount, (count) => {
  if (count > 0) expanded.value = true; // 一旦选了任何标签，自动展开
});
```

**PM 意见**：太绝对。旧面板如果继续用于其它页面，应当**默认收起**（不论是否有已选条件），用户可以**任意时刻收起**。已选条件通过"小标识"在头部展示，不靠强制展开来"提醒用户"。

**改法**：
- 删除 `selectedCount > 0 强制展开` 的拦截。
- 默认 `expanded = false`（覆盖 `defaultExpanded` prop，或把 `defaultExpanded` 默认值改为 `false`，并让 `ReviewDataManagementView.vue` 不再传 `:default-expanded="true"`）。
- 头部"小标识"的实现见 §10.2B.2。

#### 10.2B.2 头部展示当前工作区 + 已选条件计数 + 当前结果数

**PM 描述**：折叠状态下，旧标签组面板头部应当能让用户瞄一眼就知道"我现在处在哪个工作区、筛了几个标签、当前看到多少条数据"。形态类似一个简短的标识区：

```
[标签组]  当前工作区：已自动保存   当前 1234 条   1 个已选   [展开]
```

**实现要点**：
- "当前工作区：已自动保存"来自 N2 当前工作区方案。N2 还没落地之前，先用 localStorage `activeSnapshotId` 对应的 `savedAt` 时间戳作为 placeholder（"上次保存：2026-06-07 14:30"），避免空白。
- "当前 1234 条" 来自外部 prop（`<TagGroupFilter :current-total="1234">` 传入），由业务页负责传，组件内不再 fetch。
- "1 个已选" 用现有 `selectedCount` 即可。
- 当 N2 落地后，"当前工作区"标识与保存/恢复快照动作形成闭环（详见 §10.4）。

#### 10.2B.3 停用标签开关直接删除

**当前代码**（[TagGroupFilter.vue:242](../../frontend/src/components/TagGroupFilter.vue#L242)）：
```html
<el-checkbox v-model="showDisabledValues">显示停用标签</el-checkbox>
```

**PM 意见**：本期不考虑停用标签，**直接删除**该 checkbox 和相关逻辑（`isDisabledTagValue` / `showDisabledValues` ref）。`tag_value.disabled` 字段可保留，但运行时统一过滤，不在 UI 暴露。

#### 10.2B.4 搜索标签功能保留，但改名

**当前代码**（[TagGroupFilter.vue:240](../../frontend/src/components/TagGroupFilter.vue#L240)）：
```html
<el-input ... placeholder="搜索标签值" />
```

**PM 意见**：保留搜索能力，但 placeholder 不要叫"搜索标签值"——它实际是"在已展示的标签组里快速过滤"。

**确认结论**：如果旧标签云面板仍保留全局搜索输入，placeholder 使用 **"快速查找标签"**。评审数据管理页的新字段型筛选条不再需要该全局搜索，改用每个多选下拉的内置搜索。

### 10.3 标签分类太"评审数据"化，要回归通用

**事实根据**：[V20260608_01__tag_groups_review_data_seed.sql](../../backend/src/main/resources/db/migration/V20260608_01__tag_groups_review_data_seed.sql) 的标签组定义（评审模块、评审类型、评审负责人、问题状态、问题类别、评审类别）全部是评审数据域专属的、带"评审"前缀的命名。它们对应到 N1 通用化推广后的客户问题、议题查询、非法记录等其他业务表是无效的——你不能给"客户问题列表"展示"评审类别"。

**PM 意见**：标签分类要**写得宽泛一点**，让多个数据域可以共用。参考老平台 [`LabelName.java`](file:///D:/projects/spidergitdata-dev/src/main/java/com/huayun/entity/LabelName.java) 的 8 个枚举（模块 / 工具箱 / 软件 / 项目 / 状态 / 测试阶段 / 严重程度 / 类别）+ 紧急程度 + 延期原因——这是老平台跨页面通用的核心维度。

**修订动作**：

- 议题域标签组 seed（N3 §5.2.1）按主方案 N3 修订版的 7-9 个组定义，命名简洁（`module / status / phase / severity / category / urgency / delay_cause / software / project_label`），label 用"模块 / 状态 / 测试阶段 / 严重程度 / 类别 / 紧急程度 / 延期原因 / 软件 / 项目"，不加业务前缀。
- 评审数据域当前 seed 里的"评审模块"在 UI label 上重命名为 **"模块"**。`domain=review_data` 已经提供语义隔离，页面上下文也明确是评审数据，继续显示"评审模块"会显得重复。
- 评审数据域的业务专属维度可以保留"评审类型 / 评审负责人 / 评审类别"等名称，因为这些不是跨域基础维度。
- 通用维度（如模块、严重程度）应当与议题域共用 `groupKey`，让后续 N1 抽 adapter 时同一个 groupKey 在不同 domain 下表现一致。
- N1 接入新数据域时，标签组组成参考老平台 `LabelName` + 紧急程度 + 延期原因的范围作为"基础组"，业务专属维度在此之上叠加。

### 10.4 持久化（N2）的 UI 形态：当前工作区 + 手动快照

**当前代码**（[TagGroupFilter.vue:244-265](../../frontend/src/components/TagGroupFilter.vue#L244-L265)）：

```html
<el-button @click="saveSnapshot">保存固定快照</el-button>
<el-dropdown @command="(id) => restoreSnapshot(String(id), 'manual')">
  <el-button @click="restoreSnapshot()">恢复快照</el-button>
  <template #dropdown>
    <el-dropdown-menu>
      <el-dropdown-item v-for="snapshot in snapshotOptions" :command="snapshot.id">
        {{ snapshot.savedAt ? snapshot.savedAt.slice(0, 19).replace('T', ' ') : '固定快照' }}
      </el-dropdown-item>
    </el-dropdown-menu>
  </template>
</el-dropdown>
```

**确认结论**：

1. 不做传统"固定视图"优先模式，改为**当前工作区自动持久化 + 手动快照**。
2. 当前工作区不要求命名，用户修改筛选即自动保存；关闭页面再回来恢复上次状态。
3. "保存快照"按钮弹小表单 dialog：用户必须输入快照名后才能保存当前工作区，不再允许匿名时间戳快照作为主路径。
4. "恢复快照"不能用主按钮点击即恢复默认快照的形态，必须先打开一个正式的选择器；下拉 / Popover 项展示 `快照名 + 保存时间 + 条件数`，恢复后覆盖当前工作区，但不修改快照本身。
5. "清空标签"只清空 `tagSelections`，必须二次确认；"回到原生表格"清空当前工作区并持久化为空状态，也必须二次确认；已保存快照不受影响。
6. 历史筛选记录、撤销/重做是后续增强，不进入本轮 N2。

**问题调研（基于当前一期代码）**：

- `TagGroupFilterBar.vue` 当前 `saveSnapshot()` 直接调用 `savePinnedTagGroupSnapshot()`，没有命名步骤。结果是快照列表只能展示保存时间或兜底名，用户无法区分"上周复盘用"、"高风险模块"、"负责人视角"等真实场景。
- `TagGroupFilter.vue` 旧面板按钮文案仍是"保存固定快照"，与 N2 的"当前工作区 + 手动快照"概念不一致，容易让用户误以为保存后会把当前表格固定成不可变视图。
- 新旧组件的"恢复快照"按钮都有 `@click="restoreSnapshot(undefined, 'manual')"`，用户点击主按钮会直接恢复 active snapshot；旁边又有 dropdown 菜单。这种 split button 形态没有明确提示"即将恢复哪一个"，容易误导。
- `clearSelections()` 当前直接清空 `tagSelections` 并触发查询，没有确认。多选条件一多时，清空标签属于高代价操作，尤其 N2 当前工作区自动保存后，误清空会立刻变成持久状态。

**交互规则**：

- 保存快照：
  - 点击"保存快照"打开 dialog / popover form。
  - 快照名必填，建议限制 2-30 个字符；默认聚焦输入框；名称为空时确认按钮 disabled。
  - 保存内容包含当前工作区的 `tagSelections + fixedFilters + filterGroup + sortField + sortOrder + pageSize`。如果本期还没接入全部字段，UI 文案必须明确"保存当前标签筛选"而不是"保存当前视图"。
  - 保存成功后 toast 显示 `已保存快照：{name}`，并刷新恢复选择器。
- 恢复快照：
  - 入口是一个正式选择器，按钮只负责打开选择器，不直接恢复。
  - 列表项主标题显示快照名；副信息显示保存时间、条件数、schema 状态，例如 `2026-06-08 14:30 · 6 个条件`。
  - 点击某个快照后再恢复；若会覆盖当前工作区且当前有未保存变化，提示"恢复后将覆盖当前工作区"。
  - schema mismatch 或失效条件继续允许恢复，但必须在选择器项或恢复后提示中明确展示。
- 清空：
  - "清空标签"确认文案区分范围：只清空标签组筛选，不清空关键词、指标与例外条件、排序。
  - "回到原生表格"确认文案区分范围：清空当前工作区内所有筛选和排序偏好，但不删除已保存快照。
  - 用户取消确认时，不修改 `tagSelections`、工作区或 URL。

**修订动作**：

- §4 已按该口径改为 `user_filter_workspace + user_filter_snapshot`。
- 一期 localStorage 快照迁移时，迁移目标改为"快照"，不是"我的视图"。
- UI 文案统一使用"当前工作区 / 保存快照 / 恢复快照 / 回到原生表格"，不再使用"固定视图"作为主概念。
- 评审数据管理页当前 `TagGroupFilterBar.vue` 只作为字段型筛选条的第一步展示，下一步必须按上述交互规则重做保存 / 恢复 / 清空动作。

### 10.5 默认值与轮次/里程碑切换器双向同步（关键交互）

**事实根据**：议题查询页 [SystemTestIssueSearchView.vue](../../frontend/src/views/SystemTestIssueSearchView.vue) 已有的 `primaryFilters` 包含里程碑、测试阶段下拉（line 123, 201, 255），切换它们会通过 `patchQuery` 写回 URL。但当前标签组 UI 与这些下拉**互不感知**——

- 用户在固定字段筛选里选了"里程碑：M3"，标签组面板里"里程碑"组的"M3"**不会高亮**。
- 用户在标签组里选了"里程碑：M3"，固定字段下拉**不会自动切到 M3**。
- 进入页面时，老平台默认会按当前轮次/里程碑展示数据，新平台标签组若不预选对应值，与老平台行为不一致。

**PM 意见**：标签组与固定字段的轮次/里程碑（以及任何在两边都出现的维度）必须**双向同步**：

- 进入页面时：根据 URL query / 当前默认轮次 / 当前里程碑，**双向预选**——固定字段下拉显示对应值，标签组面板对应组的对应值高亮为已选。
- 用户在固定字段下拉切换里程碑：标签组面板的"里程碑"组立即更新已选值。
- 用户在标签组面板切换里程碑：固定字段下拉立即更新值。
- 涉及的维度至少包含：里程碑、测试阶段、轮次、严重程度、状态、缺陷分类。具体清单按 §10.3 修订后的"基础组"清单决定。

**修订动作**：

- 在 N1 通用 adapter（`useTagGroupFilterAdapter`）层增加"维度互通映射"：声明哪些 `groupKey` 对应固定字段的哪个 query key（如 `milestone -> milestoneTitle`、`phase -> testingPhase`）。
- adapter 内部维护单一真实来源 = URL query。固定字段和标签组都把"已选值"映射回 query；任何一边切换都先写 query，另一边自然从 query 派生。
- 进入页面时若 URL 没有相关 query，从"当前轮次/里程碑"等系统默认值生成 query 后再渲染（老平台行为对齐）。
- 单测必须覆盖：`fixedFilter -> tagGroup`、`tagGroup -> fixedFilter`、`URL 直接修改 -> 双方同步` 三种路径。

已确认：

- "当前轮次/里程碑"来源优先级为：`URL query` > `当前工作区` > `后端全局默认` > `后端按业务时间窗口兜底推断`。
- localStorage 不作为系统默认，只作为未登录或 N2 前的过渡兜底。
- 用户清空固定字段下拉（如"全部里程碑"）时，标签组对应组也必须清空。固定字段和标签组表达同一个维度时，状态只能有一份。

### 10.6 已知细节复查清单（开工前要逐条扫一遍）

把 PM 的"细节复查"明确为待办列表，避免落档时漏：

- [x] [IssueLabelRules.java:47](../../backend/src/main/java/com/data/collection/platform/service/IssueLabelRules.java#L47) 模块正则的 `module` 英文匹配保留，但标注为兼容增强。
- [x] `NON_MODULE_TOKENS` 反向排除、`isKnownReasonCategory` / `isKnownSeverityAlias` / `isKnownPriorityAlias` / `isKnownDelayReason` / `isKnownFunctionLabel` / `isTestingPhase` 反向排除保留，标注为防污染增强。
- [x] `extractModuleName` 中 `endsWith("模块")` 的兜底（无前缀但末尾是"模块"也归入模块）回退/关闭——老平台无此行为。
- [x] `BARE_MODULE_LABELS` / `normalizeBareModuleName` 的裸模块映射回退/关闭——老平台不识别裸标签作为模块。
- [ ] `tag_value_mapping.source_field` 现有数据是否已经使用中文前缀（`模块` / `工具箱`）？还是仍混用 `module_name` / `legacy_label` 等英文命名？需要 SQL 抽查。
- [ ] N3 落地时，`/api/tag-groups?domain=issue` 的 schemaHash 计算稳定性——增删 group / value 会变 hash，会触发已保存的 localStorage 快照 `schemaMismatch` warning，需要在迁移说明里告知用户。

每条都属于"复刻完整性"问题，N3 开工前 PM 要逐条决策。

---

## 11. 修订状态与下一步

§10 已按本轮讨论收口，仍需在开工前反向合并到 §1-§9：

- §10.1：英文 `module:` 和反向排除保留为增强；裸模块兜底回退。
- §10.2：评审数据管理页先用字段型多选筛选条替换标签云面板；旧面板仅作为其它页面过渡形态。
- §10.3：评审域 UI label 中"评审模块"改为"模块"。
- §10.4：N2 改为当前工作区自动持久化 + 手动快照。
- §10.5：默认轮次/里程碑来源优先级已确认；清空固定字段同步清空标签组对应组。
- §10.6：仅剩 `source_field` SQL 抽查和 schemaHash 迁移提示需要开工时验证。

**接下来流程**：

1. 把 §10 的确认结果反向 patch 进 §1-§9 对应小节（N3 复刻细节、N1 adapter 互通映射、N2 工作区/快照模型、评审数据管理 UI）。
2. 执行评审数据管理页 UI 替换：新增字段型多选 `TagGroupFilterBar`，替换当前标签云面板。
3. 再按 §6 修订后的执行顺序派工。

§10 在确认完成后会被合并删除，不在最终方案里独立保留。
