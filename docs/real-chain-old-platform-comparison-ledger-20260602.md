# 真实链路与老平台对比验收台账（2026-06-02）

本台账用于把“已跑通”“只跑了 mock/单测”“环境阻塞”“尚未执行”分开记录。后续任何一项没有跑通，都必须保留在表内并写清楚阻塞原因和下一步动作，不能用“通过”笼统覆盖。

## 状态口径

| 状态 | 含义 |
| --- | --- |
| `PASS_SAME_DATA` | 同一批数据已分别在老平台和新平台导入或同步，用户可见结果、统计口径、导出结果已对比通过。 |
| `PASS_REAL` | 当前代码已走真实前端/后端/数据库或真实 GitLab 链路通过，但未覆盖老平台同批结果对比。 |
| `PARTIAL_REAL` | 有真实链路证据，但证据不是当前最新代码、不是完整用户路径，或只覆盖部分源/页面。 |
| `PASS_MOCK_ONLY` | 只通过 mock API、组件测试、页面壳 smoke，不能代表真实数据展示正确。 |
| `PASS_UNIT_ONLY` | 只通过单元测试、服务测试、静态检查，不能代表真实链路。 |
| `FAILED` | 已执行但失败，必须记录失败命令或失败现象。 |
| `BLOCKED_ENV` | 需要内网、数据库密码、真实 GitLab 源、老平台运行环境等当前不可用条件。 |
| `NOT_RUN` | 尚未执行。 |

## 验收门禁

1. “当前最新版本通过”只能使用 `PASS_REAL` 或 `PASS_SAME_DATA`，并且证据必须来自本轮最新代码。
2. 页面路由 mock smoke、Vitest、Maven 单测只能标 `PASS_MOCK_ONLY` 或 `PASS_UNIT_ONLY`。
3. 老平台对比以 `D:\projects\spidergitdata-dev` 为语义基线，不要求代码一致，但同批数据的页面字段、统计口径、排序/筛选、导出结果必须一致或优于老平台。
4. 有历史证据但本轮修复后未复跑的项，只能标 `PARTIAL_REAL`、`NOT_RUN` 或 `BLOCKED_ENV`。
5. 未跑通项必须填写“阻塞/说明”和“后续动作”。

## 当前验收台账

