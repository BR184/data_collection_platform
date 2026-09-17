# BI 编码页静态扫描图表下线 + BI 看板改动测试与黄金基线回归更新方案

> **文档版本**：v1.2（2026-09-15，工作单元 SA 代码/测试/文档实施并本地验证全绿；黄金基线回归套件已跑完：更新模式重建 coding 快照并逐项审阅=6.4 八项预期、compare 复跑 coding 全绿，仅余 2 例 statistic-boards 他单元既有漂移）
> **文档性质**：可交接执行的工作单元方案（出具方只负责方案与验证，执行由用户指定的另一 AI 完成）
> **权威依据**：用户裁定（静态扫描数据太薄、先下线、PMD-Biome 启用后重建）+ 本仓源码/快照实证 + `AGENTS.md` 黄金基线纪律与开发期演进红线 + P0-5 频次图表下线先例
> **审批后处置**：用户批准 → 交执行 AI 按本方案落地 → 出具方按第十部分验证清单复核

---

## 进度与中间物

- **状态**：工作单元 SA（静态扫描图表下线）代码/测试/文档已全部实施并本地验证；黄金基线回归套件已跑完（见下），coding 快照重建并审阅通过。待用户裁定提交面。
- **恢复线索**：当前阶段=SA 代码完成待提交 + 黄金基线待裁定；恢复后首条命令=`git status`（工作树混他人在途改动，只提交本单元文件）；关联计划=`docs/plans/bi-intranet-two-defects-solution-20260914.md`（密度 v4）、`docs/plans/bi-chart-adaptive-axis-tail-break-20260914.md`（重尾值轴）。
- **已完成文件/变更清单**：后端 5 生产（`BiCodingSource`/`BiCodingPageData`/`BiCodingCalculator`/`BiPlatformCodingSourceAdapter`/`BiDownloadAuthorizationService`）+ 3 测试（`BiCodingCalculatorTest`、**额外连带**`BiPlatformSourceAdapterTest`——方案未逐一列出但该测试断言 `scanDataAvailable`+mock `scan_status`，必须同步）、`endpoint-catalog.yml` 注释；前端 `CodingStageContent.vue`（含三卡等宽重排）/`types.ts`/`chart-explanations.ts`/`CodingStageContent.test.ts`；文档 `decisions.md` D-12、核对表 CD-36/37、product/architecture/data-contracts/两份 progress。
- **测试状态**：后端定向 34/34（BiCodingCalculatorTest 23/BiPlatformSourceAdapterTest 10/BiDownloadAuthorizationServiceTest 1）、后端全量默认套件 **1351/1351** BUILD SUCCESS（1 环境跳过）；前端 BI 定向 81/81、eslint 0 错；三项仓库门禁与 `git diff --check` exit 0。前端全量 vitest 1 失败为 `customer-issue-records.mount.test.ts` 计时敏感冒烟（隔离复跑 7/7 绿，与本单元无关）；typecheck/build 3 错均集中在他人在途文件 `SystemTestStageContent.test.ts`（'FAILED' 不属 BiDataStatus，红线不触碰），本单元所改文件零类型错。
- **黄金基线回归（已完成，v1.2 更正归因）**：先前 v1.1 记的“受阻”经实测推翻。真因：（a）case[88] 120s 超时的是 `code-review/illegal-records/export` **Excel 导出**（`getBytes`），**非** BI coding JSON；（b）动态测试彼此独立，该 case 失败不中断其余 190 例，coding 快照其实已写盘；（c）更新模式后 68 个快照 M 绝大多数是 CRLF-vs-LF（`* text=auto eol=lf`）+ 未掩码易变字段（lastSyncedAt 同步时刻）+ JSON 键序跨环境抖动，compare 期被掩码容忍。**处置**：给 `GoldenBaselineSupport.getBytes` 单独加 `EXPORT_READ_TIMEOUT`（仅二进制导出，JSON 端点 120s 契约不变）→ 更新模式 191/191 零 error 跑完 → coding 快照重建，逐项审阅 diff **恰为 6.4 八项预期**（ruleVersion v3→v4、static-scan 分区删、scanTrend/scanCoverage 删、traces 去 scan_status/scan_bug_count 与 CD-36/37、reviewDensityTrend 由空变合法子集），无意外语义变更；含 scan 列的 `code-review/get___illegal-records__cc-page1.json` 等列表快照**未进改动集=零 diff**（记录页与 DB 列保留验证通过）。去更新模式 **compare 复跑：191 例、coding 全绿**，仅 2 例 statistic-boards 失败（case6 numericValue 期望 0 实为 null = “无数据须 null”改动、case12 board version v13→v14），**均为其它单元已提交但未回灌基线的既有漂移，非本单元回归**，依“禁止用更新模式掩盖无意差异”未在本单元吞入，其 68-2=66 噪声快照已 `git restore`。当前工作树快照区仅剩 `bi/get___coding__day-all.json` 一处待提交。

---

## 一、目标与边界

### 用户原始需求
1. **下线 BI 编码页"静态代码扫描结果"图表**：现有静态扫描数据只有 `scan_status`（三态）+ `scan_bug_count`（计数）两项，太薄；待 PMD-Biome 正式启用后再以结构化分级数据重建上线。**将该裁定落到文档**。
2. **针对本批 BI 看板改动出具测试方案 + 回归测试套件（黄金基线）测试与更新方案**：用户审批后交另一 AI 执行，出具方只负责方案与验证。

