# 2026-06-02 平台数据质量、看板与同步调度问题调研方案

## 状态

第一阶段修复中。已完成 `issue_fact` 模块/工具箱标签归一化、评审导入空默认值放开、集成测试页阶段主导与议题链接展示修复；其余问题仍按本文方案分批推进。

## 2026-06-02 第一阶段修复记录

### 已实施

- `IssueLabelRules.normalizeModuleNames` 改为只从明确模块来源抽取模块值，不再把所有未排除的 GitLab label 当模块。
- 支持 `模块：X`、`模块:X`、`module：X`、`module:X`、`工具箱：X`、`工具箱:X`，输出统一为 `X`。
- 同一条议题里的模块/工具箱来源相同值会去重，不会重复统计。
- `9007`、`前端`、`分支：发布`、`CC2023R3客户` 等未命中模块来源的标签不会进入 `issue_fact.module_names`。
- 保留历史兼容：`草图模块` 这类明确以“模块”结尾的旧式模块标签仍作为候选，避免一次性破坏已有集成测试样例。

### 已验证

- `mvn -Dtest=IssueFactNormalizationRulesTest test`：通过，覆盖模块/工具箱中英文冒号归一、相同值去重、不同值保留为多个模块。
- `mvn -Dtest=IssueFactSourceInstancePipelineTest test`：通过，覆盖 GitLab ODS label 进入 `issue_fact` 后只保留 `草图`，不保留 `9007`、`前端`、`分支：发布`。
- `mvn -Dtest=IntegrationTestFactPipelineTest test`：通过，确认本次改动未破坏当前集成测试事实构建样例。

### 2026-06-02 第二批修复记录

#### 已实施

- `ReviewDataLegacyExcelImportService` 中默认负责人、默认日期、默认专家、默认版本为空时，不再作为阻断性 `ERROR`。
- 这些字段改为 `WARNING`，允许预览和确认导入继续执行。
- 评审导入预览和确认导入都保持空值可导入语义，默认空字段只作为补充信息，不再把有效行拦成 0 可导入。

#### 已验证

- `mvn -Dtest=ReviewDataLegacyExcelParserTest test`：通过，覆盖空默认值预览、确认导入、负数拒绝和历史模板解析。

### 2026-06-02 第三批修复记录

#### 已实施

- `IntegrationTestAnalysisView` 去掉“全部项目/当前项目”的页面主筛选语义，集成测试分析页改为以 `testingPhase` 作为主范围。
- 旧路由中如果带有 `projectId`，页面会自动清理该 query，并保留/归一到有效测试阶段。
- 集成测试汇总、明细、明细导出、模块功能导出、横向对比导出不再从前端传 `projectId`，避免用户视角出现“全部项目”或项目维度主导。
- 集成测试明细的 `issuableReference` 列改为 `link` 类型，后端返回 `issueLink` 时渲染为可点击议题链接，不再把 `{label, href}` 对象当 JSON 文本显示。

#### 已验证

- `npm test -- --run src/views/integration-test-analysis.mount-smoke.test.ts`：通过，覆盖旧 `projectId` query 自动清理、页面不显示“全部项目/当前项目”、导出请求不带 `projectId`、议题编号渲染为链接且不显示 JSON。
- `npm test -- --run src/components/base/base-record-table.test.ts src/utils/issue-record-links.test.ts src/views/integration-test-analysis.mount-smoke.test.ts`：通过，覆盖通用表格 link cell、议题链接工具函数和集成测试页 smoke。

### 2026-06-02 第四批修复记录

#### 已实施

- `IntegrationTestFactBuildService` 在无法识别规范模块时不再写入 `未识别模块`，而是保留 `module_name=null`。
- `IntegrationTestQueryService` 汇总、明细、CSV 明细导出、模块功能 Excel 导出、横向对比数据源统一过滤空模块，只展示可识别的规范模块。
- 模块详情筛选改为 `module_name = ?` 精确匹配，不再通过 `coalesce(module_name, '未识别模块')` 把空模块纳入业务维度。

#### 已验证

- `mvn -Dtest=IntegrationTestFactPipelineTest test`：通过，新增覆盖只有 `前端`、`9007`、`分支：发布` 等噪声标签时，事实构建仍发生但集成测试汇总和明细不展示该记录。

### 2026-06-02 第五批调研记录（未改代码）

#### 新发现 1：系统测试/质量看板图表百分比把“数量”当成“百分比”

现象关联：截图中“模块修复率”出现 2108%、14569%、16318% 等异常值。

已确认代码路径：

- `backend/src/main/java/com/data/collection/platform/service/statistics/SystemTestDefectSummaryBoardService.java`
- `backend/src/main/java/com/data/collection/platform/service/statistics/DefectSummaryBoardSupport.java`
- `frontend/src/views/quality-board.ts`
- `frontend/src/views/system-test-multi-board.ts`

根因判断：

- 后端统计单元格 `StatisticCellData.numericValue` 在 `fix_rate`、`level*_rate`、`p*_fix_rate` 等比例列里保存的是分子数量，例如 `solved`、`level1Fixed`，而 `displayValue` 才是 `80.00%` 这种百分比文本。
- 前端图表 `buildSystemTestRepairChartOption`、`buildRepairRateChartOption` 用 `cellNumber(row, 'fix_rate')` 读取 `numericValue`，再追加 `%` 展示。
- 因此当某模块已修复数量是 14569 时，图表会显示为 `14569.00%`，并不是后端公式直接算出了 14569%。

建议方案：

1. 统一 `StatisticCellData` 的比例列语义：比例类单元格的 `numericValue` 应为百分比数值，例如 `80.00`，`displayValue` 为 `80.00%`。
2. 如果仍需要保留分子数量用于下钻或排序，新增字段或 detail param，例如 `rawNumerator`、`rawDenominator`，不要复用 `numericValue`。
3. 前端图表在修复前可临时解析 `displayValue` 作为比例值，但长期应由后端统一输出结构化比例数值。
4. 为 `quality-board.ts` 和 `system-test-multi-board.ts` 增加回归测试：当后端 `numericValue=14569`、`displayValue=80.00%` 时，图表不能显示 `14569.00%`。

#### 新发现 2：系统测试下拉选项仍依赖事实字段质量，需在事实重建后验证

