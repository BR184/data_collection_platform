# BI 看板权限按页面拆分（工作单元计划）

> 状态：**已批准并实施**（2026-09-28 用户指派按照本方案实现；代码、迁移、前端与文档均已完成，验证见下）
> 归属：BI 权限模型变更；平台通用能力（鉴权范式）与 BI 子域同时涉及

## 0. 进度与中间物

- 当前状态：实现完成；后端/前端套件、仓库护栏、迁移隔离验证与开发实例落库验证均已跑（数字见 §9）。**黄金基线门禁未跑**——用户 2026-09-28 决定待新功能完成后统一收口回归（`permission-settings` 与 `post/put` 相关快照按 §5.5 预期会变化，重建须另获授权）。
- 已完成（只读调查）：
  - 定位 BI 现存权限：`bi.dashboard.view` / `bi.dashboard.download` 两个模块级码，仅 `BiDashboardController` 类级注解与两个下载端点使用。
  - 摸清既有按页面鉴权范式：统计板/分析看板走 `@RequirePagePermission` + `PagePermissionKeyResolver` + `PlatformAuthorizationInterceptor`。
  - 摸清门禁与测试约束：`BiDashboardSecurityContractTest`、`frontend/src/feature-manifest-access.test.ts` 直接断言旧码；黄金门禁以 `admin`（SUPER_ADMIN）登录。
  - 登记 7 个 BI 页面的稳定 page key 与图表注册表 pageKey 集合。
- 已完成（实施，2026-09-28）：
  - 后端：`PlatformPermissionCodes` 14 个页面码替换 2 个模块码；新增 `BiPagePermissionResolver`（页面 → view/download 唯一映射，未登记 key 报 `BizException`）；`BiDashboardController` 去掉类级注解、7 个页面端点各挂本页查看码、`/versions` 任一查看码、两个下载端点「任一页面下载码」+ 按请求体 `pageKey` 精确校验；`GlobalExceptionHandler` 补 `AccessDeniedException` → 403（见 §7 偏差 ②）。
  - 迁移 `V20260928_01__bi_dashboard_page_permissions.sql`：14 码入库、按旧码持有角色逐角色复制角色授权、补 `customer_issue.customer.*` 的 defaults、删除旧码；校验和已登记 `scripts/flyway-migration-checksums.json`。
  - 迁移 `V20260929_01__bi_dashboard_default_permissions.sql`（前向修正，见 §7 偏差 ⑥）：开发库实测发现 928_01 把「当前持有」也写成 defaults，会让上线期的临时隐藏被固化、使「恢复默认权限」无法回到全员可见；本迁移只把 14 个页面码的 defaults 归位为平台托管五角色全量，**不动角色授权**（升级前后逐角色可见性不变）。校验和同样已登记。
  - 前端：`modules.ts` 7 页改本页查看码；新增 `features/bi-dashboard/data/page-permissions.ts`（前端页面码单一映射）并让 `BiChartPanel` 下载门按本页下载码判断（见 §7 偏差 ③）。
  - 测试：`BiPagePermissionResolverTest`、`BiDownloadPagePermissionTest`（403/400/200 与跨页拒绝）、重写 `BiDashboardSecurityContractTest`、`GlobalExceptionHandlerTest` 补 403 用例、前端 `page-permissions.test.ts` + `feature-manifest-access.test.ts`/`router.test.ts` 改按页断言。
  - 文档：`docs/bi-dashboard/decisions.md` D-04、`docs/decisions.md` D-22、`docs/bi-dashboard/architecture.md`、`docs/bi-dashboard/product.md`、`docs/architecture.md`（见 §7 偏差 ④）。
- 阻塞：无（黄金门禁按用户决定延后）。

## 1. 恢复线索

- 当前阶段：S0 方案审批。
- 恢复后首条命令：
  `git status --short && git log --oneline -5 -- backend/src/main/java/com/data/collection/platform/bi/api/BiDashboardController.java`
- 依据 commit：`b3dd8e2a`（新增 BI 客户问题页，本方案的服务对象）、`V20260804_01__bi_dashboard_permissions.sql`（既有 BI 权限迁移范式）。
- 关联计划：`docs/plans/customer-issue-customer-statistics-and-bi-20260922.md`（客户问题页与统计板来源单元）。

## 2. 目标与边界

### 目标

