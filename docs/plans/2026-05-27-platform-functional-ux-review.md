# 2026-05-27 平台功能与用户体验收口检查

## 检查口径

本轮不新增功能，重点检查最新本地版本是否存在明显功能缺陷、反用户直觉的交互、误导性状态、英文反馈残留，以及容易因为共享状态改动引入连带问题的区域。

检查原则：

- 用户态反馈必须优先使用中文；`System Hook`、`GitLab`、`CC_PRODUCT`、表名、字段名等技术名词可以保留英文。
- 用户态状态不能直接暴露 `QUEUED`、`PARTIAL_SUCCESS`、`Refresh requested`、“排队中”、“部分成功”等内部或误导性表达。
- 任何修复都必须回归相邻功能，尤其是同步日志实时性、同步策略、System Hook 唤醒、页面刷新状态之间的连带影响。
- 方便性优化应聚焦“减少阻塞和误解”，不额外堆小功能模块。

## 已执行验证

- 本地前端 `http://127.0.0.1:18181/` 返回 200。
- 本地后端 `http://127.0.0.1:18080/api/auth/current` 返回 200。
- 前端类型检查：`npm run typecheck` 通过。
- 前端全量测试：`npm run test` 通过，77 个测试文件、232 个用例通过。
- 后端全量测试尝试：使用项目自带 Maven/JDK 可启动测试，但当前默认测试库指向 `localhost:15432`，该端口没有 PostgreSQL 服务，导致依赖 Spring 上下文和数据库脚本初始化的测试失败。失败根因是 `Connection to localhost:15432 refused`，不是当前用户路径下的功能异常。

## 总体结论

当前本地运行版本没有发现会阻塞初步使用的明显功能 bug。同步状态、最近同步日志、System Hook 唤醒、全量补偿对账入口、搜索防抖、提示可关闭这些近期重点问题，在前端测试和静态扫描层面基本闭环。

仍建议在迁移或再次打包前处理下面几个“用户体验/验证体系”问题。它们多数不是核心功能缺失，但会影响内网试用时的直觉和信任感。

## 发现的问题

### P1：Element Plus 全局中文 locale 未配置

现象：

- `frontend/src/main.ts` 仅挂载了路由和 loading 指令，没有全局 `ElConfigProvider` 或 Element Plus 中文 locale。
- 因此分页、选择器、弹窗等 Element Plus 内置文案仍可能显示 `Total`、`10/page` 等英文。

影响：

- 这和“用户态反馈应为中文”的原则冲突。
- 用户截图中出现过 `Total 70`、`10/page`，该问题仍有复现基础。

建议：

- 在应用根节点增加 Element Plus 中文 locale，例如用 `el-config-provider` 包裹 `App` 内容，统一传入 `zhCn`。
- 增加一个轻量测试，至少覆盖分页组件渲染不出现 `Total`、`/page`。

### P1：后端全量测试依赖默认 15432，当前环境不可一键复跑

现象：

- `mvn -q -f backend/pom.xml test` 当前失败，根因为测试默认连接 `localhost:15432`，而本地实际可用平台库在 `15433`。
- 失败集中在需要 Spring 上下文和数据库初始化的测试，纯单元测试大量已经通过。

影响：

- 不影响当前 18181/18080 本地运行，但会影响“打包前确认没有小问题”的可信度。
- 后续如果换机器或迁移包复测，容易再次出现“功能能跑、测试不能跑”的断层。

建议：

- 固化测试数据库启动方式，或让测试 profile 明确读取 `DATASOURCE_URL`。
- 避免全量测试默认依赖已过期的 `15432` 端口。

### P2：规则说明仍有页面加载时预取，可能造成轻微卡顿感

现象：

- 多个页面仍在初始化加载中请求 `rule-explanation`。
- 这类请求通常很快，但页面切换频繁时会给用户“每次都在额外加载规则说明”的感受。

影响：

- 功能正确，但交互上有点反直觉：用户没有打开“规则说明”，却为它付出加载成本。

