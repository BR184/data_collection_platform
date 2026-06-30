<!-- DOC_STATUS_START -->
> 文档状态：当前修复方案 / 待实施
> 说明：记录 2026-06-29 内网复测后仍未对齐的问题、根因和下一轮修复方案。当前结论以老平台源码和 `docs/platform-page-business-rules.md` 为准。
<!-- DOC_STATUS_END -->

# 2026-06-29 内网复测：老平台入口、页面范围和规则差异

## 1. 核心结论

这轮问题不能再按“客户问题都强制 325”或“所有页面都补默认项目”的方式处理。老平台每个入口有自己的数据范围：

| 老平台地址 | 老平台页面/接口 | 新平台目标页面 | 项目/范围口径 |
| --- | --- | --- | --- |
| `#/moduleTableCCProduct` | 客户问题缺陷汇总 | `customer-issues-home` | 固定 `CC_Product projectId=325`，顶部切换 CC_Product 真实里程碑 |
| `#/illegalIssueSearchCCProduct` | 客户问题缺陷非法数据 | `customer-issues-illegal-records` | 固定 `projectId=325`，默认第一可用里程碑 |
| `#/getModuleAndCauseTableCCProduct` | 客户问题缺陷原因分析 | `customer-issues-defect-cause` | 固定 `projectId=325`，默认第一可用里程碑 |
| `#/ccProductIssueTable` | CC_PRODUCT 议题 | `customer-issues-cc-product-issues` | 固定 `projectId=325`，默认提交日期 `2026-01-01`，里程碑为空表示全部 |
| `#/getDelayIssueCCProduct` | 延期问题 | `customer-issues-delay-issues` | 固定 `projectId=325`，默认第一可用里程碑 |
| `#/getIssueRespEfficiency` | 缺陷响应效率 | `customer-issues-response-efficiency` | 固定 `projectId=325`，默认第一可用里程碑 |
| `#/getIssueByFunction` | 按功能展示缺陷数量 | `customer-issues-issue-by-function` | 固定 `projectId=325`，默认第一可用里程碑 |
| `#/issueSearch` | 系统测试议题查询 | `question-metrics-issue-search` | 默认 `projectId=9`，测试阶段默认空，表示全部 |
| `#/illegalIssueSearch` | 系统测试非法数据 | `question-metrics-illegal-records` | 默认 `projectId=9`，默认第一可用测试阶段 |
| `#/getModuleAndCauseTable` | 系统测试缺陷原因分析 | `question-metrics-defect-cause` | 默认 `projectId=9`，必须有测试阶段，否则老平台后端直接返回空 |
| `#/delayCauseInfo` | 申请延期缺陷分析 | `question-metrics-delay-analysis` | 默认 `projectId=9`，按系统测试阶段定义展开 |
| `#/codeThroughDataTable` | 代码走查非法数据 | `code-review-illegal-records` | 走 `staticData/getIllegalData` 口径，默认 CC 代码库 `name=CrownCAD`，不是 issue 的 `projectId=325` |

这些老 hash 地址是老平台源码定位锚点，不是新平台入口约定。它们的作用是帮助我们在老平台源码里快速找到对应页面、接口和规则代码，再和新平台当前实现做对比；新平台继续使用自己的路由和页面契约，不需要去接管这些老 hash 地址。

## 2. 已确认根因

### 2.1 老 hash 地址只是源码定位锚点，不是新平台入口

证据：

- 老平台 `webapp/src/Router.js` 明确存在 `moduleTableCCProduct`、`illegalIssueSearch`、`codeThroughDataTable` 等地址。
- 新平台 `frontend/src/router.ts` 使用的是自己的页面路径体系，不需要声明这些老 hash 地址。

影响：

- 如果把老 hash 地址误当成新平台入口，会误判页面问题来源，进而把真正该查的规则差异、默认值差异和事实层差异看偏。
- 这类老地址只能用于“从链接名推断功能名，再去老平台源码找对应实现”。

方案：