1. BI 看板由「一个模块一个查看权限」改为「**每个页面各自持有查看与下载权限**」，与统计板（`system_test.*`、`customer_issue.*`）的既有范式一致。
2. 全部 BI 页面（含新增客户问题页）默认**全员可见**，与平台既有 BI 默认口径一致；「除管理员外不可见」只是上线后为避免领导使用未完成版本的**临时手段**，不写入默认口径。
3. 既有 6 个 BI 阶段页的可见性**逐角色保持不变**（升级后无人丢失已有访问能力）。
4. 权限可在平台「权限设置」页按页面独立授予/撤销，撤销后菜单不可见、且后端与下载通道同步拒绝。

### 边界（明确禁止）

- 不修改任何 BI 端点路径、请求体或响应结构（含 `/api/bi/{page}`、`/download/authorize`、`/download/excel`）。
- 不修改任何统计口径、指标公式、图表模板与快照内容。
- 不为兼容保留 `bi.dashboard.view` / `bi.dashboard.download` 的别名、转发或「双码并存」过渡态。
- 不动本工作单元之外的任何在途改动（响应延期规则、前端客户问题页等在途工作树内容）。
- 不通过放宽门禁、挪目录或改掩码让快照变绿。

## 3. 约束与背景

- 仓库鉴权范式：方法级 `@RequirePermission(常量)`；依赖路由变量的页面级鉴权用 `@RequirePagePermission(resource, action)`，由 `PlatformAuthorizationInterceptor` 在调用前解析（`backend/src/main/java/com/data/collection/platform/security/PlatformAuthorizationInterceptor.java`）。
- `RequirePagePermission.Resource` 现有取值仅 `STATISTIC_BOARD`、`ANALYTICS_DASHBOARD`，且其解析依赖 URI 路由变量 `boardKey`/`dashboardKey`；**BI 端点没有页面路由变量**，页面身份只存在于路径常量与下载请求体。
- 程序化鉴权既有先例：`PlatformPermissionService.hasPermission/requirePermission(user, code)`（`PlatformPermissionService.java:70-78`，抛 Spring `AccessDeniedException`）与 `ReviewDataAuthorizationService`。
- 当前登录用户权限可从 `AuthUserResponse.permissions` / `AuthUserResponse.hasPermission(code)` 直接取得（会话与 `SecurityContextHolder` 均持有该主体）。
- 黄金门禁：登录账号为引导管理员 `admin` → `SUPER_ADMIN`（`LocalPlatformAuthenticationProvider.java:45`）；端点目录以路径为键，路径不变则目录无需增删。
- 迁移不可变：已登记校验和的迁移禁止改动，只能新增；权限类迁移必须同时写 `platform_permissions`、`platform_role_permissions`、`platform_default_role_permissions`（范本 `V20260804_01__bi_dashboard_permissions.sql`）。

## 4. 证据与根因

- BI 目前只有两个权限码，且挂在类级注解上：`BiDashboardController.java:39`（类级 VIEW）、`:134-139`/`:154-159`（下载 requireAll VIEW+DOWNLOAD）。
- 7 个 BI 页面的稳定 page key 已存在，分布在三处且语义一致：
  - 查看端点：`requirements`/`design`/`coding`/`unit-test`/`integration-test`/`system-test`（`BiDashboardController.java:58-110`）
  - 新增页面：`customer-issues`（`BiCustomerIssuePageService.PAGE_KEY`）
  - 下载图表注册表：同一组 key + `CUSTOMER_ISSUE` 范围（`BiDownloadAuthorizationService.java:16-51`）
- 现有测试把「一个模块一个码」写死：
  - `backend/src/test/java/com/data/collection/platform/bi/api/BiDashboardSecurityContractTest.java:21-30`
  - `frontend/src/feature-manifest-access.test.ts:77`（断言所有 BI 页面 permission 均为 `bi.dashboard.view`）
- 前端菜单按页面声明权限，鉴权链路已按页面工作：`frontend/src/feature-manifest/modules.ts`（每个 page 带 `permission`）→ `canAccessPageKey` → `router.beforeEach`（`router.ts:339,364`）。因此前端只需改权限值，机制无需新建。
- 相关缺陷（同批可选修正）：`V20260922_02__customer_issue_customer_statistics_permissions.sql` 未写 `platform_default_role_permissions`，而 `PlatformPermissionService.restoreDefaultPermissions()`（`:146-168`）会「清空角色授权 → 从 defaults 重播种」，表快照 `post___restore-defaults__restore__platform_role_permissions.json` 中新码出现 0 次可佐证。

## 5. 方案与步骤