| ID | 验收项 | 状态 | 证据 | 阻塞/说明 | 后续动作 |
| --- | --- | --- | --- | --- | --- |
| VR-001 | 28 个前端路由可打开，页面壳非空 | `PASS_REAL` | `.tmp/browser-real-smoke-20260602-153211/report.json`；`python scripts/browser_route_real_smoke.py --base-url http://localhost:18181` 通过，28 个路由均走真实 `18181 -> 18080 -> PostgreSQL` 链路。 | 未覆盖老平台同批数据对比。 | 继续执行 `OC-*` 同批数据对比。 |
| VR-002 | 最新统计看板进入页面自动 realtime refresh | `PASS_UNIT_ONLY` | `npm test -- --run src/composables/useStatisticBoardRefreshController.test.ts src/composables/usePageAutoRefreshPreference.test.ts src/views/statistic-board-page.mount-smoke.test.ts` | 尚未在真实后端确认页面进入后生成 `TABLE_REFRESH`/事实刷新链路。 | 在真实后端启动后打开统计看板，查 `sync_runs`、页面状态和看板数据刷新。 |
| VR-003 | `issue_fact.module_names` 历史污染现场检查 | `PASS_REAL` | `DATASOURCE_PASSWORD=change_this_password python scripts/check_issue_fact_module_pollution.py --limit 20` 先发现 3 组污染；对 `cc/default/dgm` 执行 issue fact full rebuild 后复跑通过：`issue_fact rows=1177 rows_with_module_names=24`，无可疑模块值。 | 两个本地 smoke 源 `configId=4/5` 的 rebuild 接口返回 400；老平台同批对比未覆盖。 | 后续若纳入 smoke 源，需要先补齐对应源数据或禁用无效 smoke 源后复跑。 |
| VR-004 | Flyway/schema drift 与迁移锁定 | `PASS_UNIT_ONLY` | 第八批记录中 `python scripts/check_schema_flyway_drift.py`、`check_flyway_migration_immutability.py`、`mvn -q -Dtest=FlywayMigrationSmokeTest test` 通过。 | 这是仓库结构和迁移烟测，不等于真实业务数据一致。 | 纳入后续全量真实链路启动前的前置检查。 |
| VR-005 | 新平台最新代码浏览器深度用户路径 | `PARTIAL_REAL` | `.tmp/browser-real-smoke-20260602-153211/report.json` 覆盖 28 个真实路由；`.tmp/api-real-smoke-20260602-153042/report.json` 覆盖 28 个只读真实接口。 | 已覆盖真实打开和只读接口，但未执行筛选、排序、分页、详情、下钻、导出、刷新、取消等操作路径。 | 使用真实后端和测试数据库逐页执行用户动作，并为每页保存截图或 JSON 证据。 |
| VR-006 | 新老平台同批 GitLab 数据展示一致性总验收 | `FAILED` | 老平台 `8091` 已用本地 MySQL/Mongo 和本地 GitLab 拉起；在 `D:\projects\spidergitdata-dev` 增加本地测试补丁后，project 1/2 issue 采集均返回 200，旧表行数为 `382/6`。`scripts/compare_old_platform_issue_data_to_new_facts.py` 证明新老同批 issue 数量和 IID 均一致。 | 用户可见字段尚未一致：project 2 最新报告 `.tmp/old-vs-new-issue-project2-20260602-after-assignee-fix.json` 仍有 display mismatch：`moduleName=2`、`urgency=2`、`bugStatus=6`、`testingPhase=1`；project 1 的 `bugStatus` 也仍与老平台占位口径不同。老平台 API `/issueStaticData/filter` 又硬编码项目 `9/325`，本地 project `1/2` 暂只能做 DB-backed 对比。 | 以老平台为准确认“未设定* 占位 vs 新平台解析标签值”是否必须一致；确认后修新平台展示或调整对比口径，再跑 API/页面/导出级对比。 |
| VR-007 | 内网真实 CC/DGM 双源直连 | `BLOCKED_ENV` | 2026-05-20 曾用 `smoke_cc`/`smoke_dgm` 最小本地测试源验证多源隔离。 | 外网/本机不能证明内网 CC/DGM 非 Docker 直连。 | 内网部署后分别配置 CC/DGM 真实 PostgreSQL，跑连接诊断、全量、增量、补偿、双源隔离抽样。 |
| VR-008 | 真实进程 kill/restart 恢复 | `NOT_RUN` | 自动化 lease/recovery 用例曾通过。 | 未构造真实长运行任务并 kill 后端进程。 | 在测试源上启动长任务，kill backend，restart 后验证 lease 恢复、run/table task 终态和页面诊断。 |
| VR-009 | 内网真实含 `inet` 表端到端 | `BLOCKED_ENV` | 归一化逻辑自动化覆盖。 | 本机没有内网真实 `authentication_events` 等表链路。 | 内网源同步时纳入 `inet` 字段表，验证全量/增量不会因类型失败。 |
| VR-010 | 全量补偿运行中单表刷新排队状态 | `PASS_UNIT_ONLY` | `mvn -q -Dtest=SyncRunSubmissionServiceTest,SyncRunDispatcherServiceTest,DatabaseBrowserServiceTest test`。 | 只验证提交策略和 dispatcher 单测，未在真实页面看到全量补偿运行时单表刷新排队。 | 构造运行中 `FULL_COMPENSATION_SCAN`，从数据库查看页刷新小表，验证页面文案、`sync_runs` 和排队顺序。 |
| VR-011 | 普通自动补偿遇到全量补偿时跳过 | `PASS_UNIT_ONLY` | `mvn -q -Dtest=GitlabCompensationSchedulerTest,GitlabDailyVerificationSchedulerTest,SyncRunSubmissionServiceTest,SyncRunDispatcherServiceTest test`。 | 未在真实 scheduler 时钟下观察跳过日志和 run 不生成。 | 缩短调度间隔，在真实后端制造活跃全量补偿，观察 scheduler 日志与 `sync_runs`。 |
| VR-012 | run deadline guard 取消超时/跨日/窗口外补偿 | `PASS_UNIT_ONLY` | `mvn -q -Dtest=SyncRunDeadlineGuardTest,SyncRunWorkerServiceTest,GitlabMirrorSyncServiceTest test`。 | 未构造真实跨日或窗口截止运行。 | 在测试配置中设置短窗口/短最大运行时长，真实同步中验证 `CANCELLING`、最终终态和页面诊断。 |
| VR-013 | System Hook 真实链路 | `PARTIAL_REAL` | 2026-05-20 记录：本地 GitLab `WebHookLog.id=56 response_status=200`，平台 `gitlab_system_hook_events.id=10 processed=true`。 | 这是五月证据，不覆盖 2026-06-02 最新修复后的完整复跑。 | 用最新后端重新投递本地 GitLab System Hook，确认事件、run、table task、日志页面均可追踪。 |
| VR-014 | 业务导出真实接口 | `PARTIAL_REAL` | 2026-05-20 记录：代码走查、客户问题、系统测试、集成测试、统计看板导出接口均返回文件内容。 | 历史证据未覆盖本轮模块归一化、集成测试阶段主导和刷新调度修复后的结果。 | 使用最新代码和同一批样例数据重新导出，并与老平台导出字段/行数/关键值对比。 |
| VR-015 | 新平台只读核心 API 真实链路 | `PASS_REAL` | `.tmp/api-real-smoke-20260602-153042/report.json`；`python scripts/real_chain_api_smoke.py --base-url http://localhost:18181` 通过，覆盖登录、配置、数据库表、评审、代码走查、集成测试、系统测试、客户问题和统计看板共 28 个只读接口。 | 未覆盖写操作、导出下载和老平台同批对比。 | 后续专项执行导出、刷新、取消、补偿调度和新老同批数据对比。 |
| VR-016 | GitLab 源库与新平台事实层同源对比 | `PASS_REAL` | `python scripts/compare_gitlab_source_to_new_facts.py --source-instance cc --project-id 1 --project-id 2 --project-id 3 --output .tmp/gitlab-source-vs-new-facts-20260602.json` 通过。GitLab 源库 issue 数 `382/6/1` 与新平台 `issue_fact` 一致；MR 数 `2/1` 与 `merge_request_fact` 一致；无 missing/extra IID。 | 这是 GitLab 源库直连 vs 新平台事实层，不是老平台页面/API 结果对比。 | 纳入后续真实链路套件；字段级老平台对比继续按 `LG-*` 和 `OC-*` 跑。 |
| VR-017 | Issue 处理人事实层同源修复 | `PASS_REAL` | 修复 `GitlabFactSourceSqlProvider` 和 `FactBuildService` 后，`mvn -q -Dtest=IssueFactSourceInstancePipelineTest test` 通过；`POST /api/facts/rebuild?scope=issue&full=true&configId=2` 影响 389 行；project 2 API 返回 `assigneeName=Administrator`。 | 只修复 assignee 漏填；模块/阶段/紧急度/bugStatus 仍存在老平台口径差异。 | 纳入后续 old-vs-new 字段对比和导出对比。 |

