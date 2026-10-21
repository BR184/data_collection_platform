## 进度与中间物

- 工作单元：客户问题统计页（`customer-issues-customer-statistics`）筛选职责落地——把客户/模块/功能并入共用“常用筛选”区、区分候选加载/失败/无成员并可重试、补首访候选反例回归。仅改平台统计页前端，不改后端字段、不改黄金基线。
- 状态：调查已完成（根因与在途覆盖核对见“证据与根因”）；用户已就四处边界给出裁定；本文件已改写为本工作单元的实施方案，实施进行中。
- 用户裁定（2026-10-13/14）：
  1. **只搬迁不新增字段**：客户/模块/功能继续走控制参数（VALUE/MISSING + 成员行展开），不在后端 `filterFields()`/`factFilterFields()` 增加同维度条件字段；`definition.filters` 与黄金基线快照保持零差异。
  2. **落点为共用快速筛选行**：与 `severityLevel` 等同处一行、同组件、同摘要、同清除；2026-10-14 用户要求对齐全平台规范默认收起快速筛选，不再默认展开。
  3. **仅平台客户问题统计页**：BI 看板客户问题页（`features/bi-dashboard/CustomerIssueDashboardView.vue`）本轮不改。
  4. **不含聚合结果过滤**：缺陷数/修复率等“统计结果”分组本轮不实现。
  5. **候选项文案去除“真实”前缀**：2026-10-14 用户要求移除“真实客户：/真实模块：/真实功能：”前缀，直接展示成员原名，缺失成员保持“未标注客户（成员缺失）”标识。
- 未改后端源码、未改数据库、未改黄金夹具/快照；临时产物放 `.tmp/`，完成后清理。

## 恢复线索

- 首条命令：`git status --short`，随后读取 `docs/progress.md` 与本文件。
- 基点提交 `3a105ddf`（20261010 包基点）；20260930 包清单基点 `de7bff4b`（dirty 标记，须从实际镜像交叉核验）。发布包在 `D:/projects/data_collection_platform_deploy/`。
- 工作树含**持续可读 P3–P6 等独立在途改动**（含新增 `frontend/src/composables/useAcceptedPageView.ts`、`useStatisticBoardData.ts` 整页接受模型，以及 `useStatisticBoardControlParams.ts`/`StatisticBoardView.vue` 的同批修改）。实施必须基于工作树现状叠加，**不得用基点版本覆盖**，不得回退其读取身份或两阶段接受语义。
- 本单元首要文件：`frontend/src/composables/useStatisticBoardControlParams.ts`、`frontend/src/components/StatisticBoardControlBar.vue`、`frontend/src/components/StatisticBoardToolbar.vue`、`frontend/src/components/StatisticBoardView.vue`、`frontend/src/feature-manifest/statistic-board-controls.ts`。
- Java 侧只读核对（不改）：`backend/src/main/java/com/data/collection/platform/service/statistics/CustomerIssueCustomerStatisticsBoardService.java`。

## 目标与边界

- 目标：客户/模块/功能三个成员控制在界面上归属“常用筛选”职责，与里程碑/行维度所在的“分析设置”分离；成员候选的三种非就绪状态（加载中、加载失败、确实无成员）在界面上可区分，失败可重试；首访时成员选择不得因草稿重建而不可用，并有回归锁定。
- 成功标准：
  1. 页面打开后快速筛选行与其他页面一致默认收起，用户展开后客户/模块/功能下拉可见且可用（候选就绪时）。
  2. 成员选择立即生效（不依赖点“查询”），路由参数仍是 `customer/customerKind/module/moduleKind/function/functionKind` 控制参数，语义与现状完全一致。
  3. 候选加载中/加载失败/确实无成员三种状态提示互不混淆；失败态提供重试。
  4. 重置时一并清除成员选择（成员已在该行内）。
  5. 其他统计板与记录页的筛选行为、折叠默认值、请求参数零变化。
- 明确禁止：新增/修改后端接口或筛选字段；改动 `filterFields()`/`factFilterFields()`；改黄金夹具或快照；把成员改成通用条件字段；把成员塞进 `filterGroup`；为兼容保留旧入口或双轨。