### 5.1 权限码设计（单一事实源）

| # | BI 页面 | 后端 page key | 查看权限码 | 下载权限码 |
|---|---|---|---|---|
| 1 | 需求 | `requirements` | `bi.dashboard.requirements.view` | `bi.dashboard.requirements.download` |
| 2 | 设计 | `design` | `bi.dashboard.design.view` | `bi.dashboard.design.download` |
| 3 | 编码 | `coding` | `bi.dashboard.coding.view` | `bi.dashboard.coding.download` |
| 4 | 单元测试 | `unit-test` | `bi.dashboard.unit_test.view` | `bi.dashboard.unit_test.download` |
| 5 | 集成测试 | `integration-test` | `bi.dashboard.integration_test.view` | `bi.dashboard.integration_test.download` |
| 6 | 系统测试 | `system-test` | `bi.dashboard.system_test.view` | `bi.dashboard.system_test.download` |
| 7 | 客户问题 | `customer-issues` | `bi.dashboard.customer_issues.view` | `bi.dashboard.customer_issues.download` |

- 命名遵循既有 `<域>.<页面>.<动作>` 与下划线分词（对齐 `system_test.summary.view`）；模块名沿用 `BI 看板`，`sort_order` 取 8010–8016（view）/ 8020–8026（download）——该区间现仅被待删除的两个旧码占用。
- 删除 `bi.dashboard.view` / `bi.dashboard.download`（连同 `PlatformPermissionCodes` 常量）。

### 5.2 后端改动

1. **新增 `BiPagePermissionResolver`**（`backend/src/main/java/com/data/collection/platform/bi/application/`）
   - `String viewCode(String pageKey)` / `String downloadCode(String pageKey)`；未知 key 抛 `BizException`（对齐 `PagePermissionKeyResolver.require`）。
   - 作为 pageKey → 权限码的**唯一事实源**，供查看端点鉴权、下载校验与测试共用。
2. **`BiDashboardController`**
   - 删除类级 `@RequirePermission(BI_DASHBOARD_VIEW)`。
   - 7 个页面端点分别标注各自的 `.view` 码。
   - `/versions`（产品版本目录，各页共用）标注为「**任一** `.view` 码即可」（`requireAll=false`）。
   - 两个下载端点：注解改为「**任一** `.download` 码即可」的粗门禁，再在方法内用 `PlatformPermissionService.requirePermission(user, resolver.viewCode(pageKey))` 与 `requirePermission(user, resolver.downloadCode(pageKey))` 按 `request.pageKey()` 做**页面级精确校验**（等价现状 `requireAll=true` 语义）。当前用户经 `SecurityContextHolder` 取（新增 `AuthSessionSupport` 无参重载）。
3. **测试**
   - 重写 `BiDashboardSecurityContractTest`：逐端点断言各自 view 码、`/versions` 的任一语义、下载端点解析出的页面精确码。
   - 新增 `BiPagePermissionResolverTest`：7 个 key 双向映射、未知 key 报错。
   - 下载鉴权行为测试：无 download 码拒绝、有 download 无该页 view 拒绝、二者齐备通过、跨页下载（A 页权限下 B 页 pageKey）拒绝。

### 5.3 迁移（新增 `V20260928_01__bi_dashboard_page_permissions.sql`）

1. 插入 14 个权限码（module_name `BI 看板`），`on conflict do update`。
2. `platform_role_permissions`：对全部 7 个页面（含 `customer-issues`），**按现有 `bi.dashboard.view`/`bi.dashboard.download` 的持有角色逐角色复制**该页 view/download 授予，保留内网既有定制；升级前后逐角色可见性一致。
3. `platform_default_role_permissions`：默认口径固定为**平台托管五角色全员可见**，不随「当前持有」漂移（避免把上线期的临时隐藏固化进默认，也避免 `restore-defaults` 清空新码）。实施中该项先随第 2 步写入、再以 `V20260929_01` 前向归位，原因与影响见 §7 偏差 ⑥。
4. 删除 `platform_role_permissions`/`platform_default_role_permissions` 中两个旧码的记录，再删除 `platform_permissions` 两行。
5. 登记校验和到 `scripts/flyway-migration-checksums.json`（只新增条目），跑 `scripts/check_flyway_migration_immutability.py`。
6. **同批修正 defaults 缺口**（见决策 D-5）：为 `customer_issue.customer.view/export` 补写 `platform_default_role_permissions`，沿用其现有 `platform_role_permissions` 的全员口径，使「恢复默认权限」回到**全员可见**，而不是把该权限从所有角色（含 ADMIN）清掉。