已确认代码路径：

- `backend/src/main/java/com/data/collection/platform/service/SystemTestIssueSearchService.java`
- `backend/src/main/java/com/data/collection/platform/service/IssueFactRecordRepository.java`

根因判断：

- 系统测试议题查询的模块下拉来自 `IssueFactRecord.moduleNames()` 的 distinct 拆分结果。
- 当前代码路径没有再次识别 GitLab 原始 label，而是消费 `issue_fact.module_names`。
- 因此模块下拉污染的主因仍是事实层污染；修复模块归一化后，必须重建 `issue_fact` 并抽样验证下拉。
- 但如果历史脏数据没有重建，前端仍可能继续看到 `9007`、`前端`、`分支：发布` 等旧值。

建议方案：

1. 模块归一化修复发布后，必须触发全量事实重建或按源实例重建 `issue_fact`。
2. 下拉接口增加防御性过滤：过滤空值、`未识别模块`、明显非模块前缀值、项目/分支/客户类值。
3. 为系统测试下拉增加后端测试，覆盖 `模块：草图`、`工具箱:草图`、`前端`、`9007`、`分支：发布` 混合输入时，选项只保留 `草图`。

#### 新发现 3：数据库浏览页单表刷新仍把“提交请求”显示为“刷新完成”

已确认代码路径：

- `frontend/src/components/DatabaseBrowserView.vue`
- `backend/src/main/java/com/data/collection/platform/controller/DatabaseBrowserController.java`

根因判断：

- 后端 `/api/database-browser/refresh` 返回 `accepted`、`runId`、`status`、`message`、`plannedTasks`，语义是刷新任务提交/复用/排队状态。
- 前端收到接口返回后立即 `loadTables()`、`loadRows()` 并提示 `当前表数据已刷新`。
- 如果后端返回的实际状态是 `QUEUED`、`RUNNING`、`DEDUPED`，页面仍会给用户“已经完成”的错觉。

建议方案：

1. 前端文案改为“刷新请求已提交”，按后端 `status` 分别提示“排队中/执行中/已合并/无可刷新任务”。
2. 数据表状态卡展示 `runId`，允许用户跳转同步日志查看真实进度。
3. 只有表级任务确认 `SUCCESS` 后才提示“当前表数据已刷新”。
4. 增加 `DatabaseBrowserView` 前端测试，模拟 `QUEUED` 和 `DEDUPED` 响应，确保不出现“已刷新完成”。

#### 新发现 4：自动补偿扫描仍只有间隔模式，没有手动定时/运行窗口

已确认代码路径：

- `backend/src/main/java/com/data/collection/platform/service/GitlabCompensationScheduler.java`
- `backend/src/main/java/com/data/collection/platform/service/GitlabDailyVerificationScheduler.java`
- `backend/src/main/java/com/data/collection/platform/entity/GitlabSyncConfig.java`

根因判断：

- 自动补偿扫描使用 `@Scheduled(fixedDelayString = "${platform.gitlab-mirror.scheduler-delay-ms:60000}")` 每 60 秒检查一次。
- 是否触发由 `lastIncrementalSyncAt + compensationIntervalMinutes` 决定。
- 全量补偿有 `fullCompensationTime`，但自动补偿没有等价的“每天几点执行”或“运行窗口”设置。

建议方案：

1. 为自动补偿增加 `compensationScheduleMode`：`INTERVAL` / `DAILY_TIME` / `WINDOWED_INTERVAL`。
2. 增加配置字段：`compensationTime`、`compensationWindowStart`、`compensationWindowEnd`、`missedWindowPolicy`。
3. UI 在镜像设置页提供模式切换，间隔模式保留当前行为，定时模式允许用户手动设置时间。
4. 调度器在全量补偿运行中应跳过自动补偿并记录跳过原因，避免任务堆叠。

### 尚未完成的验收

- 尚未将同一份真实数据分别导入老平台和新平台做深度对比；需要等后续导入、看板、集成测试页面修复继续推进后统一执行。
- 尚未进行浏览器视角全页面深度测试；后续需要按“用户视角深度测试”章节执行并补充截图/证据。

### 本轮新发现但暂不抢修

- 集成测试后端接口仍保留 `projectId` 参数和 `/project-options` 接口，当前前端已不再使用它们作为主筛选。若后续要彻底删除项目维度，需要单独做 API 契约迁移、兼容期评估和导出接口回归。
- `IssueFactNormalizationRules.normalizeModuleNames` 目前仍兼容 `草图模块` 这种旧式裸模块标签。长期方案应由模块字典/标签组配置接管裸值识别，本轮保留是为了降低回归风险。
- 评审导入虽然已放开空默认值，但当前前端预览文案和错误分级仍可能把 warning 看起来像“有问题”；后续要把预览页的 `warning` / `error` 视觉和导入结果拆清楚，避免用户误以为仍不可导入。

## 背景

本轮问题来自 2026-06-02 用户现场反馈，涉及评审数据导入、质量看板、集成测试、系统测试缺陷汇总、下拉选项、以及 GitLab 镜像同步调度。调研范围包括当前平台 `D:\projects\data_collection_platform` 和重构前老平台空框架 `D:\projects\spidergitdata-dev`。

老平台仅作为功能语义参考：记录“应该展示什么”和“以什么维度聚合”，不照搬实现代码。

## 总体判断

这些问题不是单点 UI 缺陷，主要集中在三条主线：

1. 数据导入校验策略过严，默认字段为空时把可导入数据拦截掉。
2. GitLab label 到事实层字段的归一化过宽，导致项目、分支、客户、状态等非模块字段污染模块维度。
3. 同步调度和前端状态表达存在语义不一致，尤其是补偿扫描、全量补偿、单表刷新之间的优先级和合并关系。

优先级建议：先修事实层字段归一化，再修导入和页面契约，最后修同步调度策略。因为模块字段污染会同时影响系统测试、集成测试、质量看板和下拉选项。

## 问题 1：评审数据管理导入失败

### 现象

评审数据管理模块通过 Excel 解析预览后，默认专家、默认版本、负责人、评审日期等字段为空时，预览结果显示大量错误，最终可导入数量为 0。

### 已确认根因

当前解析器能读取 Excel 行，但导入服务在预览/确认阶段把部分默认字段当成阻断性错误。