### 可验证的成功标准
- 编码页不再渲染"静态代码扫描结果"卡片，其余卡片布局无空洞/错位。
- BI 生产代码（前后端）零残留静态扫描供数符号；`/api/bi/coding` 产出不再含 `scanTrend`/`scanCoverage`/`static-scan` 分区/CD-36-37 溯源。
- 共享图表类 `StackedCategoryBarChart`、DB 列 `scan_status`/`scan_bug_count`、代码走查记录页（illegal-records）均**不受影响**。
- 后端/前端定向+全量测试全绿；黄金基线更新模式重建后人工审阅 diff 仅含预期变更，复跑零差异。
- 裁定与口径变更落入 `decisions.md`（D-12）、核对表（CD-36/37）、BI product/architecture/data-contracts、progress 文档。

### 明确禁止的行为（红线）
- **禁止删除共享构件**：`StackedCategoryBarChart.ts`、`CategorySeriesData`、`sortCategorySeriesData`、`BiChartTemplateId` 联合类型中的 `'stacked-category-bar'` 成员——SystemTest"模块缺陷级别"图复用它们。
- **禁止改动 `chart-contract.test.ts` 的 templateId 计数（14）与 `excel-table.test.ts` 的图表类计数（15）**——类未删除，计数不变（这是与 P0-5 频次图表下线的关键差异：频次图有专属类 `SubmissionFrequencyBarChart` 故删类+改计数；静态扫描复用共享类故不删类、不改计数）。
- **禁止删除或改写 DB 列** `code_review_formal_records.scan_status/scan_bug_count`、`code_review_match_mode_records.scan_status/scan_bug_count`——代码走查记录页合法消费，黄金基线 `snapshots/code-review/get___illegal-records__*.json` 含这些列。只移除 **BI 编码适配器**对这两列的 SELECT。
- **禁止触碰代码走查记录页**（illegal-records）及其快照、`label-groups` 快照中的 `scanStatus`/`scanBugCount` 记录列。
- **禁止改其它 BI 页面/端点**（requirements/design/system-test/unit-test/integration-test/versions）。
- **禁止用更新模式掩盖无意差异**：黄金基线 diff 若含第六部分审阅清单之外的变更，一律视为回归，停下修复实现而非改快照。
- **禁止手工编辑快照文件**：只能由 `-Dgolden.update=true` 更新模式生成。
- **禁止越界改动他人在途文件**（如 `SystemTestStageContent.test.ts` 的在途 typecheck 错误）。

---

## 二、约束与背景

- **技术栈**：后端 Java 21 + Spring Boot + MyBatis-Plus + JUnit5/Mockito/AssertJ；前端 Vue 3 + TS + Vite + Element Plus + ECharts 6.1 + Vitest。
- **Maven**：`../tools/maven/apache-maven-3.9.9/bin/mvn.cmd`（backend 目录下执行）；Windows 脚本统一 `pwsh.exe`。
- **端口**：后端 18080、前端 18181；本地 PostgreSQL `127.0.0.1:15432/qaflex`。
- **黄金基线纪律**（`AGENTS.md` + `docs/decisions.md` D-08）：全链路约 7 分钟（双 PostgreSQL 容器 + 从零建库 + 真实全量链路 + 全端点比对）；日常开发严禁主动跑；仅①发布打包/内网部署验收前、②用户明确要求两种时机运行。**本方案属"用户明确要求"的产出更新，授权运行**。运行前后必须停/起本机开发服务。
- **开发期演进红线**（`AGENTS.md`）：直接改目标版本，删除被替代实现，搜索确认旧符号不再被引用，不留别名/回退/双轨/死代码。
- **P0-5 先例**（`docs/plans/bi-dashboard-feedback-and-fixes-comprehensive-20260910.md` 8.4/8.7）：频次图表下线标准=卡片＋图表类＋templateId＋词条＋后端白名单＋测试一次性清理干净，全库零残留。本方案沿用该标准，但因静态扫描复用共享图表类且后端有真实计算，移除集与计数处理不同（见红线）。
- **文档路由**（`docs/bi-dashboard/README.md`）：图表清单/产品语义→`product.md`；数据来源映射→`architecture.md`；事实族与完整性→`data-contracts.md`；口径公式权威→`BI看板数据来源与计算口径核对表.md`；平台级决策→`docs/decisions.md`；BI 决策→`docs/bi-dashboard/decisions.md`（如存在，否则归 `docs/decisions.md`）；工作状态→`progress.md`。

---

## 三、证据与根因（取证结论，均经源码/快照实证）

### 3.1 静态扫描图表现状与"数据太薄"根因
- 前端 `CodingStageContent.vue` L165-184 `scanData`：按 `point.status`（扫描状态字符串）分组，仅产出两个系列——"扫描记录数"（计数）与"问题总数"（`bugCount` 求和）。**没有严重级别、分类、规则、文件、模块任何维度**。
- `docs/bi-dashboard/product.md` L48 声称"静态扫描按扫描日期和阻断/严重/一般/提示堆叠"——**与实际实现不符**：实现只按扫描状态分组，从未支持阻断/严重/一般/提示四级堆叠。这正是 SURVEY-01 调研认定的"量纲混淆硬伤"（`docs/plans/bi-dashboard-feedback-issues-and-chart-tips-20260910.md` L81-88）。
- 数据源仅 `scan_status`（未进行/已进行等三态）+ `scan_bug_count`（整数），来自老平台 SonarQube 镜像；PMD-Biome 尚未接入（全库对 `pmd_biome`/`external_tool_comments`/`onepanel` 零引用，见 `bi-dashboard/progress.md` ③）。
- **结论**：现有图表无法承载静态扫描的核心价值（分级/分类/规则/趋势），下线合理；PMD-Biome 以结构化 `issues[]`（severity/category/rule/文件行列/中文 message/GitLab 深链）接入后重建。

