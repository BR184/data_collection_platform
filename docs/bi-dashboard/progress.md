# BI 看板进度

## 当前状态

- 当前阶段：2026-09-11，BI 单元独立复核后的三项收口提交，把此前只存在于工作树、未随功能变更入库的验证基础设施补齐（平台级结论见 `../progress.md` 同日条目）：
  ① `a7af73ec` 真正把 BI 端点纳入黄金基线——`GoldenBaselineCoverageGuardTest` 扫描包由 `controller` 扩至 `controller + bi.api`；`endpoint-catalog.yml` 登记 `BiDashboardController` 的 7 个页面/版本 GET（READ，掩码 `generatedAt`/`sourceVersion`/`snapshotId`）与 2 个下载 POST（EXCLUDED，chain-dependency）、`BiCatMirrorController` 的 6 个端点（EXCLUDED，external-dependency / async-trigger）；`snapshots/bi/` 补入 7 个 READ 快照；`GoldenBaselineChainTest` 新增 `biProductVersionId`（与 `BiPlatformProductVersionAdapter.catalog()` 的 defaultId 同口径）。此前已提交状态下 BI 端点不受护栏约束，新端点 `POST /api/bi/download/excel` 既无目录登记也无快照。
  ② `02628b4a` 修 `check_api_contract_drift.py`：原只 glob `platform/controller/*.java`，main 上该门禁退出码 1 并报 6 条 `/api/bi/*` `MISSING_BACKEND`，现递归覆盖平台全部 `@RestController`。
  ③ `36ebbf65` 把模块修复率达标线从 4 处字面量（`data/sorting.ts`、矩阵图 `targets`、卡片表头「目标95%」文案、问号词条正文）收敛为 `data/quality-targets.ts` 的 `systemTestRepairTargets` 单一来源，表头文案改由常量插值生成；同一提交修正两处词条——「代码走查缺陷密度」`[2.0~10.0]`→`[3.0~12.0]`（对齐口径核对表 CD-23/CD-24 与后端 `MIN/MAX_REVIEW_DENSITY`），「模块修复率」原断言“异常优先视图下排在最前”在降序下与实现不符，改为“未达标组始终在前、升降序只调整组内顺序”。
- 待人工确认：模块修复率矩阵的**整体修复率 95% 达标线在 `BI看板数据来源与计算口径核对表.md` 中没有对应编号**（表内只登记一级 100%/ST-13、P1 90%/ST-25、P2 80%/ST-31，后端 `BiSystemTestCalculator` 也只产出这三个），常量注释已标注为待确认登记口径；确认前不得据其调整实现或词条。
- 已补入参契约：`BiExcelExportRequest` 的 `headers` 改为 `@NotEmpty List<@NotBlank String>`、`rows` 改为 `List<@NotNull List<Object>>`——表头元素为 null 原在 `columnWidths → displayWidth` 抛 NPE、数据行本身为 null 原在行遍历抛 NPE（均为 500），现由校验层拒绝为 400，单元格 null 仍合法写空串；新增 `BiExcelExportRequestTest` 6 项。
- 已复核但按纪律不改：① `ModuleQuality.fixRate` 因模块必然 `issues.size() >= 1` 而实际永不为 null，故 `sorting.ts` 的 null 分支与词条“仅无可计算数值时置于列表末尾”不可达，保留为类型层面的防御，不删除；② `SystemTestStageContent.vue` 的 `moduleSeverity`、`overlay` 两处内联比较器因后端计数为 `long`、不可能为 null 而暂不收敛，避免为尚未出现的场景做抽象。
- 已完成 `excelTable()` 全量审计：15 个图表类的导出实现逐个人工核对（表头单位、列顺序、元组索引、空值语义），未发现新缺陷；`DelayHeatmapChart` 的 `[严重级别索引, 原因索引, 数量]` 映射与其 `build()` 的 xAxis/yAxis 一致，`DefectCauseBreakdownChart` 的“未归类”行与图表同条件显式输出，`ReviewQualityDualPanelChart`/`ReviewQualityScatterChart` 的表头单位已改取图表自身 `config`。BI Excel 比率列统一 1 位小数（`excelPercent(..., 1)` 与 `toFixed(1)`），与后端 `setScale(2, HALF_UP)` 的口径不冲突。