涉及代码：

- `backend/src/main/java/com/data/collection/platform/service/ReviewDataLegacyExcelParser.java`
- `backend/src/main/java/com/data/collection/platform/service/ReviewDataLegacyExcelImportService.java`

`ReviewDataLegacyExcelImportService` 会尝试用请求默认值补全 `reviewOwner`、`reviewDate`、`reviewExperts`、`reviewVersion`。如果补全后仍为空，就加入 `ERROR`，导致行不可导入。

### 目标行为

默认设置应该允许为空。空默认值不应该直接导致导入失败。

### 解决方案

1. 将负责人、评审日期、专家、版本等字段从阻断性 `ERROR` 调整为可为空或 `WARNING`。
2. 只保留真正无法落库或无法识别业务对象的字段作为阻断错误，例如标题、评审类型、问题项结构等。
3. 预览阶段区分：
   - `canImport=true`：可导入，但可能带提示。
   - `warnings`：字段为空、无法自动补全、使用默认值等。
   - `errors`：确实不能导入。
4. 确认导入时按同一套规则执行，避免预览可导入但确认失败。

### 验证点

- 默认专家为空可以导入。
- 默认版本为空可以导入。
- 默认负责人为空可以导入。
- 默认评审日期为空可以导入。
- 预览可导入数量与确认导入数量一致。

## 问题 2：质量看板数据异常，百分比出现几百/几千，部分图表为空

### 现象

看板中模块修复率等图表出现 2108%、14569%、16318% 等异常百分比；部分图表为空。

### 老平台语义参考

老平台模块缺陷汇总的核心展示维度是“模块”，主要字段包括：

- 模块名
- 一级缺陷分类：回退、挂机、其他
- 一级/二级/三级缺陷数量、已修复数量、修复率
- P1/P2/P3 数量与占比
- 模块总缺陷数
- 缺陷占比
- 延期缺陷占比
- 修复率
- 关闭率
- 未关闭缺陷数
- 申请延期
- 复测未通过
- 新发议题
- 遗留率

老平台与标签/下拉相关的行为标准如下。本轮只参考行为语义，不照搬实现代码：

- GitLab 标签先按标签组抽取业务字段，再沉淀到 `module_name`、`bug_status`、`testing_phase`、`severity_level`、`category` 等事实字段。
- 老平台显式标签组包括：`模块`、`工具箱`、`软件`、`项目`、`状态`、`测试阶段`、`严重程度`、`类别`、`紧急程度`、`延期原因`。
- `模块` 和 `工具箱` 都属于模块来源。`模块：草图`、`工具箱：草图` 入库后的模块值都是 `草图`。
- 同一条数据同时存在 `模块：草图` 和 `工具箱：草图` 时，最终只保留一个 `草图`；如果分别是 `模块：草图` 和 `工具箱：曲线`，最终是多模块 `草图&曲线`。
- 多值字段使用 `&` 作为组合分隔符；展示和筛选时拆成独立业务值。
- 下拉选项来自已经沉淀的业务字段 distinct 值，再拆分、去重、过滤 `未设定...` 等默认占位值，而不是直接把任意 GitLab label 当选项。
- 模块筛选的目标语义是拆分后的模块精确匹配，不应该依赖宽泛字符串包含匹配，避免 `曲线` 误命中 `曲线曲面` 一类问题。
- 老平台对中文冒号支持更明确，部分路径也支持连字符；新平台应在保持语义一致的基础上补齐英文冒号 `:`、中文冒号 `：`，并兼容必要的历史分隔写法。

### 已确认根因

当前平台事实层 `issue_fact.module_names` 存在污染风险。`IssueLabelRules` 会从 GitLab labels 中排除部分已知非模块标签，但剩余未被排除的 label 会被当成模块。`ModuleDictionaryService` 如果没有命中字典规则，会保留原始 label。

因此 `9007`、`CC2023R3客户`、`前端`、`分支：发布` 等非模块值会进入模块维度，导致：

- 模块维度聚合对象错误。
- 分母/分子混入不同语义数据。
- Top 图、占比图、趋势图出现异常百分比。
- 真实模块数据被稀释，部分图表为空。

涉及代码：

- `backend/src/main/java/com/data/collection/platform/service/IssueLabelRules.java`
- `backend/src/main/java/com/data/collection/platform/service/IssueFactNormalizationRules.java`
- `backend/src/main/java/com/data/collection/platform/service/ModuleDictionaryService.java`
- `backend/src/main/java/com/data/collection/platform/service/FactBuildService.java`
- `backend/src/main/java/com/data/collection/platform/service/statistics/SystemTestDefectSummaryBoardService.java`

### 解决方案

1. 建立强约束模块识别规则。
2. 只允许以下来源进入模块字段：
   - 明确的模块前缀标签，输出时去掉前缀。当前必须支持 `模块:`、`模块：`、`module:`、`module：`、`工具箱:`、`工具箱：` 及同类前缀。
   - 前缀分隔符必须同时支持英文冒号 `:` 和中文冒号 `：`，例如 `模块:装配`、`模块：装配`、`工具箱:草图`、`工具箱：草图` 都应识别为模块，最终展示为 `装配` 或 `草图`。
   - 模块字典白名单命中的值。
   - 模块别名映射命中的值。
3. 模块与工具箱标签归一化规则：
   - `模块：X`、`模块:X`、`module：X`、`module:X`、`工具箱：X`、`工具箱:X` 最终都输出为 `X`。
   - 同一条议题里模块来源和工具箱来源值相同，只保留一次。
   - 同一条议题里模块来源和工具箱来源值不同，保留为多个模块值，展示和筛选时拆开处理。
   - 裸值 `X` 只有命中模块字典白名单或别名映射时才允许作为模块，不能因为它是一个 GitLab label 就直接进入模块维度。
4. 明确排除以下非模块标签：
   - 项目名、项目编号、客户名、版本号、轮次、分支、状态、优先级、严重级别、缺陷原因、测试阶段、平台标签。
