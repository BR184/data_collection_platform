## 进度与中间物

- 阶段：2026-10-29 员工已完成实现与代码级验证，等待本会话审批；未合并 main、未打包、未部署。
- 已完成：`matchesMetric` 增加 `module_total -> IssueSource::isRegularMetricIssue`；新增服务回归测试并通过公共 `loadBoard`/`loadDetail` 复现修复前失败、修复后通过。
- 任务归属：绝对目录 `D:/projects/dcp-system-test-drilldown-20261029`；分支 `系统测试模块总数下钻修复-20261029`；基线 `main` = `efbd6108aa0239c1a976e9741ab3a459e41814cb`；独立 worktree，未改动主混合树。
- 环境归属：定向测试与默认套件仅使用既有 Maven 与 `qaflex-test-postgres-15433` 测试库机制；未启动业务后端、未写业务库或本地 GitLab、未使用业务端口。
- 验证状态：定向回归 10/10 通过（含黄金主表测试）；后端默认套件结果见下方“实施与证据”；页面验收未执行，条件见同节。
- 当前阻塞：本机访问 115:20001 失败，本地库无截图五条议题；不能把截图标签和排除原因当作生产数据库核验结果。页面验收另需可安全隔离的实例，见“实施与证据”第 6 条。

## 恢复线索与开工门禁

- 首条恢复命令：在准备使用的绝对目录执行 `git status --short --branch`、`git worktree list`、`git rev-parse HEAD`，随后读取最新 `AGENTS.md`、`docs/progress.md` 和本计划。
- 调查目录：`D:/projects/data_collection_platform`；HEAD `84e7c023449de190e722d79d027accb062358172`，分支 `表单开发未提交现场-20261028`，存在无关表单等改动。本轮只在此维护交接文档，员工不得在该混合树修改业务或测试。
- 2026-10-29 方案复核时本地 main 为 `efbd6108aa0239c1a976e9741ab3a459e41814cb`；这是观测值，不自动证明它是生产部署版本或已完成所有发布门禁。员工从重新核实的稳定 main 提交建立中文任务分支和独立 worktree，或复用核实归属的同任务目录；遵循 AGENTS 的开工门禁并补齐最新协作规则。
- 首次代码修改前在本计划记录员工任务绝对目录、分支、基线、HEAD 和环境归属。单元测试不使用业务库、业务端口或 GitLab 写回；页面验证若需要服务，仅使用明确归属的隔离库、端口及构建输出，默认套件所需 PostgreSQL 按既有测试机制隔离。
- 前序计划/提交：无本题实施提交；只读调查来自本会话。方案涉及的服务在调查树无未提交差异，复核 main 中仍缺少相同分支；实施前再次检查，避免重复修复。

## 原始需求、成功标准与范围

用户要求修复系统测试缺陷汇总中“模块总数 3、点击后明细 4”的问题，并将方案交给 AI 员工实施，再交本会话审批；测试应与该小修复相称。

权威规则见 `docs/platform-page-business-rules.md` 4.3 第 4、5 条：同一模块和指标的主表数值与下钻总数一致，建议类单独统计，不进入模块总缺陷数。

成功标准：同一来源版本、阶段及过滤范围内，常规缺陷 3 条、可见建议类 1 条时，`module_total` 主表为 3，明细 `total=3` 且身份集合正好为三条常规缺陷；`suggestion_total` 主表及明细均为 1，仍能看到那条建议。总计行遵循同一规则。

本单元只修正总数下钻。#33743“已关闭＋未复现”的修复率归属、新发议题修复率、GitLab 标签解析、同步刷新、事实构建、客户问题看板及前端交互均不纳入；不改变过滤默认值、接口结构、主表数字或建议类列，不新增迁移、重建任务、开关或兼容路径。

## 根因与证据

- 服务：`backend/src/main/java/com/data/collection/platform/service/statistics/SystemTestDefectSummaryBoardService.java`。
- `SummaryCounts.from` 先通过 `IssueSource::isRegularMetricIssue` 得到 `regularIssues`，以其大小计算主表 `module_total`。
- `loadBoardScopedSources` 返回常规缺陷和可见建议类的并集，以支持 `suggestion_total`；这是合理的共享输入，不能在此整体排除建议类。
- `doLoadDetail` 对基础集合执行行过滤及 `matchesMetric(request.columnKey())`。`matchesMetric` 缺少 `module_total`，落入 `default -> i -> true`，导致模块总数下钻包含建议类。
- 截图明细可见 #33894、#33782、#33743、#33705；#33782 显示建议类，符合此缺陷的表现。截图并不能替代 ODS 标签关联、事实排除原因及生产 JAR 的核验，#33767 的生产关闭/排除状态未核实。

