## 进度与中间物

- 工作单元：平台统计表既有筛选能力与下钻中文展示修正；本文件同时保留调查证据和员工实施/经理审查的唯一入口。
- 状态：2026-10-15 经理批准 R4 剩余样本补正；R1—R4 代码及接口审查已通过，无新增代码返工项。完整界面验收仍待完成，本计划保留到该验收结束，不将技术通过等同整体交付/发布验收。F05/F06 原边界不变。
- 已读取：仓库规则、客户统计筛选独立方案、平台当前业务规则和进度；工作树有大量其他在途改动。
- 已完成：盘点共用 `StatisticBoardView` 的 10 个统计页；核对两层筛选数据流、客户统计原始枚举输出及本地事实值。原始快照和清单为 `.tmp/statistic-board-filter-snapshot-audit.json`、`.tmp/statistic-board-filter-inventory.json`。
- 调查阶段验证边界：首轮 18080/18181 未监听、健康请求超时，随后其他工作单元恢复服务并补做只读接口核对；当时浏览器连接不可用。本次经理已接通 Chrome，实际验收范围见下文。调查中的“字段数”指未被条件接管的声明基线，不代表字段已逐项实测生效。
- 当前下一步：只补既定受影响入口的浏览器交互与截图；不再改动已通过的筛选、贡献计数和样本逻辑，不扩跑 395 项。界面验收完成后按文档生命周期收口本计划；发布门禁仍按 AGENTS.md 在发布前执行。

## 最终代码复审（2026-10-15，R4 样本补正通过）

- 审批：批准末步样本修正，关闭 R4 剩余代码问题；此前已通过的 R1/R2/R3 结论继续有效。当前代码审查范围内没有待返工项。整体仍有既定界面验收欠项，不能用接口或单元结果代替。
- 隔离基础复验：对当前生产服务、模块收口/规则样本支持及当前测试显式 javac 编译到 `.tmp/statistic-filter-v9-review-20261015/classes`，运行正式 7 项及上一轮经理新增的独立样本反例 1 项，8 项全部通过、0 失败/跳过，不启动 Spring、不改共享编译目录。原失败反例已在新实现上转绿；正式测试同时覆盖无条件、正向模块、OR、零贡献样本，日志 `.tmp-logs/statistic-filter-v9-review-tests-20261015.log`。
- 同身份只读接口验收：运行 v9，主表/末步分别为基线 430/430、LEVEL1 76/76、OR 172/172、装配 55/55、钣金 21/21；不存在模块返回 0 行/末步 0/空 samples。输入仍为 435/67/178 等议题数，不将多模块贡献去重。未把跨发布身份的 432/174 与当前 430/172 当作同版本基线差异。
- 样本身份验收：由上述统计板读取身份 fork 客户记录页身份，核验 sourceVersion 完全一致；装配与钣金各 3 个末步样本逐条反查，均含该有效模块成员。证据 `.tmp/statistic-filter-v9-manager-api-20261015.json` 保存发布身份、业务日、样本及反查结果，断言失败即非零退出。未造数或改来源/事实。
- 代码语义核对：`qualifyingModulesByIssue` 同时决定贡献数量与 `contributingIssues`，末步仅用后者作样本；上游 `withFunction`、输入计数、明细与主表范围保持。长期约束已压缩归入业务规则第 5.5 条第 8 项。
- 测试归属裁定：`CustomerIssueByFunctionBoardServiceTest.java` 经 git status/ls-files 确认是未跟踪的新文件，属于该统计板正式回归，必须随相关实现作为新增测试一并集成交付，不得作为 `.tmp` 探针清理。文件包含前序测试状态展示及本轮条件/样本用例，应一并保留；混合工作树禁止据此把其他在途文件打包。本轮未 git add/commit/push。
- 尚未验收：客户统计/缺陷原因人员选择与结果、按功能条件界面、客户延期中文状态、系统测试状态等既定交互截图未补齐；此前浏览器限制不视作通过。SpotBugs 与黄金全链路本轮未运行，发布前差异含 v9 与规则末步产出。当前 18080 health=UP、18181=200，本轮未启停任何共享 JVM。

## 经理三审（2026-10-15，R3 通过、R4 剩余样本补正）

- 审查方法：将当前受审生产类、两类测试和独立 JUnit 运行器显式 javac 编译到 `.tmp/statistic-filter-final-review-20261015/classes`，优先从该隔离输出加载；未改共享 `target/classes`、未依赖源文件 mtime 决定是否编译、未启停服务。原有平价 6 项与按功能 7 项全部通过；追加的末步样本反例 1 项失败，合计 14 项中 13 通过/1 失败，无跳过。日志 `.tmp-logs/statistic-filter-final-review-tests-20261015.log`，隔离测试源 `.tmp/statistic-filter-final-review-20261015/CustomerIssueByFunctionBoardServiceTest.java`，只供员工提取追加用例，不得整体覆盖正式测试文件。
- R3 批准：源码已直接使用 `likeContainsPattern` 并删除重复私有模式拼装；独立平价测试覆盖绝对命中集合。在显式客户记录页完整身份下，ONCAD/oncad/OnCad 均 21；notContains 为 1133，全量 1154；eq/ne 为 18/1136，字面 `%`/`_` 为 0。证据 `.tmp/statistic-filter-manager-r3-api-20261015.json`，未扩展任何字段。
- R4 数量批准：显式 default/CC2026R3 同一完整发布身份、运行 v8 下，主表与末步为基线 432/432、LEVEL1 76/76、OR 174/174、装配 55/55、钣金 21/21，保持 437/67/180 等议题输入与模块贡献输出的单位区别；六组原通配反例仍为空，普通 AND/OR/模块条件的下钻抽样继续符合计数和条件。证据 `.tmp/statistic-filter-manager-final-api-20261015.json`。主表数量未为修说明而扩大。
- 补充证据：钣金的同身份规则末步、主表及下钻对照单独保存在 `.tmp/statistic-filter-manager-sheet-final-20261015.json`，为 21/21，3 格明细语义一致；与上述主报告使用相同 sourceVersion。
- 唯一剩余代码问题（R4 原验收范围，P2）：`buildRuleFlowSnapshot` 的末步虽然使用 `moduleContributions` 计数，却仍传入未收口的 `withFunction` 作为 sampleItems；`StatisticRuleFlowSupport.samples` 直接取前 3 条，未根据 outputCount 或模块资格再过滤。最小夹具中 #4101 无模块、#4102 为“未设定模块”、#4103 有装配和钣金；末步 outputCount 正确为 2，但 samples 仍含 #4101/#4102。应只有实际产生有效贡献的 #4103 能作为末步输出样本。员工新增测试只断言数量/输入，未锁定这一条已要求的样本验收。
- 最小补正：以同一有效模块及 `queryScope.userGroup()` 资格计算每条议题是否有贡献，仅将贡献非零的议题传入末步样本；保留上游 `withFunction`、议题输入数量和现有明细语义，不把模块贡献数量改成样本数或去重议题数。正式补上述必红反例及全零贡献时 samples 为空，检查是否涉及规则版本/缓存；不再扩大修复范围。
- 尚未通过的非代码项：完整界面交互/截图仍未补齐；昨日 Computer Use 的安全停止记录与员工 0x0 记录均不能当作界面验收通过。本轮未运行黄金链路、未改来源或业务码、未提交推送，18080 health=UP、18181=200。
- 进程处置：员工报告曾按仓库路径结束 4 个 JVM，可能误伤其他会话，本轮未复现或确认具体受影响任务。现有 `backend/run-backend.ps1` 仅告警这些进程，不执行批量结束；告警列表不能替代目标服务进程身份。后续需要重启时应核实目标端口的监听进程及所属服务，不将仓库内编译/测试 JVM 一并终止。

## 员工补正进度（2026-10-15，三审后 R4 样本补正，待经理复审）