5. 无法识别模块时不要进入业务展示维度，不能输出 `未识别模块`，也不能把原始 label 直接作为模块。
6. 对未识别模块的数据仅保留诊断信息，例如原始 labels、issue id、项目和轮次，用于后续完善标签组/字典，不进入主看板、系统测试汇总、集成测试模块列表和下拉选项。
7. 后续计划将 GitLab 标签做成可配置标签组，由用户组合出模块、轮次、状态、客户等业务维度；本轮不实现标签组能力，先按当前强约束模块方案修复。
8. 重建事实层后再校验看板百分比。
9. 看板侧补充百分比保护：
   - 分母为 0 时显示 `0%` 或 `-`。
   - 修复率类指标不得超过 100%，超过时视为数据异常并在诊断中暴露。
   - 占比类指标使用同一聚合范围的总数作为分母。

### 验证点

- 模块列表只出现工程、特征、装配、MBD、DWG、焊件等真实模块。
- 不再出现 `9007`、`CC2023R3客户`、`前端`、`分支：发布`。
- 不再出现 `模块：装配`、`模块:装配`、`工具箱：草图`、`工具箱:草图` 这种带前缀展示，最终展示值应为 `装配`、`草图`。
- 同一条数据里 `模块：草图` 和 `工具箱：草图` 不会重复统计为两个草图模块。
- 同一条数据里 `模块：草图` 和 `工具箱：曲线` 会作为两个模块分别参与展示和筛选。
- 修复率、占比类指标不超过合理范围。
- 空图表能区分“确实无数据”和“字段归一化失败”。
- 无法识别模块的数据不出现在模块 Top、模块汇总、系统测试缺陷汇总第一列和模块下拉中。

## 问题 3：集成测试详情议题编号显示 JSON，下拉选项普遍不对

### 现象

集成测试查看某一模块详情，例如 BOM，议题编号显示为 JSON 形式：

```json
{"label":"#21590","href":"http://..."}
```

同时多个页面下拉选项存在不符合业务语义的问题。

### 已确认根因

议题编号问题来自前后端单元格契约不统一。后端或前端构造了 `{ label, href }` 链接对象，但某些表格渲染路径把对象当普通文本显示。

涉及代码：

- `frontend/src/views/IntegrationTestAnalysisView.vue`
- `frontend/src/components/StatisticBoardDetailDialog.vue`
- `frontend/src/components/StatisticBoardDetailDialog.test.ts`

下拉选项问题与事实层字段污染同源。当前很多选项来自事实表 distinct 值，如果事实层模块、阶段、项目字段混入错误 label，下拉也会同步污染。

涉及代码：

- `backend/src/main/java/com/data/collection/platform/service/IntegrationTestQueryService.java`
- `backend/src/main/java/com/data/collection/platform/service/IntegrationTestFactBuildService.java`
- `backend/src/main/java/com/data/collection/platform/service/IntegrationTestFactRules.java`
- `frontend/src/views/IntegrationTestAnalysisView.vue`

### 解决方案

1. 统一表格链接单元格契约。
   - 方案 A：后端返回纯文本字段和 URL 字段，例如 `issueIid` + `issueUrl`。
   - 方案 B：前端所有表格统一识别 `{ label, href }`，不能落入普通文本渲染。
2. 对详情弹窗、统计下钻表、集成测试明细表统一加链接对象渲染测试。
3. 下拉选项改为按业务字段来源分层，保持老平台“业务字段 distinct 后再拆分去重”的语义：
   - 模块选项来自规范模块字典或归一化后的模块事实。
   - 轮次选项来自集成测试阶段字段。
   - 状态选项来自状态字段，严重程度选项来自严重程度字段，类别选项来自类别字段，延期原因选项来自延期原因字段。
   - 项目选项来自项目字段，不能混入模块、分支、客户、状态。
4. 所有下拉选项增加后端过滤规则：
   - 去空值。
   - 去 `未设定...` 等默认占位值，除非当前页面明确是非法数据诊断页面并需要展示缺失原因。
   - 去技术噪声和非本字段标签。
   - 去 label 前缀，只保留业务值。
   - 多值字段按 `&` / ` & ` 拆分后精确去重。
   - 按业务排序。
5. 模块筛选、状态筛选、原因筛选等都应以归一化后的字段值精确匹配为主，避免使用宽泛 `contains` 造成跨字段或相似词误命中。

### 验证点

- 议题编号显示为 `#21590`，点击可打开 GitLab 链接。
- 不再显示 JSON 字符串。
- 模块下拉不出现项目、客户、分支、状态。
- 状态、严重程度、类别、延期原因等下拉只出现各自业务字段值。
- 轮次下拉只出现 `2026R4` 等业务轮次。

## 问题 4：集成测试不应该有“全部项目”，应按轮次来

### 现象

当前集成测试页面有“全部项目”选项，也有项目维度选择。但期望是只按轮次/阶段来，例如 `2026R4`。

### 老平台语义参考

老平台集成测试页面更偏阶段/轮次驱动。核心聚合按 `testingPhase`，模块汇总、功能汇总、问题明细围绕当前轮次展开。

### 已确认根因

当前后端提供项目选项，前端也把项目作为主筛选条件。

涉及代码：

- `backend/src/main/java/com/data/collection/platform/service/IntegrationTestQueryService.java`
- `frontend/src/views/IntegrationTestAnalysisView.vue`

### 解决方案

1. 集成测试主筛选改为 `testingPhase`。
2. 移除前端“全部项目”作为主入口。
3. 项目仅作为明细中的来源字段或辅助过滤，不作为默认导航维度。
4. URL query 从 `projectId + testingPhase` 收敛为以 `testingPhase` 为主。
5. 后端接口提供明确的 phase options，默认选中最新轮次。

### 验证点

- 页面首次进入默认选中最新轮次。
- 页面无“全部项目”主选项。
- URL 可直接通过 `testingPhase=2026R4` 进入对应轮次。
- 模块汇总和详情都限定在当前轮次内。

## 问题 5：系统测试缺陷汇总第一列字段错误

### 现象

系统测试缺陷汇总第一列应该是模块，但实际出现：

- `9007`
- `CC2023R3客户`
- `前端`
- `分支：发布`
- 其他项目、客户、状态、分支类字段

期望第一列是纯模块名，例如：

- 工程
- 特征
- 装配
- MBD
- DWG
- 焊件

### 已确认根因

根因同问题 2：模块字段来自宽松 label 归一化，非模块标签进入 `issue_fact.module_names`。

