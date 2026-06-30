<!-- DOC_STATUS_START -->
> 文档状态：当前取证审查稿 / 待审查
> 说明：根据 2026-06-30 用户要求，直接从老平台源码提取指定模块入口、前端默认值和后端查询链路，与新平台当前实现做源码级对比。本文件只落档取证和差异，不表示已经修复。
<!-- DOC_STATUS_END -->

# 2026-06-30 老平台源码直接提取与新平台差异审查稿

## 1. 本次边界

用户明确要求先不要继续猜测修复，而是按提供的老 hash 地址和模块名，直接从老平台 `D:\projects\spidergitdata-dev` 提取源码，与新平台 `D:\projects\data_collection_platform` 对比。

本文件只记录已提取证据、差异判断和待审查修复方向；不作为“已修复”结论。后续是否实施，以本文件审查结果和 `docs/platform-page-business-rules.md` 为准。

## 2. 老平台路由到文件映射

证据入口：老平台 `webapp/src/Router.js`。

| 老 hash 地址 | 老平台组件文件 | 新平台对应页面/board |
| --- | --- | --- |
| `#/moduleTableCCProduct` | `webapp/src/views/PageHome/ContentComponents/QuestionnaireInfo/ModuleTableCCProduct.vue` | `customer-issues-home` / `customer-issue-defect-summary` |
| `#/illegalIssueSearchCCProduct` | `webapp/src/views/PageStandard/IllegalIssueSearchCCProduct.vue` | `customer-issues-illegal-records` |
| `#/getModuleAndCauseTableCCProduct` | `webapp/src/views/PageStandard/ModuleAndCauseCCProduct.vue` | `customer-issues-defect-cause` / `customer-issue-defect-cause` |
| `#/ccProductIssueTable` | `webapp/src/views/PageStandard/CCProductIssueTable.vue` | `customer-issues-cc-product-issues` |
| `#/getDelayIssueCCProduct` | `webapp/src/views/PageStandard/DelayIssueTable.vue` | `customer-issues-delay-issues` / `customer-issue-delay-issues` |
| `#/getIssueRespEfficiency` | `webapp/src/views/PageStandard/IssueRespEfficiency.vue` | `customer-issues-response-efficiency` / `customer-issue-response-efficiency` |
| `#/getIssueByFunction` | `webapp/src/views/PageStandard/IssueShowByFunction.vue` | `customer-issues-issue-by-function` / `customer-issue-by-function` |
| `#/issueSearch` | `webapp/src/views/PageStandard/IssueSearch.vue` | `question-metrics-issue-search` |
| `#/illegalIssueSearch` | `webapp/src/views/PageStandard/IllegalIssueSearch.vue` | `question-metrics-illegal-records` |
| `#/getModuleAndCauseTable` | `webapp/src/views/PageStandard/ModuleAndCause.vue` | `question-metrics-defect-cause` / `system-test-defect-cause` |
| `#/delayCauseInfo` | `webapp/src/views/PageDelayCause/DelayCauseInfo.vue` | `question-metrics-delay-analysis` / `system-test-delay-analysis` |
| `#/codeThroughDataTable` | `webapp/src/views/PageHome/ContentComponents/QuestionnaireInfo/CodeThroughTable.vue` | `code-review-illegal-records` |

重要结论：`moduleTableCCProduct` 不是同名 Vue 文件，而是 `QuestionnaireInfo/ModuleTableCCProduct.vue`；不能仅按 hash 名猜文件。

## 3. 客户问题模块源码证据

### 3.1 客户问题缺陷汇总 `moduleTableCCProduct`

老平台前端：

- `projectId: '325'`。
- `mounted()` 先调用 `getAllMileStoneData()`。
- `getAllMileStoneData()` 调用 `getAllMileStone({'projectId': '325'})`。
- 默认 `this.value[0] = this.milestoneList[0]?.value`。
- 查询 `getModuleTable()` 和下钻都传 `projectId=325` 与 `milestone=this.value[this.value.length - 1]`。

老平台后端：

