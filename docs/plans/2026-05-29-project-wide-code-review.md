# 2026-05-29 全项目 Code Review 报告

## 审查范围与方法

本次 review 覆盖整个 `data_collection_platform` 仓库（backend Spring Boot 3 / Java 21 + frontend Vue 3 / TS + 数据库迁移 + CI / 脚本），并对已有的专项审查文档 [`2026-05-29-import-and-new-feature-code-review.md`](2026-05-29-import-and-new-feature-code-review.md) 做二次复核。

方法：

- 用并行 Explore agent 对四个维度（已有 review 复核、前端、后端、运维/构建）做证据采集，再对每条高影响 finding 用 Read/Grep 二次核对到具体行号。
- 不修改任何业务代码，只做记录。
- 严重等级：**Critical**（合并前必须修复）/ **Important**（合并前应有明确处置）/ **Consider**（建议跟进）/ **FYI**（仅作记录）。

> ⚠️ Agent 一次性产出的 finding 中存在若干已被证伪的项（详见“误报清单”章节），下面的清单已经剔除或修正。

## Part 1 — 对 `2026-05-29-import-and-new-feature-code-review.md` 的复核

总体评价：质量很高。9 条 finding 中 8 条仍然成立、定位行号准确、严重等级合理、整改方案切实可行。仅 1 条（Finding 6 关于 demo SQL mojibake）已经不复存在。

| # | 原 finding | 复核结论 | 备注 |
|---|---|---|---|
| 1 | preview 快照导致 confirm 阶段忽略用户后改的默认值 | **CONFIRMED, Critical 合理** | `ReviewDataLegacyExcelImportService.java:81-104` 直接消费 `preview.rows()`，确认请求只接 `previewToken`/`duplicateStrategy`。建议优先采用方案 A（请求补齐字段并基于原始 row 重建）。 |
| 2 | preview token 无 TTL/容量限制 | **CONFIRMED, Critical 合理** | `previewCache` 是裸 `ConcurrentHashMap`（line 25），仅在 confirm 成功后 remove（line 106）。多实例/重启/异常路径都会泄漏。 |
| 3 | 解析阶段把负数静默归零 | **CONFIRMED, Important 合理** | `ReviewDataLegacyExcelParser.java:278` (`Math.max(0, …)`) + `ReviewDataLegacyExcelImportService.java:307-313` 重复实现 `safeInt/safeDouble`。建议补充：`safeInt/safeDouble` 在 service 与 parser 中重复定义，整改时一并去重。 |
| 4 | `.xls` 名义被允许但只支持列表导出 `.xlsx` | **CONFIRMED, Important 合理** | parser 与前端 `accept` 都允许 `.xls`，但解析器靠列表导出表头识别。 |
| 5 | preview/confirm 没有上传体积/边界控制 | **CONFIRMED, Important 合理** | 控制器直接 `file.getInputStream()`，`MAX_ROWS=5000` 仅在 workbook 打开后生效。 |
| 6 | demo SQL 含 mojibake | **ALREADY FIXED** | `file` 命令显示两份脚本均为 UTF-8 文本，对 `銆/绯荤/脙/鐧` 等典型乱码片段 grep 无命中。建议把这条划掉或改写为“保留 mojibake 防御性断言测试”。 |
| 7 | 集成测试导出走裸 `fetch()`，无统一头/超时/错误解析 | **CONFIRMED, Important 合理** | `integration-tests-api.ts:74-79`、`105-111`，与 `request.ts` 的统一封装形成不一致。 |
| 8 | 单条写入 + 重复 `refreshSearchIndex` | **CONFIRMED, Consider 合理** | 5000 行每行 1 次 record + N 次 problem item + 1 次显式索引刷新；`createRecord()` 内部本身就刷新了一次。 |
| 9 | 统计下钻链接需要回归 | **CONFIRMED, Consider 合理** | 已有单测覆盖；建议补一条 Playwright 端到端。 |

补充：在复核 Finding 1/2/3/8 时，在同文件内还看到一处可一并整理的小问题——
`ReviewDataLegacyExcelImportService.safeInt/safeDouble`（line 307-313）与 `ReviewDataLegacyExcelParser.safeInt/safeDouble`（line 430-432）逻辑完全重复，建议在整改 Finding 3 时合并到共用 util。