- 当前阶段：2026-09-10，已完成 BI 看板实测反馈修复与全看板图表业务说明落地（v1.0 十项修复 + v2.0 复核修正），计划 `docs/plans/bi-dashboard-feedback-and-fixes-comprehensive-20260910.md`：
  ① 图表下载菜单被 `el-tooltip` 包层拦截、编码页切换粒度跳顶、系统测试顶部 7 指标双行、饼图高度未对齐、趋势粒度跨图联动、Tooltip 缺新增代码量等 8 项前端修复已闭环（详见计划 3.1~3.7）；
  ② 系统测试轮次自然排序：`sorting.ts` 的 `parseRoundOrder` 优先使用后端 `roundOrder` 整数，兜底解析中文/数字轮次名（回归测试固定沉底）；
  ③ 模块修复率排序（v2.0 按证据修正 v1.0 错误前提）：达标阈值由 `>= 0.95` 纠正为 `>= 95`；`BiSystemTestCalculator.modules()` 只包含已发现缺陷的模块，`fixRate` 永不因“无缺陷”为 `null`，因此 **0% 严格等于“有缺陷且全部未修复”＝最高风险**，不得置底；置底仅限真正不可计算的 `null` 且只在默认 `status`（异常优先）维度生效，用户显式选 `open`/`total`/`rate`/`name` 时不干预排序；同时删除 `SystemTestStageContent.vue` 中与 `sorting.ts` 已漂移的内联比较器副本，收敛为单一权威实现 `sortModuleRepairRows`（消除“测试只护住死代码”的虚假防护网）；
  ④ 全看板图表业务说明问号 Tooltip：字典 `chart-explanations.ts` 共 **26 条词条 / 23 个卡片实例**（需求与设计评审共用同一组卡片位，故不是“21 个图表”），全部已接线；`BiChartPanel.vue` 的 `description` 走深色 `el-tooltip` + `<QuestionFilled />`（静置 `#94a3b8`、悬停 `#475467`）；Element Plus popper 被 teleport 到 `body`，所以 `max-width: 320px`、`line-height: 1.6`、`word-break` 必须写在**非 scoped `<style>` 块**的 `.bi-chart-panel__tooltip.el-popper` 上（v1.0 只写了 `popper-class`、样式全库不存在）；
  ⑤ “代码提交频次时间分布”图表已按用户确认**彻底下线**（v1.0 仅删卡片，残留死代码已于 v2.0 清零）：图表类 `SubmissionFrequencyBarChart.ts`、`types/index.ts` 导出、`data/types.ts` 联合类型成员、`aggregateFrequenciesByWeek` 及其用例、后端 `BiDownloadAuthorizationService.PAGE_TEMPLATES.coding` 条目全部删除，并同步修正两处图表清单测试计数与 `docs/bi-dashboard/product.md`、`architecture.md` 中的频次图表条目；全库 `SubmissionFrequency`/`submission-frequency-bar` 零引用；
  ⑥ Excel 导出增补口径说明行（D4）：前端 `BiChartExportRequest.description` 透传至 `biDashboardApi.exportExcel` 的 `explanation` 字段（无词条时传空串），后端 `BiExcelExportRequest.explanation` 可空，`BiExcelExportService` 在元信息行下写“口径说明：…”，表头/数据/冻结行索引由游标推导（无说明 4 行、有说明 5 行），空白说明不占行以免行索引漂移；
  ⑦ 本单元另需的两项后端最小改动：解除并行统计看板工作流造成的 2 处 `int`→`Long` 编译阻塞（`CustomerIssueByFunctionBoardService`、`CustomerIssueResponseEfficiencyBoardService` 的纯文本单元格的 `numericValue` 改为 `0L` 并加注释）；以上后端改动已作为“纯前端闭环”原则的显式例外记录在同一计划的 1.2；
  ⑧ 调研结论（未改生产代码）：静态代码扫描图因量纲堆叠硬伤建议下线或待接入真实分级数据后重建；“按指派人统计缺陷数”的按模块/组织查看经实证 **DTO 无“人×模块”交叉明细，纯前端筛选不可行**，必须合并为单一后端供数任务。
  ⑨ 浏览器真实界面验收（2026-09-11，登录具备 `bi.dashboard.view` 权限的账号访问 18181）四项均通过：编码页 9 张卡片确认无“代码提交频次时间分布”且 9/9 带问号；最长词条悬停实测 popper `width=320`、视觉折行 4 行（非 scoped 样式已在 teleport 到 body 的 popper 上命中）；含 3 个 0% 模块的 CC2026R2 在默认“异常优先”下 0% 模块均位列前 3，切“未修复数降序”时完全由该维度决定；真实下载的 `.xlsx` 解压确认行 1 标题、行 2 版本与导出时间、**行 3 “口径说明：…”**、行 5 表头、行 6 起数据与 `ySplit=5` 冻结，数值保持百分量纲（16.9 而非 1690）。
  ⑩ 验收带出的两项待裁定/待记录事实：其一，“异常优先”维度切到**降序**时未达标组仍在前、组内按修复率从高到低重排，0%（最危险）模块因此落在组末尾，当前实现把该维度定义为“分组而非方向”并已用单测固定，是否符合领导预期待裁定；其二，前端 `api-client/request.ts` 的 `waitForProgressFirstPaint` 在发请求前 `await requestAnimationFrame`，在隐藏/最小化窗口下 rAF 永不触发使 BI 页永停“加载中”——属平台请求层通用耦合（非 BI 专属），影响一切 headless/隐藏窗自动验收，本次只记录不改。