- `DataAnalysisController.getModuleTable(phase='', projectId='9', milestone='')`。
- 客户问题页面实际传 `projectId=325 + milestone`。
- 服务入口为 `spiderIssueDataService.getModuleTable(phase, projectId, milestone)`。

新平台当前观察：

- `customer-issues-home` 使用 `customer-issue-defect-summary`。
- 顶部数据范围由 `frontend/src/composables/statistic-board-data-scopes.ts` 的 `CUSTOMER_ISSUE_MILESTONE_SCOPE_PROVIDER` 提供，`defaultStrategy='first-available'`，候选来自 `api.getCustomerIssueRecordFilterOptions('cc-product', 325)`。
- 后端已有 `CustomerIssueMilestoneCatalogService`，当前实现优先从 `ods_gitlab_issues + ods_gitlab_milestones` 查候选，再合并 `issue_fact`。

已修正点：

- 老平台 `getAllMileStoneName` 的名字接口按 `spider_issue_data.project_id=325` 且 `submission_date >= 2026-01-01` 去重倒序；新平台 `CustomerIssueMilestoneCatalogService` 已将镜像候选和事实候选统一限制为 CC_Product 项目 `325` 且 `2026-01-01` 后创建的议题里程碑，避免把没有客户问题统计范围内议题的旧里程碑带入默认候选。

### 3.2 客户问题非法数据 `illegalIssueSearchCCProduct`

老平台前端：

- `projectId: '325'`。
- `testingPhase: 'CCProduct'` 只是页面残留字段；实际查询传 `projectId` 和 `milestone`。
- `created()` 中 `getMileStone()` 后默认 `this.mileStone = this.mileStoneList[0]`，再 `getTableData()`。
- `getTableData()` POST `/issueStaticData/getIllegalIssue`，参数为 `pageNum/pageSize/moduleName/projectId/milestone/illegalType`。

老平台后端：

- `IssueStaticDataController.getIllegalIssue(phaseName='', moduleName='', illegalType='', pageSize, pageNum, milestone, projectId='9')`。
- 如果 `phaseName` 非空，才用 `testingPhaseService.getByName(phaseName)` 展开。
- 实际查询 `spiderIssueDataDAO.findIllegalIssue(phases, moduleName, illegalType, pageNum, pageSize, milestone, projectId)`。

新平台当前观察：

- `CustomerIssueIllegalRecordsView.vue` 复用 `IssueIllegalRecordsPage.vue`。
- 通用页会用 `defaultProjectId` 或 query 中 `projectId` 构造请求。
- 客户问题非法页需要默认第一可用 `milestoneTitle`，而不是系统测试 `testingPhase`。

已修正点：

- `CustomerIssueIllegalRecordService.PAGE_KEY` 已修正为 `customer-issues-illegal-records`，避免客户问题非法页候选和标签组来源串到 `CC_PRODUCT议题`。
- 客户问题非法页继续按老平台首屏语义使用 CC_Product 里程碑作为主范围控件，并默认第一可用里程碑。

### 3.3 客户问题缺陷原因 `getModuleAndCauseTableCCProduct`

老平台前端：

- `projectId: '325'`。
- `mounted()` 调 `getMileStoneName()`，默认 `this.milestone = this.milestoneList[0]`，再 `gettableDataByMilestone()`。
- 查询 `getModuleAndCauseTable({ milestone, projectId })`。
- 下钻传 `moduleName/cause/pageSize/pageNum/milestone/projectId`。

老平台后端：

- `DataAnalysisController.getModuleAndCauseTable(phase='', projectId='9', milestone='')`。
- 只有 `projectId=9` 且 `phase` 为空时直接返回空字符串。
- 客户问题 `projectId=325` 不受该空 phase 返回逻辑影响，走 `issueService.getModuleAndCauseTable(phase, projectId, milestone)`。

新平台当前观察：

- `customer-issues-defect-cause` 使用 `customer-issue-defect-cause`。
- 顶部里程碑范围来自 `statistic-board-data-scopes.ts`，应默认第一可用。
- 后端相关 board 使用 `CustomerIssueMilestoneFilterSupport` 处理 `milestoneTitle`。