### 解决方案

1. 优先修事实层模块归一化。
2. 系统测试缺陷汇总只消费规范模块字段。
3. 看板和表格展示时不拼接 `模块：`、`工具箱：` 等前缀；中英文冒号前缀都只用于识别，不进入最终展示值。
4. 对无法识别模块的数据不进入业务展示维度；仅在诊断入口列出原始 labels，便于后续完善字典或标签组配置。

### 验证点

- 第一列只显示纯模块名。
- 不显示 `模块：装配`、`模块:装配`、`工具箱：草图`、`工具箱:草图` 等带前缀值。
- 不显示项目、分支、客户、状态。
- 不显示 `未识别模块` 作为业务模块行。
- 模块筛选按拆分后的模块值精确命中，`草图`、`曲线`、`曲线曲面` 不互相误命中。
- 修改后重建事实层，历史污染数据被清理。

## 问题 6：全量补偿、自动补偿扫描、单表刷新调度语义问题

### 现象

全量补偿对账凌晨 2 点启动后长时间未完成。自动补偿扫描看起来会自行启动。全量补偿和自动补偿运行期间，单表刷新没有真正优先执行；页面却提示表格刷新完成，和同步日志中的排队状态冲突。

### 已确认事实

平台部署在 Ubuntu 服务器上，因此浏览器所在 PC 睡眠不会影响后端定时任务。定时任务由服务端 Spring Boot 进程执行。

涉及代码：

- `backend/src/main/java/com/data/collection/platform/service/GitlabCompensationScheduler.java`
- `backend/src/main/java/com/data/collection/platform/service/GitlabDailyVerificationScheduler.java`
- `backend/src/main/java/com/data/collection/platform/service/sync/SyncRunPolicyService.java`
- `backend/src/main/java/com/data/collection/platform/service/sync/SyncRunSubmissionService.java`
- `backend/src/main/java/com/data/collection/platform/service/sync/SyncRunDispatcherService.java`
- `backend/src/main/java/com/data/collection/platform/service/sync/SyncRunWorkerService.java`
- `backend/src/main/java/com/data/collection/platform/service/sync/SyncRunTableTaskExecutor.java`
- `frontend/src/components/DatabaseBrowserView.vue`

### 已确认根因

#### 6.1 单表刷新优先级只对未运行队列有效

`TABLE_REFRESH` 优先级高于补偿任务，但 dispatcher 只在挑选 `QUEUED` 任务时按优先级排序。已经 `RUNNING` 的全量补偿不会被抢占。

#### 6.2 全量补偿运行中，单表刷新会被复用，但不会真正插队

提交策略会把活跃的 `FULL_COMPENSATION_SCAN` 视为可复用 mirror run。用户提交单表刷新时，平台可能返回复用/合并结果，但不会创建新的高优先级表任务，也不会把目标表移动到当前补偿任务前面。

#### 6.3 自动补偿扫描的触发时间不可手动设置为固定时间点

当前自动补偿扫描使用固定延迟调度，每 60 秒检查一次：

```text
platform.gitlab-mirror.scheduler-delay-ms: 60000
```

是否触发由 `lastIncrementalSyncAt + compensationIntervalMinutes` 决定。也就是说，用户只能配置补偿间隔，不能像全量补偿那样配置“每天几点运行”或“允许运行窗口”。

全量补偿已有 `fullCompensationTime`，默认 `02:00`，但自动补偿扫描没有等价的手动时间设置。

#### 6.4 全量补偿缺少整体运行窗口和总时长上限

单次外部查询有超时配置，默认 120 秒，但整个 run 没有最大运行时长、夜间窗口截止、跨日保护或低峰窗口限制。

#### 6.5 取消是协作式取消

运行中的表任务只在读表前、读表后、写表后等检查点响应取消。如果当前外部查询或写入耗时较长，页面会显示取消中，但实际停止要等当前表任务返回。

#### 6.6 前端把“刷新请求已提交”误报为“数据已刷新”

数据库浏览页单表刷新接口返回后，前端直接提示“当前表数据已刷新”。但后端返回的只是提交状态，可能是 `QUEUED`、`DEDUPED` 或 `RUNNING`，并不代表数据已刷新完成。

### 解决方案

#### 调度配置

1. 为自动补偿扫描增加手动可配置时间策略。
2. 建议支持两种模式：
   - 间隔模式：每 N 分钟检查一次，保持当前能力。
   - 定时模式：每天指定时间运行，例如 `03:30`。
3. 增加运行窗口配置：
   - `compensationWindowStart`
   - `compensationWindowEnd`
   - `missedWindowPolicy`
4. 自动补偿扫描和全量补偿不能在同一来源上互相堆叠。若全量补偿正在运行，自动补偿应跳过或延后，并记录原因。

#### 单表刷新优先级

1. 单表刷新遇到正在运行的全量补偿/自动补偿时，不应静默复用为“已合并”。
2. 可选策略：
   - 策略 A：单表刷新排到补偿任务之后，但 UI 明确显示“排队中”。
   - 策略 B：支持补偿任务在表边界暂停，先执行单表刷新，再恢复补偿。
   - 策略 C：补偿任务运行中允许动态插入目标表任务，并提升目标表任务优先级。
3. 推荐先实现策略 A，快速消除状态误导；后续再评估策略 B/C。

#### 前端状态表达

1. 数据库浏览页刷新按钮文案改为“提交刷新请求”。
2. 返回 `QUEUED` 时提示“已提交，等待同步执行”。
3. 返回 `RUNNING` 时提示“已提交，正在同步”。
4. 返回 `DEDUPED` 时提示“已合并到现有同步任务，完成后生效”。
5. 只有确认对应表任务 `SUCCESS` 后，才能提示“当前表数据已刷新”。

#### 取消与超时

1. 页面上明确展示“取消中，正在等待当前表任务停止”。
2. 表任务诊断中展示当前表、运行时长、lease 信息、最近心跳。
3. 增加 run 级最大运行时长或窗口截止保护。
4. 长表任务可考虑更细粒度分片，减少取消等待时间。

### 验证点