## Part 2 — 新增项目级 Findings

下面的 finding 都不与已有专项审查重复。

### A. 安全 / Security

#### A1. Critical: 鉴权策略以 `permitAll` 兜底，授权完全依赖单一 `@RequireRole` 拦截器

位置：

- [backend/src/main/java/com/data/collection/platform/config/PlatformSecurityConfiguration.java:38](../../backend/src/main/java/com/data/collection/platform/config/PlatformSecurityConfiguration.java#L38)
- `backend/src/main/java/com/data/collection/platform/security/PlatformAuthorizationInterceptor.java`

现象：

- Spring Security 链以 `.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())` 兜底，所有请求在 Spring Security 层无条件放行。
- 真正的 RBAC 是用 MVC 拦截器 `PlatformAuthorizationInterceptor` + `@RequireRole` 注解实现的，应用层一旦遗漏注解或拦截器注册失败，端点即变为公开。

影响：

- 这是“deny by default”反模式。任何新增 controller 方法只要忘加 `@RequireRole`，默认就是匿名可访问。
- 静态资源、错误页、Spring 管理端点、未来加入的 actuator 之类容易被无意暴露。

建议方案：

- Spring Security 链改为 `.anyRequest().authenticated()`，再用一个白名单显式 permit `/api/auth/**`、健康检查和静态资源。
- `@RequireRole` 继续承担细粒度角色判定，但鉴权基线由 SecurityFilterChain 兜底。
- 增加一条架构守护测试：扫描所有 controller 公共方法，断言要么命中白名单、要么标注了 `@RequireRole`。

#### A2. Critical: 本地认证使用明文密码 + `String.equals` 比较

位置：

- [backend/src/main/java/com/data/collection/platform/security/LocalPlatformAuthenticationProvider.java:19-22](../../backend/src/main/java/com/data/collection/platform/security/LocalPlatformAuthenticationProvider.java#L19-L22)
- [backend/src/main/java/com/data/collection/platform/config/PlatformAuthProperties.java:11-13](../../backend/src/main/java/com/data/collection/platform/config/PlatformAuthProperties.java#L11-L13)

现象：

- 配置文件里以明文存储管理员/审批账号密码，认证逻辑直接 `password.equals(properties.getAdminPassword())`。
- 默认值 `admin123` / `approval` 硬编码在 properties 类里，仅靠 `PlatformStartupSecurityGuard`（line 27-32）阻止生产部署使用默认值。

影响：

- 运维事故、配置文件外泄、备份外泄都会直接泄露原始密码；账号无法复用其他系统的统一鉴权。
- `String.equals` 不是常量时间比较，理论上可被时序侧信道探测密码长度（实际威胁有限，但既然已经在改造范围内可一并修掉）。
- 一旦 `secureConfigRequired=false` 被某次 demo 部署设置，默认密码即可登入。

建议方案：

- 改用 `PasswordEncoder` (`BCryptPasswordEncoder` 或 `DelegatingPasswordEncoder`)；存储 hash 而非明文。
- 用 `MessageDigest.isEqual` / `Hex.constantTimeEquals` 类常量时间比较替代 `equals`。
- 移除 `PlatformAuthProperties` 中的硬编码默认值，启动时强制要求显式配置。
- 长期：把账号体系迁移到 LDAP（已有 `LdapPlatformAuthenticationProvider` 雏形）或外部 IdP。

#### A3. Important: CSRF 默认关闭

位置：

- [backend/src/main/java/com/data/collection/platform/config/PlatformAuthProperties.java:15](../../backend/src/main/java/com/data/collection/platform/config/PlatformAuthProperties.java#L15)
- [backend/src/main/java/com/data/collection/platform/config/PlatformSecurityConfiguration.java:25-29](../../backend/src/main/java/com/data/collection/platform/config/PlatformSecurityConfiguration.java#L25-L29)

现象：

- `csrfEnabled = false` 是默认值。前端 `request.ts:98-101` 已经按 cookie `XSRF-TOKEN` 写头，但只有显式开启后端配置才生效。

影响：

- 在浏览器场景下，浏览器附带 session cookie 自动随跨站表单/链接发送 POST，存在 CSRF 风险。
- 前后端 CSRF 实现“一半已经写好了、一半默认不开”，任何重新部署都可能踩坑。

建议方案：

- 默认开启 CSRF，并在文档里说明哪些 API-only 部署可以显式关掉。
- 加冒烟测试：开启 CSRF 时 POST 不带 `X-XSRF-TOKEN` 必须 403。

#### A4. Important: `PlatformAuditInterceptor` 跳过了 `/api/auth/**`，登录登出无审计

位置：

- [backend/src/main/java/com/data/collection/platform/security/PlatformAuditInterceptor.java:40-42](../../backend/src/main/java/com/data/collection/platform/security/PlatformAuditInterceptor.java#L40-L42)

现象：

- 所有 `/api/auth/` 前缀都直接 return，不进入审计记录。
- 同时 line 50 把异常类名 + `ex.getMessage()` 拼成审计字段，没有像请求体那样走 `sanitize()`，理论上可能落 DB 错误明文。

影响：

- 登录/登出/失败尝试不会留审计痕迹；后续做安全合规、追溯账号被滥用时缺少证据。
- 异常消息中可能夹带 SQL 错误回显的输入参数。

建议方案：

- 至少为登录成功/失败、密码修改类操作单独走一条审计代码路径（带用户名、IP、结果码、UA），但不要落明文密码。
- `ex.getMessage()` 在写入前过 `sanitize()`，或限制为 `ex.getClass().getSimpleName()` + 长度截断。

### B. 后端架构 / 持久化 / 并发

#### B1. Important: `SyncRunExecutorService` 使用 `Executors.newCachedThreadPool()`

位置：

- [backend/src/main/java/com/data/collection/platform/service/sync/SyncRunExecutorService.java:42](../../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunExecutorService.java#L42)

现象：

- 默认构造使用 `Executors.newCachedThreadPool(...)`，线程数无上限，空闲 60 秒回收。
- 应用层用 `activeRuns` + `availableSlots()` 自己维护并发上限，但这是“先 incrementAndGet 再 submit”的两步操作，并不是真正的线程池容量保证。

影响：

- 任意 bug、测试、未来新调用方绕过 `availableSlots()` 检查直接 submit，都可能让 JVM 创建任意数量线程，吃掉文件描述符和堆。
- 在 Windows 服务器上线程开销不便宜，事故时容易雪崩。

建议方案：

- 用 `ThreadPoolExecutor` 显式指定 `corePoolSize = maxConcurrentRuns()`、`maximumPoolSize = maxConcurrentRuns() * 2` 之类有界值，配 `LinkedBlockingQueue` + `CallerRunsPolicy` 或 `AbortPolicy`。
- 同样的检查也适用于 `SourceConnectionTester`（`backend/src/main/java/com/data/collection/platform/service/SourceConnectionTester.java`）。

#### B2. Important: `GitlabDirectJdbcExecutor` 为每个 config 起独立 `HikariDataSource`

位置：

- `backend/src/main/java/com/data/collection/platform/service/GitlabDirectJdbcExecutor.java`

现象：

- 每个 `GitlabSourceConfig` 单独创建 HikariDataSource，且 `maxPoolSize` 配置较小。
- 销毁/回收策略依赖 service 层手动管理。

影响：

- 频繁切换/新增配置会持续创建数据源，连接泄漏隐患高。
- 多 config 时整体连接数缺乏全局上限，外部 PG 易被占满。

建议方案：

- 维护一个全局 `Map<configKey, HikariDataSource>`，按 config 指纹缓存复用。
- 增加 `idleTimeout`、`maxLifetime`、`leakDetectionThreshold`，并在 `@PreDestroy` 钩子里逐一关闭。

#### B3. Important: 缺乏全局 `@RestControllerAdvice`，异常翻译散落

位置：

- 全 backend `grep -r "ControllerAdvice"` 在业务包下无命中；只有 `PlatformSecurityConfiguration` 的 entryPoint/deniedHandler 处理鉴权异常。

现象：

- `BizException` 由各 controller 自行处理或落到 Spring 默认翻译；DB/IO 异常未统一收口。

影响：

- 错误响应格式不统一（前端在 `request.ts` 里兜底 `payload?.message`），调用方拿不到统一 `code/message`。
- 异常堆栈可能直接序列化进响应体，泄漏内部实现。

建议方案：

- 增加 `@RestControllerAdvice`：分别处理 `BizException`、`MethodArgumentNotValidException`、`AccessDeniedException`、`DataAccessException`、`Exception` 兜底；统一返回 `ApiResponse.fail(code, message)` 并打印结构化日志。
- 配套：把 controller 中重复的 try/catch 逐步收回 advice。

#### B4. Consider: “preview token + ConcurrentHashMap”模式在多个导入服务里重复出现

位置：

- `ReviewDataLegacyExcelImportService.java:25`（评审数据）
- 类似形态待复核：`CollectFormService` 等其他 Excel 解析 service。

现象：

- “解析→token→缓存→confirm”这套模式分散在多个 service，每处都自己写 `ConcurrentHashMap` + 无 TTL。

影响：

- 同一类问题（无 TTL、token 易失效、内存增长）会被重复触发，整改成本叠加。

建议方案：

- 抽一个 `PreviewSessionStore<T>`（基于 Caffeine `expireAfterWrite + maximumSize`），所有 import preview 共享。
- 同时把 token 存活策略、错误语义、metrics 统一掉。

### C. 前端架构

#### C1. Important: 多处导出/下载端点绕过 `request()` 封装

位置：

- [frontend/src/api-client/integration-tests-api.ts:74-79, 105-112](../../frontend/src/api-client/integration-tests-api.ts#L74-L112)
- 同类需复核：`code-review-api.ts`、`statistic-boards-api.ts`、`issue-records-api.ts` 的 export 函数。

现象：

- `request.ts` 已经提供完整封装：超时（默认 15s）、CSRF 头、JSON 错误解析、`ApiResponse.success` 拆封。
- 但导出/下载使用裸 `fetch()`，仅检查 `response.ok`，错误时 `throw new Error(text)`。

影响：

- 后端如果以 `ApiResponse{success:false,message:"..."}`（JSON）报错，前端会把整段 JSON 当文本扔进 toast。
- 没有超时控制，长时间导出会无声 hang。
- 后端将来对导出加 CSRF 校验，这些路径会立即崩。

建议方案：

- 抽 `requestBlob(url, init)` / `requestText(url, init)`：复用 `buildRequestHeaders` 与超时机制，按 `Content-Type` 解析 JSON 错误体取 `message`，成功时返回 `Blob` / `string`。
- 旧专项 review 的 Finding 7 与本条一次解决。

#### C2. Important: 前端无统一“匿名跳登录”守卫，依赖 App.vue watch 兜底

位置：

- [frontend/src/router.ts:244-261](../../frontend/src/router.ts#L244-L261)
- [frontend/src/App.vue:87-91, 144-150](../../frontend/src/App.vue#L87-L150)

现象：

- `router.beforeEach` 只处理 loading 和 query 归一化，不做权限判断。
- “无权限页面跳到第一可访问页”逻辑在 `App.vue` 的 watch 中，组件已经开始加载之后才被 redirect。

影响：

- 体验上有闪烁；直接访问敏感页面会先看到组件初始化中的空状态。
- 后端虽能拒绝，但前端没有 fail-fast。

建议方案：

- 在 `router.beforeEach` 中加权限判断：未登录跳登录；登录但角色不含 `meta.requiredRole` 时跳第一可访问页。
- 路由 meta 增加 `requiredRole` / `requiredFeature`，集中维护。
- App.vue 只保留登录态变更后的副作用，不再承担权限路由。

#### C3. Consider: 大型视图组件膨胀

位置：

- `frontend/src/views/MirrorSettingsView.vue`、`DatabaseBrowserView.vue`、`CodeReviewIllegalRecordsView.vue` 等。

现象：

- 单文件 700-1100+ 行；组合多个 composable，prop 透传层级深。

影响：

- 难维护、难测、code review 噪声大；合并冲突频繁。

建议方案：

- 不需要一次拆完。结合下次有功能改动时按子面板逐步拆出（例如 `MirrorConfigPanel` / `MirrorSyncPanel` / `MirrorWhitelistPanel`）。
- 配套：约束新视图行数（lint 规则或 PR checklist）。

#### C4. Consider: composable 用 `deep:true` watch 整个 `route.query`

位置：

- `frontend/src/composables/useRouteTableState.ts`

现象：

- 任意 query 字段变化都会触发 loader，即使变更与当前表格分页/排序无关。

影响：

- 父组件改某个不相关的 filter 也会让表格重新请求；高频场景下浪费 RT。

建议方案：

- 改成只 watch 该表格关心的具体 key 数组（`page/pageSize/sortBy/sortOrder/...filterKeys`）。
- 或者用 `computed` 派生“表格请求参数对象”，再 watch 那个对象。

#### C5. FYI: `request.ts` 在 JSON 解析失败时静默吞错

位置：

- [frontend/src/api-client/request.ts:62-67](../../frontend/src/api-client/request.ts#L62-L67)

现象：

- `JSON.parse` 失败时 `payload = null`，后续按 `response.ok` 判断成功；返回 `null as T`。
- 实际生产场景下后端总返回 JSON，问题不大；但反向代理插 HTML 错误页时会让前端拿到 `null` 数据而非异常。

建议方案：

- 当 `response.ok` 但 JSON 解析失败时主动 throw，让上层错误处理负责。

### D. 构建 / CI / 运维 / 数据

#### D1. Critical: `.gitlab-ci.yml` 中嵌入了测试库明文密码

位置：

- [.gitlab-ci.yml](../../.gitlab-ci.yml)（约 line 60、63，`POSTGRES_PASSWORD`、`TEST_DATASOURCE_PASSWORD`）

现象：

- 测试库密码 `qaflex_dev_2026` 以明文写在 CI 配置里。

影响：

- 即便是测试库，仍暴露团队的命名习惯（`项目名_环境_年份`），存在被尝试横向用于其他系统的风险。
- 如果未来该 DB 实例被复用做其他用途，将无法低成本轮换。

建议方案：

- 迁移到 GitLab CI/CD masked variables；CI 文件里只引用 `$POSTGRES_PASSWORD`。
- 同步检查 `infra/`、`scripts/` 中是否还有同字符串残留，一并替换。

#### D2. Important: CI 缺少安全 / 质量门禁

位置：

- [.gitlab-ci.yml](../../.gitlab-ci.yml)
- `backend/pom.xml`

现象：

- 当前 CI 只做编译 + typecheck + 部分 smoke 测试。缺：
  - 后端 lint（Checkstyle / SpotBugs / PMD）。
  - 测试覆盖率报告与阈值（Jacoco）。
  - 依赖漏洞扫描（OWASP Dependency-Check / Trivy）。
  - 前端 lint（视 `package.json` 配置补到 CI）。

影响：

- 代码质量、依赖漏洞、覆盖率回退都缺少自动化兜底。

建议方案：

- 分阶段引入：第一步加 Jacoco 报告（先不卡阈值）、加 OWASP DC 报 SARIF、加前后端 lint job。第二步再卡门槛。

#### D3. Important: Flyway `baseline-on-migrate=true`

位置：

- `backend/src/main/resources/application.yml`（搜 `baseline-on-migrate`）

现象：

- `baseline-on-migrate: true` 允许对存量库自动 baseline。

影响：

- 在生产环境如果误把空版本表当 baseline 起点，后续所有迁移会被跳过；事故时无法自动发现。

建议方案：

- 生产 profile 强制 `baseline-on-migrate=false` 并显式指定 `baseline-version`。
- 每次发布前在 CI 跑 `mvn flyway:info` 比对预期版本。

#### D4. Important: 销毁性迁移没有备份/灰度策略

位置：

- 例：`backend/src/main/resources/db/migration/V20260515_00__remove_legacy_gitlab_sync_models.sql` 一次性 drop 5 张表 cascade。

现象：

- 迁移直接 drop，无 archive/rename + retention 期。

影响：

- 一旦在错环境/错时序执行，没有回滚路径。

建议方案：

- 销毁性迁移走两步：先 `ALTER TABLE … RENAME TO _legacy_xxx`（同一版本号）+ 等待若干个 release，再正式 `DROP`。
- 在 `docs/flyway-migration-rules.md` 里固化规则。

#### D5. Consider: `scripts/repair_demo_display_data.sql` 行尾混合 LF / NEL

位置：

- `scripts/repair_demo_display_data.sql`（`file` 命令报告 `with LF, NEL line terminators`）

现象：

- 文件中夹带 NEL（U+0085）作为行结束符，可能来自 Windows / Excel 复制粘贴。

影响：

- 在某些 psql 客户端下解析异常，或被当作字符串字面量的一部分。

建议方案：

- 用 `dos2unix`/编辑器另存为 LF；同时在 `.gitattributes` 里把 `*.sql` 设为 `text eol=lf`。

#### D6. FYI: 仓库根存在 `.tmp_backend_stdout.log`（39 MB）

位置：

- 仓库根 `.tmp_backend_stdout.log`

现状（已复核）：

- `.gitignore` 里 `*.log`、`.tmp/`、`tmp-*.log` 已经覆盖；`git check-ignore` 确认该文件被忽略，未进入版本库。

建议（仅作预防）：

- 这种本地运行日志最好直接放进 `.tmp/` 子目录或 `backend/logs/`，避免根目录被刷成日志夹。
- 已有的开发脚本（`run-backend.ps1` 等）建议默认输出到 `backend/logs/yyyyMMdd-HHmmss.log` 并带轮转。

## Part 3 — 误报清单

复核过程中，多次 Agent run 给出过若干在事实层面不成立的判断，集中记录如下，避免下次又被翻出来：

| Agent 原说法 | 复核结论 |
|---|---|
| “demo SQL 里有大量 mojibake” | 已修复：`file` 报 UTF-8，常见乱码片段 grep 0 命中。 |
| “`.tmp_backend_stdout.log` 39MB 在 .gitignore 漏网” | 已被 `*.log` 命中，`git check-ignore` 通过；未进入版本库。 |
| “`router.ts` 完全没有 `beforeEach`” | 存在 `beforeEach`，只是不做权限判断（保留为 C2）。 |
| “前端 `request.ts` line 80 `as T` 是 Critical 类型不安全” | 是 fallback 分支，`response.ok` 已经被检查；降级为 FYI（C5）。 |
| “`Executors.newCachedThreadPool` 完全无并发上限保护” | 应用层有 `activeRuns` 计数；威胁是“绕过路径”而非“立刻失控”，降级为 Important（B1）。 |

## Part 4 — 总体结论与优先级建议

## 2026-05-29 修复落地记录

本轮已修复：

- 专项 Finding 1/2/3/4/5：评审数据 Excel 导入的 confirm 默认值、preview token TTL/容量、负数校验、`.xlsx` 边界和上传体积控制已处理。
- 专项 Finding 7 + C1：所有前端 API-client 导出/下载路径已统一使用 `requestText` / `requestBlob`，保留超时、CSRF 和 JSON 错误解析。
- A1/A3：Spring Security 默认兜底从 `permitAll` 收紧到认证要求，`/api/auth/**`、静态资源和健康检查显式白名单；新增 session 到 Spring Security 的桥接过滤器和 CSRF cookie 触发过滤器。
- A2：本地认证支持 `{bcrypt}` 等 Spring Security password hash，同时保留现有明文配置兼容；明文比较改为常量时间比较。
- B3：新增全局 `@RestControllerAdvice`，统一处理 `BizException`、参数校验、上传边界、DB 异常和兜底异常。
- D1：`.gitlab-ci.yml` 中测试库密码改为引用 masked variable `$TEST_POSTGRES_PASSWORD`。
- D3：默认 `baseline-on-migrate` 改为 `${SPRING_FLYWAY_BASELINE_ON_MIGRATE:false}`。

本轮暂不处理：

- A4：登录/登出审计需要和现有审计字段口径一起设计，避免记录密码或错误敏感信息。
- B1/B2/B4：线程池有界化、外部数据源连接池全局化、统一 PreviewSessionStore 属于架构治理项，建议单独拆小 PR。
- C2/C3/C4：前端路由权限守卫、大视图拆分、route query 精准 watch 暂不和导入修复混在一轮。
- D2/D4/D5：CI 质量门禁、破坏性迁移灰度规则、SQL 行尾治理继续留在工程治理清单。

已验证：

```powershell
cd D:\projects\data_collection_platform\backend
..\tools\maven\apache-maven-3.9.9\bin\mvn.cmd -q "-Dtest=ReviewDataLegacyExcelParserTest,ReviewDataControllerTest,AuthControllerTest,IntegrationTestControllerTest,IntegrationTestExcelExportServiceTest,SystemTestIllegalRecordServiceTest" test

cd D:\projects\data_collection_platform\frontend
& 'C:\Program Files\nodejs\npm.cmd' run typecheck
& 'C:\Program Files\nodejs\npm.cmd' test -- request integration-test-analysis review-data StatisticBoardDetailDialog code-review issue statistic-board
```

## 2026-05-29 第二轮修复落地记录

本轮已修复：

- A4：登录成功、登录失败、登出已通过 `OperationAuditService` 显式记录审计；审计摘要只记录用户名，不记录密码。`PlatformAuditInterceptor` 写入异常信息前也会脱敏并截断，避免 token/password/secret 泄漏到审计表。
- B1：`SyncRunExecutorService` 默认 worker executor 从 `newCachedThreadPool()` 改为有界 `ThreadPoolExecutor`，线程数按 `maxSyncThreads` 固定，队列容量按 `maxSyncThreads * 4` 设置，超出后拒绝提交，避免绕过容量检查时无限建线程。
- C2：前端路由增加统一访问守卫，路由组件加载前即按 `feature-manifest` 的 `requiresLogin/hiddenForApproval` 判定可访问性，避免依赖 `App.vue` watch 兜底导致页面先加载再跳转。

本轮继续保留：

- B2/B4：外部数据源连接池全局化、统一 PreviewSessionStore 仍建议单独拆架构 PR。
- C3/C4：大型视图拆分、route query 精准 watch 仍建议随对应页面功能改动逐步治理。
- D2/D4/D5：CI 质量门禁、破坏性迁移灰度规则、SQL 行尾治理仍保留在工程治理清单。

已验证：

```powershell
cd D:\projects\data_collection_platform\backend
..\tools\maven\apache-maven-3.9.9\bin\mvn.cmd -q "-Dtest=ReviewDataLegacyExcelParserTest,ReviewDataControllerTest,AuthControllerTest,PlatformAuditInterceptorTest,SyncRunExecutorServiceTest,IntegrationTestControllerTest,IntegrationTestExcelExportServiceTest,SystemTestIllegalRecordServiceTest" test

cd D:\projects\data_collection_platform\frontend
& 'C:\Program Files\nodejs\npm.cmd' run typecheck
& 'C:\Program Files\nodejs\npm.cmd' test -- request router App integration-test-analysis review-data StatisticBoardDetailDialog code-review issue statistic-board
```

## 2026-05-29 第三轮修复落地记录

本轮已修复：

- B4：新增通用 `PreviewSessionStore<T>`，将评审数据 Excel 导入的 preview token 缓存从业务服务中抽离；统一 TTL、容量裁剪、过期清理和确认后移除语义，并补充 `PreviewSessionStoreTest` 覆盖有效期、过期和容量裁剪。
- D5：清理 `scripts/repair_demo_display_data.sql` 中残留的 NEL（U+0085）异常行分隔符；`scripts/check_text_whitespace.py` 增加对 NEL、bare CR、trailing whitespace 的检查。为避免把历史 CRLF 文件一次性纳入本轮范围，脚本只检查 CI diff、暂存/未暂存变更和未跟踪文本文件。
- D2：`.gitlab-ci.yml` 增加本轮相关的后端目标测试和前端目标 Vitest 回归入口，覆盖评审导入、集成测试导出、鉴权审计、同步执行池、路由/API 边界和统计下钻相关组件。

本轮继续保留：

- B2：外部数据源连接已补上 direct JDBC 池的生命周期回收；如果后续需要多服务共享/监控粒度更细的外部连接池，再单独做全局化治理。
- C3/C4：大型视图拆分和 route query 精准 watch 仍建议跟页面功能变更一起拆小处理。
- D4：已补“变更中的 destructive migration 必须带审查/恢复标记”的静态门禁；更细的发布白名单和审批流仍需结合团队流程落地。

已验证：

```powershell
cd D:\projects\data_collection_platform\backend
..\tools\maven\apache-maven-3.9.9\bin\mvn.cmd -q "-Dtest=PreviewSessionStoreTest,ReviewDataLegacyExcelParserTest,ReviewDataControllerTest,IntegrationTestControllerTest,IntegrationTestExcelExportServiceTest,AuthControllerTest,PlatformAuditInterceptorTest,SyncRunExecutorServiceTest,SystemTestIllegalRecordServiceTest" test

cd D:\projects\data_collection_platform
python scripts\check_text_whitespace.py
git diff --check

cd D:\projects\data_collection_platform\frontend
& 'C:\Program Files\nodejs\npm.cmd' run typecheck
& 'C:\Program Files\nodejs\npm.cmd' test -- request router App integration-test-analysis review-data StatisticBoardDetailDialog code-review issue statistic-board
```

## 2026-05-29 第四轮修复落地记录

本轮已修复：

- B2：`GitlabDirectJdbcExecutor` 补充 direct JDBC 池的生命周期治理，`GitlabExternalDbService` 作为 Spring bean 销毁时会统一关闭缓存的 `HikariDataSource`；同时增加 `GitlabDirectJdbcExecutorTest` 覆盖等价连接复用同一池、服务销毁时释放池资源。
- D4：新增 `scripts/check_flyway_destructive_migrations.py`，对本次变更中的 Flyway migration 做 destructive SQL 审查门禁；出现 `drop table`、`drop column`、`rename table/column` 时，必须显式写入 `-- destructive-migration-reviewed:` 和 `-- destructive-migration-recovery:` 标记。CI 和本地 `verify-local.ps1` 都已接入。
- D4：新增 `scripts/check_flyway_destructive_migrations_test.py`，为 destructive SQL 识别和审查标记规则补了脚本级回归。

本轮继续保留：

- C3/C4：大型视图拆分和 route query 精准 watch 仍建议跟页面功能变更一起拆小处理。
- D4：静态门禁已补，但真正的“先 rename 到 legacy、观察若干 release 再 drop”的发布流程，还需要在迁移规范文档和发布流程里再固化。

已验证：

```powershell
cd D:\projects\data_collection_platform
python scripts\check_flyway_destructive_migrations.py
python scripts\check_flyway_destructive_migrations_test.py
python scripts\check_text_whitespace.py
git diff --check

cd D:\projects\data_collection_platform\backend
..\tools\maven\apache-maven-3.9.9\bin\mvn.cmd -q "-Dtest=PreviewSessionStoreTest,ReviewDataLegacyExcelParserTest,ReviewDataControllerTest,IntegrationTestControllerTest,IntegrationTestExcelExportServiceTest,AuthControllerTest,PlatformAuditInterceptorTest,SyncRunExecutorServiceTest,SystemTestIllegalRecordServiceTest,GitlabDirectJdbcExecutorTest,GitlabExternalDbServiceTest,GitlabSourceConnectionSettingsTest,GitlabSourceQueryRetryPolicyTest" test

cd D:\projects\data_collection_platform\frontend
& 'C:\Program Files\nodejs\npm.cmd' run typecheck
& 'C:\Program Files\nodejs\npm.cmd' test -- request router App integration-test-analysis review-data StatisticBoardDetailDialog code-review issue statistic-board
```

按可观察影响 × 修复成本排序：

**1. 本次必须先动**（合并风险大、修复成本不高）

- 既有专项 Finding 1 / 2（preview 默认值不生效、token 无 TTL）。
- 既有专项 Finding 3（负数静默归零）。
- 新增 A1 / A2（`permitAll` 兜底、明文密码）；可与 A3（CSRF 默认开启）打包成一次“鉴权基线收紧”的小型修复。
- 新增 D1（CI 明文测试库密码迁 masked variable）。

**2. 下一轮迭代前完成**

- C1（导出 fetch 统一封装）、C2（路由权限守卫）。
- B1（线程池有界化）、B3（统一异常 advice）。
- 既有专项 Finding 4 / 5（`.xls` 兼容声明、上传体积控制）。
- D2 / D3 / D4（CI 质量门禁、Flyway baseline、迁移灰度）。

**3. 长期治理项**

- B2（外部数据源全局连接池）、B4（统一 PreviewSessionStore）。
- 既有专项 Finding 8 / 9（批量写入优化、统计下钻 e2e 回归）。
- C3（大型视图组件按面板拆分）、C4（composable watch 精确化）。

落地建议：把第 1 档拆成两条小 PR——“鉴权基线收紧”和“评审数据导入修复 + Excel 解析负数校验”，分别配套补回归测试，再做一轮真实页面验证。其余项进 backlog 按周清理。

---

附：本次审查不修改任何业务代码。如果需要把某条 finding 落到具体修改方案/补丁，请告诉我对应编号。