待审查点：

- 若内网仍无表格，优先查默认里程碑是否成功写入 URL/filterGroup，以及 `issue_fact.reason_category` 是否已按老平台 `spider_issue_data.cause` 生成。

### 3.4 `CC_PRODUCT议题` `ccProductIssueTable`

老平台前端：

- `submissionDate: '2026-01-01'`。
- `mileStone: ''`，默认全里程碑。
- `testingPhase: ''`，默认不选。
- `created()` 直接 `getTableData()`，之后再加载各下拉候选。
- 查询 `findCCProductIssueInfo(..., mileStone, ..., pageSize, pageNum)`。

老平台后端：

- `IssueStaticDataController.findCCProductIssueInfo` 使用 `ProjectIssueInfoQueryBuilder`。
- 查询链路设置 `setSubmissionDate(submissionDate)`、`setMilestone(mileStone)`、`setFilterRejected(true)`。
- 不使用客户问题统计页默认第一里程碑。

新平台当前观察：

- `CustomerIssueRecordsView.vue` 根据 route meta 区分 `cc-product` 和 `delay` topic。
- 该页面有 `milestoneDefaultPatchInFlight` 相关逻辑，需要审查是否只对 delay topic 默认里程碑，而不要影响 `cc-product` topic。

待审查点：

- 文档和代码必须明确：`CC_PRODUCT议题` 默认全里程碑，只默认创建日期 `2026-01-01`；不能套统计页“第一里程碑”。
- 如果新平台比老平台数据略少，重点对比 `ProjectIssueInfoQueryBuilder.setFilterRejected(true)` 与新平台 `CustomerIssueRecordProfile` 的 `excludeExcluded/excludeRejectedBugStatus` 组合。

### 3.5 延期问题 / 响应效率 / 按功能展示缺陷数量

老平台三个页面共同点：

- `projectId: '325'`。
- `mounted()` 先获取 `getAllMileStoneName({ projectId: 325 })`。
- 里程碑列表非空时默认 `milestone = mileStoneList[0]`。
- 查询分别传：
  - `getDelayIssue({ projectId, milestone })`
  - `getRespEfficiency({ projectId, milestone })`
  - `getIssueByFunction({ projectId, milestone })`

老平台后端：

- `DataAnalysisController.getDelayIssue(projectId='325', milestone='')`。
- `DataAnalysisController.getIssueRespEfficiency(projectId='325', milestone='')`。
- `DataAnalysisController.getIssueCountByFunc(projectId='325', milestone='')`。

待审查点：

- 新平台这些统计页都应该使用客户问题里程碑范围控件，并默认第一可用。
- 下钻、导出、快照 key 必须携带同一个 `milestoneTitle`。

## 4. 系统测试模块源码证据

### 4.1 议题查询 `issueSearch`

老平台前端：

- `submissionDate: null`。
- `testingPhase: ''`。
- `created()` 直接 `getTableData()`。
- `phaseNameList = this.testingPhase.length > 0 ? this.testingPhase.join(',') : ''`。
- 查询 `findByModuleNameAndPhaseName(..., phaseNameList, ..., queryFilter=false, pageSize, pageNum)`。

老平台后端：

- `IssueStaticDataController.filter` 对 `projectId=9` 使用 `SpiderIssueDataQueryBuilder`。
- 传入 `.setQueryFilter(queryFilter)`，前端默认 `queryFilter=false`。
- 同时 `.setFilterRejected(true)`。
- `SpiderIssueDataQueryBuilder.setFilterRejected(true)` 只排除 `bug_status like 已拒绝` 和 `category like 功能屏蔽`。
- 因为默认 `phaseNameList=''`，不限制测试阶段，查 CrownCAD 项目下全量记录入口。

新平台当前观察：

- `SystemTestIssueSearchService` 默认 `projectId=9`。
- 当前 in-memory 路径调用 `matchesLegacyIssueSearchVisibility`，需要确认其是否只排除“已拒绝/功能屏蔽”，还是仍套了 `is_excluded=false` 或建议/关闭状态排除。
- 当前 SQL repository 的 `Scope.SYSTEM_TEST` 会限定 `testing_phase` 包含“系统测试/回归测试”；议题查询老平台默认不应套此范围。