- 当前阶段：2026-09-10，已完成 BI 看板“导出 Excel 数据表”从前端伪 `.xlsx` 到后端标准 OOXML 的完善改造，并修复调查中暴露的既有缺陷：
  ① 职责重划——前端把表格提取下沉为 `BiChart<TData>` 抽象方法 `excelTable()`，图表类各自用强类型数据与配置实现（本轮下线频次图后为 15 个），彻底删除 `switch(templateId) + as 强断言` 脆弱提取、死代码与本地 XML Spreadsheet 2003 拼接；后端新增无状态 `BiExcelExportService`（延迟 Runtime、Factory 装配），用 Apache POI `XSSFWorkbook` 产出真正的标准 `.xlsx`（`application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`），复用平台 `ExcelExportStyles` 与 `DownloadResponseHeaders`；
  ② 修复 `DefectCauseBreakdownChart`（与垂直柱状图共用 `vertical-category-bar` 模板但数据为对象）导出即崩溃——旧代码把对象当 `NamedValue[]` 调 `.map()`，现按大类/子类/缺陷数/占比展开并含未归类行；
  ③ 修复评审质量双面板/散点图表头单位写死“个/页”“页/小时”，改取图表自身 `config.densityUnit/rateUnit`（编码页实为 `个/KLOC`、`行/小时`、`KLOC/小时`）；
  ④ 修复比率类字段（修复率、通过率、关闭率、注释率）二次乘 100 导致“9500%”——这些字段在页面数据中已是百分数（0-100），`excelTable()` 原样忠实呈现图表口径；
  ⑤ 修复延期热力图元组索引反转——按 `[严重级别索引, 原因索引, 数量]` 与图表 xAxis/yAxis 一致地映射为“原因 × 级别”；
  ⑥ 补齐 `productVersionName` 从 `BiDashboardView` 经四个阶段内容组件透传至全部 23 个 `BiChartPanel`，元信息不再回退成 `Version-{id}`；
  ⑦ Excel 端点 `POST /api/bi/download/excel` 内部复用与 PNG 完全相同的下载授权门（`BiDownloadAuthorizationService`），授权提示措辞由“PNG”泛化为通用下载，`@RequirePermission` 查看 + 下载双权限 `requireAll`。黄金基线覆盖护栏**不豁免** `bi.api`：并行工作流已将 `GoldenBaselineCoverageGuardTest` 扩展为同时扫描 `controller` 与 `bi.api`，BI 的 7 个页面/版本 GET 已登记为 READ（遮 `generatedAt`/`sourceVersion`/`snapshotId` 三个墙钟字段），`POST /api/bi/download/authorize` 与 `POST /api/bi/download/excel` 因必须携带页面当前 `sourceVersion` 指纹而登记为 EXCLUDED（chain-dependency）。
  同时修复既有验证门禁红灯：`scripts/check_api_contract_drift.py` 后端扫描器由单一 `controller` 包扩展为递归扫描平台全部 `@RestController`（含 `bi.api`），6 个 BI 页面路径不再被误判 `MISSING_BACKEND`。
  验证（2026-09-10 本单元收尾时复跑，涵盖同日统计看板并行单元）：`check_api_contract_drift.py` 0 missing（backend_paths=175、frontend_paths=87）、`check_frontend_api_boundary.py`、`check_worktree_artifacts.py`、`check_runtime_artifact_locations.py`、`check_text_whitespace.py` 与 `git diff --check` 均通过；后端默认套件 287 个测试类 1342 项全绿（0 失败 0 错误 1 跳过，含已覆盖 `bi.api` 的黄金基线覆盖护栏），BI 包定向 109 项全绿（含 `BiExcelExportServiceTest` 6 项、`BiDashboardControllerExcelExportTest` 2 项），SpotBugs 0 发现，BI 代码 Checkstyle 0 违规（仓库仅剩 `backup` 模块 7 处既有未用 import，与本单元无关）；前端 ESLint、TypeScript 0 错误，全量 133 个测试文件 519 项单测通过，生产构建成功；本地开发服务已重启至当前代码并验证 `http://127.0.0.1:18080/actuator/health` 为 UP、18181 可访问；2026-09-11 另已用登录账号在真实浏览器完成 Excel 下载验收（解压核对标题/版本行/口径说明行/表头行/冻结 5 行与百分量纲数值）。
