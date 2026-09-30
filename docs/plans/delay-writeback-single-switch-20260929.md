# 延期标签写回：唯一开关口径（工作单元计划）

> 状态：**已实施完成并验证**（2026-09-29 用户选定方案 A：镜像设置页开关为唯一控制、默认关闭、开关两向提示）
> 归属：平台同步配置与延期标签写回；涉平台通用配置契约与业务规则第 14 条

## 0. 进度与中间物

- 当前状态：**编码与文档已完成，验证已跑**（数字见 §8）。
- 恢复线索：当前阶段＝收尾（待用户决定是否重启开发实例以实机验证）；恢复后首条命令
  `git status --short -- backend/src/main/java/com/data/collection/platform/service/CustomerIssueDelayLabelWritebackService.java frontend/src/views/MirrorSettingsView.vue`；
  上一相关单元＝`docs/plans/delay-label-writeback-prewriteback-gate-fix-20260920.md`（闸门）、`docs/plans/delay-writeback-scheduler-starvation-fix-20260920.md`（编排）。
- 已完成（只读调查，2026-09-29）：摸清写回的两层开关与现状（见 §3 证据）、全部引用点（后端服务/编排/worker、两个集成测试与一个单测、内网打包脚本两行、业务规则第 14 条、架构文档写回段、runbook 两开关段）、前端开关位置与既有确认框约定。
- 已完成（实施，2026-09-29）：①后端删全局开关层（`application.yml`、服务 `@Value`/字段/双参构造器、`isEnabled` 改单层判定）；②测试与打包同步（单测改唯一开关语义、Planner/Queue 构造器、两个集成测试删旧属性、打包脚本删两处钉 false）；③前端两向确认与常驻警示（纯函数 `delayWritebackSwitchPrompt` + 视图 `@change` 处理与 `el-alert`）；④文档收口（业务规则第 14 条、`docs/architecture.md`、runbook、`docs/decisions.md` D-23、`docs/progress.md`）。
- 测试状态：见 §8（全部完成项均已验证；全量套件的 14 个错误已定性为并发构建干扰并单独复跑为绿）。
- 阻塞：无。**注意**：其他单元正在同一工作树改动（客户统计/平台评审整改与 `scripts/package_intranet_offline.py` 的 compose 校验区），本单元只做定点编辑，未触碰其区块。

## 1. 目标与边界

### 目标

1. 「延期标签写回」的启用只由**镜像设置页的开关**（`gitlab_sync_configs.delay_label_writeback_enabled`）决定，删除部署级全局开关层，消除"页面打开却静默不写"的误导。
2. 默认关闭（库默认、创建/保存/读取归一化、页面初值全部为 false，保持现状不变）。
3. 开关**开启与关闭两个方向**都有明确提示：开启为确认框（说明会真实调用 GitLab API 写入/摘除标签、且未配地址或 Token 时仍不会写），关闭为确认框（说明仍监控事实、不再写标签）；开启状态下页面常驻醒目说明。

### 边界（明确禁止）

- 不改写回判据、差异计算、队列协议、标签白名单、重试/租约、worker 调度周期与编排三段流程。
- 不改端点路径、请求体或响应结构（`POST/PUT /api/gitlab-sync/config` 的 DTO 字段不变）。
- 不为兼容保留 `delay-label-writeback-api-enabled` 的别名、默认 true 的过渡态或双轨判定。
- 不触碰其他单元在途文件（`scripts/package_intranet_offline.py` 的 compose 校验区、其他单元的后端/前端改动）。
- 不主动运行黄金基线门禁（用户已决定待新功能完成后统一收口）。

## 2. 约束与背景