建议：

- 默认改为打开抽屉时再加载。
- 如果希望保持首开速度，可在页面主数据加载完成后使用 idle/延迟预取，不阻塞页面主体。

### P2：少量异常兜底仍可能暴露英文

现象：

- `frontend/src/api-client/request.ts` 在非标准响应或空错误体时会兜底为 `Request failed: ${status}`。
- 导出 API 也存在 `Export failed: ${status}` 兜底。
- 这类文案平时不常出现，但网络异常、反向代理错误、接口非 JSON 返回时会直接进入 `ElMessage.error`。

影响：

- 用户态错误反馈可能出现英文，且不够可操作。

建议：

- 将通用请求兜底改为中文，例如“请求失败，状态码：xxx”。
- 导出失败兜底改为“导出失败，状态码：xxx”。
- 保留开发日志中的原始错误，用户提示只展示中文摘要。

### P2：统计看板刷新状态的“部分失败/事实失败”仍偏绝对

现象：

- `StatisticBoardToolbar` 对刷新失败状态会显示“部分失败”“事实失败”。
- 这虽然是中文，但在“已经展示当前可用数据，只是后台刷新未完成”的场景下仍偏武断。

影响：

- 用户可能把“刷新未完成/待后台补齐”理解成当前页面功能失败。

建议：

- 改成更贴近真实语义的短状态，例如“刷新未完成”“事实数据待更新”“已展示当前可用数据”。
- 错误详情可以放在悬浮提示或诊断区域，不在主状态栏直接制造失败感。

### P3：统计下钻议题链接基本闭环，但需要保留回归保护

现状：

- 前端 `StatisticBoardDetailDialog` 已能把 `{ label, href }` 渲染为外链。
- 具体统计服务大多已经调用 `StatisticIssueLinkSupport.putIssueFields` 输出 `iid` 链接结构。
- `GitlabIssueLinkService` 已支持多源镜像项目表路径解析。

残余风险：

- 如果某个后续统计服务直接复用旧的纯 `iid` 输出方式，议题编号会退回纯文本。
- 当前 `DefectSummaryBoardSupport.toDetailRecord` 仍保留纯 `iid` 输出，虽然扫描未发现活跃调用，但未来复用时可能带回问题。

建议：

- 保留或补充“统计下钻 iid 必须是可链接结构或有 issueUrl fallback”的契约测试。
- 未使用的旧辅助方法后续可选择删除或改为显式接收 `StatisticIssueLinkSupport`。

## 功能分组检查摘要

| 模块 | 当前判断 | 需要关注 |
| --- | --- | --- |
| 质量看板 | 未发现阻塞问题 | 依赖 Element Plus locale 修复内置英文 |
| 评审数据 | 搜索防抖和基础 CRUD 测试通过 | 规则说明可继续优化为懒加载 |
| 代码走查 | 非法数据、规则配置、导出测试通过 | 导出异常兜底需中文化 |
| 集成测试 | 页面挂载、明细、导出测试通过 | 暂未发现新的用户态问题 |
| 系统测试 | 议题查询、非法数据、统计看板测试通过 | 统计刷新状态文案可继续收敛 |
| 客户问题 | 列表与统计路径测试通过 | 下钻链接继续保留回归保护 |
| 数据同步 | 状态词已基本用户态化 | 后端全量测试环境需修复；全量补偿大数据压力仍属内网专项 |
| 数据库查看 | 功能可用 | Element Plus 分页英文问题会直接体现在这里 |
| 测试阶段定义 | 基础维护测试覆盖 | 暂未发现新的用户态问题 |

## 建议下一步

1. 先修复 Element Plus 全局中文 locale，这是最容易被内网用户第一眼看到的问题。
2. 再统一请求/导出错误兜底文案，避免异常路径暴露英文。
3. 将规则说明请求改为懒加载或空闲预取，减少页面切换时的感知负担。
4. 将“部分失败/事实失败”调整为更准确的“刷新未完成/待更新”类状态。
5. 修复后端测试默认数据库端口问题，让打包前 `mvn test` 能稳定复跑。
## 2026-05-27 修复方案与落地结果