- 当前阶段：2026-09-09，已完成实测反馈的两轮共 6 项前端体验与功能缺陷修复：
  ① 修复图表卡片下载按钮因 Element Plus `el-tooltip` 内部嵌套阻断点击事件而无法弹出的问题，调整触发器结构并增加清晰的状态提示；
  ② 修复全局路由 `scrollBehavior` 在同页面 query 变更时强制 `{ top: 0 }` 导致切换跳顶的问题，并在视图层增加滚动位置记忆保护；
  ③ 系统测试页面顶部 3 个质量目标与 4 个测试概览指标重构为统一单行紧凑横向条（左目标右概览、中间细分割线）；
  ④ 各模块系统测试修复率矩阵（`ModuleRepairMatrixChart`）、Tooltip 以及 Excel 导出全面显式呈现“遗留缺陷数”（未修复缺陷数高警示色标记）；
  ⑤ 修复需求与设计阶段“评审问题类别分布”卡片在同行质量图表有数据时高度未对齐产生底部空白的问题（移除 `.bi-chart-panel--pie` 上的 `align-self: start; height: auto`，继承 Grid 容器拉伸，并统一卡片显式高度 `:height="430"`）；
  ⑥ 编码阶段趋势粒度联动解耦，三大图表（代码增量趋势、代码提交趋势、代码提交频次时间分布）各自配备独立且互不影响的“按日/按周”本地即时聚合切换控件，彻底消除跨图表联动、路由 query 污染、页面重拉数据与跳顶。
  全量前端 128 个测试文件 475 项单测全部通过，TypeScript 与 ESLint 0 错误，生产构建成功。