### 5.4 前端改动

- `frontend/src/feature-manifest/modules.ts`：7 个 BI 页面 `permission` 改为各自 view 码。
- `frontend/src/feature-manifest-access.test.ts`:77 改为逐页面断言，并保留「隐藏后 `canAccessPageKey` 为 false、URL 直达被 `router.beforeEach` 拦截」用例。
- 全仓搜索确认无其它 `bi.dashboard.view` 引用（含 API 客户端、BI 视图、脚本）。

### 5.5 门禁与快照

- 端点路径未变 → `endpoint-catalog.yml` 无需增删。
- 预期受影响快照：`permission-settings/get____default.json`（目录新增 12 个移除 2 个）、`post___restore-defaults__restore.json`(+表快照)、`put___roles_{roleCode}__normal-user-two-codes.json`(+表快照)。
- BI 页面/下载端点快照：门禁以 `admin`(SUPER_ADMIN) 登录且迁移已授予新码 → 预期**零差异**；出现差异一律按回归处理，不得改快照掩盖。
- 执行顺序：先跑默认快速套件 → 再 `-Dgolden.update=true` 只重建受影响快照 → `git diff` 人工审阅并向用户展示差异 → 去掉更新模式复跑全绿。

### 5.6 文档

- `docs/decisions.md`：新增决策（BI 按页面拆分 view+download；删除模块级码；默认全员可见，「除管理员外不可见」仅为上线后临时手段）。
- `docs/architecture.md` 与 `docs/bi-dashboard/architecture.md`：BI 鉴权段落改为按页面权限。
- `docs/platform-page-business-rules.md`：BI 权限条目。
- `docs/progress.md`：本单元状态与验证证据。

### 5.7 运维（部署到内网后由人工执行）

- 需要临时隐藏未完成版本的页面时，在权限设置页撤销非 `ADMIN`/`SUPER_ADMIN` 角色的对应权限：
  - BI 客户问题页：`bi.dashboard.customer_issues.view`、`bi.dashboard.customer_issues.download`
  - 统计板客户问题统计页：`customer_issue.customer.view`、`customer_issue.customer.export`
- 该隐藏是**临时**的：点「恢复默认权限」即回到全员可见（既定语义）。测试完成后无需反向操作，恢复默认或重新授予即可。

## 6. 决策记录

| 编号 | 事项 | 结论 | 理由 / 否决项 |
|---|---|---|---|
| D-1 | 是否保留模块级总开关 | **已选**：单层，删除 `bi.dashboard.view`/`bi.dashboard.download` | 与统计板一致（统计板无模块级码）；两层会出现「有模块码无页面码」的空菜单中间态。否决：保留模块码作为「BI 模块准入」 |
| D-2 | 下载权限粒度 | **已选**：每页面一个 download 码，共 14 码 | 对齐统计板 view+export 成对范式；只拆 view 会留下载通道绕过页面权限。否决：保留单一模块级 download 码 |
| D-3 | 下载页面级校验落点 | **已选**：controller 内按 `request.pageKey()` 程序化校验 | pageKey 在请求体，拦截器拿不到；已有 `requirePermission`/`AccessDeniedException` 先例。否决：改路径为 `/api/bi/{pageKey}/download/*`（破坏端点契约、pageKey 双源）；否决：在 `BiDownloadAuthorizationService` 内耦合平台权限 |
| D-4 | 全部页面升级后授权 | **已选**：按现有旧码持有角色逐角色复制（默认即 5 角色全员可见） | 用户口径（2026-09-28 确认）：新页面默认**全员开启**，「除管理员外不可见」只是上线后避免领导使用未完成版本的**临时手段**，不写入默认口径。否决：在迁移里把新页设为管理员专属 |
| D-5 | 是否同批补 `customer_issue.customer.*` 的 defaults | **已选**：同批补写（全员口径） | 既然默认全员可见，defaults 就应为全员；不补则 `restore-defaults` 会把它从所有角色（含 ADMIN）清掉，属缺陷。否决：同批把该页收为管理员可见 |
| D-6 | 权限码命名前缀 | **已选**：沿用 `bi.dashboard.` 前缀 + 页面 key | 保持 BI 看板权限族可排序聚合。否决：`bi.page.*`（割裂既有族） |

## 7. 接口契约