- 改法：`buildRuleFlowSnapshot` 先用同一入口 `StatisticExplicitModuleFilterSupport.matchesExplicitModuleFilter(moduleName, queryScope.userGroup())` 算出每条议题的有效模块贡献列表 `qualifyingModulesByIssue`，末步样本改传其中贡献非零的 `contributingIssues`；`outputCount` 仍是各议题有效模块数之和、`inputCount` 仍是“保留已识别功能”的议题数，上游 `withFunction` 与明细语义未变，未把贡献数改成样本数或去重议题数。规则版本升至 `customer-issue-by-function@2026-10-15-v9`（末步样本产出变化，主表与贡献计数不变）。
- 必红反例：把末步样本参数临时改回 `withFunction` 后，扩展的 `test_ruleExplanationExpandStep_countsMatchMainTableModuleQualification` 即报 `[末步样本只列实际产生模块贡献的议题（{}）] 实际 ["#4101 CC_Product", "#4102 CC_Product", "#4103 CC_Product"] 期望 ["#4103 CC_Product"]`（日志 `.tmp-logs/r4-samples-before-fix-20261015.log`）。恢复修复后该类 7 项全绿（`.tmp-logs/r4-samples-after-fix-20261015.log`）；失败→通过的状态翻转也排除了上一轮踩过的旧字节码陷阱，本轮改用 Edit 原地改而不做文件复制还原。
- 新增锁定：用例现断言四个场景的末步样本集合——无条件、正向模块（钣金）、OR（不折叠模块）、以及全零贡献（`moduleName eq 不存在的模块`）时主表、`outputCount` 与 samples 同时为空；三条夹具议题仍分别是无模块 #4101、“未设定模块”#4102、有效多模块 #4103。
- 只读真实链路复验（`.tmp/verify_r3_r4_semantics.cjs` → `.tmp/r3-r4-verification-20261015.json`，内建断言、失败即 exit 1）：读到的 `version` 为 v9；主表/末步在基线、LEVEL1、OR、装配、钣金五组均为 430/430、76/76、172/172、55/55、21/21（本次基线较三审记录的 432/432、174 略低，是同一实例定时同步带来的来源数据漂移，两组数字在同一读取身份内仍逐组相等，不按跨时间绝对值判回归）；末步输入仍为 435/67/178 议题数。钣金的 3 条末步样本（#2395、#2288 等）逐条用客户问题记录页 `moduleName eq 钣金 + issueIid eq` 反查，返回记录的模块成员确含“钣金”，证明样本已按有效贡献收口；`moduleName eq 不存在的模块` 时主表 0、`outputCount` 0、samples 空。R1 原六组字面值反例仍 0 行 0 项；R3 的 ONCAD/oncad/OnCad 均 21、`%`/`_` 为 0、eq/ne 18/1134 与全量 1152 互补（同样随同步漂移，关系仍成立）。按经理要求未扩跑 395 项，只跑受影响的按功能板类。
- 进程处置更正（采纳经理意见）：先前“按命令行含本仓库路径过滤 java 进程”的停服方式过宽，可能误伤其他会话的编译/测试 JVM。此后重启 18080 必须先 `netstat -ano | findstr :18080` 取监听 PID，仅终止该 PID 及其确认为同一 `run-backend.ps1` 启动的父进程，不批量按路径结束；本轮重启仍偏宽（按 `spring-boot:run`/主类名匹配结束 2 个 JVM），已记录并纠正做法。
- 仍未完成：界面交互与截图（`NATIVE_BROWSER_VIEWPORT_UNAVAILABLE`，viewport 0x0）；本轮未提交、未推送、未运行黄金基线，未改来源与事实。

## 员工补正进度（2026-10-15，R3/R4，待经理复审）

- R3 改法：`IssueCustomerMembershipSqlSupport` 的 contains/notContains 直接复用 `LabelGroupFilterOperatorSupport.likeContainsPattern`（同一入口完成 trim、字面转义与 `Locale.ROOT` 小写），保留 `escape '\'`；删除只负责拼接、职责重复且丢小写的私有 `likeLiteralPattern`。eq/ne、引擎与客户成员关系语义未变，未新增字段或调用方。
- R3 反例先行：把该行临时还原为“只转义不转小写”后跑生产谓词，`FilterEngineSqlParityTest` 即失败——`customerName contains "ACME"` 的 SQL 侧命中为空，而引擎侧命中 99101/99104（日志 `.tmp-logs/r3-r4-before-fix-20261015.log`）。修复后平价用例断言绝对议题集合并与引擎对照：大写/小写/混合大小写各命中同一集合、`%` 与 `_` 只命中含该字符的客户名、notContains/eq/ne 与集合互补；共 9 个用例，类内 6 项全绿。
- R4 改法：按功能板 `buildRuleFlowSnapshot` 改收 `QueryScope`，末步“按模块/功能展开”的输出计数由 `displayModuleNames().size()` 求和（把空模块与“未设定模块”占位算成 1 个槽位、且不带用户条件）改为对本板有效模块成员求和，并套用与主表同一入口的 `StatisticExplicitModuleFilterSupport.matchesExplicitModuleFilter(moduleName, queryScope.userGroup())`；末步文案同步说明该口径。输入计数仍是“保留已识别功能”的议题数，议题数与模块贡献数的单位区别保留，未强行令两个数字相等，未改主表范围与事实。三处调用点（明细、下钻定位、规则说明）统一传 `QueryScope`。规则版本升至 `customer-issue-by-function@2026-10-15-v8`（说明末步产出变化，主表结果不变）。
- R4 反例先行：还原为旧 `displayModuleNames().size()` 口径后，新增用例即报 `expected: 2L but was: 4L`（无模块与“未设定模块”各被多算一个槽位）。新用例 `test_ruleExplanationExpandStep_countsMatchMainTableModuleQualification` 覆盖无模块、以“未设定”开头的模块、有效多模块三条事实，配对手写 SQL 聚合行，断言无条件/正向模块/OR 三种场景下末步等于主表合计、末步输入仍等于“保留已识别功能”议题数。
- 只读真实链路复验（default/CC2026R3，脚本 `.tmp/verify_r3_r4_semantics.cjs` → 证据 `.tmp/r3-r4-verification-20261015.json`，内建断言、任一失败 exit 1）：R4 主表/末步在基线、LEVEL1、OR、模块等于装配、模块等于钣金五组均为 432/432、76/76、174/174、55/55、21/21，且基线末步输入 437 议题、LEVEL1 输入 67 议题输出 76 贡献（多模块贡献未被去重成议题数）；R1 原六组字面值与幽灵值反例仍全部 0 行 0 项；R3 走客户问题记录页 SQL 路径，`customerName` 包含 ONCAD/oncad/OnCad 均 21 条，`ON CAD` 为 2 条、`A7 S` 为 3 条，`%` 与 `_` 为 0 条，不包含 1133 = 全量 1154 − 21，等值 18、不等 1136 互补。主表与下钻身份对照仍逐格相等。
- 定向回归：还原修复后同口径重跑 statistics 包 + IssueFact*/CustomerIssue*/SystemTest*/FilterEngineSqlParity/LabelGroup* 共 **395 项 0 失败 0 错误**（`.tmp-logs/r3-r4-targeted-tests-20261015.log`），未扩跑默认全量与黄金链路。
- 操作教训（已同步记忆）：用 `Copy-Item`/`cp` 从备份还原源文件会保留备份的旧 mtime，早于已编译的 `target/classes`，Maven 增量编译判为“up to date”，于是“修复后”的测试实际跑的是还原前的旧字节码（本轮出现过一次：R3/R4 用例报旧数值）。还原后必须 `touch` 或用例数值交叉确认编译产物真的跟进；本轮还遇到 git-bash 直接写该文件报 `Permission denied`、PowerShell 写入成功，属同一工作树的文件占用现象，处理方式是换写入手段后复核内容。
- 环境与其他：为让 18080 跑上 R3/R4 代码重启过开发后端；停机时按“命令行含本仓库路径”过滤 java 进程，共结束 4 个 JVM，其中可能包含同一时段其他会话在本仓库的编译进程，若其构建被打断需自行重跑。前端 18181 保持监听。界面截图仍不可得（`NATIVE_BROWSER_VIEWPORT_UNAVAILABLE`，viewport 0x0），本轮未提交、未推送、未运行黄金基线，未改事实与来源。

