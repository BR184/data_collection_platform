# 进度与中间物

- 状态：第二轮方案已按 AI 经理评审意见修正（v2）；R1 行为锁定已完成。**2026-09-02 实证核对（黄金基线工作单元交叉核查）：R2/R3/R4 的拆分件均已实现并接线（详见各阶段完成记录），实际进度领先本计划此前记录；R5 经用户决定暂不进行；R6 收口未执行**。行为一致性证据：golden 全链路套件在该工作树状态下两轮全新运行零差异（173/173，快照 229 个）。
- 评审修正要点：纳入 FactBuildTaskService/CodeReviewIllegalRecordSourceLoader/ReviewDataRecordReadRepository 三个遗漏目标；FactBuildService 拆分范围扩为全部行映射与读取路径；阶段日历双轨先锁行为再共享；Quality RD 拆为三边界；候选台账与第一轮 Tier B 清单对齐闭合。
- 第一轮解耦（阶段一至六）已完成并验证；基线：后端 qaflex_clean 隔离库全量 1169 项零失败，前端 122 文件/446 项全绿 + typecheck + build。

## 恢复线索

- 当前阶段：R2/R3/R4 代码完成待收口；R5 已暂停（用户 2026-09-02 决定暂不进行）；下一步为 R6 收口（红灯测试修正 + 全量验证 + 文档收口）。
- 恢复后首条命令：`git status --porcelain -- backend/src/main/java/com/data/collection/platform/service/statistics/`（已知例外：7 文件 11 行 import-only 删除属合并遗留，出现业务逻辑差异才停止）。
- 第一轮计划：`decouple-god-classes-20260821.md`（已完成）。

## 目标与边界

- 用户需求：继续项目解耦；方案必须先落文档、经 AI 经理评审修正后严格执行；不得影响任何现有模块功能。
- 明确禁止：
  - 修改 `service/statistics/**`（同事模块；import-only 遗留除外，且不得再新增任何改动）。
  - 触碰兼容模式冻结区：QualityBoardCodeReviewReadSupport、QualityBoardRdService、ReviewDataRecordQueryService 的兼容分支、CodeReviewDgmGitlabProjectOptionService、CodeReviewIllegalRecordService 编排层、前端老平台设置页。所有"兼容模式/MatchMode"注释原文保留，代码移动时随边界迁移。
  - 改变业务口径、公开 HTTP API、数据库迁移、事实发布代际、导出列序、快照键格式。
  - 为旧实现保留别名、转发或双轨路径。
- 本轮不做（评审确认）：IssueScopeDefinitionService、CustomerIssueRecordService、ReviewDataRecordPersistenceSupport、IssueIllegalRecordsPage、同步包已有租约/worker 边界的类——拆分收益不足。

## 约束与背景

- 后端 Java 21/Spring Boot 3.5/Flyway；本机无 mvn/java，验证走 Docker `maven:3.9-eclipse-temurin-21`（Windows 路径 `D:/...` + `-w //ws` 双斜杠）；前端 Node 24 本地。
- `FilterEngineSqlParityTest` 等 @SpringBootTest 需要 TEST_DATASOURCE_URL/USERNAME/PASSWORD 指向隔离库（此前用 qaflex_clean 通过）。
- 工作树含前序工作单元未提交变更（53 项），本单元不回退、不提交推送（除非用户指示）。
- 项目维护文本中文、标识符英文；公共方法补 Javadoc/JSDoc。

## 证据与根因（v2 调研结论）

### R-T1：FactBuildTaskService（851 行，评审 P1 新增）
- 结构事实：事实 advisory lock、任务入队、版本化目标分配（MAX_ASSIGNMENT_TASKS_PER_PASS=8）、租约认领与过期恢复、RETRY_WAITING→FAILED 重试链、运行汇总、状态持久化集中于单一 @Service；消费者为 FactBuildController、FactBuildService、FactRefreshTaskWorkerService、SyncFactRefreshRunExecutor。
- 测试保护实测：FactBuildTaskServiceTest 11 项已覆盖成功登记、来源隔离、失败回滚发布、跨连接可见性、锁互斥、去重认领、run 绑定、有界分配、租约恢复、重试状态机、空汇总——**编排行为保护已较强**；缺口是入队参数校验与 SKIPPED 分支语义。
- 结论：不拆结构，只补缺口测试后转为"已锁定"，作为后续一切事实层改动的锚点。