待审查点：

- 这是 2400 vs 31000 的最大风险点：议题查询默认应是 `projectId=9 + 测试阶段空 + 仅 filterRejected`，不能默认锁定阶段，也不能限定系统测试白名单。

### 4.2 系统测试非法数据 `illegalIssueSearch`

老平台前端：

- `testingPhase: ''`。
- `created()` 先 `getPhaseName()`，然后默认 `this.testingPhase = this.testingPhaseList[0]`，再 `getTableData()`。
- 查询 POST `/issueStaticData/getIllegalIssue`，参数为 `phaseName/pageNum/pageSize/moduleName/illegalType`，不显式传 projectId 时后端默认 `9`。

老平台后端：

- `getIllegalIssue` 中 `phaseName` 非空时使用 `testingPhaseService.getByName(phaseName)` 展开。
- 查询 `spiderIssueDataDAO.findIllegalIssue(..., projectId=9)`。

新平台当前观察：

- `SystemTestIllegalRecordsView.vue` 主筛选 `testingPhase` 已设置 `defaultStrategy='first-available'` 且 `clearable=false`。
- `SystemTestIllegalRecordService.resolvedRequestedPhaseOrWhitelist` 在未传 phase 时取 `phaseScopeOptions().first()`，与老平台默认第一阶段一致。

待审查点：

- 如果内网表格为空，应优先看 `issue_fact.is_illegal / illegal_reasons` 是否重建，以及默认阶段是否实际进入 query。

### 4.3 系统测试缺陷原因 `getModuleAndCauseTable`

老平台前端：

- `ModuleAndCause.vue` 中 `mounted()` 先 `getPhaseName()`，默认 `this.testingPhase = this.testingPhaseList[0]`，再 `gettableData()`。
- 查询 `getModuleAndCauseTable({ phase: this.testingPhase })`。

老平台后端：

- `DataAnalysisController.getModuleAndCauseTable` 在 `(phase == null || phase == '') && projectId == '9'` 时直接返回空字符串。
- 有默认阶段后才进入 `issueService.getModuleAndCauseTable(phase, projectId, milestone)`。

新平台当前观察：

- `system-test-defect-cause` 有后端 `defaultTestingPhase` 和前端 `SYSTEM_TEST_PARENT_SCOPE_PROVIDER`，理论上应默认第一阶段。

待审查点：

- 缺陷原因空表可能有两个来源：默认阶段没进入查询；或 `issue_fact.reason_category` 没按老平台 `cause` 生成。

### 4.4 申请延期缺陷分析 `delayCauseInfo`

老平台前端：

- 路由映射到 `webapp/src/views/PageDelayCause/DelayCauseInfo.vue`。
- `testingPhase: ''`，`testingPhaseList: []`。
- `mounted()` 先调用 `getPhaseName()`，默认 `this.testingPhase = this.testingPhaseList[0]`，再 `gettableData()`。
- 查询 `getDefectAndDelayCauseTable({ phase: this.testingPhase })`。
- 下钻一级/二级/三级缺陷时传 `delayCause/severityLevel/phaseName/pageSize/pageNum`。
- 下钻建议类缺陷时传 `phaseName/delayCause/category='建议'/pageSize/pageNum`。
- 导出调用 `exportDefectAndDelayCauseTable({ phase: this.testingPhaseList[0] })`，老平台这里固定导出第一阶段，和当前选择阶段存在代码差异。

老平台后端：

- `DataAnalysisController.getDelayCauseAndTestingPhase(@RequestParam String phase)`。
- 如果 `phase` 为空，直接返回空字符串。
- `testingPhaseService.getByName(phase)` 展开父级阶段到具体阶段集合。
- 遍历 `DelayEnum.values()`，按每个延期原因分别统计：
  - `SpiderIssueDataQueryBuilder.setTestingPhases(phases).setProjectId(9).setDelayCause(cause.findName).setSeverityLevel('一级缺陷').buildCount()`
  - 二级、三级同理。
  - 建议类调用 `spiderIssueDataDAO.getNumByPhasesAndDelayCauseSuggestion(phases, cause.findName, projectId=9)`。