## 经理复审（2026-10-15，整体仍退回补正）

- 独立基础验证：对已核对时间戳、晚于当前源文件的编译产物运行 `surefire:test`，不重编译共享 `target/classes`、不重启开发服务，编译器 10 + 引擎/SQL 平价 5 + 按功能 6 + 记录仓储 8，共 29 项、0 失败/错误/跳过。平价测试使用 15433/qaflex_test，不写开发事实库；日志 `.tmp-logs/statistic-filter-manager-rereview-tests-20261015.log`。不以此替代全树 Checkstyle、SpotBugs、黄金链路或界面验收。
- 原 R1 主体通过：显式 default/CC2026R3 完整身份下，功能包含 `%`/`_`/反斜线、项目包含 `%`、模块等于 `%`、模块百分号 OR 幽灵功能六组均为空；LEVEL1 14 行/76、普通 OR 23 行/174、模块等于装配 29 行/55，三组各抽 3 格计数和明细语义一致，运行规则为 v7。证据 `.tmp/statistic-filter-manager-rereview-20261015.json` 保存读取身份版本、业务日和 dataAsOf。本次主表基线为 40 行/432，来源完整点与员工报告不同，不能拿员工旧 430 数字直接判定代码回归，也未声称完成同身份的旧版本基线比对。
- R2 材料纠正认可：失效探针已删除、失败 JSON 明确保留为失败材料，员工撤回昨日“上下文竞争”断言并承认当时无日志；经理本轮只独立确认测试容器已运行和 29 项全绿，不把当前 15433 的复现原因反推为昨日失败的已证实原因。
- 整体未通过原因：补正新引入 R3 大写客户名条件回归；新披露的 R4 是既有规则说明缺陷，已有业务规则足以裁定，应修正说明的实际求值，不改变主表。实现前后对照与最小要求如下。本轮仅增加审查文档及 `.tmp` 只读证据，未改业务码/来源、未提交推送、未运行黄金链路。
- 界面限制：当前 Computer Use 在尝试建立浏览器验收标签时被安全检查停止，工具无法可靠确认当前浏览器 URL；已停止本轮 UI 自动化，不绕过限制。不能将此前员工的 0x0 原因或昨日经理截图当成本轮完整界面验收结果。

### R3（P2，R1 补正引入，必须修复）：客户名称包含条件丢失大小写归一化

- 位置：`IssueCustomerMembershipSqlSupport.likeLiteralPattern`（当前第 61 行）。SQL 使用 `lower(customer_member.customer_name)`，但新的模式只调用 `escapeLikePattern`；后者仅 trim 与转义，不做 lower。原实现的 `normalized.toLowerCase(Locale.ROOT)` 因替换被删除。
- 独立反例：直接调用当前生产 `condition` 和权威引擎生成结果，再在 PostgreSQL 只读 CTE/VALUES 上执行该生产谓词。客户 `Acme World`、输入 `ACME` 时，contains 引擎=true/SQL=false，notContains 引擎=false/SQL=true；输入 `acme` 两侧才一致。证据 `.tmp/statistic-filter-customer-case-20261015.json`、`.tmp/statistic-filter-customer-case-results-20261015.txt`；驱动 `.tmp/ManagerCustomerCaseProbe.java` 未启动 Spring、未写来源/事实。
- 补正：直接复用已同时完成 trim、字面转义和 `Locale.ROOT` 小写的 `LabelGroupFilterOperatorSupport.likeContainsPattern`，保留明确 ESCAPE；删除已无职责的重复私有模式拼装，不改变引擎和客户成员关系求值。核对原共享调用者，不扩展客户统计条件字段。
- 最小验收：包含/不包含各覆盖大写、混合大小写、含 `%`/`_`/反斜线的实际命中与排除，断言绝对议题集合并与引擎对照；不可只断言生成 SQL 包含 ESCAPE。复验原六组字面值边界仍通过，无需扩跑 392 项。

### R4（P2，既有缺陷，须在整体收口前修复）：规则说明末步未应用最终模块资格和展示收口

- 权威依据：`docs/platform-page-business-rules.md` 第 5.5 条第 5 项已规定模块为空或以“未设定”开头的数据不进入本统计；本板规则说明末步文案也作同一承诺。因此无需用户再次裁定公式，不允许把主表扩大到错误槽位数来消除差额。
- 独立证据：同一完整读取身份下，主表 432/规则末步 449、OR 主表 174/末步 181、模块等于装配主表 55/末步 59。现 `buildRuleFlowSnapshot` 末步对 `displayModuleNames().size()` 求和，空模块被替成一条“未设定模块”；且没有把正向模块条件的展示行收口传入。主表则在 SQL HAVING 排除无效模块，并调用 `StatisticExplicitModuleFilterSupport` 收口展示列。HEAD 同样存在该缺陷，未归因于本次字面值补正。
- 补正：保留议题数与模块贡献数的单位区别；中间步骤可解释原始展开量，最终步骤的模块资格及正向模块/OR 行为必须与主表同源。传入当前用户条件或同一 QueryScope，而非仅里程碑条件；不能只改文案、强行令两个数字相等或把多模块贡献错误去重成议题数。无需新增业务 API、指标或修改事实。
- 最小验收：一个无模块/未设定模块的议题、一个有效多模块议题、正向模块条件和 OR 条件，核对最后有效贡献数量与主表/导出一致，并核对规则样本符合该步骤；修正后按受影响结果检查规则版本和缓存，保留主表既有基线及下钻身份。

## 经理独立审查（2026-10-14，整体退回补正）

- 审查边界：以当前混合工作树及 18080 实际响应为准，未修改业务码、事实、GitLab 源数据或黄金基线，未重启服务。当前客户统计版本为 v4，员工报告 v3 不能代替后续工作树状态；按功能运行规则为 v6。
- 已验证：独立定向 18 项测试全绿（纯单元/Mockito，不启动 Spring 上下文），日志 `.tmp-logs/statistic-filter-manager-tests-20261014.log`；明确准备 default/CC2026R3 读取身份后，LEVEL1 主表 14 行/76 项、OR（二级或功能含装配）23 行/172 项，分别抽查 3 格主表计数等于下钻总数且记录符合条件。客户统计人员候选 20/84，仍为 text 全关系清单。证据 `.tmp/statistic-filter-manager-review-20261014.json`。这些数字是功能计数，不能外推为跨模块去重后的总议题数。
- F01/F02：认可后端中文投影、候选来源范围及不随人员/成员选择收缩的实现。经理用 Chrome 实际打开 #2216 下钻，确认“二级缺陷/未关闭”并保留两个测试状态标签；人员下拉确有 20 项并能点选田松。截图 `.tmp/statistic-filter-manager-customer-detail-20261014.png`、`.tmp/statistic-filter-manager-person-candidates-20261014.png`。人员选中后的整页接受曾超时，保留旧完整画面，不能据此声称筛选后的界面结果通过；延期/系统状态界面和完整前后截图尚未补齐。浏览器并非当前不可连接，不再要求用户仅因员工内嵌浏览器不可用而开启面板。
- F04：批准中文 label + `open/closed` value 的方向。经理确认 `SystemTestDefectSummaryBoardService` 的 state 描述符以 `isClosed()` 投影为 open/closed，独立选项测试与真实响应一致；原方案 S1.4 的中文 value 判断错误，已直接改正下述实施/验收条款。OR 附加谓词整体括号修复同样认可，不能回退裸拼接。
- 金标文件核对：`golden/customer-issue-defect-cause` 的 11 份 JSON 与 HEAD 比较，移除两个人员字段 options 后逐份完全相同；工作簿 fingerprint 无差异。这是本地 GoldenMaster 资源检查，不是黄金全链路门禁通过。证据 `.tmp/statistic-filter-manager-golden-audit-20261014.json`。