- 继续沿用“老地址 -> 老平台源码定位 -> 新平台对应实现对比”的分析方式。
- 新平台修复只针对自己的路由、页面契约、事实层和查询规则，不做老 hash 地址兼容接管。

### 2.2 客户问题无默认里程碑导致统计页无表格

证据：

- 老平台客户问题统计类页面通过 `getAllMileStoneName({ projectId: 325 })` 取里程碑，并默认 `mileStoneList[0]`。
- 老平台 `MilestoneController.getAllMileStoneName` 调用 `spiderIssueDataDAO.findMileStoneByProjectId(projectId)`，并按倒序返回。
- 新平台 `CustomerIssueMilestoneCatalogService` 只从 `issue_fact.milestone_title` 取候选。
- 新平台 `GitlabFactSourceSqlProvider` 需要 join `ods_gitlab_milestones` 才能生成 `milestone_title`；join 不可用时 fallback 会把 `milestone_title` 置空。

影响：

- 内网显示 `projectId=325` 但没有默认里程碑，客户问题缺陷汇总、缺陷原因分析、延期问题、响应效率、按功能展示缺陷数量都会缺少首屏范围。
- 如果统计 board 设计为“没有默认里程碑不查表”，用户看到的就是没有表格或空表格。

方案：

- 客户问题里程碑候选不能只依赖 `issue_fact`；应优先从镜像库 `ods_gitlab_milestones` + `ods_gitlab_issues(project_id=325)` 取真实里程碑，`issue_fact` 只作兜底。
- 事实层构建时必须把 milestone join 纳入硬性依赖或可观测告警；不能静默 fallback 成空里程碑后仍认为事实层可用。
- 客户问题统计类页面进入时，前端和后端都要补同一个默认里程碑：CC_Product 里程碑倒序第一项。

### 2.3 CC_PRODUCT 议题“略少”与统计范围混用有关

证据：

- 老平台 `CCProductIssueTable.vue` 初始 `submissionDate = '2026-01-01'`，里程碑默认空。
- 老平台 `IssueStaticDataController.findCCProductIssueInfo` 走 `ProjectIssueInfoQueryBuilder`，默认只设置 `setFilterRejected(true)`，并按前端传入的提交日期过滤。
- 业务规则总表要求 `CC_PRODUCT议题` 默认 `projectId=325`、默认全里程碑、默认排除 `已拒绝`，且与统计页口径分开。

影响：

- 如果新平台把 CC_PRODUCT 议题也套成“必须选择默认里程碑”或套用统计页公共排除，会比老平台少。

方案：

- 拆分客户问题记录页画像：`CC_PRODUCT议题` 使用老平台 `findCCProductIssueInfo -> ProjectIssueInfoQueryBuilder` 口径；统计/非法/延期页使用 `SpiderIssueDataDAO` 口径。
- `CC_PRODUCT议题` 默认里程碑为空表示全部，不能沿用统计页默认第一里程碑。

### 2.4 系统测试议题查询被套用了过严范围

证据：

- 老平台 `IssueSearch.vue` 的 `created()` 直接调用 `getTableData()`；`testingPhase` 初始为空。
- 老平台议题查询使用 `findByModuleNameAndPhaseName`，测试阶段多选为空时传 `phaseNameList=''`，即查询 CrownCAD 项目下全部阶段。
- 业务规则总表也明确：系统测试议题查询是记录检索特例，默认测试阶段为空，不默认锁定 `CC2026R3`。

影响：

- 内网新平台只有约 2400 条，老平台约 31000 条，说明新平台仍在默认阶段、系统测试白名单、`is_excluded` 或其他统计页过滤上过度收窄。

方案：

- 系统测试议题查询直接复刻老平台 `IssueStaticDataController.filter -> SpiderIssueDataQueryBuilder.buildPage` 的记录页条件。
- 默认只补 `projectId=9`，不要补默认测试阶段。
- 页面筛选候选、分页、导出必须共用这套记录页查询，不再复用统计看板过滤链。

### 2.5 系统测试非法数据空表与默认阶段/非法来源不一致有关

证据：