## 老平台同批数据对比项

| ID | 对比项 | 状态 | 证据 | 阻塞/说明 | 后续动作 |
| --- | --- | --- | --- | --- | --- |
| OC-001 | 系统测试缺陷汇总第一列为规范模块 | `NOT_RUN` | 无。 | 尚未在两边导入同一批 issue 后逐行比较模块汇总。 | 老平台参考 `issueStaticData` 与 `module_name/testing_phase` 语义；新平台查 `/api/statistic-boards/system-test-defect-summary`，比较模块名、一级/二级/三级/建议类、修复率、占比。 |
| OC-002 | 模块污染值不进入看板和下拉 | `PARTIAL_REAL` | 新平台真实库已重建 `cc/default/dgm` issue facts，`scripts/check_issue_fact_module_pollution.py --limit 20` 复查无可疑模块值。 | 只验证了新平台事实层数据；尚未截图核对各页面下拉，也未与老平台同批数据对比。 | 用真实页面检查系统测试/集成测试/客户问题等下拉，再在老平台环境可用后比较两边展示。 |
| OC-003 | 集成测试按轮次/阶段主导 | `NOT_RUN` | 无。 | 已有单测和前端 smoke，但未与老平台同批轮次数据对比。 | 老平台按 `testingPhase` 聚合；新平台查 `/api/integration-tests/phase-options`、`summary`、`details`、导出，比较模块汇总、功能汇总、问题明细。 |
| OC-004 | 集成测试问题编号展示为链接文本而非 JSON | `PASS_MOCK_ONLY` | 前端表格 link cell 单测和 mount smoke 已覆盖。 | 未在真实集成测试数据页面验证。 | 用真实 issue 链接样例打开详情，确认显示 `#iid` 且跳转 URL 正确。 |
| OC-005 | 评审数据旧 Excel 默认空值导入 | `PASS_UNIT_ONLY` | `ReviewDataLegacyExcelImportService` 相关测试已覆盖空默认值导入。 | 未用老平台同一份 Excel 文件做预览、确认导入、列表展示对比。 | 固定一份旧 Excel，分别导入新老平台，比较记录数、默认空值、问题项、搜索字段。 |
| OC-006 | 系统测试修复率/占比图表不超过合理范围 | `PASS_UNIT_ONLY` | `npm test -- --run src/views/quality-board.test.ts src/views/system-test-multi-board.test.ts`。 | 单测只覆盖前端解析，未对真实聚合分母和页面图表截图验收。 | 同批数据下打开质量看板和系统测试多元看板，比较图表值、表格值和老平台导出。 |
| OC-007 | 系统测试问题查询和导出 | `FAILED` | 新平台 `18080` 真实 API smoke 通过；`GET /api/question-metrics/issues?projectId=2&sourceInstance=cc&page=1&size=10` 返回 issue `#2`，处理人修复后为 `Administrator`。老平台 DB-backed 对比报告 `.tmp/old-vs-new-issue-project2-20260602-after-assignee-fix.json` 通过数量/IID，但字段仍失败。 | 本地老平台 API 硬编码项目 `9/325`，未完成同 URL API 对比；DB-backed 字段差异仍包括模块、阶段、紧急度、bugStatus。导出尚未比对。 | 先收敛字段口径，再跑 `/api/question-metrics/issues/export` 与老平台导出文件行数/字段/关键值对比。 |
| OC-008 | 客户问题 CC_PRODUCT/延期问题展示 | `NOT_RUN` | 无。 | 未与老平台同批客户问题数据对比。 | 新平台查 `/api/customer-issues/records` 和相关统计看板；比较列表、延期原因、响应效率、按功能统计。 |
| OC-009 | 代码走查非法记录和多源看板 | `NOT_RUN` | 无。 | 五月有真实导出接口证据，但本轮未与老平台同批 MR/评审数据对比。 | 同批 MR 和评审样例下比较非法记录、规则说明、筛选项、导出、多源看板。 |
| OC-010 | 数据镜像调度页面状态语义 | `NOT_RUN` | 无。 | 老平台没有完全等价的新 orchestrator，但用户可见状态必须自洽。 | 真实执行全量、增量、补偿、单表刷新、取消，截图比较“请求已提交/排队/运行/取消中/完成”的页面和日志一致性。 |

## 后续统一跑通顺序

1. 先跑最新代码真实基础链路：启动 `18080` 后端、`18181` Vite、测试 PostgreSQL，执行无 mock 路由 smoke 和主要接口巡检。
2. 再跑数据质量链路：重建事实层，执行 `scripts/check_issue_fact_module_pollution.py`，抽样核对 `issue_fact` 与 `integration_test_fact`。
3. 再跑新老同批数据：固定 GitLab issue/MR、评审 Excel、集成测试 note 三类样例，两边导入后按 `OC-*` 逐项比较。
4. 最后跑调度专项：补偿、全量补偿、单表刷新、取消、deadline、kill/restart、System Hook。