### R1（P2，必须修复）：F03 SQL 与权威引擎的字面值语义不一致

- 位置：`IssueFactFilterGroupSqlSupport.containsTextCondition` 直接构造 `%输入%`，`moduleCondition` 的 eq/ne 也直接用 LIKE 匹配模块。按功能本轮接入该编译器后，用户输入 `%`/`_`/反斜线会按 SQL 模式解释；引擎的 contains 是字面子串、eq/ne 是完整成员比较。
- 确定性真实反例：同一 default/CC2026R3 身份，`AND(functionName contains "%")` 返回 40 行/430 项；`OR(moduleName eq "%", functionName eq "绝不存在功能经理审查")` 也返回 40 行/430 项。只读核对当前项目事实中含字面 `%` 的功能、模块均为 0，两组正确结果均应为空。抽查下钻同样进入不匹配记录，所以不能以“主表与下钻数一致”代替条件正确。证据 `.tmp/statistic-filter-manager-edge-20261014.json`；Chrome 实际提交功能包含百分号后，页面仍显示 40 行，截图 `.tmp/statistic-filter-manager-function-condition-20261014.png`。
- 相邻成员反例：本板聚合/明细/映射均按 `[,&]` 分割并 trim 模块；共享编译器 eq/ne 只识别逗号拼接。PostgreSQL 只读 VALUES 对照中，`工具&装配`、`工具 , 装配` 的“工具”成员为真，而现谓词为假；本地事实尚无 `&` 模块，不宣称已在真实源数据复现该边界，也不将其作为容量结论。
- 补正：直接修原共享 SQL 适配，文本 LIKE 复用项目已有字面模式转义及明确 ESCAPE，模块 eq/ne 与本板真实拆分、trim、完整成员比较一致；禁止在看板叠加私有兜底或改引擎语义。核对共享调用者与缓存规则版本，按功能变更结果后提升版本，避免命中 v6 错误缓存。
- 最小验收：先增加会在当前实现失败的求值反例，覆盖 `%`/`_`/反斜线字面值和多模块两类分隔，不只断言 SQL 可编译；修复后用只读本地链路复验上述两组应为 0，再选一个普通中文 AND/OR 主表—下钻对照。无需完整默认套件或黄金全链路。

### R2（交付证据，必须补正）

- `.tmp/by-function-final-depth.json` 的 LEVEL1 样本明确存在主表 4/下钻 5、`allLevelOne=false`，OR 部分 total=0；对应脚本下钻未附主表条件，OR 还使用本板未声明的 priorityLevel。这份文件不是通过证据，不能继续与 `.tmp/by-function-consistency-after-fix.json` 并列证明最终全绿。保留原文件作失败证据，修正/移除失效探针，重新输出最小有效证据并自动断言失败，禁止吞错后用空集合通过。
- 员工聚合测试的 `ApplicationContext failure threshold exceeded` 只是后续症状；仅凭不同类失败、单类复跑通过，不能认定“共享上下文竞争”。在未取得首次上下文异常根因前改记为未确证原因；经理独立 18 项通过不能抹掉该记录，也不要求本单元扩跑数百项。
- `applyDefaultMilestone` 抹掉 OR 是已存在的相邻问题，保留为待单独裁定，不在本次审批中默认为已修复。F05/F06、BI 页面、指标公式与生产缺数据继续保持原边界。

## 员工补正进度（2026-10-15，R1/R2，待经理复审）

- R1 改法（只改共享 SQL 适配，未新增看板私有兜底、未改引擎语义）：`containsTextCondition` 复用项目既有 `LabelGroupFilterOperatorSupport.likeContainsPattern`（先转义反斜线、再 `%`/`_`）并声明 `escape '\'`，空白条件值与引擎一致改为恒假/恒真；`indexedSearchCondition`、`issueIid` 的 contains、客户成员 `IssueCustomerMembershipSqlSupport` 的 contains/notContains 同源转义并显式 ESCAPE；`moduleCondition` 的 eq/ne 由 “`, `' 拼接 LIKE” 改为按 `\s*[,&]\s*` 拆分、`btrim` 后完整成员 lower 比较（`exists` / `not (exists)`），与本板聚合 `regexp_split_to_table`、明细 `loadDetailSources` 与内存 `splitModuleNames` 同口径。
- 共享调用者核对：编译器的生产调用点只有 `IssueFactRecordConditionBuilder.appendFilterGroup`（客户问题列表页、客户/系统测试违规页、系统测试检索页——均为实时分页查询，无规则版本缓存）与本板 `CustomerIssueByFunctionBoardService`；经 `IssueFactRecordRepository` 取数的客户缺陷汇总与系统测试缺陷汇总只按 filters 收口、条件走内存引擎，不经此路径。因此规则版本仅提升按功能板 `v6 → customer-issue-by-function@2026-10-15-v7`（v6 已缓存过错误的通配结果），其余看板不动。
- 反例先行（确证会在修复前失败）：临时把两处还原为修复前形态跑同一批用例——`functionName contains "%"` 命中全部 6 行（正确 1 行）、模块 `钣金` 漏掉 `装配 & 钣金` 与 `钣金&喷涂`、`工具 , 装配` 的“工具”漏判、空白 contains 退化为全表匹配；还原后 `IssueFactFilterGroupSqlSupportTest` 4 项、`FilterEngineSqlParityTest` 2 项失败。恢复修复后定向结果：编译器 10、平价 5、按功能板 6、记录仓储 8 全绿；扩跑 statistics 包 + IssueFact*/CustomerIssue*/SystemTest*/FilterEngineSqlParity/LabelGroup* 共 392 项 0 失败 0 错误（`.tmp-logs/r1-targeted-tests-20261015.log`）。平价测试新增两组“绝对命中集合”断言，避免两侧同时漏判把空结果当一致。
- 本地真实链路只读复验（`.tmp/verify_r1_literal_semantics.cjs` → `.tmp/r1-literal-semantics-20261015.json`，脚本内建断言、任一失败 exit 1，禁止空集合吞错；default/CC2026R3，未造数、未改事实）：经理两组反例加下划线、反斜线、项目名共 6 组全部 0 行 0 项且条件原样回显；基线 40 行/430 项不变；正向对照 `functionName contains 装配` 4 行/12、`moduleName eq 装配` 29 行/55、`moduleName ne 装配` 40 行/371；AND LEVEL1 14 行/76、OR（二级 或 功能含装配）23 行/172、模块等值 29 行/55 三组各抽 3 格，主表计数与下钻总数逐格相等且下钻记录语义全部满足条件（严重程度中文、模块成员确含“装配”）；响应读到的 ruleVersion 为 v7。空值 contains 属解析边界既有契约：`StatisticFilterGroupSupport.validateFilterGroup` 直接丢弃空白条件，主表等于基线，本单元未改。
- 更正上一轮的过度声明：规则说明“按模块/功能展开”步骤的 outputCount 是模块槽位数（无有效模块的议题计 1 个“未设定模块”槽位），主表合计来自 `having` 剔除这些行的 SQL 聚合，两者口径本就不同——基线 435→447 槽位对应主表 430，OR 178→179 对应 172，`moduleName eq 装配` 55→59 对应 55，LEVEL1 恰好 76=76，因此旧报告“末步输出等于主表”只是巧合样本，已作废。条件确实作用于规则说明的议题级样本（435→67/178/55）。该口径差异是既有看板定义问题、非本轮引入，交经理单独裁定，本单元未改。
- R2 证据纠正：`.tmp/by-function-final-depth.json` 仅保留为失败证据（其驱动脚本下钻未携带主表条件、OR 还使用了本板未声明的 priorityLevel），失效探针 `.tmp/verify_by_function_conditions.mjs` 已删除；最终有效证据为 `.tmp/r1-literal-semantics-20261015.json`，`.tmp/by-function-consistency-after-fix.json`（由携带条件的 `.tmp/diagnose_by_function_consistency.mjs` 产出）继续作主表—下钻对照证据。
- R2 归因纠正：聚合测试的 `ApplicationContext failure threshold exceeded` 已复现根因——本机重启后 15433 测试库容器未自启，Flyway 报 `Connection to localhost:15433 refused`，首个 `@SpringBootTest` 上下文加载失败后同类用例被阈值跳过；容器启动后同口径 392 项全绿。旧“共享工作树上下文并发/资源竞争”判断证据不足，予以撤回；昨日那次未留日志，不能断言必然是同一原因，但已证实该失败签名可复现且与本单元代码无关。
- 开发服务与边界：18080 已按当前工作树重启且 `/actuator/health` 为 UP、`/api/auth/current` 正常；18181 前端已恢复监听。本轮未运行、未重建黄金基线，未提交、未推送；混合工作树中其他会话（BI、指标、控制器等）改动未打包进本单元。界面截图与下拉交互仍未补齐，不以全绿日志自封验收。