- 当前阶段：2026-09-03，BI 看板全面优化与改造（根据领导反馈与习惯对齐）已全部落地完成并热部署生效。页面标题全面单行化，编码阶段图表排布重构（质量走查指标置顶、频次置底、“按日/按周”下放至代码增量趋势卡片内部），图表更名为“代码注释率与走查密度趋势”，补齐所有物理单位；“按指派人统计缺陷数”改造为单根垂直堆叠柱（已修复/待修复，柱顶标总数）；落地通用图表排序控制器（`BiChartSortControl.vue` + `sorting.ts`），全阶段 17+ 图表全面支持自定义字段与升降序排序；新增全图表标准 Excel 表格导出（`.xlsx`）与高清 PNG 下载下拉菜单；全量单元测试与类型检查 100% 通过。
- 当前状态：内网 BI/CAT 缺口的生产修复和本地验证已完成。CAT 配置、显式产品版本/阶段映射、受限 HTTP 客户端、定时全量、手动全量、全量补偿、原始响应留存、阶段原子发布和失败保留上一版均已实现；配置与调度状态现以单事务保存。单元/集成页面只读取 `bi_cat_*` 当前发布快照，不在页面请求中直连 CAT。真实 CAT 认证、证书、完整路径、用例计数和来源身份仍按已确认边界等待内网联调。
- 当前 CAT 设置页已收敛为管理员可理解的地址与同步策略；连接/读取超时、响应上限和快照保留数改由后端部署配置控制。已发布目录会生成可编辑映射建议，保存映射仍是同步运行的唯一权威输入，唯一建议不会覆盖用户已保存选择，歧义保持空白。最新源码已应用 Flyway `20260806.02` 并在 1920×1080 完成该设置区验收：无页面横向溢出、控制台错误或技术参数残留；本地未发布 CAT 目录时保持“尚未采集”和空映射，不伪造默认值。
- 已确认：BI 是数据采集平台一级模块，包含需求、设计、编码、单元测试、集成测试、系统测试六个页面。
- 已确认：首期使用 `com.data.collection.platform.bi` 强包边界、进程内 BFF、延迟 Runtime 和单应用部署，不实施 Maven Reactor；Reactor 作为未来平台级重构候选保留。
- 已确认：需求、设计、编码、系统测试由平台供数；单元测试、集成测试由 CAT 全权供数；页面间只对齐产品版本语义，不要求平台和 CAT 共享批次。
- 已确认：BI 图表类、页面状态、PNG 下载和页面计算只在 BI 内实现/复用，不修改或依赖平台现有看板实现。
- 已完成：后端 `com.data.collection.platform.bi` 强包边界、进程内 BFF、延迟 Runtime、产品版本及六页 API、查看/下载权限和来源追溯已实现；前端六页路由、15 个 BI 专属 ECharts 图表类型类、分区状态、筛选和完整数据 PNG 已实现，未改造平台现有看板。
- 已完成审计与修复：以用户人工确认的 `BI看板数据来源与计算口径核对表.md` 为唯一业务语义与公式口径，真实物理字段从表结构、实体和查询核实后记录为直接使用或等价映射。跨年份组合版本不再误归属，评审、编码和系统测试计算器会隔离冲突稳定 ID、缺失日期/分母、非法负值和不可替代度量，不再任选首条、补零、钳零或用替代日期继续统计。
- 已完成运行故障修复：需求、设计和编码页曾因 PostgreSQL `integer/int4` 被 JDBC 按 `Long.class` 读取而整体失败；现已在 BI 基础设施边界统一执行空值保真的精确整数映射，并覆盖全部 BI Adapter。页面级 `ERROR` 现只展示明确失败原因和重试入口，不再误显示为一屏“数据暂不完整”或空来源/规则标签。
- 已完成来源维度及人员语义修复：编码 `author_name/module_name`、评审 `module_name`、系统测试 `module_names` 均按单一冻结平台快照维度处理，不再要求不存在的跨平台实体 ID；系统测试 `reason_category/label_names` 由 BI 版本化词典生成原因大类/子类，`delay_issue/delay_cause/delay_reason` 生成“延期原因 × 严重级别”。人员负荷已统一按 `assignee_name` 聚合总数、已修复数和待修复数，`fix_user` 仅保留实际修复历史语义。
- 当前平台输入缺口与已确认缺陷：编码仓库稳定 ID 仍缺失；人工走查兼容数据已复用平台兼容表 `code_review_match_mode_records` 接入，内网扫描和注释率覆盖率仍待只读统计。提交 ODS 定点来源 SQL可产出 31789 条 MR-提交关系，而本地 `merge_request_commit_fact=0`，来源能力首次就绪后缺少权威回填；同时该事实把 GitLab 仓库名写入 `project_name`，BI 又把它错误用作产品版本筛选。提交事实必须删除错误冗余列并按完整稳定键连接 `merge_request_fact.project_name`，部署后执行一次 MR 全量事实重建。轮次提交总数已确认可由有效缺陷计算，不再列为上游缺口。
- 已完成审计：系统测试没有执行用例数、通过用例数或通过率，历史 `ST-70`～`ST-72` 已废弃；客户问题统计不属于当前六个 BI 页面公式范围。
- 已完成 CAT 手册核对：项目、版本/测试阶段、模块统计和功能下钻四个 POST 接口已具备强类型传输事实；当前按用户确认让单元/集成页用各自显式阶段 ID 复用该协议。现有接口没有三层执行/通过用例数、精确通过率公式和快照/来源版本。
- 已完成 CAT 接入实现：CAT 配置、阶段映射、请求路径、超时和响应上限外置；模块/整体功能达标数量以 `FUNCTION` 单位呈现，功能层缺失计数显示不可用；真实 CAT 数据可在来源身份不完整时查看，但页面保持 `INCOMPLETE` 且不可下载。
- 已完成 CAT 镜像实现：系统设置的数据镜像页提供 CAT 独立配置、目录同步、阶段映射、全量同步和补偿状态；CAT 数据只写 `bi_cat_*` 专属表，不进入 GitLab 镜像表、平台兼容模式或表级增量引擎。单个阶段采集失败时不切换该阶段发布指针，其上一成功快照继续可读，且不影响其它阶段和平台原有同步。
- 已完成 CAT 配置修复：`PUT /api/bi-cat-mirror/config` 以 Java 强类型可空 `Timestamp` 直接写入 `next_scheduled_at`，配置表和调度状态表位于同一事务；真实 PostgreSQL 回归覆盖启用写入、禁用清空及第二表失败时第一表回滚。`operation_audit_logs.role` 已由独立迁移改为 `text`。该修复只消除平台本地保存 `500`；真实 CAT 出站联通仍需内网正式契约和证书链。
- 已完成 2026-08-06：上一轮误把浏览器内容区强制设为 `1920×1080`，没有覆盖物理 1920×1080 显示器中的浏览器栏和 Windows 任务栏。现已恢复最大化 Chrome 原生内容区验收，并将 BI 根页面底部安全区调整为 `80px`；六页文档滚动模型、图表网格和平台其它页面不变。
- 已确认边界：同一稳定 ID 的精确重复允许去重，核心字段冲突只使依赖该事实族的指标不可计算；无依赖且能独立证明完整的分区不被连带清空。长期规则见 `data-contracts.md`。
- 已完成内网 BI 数据修复：设计模块/散点、编码扫描/注释率/质量趋势按各自合法观测独立保留并报告不完整覆盖，不补零或猜测空扫描状态；静态扫描前端读取正式响应。提交事实删除仓库名冗余语义，按稳定 MR 键关联产品版本。系统测试 P1/P2 按各自合法成员独立计算，缺优先级的其它议题不再清空卡片和模块结果。现有字段仍不支持把问题总数伪拆为四级扫描数量。
- 已完成系统测试暂态“未归类”根因修复：镜像活动期间只登记事实 outbox，终态按事实族完整依赖提交 `READY/BLOCKED` 代际，再由与镜像共享来源互斥域的来源级消费者跨历史目标发布；失败事实归属和失败权威范围均可由后续运行恢复。真实 PostgreSQL 删除链已验证旧标签从 ODS、`issue_fact` 和投影中一致清除。
- 已确认：权限只有查看和下载两个；下载行为与静态页面一致，输出完整图表 PNG。
- 已确认：代码走查缺陷密度与老平台“千行代码缺陷率”是同一指标，不再保留独立“编码阶段缺陷密度”或第二套千行指标。
- 已确认兼容边界：BI 不拥有独立兼容开关，必须在请求开始时冻结平台现有兼容模式并与对应平台页面使用同一读源。内网真实环境兼容模式常开，几乎不会关闭；兼容态是主要发布验收路径，正式态只保留正确性和回归保障。
- 已确认轮次口径：轮次提交总数是该轮次有效缺陷 ID 去重数，不是上游独立字段，并且必须满足 `提交总数 = 一级 + 二级 + 三级 = 已关闭 + 未关闭`。
- 已确认人员语义：`assignee_name` 是当前指派责任人，`fix_user` 是合法修复状态评论作者；两者不能回退或混用。领导需要同时查看总数和待修复数的人员负荷图应按当前指派人统计，实际修复人只适用于历史修复归属。
- 已完成 2026-08-10：BI 首次跨模块进入时不再恢复会话中的旧产品版本，无显式参数时采用“议题测试阶段定义”排序后的首项；BI 六阶段内部切换只继承当前版本，显式深链版本保持优先。系统测试只输出存在有效缺陷事实的目录轮次；评审与编码图表单位已按各自数据域显式声明，三个代码走查质量图表共享保留真实极值的自适应值轴与显著空白断轴策略。
- 验证状态：后端全量 1,118 项通过、0 失败、0 错误、1 跳过；CAT/BI 定向回归通过，Flyway 迁移校验和已锁定，Checkstyle 0 违规，SpotBugs 0 发现，生产 JAR 构建成功。前端 114 个测试文件、409 项测试及 ESLint、TypeScript、生产构建均通过。CAT 镜像 Spring 生命周期壳的生产构造器已明确标注注入入口，消除了多构造器导致的全量上下文启动失败。最新源码已在 `18080`/`18181` 运行，并于 1366×768 视口完成系统设置 CAT 区块和 BI 六页浏览器验收：无页面横向溢出、文本容器裁剪、页面级错误或“缺少区块状态契约”；未发布 CAT 快照时单元/集成页保持明确 `INCOMPLETE` 且下载禁用。
- 验证状态 2026-08-06：物理显示器 `1920×1080`、最大化 Chrome 的原生内容区为 `1920×897`；需求和系统测试页滚动到底后最后图表底部位于内容区 `803px`，保留 `94px` 余量且横向溢出为 `0px`。用户已人工确认本次修改命中。BI 定向测试 2 项、前端 ESLint、TypeScript、生产构建和 Impeccable 检测均通过；上一轮强制内容视口的测量结论作废。
- 验证状态 2026-08-10：当前代码状态前端 115 个测试文件、421 项及 ESLint、TypeScript、生产构建全部通过；后端 1138 项零失败、零错误、1 项环境条件跳过并完成生产 JAR。BI 计算、CAT 原子保存、来源级事实 READY 查询、失败恢复和物理删除端到端均有确定性回归。Chrome 既有默认/继承/深链版本及页面布局验收保持有效；本地无真实编码走查质量事实，断轴有数据视觉仍留给内网复验。
- 当前下一步：生成新的内网隔离发布包并验收 BI 六页真实数据。使用真实地址完成 CAT 证书、完整路径、认证、响应和阶段隔离联调后，再补齐来源快照身份及执行/通过用例数契约。
- 当前阻塞：本地无法读取内网老平台 MySQL 和平台 PostgreSQL，因此 `spider_crowncad_data` 的扫描/注释率覆盖、设计正分母覆盖及 3583 条对应的具体内网运行 ID 需在内网执行计划中的只读 SQL；控制流根因本身已经闭环。静态扫描四级严重度和注释率同日/周聚合公式没有已确认物理字段或业务公式，实施前需冻结目标契约。CAT 手册仍缺基地址完整路径、认证、可信证书、执行/通过用例数、原子来源身份和稳定分页契约；当前主机仅证明 88 端口 TCP 可达，TLS/HTTP 未在 3 秒内完成，不能据此推断内网后端容器的最终联通性。运行产物位置门禁仍仅被仓库根目录既有 8 个 `.tmp-*.log` 阻塞，本工作单元未创建、删除或移动这些用户文件。