- 对外 HTTP 契约**不变**：所有路径、查询/请求体、响应结构、状态码语义（未登录 401、无权限 403）保持现状。
- 新增内部契约：
  ```java
  // com.data.collection.platform.bi.application.BiPagePermissionResolver
  public String viewCode(String pageKey);      // "system-test" -> "bi.dashboard.system_test.view"
  public String downloadCode(String pageKey);  // 未知 pageKey -> BizException
  ```
- 新增权限码（14 个）与模块归属、sort_order 见 5.1。
- 迁移：`V20260928_01__bi_dashboard_page_permissions.sql`（新增，不可变登记）、`V20260929_01__bi_dashboard_default_permissions.sql`（前向修正 defaults 口径，不可变登记）。
- 不变式：任一 `.download` 码生效的必要条件是该页面 `.view` 码同时生效（与拆分前 requireAll 语义一致）。

## 8. 风险与假设

- 假设1：BI 客户问题页与统计板客户问题统计页均**尚未在内网发布**，无既有可见性需要保留；默认口径按平台惯例设为全员可见。
- 假设2：门禁账号 `admin` 恒为 `SUPER_ADMIN`（已核实 `LocalPlatformAuthenticationProvider.java:45`）；若未来改为非管理员账号，需在快照前同步授权。
- 风险1：删除旧码后，内网若有脚本/文档以 `bi.dashboard.view` 判断，会失效 → 实施前全仓 + 内网配置搜索确认无外部依赖。
- 风险2：下载端点由注解校验改为程序化校验，若异常映射与拦截器不一致会出现 500 而非 403 → 必须补 403 断言测试。
- 风险3：上线后的**临时隐藏**会被「恢复默认权限」按 defaults 恢复为全员可见（清空→按 defaults 重播种）。这是既定语义（默认=全员可见），需在发布说明中写明：临时隐藏不是持久配置，测试完成后无需反向操作。
- 风险4：当前工作树存在在途改动（响应延期、前端客户问题页），本单元必须独立提交，避免混提与快照误覆盖。
- 风险5：`sort_order` 若与其它模块未来分配冲突 → 实施时以 `get____default.json` 目录快照核对独占区间。

## 9. 实施偏差与验证

实施偏差（均为修正方案本身的缺口，理由随附）：

1. **当前用户取值来源**：§5.2 原写「新增 `AuthSessionSupport` 无参重载（经 `SecurityContextHolder`）」。实施改为两个下载端点显式接收 `HttpServletRequest` 并调用既有的 `AuthSessionSupport.currentUser(request)`——与拦截器**同一取值源**，避免「拦截器读会话、控制器读安全上下文」两套取法在过滤链外场景下产生分歧；生产环境两者等价（`PlatformSessionAuthenticationFilter` 每请求由会话写入安全上下文），测试也无需额外摆弄 `SecurityContextHolder`。未新增任何公开 API。
2. **`AccessDeniedException` → 403 映射**：§8 风险2 要求「必须补 403 断言测试」。实测 `GlobalExceptionHandler` 无该异常处理，程序化拒绝会落成 500「服务处理异常」。为满足 §7「无权限 403 保持现状」，在异常映射入口补 `AccessDeniedException` → 403 + `A0303`（与拦截器、`platformAccessDeniedHandler` 同口径）；该修正同时让评审删除等既有程序化鉴权路径的拒绝不再误报 500。详见 `docs/decisions.md` D-22。
3. **前端下载门（方案 §5.4 遗漏项）**：`BiChartPanel.vue:45` 以旧模块码 `bi.dashboard.download` 控制下载按钮可见性。删码后若不同步，所有人（含管理员）都会看不到下载按钮。故新增 `features/bi-dashboard/data/page-permissions.ts` 作为前端页面码单一映射，`BiChartPanel` 按 `downloadContext.pageKey` 取本页下载码；`modules.ts` 的查看码与它由 `page-permissions.test.ts` 锁定一致（后端对应 `BiPagePermissionResolver`）。
4. **文档落点（方案 §5.6 修正）**：§5.6 原列 `docs/platform-page-business-rules.md` 加 BI 权限条目，与 AGENTS.md「BI 专属事实只维护在 `docs/bi-dashboard/`」冲突，故 BI 页面权限事实改写入 `docs/bi-dashboard/architecture.md`（请求与安全）与 `docs/bi-dashboard/product.md`，BI 决策写入 `docs/bi-dashboard/decisions.md` D-04；平台通用机制（按页面解析范式、程序化拒绝 403、默认授权登记口径）写入 `docs/architecture.md` 与 `docs/decisions.md` D-22，平台文档不复制 BI 专属结论。
5. **迁移授权口径的精确化**：§5.3 第 2 步的「逐角色复制」落实为以旧码持有角色集合为唯一来源写入 `platform_role_permissions`（与仓库既有权限迁移范式一致，见 `V20260903_01`/`V20260909_01`）；隔离库实测「五角色全量」与「部分角色被撤销 / view 与 download 集合不同」两种前置状态都逐角色保持一致。**defaults 不再随该集合写入**，见偏差 ⑥。
6. **defaults 口径与角色授权解耦（前向修正迁移 `V20260929_01`）**：§5.3 第 3 步原写「defaults 与第 2 步同一口径写入」，隐含「defaults = 当前持有角色」。开发库上迁移后核对发现：`NORMAL_USER` 在升级前**本来不持有** `bi.dashboard.view`（上线前为避免使用未完成版本的临时隐藏），928_01 把这一隐藏也写进了 defaults → 点「恢复默认权限」无法回到全员可见，与用户确认的既定口径（默认全员可见、隐藏只是临时手段）冲突。因已登记迁移不可改，改用前向修正：929_01 把 14 个页面码的 defaults 归位为平台托管五角色全量（`on conflict do nothing`），**完全不触碰 `platform_role_permissions`**，因此升级前后逐角色可见性逐一不变、定制继续生效，而「恢复默认权限」回到全员可见。此偏差固化为平台级规则，见 `docs/decisions.md` D-22。