### 方案原则
- 用户态反馈只展示中文；`System Hook`、`GitLab`、表名、字段名等技术名词保留原文。
- 页面主状态不再使用“部分失败”“事实失败”这类容易让用户误判页面不可用的文案；后台刷新未完成时表达为“已展示当前可用数据”“待更新”。
- 规则说明不再跟随页面首屏加载；用户点击说明入口时再请求，减少页面切换的轻微卡顿感。
- 测试环境默认配置对齐本地直连测试容器：`localhost:15433/qaflex`，并继续使用 `qaflex_test` / `qaflex_test_flyway` 独立 schema，避免污染平台展示数据。

### 已完成修复
- `frontend/src/App.vue`：根组件增加 `el-config-provider`，统一传入 Element Plus `zhCn` locale，解决分页、选择器等内置文案可能显示英文的问题。
- `frontend/src/api-client/request.ts`：通用请求失败兜底从 `Request failed` 改为中文“请求失败，状态码：xxx / 请求失败”。
- `frontend/src/api-client/statistic-boards-api.ts`、`code-review-api.ts`、`integration-tests-api.ts`：导出失败兜底从 `Export failed` 改为中文“导出失败，状态码：xxx”。
- `frontend/src/views/CodeReviewIllegalRecordsView.vue`：移除首屏 `rule-explanation` 请求，规则说明改为点击后加载。
- `frontend/src/components/StatisticBoardToolbar.vue`：统计看板刷新异常态主文案改为“已展示当前可用数据”，分阶段状态改为“镜像待更新/事实待更新”，标签由危险态改为提醒态。
- `backend/src/test/resources/application.yml`、`application-flyway-test.yml`：测试默认数据源改为本地 15433，并同步默认密码到当前测试容器；CI 仍可通过 `TEST_DATASOURCE_*` 覆盖。

### 新增/更新回归测试
- `frontend/src/App.test.ts`：保护根组件必须挂载 Element Plus 中文 locale。
- `frontend/src/api-client/request.test.ts`：覆盖空错误体和业务 envelope 无 message 时的中文兜底。
- `frontend/src/api-client/export-error-messages.test.ts`：覆盖统计看板、代码走查、集成测试导出异常的中文兜底。
- `frontend/src/views/code-review-illegal-records.mount-smoke.test.ts`：验证 Code Review 非法记录页首屏不再请求规则说明。
- `frontend/src/components/StatisticBoardToolbar.test.ts`：验证刷新异常态不再出现“失败”类绝对文案。

### 验证结果
- 前端定向测试通过：5 个测试文件，19 个用例通过。
- 前端类型检查通过：`npm run typecheck`。
- 前端全量测试通过：78 个测试文件，238 个用例通过。
- 前端生产构建通过：`npm run build`。
- 后端全量测试通过：415 个用例，0 失败，4 个真实外部链路集成测试按设计跳过。
- 本地 HTTP 冒烟通过：
  - `http://127.0.0.1:18181/` 返回 200。
  - `http://127.0.0.1:18080/api/auth/current` 返回 200。
  - `http://127.0.0.1:18080/api/statistic-boards/system-test-defect-summary/status` 返回 200。
  - `http://127.0.0.1:18080/api/code-review/illegal-records/status` 返回 200。
- Vite 实时源码确认 `App.vue` 已注入 `ElConfigProvider` 和 `zhCn`，本地 18181 运行中的前端已加载新代码。

### 剩余注意点
- 真实内网大数据量压力、真实 CC/DGM 多源隔离仍属于内网专项验证，不在本次本地修复闭环内。
- 这次只修复 Code Review 非法记录页的首屏规则说明预取；其它统计页使用通用 `StatisticBoardView` 时已经是点击打开说明，后续如果新增页面要遵循同一懒加载规则。