## 恢复线索

- 开始开发前先读 `README.md`、本文件和 `decisions.md`（D-01）。
- 首条恢复命令：`mvn -f backend/pom.xml -Dtest=BiCatTestSourceAdapterTest,BiCatTestPageServiceTest test`。
- 架构基线：保持唯一 `backend/pom.xml` 和单 Spring Boot JAR，不创建 Reactor 子模块。
- 旧 `D:/projects/bi_dashboard` 只读；静态页面只用于视觉和行为核对，不能成为字段或公式权威。

## 已完成证据

- 当前后端是单 `backend/pom.xml`、单 Spring Boot 应用；根包自动扫描 `com.data.collection.platform`，平台 Mapper 扫描边界为 `com.data.collection.platform.mapper`。
- 平台调度器每分钟检查配置是否到期，默认补偿间隔为 360 分钟；正式环境的 20 分钟属于配置，不是 BI 契约。事实和页面投影在成功发布后才成为可读版本。
- 平台已有“议题测试阶段定义”及父版本到测试轮次的展开服务，系统测试不需要第二套版本解析器。
- 老平台 `StaticDataController`、`SpiderCrowncadDataService`、`PageCodeWalkThroughInfo` 和横向导出 DTO 均把代码走查缺陷密度称为千行代码缺陷率，使用同一 KLOC 公式。
- BI 专属架构决策已记录在 `decisions.md`（D-01）。