### R-FB：FactBuildService（1471 行，75 个方法，评审 P1 范围修正）
- 结构事实：除主 mapIssueFact(891)/mapMergeRequestFact(1103) 外，还有 4 处内联 lambda 行映射（354 客户问题开放议题、645/723/788 增量与定向加载、1192 MR 提交事实）、客户问题延迟刷新编排（255-291）、搜索索引修复（refreshIssueFactSearchIndexesInBatches）、阶段日历加载（859）。PhaseCalendarKey/Entry 是 private record 且在 IntegrationTestFactBuildService 中重复定义（421-430）。
- **阶段日历双轨实锤**：两处 loadPhaseCalendar SQL 相同但语义分叉——FactBuildService 按 phaseStartAt 降序排序取最新，Integration 版无排序取首见；Integration 版 Entry 有 matches(target) 区间判断方法而 Fact 版没有。共享前必须先用契约测试锁定两者各自语义，确认是否允许统一。
- 测试保护实测：FactBuildServiceTest 3 项（MR 来源裁剪、预检先行、run 绑定）+ FactBuildOperationGuardTest 8 项 + IntegrationTestFactBuildServiceTest 1 项 + FactRefreshTaskWorker/SyncFactRefresh 相关集成测试；**mapIssueFact 映射行为零直接测试**（OrchestrationTest 注释明确声明不覆盖）。

### R-CR：CodeReviewIllegalRecordSourceLoader（617 行，评审 P1 新增）
- 结构事实：FACT_SQL 与 ALL_EXPORT_FACT_SQL 两段 43 列 SELECT 字面量逐列重复（仅 where 不同）；buildPageQuery/buildFilterOptionQuery/buildAllExportQuery 三套查询构建 + appendXxx 条件族 + SORT_COLUMNS 白名单 + mapFactSource 43 列行映射 + toDouble/splitLabels 工具集中一类。不属于兼容模式（读正式表 code_review_formal_records）。
- 消费者：仅 CodeReviewIllegalRecordService（编排层，本轮冻结）经 activeLoader() 调用其 5 个公开方法——签名冻结约束同第一轮。
- 测试保护：CodeReviewIllegalRecordSourceLoaderTest 4 项。

### R-RD：QualityRdAnalyticsDetailQueryService（809 行，评审 P2 边界修正）
- 结构事实：SQL 构建（issueRows/appendExactFilter/appendSeverityFilter/issueScope）、内联 Map 行映射（issueRows 内 lambda）、内存排序分页聚合（page/compareValues/loadAggregateSources）、代码走查主题匹配（matchesCodeReviewTopic/codeReviewRecord）四种职责。
- 评审要求三边界：查询构建 / 行映射 / 内存分页排序分离；page+compareValues 是纯函数可直接外移。
- 测试保护：QualityRdAnalyticsDashboardProviderTest 8 项以 mock DetailQueryService 为主，DetailQueryService 本体缺直接单测——需先补 page/compareValues/matchesCodeReviewTopic 直测。

### R-RV：ReviewDataRecordReadRepository（657 行，评审 P1 恢复立项）
- 结构事实：基础 SQL（buildFilteredFromSql 窗口函数分页）、7 个公开查询入口、appendXxx 条件族（含 problemStatus/reviewExpert/filterGroup 特化）、mapRecordRow 行映射 + 密度/达标派生计算、索引修复检查（hasMissingSearchIndexes×2）、重复校验（existsDuplicateRecord）。
- 本体零 MatchMode 标记（兼容分支在 ReviewDataRecordQueryService 层），可安全单独拆分；3 个消费者（QueryService、PersistenceSupport.loadRecordPage、QualityBoardRdService.loadRecords）调用面窄。
- 测试保护：无直属测试类；依赖上层 QueryService 测试间接保护——拆分前必须补行映射与窗口 SQL 构建的直测。

### 台账闭合（评审 P2）
- 第一轮 Tier B 清单状态更新：FactBuildService → 本轮 R-FB；ReviewDataRecordReadRepository → 本轮 R-RV（上轮误移除，本轮恢复并说明）；BiCodingCalculator(944) → 维持 BI 强包内按核对表单独评估，不入平台普通解耦；新增观察项 LabelGroupDynamicRuleEvaluationService(644，规则序列化/校验/Join SQL 生成/聚合/执行五职责) → 下一批评估；BiCatMirrorRepository(720，配置/快照/租约/清理) → BI 单独立项。

## 方案与步骤（v2 执行序列，按评审建议顺序）

### R1：事实任务服务行为锁定【已完成】
1. 补 FactBuildTaskServiceTest 缺口：SKIPPED 分支语义、入队参数边界（非法 scope/full 组合）。
2. 全量跑 FactBuildTaskServiceTest 11+ 项确认既有行为锚点成立；不改生产代码。
3. 计划文档标记 R-T1 为"已锁定"。

验证结果：`FactBuildTaskServiceTest` 14 项全部通过（0 failure、0 error、0 skip）；测试预期按既有来源实例键契约将 `corp-x` 修正为权威形式 `corp_x`，生产代码未修改。R-T1 已锁定。