验证证据：

- 迁移隔离验证（测试库容器 15433 临时库，脚本 `.tmp/bi-perm-verify/verify_migration.py`）：按文件名顺序重放 134 个既有迁移后，按顺序执行两个新迁移，分三场景断言——A 默认（旧码五角色全量）→ 五角色各得 7 查看 + 7 下载码、旧码从目录/角色/defaults 三处清除、defaults 为五角色全量、`module_name='BI 看板'` 恰 14 条且 sort_order 独占 8010–8026、模拟「恢复默认权限」后仍持有全部页面码；B 定制（撤销 NORMAL_USER 的查看、撤销 DIRECT_MANAGER 的下载，旧 defaults 未改动，即开发库现状）→ 记录「仅 928_01」中间态证实隐藏被带入 defaults（`NORMAL_USER` 只有下载码），929_01 后逐角色可见性不变（`NORMAL_USER` 无查看码但保留下载码、`DIRECT_MANAGER` 无下载码）、defaults 一律回到五角色全量、模拟「恢复默认权限」回到全员可见；C 修补 `customer_issue.customer.*` defaults 缺口（迁移前为空、迁移后与五角色角色授权一致）。**RESULT=PASS**，临时库全部删除，未触碰开发库与测试库既有 `qaflex`。
- 定向测试：`BiPagePermissionResolverTest` 4 项、`BiDashboardSecurityContractTest` 4 项、`BiDownloadPagePermissionTest` 6 项、`BiDashboardControllerExcelExportTest` 3 项、`GlobalExceptionHandlerTest` 7 项、`PlatformAuthorizationInterceptorTest` 3 项，共 27 项全绿。
- 后端默认套件（含两个迁移在测试库应用）：**1567 项 / 0 失败 / 0 错误 / 1 跳过，BUILD SUCCESS**；13 项仓库护栏全绿（Flyway 不可变 136 迁移）、`git diff --check` exit 0；`check_issue_fact_module_pollution` 连开发库只读复跑无污染。
- 开发实例落库验证（2026-09-29，经用户授权重启）：Flyway 应用 `20260929.01`（13 ms）、18080 `/actuator/health` UP、18181 HTTP 200；开发库只读核对——BI 目录 14 码、旧码在目录/角色/defaults 三处为 0、角色授权 4 角色×14（`NORMAL_USER` 0 码＝临时隐藏保留）、defaults 5 角色×14；`admin` 登录实测 `/api/permission-settings` 返回 14 个页面码且无旧码，7 个 BI 页面端点与 `/api/bi/versions` 全部 200；越权/CSRF 拒绝在实例上稳定返回 403 `A0303`。
- 前端：`page-permissions.test.ts`(3)、`feature-manifest-access.test.ts`(11)、`router.test.ts`(25) 通过；`tsc --noEmit`、`eslint` 通过。
- 全量套件与仓库护栏结果见 `docs/progress.md` 本轮条目；黄金基线快照按 §0 说明留待统一收口。