### 3.2 后端供数链（BI 编码页专属，自包含）
- `BiCodingSource.java`：`scanDataAvailable`（L21）、`CodeReviewRecord.scanStatus/scanBugCount`（L95-96）。
- `BiPlatformCodingSourceAdapter.java`：两段 SQL 各 SELECT `scan_status, scan_bug_count`（L53-54 正式态、L84-85 兼容态）、reader（L213）、`scanDataAvailable` 计算（L144-146）与传参（L174）、行映射（L247-248）、内部 record 字段（L326-327）。
- `BiCodingCalculator.java`：`scanFacts`（L78-79）、`scanValues`（L162-164）、`scansComplete`（L168-169）、`coverageSection("static-scan",…)`（L208-209）、`scanPoints(scanValues)`（L247）、`scanCoverage`（L252）、`empty()` 的 scanTrend/scanCoverage（L269-270）、`emptySections()` 的 static-scan（L294）、`scanPoints()` 方法（L374-379）、`scanFact()`（L714-715）、`ScanFact` record（L897）、`validScanInputs()`（L752-753）、traces 的"静态扫描状态/问题数"两个 SourceField + CD-36/CD-37（L633、L640-647）。
- `BiCodingPageData.java`：`scanTrend`（L16）、`scanCoverage`（L21）、copy（L30）、`ScanPoint` record（L69-74）。
- `BiDownloadAuthorizationService.java`：`PAGE_TEMPLATES` 的 **"coding"** 集含 `"stacked-category-bar"`（L17）；**"system-test"** 集也含（L22，保留）。
- **关键边界**：`scanStatus()`/`scanBugCount()` 在 BI 包内**仅**被 `scanPoints`/`scanFact`/`validScanInputs`/`scanDataAvailable` 消费，无其它 BI 计算依赖；DB 列被代码走查记录页（非 BI）消费，故列保留、只删 BI 适配器 SELECT。

### 3.3 前端消费链
- `CodingStageContent.vue`：`scanChart = new StackedCategoryBarChart()`（L54）、`scanSort/scanSortOrder`（L65-66）、`scanSortOptions`（L84）、`scanData`（L165-184）、template"静态代码扫描结果"`<BiChartPanel>`（L260-278，layout=`wide`）、import `StackedCategoryBarChart`/`sortCategorySeriesData`/`CategorySeriesData`。
- `data/types.ts`：`scanTrend`（L126）、`scanCoverage`（L148）。
- `data/chart-explanations.ts`：`codingScanResult`（L18）。
- `stages/CodingStageContent.test.ts`：mock `scanTrend: []`（L42）、`scanCoverage`（L47）。
- **共享构件（保留）**：`StackedCategoryBarChart`（SystemTest `moduleSeverity` L140 复用）、`CategorySeriesData`/`sortCategorySeriesData`（SystemTest 复用）、`'stacked-category-bar'` templateId（SystemTest 白名单 + 联合类型）。

### 3.4 黄金基线现状（`snapshots/bi/get___coding__day-all.json`，实证）
- `ruleVersion: "bi-coding-v3"`（L10）——快照仍是 v4 之前。
- `static-scan` 分区存在（L58）；`scanTrend: [ {…} ]` 有数据（L2499 起）；`scanCoverage: {…}`（L56675）。
- `reviewDensityTrend: [ ]`（L56674）——**空数组，正是密度"全有全无置空"缺陷的快照铁证**；密度覆盖率语义（v4）重建后应变为合法子集数值。
- traces 含 `scan_status`/`scan_bug_count` 的 englishName（L135/L138）。
- 目录 `endpoint-catalog.yml`：`/api/bi/coding` 为 READ、case `day-all`、`ignore-paths` 仅掩 `generatedAt/sourceVersion/snapshotId`；L1491 注释明确"数据本体（sections/traces/**scanDate**/reviewDate/observedOn/metrics）全量参与比对"——故 scanTrend/scanDate 是被比对数据，移除必触发 diff。
- **不受影响快照**：`snapshots/code-review/get___illegal-records__cc-page1.json`（L29-30 `scanStatus`/`scanBugCount` 记录列）、`get___illegal-records_rule-explanation__default.json`（L38）、`label-groups/get___dynamic-rule-sources__default.json`（L641）——DB 列保留，这些快照应零 diff。

### 3.5 本批 BI 改动清单（"一些修改"，需测试）
1. **密度覆盖率语义（`bi-coding-v4`，后端，已实现未提交）**：`BiCodingCalculator` 删全有全无门槛改覆盖率语义、`validDensityTrendInputs` 增 MR 身份要求、`ReviewAccumulator` null 工时安全、quality-trend 改 `coverageSection` 披露、`RULE_VERSION` v3→v4。`/api/bi/coding` 产出变更（`reviewDensityTrend` 由空变合法子集）。
2. **重尾值轴（前端，已实现未提交）**：`adaptive-value-axis.ts` 重写为百分位主体+单段尾部断轴；纯前端渲染，**不改后端 JSON 产出，不触发黄金基线快照变更**；影响 `chart-contract.test.ts`/`adaptive-value-axis.test.ts` 断言与图表渲染。
3. **静态扫描下线（本方案 A，待实现）**：后端产出移除 scanTrend/scanCoverage/static-scan/CD-36-37；前端移除图表。

> 改动 1 与 3 都改 `/api/bi/coding` 产出，且都尚未重建快照——**黄金基线一次更新模式重建即可同时锁定两者**（见第六部分）。改动 2 不进快照。

---

## 四、方案 A：静态代码扫描图表下线（工作单元 SA）

> 执行顺序建议：后端移除 → 前端移除 → 测试更新 → 文档更新 → 定向测试 → （并入第六部分黄金基线重建）。