- 老平台 `IllegalIssueSearch.vue` 使用 `getLabelPhase()`，并在 `created()` 中默认 `testingPhaseList[0]`。
- 老平台后端 `IssueStaticDataController.getIllegalIssue` 会用 `testingPhaseService.getByName(phaseName)` 展开阶段，再调用 `SpiderIssueDataDAOImpl.findIllegalIssue`。
- 老平台非法数据查询的核心字段是 `spider_issue_data.illegal_list`：指定类型时 `like illegal_list`，未指定类型时 `illegal_list is not null`。
- 新平台此前使用 `issue_fact.is_illegal / illegal_reasons` 重新推导，容易与老平台数量不一致；如果阶段默认或事实非法字段为空，还会直接空表。

影响：

- 内网系统测试非法数据页面空表。
- 客户问题非法数据虽然有数据但略多，说明新平台非法判定仍未完全等价 `illegal_list`。

方案：

- 将老平台非法判定代码和 `findIllegalIssue` 查询语义沉淀为新平台统一规则层，事实字段也按老平台 `illegal_list` 语义生成。
- 系统测试非法数据默认第一可用父级阶段；客户问题非法数据默认第一可用 CC_Product 里程碑。
- 不再用统计页 scope 或标签兜底扩大/缩小非法页集合。

### 2.6 系统测试缺陷原因分析空表有两个触发点

证据：

- 老平台 `DataAnalysisController.getModuleAndCauseTable` 在 `projectId=9` 且 `phase` 为空时直接返回空字符串。
- 老平台原因统计走 `IssueServiceImpl.getModuleAndCauseTable`，按 `spider_issue_data.cause` 和缺陷原因枚举统计。
- 新平台缺陷原因分析依赖 `issue_fact.reason_category/raw_payload`，如果事实字段没生成，表格只有表头。

影响：

- 如果旧地址没有正确进入新页面、页面没有默认系统测试阶段，系统测试缺陷原因分析会空。
- 即使默认阶段正确，`reason_category` 没按老平台 `cause` 规则生成也会空。

方案：

- 系统测试缺陷原因分析默认第一可用系统测试父级阶段，并同步到 URL、接口、导出和下钻。
- 事实层缺陷原因生成直接复刻老平台从模板解析到 `spider_issue_data.cause` 的规则；统计 SQL 只消费这个等价字段。
- 如果原因事实为空，页面应暴露“原因事实未生成/需重建事实层”的状态，不能表现成正常空表。

### 2.7 申请延期缺陷分析差异应查 delay_cause 生成与阶段过滤

证据：

- 业务规则总表要求延期原因固定为：技术卡点、方案卡点、资源卡点、数据异常、算法问题、机制问题、计算效率。
- 客户问题 GitLab 延期标签写回默认关闭，只影响真实 GitLab 标签写操作，不直接改变系统测试 `delay_cause` 聚合。

影响：

- “数据异常”列差异不是写回关闭直接导致，应定位事实层 `delay_cause` 生成规则和系统测试公共过滤。

方案：

- 复刻老平台延期原因标签解析和 `DelayEnum` 聚合规则。
- 与系统测试缺陷原因分析共用同一套阶段展开和公共过滤，避免同阶段下两个统计页取数范围不同。

### 2.8 代码走查非法数据空表不是客户问题 projectId 问题

证据：

- 老平台 `/codeThroughDataTable` 实际页面是 `PageHome/ContentComponents/QuestionnaireInfo/CodeThroughTable.vue`。
- 老平台页面默认 `activeName='CC'`、`nameSearch='CrownCAD'`，调用 `/staticData/getIllegalData`。
- 老平台后端 `StaticDataController.getIllegalData` 使用 `SpiderCrowncadQueryBuilder`，筛选字段是 `name/projectName/moduleName/author/mergedUserName/targetBranch/illegalType`，不是 issue 的 `projectId=325`。
- 新平台 `CodeReviewIllegalRecordSourceLoader` 从 `merge_request_fact` 读取，基础条件含 `merge_request_state='merged'`、`merged_at_source > 2024-04-01`、`module_name <> '无需标注'`。

影响：

