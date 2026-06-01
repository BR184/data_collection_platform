<!-- DOC_STATUS_START -->
> 文档状态：待实施修复方案
> 说明：针对 2026-06-01 用户旅程测试报告中的问题给出代码级解决方案，仅做方案设计，未改动业务代码。
<!-- DOC_STATUS_END -->

# 2026-06-01 用户旅程测试问题修复方案

## 来源

本方案对应测试报告：[2026-06-01-project-wide-user-journey-test-report.md](2026-06-01-project-wide-user-journey-test-report.md)。

报告“发布级最终问题清单”共 5 项 + 1 项发布前待复测风险。本文逐项给出根因定位（已对照源码确认）、修复方案、影响面与验证方式。本轮不改业务代码。

## 问题与方案总览

| 编号 | 问题 | 根因层 | 建议优先级 |
| --- | --- | --- | --- |
| P1-1 | `/actuator/health` 返回 500 | 后端 | 高 |
| P1-2 | 同源重复配置前端文案过时 | 前端 | 高 |
| P2-1 | 游客态质量看板 401 噪声与“加载失败” | 前端 | 中 |
| P2-2 | 窄屏登录入口/弹窗点击区域不可靠 | 前端样式 | 中 |
| P3-1 | 离开镜像设置页静默丢失草稿 | 前端 | 低 |
| R-1 | 全量类同步缺二次确认（待复测） | 前端 | 复测后定 |

## P1-1：`/actuator/health` 返回 500

### 根因（已确认）