## 员工实施进度（2026-10-14，待经理审查）

- F01 已完成：客户统计 `toDetailRecord` 与下钻排序改用 `IssueDisplayValueSupport.displaySeverityLevelOrBlank` 和按 `fact.closed()` 的“已关闭/未关闭”；客户延期页按本板真实判据 `IssueSource.open()` 输出中文。事实、条件值 LEVEL1/LEVEL2/LEVEL3/SUGGESTION、指标谓词与候选生成规则（空值不成候选）未变。
- F02 已完成：新增共享候选类型 `StatisticPersonCandidates` 与 `StatisticFilterFieldFactory.textWithCandidates`，字段保持 `type=text` 和完整文本关系清单，只补 `options`。客户统计候选取“来源 + 里程碑”的基础事实（不带成员选择、不应用用户条件），在快照未命中的构造路径内求解，避免 definition 递归；客户缺陷原因候选取本板 `loadSources` 的基础行。
- F03 已完成：按功能板的项目/模块/功能/严重程度条件经 `IssueFactFilterGroupSqlSupport`（已按方案开放为公共契约）编译后整体 AND 到公共范围，主表聚合、明细与规则说明共用同一 `QueryScope`；里程碑等值作为硬范围不再重复编译，其余里程碑关系仍交给编译器；遗留裸业务字段下推被条件表达式单一取代。共享 `SqlPredicate` 与编译器访问边界按方案直接整理，未新增 v2 入口。
- F04 与方案有出入（按代码事实修正，需经理裁定）：方案 S1.4 认定该板描述符期望中文取值并要求只改 label。实测 `SystemTestDefectSummaryBoardService.filterFields()` 的 `state` 描述符产出 `open/closed`，而原 `value` 是中文，选择后永不命中；本板既有的 `SystemTestDefectSummaryBoardGoldenMasterTest` 也以 `value:"open"` 作为契约。因此实现改为 label 中文、value 回到 `open/closed`（与其他看板同源），只保留 label 修正会留下“只改标签未改求值”。
- 实施中发现并修复的新缺陷：`CustomerIssueSqlScopeSupport.withExtraPredicate` 直接把附加谓词拼在 `and` 之后，OR 条件会被 SQL 优先级拆出公共范围（项目/里程碑/排除只约束左半边）。真实链路实测同一单元格主表 14、下钻 5 即由此产生；现附加谓词整体加括号，并由按功能板测试锁定该形状。
- 实施中发现但未在本单元处理：`CustomerIssueMilestoneFilterSupport.applyDefaultMilestone` 在补默认里程碑时把条件组强制成 `AND`，会抹掉用户“满足任意”。按功能板已通过 `QueryScope` 规避（快照键与响应沿用合并形状但保留原逻辑），其余走内存 `StatisticFilterEngine` 的客户看板仍是旧形状；改动共享helper会波及 7 个看板与基线，建议另立工作单元裁定。
- 规则版本提升与原因：`system-test-defect-summary@2026-10-14-v15`（状态选项变更）、`customer-issue-customer-statistics@2026-10-14-v3` 与 `customer-issue-defect-cause@2026-10-14-v10`（人员候选进入 definition_payload）、`customer-issue-by-function@2026-10-14-v6`（条件真正生效改变行结果；先落 v5，随后 OR 优先级修正再次改变主表结果，同工作单元内续升为 v6，未发布的 v5 不做兼容）。客户延期板只改下钻展示、不触碰 definition/rows，未提升版本。
- 证据文件：`.tmp/statistic-filter-before-20261014.json`（修改前复现：#2216 `LEVEL2/opened`、人员候选 0、按功能无条件与 LEVEL1 条件 40 行逐格相同、系统状态 label 为 open/closed）、`.tmp/statistic-filter-after-20261014.json`、`.tmp/statistic-filter-final-20261014.json`、`.tmp/by-function-consistency-after-fix.json`、`.tmp/by-function-final-depth.json`、`.tmp/by-function-level1-export.xlsx`；驱动脚本 `.tmp/verify_statistic_filter_semantics.mjs`、`.tmp/diagnose_by_function_consistency.mjs`、`.tmp/verify_by_function_conditions.mjs`。
- 本地真实链路复核（CC2026R3 现有 DOCKER 来源，只读，未造数、未改事实）：客户统计 #2216 下钻为“二级缺陷/未关闭”，候选同为中文；提交人候选 20 项、处理人候选 84 项且字段仍为 text 全关系清单；客户延期明细为“未关闭”；系统测试状态选项 label 中文、value 回到 open/closed。按功能板无条件 40 行/430 议题与修改前逐项相同；加 LEVEL1 后 14 行/76 议题，抽查 4 个单元格的主表计数与其下钻身份总数逐格相等且记录全为一级缺陷；OR（二级缺陷 或 功能含“装配”）23 行、抽查 4 格同样逐格相等，修正前该 OR 让主表虚增到 14（正确值 5）。同一条件下导出工作簿问题数量合计 76、与主表一致。（更正见下：本次“规则说明末步输出 76”属巧合样本，不能作为口径一致的结论，见 2026-10-15 补正记录第 4 条。）
- 定向基础回归：`mvn -o test` 选择 statistics 包 + IssueFact*/CustomerIssue* 共 289 至 291 项；单独复跑全部通过。聚合跑次中出现过两次不同 `@SpringBootTest` 类的 `ApplicationContext failure threshold (1) exceeded`（先后为客户需求事实持久化板与客户延期刷新范围版本板），单类复跑均 0 失败，判定为共享工作树上的上下文并发/资源竞争，不在本单元内追查。（该归因证据不足，2026-10-15 已按 R2 更正为已复现的 15433 测试库容器未运行，见“员工补正进度”第 5 条。）本地看板金标 `golden/customer-issue-defect-cause` 已按 F02 有意变化重生成，逐项 diff 确认仅两个人员字段的 options 由空变为事实候选，行、元数据与工作簿指纹零变化。
- 界面验收未完成：Qoder 内嵌浏览器无可见绘制面（viewport 0x0，pointer/screenshot 均不可用），与经理本轮“浏览器连接仍不可用”一致；`take_snapshot` 可读到登录弹层但无法继续渲染。截图与下拉交互需用户打开内嵌 Browser 面板后补做；本轮不声称已通过界面验收。
- 黄金基线：本单元未运行、未重建快照。预期有意差异至少涉及客户统计与延期明细的中文取值、客户统计与缺陷原因的人员 options、系统测试状态 options、按功能带条件主表/下钻/导出/规则说明，以及四处规则版本字符串；发布前按 AGENTS.md 门禁执行并交经理审阅。

## 恢复线索