- 每次加入行后按 `sum desc`，若总数相同按 `critical desc` 排序。

新平台当前观察：

- `question-metrics-delay-analysis` 对应 `system-test-delay-analysis`。
- 前端顶部范围来自 `statistic-board-data-scopes.ts` 的 `SYSTEM_TEST_PARENT_SCOPE_PROVIDER`，默认第一可用父级阶段。
- 后端 `SystemTestDelayAnalysisBoardService.applyDefaultTestingPhase()` 在 filterGroup 未带阶段时补第一可用阶段。
- 后端固定延期原因列表为 `技术卡点、方案卡点、资源卡点、数据异常、算法问题、机制问题、计算效率`，与老平台 `DelayEnum` 口径一致。

待审查点：

- 内网若该页仍为空，优先确认默认 `testingPhase` 是否实际写入 URL/filterGroup/接口，以及 `issue_fact.delay_cause` 是否已从老平台延期原因标签正确派生。
- 老平台导出固定使用 `testingPhaseList[0]`，新平台若导出当前选中阶段属于体验修正但不是 1:1 复刻；需业务确认是否保留新平台行为。

## 5. 代码走查模块源码证据

### 5.1 代码走查非法数据 `codeThroughDataTable`

老平台前端：

- `activeName: 'CC'`。
- `nameSearch: 'CrownCAD'`。
- `pageSize: 40`。
- `mounted()` 直接 `getTableData()`。
- CC 查询调用 `getSpiderCrowncadDataByPage`，参数包括：
  - `projectName`
  - `illegalType`
  - `moduleName`
  - `mergedUserName`
  - `targetBranch`
  - `author`
  - `name: this.nameSearch`
  - `issuableReference`
  - `pageSize/pageNum`
- 不是 issue 的 `projectId=325`。

老平台后端：

- `StaticDataController.getIllegalData` 参数包含 `targetBranch/name/moduleName/mergedUserName/author/issuableReference/illegalType/projectName/startMergedTime/endMergedTime/pageSize/pageNum`。
- 如果 DGM 数据源则强制 `name='DGM'`。
- 查询链路：`SpiderCrowncadQueryBuilder`
  - `.setName(name)`
  - `.setProjectName(projectName)`
  - `.setIllegalSearch(true)`
  - `.setIllegalType(illegalType)`
  - `.setMergedLocalDateTimeStart/End(...)`
  - `.buildPage()`
- `SpiderCrowncadQueryBuilder.setSearchIllegalQuery()`：
  - `status = MERGED`
  - 命中任一非法类型：无代码走查、未代码扫描、未标注项目名、未标注模块名、注释率/bug count/扫描失败/GitLab 接口报错等。
  - `module_name <> '无需标注'`
  - 若 `name` 为空、`CrownCAD` 或 `DGM`，默认 `merged_time > 2024-04-01 00:00:00`。

新平台当前观察：

- `CodeReviewIllegalRecordsView.vue` 当前 `pageSize=40`，并在请求中固定 `projectId: undefined`。
- `route-contracts.ts` 和 `record-route-query-keys.ts` 当前未把 `projectId` 作为代码走查非法页持久 query。
- `CodeReviewIllegalRecordService.withLegacyDefaultScope()` 当前在 source 为空或 CC/default 且 projectName 为空时默认 `projectName='CrownCAD'`。
- `CodeReviewIllegalRecordSourceLoader` 基础 SQL 已包含 `merge_request_state='merged'`、`merged_at_source > 2024-04-01`、`module_name <> '无需标注'`。

已修正点：

- 老平台源码明确默认 `activeName='CC'` 且 `nameSearch='CrownCAD'`。
- `docs/platform-page-business-rules.md` 已按老平台源码更新为代码走查非法数据默认 `source=CC`、`projectName=CrownCAD`，不再采用此前“默认全部数据源”的旧结论。
- 新平台非法数据页已改为使用必选来源范围，按后端候选顺序默认落到 `CC/CrownCAD`。

