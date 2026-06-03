# 新老平台可见差异与真实链路对比记录 - 2026-06-03

本报告承接 `docs/local-gitlab-old-new-comparison-20260602.md`，专门记录：

- 已发现的用户可见字段差异。
- 本轮继续补跑的真实链路对比结果。
- 老平台没有等价功能时，新平台自身功能是否能在真实后端/真实数据库链路下正常运行。

## 证据文件

| 证据 | 路径 |
| --- | --- |
| 本轮汇总 JSON | `.tmp/old-new-full-compare-20260603/full-comparison-summary.json` |
| 系统测试 issue 同范围 API diff | `.tmp/old-new-api-compare-20260603/project2-system-test-page1-api-compare-report.json` |
| 新平台 28 个只读 API smoke | `.tmp/old-new-full-compare-20260603/new-api-smoke-18080/report.json` |
| 新平台 28 个页面入口 smoke | `.tmp/old-new-full-compare-20260603/new-browser-smoke-18181/report.json` |
| 系统测试问题列表新老 API 样本 | `.tmp/old-new-full-compare-20260603/system-test/` |
| 老平台 legacy 入口探测 | `.tmp/old-new-full-compare-20260603/old-legacy/` |

## 当前总判断

还没有达到 `PASS_SAME_DATA`。

已经确认的是：老平台 `8091`、新平台后端 `18080`、新平台前端 `18181` 都能真实运行；新平台 28 个只读 API 和 28 个页面入口 smoke 通过；本地 GitLab 到新平台 facts 的 issue/MR 数量和 IID 已对齐。

仍未对齐的是：同一条系统测试问题在用户可见字段上存在差异；部分老平台接口仍是旧项目/全局口径，无法直接作为本地 `projectId=2 + sourceInstance=cc` 的同源 API 基线。

## 状态口径

| 状态 | 含义 |
| --- | --- |
| `PASS_REAL` | 真实后端、真实数据库、真实登录链路已跑通，但不代表已和老平台同源对齐。 |
| `PASS_REAL_LEGACY` | 老平台真实入口能跑通，但入口是 legacy/全局/旧项目口径，暂不能作为本地 `projectId=2 + sourceInstance=cc` 的同源基线。 |
| `FAILED` | 已经做了真实链路对比，且发现字段、数量、规则或接口错误差异。 |
| `NOT_COMPARABLE` | 新老平台真实入口都已探测，但老平台接口口径或硬编码导致无法做同源比较。 |
| `PARTIAL_REAL` | 真实链路已跑到一部分，仍缺同批数据、逐项聚合、页面操作或截图断言。 |
| `PASS_READ_ONLY_REAL` | 只读入口已跑通；写操作、长任务或破坏性动作未触发。 |

本轮只有“系统测试 issue 列表”的身份层面达到同范围对齐：数量、IID、标题一致；可见字段仍未对齐。因此这里不标 `PASS_SAME_DATA`。

## 已发现的可见字段差异

样本：本地 GitLab project `2`，系统测试口径，issue IID `#2`。

| 字段 | 老平台返回 | 新平台返回 | 结论 |
| --- | --- | --- | --- |
| 数量/IID | `total=1`, IID `#2` | `total=1`, IID `#2` | 已对齐 |
| 标题 | `系统测试发现 MR 评论计数未进入代码走查事实` | 同左 | 已对齐 |
| module | `未设定模块` | `:代码走查` | 未对齐 |
| stage/testingPhase | `回归测试 & 系统测试` | `回归测试` | 未对齐 |
| urgency | `未设定紧急程度` | `null` | 未对齐 |
| bugStatus | `未设定议题状态` | `未关闭` | 未对齐 |
| status | `OPEN` | `opened` | 未对齐 |

解释：这不是同步丢数，而是展示/映射口径差异。需要决定新平台是否应复刻老平台的占位值，还是保留新平台更细的标签解析结果。

## 本轮新老真实链路对比