- 首条命令：`git status --short`；随后读取 `docs/progress.md`、本计划及 `customer-statistics-filter-review-20261013.md`。
- 当前实现基点含其他会话在途改动；本轮不提交、推送或重启开发服务。
- 基点 `main@3a105ddf` 仅作恢复线索，实际修改必须以最新工作树为准。保护持续可读、指标新增、快速筛选成员、默认收起/成员文案等其他工作单元，禁止用基点文件整体覆盖。
- 交接顺序：员工实现并回报 → 用户将结果交经理 → 经理独立核对 diff、运行证据和定向反例；员工不得代替经理宣布审查通过。

## 目标与边界

- 用户要求调查：客户统计条件字段与页面是否匹配；下钻 `LEVEL2` 等英文值是否遗漏中文转换；主表快速筛选是否完整、与下钻筛选的职责是否合理，以及其他平台统计板是否同样存在现象。
- 本轮追加要求：把已调查问题转成可执行方案，修复技术缺陷后交经理审查；不能将字段差额或尚无反馈直接转换成新增产品需求。
- 实施成功标准：中文下钻及候选与其他表一致；已存在的人员快速入口可选择真实人员；按功能板已声明的条件确实参与主表、下钻、规则说明和导出；空结果仍保留合法列定义与空集合解释。
- 本轮经理仅修改文档。员工后续可按本方案修改必要实现与基础测试，不改变来源/ODS/事实模型、指标公式和公共范围；不新增 API、字段、数据库迁移或权限，BI 页不在范围内。

## 约束与背景

- 已有裁定：客户/模块/功能为 VALUE/MISSING 成员控制参数，界面搬入快速筛选行；此前明确不另加同维度条件字段，不含聚合结果过滤。本轮用户要求重新调查，不代表撤销这些已确认边界。
- 平台业务规则第 6.1 条约束范围/条件/快速筛选入口优先级；第 5.8 节约束客户统计成员、贡献集合和同源下钻。
- 验证沿用户已给出的偏好：定向基础测试与本地 GitLab 真实链路、实际界面验收；日常不运行完整套件或黄金全链路。发布前的黄金门禁及快照审批仍按 AGENTS.md 执行，本单元不得自行重建快照。

## 证据与根因

### 客户统计字段列表与主表不是同一对象

- `CustomerIssueCustomerStatisticsBoardService.filterFields()` 声明 8 个源记录维度：里程碑、严重程度、优先级、测试状态、议题类别、提交人、处理人、议题状态。里程碑由独立范围控件承接，`StatisticBoardView.conditionFilterFields` 按维度排除它，所以截图显示剩余 7 项，与当前实现一致。
- 客户/模块/功能由 `statistic-board-controls.ts` 单独登记，走成员控制参数及 VALUE/MISSING 令牌；没有登记进上述字段和条件求值描述符。它们已搬入主表快速筛选行，单选成员即时生效；目前不能作为条件表达式的字段参与任意 AND/OR 组合。
- 条件列表不是主表 51/52 列汇总结果的字段列表，也不是全部下钻列的列表。是否应加入客户名称或统计结果字段属于产品范围重新讨论；本轮不把既有隔离设计直接判为漏实现。

### 中文显示遗漏

- 窄事实查询直接保留 `issue_fact.severity_level/issue_state` 原值；本地当前开放修订确有 `LEVEL1/LEVEL2/LEVEL3/SUGGESTION` 与 `opened/closed`。
- 客户统计 `toDetailRecord` 将 `fact.severityLevel()` 和 `fact.issueState()` 原样写入 `severityLevel/state`，共用前端 `detailCellValue` 仅转字符串，没有中文转换。因而 `LEVEL2` 不会自动变为“二级缺陷”，开闭状态也会输出英文；下钻候选同样会使用这些原值。
- 真实接口复现：本地管理员准备 `customer-issue-customer-statistics` 的 default/CC2026R3 完整视图，再从非空 `defect_total` 单元格请求下钻，记录 2216 返回 `severityLevel=LEVEL2`、`state=opened`、`priorityLevel=P2`。报告 `.tmp/customer-statistic-filter-live-audit.json` 保留最少样本和列定义，不含登录凭据。
- 其他客户缺陷汇总、按功能、响应效率和系统测试汇总/延期/阶段统计下钻在记录投影时转换严重程度，开闭状态转换为“已关闭/未关闭”。现有共享 `IssueDisplayValueSupport` 已定义四类中文严重程度；未来修正应落在展示投影，保留事实和条件求值的枚举，不改指标口径。P1/P2/P3 与其他表一致，是既有优先级标识。
- 相邻技术差异：客户延期页也直接输出原始 `issue_state`；系统测试缺陷汇总的状态条件选项把 `open/closed` 用作 label、中文开闭值用作 value，代码与最近保存定义一致。均未做本轮界面复测，不能声称所有页面显示已对齐。

### 主表与下钻筛选的影响不同

| 入口 | 字段来源 | 发送方式 | 对数据的影响 |
| --- | --- | --- | --- |
| 主表条件筛选 | 后端 `definition.filters`，再排除独立范围维度 | `filterGroup`，可表达 AND/OR | 先筛选源议题，再按该板口径聚合；可能改变数量、比率的分子和分母 |
| 主表字段类快速筛选 | 同一字段集合中命中前端固定 13 个 key 且类型为 text/select 的子集 | 转为同一 `filterGroup` 中的 QUICK 条件，冲突时由条件接管 | 与对应主表条件共用业务求值路径，不是只隐藏汇总行 |
| 客户统计成员控件 | 当前范围成员目录 | 独立 `customer/module/function` 及 `*Kind` | 收窄成员范围，保持原成员和去重语义，不参与通用条件接管 |
| 下钻快速筛选 | 明细全部列（包含展开区）按类型/key/标签规则生成 | `detailFilter.<key>` 或 `detailKeyword` | 在当前单元格的计数/分子/分母/周期样本集合内检索；不回写主表指标 |

- 客户统计后端明确先限定行与贡献集合，随后在 `AbstractStatisticBoardService.sliceDetailRecords` 中应用下钻过滤、生成候选并分页。候选基于当前集合过滤前的完整记录，而非整个平台；前端另补当前页值。两处均有 200 候选限制，不能把字段存在等同于所有候选都可见。
- 下钻控件也没有覆盖每个明细列：编号/标题为输入，人员/状态/模块等为下拉，创建/更新时间不生成独立时间控件；任意关键字能做文本定位，不等于日期范围查询。集合候选拆分与子串匹配的已知问题仍归独立 `platform-quick-filter-members-20261013.md`，本轮不冒领修复。

### 十个共用统计页的声明基线

下表来自最近 READY 持久定义，并逐组对照当前工厂声明和前端名单；不包含独立范围控件，不代表全部字段已实测可用。客户统计的三个成员控件单独计数；已有条件接管时实际可见快速字段会减少。

| 页面 | 条件字段数 | 主表字段类快速数 | 条件有、主表快速未展示的字段 |
| --- | --- | --- | --- |
| 客户问题统计 | 7 | 7＋3 个成员控件 | 无普通字段差额；客户/模块/功能不在条件字段中 |
| 客户缺陷汇总 | 11 | 10 | 项目名称 |
| 客户缺陷原因 | 10 | 9 | 项目名称 |
| 客户延期问题 | 6 | 5 | 项目名称 |
| 客户响应效率 | 8 | 7 | 项目名称 |
| 客户按功能统计 | 4 | 2 | 项目名称、功能名称 |
| 系统测试缺陷汇总 | 13 | 7 | 项目名称、延期原因、开闭状态、提交时间、更新时间、标签 |
| 系统测试延期分析 | 0 | 0 | 仅独立测试阶段范围；下钻另有快速筛选 |
| 系统测试缺陷原因 | 0 | 0 | 同上 |
| 系统测试阶段统计 | 0 | 0 | 同上 |