## 6. 老平台公共查询规则提取

### 6.1 `getAllMileStoneName`

老平台 `MilestoneController.getAllMileStoneName(projectId)`：

- 调用 `spiderIssueDataDAO.findMileStoneByProjectId(projectId)`。
- DAO 条件：
  - `project_id = projectId`
  - `select distinct milestone`
  - `submission_date >= 2026-01-01`
- Java 层 `mileStone.sort(Comparator.reverseOrder())`。

对新平台影响：

- 客户问题默认里程碑应该来自 CC_Product 项目真实议题里程碑，且至少要能覆盖 2026-01-01 后数据。

### 6.2 `QueryUtil.setQueryFilter`

老平台公共过滤：

- 非 CC_Product 项目额外排除：
  - `category not like 功能屏蔽`
  - `bug_status not like 已拒绝`
  - `category not like 建议`
- 所有项目排除：
  - `bug_status not like 申请否决 OR status not like %CLOSED%`
  - `bug_status not like 需求如此 OR status not like %CLOSED%`

注意：

- 议题查询页前端默认 `queryFilter=false`，但仍调用 `setFilterRejected(true)`。
- 因此议题查询默认不是完整 `QueryUtil.setQueryFilter`，而主要是 `已拒绝/功能屏蔽` 排除。

## 7. 待审查差异清单

| 问题 | 老平台源码结论 | 新平台需审查点 |
| --- | --- | --- |
| 所有页面 projectId=325 | 错。只有客户问题相关统计/记录固定 325；系统测试默认 9；代码走查非法不用 issue projectId=325 | 页面契约和服务层不能一刀切 |
| 客户问题统计页无默认里程碑 | 老平台默认 CC_Product 里程碑列表第一项 | 新平台需确认默认值写入 URL/filterGroup/接口，候选不能依赖空的 `issue_fact.milestone_title` |
| `CC_PRODUCT议题` 数据略少 | 老平台默认 `submissionDate=2026-01-01`，里程碑空表示全部，只 `setFilterRejected(true)` | 新平台不能给该页补默认第一里程碑；过滤规则需对齐 `ProjectIssueInfoQueryBuilder` |
| 系统测试议题查询只有 2400 | 老平台默认 phase 空，查 projectId=9 全量记录入口，只 `filterRejected` | 新平台不能默认阶段、不能限定系统测试白名单、不能套 `is_excluded=false` |
| 系统测试非法数据空 | 老平台默认第一测试阶段，查 `illegal_list is not null` 类口径 | 新平台需确认默认阶段实际生效和 `issue_fact.is_illegal/illegal_reasons` 已重建 |
| 系统测试缺陷原因空 | 老平台若 projectId=9 且 phase 空直接返回空；前端默认第一阶段 | 新平台需确认默认阶段和 `reason_category` 事实生成 |
| 延期原因分析 | 老平台默认第一测试阶段；phase 为空返回空；按固定 `DelayEnum` 统计一级/二级/三级和建议类 | 新平台需确认默认阶段写入和 `delay_cause` 事实生成；导出当前阶段 vs 老平台固定第一阶段需审查 |
| 代码走查非法数据空 | 老平台默认 CC / CrownCAD，不传 issue projectId=325 | 已按老平台确认默认 `CC/CrownCAD`，并移除 issue `projectId=325` 依赖 |

## 8. 建议的下一步审查顺序

1. 继续审查 `CC_PRODUCT议题` 是否仍被统计页默认里程碑或公共排除规则误伤。
2. 继续审查系统测试议题查询是否仍使用 `Scope.SYSTEM_TEST` 或 `is_excluded=false`。
3. 申请延期缺陷分析已补充旧源码取证；后续重点确认默认阶段和 `delay_cause` 事实生成。
4. 修改后按仓库规则只做一次最小编译/类型检查。

## 9. 2026-06-30 客户问题统计首次缺快照执行方案