## 约束与背景

- 业务规则第 6.1 条定义筛选优先级：单独展示的范围下拉 > 条件筛选 > 快速筛选；高优先级已覆盖的维度，低优先级不得再提供同维度入口。
- 控制参数与业务筛选条件是两个命名空间：`groupBy/customer(*Kind)/module(*Kind)/function(*Kind)/businessDate/sourceInstance` 走独立查询参数，业务条件只序列化进单一 `filterGroup` 参数。二者互不重叠，因此“同一维度只有一个生效状态”在本页天然成立。
- 成员候选来自后端 `control-options`，与主表在 `useAcceptedPageView` 的两阶段接受中**整批准备、原子替换**：`acceptedFrame` 同时含主表、候选与本批 query；准备失败保留原画面。
- 行维度改变聚合结构，不是过滤条件；切换维度必须保留仍有效的成员选择。里程碑由范围选择器（`DataScopeBar`）承接，属“分析设置”。
- 前端 Vue 3 + TypeScript + Vitest；测试沿用 `frontend/src/**/*.test.ts` 现有布局。

## 证据与根因

### 首访候选不可用（原缺陷）

- 20261010 基点 `useStatisticBoardControlParams` 使用 `watch(() => [enabled, scopeSignature], ...)`，getter 每次返回新数组：主表报告 V1 后只要草稿被等值重建，Vue 便视作依赖变化而清空 `boardSourceVersion`，候选 `settleCandidate` 在无主表版本时只能等待，`StatisticBoardControlBar` 在非 ready 时禁用成员选择；再次报告 V1 才恢复。行维度切换后恢复的现场现象与此一致。
- `StatisticBoardView.handleBoardLoaded` 先报告主表版本再回填/替换筛选草稿，未在草稿回填后重新报告，因此既有数据与候选都存在仍可出现首访禁用。
- **在途覆盖核对（2026-10-13）**：持续可读 P5 已删除旧独立候选/主表版本监听链，候选改为从已接受整页读取（`controlOptionsStatus` 只由 `acceptedFrame()?.options` 决定），本次首访缺陷的旧触发机制已消除；但“等价空草稿首访、三控件可选”的完整浏览器验收尚未取得。因此本单元不对该模型再作改动，只补**反例回归**。

### 候选状态不可区分（本次要解决）

- `useStatisticBoardData.loadBoard` 在 `options.scopeReadable === false` 时直接抛错丢弃整批画面：`acceptedFrame` 保持为空 → `controlOptionsStatus` 恒为 `loading`，“加载失败”与“加载中”在界面上无法区分；仅主表顶部 `el-alert` 披露错误。
- `scopeReadable=true` 但候选组为空（该范围确实没有成员）时状态为 `ready`，下拉为空且无任何说明，与“加载失败”表现相似。

### 字段范围与后端能力（只读核对，确认无需补齐）