### A.1 后端移除集（精确到符号，全部在 `bi` 包内）
1. `bi/domain/model/BiCodingPageData.java`：删 record 组件 `scanTrend`（L16）、`scanCoverage`（L21）、compact 构造器内 `scanTrend = copy(scanTrend)`（L30）、`ScanPoint` record（L69-74）。
2. `bi/domain/source/BiCodingSource.java`：删 `scanDataAvailable`（L21）、`CodeReviewRecord` 的 `scanStatus`/`scanBugCount`（L95-96）。
3. `bi/infrastructure/BiPlatformCodingSourceAdapter.java`：删两段 SQL 的 `scan_status, scan_bug_count`（L53-54、L84-85，注意保持 SQL 逗号合法）、reader 对 `scan_bug_count` 的读取（L213）、`scanDataAvailable` 计算（L144-146）与构造传参（L174）、行映射 `row.scanStatus()/row.scanBugCount()`（L247-248）、内部 record 字段（L326-327）。
4. `bi/domain/BiCodingCalculator.java`：删 `scanFacts`（L78-79）、`scanValues`（L162-164）、`scansComplete`（L168-169）、`coverageSection("static-scan",…)`（L208-209）、`scanPoints(scanValues)` 实参（L247）、`coverage(scanFacts…)` scanCoverage 实参（L252）、`empty()` 中 scanTrend/scanCoverage 对应实参（L269-270）、`emptySections()` 的 static-scan 行（L294）、`scanPoints()` 方法（L374-379）、`scanFact()`（L714-715）、`ScanFact` record（L897）、`validScanInputs()`（L752-753）、traces 中"静态扫描状态""静态扫描问题数"两个 `SourceField`（L640-647）及该 trace 组 cdIds 列表中的 `"CD-36","CD-37"`（L633）。
   - **注意**：`BiCodingPageData` 构造器实参顺序须与删字段后的 record 组件顺序严格一致（scanTrend 在 moduleIncrements 与 moduleReviewQuality 之间、scanCoverage 在 reviewDensityTrend 之后）；`empty()` 的 `List.of()` 占位数量同步减一。
5. `bi/application/BiDownloadAuthorizationService.java`：从 `PAGE_TEMPLATES` 的 **"coding"** 集删除 `"stacked-category-bar"`（L17）；**"system-test"** 集（L22）保留不动。

### A.2 后端 RULE_VERSION 决策
- **保持 `RULE_VERSION = "bi-coding-v4"`，不升 v5**。理由：v4 尚未提交、未发布、无外部消费者；静态扫描下线并入同一未发布 v4 变更集，避免版本号虚增（符合开发期"直接改目标版本"红线）。生产环境当前缓存为 v3，发布 v4 时自动失效重建。
- 前端 `CodingStageContent.test.ts` fixture `ruleVersion: 'bi-coding-v4'`（L12）无需改动。

### A.3 前端移除集
1. `stages/CodingStageContent.vue`：
   - 删 `scanChart`（L54）、`scanSort`/`scanSortOrder`（L65-66）、`scanSortOptions`（L84 起整块）、`scanData` computed（L165-184）。
   - 删 template"静态代码扫描结果"`<BiChartPanel>`整块（L260-278）及其上方注释 L241 改写。
   - 删孤立 import：`StackedCategoryBarChart`、`sortCategorySeriesData`、`CategorySeriesData` 类型（确认本文件移除 scanData 后不再引用它们；`reviewCategories` 用 `NamedValue` 不受影响）。
2. `data/types.ts`：删 `BiCodingPageData` 接口的 `scanTrend`（L126）、`scanCoverage`（L148）。
3. `data/chart-explanations.ts`：删 `codingScanResult`（L18）。
4. **布局重排（关键，避免空洞）**：宽屏 12 列网格下 `compact`=span 4、`wide`=span 8（`BiChartPanel.vue` L378-379）。原行2=问题分布(compact 4)+静态扫描(wide 8)=12。移除 span-8 后须重排：
   - **推荐方案（决策完整，首选）**：行2 改为**三卡等宽**——代码走查问题分布(compact 4) + 开发人员代码贡献(compact 4) + 各模块代码增量(**由 wide 改 compact** 4)=12。即把 `各模块代码增量` 的 `layout="wide"`（L308）改为 `layout="compact"`，并将其与"开发人员代码贡献""代码走查问题分布"同处一行；更新对应注释（L241、L280）。三张卡（环形图 + 两个竖向柱图）高度均 356，1/3 宽可读。
   - **备选方案（仅当浏览器验收认为三卡不平衡时）**：行2=代码走查问题分布(**由 compact 改 wide** 8)+开发人员代码贡献(compact 4)；各模块代码增量(wide 8)与代码增量趋势(wide 8)另行配对——此方案改动面更大，非首选。
   - 最终布局以浏览器截图验收为准（见第十部分）。

### A.4 测试更新
**后端 `BiCodingCalculatorTest.java`**：
- `returnsEveryFrontendSectionContractWhenCodingSourceIsEmpty`（L17-32）：sections contains 断言删 `"static-scan"`（L30）。
- `exposesSeparatedPhysicalSourcesInTrace`（L127-152）：traces contains 删 scan_status englishName 行（L147）。
- `omitsDateBasedReviewSeriesWhenReviewDateIsMissing`（L259-281）：删 `assertThat(response.data().scanTrend()).isEmpty()`（L278）。
- **构造器连锁**：`CodeReviewRecord` 删 `scanStatus/scanBugCount` 后，所有构造该 record 的测试 helper（如 `review(...)`）与内联构造（如 L261-265 含 `"SUCCESS_WITH_ISSUES", 2L`）必须同步删除这两个实参；逐一编译修正。
- 全文件搜索 `scan`/`Scan`/`static-scan` 确认无其它专用断言残留。

**后端 `BiDownloadAuthorizationServiceTest.java`**：核查是否存在 coding+`stacked-category-bar` 的**正向**授权断言；若有则删除/改为断言该模板在 coding 页被拒（L31 现为 system-test 负向用例，不受影响）。