| ID | 范围 | 结果 | 证据与差异 |
| --- | --- | --- | --- |
| SYS-ISSUE-LIST | 系统测试问题列表 API | `FAILED` | 老平台 `/issueStaticData/filter?projectId=2&pageSize=10&pageNum=1&phaseNameList=回归测试 & 系统测试` 与新平台 `/api/question-metrics/issues?projectId=2&sourceInstance=cc&page=1&size=10` 均返回 IID `#2`，但 `module/stage/urgency/bugStatus/status` 差异仍在。 |
| SYS-ISSUE-EXPORT | 系统测试问题导出 | `NOT_COMPARABLE` | 新平台 `/api/question-metrics/issues/export?projectId=2&sourceInstance=cc` 返回 CSV，`csvRows=1`。老平台 `/issueStaticData/exportIssue` 源码硬编码 `ProjectList.CC_PROJECT_ID`，忽略本地 `projectId=2`，本轮返回空内容，不能作为同源导出基线。 |
| SYS-ILLEGAL-LIST | 系统测试非法议题，带阶段 | `FAILED` | 老平台 `/issueStaticData/getIllegalIssue` 传 `phaseName=回归测试 & 系统测试` 后 500，原因是旧阶段定义查不到该阶段，生成 `testing_phase IN ()`。新平台 `/api/question-metrics/illegal-records?projectId=2` 返回 200，`total=3`。 |
| SYS-ILLEGAL-LIST-PROJECT-ONLY | 系统测试非法议题，仅项目 | `FAILED` | 老平台不传阶段、只传 `projectId=2` 返回 200，`total=6`；新平台 `projectId=2` 返回 200，`total=3`。这里是规则/范围差异，需要继续拆 illegal 判断规则。 |
| OLD-INTEGRATION-PHASES | 老平台集成测试阶段入口 | `PASS_REAL_LEGACY` | 老平台 `/integration/getTestingPhase` 返回 200，列表数量 `1`。这是老平台全局/legacy 口径，不含 `sourceInstance/projectId`，不能直接和新平台 project 2 对齐。 |
| OLD-INTEGRATION-SEARCH | 老平台集成测试列表入口 | `PASS_REAL_LEGACY` | 老平台 `/integration/getSearchByPage` 返回 200，`total=0`。新平台 project 2 integration details 也为 `total=0`，但双方不是同一批导入数据，暂不标 `PASS_SAME_DATA`。 |
| OLD-REVIEW-SEARCH | 老平台评审查询入口 | `PASS_REAL_LEGACY` | 老平台 `/review/getSearch` 返回 200。该入口依赖老平台 Mongo/legacy 评审数据，尚未和新平台同一份 Excel 导入数据做对比。 |
| OLD-CODE-ILLEGAL | 老平台代码走查非法记录入口 | `PASS_REAL_LEGACY` | 老平台 `/staticData/getIllegalData?pageSize=10&pageNum=1` 返回 200，`total=0`。新平台 project 2 code review illegal records 返回 `total=3`；双方口径/数据源未确认一致。 |

## 新平台新功能或无老平台等价基线的真实运行检查

这些项目没有直接标成新老对齐，只说明新平台在真实后端和真实数据库链路下能返回成功。
其中 `NEW-API-SMOKE` 是全平台只读 smoke，部分接口未限定 `projectId/sourceInstance`；下方按功能列出的检查来自本轮汇总 JSON，能带 `projectId=2&sourceInstance=cc` 的接口均使用了该本地 GitLab 范围。