- 既有业务规则权威：`docs/platform-page-business-rules.md` 第 14 条（旧述"必须同时满足两个开关"，本单元改写）。
- 仓库红线：开发期不留兼容层，删掉的开关必须连同其判定、测试、打包钉值与文档一并清除。
- 写 GitLab 的唯一运行时出口是 `CustomerIssueDelayLabelWritebackService.sendLabelUpdate`，只被 worker 调用；worker 每个任务执行前重新校验 `isEnabled`，关闭后已入队任务被 `markSkipped`，不触碰 GitLab。
- 前端确认框既有约定：`ElMessageBox.confirm(message, title, { type, confirmButtonText, cancelButtonText })`（`MirrorSettingsView.vue:358` 同页既有用法）。

## 3. 证据与根因

- 现状为两层门禁：①页面开关 `delay_label_writeback_enabled`（默认 false）；②全局部署开关 `platform.gitlab-mirror.delay-label-writeback-api-enabled`（`application.yml:147`，默认 false；`scripts/package_intranet_offline.py:554/851` 钉入内网 `.env` 与 compose），`isEnabled` 要求 ② ∧ ① ∧ webBaseUrl ∧ apiToken（`CustomerIssueDelayLabelWritebackService:66-72`）。
- 后果：用户在内网页面把开关打开后仍不会写回，且页面与帮助文案（`MirrorSettingsView.vue:500`）未提示还有部署级开关，属"打开了却静默空转"的误导；这也是该路径从未在真实环境执行的原因之一（`docs/plans/delay-label-writeback-prewriteback-gate-fix-20260920.md:101`）。
- 开发库与本地实测：唯一数据源 `id=1/default` 的开关为 false、队列仅 55 条 SUCCEEDED、全局开关默认 false ⇒ 现状不写回。

## 4. 方案与步骤

1. 后端删层：`application.yml` 删 `delay-label-writeback-api-enabled`；服务去掉 `apiWritebackEnabled`、`@Value` 与测试用双参构造器，`isEnabled` 只按数据源开关 + `webBaseUrl` + `apiToken` 判定（`java.util.Optional` 无关，保持空安全）。
2. 测试与打包同步：重写 `CustomerIssueDelayLabelWritebackServiceTest` 的开关用例为唯一开关语义（开关关/空、地址空、Token 空各拒绝；三者齐备才允许）；`PlannerTest`/`QueueServiceTest` 改为无参构造；两个集成测试删除 `platform.gitlab-mirror.delay-label-writeback-api-enabled=false` 属性；打包脚本删除钉 false 的两行。
3. 前端：把两向提示文案抽为纯函数 `delayWritebackTogglePrompt(enabled, credentialsReady)`（`mirror-settings-helpers.ts`）+ 单测；`MirrorSettingsView.vue` 的 `el-switch` 加 `@change` 处理器，取消则回退开关；开启时下方说明改为醒目提示。
4. 文档：业务规则第 14 条改为唯一开关口径并写明默认关闭与应急停止手段；`docs/architecture.md:56` 写回资格改为"由数据源开关与地址/令牌配置决定"；runbook 两开关段改为单一开关 + 应急停止清单；`docs/decisions.md` 新增决策；`docs/progress.md` 记本单元状态与验证。
5. 验证：后端定向 → 默认套件；前端定向 → typecheck/lint；仓库护栏；确认开发库开关仍为 false、运行实例行为不变（不重启后端，仅只读核对）。

## 5. 决策记录

- **已选**：方案 A（页面开关为唯一控制，默认关闭，两向提示）。理由见 §3；应急停止手段齐全（改库即停、撤 Token、关 worker/调度 env），不依赖前端。
- **否决**：方案 B（保留全局层、内网改默认 true）——多一层需解释的配置，收益仅为"改 env 重启"级别的停止手段，而改库停止更快且不需要重启；方案 C（维持两级仅补文案）——与用户要求的"唯一控制"冲突。
- **待定**：无。

## 6. 接口契约