**前端 `CodingStageContent.test.ts`**：
- mock 删 `scanTrend: []`（L42）、`scanCoverage`（L47）。
- 在 test 1（L55-107）增加回归断言：`expect(panels.find((p) => p.props('title') === '静态代码扫描结果')).toBeUndefined();`（对标既有频次图表 removed 断言 L71-77）。

**前端 `chart-contract.test.ts` / `excel-table.test.ts`**：确认 `StackedCategoryBarChart` 仍被测（类保留），templateId 计数（14）与图表类计数（15）**不变**；若有用例专门以"编码页静态扫描"为语境则调整语境为 SystemTest，不删类测试。

**全仓回归搜索**（执行后必须零残留，DB 列/code-review 页/共享类除外）：`scanTrend`、`scanCoverage`、`scanDataAvailable`、`ScanPoint`、`scanFact`、`ScanFact`、`validScanInputs`、`scanPoints`、`codingScanResult`、`static-scan`（BI 生产代码）；`stacked-category-bar` 仅应存在于 SystemTest 白名单、共享类、联合类型、chart-contract/excel-table 测试。

### A.5 文档更新（裁定落档）
1. `docs/decisions.md`：新增 **D-12 BI 编码页静态代码扫描图表下线（待 PMD-Biome 重建）**——决策（下线编码页静态扫描图表及后端供数）；理由（数据仅 scan_status 三态+scan_bug_count 计数，无严重级别/分类/规则/文件/模块维度，量纲混淆；product.md 旧述"阻断/严重/一般/提示堆叠"从未被数据支持；SonarQube→PMD-Biome 迁移中，PMD-Biome 尚未接入）；重建条件（PMD-Biome 正式启用并以结构化 `issues[]` 接入平台后，按严重级别/分类/工具/Top 规则/模块密度趋势重建，届时重新登记核对表 CD 与黄金基线）；黄金基线影响（bi/coding 快照有意变更，按 D-08 更新模式重建审阅）；边界（DB 列与代码走查记录页保留）。
2. `docs/bi-dashboard/BI看板数据来源与计算口径核对表.md`：CD-36（L156）、CD-37（L157）备注列追加"BI 编码页已下线（2026-09-15，D-12），待 PMD-Biome 重建；底层 `scan_status`/`scan_bug_count` 列仍供代码走查记录页"。**保留行**（重建时复用编号语义），不删除。
3. `docs/bi-dashboard/product.md`：L31 图表清单删"静态扫描"；L45 删"静态扫描"；L48 删"静态扫描按扫描日期和阻断/严重/一般/提示堆叠，扫描失败不能转换为零问题"整句；L83 `stacked-category-bar` 行**保留**，用法列由"模块缺陷级别、静态扫描"改为"模块缺陷级别"。
4. `docs/bi-dashboard/architecture.md`：L63 删"静态扫描"。
5. `docs/bi-dashboard/data-contracts.md`：L17 事实族删"静态扫描"；L60 删"静态扫描"。
6. `docs/bi-dashboard/progress.md`：③ 条目由"待裁定，暂不改代码"更新为"已裁定下线（D-12），本工作单元 SA 执行；PMD-Biome 接入后重建"。
7. `docs/progress.md`：L13、L32 由"只记录不修/待裁定"更新为"静态扫描图已裁定下线（D-12）"。
8. `endpoint-catalog.yml` L1491 注释：被比对数据示例中删 `scanDate`（不再是 BI coding 产出）。
9. 历史计划 `docs/plans/bi-dashboard-feedback-and-fixes-comprehensive-20260910.md`（SURVEY-01/8.6）与 `bi-dashboard-feedback-issues-and-chart-tips-20260910.md`（L81-88）：标注"SURVEY-01 已由 D-12 裁定下线"，不展开（历史计划按 AGENTS 完成后压缩归档，本单元只标决议）。

---

## 五、方案 B-1：BI 看板改动测试方案（分层）

### 5.1 后端单元测试
- **密度覆盖率语义（v4，已实现）**：`BiCodingCalculatorTest` 既有强化 READY 用例 + 3 新增用例验证——坏行只剔除自身、合法子集出数、`coverageSection` 披露"有效观测 X/Y"、`validDensityTrendInputs` 的 MR 身份要求、`ReviewAccumulator` null 工时安全。执行 AI 须确认这些用例在静态扫描移除后仍全绿（构造器连锁修正后）。
- **静态扫描移除（SA）**：按 A.4 更新断言；保留/新增"sections 不含 static-scan""traces 不含 scan_status/scan_bug_count""data 无 scanTrend/scanCoverage"。
- 命令（backend 目录）：定向 `../tools/maven/apache-maven-3.9.9/bin/mvn.cmd -o test -Dtest=BiCodingCalculatorTest`；再全量默认套件 `../tools/maven/apache-maven-3.9.9/bin/mvn.cmd -o test`。

### 5.2 后端契约/边界测试
- `BiDownloadAuthorizationServiceTest`（coding 白名单去 `stacked-category-bar`、system-test 保留）、`GoldenBaselineCoverageGuardTest`（端点登记护栏，确认 bi/coding 仍登记）、`BiSpringBoundaryContractTest`、`BiDashboardSecurityContractTest`、`BiExcelExportServiceTest`/`BiDashboardControllerExcelExportTest`（确认 Excel 导出不再含静态扫描表，因前端 scanChart 移除后 `excelTable` 不再被调用）。

### 5.3 前端单元测试（Vitest）
- `adaptive-value-axis.test.ts`（7 用例，轴算法：连续重尾、中位数封顶、去重、小样本极值、无尾部退化等）。
- `chart-contract.test.ts`（三图契约断言 + templateId 计数 14 不变 + StackedCategoryBarChart 仍在册）。
- `CodingStageContent.test.ts`（删 scan fixture + 增"静态扫描图表已移除"回归断言 + 密度覆盖率披露断言）。
- `excel-table.test.ts`（图表类计数 15 不变）。
- 命令（frontend 目录）：定向 `npx vitest run src/features/bi-dashboard/`；全量 `npx vitest run`。