- 自动补偿扫描时间可在 UI 设置，不再只能依赖固定间隔。
- 全量补偿运行中提交单表刷新，不显示“刷新完成”。
- 单表刷新在队列中能看到明确状态。
- 自动补偿不会在全量补偿运行时重复堆叠。
- 取消后页面能准确显示取消中、已取消或仍在等待当前表任务结束。

## 实施顺序建议

1. 修复模块归一化规则，重建事实层，解决看板、系统测试、集成测试和下拉选项的数据源污染。
2. 统一所有下拉选项的数据来源和过滤规则，确保模块、状态、阶段、严重程度、类别、延期原因等维度互不串字段。
3. 修复评审数据导入校验策略，允许默认字段为空。
4. 修复集成测试页面契约：移除“全部项目”，改为轮次/阶段主导。
5. 修复表格链接单元格渲染，避免议题编号显示 JSON。
6. 修复数据库浏览页单表刷新状态文案，先消除“刷新完成”的误导。
7. 增加自动补偿扫描的手动时间配置和运行窗口。
8. 调整补偿任务与单表刷新之间的队列/插队策略。
9. 增加 run 级超时、窗口截止、取消状态诊断。

## 建议测试范围

本轮修复完成后的验收标准不能只依赖命令返回。必须以用户视角完成页面级深度测试，并与重构前老平台在同一份数据输入下做结果对比。新平台的效果至少要达到 `D:\projects\spidergitdata-dev` 的功能语义水平。

### 后端

- `ReviewDataLegacyExcelImportService` 空默认值导入测试。
- `IssueLabelRules` / `IssueFactNormalizationRules` 模块白名单和排除规则测试。
- 标签组归一化测试：`模块`、`工具箱`、`软件`、`项目`、`状态`、`测试阶段`、`严重程度`、`类别`、`紧急程度`、`延期原因` 分别进入正确事实字段。
- 模块多来源测试：`模块：草图`、`模块:草图`、`工具箱：草图`、`工具箱:草图`、`模块：草图 + 工具箱：曲线`。
- 下拉选项服务测试：多值拆分、精确去重、过滤 `未设定...`、过滤跨字段污染标签。
- `FactBuildService` 重建事实层后的模块字段测试。
- `IntegrationTestQueryService` phase options 测试。
- `SyncRunSubmissionService` 在补偿运行中提交单表刷新的状态测试。
- `GitlabCompensationScheduler` 定时模式和间隔模式测试。
- `GitlabDailyVerificationScheduler` 与自动补偿互斥测试。

### 前端

- 评审数据导入弹窗预览和确认导入测试。
- 集成测试页面无“全部项目”测试。
- 议题编号 link cell 渲染测试。
- 系统测试缺陷汇总模块列 smoke 测试。
- 数据库浏览页刷新提交状态文案测试。
- 镜像设置页自动补偿时间配置表单测试。

### 数据验证

- 抽样检查 `issue_fact.module_names`。
- 抽样检查 `issue_fact.bug_status`、`issue_fact.testing_phase`、`issue_fact.severity_level`、`issue_fact.category`、`issue_fact.delay_reason`。
- 抽样检查 `integration_test_fact.testing_phase`。
- 抽样检查系统测试缺陷汇总第一列。
- 抽样检查各页面下拉选项，确认选项只来自对应业务字段。
- 对比旧平台语义检查模块缺陷汇总字段是否齐全。
- 将同一份 GitLab/评审/集成测试样例数据分别导入老平台和新平台，逐项对比模块汇总、系统测试缺陷汇总、集成测试模块汇总、问题明细、评审导入结果。
- 对比目标不是代码一致，而是用户可见结果、统计口径和页面交互语义一致或优于老平台。

### 用户视角深度测试

- 以普通用户/管理员视角登录并访问新平台所有主页面。
- 对每个页面执行真实用户路径：筛选、下拉选择、搜索、排序、分页、详情查看、下钻、导出、刷新、取消、异常提示。
- 对所有看板检查首屏、图表、表格、空状态、异常状态和详情弹窗。
- 对评审数据管理执行 Excel 上传、解析预览、默认值为空、确认导入、重复导入、列表查询、问题项查看。
- 对集成测试执行轮次切换、模块汇总、模块详情、议题链接跳转。
- 对系统测试执行缺陷汇总、议题查询、原因分析、延期缺陷、非法数据等页面路径。
- 对镜像设置执行状态刷新、同步日志、表级诊断、单表刷新、自动补偿配置、全量补偿配置、取消任务。
- 每个用户路径都要记录截图或测试证据；不能仅凭后端接口成功或测试命令通过判断功能正常。

## 待确认问题

1. 自动补偿扫描是希望“每天固定时间运行一次”，还是“每天多个时间点/时间窗口内按间隔运行”？
2. 单表刷新遇到正在运行的全量补偿时，是否允许自动暂停补偿任务，还是先只做明确排队展示？
3. 集成测试是否完全移除项目筛选，还是保留为高级过滤但默认隐藏？

## 2026-06-02 第六批修复记录

### 已实施
- 系统测试质量看板 `buildSystemTestRepairChartOption` 修复率图表改为优先解析 `displayValue` 中的百分比值。
- 系统测试多看板 `buildRepairRateChartOption` 修复率图表同样优先解析 `displayValue` 中的百分比值。
- 当 `displayValue` 不包含 `%` 或无法解析时，仍回退使用 `numericValue`，避免破坏后端已返回结构化百分比数值的场景。

### 已验证
- `npm test -- --run src/views/quality-board.test.ts src/views/system-test-multi-board.test.ts`：通过，覆盖 `numericValue=14569`、`displayValue=80.00%` 时图表数据为 `80`，并覆盖无 `%` 时回退 `numericValue`。
- `npm run typecheck`：通过。

## 2026-06-02 第七批修复记录

### 已实施
- 系统测试议题查询下拉新增历史脏模块值防御过滤，过滤 `9007`、`前端`、`分支：发布`、`CC2023R3客户`、纯数字和跨字段前缀值，避免旧 `issue_fact.module_names` 污染继续进入模块选项。
- 数据库浏览页单表刷新改为展示接口提交状态：`QUEUED` / `RUNNING` 提示刷新请求已提交，`DEDUPED` 提示已合并到现有任务，只有 `SUCCESS` 才提示当前表数据已刷新。
- 自动补偿扫描新增 `INTERVAL`、`DAILY_TIME`、`WINDOWED_INTERVAL` 三种调度模式，支持每日执行时间、运行窗口起止时间和错过窗口策略；旧配置默认保持 `INTERVAL`。
- 镜像设置页补充自动补偿模式、执行时间、运行窗口和错过窗口策略表单项，并纳入配置变更检测。
- 新增 Flyway 迁移 `V20260602_01__compensation_schedule_window_config.sql`，并同步 `schema.sql` 与本次迁移 checksum。