- `backend/pom.xml` 没有引入 `spring-boot-starter-actuator`，运行时根本没有 health endpoint 的 handler。
- `PlatformSecurityConfiguration` 已放行 `/actuator/health`、`/actuator/health/**`，所以安全层认为它应该存在，但实际不存在。
- 请求落到静态资源处理，抛 `NoResourceFoundException`；该异常被 `GlobalExceptionHandler.handleException`（[GlobalExceptionHandler.java:37](../../backend/src/main/java/com/data/collection/platform/common/exception/GlobalExceptionHandler.java#L37)）与 `GlobalRestExceptionHandler.handleException`（[GlobalRestExceptionHandler.java:63](../../backend/src/main/java/com/data/collection/platform/common/exception/GlobalRestExceptionHandler.java#L63)）的兜底 `Exception` 分支捕获，统一包装成 `SYSTEM_ERROR`（500）。
- 因此现象是两个问题叠加：health 未暴露 + 兜底异常处理把“资源不存在”误报成“服务内部异常”。

### 方案（推荐：引入 actuator）

1. `backend/pom.xml` 增加依赖：
   ```xml
   <dependency>
     <groupId>org.springframework.boot</groupId>
     <artifactId>spring-boot-starter-actuator</artifactId>
   </dependency>
   ```
2. `application.yml` 显式收敛暴露面，避免顺带暴露其它端点：
   ```yaml
   management:
     endpoints:
       web:
         exposure:
           include: health
     endpoint:
       health:
         show-details: never
   ```
3. 安全放行路径已具备，无需改动 `PlatformSecurityConfiguration`。
4. 健康聚合默认会带上 DataSource ping；若不希望健康检查耦合外部 GitLab 源库连通性，确认 `db` 健康指示器只覆盖平台库（本平台外部源库走自建连接，不在 Spring DataSource 自动健康检查内，默认即满足）。

### 备选方案（不引入 actuator）

- 新增一个极轻的 `HealthController`，暴露 `GET /api/health` 或 `/healthz` 返回固定 `{"status":"UP"}`；同步更新安全放行路径与部署文档，统一探针地址。
- 仅在团队明确不想加 actuator 体积/攻击面时采用。

### 兜底异常处理修正（无论选哪种方案都建议做）

- 在两个 `@RestControllerAdvice` 中为 `org.springframework.web.servlet.resource.NoResourceFoundException`（或其父类 `NoHandlerFoundException`，视 Spring MVC 配置）单独加分支，返回 `404 / NOT_FOUND`，不要再走 `SYSTEM_ERROR`。
- 这样未来任何“路径不存在”都返回 404，而不是误导性的 500。
- 注意两个 handler 都要改，否则按 advice 优先级仍可能落到旧分支。

### 影响面与验证

- 影响：`backend/pom.xml`、`application.yml`、可选两个异常处理类。属于中风险（新增依赖 + 改全局异常映射）。
- 验证：
  - `curl http://localhost:18080/actuator/health` 返回 200 + `{"status":"UP"}`。
  - 访问任意不存在路径（如 `/api/not-exist`）返回 404 而非 500。
  - 回归 `GlobalExceptionHandlerTest`，并补一条 `NoResourceFoundException → 404` 用例。

## P1-2：同源重复配置前端文案过时

### 根因（已确认）

- 后端 `GitlabConfigService.saveConfig` 已实现物理源唯一性校验：当某物理源已有启用配置，且任一侧 `autoSyncEnabled=true` 时，**会阻断保存并返回 400**（见 [sync 投资调研文档 §17.1](2026-06-01-sync-source-duplication-log-scroll-compensation-investigation.md)）。
- 但前端 [MirrorSettingsView.vue:230](../../frontend/src/views/MirrorSettingsView.vue#L230) 的 `duplicatePhysicalSourceWarning` 仍硬编码“平台不会阻止保存”。
- 前端判重逻辑也比后端粗：`duplicatePhysicalSourceMatches`（[MirrorSettingsView.vue:208](../../frontend/src/views/MirrorSettingsView.vue#L208)）只比较“已启用”候选，且 DOCKER 指纹只用容器名（后端用 `docker:container:db:user`，DIRECT 用 `direct:host:port:db:user`），未纳入 `autoSyncEnabled` 维度，导致提示口径与后端不一致。

### 方案

1. 改写 `duplicatePhysicalSourceWarning` 文案，与后端策略对齐，分情形表达：
   - 当前源或冲突源任一开启自动同步：明确“平台会阻止保存”，并给出处置建议（停用其一 / 关闭自动同步后作为测试源保存）。
   - 都未开启自动同步：维持“风险提示”，说明同时启用可能导致事实层重复。
   - 建议文案示例：
     > 当前数据源和 {names} 指向同一个 GitLab 源库。若双方有任一开启自动同步，平台会阻止保存；如需保留测试源，请关闭自动同步或停用该数据源。同时启用会导致同一批 Issue、MR、评论重复进入事实层。
2. 让前端指纹与后端归一化口径一致：DIRECT 用 `host:port:db:user`、DOCKER 用 `container:db:user`，host/db/user 统一 trim + lowercase；这样 warning 触发条件与后端阻断条件尽量一致，减少“前端不提示但后端拦截”的割裂。
3. 保存失败时（后端 400）确保 `ElMessage` 直接透出后端 message（含冲突 `sourceInstance`），不要被通用错误覆盖。
4. 历史文档中“不会阻止保存”的描述保留为历史背景，但当前行为说明需更新（见状态索引同步）。

### 影响面与验证

- 影响：仅 `frontend/src/views/MirrorSettingsView.vue`（计算属性与指纹函数）。低风险，纯前端展示。
- 验证：
  - 两个启用 + 自动同步源指向同库：warning 文案显示“会阻止保存”，点击保存收到后端 400 且提示一致。
  - 一启用一停用（测试源）：文案为风险提示，保存成功。
  - 复用/新增 `mirror-settings-helpers.test.ts`，断言文案分支与指纹归一化。

## P2-1：游客态质量看板 401 噪声与“加载失败”

### 根因（已确认）

- [QualityBoardRdView.vue:162](../../frontend/src/views/QualityBoardRdView.vue#L162) `loadPage()` 无条件并发请求评审/代码走查/集成测试/系统测试四类摘要，而这些接口需要登录。
- 游客态下四个 `loadSection` 均 401 失败 → 控制台噪声 + `ElMessage.warning('部分看板加载失败，已展示可用数据')`（[:184](../../frontend/src/views/QualityBoardRdView.vue#L184)、[:193](../../frontend/src/views/QualityBoardRdView.vue#L193)）。
- 文件内已有 `isAdmin`（[:72](../../frontend/src/views/QualityBoardRdView.vue#L72)）但未用于 gate 这些请求。

### 方案

1. 在 `loadPage()` 前判断登录态：用 `authState.currentUser.authenticated`（路由守卫已调用 `loadCurrentUser`，详见 [router.ts](../../frontend/src/router.ts)）。
   - 未登录：跳过登录态摘要请求，直接进入“游客空态”，不发请求、不报 401。
2. 区分“未登录空态”与“真实加载失败”：
   - 未登录空态文案改为引导性，例如“登录后查看研发质量看板数据”，不要使用“加载失败”。
   - 仅当已登录且确有 section 失败时，才显示“部分看板加载失败，已展示可用数据”。
3. 登录成功后主动重载：监听登录状态变化（`watch(() => authState.currentUser.authenticated)`）或在登录回调里触发 `loadPage()`，避免用户登录后还要手动刷新。
4. 可选：对确需登录的 section loader，在 `loadSection` 内对 401 做静默处理（不 `console.warn`），进一步降噪。

### 影响面与验证

- 影响：仅 `frontend/src/views/QualityBoardRdView.vue`（可能触及 `auth-state` composable 的读取）。低风险。
- 验证：
  - 游客访问首页：无 401、无“加载失败”提示，显示登录引导空态。
  - 登录后：自动加载四类摘要，无需手动刷新。
  - 扩充 `quality-board-rd.mount-smoke.test.ts`：游客态不发登录接口、登录态正常加载。

## P2-2：窄屏登录入口/弹窗点击区域不可靠

### 根因（已确认）

- 顶部布局为 `.top-nav`（横向滚动，[styles.css:114](../../frontend/src/styles.css#L114)）+ `.header-actions`（`min-width:116px`，[styles.css:152](../../frontend/src/styles.css#L152)），登录按钮在 `.header-actions` 内（[App.vue:180-200](../../frontend/src/App.vue#L180-L200)）。
- 全局 `@media (max-width:1480px)` 仍强制 `body { min-width:1160px }`（[styles.css:1860](../../frontend/src/styles.css#L1860)），没有任何 ≤768px / ≤390px 的真实窄屏布局。
- 390px 视口下整页按 1160px 渲染并被压缩/重叠，导致 `.top-nav` 或 header 覆盖登录按钮命中区域；登录弹窗 footer 按钮同理被 overlay 命中层级影响。这解释了报告里“必须 force click 才能登录”。

### 方案

> 当前平台定位桌面内网，优先级中。方案分两档，按是否承诺移动/平板支持选择。

- 档位 A（最小修复，保证登录可用）：
  1. 为登录入口增加窄屏断点（如 `@media (max-width:768px)`）：让 `.app-header` 在窄屏下纵向堆叠或让 `.header-actions` 不被 `.top-nav` 覆盖（提高 `z-index` / `flex-shrink:0` / 独立成行）。
  2. 确保 `.auth-dialog` 在窄屏下宽度自适应（`width: min(92vw, 360px)`），footer 登录按钮处于可点击层级，不被 dialog header/overlay 拦截。
  3. 目标：390px 下普通点击即可打开登录弹窗并完成登录，无需 force click。
- 档位 B（完整响应式，仅在计划支持移动/平板时做）：
  1. 移除或下调 `body { min-width:1160px }`，引入移动导航（汉堡折叠菜单 / 顶部抽屉）。
  2. 对主要管理页做响应式栅格回流（`.settings-grid` 已有单列回退，可延展到其它页面）。
  3. 这是较大改造，建议单列 backlog，不混入本次发布修复。

### 影响面与验证

- 影响：档位 A 仅触及 `styles.css` 顶部布局/弹窗样式与可能少量 `App.vue` 结构；中风险（响应式样式易回归桌面布局，需回归桌面宽度）。
- 验证：
  - 新增 390px 与 768px 登录 smoke（Playwright 普通 click 完成登录）。
  - 回归 1280px+ 桌面布局未变形。

## P3-1：离开镜像设置页静默丢失草稿

### 根因（已确认）

- `MirrorSettingsView` 无路由离开守卫；报告第 4 轮已确认：编辑 `补偿间隔` 为 127 不保存，切到“数据库查看”再回来，值被服务端 360 覆盖。
- 报告同时确认：页内“测试连接 / 刷新最新数据 / 状态轮询”都不会覆盖草稿（这些不应触发离页确认）。

### 方案

1. 引入 dirty state：以“服务端基线配置”与“当前表单”做浅比较，得到 `isDirty`。
2. 用 `onBeforeRouteLeave`（vue-router）在 `isDirty` 时弹出确认：“存在未保存的同步策略修改，确认离开将丢失。”
   - 仅在跨路由离开时触发；页内局部操作（测试连接/刷新最新数据/数据源切换内部刷新）不触发。
   - 保存成功后重置基线，`isDirty` 归零。
3. 数据源切换（同页内切 config）若也会丢草稿，可复用同一 dirty 判定给出提示或显式丢弃确认。
4. 可选：浏览器关闭/刷新场景加 `beforeunload` 提示（注意仅在 dirty 时绑定，避免无脑拦截）。

### 影响面与验证

- 影响：仅 `frontend/src/views/MirrorSettingsView.vue` 及其 controller composable。低风险。
- 验证：
  - 编辑未保存后切页：出现离开确认；取消则留在原页且草稿保留，确认则离开。
  - 测试连接/刷新最新数据/状态轮询：不触发离开确认（保持第 4 轮已验证结论）。
  - 保存后切页：无确认。
  - 新增 controller 单测覆盖 dirty 判定与守卫触发条件。

## R-1：全量类同步缺二次确认（发布前待复测）

### 现状（已确认代码路径）

- [useMirrorSyncActionsController.ts](../../frontend/src/views/useMirrorSyncActionsController.ts) 中 `startFullSync` / `startFullCompensationSync` 均为“先 `saveConfig(false)` 再直接提交任务”（[:88](../../frontend/src/views/useMirrorSyncActionsController.ts#L88)、[:116](../../frontend/src/views/useMirrorSyncActionsController.ts#L116)），无 `ElMessageBox` 二次确认。
- 报告第 8 轮因当前数据源命中同源保护（400）未能完成提交路径验收，故列为“待复测”。

### 方案（复测确认仍为直接提交后再实施）

1. 先在“无同源冲突的测试数据源”上复测：确认全量同步 / 全量补偿对账是否真的没有二次确认。
2. 若确认无确认弹窗，对全量类重操作补 `ElMessageBox.confirm`：
   - “首次全量同步”：提示耗时与覆盖范围后再提交。
   - “全量补偿对账（FULL_COMPENSATION_SCAN / FULL_RECONCILE）”：影响面与耗时更大，确认文案需更强，并提示建议在业务低峰执行。
3. 与“删除镜像数据”已有的确认短语保护保持体验一致（第 8 轮已验证 purge 弹窗 OK）。
4. 增量同步 / 页面级 `TABLE_REFRESH` 不加二次确认，避免拖慢高频路径。

### 影响面与验证

- 影响：仅 `useMirrorSyncActionsController.ts` 及调用处。低风险。
- 验证：复测脚本在无冲突测试源上点击全量类操作，确认弹出二次确认；取消则不提交，确认才提交。

## 实施顺序建议

1. P1-1 健康检查（部署可信度，安全配置已默认其存在）。
2. P1-2 同源文案对齐（后端规则已落地，前端必须跟上）。
3. P2-1 游客态请求策略（第一眼体验）。
4. P3-1 离页草稿保护、R-1 全量二次确认（先复测）。
5. P2-2 窄屏登录档位 A（桌面内网优先级中；档位 B 单列 backlog）。
6. 将本轮用户旅程脚本沉淀为可复跑 smoke，至少覆盖“编辑中轮询不覆盖草稿”“日志展开后滚动条可用”“游客态无 401”三类回归。

## 说明

本文仅为方案设计，未改动任何业务代码。各项实施需各自走构建与测试验证（后端 `mvn -f backend/pom.xml test`，前端 `npm run test` / `npm run typecheck`）。