- 如果旧地址没映射到新代码走查页面，或新平台页面保留了错误 `projectId`、错误 source、错误 projectName，列表会空。
- 代码走查剩余 6000 条差异不能用客户问题项目规则修；需要逐项复刻 `SpiderCrowncadQueryBuilder` 和 `StaticDataController.getIllegalData` 的非法类型映射。

方案：

- 老地址 `#/codeThroughDataTable` 映射到 `code-review-illegal-records`，默认 `source=CC`、`projectName` 按老平台 `nameSearch='CrownCAD'` 行为处理。
- 移除该页对 `projectId` 的持久化依赖，优先使用 `projectName/name/source`。
- 代码走查非法类型直接移植老平台 `SpiderCrowncadQueryBuilder.setIllegalSearch/setIllegalType` 与 `StaticDataController.getIllegalData` 的结果组装逻辑。

## 3. 下一轮修复顺序

1. 建立页面范围注册表，明确每个页面默认项目、默认阶段/里程碑、是否允许项目切换、是否允许全范围。
2. 客户问题里程碑候选改为镜像里程碑优先，并补事实层 milestone join 失败告警。
3. 系统测试记录页、非法页、原因/延期统计页分别复刻老平台入口规则，不能共用一个过泛 scope。
4. 代码走查非法页复刻老平台 `StaticDataController.getIllegalData -> SpiderCrowncadQueryBuilder`。
5. 重建事实层：`issue_fact` 需要重建原因、延期、非法、里程碑字段；`merge_request_fact` 需要重建代码走查非法相关字段。

## 4. 需要直接复用/转译的老平台代码入口

- 路由入口：`D:\projects\spidergitdata-dev\webapp\src\Router.js`
- 系统测试议题查询：`webapp/src/views/PageStandard/IssueSearch.vue`
- 系统测试非法数据：`webapp/src/views/PageStandard/IllegalIssueSearch.vue`
- 客户问题非法数据：`webapp/src/views/PageStandard/IllegalIssueSearchCCProduct.vue`
- CC_PRODUCT 议题：`webapp/src/views/PageStandard/CCProductIssueTable.vue`
- 客户问题缺陷原因：`webapp/src/views/PageStandard/ModuleAndCauseCCProduct.vue`
- 延期问题：`webapp/src/views/PageStandard/DelayIssueTable.vue`
- 响应效率：`webapp/src/views/PageStandard/IssueRespEfficiency.vue`
- 按功能展示缺陷数量：`webapp/src/views/PageStandard/IssueShowByFunction.vue`
- 代码走查非法数据：`webapp/src/views/PageHome/ContentComponents/QuestionnaireInfo/CodeThroughTable.vue`
- 议题接口：`src/main/java/com/huayun/controller/IssueStaticDataController.java`
- 统计接口：`src/main/java/com/huayun/controller/DataAnalysisController.java`
- 里程碑接口：`src/main/java/com/huayun/controller/MilestoneController.java`
- 系统测试/客户问题 DAO：`src/main/java/com/huayun/service/impl/SpiderIssueDataDAOImpl.java`
- 代码走查接口：`src/main/java/com/huayun/controller/StaticDataController.java`
- 代码走查查询构造器：`src/main/java/com/huayun/service/queryBilder/SpiderCrowncadQueryBuilder.java`

## 5. 验收重点

1. 直接访问所有老 hash 地址，确认进入正确新页面且顶部默认控件与老平台一致。
2. 客户问题统计类页面必须出现 CC_Product 默认里程碑和表格；`CC_PRODUCT议题` 默认里程碑为空但提交日期为 `2026-01-01`。
3. 系统测试议题查询默认测试阶段为空，数量应接近老平台全量记录页。
4. 系统测试非法数据默认第一可用阶段，不应空表。
5. 系统测试缺陷原因分析默认阶段后不应空表；若事实层原因字段为空，应明确提示需重建事实层。
6. 代码走查非法数据默认 CC 代码库/CrownCAD 范围，不携带客户问题 projectId。
7. 所有导出、下钻、分页总数复用页面同一套查询规则。