## 实施顺序

1. [已完成] 删除无真实消费者的旧 BI 外部 Provider、Payload、测试和配置；保留通用外部数据集框架及其它真实消费者。
2. [已完成] 建立 `com.data.collection.platform.bi` 分层、薄网关、Runtime Manager、Runtime Factory 和架构依赖测试。
3. [已完成] 注册 BI 一级导航、六个页面路由、`bi.dashboard.view` 与 `bi.dashboard.download`，复用平台 Session、CSRF、RBAC、侧边栏和通用控件。
4. [已完成] 建立六个页面 API、来源版本/状态契约和平台只读查询端口；需求、设计、编码、系统测试按现有真实字段计算，缺失输入显式返回 `INCOMPLETE`。
5. [已完成] 图表数据模型、计算器、15 个图表类型类、事实族完整性隔离、PNG 和 PC 页面验收已完成。
6. [已完成实现与本地验证，待内网联调] 已核对 CAT 四接口并完成强类型适配和 BI 专属快照镜像；单元/集成页面使用同一统计协议及各自显式阶段 ID，并只读取当前发布快照。CAT 仍需补充来源身份、真实用例计数、认证和精确通过率公式。
7. [已完成] 按核对表编号复核平台真实 `table.column`、公式、规则版本和来源版本；已删除虚构稳定作者/模块/延期状态前置条件，接入来源快照维度与系统测试原因/延期真实字段；编码跟随平台统一兼容模式读源，轮次总数由有效缺陷去重计算，人员负荷统一按当前指派人聚合，并保证所有响应路径返回完整区块状态集合；自动验证和 1366×768 浏览器终验均已通过。
8. [已完成实现与本地验证，待内网数据验收] GitLab 提交表进入平台推荐同步、血缘与 `merge_request_commit_fact`；编码页人工走查兼容态复用平台兼容表 `code_review_match_mode_records`（重复抓取链路已移除，不再维护 BI 独占走查表）。配置已启用但事实尚未发布时，提交区块不会生成全零序列。
9. [待真实契约/发布工作单元] 完成剩余平台字段和 CAT 接入后，再执行 Docker/离线包、升级/回滚和真实数据验收。