### R2：FactBuildService 及阶段日历（评审 P1 修正后的完整范围）

完成记录（2026-09-02 核实，接线证据）：`IssueFactSourceRowMapper`/`MergeRequestFactSourceRowMapper` 已抽并注入 FactBuildService，主映射与原 4 处内联 lambda 全部改为委托（`issueRowMapper.mapSource`、`mergeRequestRowMapper.mapSource/mapCommit`、`issueRowMapper.mapOpenCustomerIssue`）；`IssuePhaseCalendarLoader` 已按"不可合并则保语义"路径落地——共享同一 SQL 与 Entry，但保留两个选择方法（`selectForFactProjection` 保留降序取最新含 nullsLast 反转怪癖并注释、`selectForIntegrationTest` 保留首见优先），Entry 含 `matches` 区间判定；`FactSearchIndexRepairService` 已抽并接线。**R6 验收运行未执行。**
1. **阶段日历契约测试先行**：新建 PhaseCalendarContractTest 锁定两侧语义差异（排序取新 vs 首见优先、matches 区间判定 vs phaseStartAt 直取），把差异写成显式断言。
2. 若契约测试证明可统一：抽 `IssuePhaseCalendarLoader`（@Component：loadPhaseCalendar + Key/Entry 公共 record + 各自的解析策略参数）；若不可统一：保持双轨并在两处注释互相引用差异原因。以测试结果为准，不为合并而合并。
3. 抽 `IssueFactSourceRowMapperFactory` 或按读取路径拆分：主 issue 映射（mapIssueFact 一族）、客户问题开放议题映射（354）、增量/定向映射（645/723/788）、MR 主映射（mapMergeRequestFact）、MR 提交映射（1192 内联）各自成为明确协作者；每抽一个跑一次定向回归。
4. 抽 `FactSearchIndexRepairService`（refreshIssueFactSearchIndexesInBatches 一族）。
5. 客户问题延迟刷新编排（255-291）留服务本体（业务口径集中地，不外移）。
6. 验收：FactBuildServiceTest + OrchestrationTest + OperationGuardTest + IntegrationTestFactBuildServiceTest + FactRefreshTaskWorker 相关全绿；Checkstyle/SpotBugs 零违规。

### R3：代码走查正式查询器

完成记录（2026-09-02 核实）：`CodeReviewIllegalRecordSqlQueryBuilder` 已抽并经 `CodeReviewIllegalRecordSourceLoader` 接线；`CodeReviewIllegalRecordRowMapper`/`ExcelExporter`/`FilterOptionAssembler`/`ResponseMapper` 拆分件已存在于工作树。**R6 验收运行未执行。**
1. 合并 FACT_SQL 与 ALL_EXPORT_FACT_SQL 为单一列清单常量 + 两个 where 后缀（逐字符保真验证：拼接结果 diff 为空）。
2. 抽条件构建（appendXxx + buildPageQuery/buildFilterOptionQuery/buildAllExportQuery → CodeReviewIllegalRecordSqlConditionBuilder）与行映射（mapFactSource + toDouble/splitLabels → CodeReviewIllegalRecordRowMapper）。
3. SORT_COLUMNS/orderByClause 归入条件构建器。
4. 冻结约束：对 CodeReviewIllegalRecordService 的 5 个公开方法签名不动。
5. 验收：SourceLoaderTest 4 项 + CodeReviewIllegalRecordServiceTest 8 项全绿。

### R4：正式评审读取仓库

完成记录（2026-09-02 核实）：`ReviewDataRecordQueryBuilder` 已抽并经 `ReviewDataRecordReadRepository` 接线，`ReviewDataRecordRowMapper` 已抽（直测 `ReviewDataRecordReadSupportTest` 行映射 2 项通过）。**遗留 1 项红灯：`queryBuilderShouldPreserveFilterArgumentOrderAndWindowSortWhitelist` 期望值错误**——该测试期望关键字 `%Alice%`（保留大小写）且 `#41` 单候选，但既有行为是 `normalizeForMatch` 小写化 + `#41` 双候选（8 参数）；经逐字符对比，HEAD 版 `appendKeywordSearch` 与新构建器完全一致，且二者调用的 `ReviewDataSearchIndexSupport.keywordCandidates` 为已提交未改动的共享类——**生产行为零变化，测试期望值描述的是从未存在过的契约**。用户 2026-09-02 确认行为一致契约：修正方向 = 把测试期望值改为实际既有行为（归 R6）。
1. 先补直测：mapRecordRow 行映射（含密度/达标派生）、buildFilteredFromSql 窗口 SQL、buildWindowOrderBy 白名单——以 mock JdbcTemplate 捕获或 H2 不适用则用 Testcontainers PG（项目已有先例）。
2. 抽 ReviewDataRecordRowMapper（行映射 + 派生计算）、ReviewDataRecordConditionBuilder（appendXxx + SqlParts）。
3. hasMissingSearchIndexes/existsDuplicateRecord 保留本体（薄查询，移动无收益）。
4. 兼容约束：QueryService/PersistenceSupport/QualityBoardRdService 三个消费者的调用面不变；QualityBoardRdService 属兼容冻结区只读不改。
5. 验收：新建直测 + ReviewDataRecordQueryService 上层测试全绿。