- 系统测试缺陷汇总使用 `state`，前端主表名单只含 `issueState`；虽然维度冲突模块认识两者等价，快速字段名单不做等价归一。延期原因、功能名称也不在该名单；日期字段还受类型过滤限制。这些解释“为什么没展示”，不自动证明“应该展示”。
- 候选接线技术问题：客户统计与客户缺陷原因后端把提交人/处理人声明为 text 且 options 为空；前端却将人员键统一渲染为 select，渲染器仅使用传入 options，没有外部人员候选补齐，`SmartSelect.allowCreate` 默认 false。客户统计真实主表接口确认这两个字段 type=text、options 数均为 0；客户缺陷原因当前仅核对代码与保存定义。它与是否扩充字段范围是两个问题，浏览器操作仍待复测。
- 按功能严重程度条件生效异常已实测：在同一完整来源版本及 CC2026R3 范围分别请求未加条件主表、`filterGroup={logic:AND,conditions:[{fieldKey:severityLevel,operator:eq,value:LEVEL1}]}` 主表，第二次响应 `appliedFilterGroup` 明确保留该条件，但全部 40 行与每个单元格逐项相同。本地事实该范围同时存在一级、二级、三级和建议类。源码对应根因是 `CustomerIssueSqlScopeSupport.boardScope` 只把里程碑转为 SQL，`withoutReservedFilters` 会删除整个 filterGroup，按功能主表只另行应用模块辅助规则，未将严重程度条件用于查询/求值。功能/项目等其它声明条件仍需逐项核验，不从一个反例推断全部。本轮未更改此路径。

### 场景与待讨论边界

- “只统计 P1 客户问题并重算修复率”属于主表源记录条件；“在已修复明细中找某人处理的议题”属于下钻检索；“只显示缺陷数大于 10 的客户”属于聚合结果过滤，当前并未提供。
- 客户/模块的精确成员选择已可表达简单范围收窄；把客户名加入条件后是否需要多选、客户分支与其它字段任意 OR，是另一种能力，不能仅搬迁控件解决。
- 主表新增源字段可能改变指标分母；把下钻字段机械搬到主表存在误解风险。哪些需要常用入口、哪些只保留条件或下钻，需要真实使用场景，不能由代码或“尚无人反馈”推断使用频率。

## 方案与步骤

### 问题登记与实施边界

| ID | 问题及证据 | 解决方向 | 本单元状态 |
| --- | --- | --- | --- |
| F01 | 客户统计真实下钻 2216 输出 LEVEL2/opened；客户延期页也直接投影原始开闭状态 | 在记录投影使用现有中文显示规则，覆盖对应排序和候选 | 实施；延期页先补定向复现再修改 |
| F02 | 客户统计人员字段真实 options=0；客户缺陷原因同一声明/渲染组合 | 为已有提交人/处理人入口补同范围、同版本事实候选，保留字段和关系语义 | 实施；原因页先补定向复现 |
| F03 | 按功能页受理 LEVEL1 条件，40 行全部单元格与未筛选相同 | 对本板全部既有声明条件建立统一求值，并贯通统计/下钻/导出/规则说明 | 实施；其他字段逐项补反例 |
| F04 | 系统测试缺陷汇总状态 options.label 为 open/closed，value 为中文，与描述符相反 | label 改中文，value 改为描述符实际求值的 open/closed | 经理认可契约修正；不增加快速字段 |
| F05 | 客户名称不在条件字段中，但已有独立成员入口 | 现有成员语义保持；是否允许客户参与 AND/OR、是否需要多选另行讨论 | 待产品取舍，不实施扩字段 |
| F06 | 十页快速名单与条件/下钻列不完全一致；日期、功能、延期原因等存在差额 | 先按使用场景决定常用入口、条件入口或下钻检索；不按列数机械补齐 | 待产品取舍，不实施全量覆盖 |

### S0：实施前锁定证据与目标版本

- 读取本计划、进度、相关业务规则及最新工作树；重新确认 F01—F04 未被其他会话修复，记录本单元开始时的文件摘要/差异范围。
- 补能在原实现失败的最少基础反例。已有统计测试路径见 `backend/src/test/java/com/data/collection/platform/service/statistics/`，前端沿现有 `src/**/*.test.ts`；不要用只检查源码字符串的测试代替筛选命中、记录值或真实组件行为。
- 测试使用替身、隔离测试库或受控回滚空间；不得对共享开发库执行 truncate、清空发布视图或批量重写事实来制造样本。提交前先核对被调用测试的 setup/cleanup，不能盲跑整类历史集成测试。

### S1：中文投影与状态选项修正（F01/F04）

主要文件：`CustomerIssueCustomerStatisticsBoardService.java`、`CustomerIssueDelayIssuesBoardService.java`、`SystemTestDefectSummaryBoardService.java`；公共严重程度显示复用 `IssueDisplayValueSupport`。

1. 客户统计 `severityLevel` 使用现有严重程度显示帮助器，`state` 根据 `fact.closed()` 输出“已关闭/未关闭”，保持关闭时间与源状态共同判定。内部 `CustomerIssueFact`、条件值 LEVEL1/LEVEL2/LEVEL3/SUGGESTION、指标谓词保持原值。
2. 客户延期下钻按其真实关闭判据输出中文；不能把测试状态当作 GitLab 开闭状态。先补源码路径对应的反例，确保只修原始开闭展示。
3. 对受影响字段的排序复用其他同类下钻的现有规则，列 key 与请求 sortField 不变；主表数量、贡献成员及默认排序不得因中文转换变化。下钻候选应与最终显示值一致。
4. 系统测试缺陷汇总 `StatisticFilterOption` 为 label=未关闭/已关闭、value=open/closed，与本板 `isClosed()` 求值描述符绑定；中文仅用于显示。原方案要求保留中文 value 的判断已由经理纠正，不保留错误取值兼容入口。
5. 空值遵循同类字段现有占位规则，P1/P2/P3 标识保留；不创建第二套中文枚举、前端猜值或 raw/display 双轨字段。保持测试状态全部标签及 `已修复/完成` 整体。

### S2：补齐既有人员入口的事实候选（F02）

主要文件：`CustomerIssueCustomerStatisticsBoardService.java`、`CustomerIssueDefectCauseBoardService.java`；只在必要时扩展已有候选帮助器，前端已有人员下拉逻辑可复用。

1. 为 `authorName/assigneeName` 的现有字段补 `options`，候选直接来自本板已发布事实的 `author_name/assignee_name`；去空、去重，按现有人员候选顺序处理，label/value 使用真实事实人员名。
2. 候选基于同一固定读取身份、来源、里程碑及公共业务范围的基础集合；不能按主表页码、当前选中的客户/模块/功能或人员值收缩，也不能混入其他里程碑/来源人员。复用已有同范围事实读取，避免每个字段逐行查人员或新增网络人员目录查询。
3. 优先保留现有字段 key、type=text 与 text 关系清单，仅提供 options；前端已有人员 quick-select 会使用它们，条件筛选原有 eq/ne/contains/isEmpty/isNotEmpty 继续可用。不得为套 select 工厂悄悄删除 contains，或把人员快速控件改成自由输入来绕过候选问题。
4. 在主表构造定义/快照前统一组装候选；解析 definition 不得再次调用依赖该 definition 的 loadFlags 造成递归。可将“固定范围事实→候选”作为独立无副作用步骤，在已持有版本的调用点接线，不能从当前可变事实旁路取数。
5. 有事实人员时下拉非空、选中后沿原条件路径生效；当前基础范围确实无人时允许空候选。条件查询仍可表达空值，不制造“未知人员”假候选；不改变成员控件的加载/失败/重试和 VALUE/MISSING 规则。

### S3：恢复按功能板声明条件的真实执行（F03）

主要文件：`CustomerIssueByFunctionBoardService.java`，按需使用/调整 `IssueFactFilterGroupSqlSupport` 的访问边界及既有 SQL 适配；`CustomerIssueSqlScopeSupport` 为共享范围帮助器，修改前须核对所有调用者。