- 本板 `filterFields()` 只声明里程碑、严重程度、优先级、测试状态、类别、提交人、处理人、开闭状态；里程碑又由范围选择器承接，因此条件组件显示 7 类字段。客户/模块/功能由独立 ControlParams 承担，不在条件白名单；计数/率等聚合结果也未实现结果过滤。
- 条件字段由 `factFilterFields()`（`CustomerIssueCustomerStatisticsBoardService.java:588-625`）注册并经 `StatisticFilterEngine` 求值；8 个声明键均有物理映射。客户/模块/功能**未**注册为条件字段，只由 `ControlParams`（`:1376-1385`）解析为 SQL 谓词（VALUE=成员精确匹配，MISSING=成员缺失），候选由 `control-options` 的 `baseScopeControlOptions` 生成。是故“搬迁”不需要后端改动，也不得凭空增加条件字段（否则同维度双状态、且触发黄金基线差异）。
- 外部依据：[Ant Design 数据列表](https://ant.design/docs/spec/data-list-cn/)区分提交式查询与即时筛选，强调常用功能集中到工具栏；[阿里云 Quick BI 查询控件](https://help.aliyun.com/zh/quick-bi/user-guide/overview-of-the-filter-bar-widget)与[复合查询控件](https://help.aliyun.com/zh/quick-bi/user-guide/configure-a-compound-query-control)区分文本/日期/数值与 AND/OR 复合条件。外部参考只提供设计依据，推荐方案仍须满足本平台范围、缺失成员及读取身份契约。

## 方案与步骤

### A1 控制参数组合式暴露“常用筛选”条目与候选状态

- 在 `useStatisticBoardControlParams` 内把每个成员控制项映射为 `RecordTableFilterField`（key 用成员键、label 用成员 label、`type: 'select'`、`clearable`、options 复用现有令牌候选），并输出 `memberQuickValues`（令牌值）与 `memberSummaryChips`（已生效成员摘要，缺失用页面缺失文案、精确成员用候选原始名）。
- 新增候选“确实无成员”判定：状态为 `ready` 且后端候选组内无任何成员选项时为真。
- 新增成员清除补丁（清空 `customer(*Kind)/module(*Kind)/function(*Kind)`），供“重置”一次性合并下发，避免两次路由写入。
- 成员禁用状态由成员组件按候选状态自行判定，不并入工具栏字段快速筛选的禁用键集合，避免维度别名把禁用态泄漏到同维度字段筛选。
- 不改变 `requestParams`、`setMember`、`setDimension`、`memberSelectionError` 的既有语义与令牌格式。

### A2 `StatisticBoardControlBar` 收敛为行维度控件

- 只保留行维度选择（分析设置），移除成员渲染与成员相关 props/events；成员非法参数与候选状态的展示职责移交 A3。

### A3 新增成员常用筛选组件 + 工具栏扩展

- 新增 `frontend/src/components/StatisticBoardMemberFilters.vue`：内部复用 `RecordTableFilterFields` 渲染成员项，展示“加载中/加载失败(含重试)/确实无成员/非法成员参数”四类状态，向上发出 `filter-change` 与 `retry`。
- `StatisticBoardToolbar` 增三个**可选**扩展（默认值使其他看板行为完全不变）：`quickFiltersDefaultExpanded`（决定展开初值）、`quick-extra` 具名插槽（渲染在快速筛选行内、重置按钮前）、`extraSummaryChips`（并入筛选摘要）。

### A4 `StatisticBoardView` 接线（仅本页生效）

- 成员项仅在 `controlSpec` 存在时并入快速筛选行的 `quick-extra` 插槽；本页 `quickFiltersDefaultExpanded = controlSpec != null`。
- 成员项不进工具栏的字段快速筛选集合（否则会与插槽重复渲染，且会经 `commitQuickFilterValue` 在发出变更后立即 `applyFilters`，用陈旧 route 覆盖成员参数）；工具栏的三个扩展均为**可选且默认无行为变化**，其他看板不受影响。
- 成员变更直接写控制参数路由（立即生效，不触发 `applyFilters`），避免与“查询”路径竞争覆盖。
- 成员摘要 chip 经 `extraSummaryChips` 汇入共用摘要。
- 成员候选状态与重试接入：无画面且加载中→加载中提示；无画面且有错误→失败提示+重试（重试=重新准备整批画面）；画面就绪但候选为空→无成员提示。
- “重置”在既有重置补丁上合并成员清除补丁；行维度与里程碑不参与重置。
- 条件接管（6.1）继续由既有 `useQuickFilterConflictState` 负责字段类快速筛选；成员属控制参数命名空间，不参与、也不可能与之冲突。

### A5 测试与收口

- 组合式单测：成员条目/取值/禁用键/无成员判定/清除补丁；既有令牌与请求参数断言保持通过。
- 组件单测：成员筛选组件四类状态与 `filter-change`/`retry`；工具栏默认展开、`quick-extra` 插槽与 `extraSummaryChips`；`StatisticBoardControlBar` 行维度行为。
- 回归：首访反例——成员候选就绪后，等价路由重载（触发 `onBoardLoaded` 草稿等值重建）不得让成员选择退回不可用。
- 运行前端定向 Vitest 与类型检查；确认其他看板相关既有测试未受影响。

## 决策记录

- 采用“控制参数仍是成员选择唯一权威，界面并入共用快速筛选行”：界面归属改变，请求语义与统计口径不变。
- 否决把客户/模块/功能注册为条件字段：会与成员入口形成同维度双状态（违反第 6.1 条与方案约束）、把 MISSING 与成员行展开语义混入通用条件引擎，且改变 `definition.filters` 触发黄金基线差异。
- 否决把成员项混入 `useQuickFilterConflictState` 的字段集合：条件接管对控制参数不适用；且工具栏 `commitQuickFilterValue` 会在发出变更后立即触发 `applyFilters`，与成员的路由写入形成竞态，可能用陈旧 query 覆盖成员参数。
- 成员保持“即时生效、不需点查询”，与现状一致（降低行为变更面）。
- 不新增后端接口、不改黄金基线（见“接口契约”）。

## 接口契约

- 后端：无新增或修改。`GET /api/statistic-boards/{boardKey}`、`/control-options`、`/status`、`/rule-explanation` 的响应结构不变。
- 前端新增组件 `StatisticBoardMemberFilters`：
  - props：`filters: RecordTableFilterField[]`、`values: Record<string, unknown>`、`status: 'loading'|'ready'|'unavailable'|null`、`message?: string`、`selectionError?: string`、`emptyNotice?: string`、`retryVisible?: boolean`、`retrying?: boolean`。
  - emits：`filterChange: { key: string; value: string | string[] | null }`、`retry: void`。
- `StatisticBoardToolbar` 新增可选 props：`quickFiltersDefaultExpanded?: boolean`（默认 `false`）、`extraSummaryChips?: { id: string; label: string }[]`（默认 `[]`）；新增具名插槽 `quick-extra`（默认空）。
- `useStatisticBoardControlParams` 新增返回：`memberQuickFilters`、`memberQuickValues`、`memberCandidatesEmpty`、`memberSummaryChips`、`clearMemberPatch`；既有返回项语义不变。

## 验收标准

| 输入/操作 | 预期 |
| --- | --- |
| 打开本页（候选就绪） | 客户/模块/功能下拉可见、可用，快速筛选行默认展开；里程碑与行维度仍在“分析设置”行 |
| 选择“客户=客户甲” | 立即按控制参数写路由并重载；统计口径与改造前一致 |
| 选择“未标注模块（成员缺失）” | 走 MISSING 语义，仍与同名真实成员区分 |
| 候选加载中 | 成员下拉禁用并提示“加载中”，不显示为“无成员” |
| 候选加载失败 | 提示失败原因并提供“重试”；点击重试重新准备整批画面 |
| 候选就绪但该范围无成员 | 提示“当前范围没有可选的成员”，与失败态区分 |
| 点击“重置” | 业务条件与成员选择一并清除；行维度、里程碑保持 |
| 首访反例（等价重载/等价草稿重建） | 成员候选保持就绪，成员下拉不退回不可用 |
| 其他统计板与记录页 | 折叠默认值、筛选行为、请求参数零变化 |

## 风险与假设

- 工作树存在大量在途改动，`StatisticBoardView.vue`/`useStatisticBoardControlParams.ts` 同时被其他工作单元修改；实施前必须核对最新差异，只做增量叠加。
- 成员选项标签去除“真实客户：”等多余前缀，直接展示纯净成员名；缺失成员保持“未标注客户（成员缺失）”标识；已同步更新单元测试断言。
- “重置清除成员选择”是本页有意的行为变更（现状重置不清成员）；已限定为仅本页、且仅成员（不含行维度）。
- 无现场截图，界面归属以方案文字与“本页默认展开”裁定为准；若视觉上仍需调整，不改动控制参数与请求契约即可回退。
- 本轮不访问内网、不触发同步、不修改开发/生产数据库；未经用户确认不得触碰黄金基线，本单元预期零快照差异，若出现差异必须停下按门禁纪律处理。