- 对外 HTTP 契约不变：`GET/POST/PUT /api/gitlab-sync/config(s)` 的字段与语义不变（`delayLabelWritebackEnabled` 仍是唯一业务开关）。
- 删除的配置项：`platform.gitlab-mirror.delay-label-writeback-api-enabled`（含 env `CUSTOMER_ISSUE_DELAY_LABEL_WRITEBACK_API_ENABLED`），不保留兼容读取。
- 新增前端内部契约：
  ```ts
  // frontend/src/views/mirror-settings-helpers.ts
  export function delayWritebackTogglePrompt(enabled: boolean, credentialsReady?: boolean):
    { title: string; message: string; confirmButtonText: string };
  ```
- 保留（未改动）配置项：`customer-issue-delay-writeback-worker-enabled`（worker 是否轮询队列）、`customer-issue-delay-check-delay-ms`（周期，默认 1 小时）、`scheduler-enabled` 等。

## 7. 风险与假设

- 风险1：删掉全局层后，"打开即真写"。缓解＝默认关闭不变 + 两向确认 + 开启时常驻警示 + 未配地址/Token 时在确认文案里明确提示仍不会写。
- 风险2：内网升级后若历史环境曾依赖该 env（现存部署只钉 false，行为等价于删除），无兼容问题；打包脚本同步删除钉值，避免留下死配置。
- 风险3：其他单元正在改同一工作树，套件可能因他人在途改动出现与本单元无关的失败 → 逐项归因，只修本单元原因，不改他人文件。
- 假设1：写回目标的隔离靠数据（`web_base_url`/`api_token` 指向哪个 GitLab），本地/测试保持指向本地 GitLab 或替身。
- 假设2：黄金基线不受影响（无端点产出变化；夹具开关为 false、worker 关闭），但仍按用户决定不主动运行门禁。

## 8. 验证证据

- 后端定向（写回相关 5 个测试类）：`CustomerIssueDelayLabelWritebackServiceTest`（8 项，唯一开关语义：开关关/空、地址空、令牌空、配置缺失各拒绝）、`...PlannerTest`(1)、`...QueueServiceTest`(3)、`...WorkerServiceTest`(4)、`CustomerIssueDelayClosureOrchestratorTest`(10)，共 **26 项 / 0 失败 / 0 错误**。
- 前端定向：`delay-writeback-switch-prompt.test.ts`(3：开启文案含真实写入说明、未填地址时追加提示、关闭文案含跳过队列) 与 `mirror-settings-writeback-switch.test.ts`(2：取消开启回退且不发请求、确认开启后常驻警示并可再次确认关闭)，加上 `mirror-settings.mount-smoke.test.ts`(4) 与 `mirror-settings-helpers.test.ts`(5)，共 **14 项全绿**；`npm run typecheck` exit 0；改动文件 `eslint` exit 0。
- 仓库护栏与打包自测：`check_text_whitespace`、`check_worktree_artifacts`、`check_runtime_artifact_locations`、`check_flyway_migration_immutability`（136 迁移）全部 exit 0；`scripts/test_package_intranet_offline.py` **51 项通过（跳过 10）**。
- 全量后端套件：**1577 项 / 0 失败 / 14 错误 / 1 跳过**；14 个错误全为 `NoClassDefFoundError: com.data.collection.platform.service.statistics.StatisticBoardTestSnapshotScopes`，成因是并发 Maven 构建在本轮运行期间重写 `backend/target/test-classes`（该 `.class` 于 10:09 落盘，晚于本轮测试阶段），单独复跑 `CustomerIssueCustomerStatisticsBoardServiceTest` 与 `CustomerIssueCustomerStatisticsDetailContractTest` 得 **16 项全绿**。定性：环境干扰，与本单元无因果关系（本单元未触碰客户统计代码或其测试）。
- 只读核对：开发库 `gitlab_sync_configs` 唯一数据源 `id=1/default` 的 `delay_label_writeback_enabled` 为 false、写回队列仅 55 条 `SUCCEEDED`、无 `PENDING/RETRY`，与"默认关闭、不发生写回"一致。
- 未跑：黄金基线门禁（按既定决定延后）；开发实例未重启（见 §0 状态风险）。