### R5：Quality RD 查询三边界拆分【已暂停（用户 2026-09-02 决定暂不进行）】
1. 补 page/compareValues/matchesCodeReviewTopic 直接单测（纯函数，无需 mock）。
2. 抽 `AnalyticsDetailPageSupport`（page+compareValues 纯函数）、`QualityRdIssueRowMapper`（issueRows 内联映射）、查询构建留服务本体或抽 Builder（不含分页排序，遵评审边界）。
3. matchesCodeReviewTopic/codeReviewRecord 与 ILLEGAL_CODE_REVIEW_PEORY 集合聚为 `QualityRdCodeReviewTopicMatcher`。
4. 验收：Provider 8 项 + 新增直测全绿。

### R6：收口【未执行；待办清单 2026-09-02 更新】
1. 修正 R4 红灯测试 `ReviewDataRecordReadSupportTest.queryBuilderShouldPreserveFilterArgumentOrderAndWindowSortWhitelist` 期望值为实际既有行为（`%alice%` 小写候选、`#41` 双候选 8 参数）——依据见 R4 完成记录与用户 2026-09-02 行为一致契约确认。
2. Docker 定向回归（R2/R3/R4 上述全部测试类）+ Checkstyle/SpotBugs。
3. 隔离库全量后端测试（TEST_DATASOURCE_URL 注入隔离 schema；含同事金标与 FilterEngineSqlParityTest）；前端不受影响则抽查 typecheck。
4. 仓库门禁四项 + `git diff --check`；更新本计划与 progress.md。
5. 内网部署验收前跑一次 golden 全链路（`mvn test -Pgolden-baseline -Dtest=GoldenBaselineChainTest`）确认零差异；内网测试通过后按 decisions.md D-08 以 `-Dgolden.update=true` 重冻结基线升级为可信基线。

## 决策记录

- 已采纳评审执行顺序：R-T1 锁定 → R2 事实构建器及阶段日历 → R3 走查正式查询器 → R4 评审读取仓库 → R5 Quality RD → R6 收口；标签组与 BI 单独评估不在本轮。
- 2026-09-02 用户决策：R5 未开始，暂不进行；解耦验收契约确认为"产出与解耦前完全一致"，R4 红灯测试据此定性为期望值错误并归入 R6 修正。当前基线快照（golden）对应含 R2-R4 拆分的工作树，两轮零差异即为行为一致性的输出级证据。
- 已决：阶段日历先契约测试后决定是否共享——测试证明语义等价才合并，否则显式双轨加注释；禁止无证据统一。
- 已决：FactBuildService 拆分覆盖全部 6 处行映射与读取路径（评审 P1-2），不只主映射。
- 已决：R3 期间 CodeReviewIllegalRecordService 编排层冻结（兼容模式相关），只动 SourceLoader 内部。
- 已否决：本轮拆 LabelGroupDynamicRuleEvaluationService 与 BiCatMirrorRepository（分别列入下一批观察与 BI 单独立项）。
- 继承第一轮决策：桥接测试删除而非迁移；statistics 包 3 个 repository 签名冻结；兼容分支不抽象。

## 接口契约

- 不新增对外 HTTP API、表结构、数据格式；无数据库迁移。
- 冻结签名：CodeReviewIllegalRecordSourceLoader 5 个公开方法；ReviewDataRecordReadRepository 对 3 个消费者的现有方法集；FactBuildTaskService 对 4 个消费者的 runGuarded/入队/认领/汇总接口；statistics 包对 IssueFactRecordRepository 的 3 方法（继承）。
- 内部接口破坏性调整须同轮更新全部调用者与测试并删除旧路径。

## 风险与假设

- FactBuildService 行映射抽取的 ResultSet 列名与派生口径必须逐字段保真；以 OrchestrationTest + 新增映射直测双保险。
- 阶段日历若测试证明语义不可统一，接受双轨存续并文档化——正确性优先于 DIP。
- R4 无既有直测，Testcontainers 在容器内嵌套 Docker 不可用时降级为 mock JdbcTemplate 捕获 SQL 断言（第一轮已有同型先例）。
- 全程不 commit/push；工作树前序变更保留。