### 已验证
- `mvn -Dtest=SystemTestIssueSearchServiceTest,GitlabCompensationSchedulerTest,GitlabConfigServiceTest test`：通过，26 个测试覆盖系统测试下拉过滤、自动补偿每日定时/窗口内间隔调度和配置保存归一化。
- `npm test -- --run src/components/DatabaseBrowserView.test.ts src/views/system-test-issue-search.mount-smoke.test.ts src/views/mirror-settings.mount-smoke.test.ts`：通过，7 个测试覆盖刷新状态文案、系统测试查询页 smoke 和镜像设置页自动补偿配置展示。
- `npm run typecheck`：通过。
- `python scripts/check_flyway_destructive_migrations.py`：通过。
- `python scripts/check_schema_flyway_drift.py`：仍失败，但本次新增的自动补偿列已从 drift 中消除；剩余为既有 `gitlab_system_hook_events` / 旧同步表 / webhook 字段等历史差异。
- `python scripts/check_flyway_migration_immutability.py`：仍失败，但本次新增迁移已锁定；剩余为历史 `V20260506_02` changed 和 `V20260518_02`、`V20260519_01`、`V20260519_02`、`V20260522_01` unlocked。

## 2026-06-02 第八批修复记录

### 已实施
- 收口 Flyway/schema 历史残留：`check_schema_flyway_drift.py` 改为按迁移顺序计算最终结构，支持 `DROP TABLE`、`DROP INDEX`、表重命名、列删除、列重命名，以及同一 `ALTER TABLE` 中的多个 `ADD COLUMN`，避免把已删除旧同步表和已重命名 webhook 表误报为当前漂移。
- `schema.sql` 补齐当前最终存在的 sync orchestrator 基线表、索引和列，包括 `sync_runs`、`sync_run_table_states`、`sync_run_table_tasks`、`sync_run_events`、`sync_worker_leases`，以及后续迁移追加的 `parent_run_id`、`lookup_column`、`lookup_value`。
- `schema.sql` 补齐 `idx_review_records_gitlab_context`，与 `V20260513_01__review_data_gitlab_context.sql` 保持一致。
- 还原历史迁移 `V20260506_02__gitlab_sync_core_schema.sql` 中被误改的 `compensation_interval_minutes` 初始默认值为 `10`；最终默认值继续由后续 `V20260519_01__gitlab_sync_compensation_default.sql` 设置为 `360`，避免修改已锁历史迁移。
- 将已审阅但未锁定的 `V20260518_02`、`V20260519_01`、`V20260519_02`、`V20260522_01` 纳入 `flyway-migration-checksums.json`。

### 已验证
- `python scripts/check_schema_flyway_drift.py`：通过，当前为 23 张表、121 个索引、1 个扩展，字段集合一致。
- `python scripts/check_flyway_migration_immutability.py`：通过，25 个迁移均已锁定。
- `python scripts/check_flyway_destructive_migrations.py`：通过。
- `python scripts/check_flyway_destructive_migrations_test.py`：通过。
- `python scripts/check_flyway_profile_smoke_coverage.py`：通过。
- `python scripts/check_fact_field_contract.py`：通过。
- `mvn -q -Dtest=FlywayMigrationSmokeTest test`：通过。

## 2026-06-02 第九批修复记录

### 已实施
- 修复全量补偿运行中单表刷新被静默合并的问题：`TABLE_REFRESH` 遇到活跃 `FULL_COMPENSATION_SCAN` 时不再返回 `DEDUPED`，而是创建独立的 `QUEUED` 单表刷新 run。
- 保留同一 `exclusive_scope`，因此全量补偿已在运行时，单表刷新会明确排队等待；若全量补偿尚未开始且仍在队列中，单表刷新凭借更高优先级会先被 dispatcher 调度。
- 保留既有行为：单表刷新遇到普通全量同步仍可复用全量结果；相同表的重复单表刷新仍去重。

### 已验证
- `mvn -q -Dtest=SyncRunSubmissionServiceTest test`：通过，新增覆盖 `FULL_COMPENSATION_SCAN` 运行中提交 `TABLE_REFRESH` 时返回 `QUEUED`。
- `mvn -q -Dtest=SyncRunSubmissionServiceTest,SyncRunDispatcherServiceTest,DatabaseBrowserServiceTest test`：通过，覆盖提交策略、同 scope 排他调度和数据库浏览页刷新提交状态服务。

## 2026-06-02 第十批修复记录

### 已实施
- 镜像运行监控面板在 run 或当前表任务处于取消中时，新增“取消中，正在等待当前表任务停止”诊断区，展示当前表、运行时长、租约持有者和最近心跳，避免用户误以为取消请求没有生效。
- 表任务队列表将原来的“更新时间”拆成“最近心跳”和“租约到期”，直接暴露后端已有的表任务 lease/heartbeat 诊断字段，方便判断任务是否仍由 worker 持有。

### 已验证
- `npm test -- --run src/views/MirrorRunMonitorPanel.test.ts src/views/MirrorRunQueueTable.test.ts`：覆盖取消等待诊断、当前表、租约持有者、最近心跳，以及表任务队列中的心跳和租约到期时间展示。
- `npm run typecheck`：覆盖本批前端类型检查。

## 2026-06-02 第十一批修复记录

### 已实施
- 新增 `SyncRunDeadlineGuard`，对活跃同步 run 增加业务截止保护：超过全局最大运行时长、补偿 run 跨过日界，或窗口模式补偿 run 超过配置窗口结束时间时，主动写入 `cancel_requested=true` 并将 run 标记为 `CANCELLING`。
- 将 deadline guard 接入 `GitlabMirrorSyncService.recoverTimedOutTasks()`，让定时恢复循环除了 lease 超时外，也会定期扫描业务截止条件；已进入手工 `CANCELLING` 的 run 不会被重复覆盖取消原因。
- `SyncRunWorkerService` 在开始后、表任务规划后和表任务 drain 后都检查 deadline guard；命中后不再继续派发表任务，并保留 deadline 原因作为最终取消消息。
- 新增配置 `platform.gitlab-mirror.max-run-duration-minutes`，默认 `720`；新增 `platform.gitlab-mirror.cancel-compensation-runs-at-day-boundary`，默认 `true`。