## 固定边界

- BI 专属事实只维护在 `docs/bi-dashboard/`，平台通用文档只保留入口和真实平台交界。
- BI 不调用 `/api/external/v1/datasets`，浏览器不直连 CAT，不引入第二套登录、端口或部署。
- BI 不创建兼容模式开关或读源回退；平台唯一兼容模式控制 BI 与其它页面的来源选择，同一请求内模式固定。
- 平台现有看板不得依赖 BI 图表类；BI 不直接复用平台看板组件、页面 Service 或页面 DTO。
- 系统测试直接使用平台阶段定义；BI 不建立重复同步任务，也不硬编码 20 分钟。
- 已打开页面不在同步完成后自动替换数据，只提示刷新；刷新后整页切换来源版本。
- 当前不设计 BI 专属线程池、连接池、缓存或资源配额；出现实测影响后再按固定负载决定。

## 风险与待确认

- 同 JVM 无法隔离 OOM、进程退出、全局死锁和共享依赖耗尽；Runtime 只能隔离 BI 初始化与普通请求异常。该边界已接受。
- 强包边界不提供 Maven 级编译隔离；BI 编译、依赖或测试失败会阻止整个单体打包，必须通过定向测试和平台回归控制。
- CAT 已提供单元/集成页面当前共用的统计传输说明，但仍没有内网基地址/认证、用例计数、精确通过率公式和来源身份；不得提前增加字段别名、模糊版本匹配或合成数据分支。若后续拆分单元测试专用接口，必须先取得正式契约，再修改唯一适配边界。
- CAT 模块和功能数据来自独立接口且没有同页来源身份，当前无法证明页面一致性，必须保持 `INCOMPLETE` 而不是拼接。
- 平台现有 Service 可能绑定旧页面 DTO；BI 应读取稳定事实/基础查询并独立实现页面计算，不通过临时适配层长期依赖旧页面。

## 最终验收标准

- 单 Maven 工程可完成 BI 定向测试、平台回归、后端打包和现有发布链验证；没有 Reactor、循环依赖或第二个 JAR。
- 平台在 CAT 未配置或 BI Runtime 初始化失败时仍能启动并使用非 BI 页面；BI 路径返回明确状态。
- 六页数据来源符合页面边界，单页所有图表共享同一来源版本；没有 mock、零值补齐、跨来源拼接或旧 Provider 回退。
- 每个计算字段可追溯到核对表编号、来源平台、来源字段中英文语义、公式/规则版本和来源版本。
- CAT 集成适配只能使用手册已说明且通过完整性校验的字段；功能数量不冒充用例数量，单元测试不复用集成测试接口，缺失来源身份时页面不进入 `READY`。
- 图表类型类只在 BI 内复用；在物理显示器 `1920×1080`、最大化 Chrome 原生窗口下，页面效果、布局、交互和 PNG 与已确认静态页面一致，六页无横向溢出且滚动到底部时最后图表完整显示在 Windows 任务栏上方。其它分辨率和非最大化窗口不属于本阶段验收范围。
- 查看和下载权限在前端入口与后端接口同时生效，下载权限不能绕过查看权限。