## 选定实现与步骤

1. 完成隔离与基线核对后，先添加经过公共服务入口的回归测试，在修改生产逻辑前复现主表 3、总数下钻 4。保存测试名和失败断言，避免只用手工计算证明。
2. 在现有 `matchesMetric` 的 switch 中增加唯一目标分支：

   ```java
   case "module_total" -> IssueSource::isRegularMetricIssue;
   ```

   复用既有领域判定，不复制建议识别条件，不针对实际议题编号或标题特判。
3. 不修改共享基础集合，也不把 default 整体改为排除建议，后者会扩大其他指标行为变化。默认分支的通用重构或未知列校验不属于本修复。
4. 当前 `loadDetail -> doLoadDetail` 直接按读取上下文计算，主表快照数值不变；默认无需修改 `RULE_VERSION`、清缓存或预热快照，更不需要重建事实、全量同步。员工若在所用稳定基线发现额外明细缓存，须先记录实际路径及必要失效方式，不能猜测后盲目加版本。
5. 验证后更新本计划的实施与证据。现有业务规则已经完整覆盖该修复，不另造业务规则或 ADR；方案收口后按 AGENTS 将必要状态归入进度，删除活动计划，保留 Git 历史。

## 最小充分测试

使用 JUnit 5/Mockito，复用现有系统测试汇总服务测试的构造、阶段和读取上下文夹具。建议新增同目录 `SystemTestDefectSummaryBoardServiceTest.java`；若已有合适服务行为测试类，直接复用。不要扩充只为锁主表快照的 GoldenMaster 夹具。

| 用例 | 输入与入口 | 必须断言 |
| --- | --- | --- |
| 模块总数与下钻一致 | 同阶段、同模块的三条常规事实、一条建议事实、一条已公共排除的事实；调用公共 `loadBoard`、`loadDetail`。以普通模块行及 `__total__` 两个行键参数化复用同一数据 | 对应主表 `module_total=3`，明细 `total=3`，返回恰好三条常规议题身份；建议和已排除事实均不在集合中 |
| 建议类下钻仍可用 | 复用上述夹具，经公共 `loadBoard`、`loadDetail` 查询 `suggestion_total` | 对应建议列为 1，明细 `total=1`，返回的恰好是建议议题 |

- 这两类行为足以覆盖此次分支修复；模块行和总计行可参数化，不为每个状态、建议别名、分页参数、空值或非法请求组合新建测试矩阵。
- 夹具表达已归一化事实，不伪称生产标签实测；以小型独立测试编号表示语义即可。建议判定及公共排除的内部组合继续由现有领域测试承担，不重复覆盖其实现。
- 只替换事实数据和外围依赖，保留主表聚合、范围匹配、指标选择、下钻筛选和响应组装的真实实现。主表快照替身应执行传入的计算器，不直接返回手写的预期主表。
- 断言数量及议题身份集合，不反射私有方法，不断言私有字段、调用次数、switch 文本或源码行号。无需新建 Testcontainers 场景、并发/性能/同步测试或端到端框架。

## 验证与交付

1. 同一夹具留存修改前失败、修改后通过记录；修复后先执行相关服务测试及现有 `SuggestionMetricRulesTest`、`SystemTestDefectSummaryRuleExplanationTest`、`SystemTestDefectSummaryBoardGoldenMasterTest`。测试基线和命令以任务树为准，不使用混合树构建产物。
2. 送审前在最终代码版本运行一次后端默认 `mvn test`，使用仓库 Maven 入口；该默认套件含真实 PostgreSQL 集成测试，预先核实测试库归属。若失败，分别报告失败范围和根因，不把定向通过冒充默认套件通过，不靠重试或忽略转绿。若默认套件已覆盖同一最终代码的定向范围，无需再无理由重跑。
3. 本次不改前端，不要求新增前端测试或全量前端构建。在可用的隔离页面/既有授权测试实例做一次最小验收：同一读取上下文点击模块总数 3，看到三条常规缺陷；打开建议类 1，看到一条建议。保存两组请求参数、响应总数/身份集合及页面截图。环境不具备时明确缺失条件，代码回归与页面验收分开报告，不改生产数据制造样例。
4. 只有已能访问目标实例时才做五条样本的限量只读核验，核对真实来源身份、ODS 标签/关联、当前可见事实与页面集合；不能直接扫描所有修订计数。当前无法访问生产不阻止本地实施和送审，但生产数据/部署归属不得标为已验收。
5. 日常修复不运行 `-Pgolden-baseline`，不新增目录用例或修改黄金快照来满足形式；既有端点不变，默认套件运行现有覆盖护栏。若后续获准出包/部署，再在冻结候选执行 AGENTS 发布门禁；出现有意黄金差异须先展示并获确认，不自行 update。
6. 提交前执行 `scripts/check_worktree_artifacts.py`、`scripts/check_runtime_artifact_locations.py`、`scripts/check_text_whitespace.py`、`git diff --check`。本单元没有迁移，不改 Flyway 锁文件。只提交任务文件，按 AGENTS 同步任务分支双远端并回读，不合并 main、不正式打包或部署。