### 5.4 前端类型/构建/规范
- `npm run typecheck`（`tsc --noEmit`，**非 vue-tsc**）；`npm run lint`/`npx eslint "src/**/*.{ts,vue}"`；`npm run build`（`vite build`）。
- 注意：工作树存在他人在途文件 `SystemTestStageContent.test.ts` 的 TS2322（'FAILED' 不属于 BiDataStatus）——**非本单元引入，不触碰**，typecheck 报错时须区分归属。

### 5.5 浏览器界面验收（视觉，18181）
- 编码页不再显示"静态代码扫描结果"卡片；行2 三卡等宽（或备选布局）无空洞/错位；其余卡片（走查质量/问题分布/人员贡献/模块增量/质量趋势/增量趋势/提交趋势/散点）正常。
- 密度趋势轨主体均匀小步长 + 单段锯齿尾断轴、带顶标签≈真实极大值；散点主体 0–20 量级；注释率/密度断点=当日无合法观测（诚实表达）。
- Excel 导出（编码页各图）不再含静态扫描表；PNG 下载授权对 coding+stacked-category-bar 应被拒（白名单已移除）。

---

## 六、方案 B-2：黄金基线回归套件测试与更新方案（核心）

### 6.1 触发判定
- 密度 v4（改 `reviewDensityTrend`/quality-trend 分区/ruleVersion）+ 静态扫描下线（删 scanTrend/scanCoverage/static-scan/CD-36-37 traces）均改 `/api/bi/coding` JSON 产出 → `snapshots/bi/get___coding__day-all.json` 必然 diff → 属 `AGENTS.md`"有意修改产出"，须：先展示差异确认有意 → 更新模式重建 → 人工审阅。
- 轴改动（B-1 改动 2）纯前端渲染，不进快照，不触发。

### 6.2 运行时机与前后处置（纪律）
- 时机：本方案属"用户明确要求"的产出更新，授权运行（全链路约 7 分钟）。
- **运行前**：停止本机开发服务（后端 18080、前端 18181）；确认 Docker 在运行（双 PostgreSQL 容器）。
- **运行后**：重新拉起 `pwsh -File backend/run-backend.ps1`、`pwsh -File frontend/run-frontend.ps1`；验证后端 `/actuator/health` 为 UP、前端 18181 可访问。不得让用户面对停摆的开发环境。

### 6.3 更新命令（backend 目录）
```
../tools/maven/apache-maven-3.9.9/bin/mvn.cmd test -Pgolden-baseline -Dtest=GoldenBaselineChainTest -Dgolden.update=true
```
（更新模式只写不比，重建 `snapshots/bi/get___coding__day-all.json`。其它 bi 快照若因链路联动变化一并重建，但预期仅 coding 变化。）

### 6.4 人工审阅 diff（`git diff backend/src/test/resources/golden-baseline/snapshots/bi/get___coding__day-all.json`）
**必须且仅含以下预期变更**：
1. `ruleVersion`：`bi-coding-v3` → `bi-coding-v4`。
2. `sections`：`static-scan` 条目消失。
3. `data.scanTrend`：整个数组（原 L2499 起有数据）消失。
4. `data.scanCoverage`：消失。
5. `data.reviewDensityTrend`：由 `[ ]`（原 L56674 空）变为合法子集数值（密度 v4 生效）。
6. `quality-trend` 分区：status/message 反映覆盖率披露（"有效观测 X/Y"）。
7. `traces`：`scan_status`/`scan_bug_count` 的 englishName（原 L135/L138）消失，CD-36/CD-37 不再出现；CD-38/CD-39 保留。
8. **其余字段保持不变**：`summary`/`codeTrend`/`submissionTrend`/`contributors`/`moduleIncrements`/`reviewCategories`/`moduleReviewQuality`/`reviewPoints`/`commentRatePoints`/`commentRateCoverage`/`reviewDensityCoverage` 应零变化（`reviewDensityCoverage` 若随 v4 首次出现属预期）。
- **若 diff 含上述之外的意外变更 → 视为回归，停止，修复实现而非改快照。**

### 6.5 复跑验证（去掉更新模式）
```
../tools/maven/apache-maven-3.9.9/bin/mvn.cmd test -Pgolden-baseline -Dtest=GoldenBaselineChainTest
```
→ 全绿 = 产出与基线零差异，可继续打包。

### 6.6 不受影响快照的确认
- `snapshots/code-review/get___illegal-records__cc-page1.json`、`get___illegal-records_rule-explanation__default.json`、`snapshots/label-groups/get___dynamic-rule-sources__default.json` 含 `scanStatus`/`scanBugCount` 记录列——DB 列保留、代码走查记录页不改 → 这些快照应**零 diff**；审阅时一并确认未被误改。

### 6.7 夹具与 manifest
- 本单元不改冻结夹具（`fixtures/`），故 `baseline-manifest.json`（行数与 SHA-256）不变；若审阅发现 manifest 或夹具被动过，须停下核查（改动夹具=新基线版本，须用户确认并在 D-08 留痕）。

---

## 七、决策记录