1. 本板当前声明为：projectName、moduleName、functionName、severityLevel、milestoneTitle。逐项确认字段物理绑定和公开关系，不增加字段，不仅修 LEVEL1 单个值。公共范围继续为项目 325、2026 起点、现有排除规则、有效模块/功能及已选里程碑。
2. 一次规范化接受的 filterGroup：独立里程碑作为硬范围，与用户条件整体用 AND 收口；用户条件内部保留 AND/OR。不能把 OR 逐个转成 AND，也不能让其中一条条件绕过项目/里程碑/排除范围。
3. 默认保留现有 SQL GROUP BY 聚合，复用既有 `IssueFactFilterGroupSqlSupport` 的绑定参数编译和字段语义；需要跨包调用时直接整理原编译器访问边界，不写一套私有操作符 switch、别名或备用编译器。SQL 语义以 `StatisticFilterEngine/StatisticFieldDescriptor` 为准，对本板实际使用的操作加最少 SQL/引擎命中集合对照。
4. 标准化表达式覆盖旧的业务字段下推，查询只保留必要的来源/公共范围参数；避免 filterGroup 和遗留 raw 字段重复求值导致 OR 被二次 AND。无法支持的已声明关系必须补齐同一编译器，不能忽略条件；不要将本来可下推的统计改成无规模证据的整表内存处理。
5. 多模块议题继续按模块展开计数；正向模块条件的展示行收口、OR 时的模块行为沿当前公共辅助规则，补多模块反例验证，不按纯标题猜模块。同一过滤策略用于 `loadFunctionBuckets/loadSources/loadDetailSources`，保留单元格携带的实际模块和功能地址。
6. 主表计数、实际下钻议题身份、规则说明样本及 Excel 都来自同一生效条件和读取版本。修复后不能只让下钻被筛选而主表数字不变，也不能只更新 appliedFilterGroup 文案。
7. 无过滤时的原范围、计数、列分组、模块排序、功能数量倒序及并列次序保持；不覆盖已经完成的测试状态多标签、独立开闭详情和 bugStatus 排序。

### S4：缓存、最小验证与交付

- 逐板检查需要更新的规则版本：快照保存 definition_payload，修定义/候选或过滤结果时必须使旧快照失效；沿当前版本直接更新原服务，不创建 new/v2 板或双缓存。记录每个提升的版本和原因，既有完整事实仍应可读。
- 只运行与本次改动直接相关的基础用例和前端类型/定向检查；不跑默认全量后端/前端、性能大套件或黄金链路。新用例下表锁定关键行为，不为凑数量镜像实现。
- 本地 GitLab 真实验证沿当前 DOCKER 实际采集来源，先只读选择有不同级别、多个模块、不同人员/状态的样本；如需生成新样本，只在明确本地的受控议题操作并恢复，走正常镜像→自动事实→完整发布→页面/下钻，禁止直接改 ODS/事实冒充真实链路。
- 浏览器至少覆盖客户统计、客户缺陷原因、按功能和系统缺陷汇总受影响入口，保留可核对字段/候选/计数的截图；客户延期状态若修正，同样留截图。单纯返回 200 不算功能验收。
- 日常不更新黄金快照；交付列出受影响既有端点和有意差异，发布前按 AGENTS.md 执行黄金门禁及审批。不得声称本后端修正预期零快照差异；当前完整黄金通过只属于此前代码状态。
- 更新本计划完成项/剩余项和进度，长期事实按原权威归属维护；员工提交经理审查前保留本计划，经理通过后再压缩长期结论并删除计划。混合工作树不得打包提交其他任务改动，未指定远端时不推送。

### 基础验收与经理审查矩阵

| 项目 | 最少有意义的反例/实际核对 | 通过标准 |
| --- | --- | --- |
| F01 中文值 | LEVEL1/2/3、SUGGESTION、空严重程度；open/closed 和有关闭时间的样本 | 展示遵循公共中文规则；主表内部条件值、计数和事实不变；候选与记录同语义 |
| F02 人员候选 | 有人员范围、空人员、跨来源/里程碑隔离、带成员重载 | 正确范围人员可选择、筛选命中；候选不随分页/已选成员丢失；文本关系清单不缩水 |
| F03 条件执行 | 级别匹配/不匹配、功能/项目文本、AND/OR、多模块，范围外哨兵 | 验证预期议题身份及数量，不只断言“结果变化”；所有返回记录满足条件，OR 不越界 |
| F03 一致性 | 同一真实单元格的计数、下钻全身份集合及导出对应单元格 | 主表值等于贡献身份集合；规则样本同条件；无过滤基线不漂移 |
| F04 状态选项 | 中文 label、open/closed value、选择后命中 | 标签中文与本板状态求值契约一致 |
| 共用前端 | 当前条件接管、成员清除/折叠、状态标签及下钻局部筛选 | 原成员/两层筛选职责不变；不重复渲染、不退回加载中、不把英文挪到别的控件 |

经理审查顺序：核对实际 diff 范围与 F01—F04 → 检查基础测试及失败前/修复后证据 → 在本地真实数据上复核字段、人员和至少一个 AND/OR 反例 → 对照主表/下钻/导出身份和数值 → 检查缓存/文档及未验证边界。发现 F05/F06 扩范围、弱化断言、直接修改事实、只改标签未改求值或无界面证据，不能批准完整交付。

### 员工交回格式

1. 当前 commit/工作树摘要和本单元改动文件；其他会话改动单独标明。
2. F01—F04 逐项完成/未完成，修改前复现、根因、最终改法及规则版本；待讨论 F05/F06 保持。
3. 基础测试名称/命令/通过失败跳过数；本地来源、样本议题、镜像/事实运行号、发布身份、期望与实测，以及恢复核对。
4. 界面截图路径和主表/下钻/导出对照证据；当前 18080 health 与 18181 状态。
5. 未验证事项、黄金发布差异清单、风险、文档更新；明确写“待经理审查”，不以全绿日志自封验收。

## 决策记录

- 本单元选择修复 F01—F04 既有技术行为；F05/F06 的新增字段、成员条件组合、主表快速覆盖和聚合结果过滤不实施。
- 人员候选补齐优先保持 text 字段及现有关系，通过已有 options 契约接入，避免用改控件形态掩盖缺数据。
- 按功能默认保留 SQL 聚合，复用原编译器和公共引擎语义；不选择未测量的整表内存重算或私有条件实现。
- 实施与审查分离，由员工提供可复核交付，经理决定审查结论；不代替用户裁定产品扩展。

## 接口契约

- 无新增 API、字段 key、表结构或权限。已有 `/api/statistic-boards/{boardKey}`、`/details`、`/export`、`/rule-explanation`、`/control-options` 沿现有读取视图、来源与范围契约。
- 有意响应变化：客户统计下钻 `severityLevel=LEVEL2`→二级缺陷、`state=opened`→未关闭；候选及排序同步。人员字段保留 key/type/operators，options 从缺失变为基础范围真实人员。系统状态 options 的 label 改为中文、value 改为描述符实际求值的 open/closed。
- 有意行为变化：按功能原有已声明 filterGroup 从被忽略改为实际筛选，因此带条件主表/下钻/导出结果变化；无条件结果保持。列定义/规则版本和缓存变化在交付中逐项说明。
- 同版本来源、业务日、成员 VALUE/MISSING、population 和 sortField 仍使用原契约，不增兼容别名或回退字段。内部 SQL 编译器如需跨包复用，直接更新原类/方法访问与契约注释，不包一层 v2 转发。

## 风险与假设

- 后端声明字段、前端可见字段与实际可求值字段可能不同；本轮已分开登记，实时行为仍需复测。
- 下钻贡献集合可能为分子、分母或周期样本，不能把其字段机械搬入主表筛选而改变指标含义。
- 无业务日志或使用证据时不能判断场景频率；其他在途改动需保护。