本节记录 2026-06-30 继续修复前的执行方案。该方案区分“快照命中后页面快速返回”和“首次缺快照/快照失效后仍同步聚合”的两层问题，后续实现以本节和 `docs/platform-page-business-rules.md` 为依据。

### 9.1 根因判断

- 客户问题五个聚合看板已经接入 `statistic_board_snapshots`，快照命中时不再重复聚合。
- 当前快照服务在未命中时仍同步执行 `responseSupplier.get()` 并保存结果；因此首次打开、快照失效或事实层版本变化后，用户请求仍会等待现场计算完成。
- 现有客户问题统计服务已经有部分 SQL 前置条件，例如 `projectId=325`，但主聚合和部分过滤仍在 Java 内存完成；问题不是“完全全局扫 issue_fact”，而是“半 SQL、半 Java 聚合”导致缺快照路径仍可能慢或超时。
- 系统测试非法数据属于记录分页范围问题，不属于客户问题统计性能问题；必须单独审计默认 `projectId=9`、默认第一测试阶段和 `Scope.SYSTEM_TEST` 是否生效。

### 9.2 P0 执行范围

1. **P0-0：系统测试非法数据范围审计**
   - 审查 `SystemTestIllegalRecordService`、分页查询对象和 SQL repository。
   - 确认默认 `projectId=9`、默认第一测试阶段、SQL 范围不是 `Scope.ALL`，且不再用标签兜底扩大范围。
   - 若后端已满足，仅记录审计结论；若未满足，作为 P0 同批补丁。
   - 审计结论：当前后端主分页已使用 `IssueFactRecordPageQuery.Scope.SYSTEM_TEST`，`projectId` 为空时默认 `9`，`testingPhase` 为空时取系统测试阶段列表第一项并展开，暂不需要额外补后端范围补丁。
2. **P0-1：客户问题 SQL scope 支撑**
   - 抽取统一的客户问题 SQL scope 支撑，避免五个服务各自半 SQL、半 Java 过滤。
   - 统一下推 `project_id=325`、`created_at_source >= 2026-01-01`、`milestone_title = :milestoneTitle`、`is_excluded=false`。
   - `milestoneTitle` 必须作为参数进入 SQL，不能写死默认里程碑。
3. **P0-2：客户问题缺陷汇总首屏 SQL 化**
   - 优先改造主表聚合路径。
   - 模块拆分、严重程度计数和一级缺陷分类尽量在 SQL 前推；若标题分类 SQL 表达过硬，可保留 Java 分类，但输入必须已经按客户问题 scope 和当前里程碑收窄。
4. **P0-3：客户问题缺陷原因分析首屏 SQL 化**
   - 按 `module_names + reason_category` 聚合当前客户问题 scope、当前里程碑、公共排除后的数据。
   - 保留老平台原因映射规则，不因 SQL 优化改变口径。
5. **P0-4：默认快照预热**
   - 保留现有快照命中读取。
   - 事实层重建后预热客户问题 P0 页面。
   - 预热前 3 个活跃里程碑，快照 key 必须包含 `milestoneTitle`，不同里程碑不共享快照。

### 9.3 P1 暂缓范围

- 客户问题延期问题、缺陷响应效率、按功能展示缺陷数量在 P0 稳定后再 SQL 化。
- 延期页优先下推 `is_response_delayed/is_resolve_delayed/open/illegal_reasons not contains GitLab接口报错`。
- 响应效率可用 SQL 做基础筛选和平均值计算，展示格式仍由 Java 组装。
- 按功能展示后续按 `module + function_name` 做 SQL 聚合。

### 9.4 验收口径

- 清空或置 stale 客户问题 P0 快照后，首次打开也应返回表格。
- 默认请求的 applied filters 必须包含 `projectId=325` 和明确 `milestoneTitle`。
- SQL 慢日志中不应再出现客户问题 P0 首屏一次性大范围拉取后长时间 Java 聚合。
- 系统测试非法数据单独给出范围审计结论或补丁，不与客户问题性能优化混为同一个问题。