| 编号 | 决策事项 | 结论 | 理由 | 否决的替代 |
|---|---|---|---|---|
| SA-D1 | 静态扫描下线深度 | **全栈移除**（前端图表+后端供数+白名单+DTO+溯源），非仅隐藏 UI | 开发期红线"删除被替代实现、不留死代码"；重建将基于 PMD-Biome 全新结构化数据，旧 sonar 路径不会被复用 | 仅删前端卡片（留后端死计算）——违反红线，否决 |
| SA-D2 | 共享图表类处置 | **保留** `StackedCategoryBarChart`/`CategorySeriesData`/`sortCategorySeriesData`/`'stacked-category-bar'` 联合成员与计数 | SystemTest 模块缺陷级别图复用；与 P0-5（专属类）不同 | 删类+改计数——会破坏 SystemTest，否决 |
| SA-D3 | DB 列处置 | **保留** `code_review_*.scan_status/scan_bug_count`，只删 BI 适配器 SELECT | 代码走查记录页合法消费，黄金基线 code-review 快照含这些列 | 删列——破坏记录页与快照，否决 |
| SA-D4 | RULE_VERSION | **保持 `bi-coding-v4`**，不升 v5 | v4 未提交未发布无外部消费者，静态扫描并入同一变更集，避免版本虚增 | 升 v5——无谓虚增，否决 |
| SA-D5 | 核对表 CD-36/37 | **保留行+标注下线**，不删除 | 重建时复用编号语义；保留决策痕迹 | 删除行——丢失口径痕迹，否决 |
| SA-D6 | 布局重排 | **首选三卡等宽**（问题分布+人员贡献+模块增量各 compact 4） | 环形图与两竖向柱图 1/3 宽可读，行和=12 无空洞，改动最小 | 备选（问题分布升 wide 重配对）改动面大，仅验收不平衡时启用 |
| B-D1 | 黄金基线运行授权 | **授权运行**（用户明确要求产出更新方案） | 符合 D-08"用户明确要求"时机；密度 v4+静态扫描两项产出变更须一次重建锁定 | 日常不跑——但本批含有意产出变更，发布前必须重建，否决拖延 |
| B-D2 | 快照重建粒度 | **一次更新模式重建** coding 快照，覆盖密度 v4+静态扫描两项 | 两者都改同一端点产出，分两次重建会互相覆盖、增加审阅噪声 | 分两次重建——冗余且易混，否决 |

---

## 八、接口契约变更

- **`GET /api/bi/coding` 响应**（内部前端消费，非对外发布契约，无需兼容层）：
  - 移除 `data.scanTrend`（`Array<{codeReviewId, scanDate, status, bugCount}>`）、`data.scanCoverage`（`{totalObservations, validObservations, coveragePercent}`）。
  - 移除 `sections` 中 `key="static-scan"` 条目。
  - 移除 `traces` 中 scan_status/scan_bug_count 的 SourceField 与 CD-36/CD-37。
  - `ruleVersion` 由 `bi-coding-v3`（当前快照）→ `bi-coding-v4`。
  - `data.reviewDensityTrend` 由空数组变为合法子集数值（密度 v4，已实现）。
- **`BiCodingSource.CodeReviewRecord`**（BI 内部源模型）：移除 `scanStatus`/`scanBugCount` 两组件——所有构造点（适配器行映射、测试 helper）同步。
- **`BiCodingPageData`**（BI 内部 DTO）：移除 `scanTrend`/`scanCoverage` 组件与 `ScanPoint` 嵌套 record。
- **`BiDownloadAuthorizationService.PAGE_TEMPLATES["coding"]`**：移除 `"stacked-category-bar"`（PNG 下载授权对 coding 页该模板转为拒绝）。
- **前端 `BiCodingPageData` 接口（`data/types.ts`）**：移除 `scanTrend`/`scanCoverage` 字段。
- 无新增 API/表结构/字段。

---

## 九、风险与假设

- **R1 构造器连锁**：`CodeReviewRecord`/`BiCodingPageData` 删组件后，所有构造点（含测试 helper `review(...)`、内联构造、`empty()` 的 `List.of()` 占位、数据装配实参顺序）须同步，否则编译失败。**缓解**：以编译错误为清单逐一修正，全量 `mvn -o test-compile` 把关。
- **R2 SQL 逗号合法性**：适配器删 `scan_status, scan_bug_count` 两列后须保持 SELECT 列表逗号合法（前/后列衔接）。**缓解**：编译+定向链路测试。
- **R3 布局空洞**：移除 span-8 卡片后若不改布局会留 8 列空洞。**缓解**：A.3 首选三卡等宽 + 浏览器截图验收。
- **R4 黄金基线意外 diff**：密度 v4 与静态扫描叠加重建，diff 较大。**缓解**：6.4 审阅清单逐项核对，意外变更即停。
- **R5 共享类误删**：误删 `StackedCategoryBarChart`/templateId 会破坏 SystemTest。**缓解**：红线明示 + 全仓搜索 + SystemTest 测试全绿把关。
- **R6 code-review 快照误改**：若误删 DB 列或改记录页会污染 illegal-records 快照。**缓解**：6.6 确认这些快照零 diff。
- **假设 A1**：PMD-Biome 重建为未来独立工作单元，本方案不预留抽象/扩展点（红线"不为尚未出现的需求过度抽象"）；重建时按新数据形态全新设计。
- **假设 A2**：`bi-coding-v4` 未在任何已部署环境缓存（v4 未提交）；生产缓存为 v3，发布 v4 自动失效。
- **敏感只读数据**：黄金基线夹具、内网老平台镜像数据只读，不得改写。

---

## 十、执行交接与出具方验证职责

### 10.1 执行 AI 落地顺序（建议）
1. 后端移除集（A.1）→ `mvn -o test-compile` 编译通过。
2. 后端测试更新（A.4 后端）→ 定向 `BiCodingCalculatorTest`/`BiDownloadAuthorizationServiceTest` 绿。
3. 前端移除集（A.3）+ 布局重排 → 前端测试更新（A.4 前端）。
4. 前端定向 vitest（`src/features/bi-dashboard/`）+ typecheck + lint + build 绿。
5. 文档更新（A.5）。
6. 后端全量默认套件 + 前端全量 vitest 绿。
7. 黄金基线：停开发服务 → 更新模式重建（6.3）→ 人工审阅 diff（6.4）→ 复跑零差异（6.5）→ 确认 code-review 快照零 diff（6.6）→ 起开发服务（6.2）。
8. 全仓回归搜索零残留（A.4 末）+ 三项仓库门禁（`check_worktree_artifacts.py`/`check_runtime_artifact_locations.py`/`check_text_whitespace.py`）+ `git diff --check` exit 0。
9. 浏览器界面验收（5.5）。
10. 提交编排（见 10.3），由用户决定提交时机与切分。