| ID | 新平台入口 | 结果 |
| --- | --- | --- |
| NEW-API-SMOKE | 28 个只读 API | `PASS_REAL` |
| NEW-BROWSER-SMOKE | 28 个页面入口 | `PASS_REAL` |
| STAT-system-test-defect-summary | `/api/statistic-boards/system-test-defect-summary?projectId=2&sourceInstance=cc` | 200，rows=2 |
| STAT-system-test-delay-analysis | `/api/statistic-boards/system-test-delay-analysis?projectId=2&sourceInstance=cc` | 200，rows=0 |
| STAT-system-test-defect-cause | `/api/statistic-boards/system-test-defect-cause?projectId=2&sourceInstance=cc` | 200，rows=0 |
| STAT-system-test-phase-statistics | `/api/statistic-boards/system-test-phase-statistics?projectId=2&sourceInstance=cc` | 200，rows=2 |
| INTEGRATION-phase-options | `/api/integration-tests/phase-options?projectId=2&sourceInstance=cc` | 200 |
| INTEGRATION-summary | `/api/integration-tests/summary?projectId=2&sourceInstance=cc` | 200，rows=0 |
| INTEGRATION-details | `/api/integration-tests/details?projectId=2&sourceInstance=cc&page=1&size=10` | 200，total=0 |
| CUSTOMER-records | `/api/customer-issues/records?projectId=2&topic=cc-product&page=1&size=10` | 200，total=0 |
| CUSTOMER-illegal-records | `/api/customer-issues/illegal-records?projectId=2&page=1&size=10` | 200，total=0 |
| CODE-illegal-records | `/api/code-review/illegal-records?projectId=2&page=1&size=10` | 200，total=3 |
| CODE-multi-board | `/api/code-review/multi-board/overview?projectId=2&sourceInstance=cc` | 200 |
| MIRROR-configs | `/api/gitlab-sync/configs` | 200 |
| MIRROR-status | `/api/gitlab-sync/status` | 200 |
| MIRROR-source-health | `/api/gitlab-sync/source-health` | 200 |
| MIRROR-table-diagnostics | `/api/gitlab-sync/table-sync-diagnostics` | 200，状态 `IDLE` |
| DB-tables | `/api/database-browser/tables` | 200 |

## 本轮仍未做成 `PASS_SAME_DATA` 的地方

| 范围 | 状态 | 原因/下一步 |
| --- | --- | --- |
| 系统测试问题可见字段 | `FAILED` | 需要决定占位值、组合阶段、状态大小写/枚举的展示合同。 |
| 系统测试非法议题 | `FAILED` | 老平台阶段参数会生成空 `IN ()`；项目级数量为老 `6` vs 新 `3`，需要继续拆规则。 |
| 系统测试导出 | `NOT_COMPARABLE` | 老平台导出硬编码旧项目，不能导出本地 project 2 同源数据。 |
| 系统测试统计看板数值 | `PARTIAL_REAL` | 新平台看板 API 能返回；尚未和老平台同批统计聚合逐项比数值。 |
| 集成测试 | `PARTIAL_REAL` | 新老入口都能跑，但当前没有同批 integration 数据对齐。 |
| 评审数据/Excel 导入 | `PARTIAL_REAL` | 老平台入口能跑，新平台入口能跑；尚未用同一份旧 Excel 在两边导入后比较。 |
| 客户问题 | `PARTIAL_REAL` | 新平台接口能跑但 project 2 样本为空；尚未构造/导入同批老平台客户问题数据。 |
| 代码走查 | `PARTIAL_REAL` | 新平台 project 2 有 `3` 条非法记录；老平台 legacy 查询为 `0`，口径/数据源未对齐。 |
| 镜像调度/新平台新功能 | `PASS_READ_ONLY_REAL` | 只跑了只读状态、配置、健康、诊断入口。全量同步、增量同步、补偿、retry、cancel、purge、system hook 注册等写操作/长任务未在本轮触发。 |
| 页面级操作 | `PARTIAL_REAL` | 28 个页面入口能打开，但筛选、排序、分页、详情、导出按钮、刷新按钮、取消按钮等完整用户操作还没有逐页截图和断言。 |

## 建议下一步

1. 先定系统测试字段展示合同：`未设定*` 占位、组合阶段、`OPEN/opened`、`未关闭` 是否需要向老平台兼容。
2. 给老平台本地测试环境继续补兼容：`exportIssue` 支持 local `projectId=2`，`getIllegalIssue` 避免阶段定义为空时生成 `IN ()`。
3. 以固定样本数据补齐四类同源数据：系统测试 issue、代码走查 MR、评审 Excel、集成测试 note。
4. 再跑页面级真实操作：每页至少覆盖筛选、排序、分页、详情、导出、刷新/取消，并把截图和 JSON 证据纳入本报告。