### 已验证
- `mvn -q -Dtest=SyncRunDeadlineGuardTest,SyncRunWorkerServiceTest,GitlabMirrorSyncServiceTest test`：通过，覆盖最大运行时长、补偿跨日、补偿窗口截止、统一恢复链路接入，以及 worker 在 deadline 命中后停止派发表任务。

## 2026-06-02 第十二批修复记录

### 已实施
- 统计看板“进入页面自动刷新”从仅重新加载页面数据和实时状态，调整为首次进入和窗口重新聚焦时静默提交一次 realtime refresh，再刷新看板数据。
- 自动刷新复用手动“刷新最新数据”的等待链路，但不弹成功提示；仍保留 10 秒节流和用户关闭自动刷新偏好。

### 已验证
- `npm test -- --run src/composables/useStatisticBoardRefreshController.test.ts`：通过，覆盖手动刷新通知、自动刷新静默提交 realtime refresh、等待实时状态和重新加载看板数据。

## 2026-06-02 第十三批修复记录

### 已实施
- 自动补偿调度器在普通 `COMPENSATION_SCAN` 到期后，提交前先检查同 source 是否已有活跃 `FULL_COMPENSATION_SCAN`；命中时记录跳过原因，不再把普通自动补偿提交给底层复用逻辑。
- `SyncRunSubmissionService` 新增只读判断 `hasActiveFullCompensationRun`，按 `configId + sourceInstance + exclusiveScope + runType + activeStatuses` 查询活跃全量补偿，底层提交去重仍作为并发兜底。

### 已验证
- `mvn -q -Dtest=GitlabCompensationSchedulerTest,GitlabDailyVerificationSchedulerTest,SyncRunSubmissionServiceTest,SyncRunDispatcherServiceTest test`：通过，覆盖全量补偿活跃时自动补偿调度跳过、同 source 活跃全量补偿查询、每日全量补偿调度和同 scope 排他调度。

## 2026-06-02 第十四批修复记录

### 已实施
- 新增 `scripts/browser_route_smoke.py`，用 Python Playwright 打开真实 Vite hash 路由，并拦截 `/api/**` 返回最小成功响应，避免依赖本地后端或真实数据库。
- smoke 脚本逐页检查 `#app` 非空、页面尺寸、console error、page error 和 4xx/5xx 响应；每个路由使用独立 page，避免后台轮询串扰；失败时写入截图。
- 默认覆盖 28 个路由，包括质量看板、评审数据、代码走查、集成测试、系统测试、客户问题、镜像设置、数据库查看、外部采集表单和 404。

### 已验证
- `python scripts/browser_route_smoke.py --help`：通过，确认脚本入口可用。
- `python C:\Users\admin\.codex\skills\webapp-testing\scripts\with_server.py --server "cmd /c cd frontend && npm.cmd run dev -- --host 0.0.0.0 --port 18181" --port 18181 --timeout 90 -- python scripts/browser_route_smoke.py --base-url http://localhost:18181`：通过，28 个真实路由 smoke 全部通过，报告见 `.tmp/browser-smoke-20260602-140730/report.json`。

## 2026-06-02 第十五批修复记录

### 已实施
- 新增 `scripts/check_issue_fact_module_pollution.py`，用于现场只读检查 `issue_fact.module_names` 是否仍残留跨字段污染值。
- 检查脚本通过 `psql` 展开 `module_names`，识别纯数字、`前端/后端`、跨字段前缀、客户/版本样式值和 `未识别模块/缺失模块/未设定模块` 占位值，并输出可疑值、数量和样例 issue 引用。
- 脚本支持 `FACT_CHECK_DSN`、`DATASOURCE_URL`、`DATASOURCE_USERNAME`、`DATASOURCE_PASSWORD`、`DATASOURCE_SCHEMA` 和显式命令行参数；默认连接 `localhost:15432/qaflex`，并设置连接超时和非交互密码模式，避免现场检查卡住。

### 已验证
- `python scripts/check_issue_fact_module_pollution.py --help`：通过，确认脚本入口可用。
- `python scripts/check_issue_fact_module_pollution.py --print-sql`：通过，确认污染检测 SQL 可打印。
- `python -m py_compile scripts/check_issue_fact_module_pollution.py`：通过，确认 Python 语法有效。
- `python scripts/check_issue_fact_module_pollution.py --limit 20`：本机环境快速返回连接错误，说明当前 `localhost:15432/qaflex` 未提供可用无密码连接；现场需要配置 `DATASOURCE_PASSWORD` 或 `FACT_CHECK_DSN` 后复跑。

## 2026-06-02 第十六批修复记录

### 已实施
- 新增 `docs/real-chain-old-platform-comparison-ledger-20260602.md`，把真实链路、mock-only、unit-only、环境阻塞、未执行和老平台同批数据对比项统一登记。
- 台账明确 `PASS_SAME_DATA`、`PASS_REAL`、`PARTIAL_REAL`、`PASS_MOCK_ONLY`、`PASS_UNIT_ONLY`、`FAILED`、`BLOCKED_ENV`、`NOT_RUN` 状态口径，避免把 mock 路由 smoke 或单测误标为真实通过。
- 台账把 2026-06-02 最新修复后尚未真实复跑的项全部保留为 `NOT_RUN`、`BLOCKED_ENV`、`PARTIAL_REAL` 或 `PASS_UNIT_ONLY`，并记录后续统一跑通动作。
- 新增 `scripts/check_verification_ledger.py`，校验台账内每条状态表行都有合法状态；非真实通过项必须写清楚阻塞/说明和后续动作。

### 已验证
- `python scripts/check_verification_ledger.py`：通过，确认当前台账 24 个跟踪项均有明确状态和后续动作。
- `python -m py_compile scripts/check_verification_ledger.py`：通过，确认 Python 语法有效。