### 10.2 出具方（我）验证清单（执行完成后复核，不代为修改）
- [ ] `git diff --stat` 范围仅含本方案列明文件，红线文件（共享类、DB 迁移、code-review 页、他人在途文件）未被触碰。
- [ ] 后端定向+全量套件零失败；`BiDownloadAuthorizationServiceTest`/`GoldenBaselineCoverageGuardTest`/边界契约测试绿。
- [ ] 前端 BI 定向+全量 vitest、typecheck、lint、build 全绿（区分他人在途 `SystemTestStageContent.test.ts` 报错归属）。
- [ ] 黄金基线 diff **仅含** 6.4 八项预期变更；复跑零差异；code-review/label-groups 快照零 diff。
- [ ] 全仓回归搜索：BI 生产代码零残留 scan 供数符号；`stacked-category-bar` 仅存于 SystemTest/共享类/联合类型/相关测试。
- [ ] 文档落档齐全：D-12、核对表 CD-36/37 标注、product/architecture/data-contracts/progress、endpoint-catalog 注释。
- [ ] 三项仓库门禁 + `git diff --check` exit 0。
- [ ] 浏览器截图：编码页无静态扫描卡、布局无空洞、密度轨尾断轴、断点诚实表达。
- [ ] `AGENTS.md` 体积 < 32 KiB（文档更新触发检查）。

### 10.3 提交编排（用户决定时机；工作树混他人在途改动，只挑本单元文件）
- 建议切分（视用户偏好可合并）：
  1. `feat(bi-dashboard): 编码页密度趋势改覆盖率语义并重写重尾值轴为百分位主体+尾部断轴`（密度 v4 后端 + 轴前端 + 各自测试 + CD-39 核对表 + 词条 + 轴计划文档）——若此前未提交。
  2. `refactor(bi-dashboard): 下线编码页静态代码扫描图表并清理后端供数（待 PMD-Biome 重建）`（A.1-A.5 全部 + D-12 + 文档 + 黄金基线 coding 快照重建）。
- 黄金基线快照重建必须与产出变更**同工作单元提交**（AGENTS 纪律）。
- 提交前重跑 BI 定向 + 前端全量 + 五项门禁；提交后确认 `git status` 只剩其他单元在途文件。

---

## 附录：关键文件索引（执行/验证快速定位）

| 层 | 文件 | 关键位置 |
|---|---|---|
| 后端 DTO | `bi/domain/model/BiCodingPageData.java` | scanTrend L16、scanCoverage L21、copy L30、ScanPoint L69-74 |
| 后端源模型 | `bi/domain/source/BiCodingSource.java` | scanDataAvailable L21、scanStatus/scanBugCount L95-96 |
| 后端适配器 | `bi/infrastructure/BiPlatformCodingSourceAdapter.java` | SQL L53-54/L84-85、reader L213、scanDataAvailable L144-146/L174、行映射 L247-248、record L326-327 |
| 后端计算 | `bi/domain/BiCodingCalculator.java` | scanFacts L78-79、scanValues L162-164、scansComplete L168-169、static-scan 分区 L208-209、scanPoints L247/L374-379、scanCoverage L252、empty L269-270/L294、scanFact L714-715、ScanFact L897、validScanInputs L752-753、traces L633/L640-647 |
| 后端白名单 | `bi/application/BiDownloadAuthorizationService.java` | coding 集 L15-18（删 stacked-category-bar）、system-test 集 L21-24（保留） |
| 后端测试 | `bi/domain/BiCodingCalculatorTest.java` | L30、L147、L261-265、L278；review helper 构造参数 |
| 后端测试 | `bi/application/BiDownloadAuthorizationServiceTest.java` | 核查 coding+stacked-category-bar 正向断言 |
| 前端页面 | `stages/CodingStageContent.vue` | scanChart L54、scanSort L65-66、scanSortOptions L84、scanData L165-184、卡片 L260-278、布局 L241/L280/L308 |
| 前端类型 | `data/types.ts` | scanTrend L126、scanCoverage L148、BiChartTemplateId L11-25（保留 stacked-category-bar） |
| 前端词条 | `data/chart-explanations.ts` | codingScanResult L18 |
| 前端测试 | `stages/CodingStageContent.test.ts` | scanTrend L42、scanCoverage L47、removed 断言对标 L71-77 |
| 共享类（保留） | `charts/types/StackedCategoryBarChart.ts` | templateId L7（SystemTest 复用） |
| 黄金基线目录 | `golden-baseline/endpoint-catalog.yml` | /api/bi/coding L1512-1518、注释 L1491（删 scanDate） |
| 黄金基线快照 | `golden-baseline/snapshots/bi/get___coding__day-all.json` | ruleVersion L10、static-scan L58、scanTrend L2499、reviewDensityTrend L56674、scanCoverage L56675 |
| 不受影响快照 | `golden-baseline/snapshots/code-review/get___illegal-records__*.json`、`label-groups/get___dynamic-rule-sources__default.json` | scanStatus/scanBugCount 记录列（零 diff） |
| 文档 | `docs/decisions.md`（D-12，最新 D-11）、核对表 CD-36/37 L156-157、`bi-dashboard/product.md` L31/L45/L48/L83、`architecture.md` L63、`data-contracts.md` L17/L60、`bi-dashboard/progress.md` ③、`docs/progress.md` L13/L32 |