## 送审材料与审批标准

员工交付以下可复现信息，再由用户交回本会话审批；本方案不是实现通过或发布批准：

- 绝对任务目录、分支、稳定基线、最终提交、改动文件清单，及 `git status`；提交/双远端回读结果或真实阻塞。
- 核心差异及为何只需补 `module_total` 判定；说明未改变共享输入与主表统计。
- 回归测试修改前失败与修改后通过、最终默认套件和仓库门禁结果，附日志路径。结果必须对应最终提交，不能沿用先前版本日志。
- 最小页面验收材料或明确的未验证部分；涉及真实样本时附只读来源与观测时点，不交付凭据或无关业务明细。

审批关注三件事：计入集合与主表一致、建议类独立能力保留、改动及测试与问题规模相称。审核方复核差异并定向重跑必要测试，不再创建重复全链路、防御性矩阵或与本缺陷无关的审批门槛。需要补证据时明确其与缺陷的关系。

## 实施与证据（2026-10-29 员工）

1. 代码差异：仅 `SystemTestDefectSummaryBoardService.matchesMetric` 增加一行 `case "module_total" -> IssueSource::isRegularMetricIssue;`。该方法只被 `doLoadDetail` 调用，主表 `SummaryCounts`、共享基础集合、`RULE_VERSION` 与快照均未改动；无前端、同步、事实构建、迁移或 Flyway 变更。
2. 新增测试：`backend/src/test/java/com/data/collection/platform/service/statistics/SystemTestDefectSummaryBoardServiceTest.java`，只替换事实数据与外围依赖，主表聚合、范围匹配、指标选择、下钻筛选和响应组装走真实实现，快照替身执行传入的计算器。模块行与 `__total__` 行以参数化复用同一夹具。
3. 修复前失败：`.tmp/test-before-fix.log`，3 个用例 2 失败，两行键均在 `detail.total()` 上 `expected: 3L but was: 4L`；建议类用例通过。
4. 修复后定向：`.tmp/test-after-fix.log`，`SystemTestDefectSummaryBoardServiceTest`、`SuggestionMetricRulesTest`、`SystemTestDefectSummaryRuleExplanationTest`、`SystemTestDefectSummaryBoardGoldenMasterTest` 共 10/10 通过，黄金主表响应无变化。
5. 后端默认套件：`.tmp/test-default-suite.log`，1818 个用例 1 失败（跳过 1），失败为 `CustomerIssueDelayLabelWritebackEnqueueIntegrationTest.actualOdsLabelQueryFailureMustNotReturnEmptyLabels`。在干净 main `efbd6108` 的临时 worktree 复跑同一用例同样失败（`.tmp/test-baseline-enqueue.log`），与本改动无关。根因证据：该用例把 `qaflex_test.ods_gitlab_labels` 重命名后期望查询报错，但测试库同时存在 `public.ods_gitlab_labels`，未限定模式名的查询回落到 public 表，实测同一 SQL 在重命名后仍返回 0 行而非报错；属测试库模式残留，清理需环境所有者授权，未擅自处理。
6. 页面验收未执行：115 实例不可达；本机开发库为共享库且缺少截图样本议题；对共享开发库/本地 GitLab 启动后端会触发同步与延期写回任务产生外部写入，需单独授权。补齐条件：归属明确的隔离库与端口、停用或隔离后台任务、可用的登录方式。代码回归与页面验收分开报告，未改任何库造样例。
7. 仓库门禁：`check_worktree_artifacts.py`、`check_runtime_artifact_locations.py`、`check_text_whitespace.py`、`git diff --check` 全部通过。

## 接口、数据与风险

- 无新增 API、函数契约、表结构、协议或关键常量；沿用 `module_total`、`suggestion_total`、`__total__` 和既有 `IssueSource::isRegularMetricIssue`。
- 主要风险是误改共享输入，使建议类列消失，或将此次修复扩展成“未复现”修复率调整；上述两类测试与范围限制直接覆盖。
- 纯服务行为修复默认不需要平台数据迁移。生产实际代码版本、五条议题实时事实及刷新任务是否成功仍属未验证事项，不能以此次过滤修复结论替代。
- 本计划仅保存本任务至审批收口；临时日志/截图置于任务树 `.tmp/` 或后端测试输出目录，不提交敏感只读数据。
