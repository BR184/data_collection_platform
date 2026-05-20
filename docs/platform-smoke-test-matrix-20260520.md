# 全平台功能标记与分组冒烟测试矩阵

更新时间：2026-05-20

## 目标

在迁移到内网并执行大数据量同步前，先确认平台所有功能入口、后端服务、前端组件和关键抽象类具备基础功能测试与真实链路验证路径。后续如果在大量数据同步后出现异常，优先从数据量、索引、SQL、连接池、任务排队、GitLab 压力方向定位，而不是反复怀疑基础功能是否本来就不通。

本文档只做功能标记、分组和测试状态记录；数据量压力测试单独后置，不混入本轮功能冒烟。

## 状态标记

| 标记 | 含义 |
| --- | --- |
| ✅ 自动化已验证 | 已有单元、集成或前端组件测试，并在 2026-05-20 本轮测试中通过 |
| ✅ 真实链路已验证 | 已通过 HTTP、页面、真实 GitLab、本地 GitLab 或真实数据库链路验证 |
| ⚠️ 待真实链路 | 有自动化覆盖，但还需要按真实链路跑一遍 |
| ❌ 待补覆盖 | 当前未找到足够的自动化或真实链路覆盖 |
| ⏸ 后置专项 | 不属于基础冒烟，放到大数据量或内网专项中验证 |

## 冒烟分组

### G0 平台基础、构建与安全壳

| 功能点 | 覆盖范围 | 当前状态 | 后续动作 |
| --- | --- | --- | --- |
| 后端整体构建与测试 | Maven 全量测试、Flyway 迁移冒烟、旧同步模型架构约束 | ✅ 自动化已验证 | 后续每轮大改后重跑 |
| 前端类型与基础测试 | typecheck、路由、manifest、请求封装、Vite 构建配置 | ✅ 自动化已验证 | 后续补一次真实浏览器页面巡检 |
| 登录、当前用户、退出 | `/api/auth/current`、`/api/auth/login`、`/api/auth/logout` | ✅ 自动化已验证，⚠️ 待真实链路 | 用浏览器登录/退出并确认角色菜单 |
| 权限与审计 | Endpoint 授权契约、授权拦截器、审计拦截器、请求缓存过滤器 | ✅ 自动化已验证，⚠️ 待真实链路 | 用不同角色访问受限页面 |
| 全局异常与启动安全 | `GlobalExceptionHandler`、`PlatformStartupSecurityGuard` | ✅ 自动化已验证 | 保持自动化覆盖 |
| 路由与菜单壳 | 首页重定向、页面 query 白名单、404、审批角色隐藏菜单 | ✅ 自动化已验证，⚠️ 待真实链路 | 浏览器逐页打开 |

### G1 GitLab 数据源配置与镜像同步

| 功能点 | 覆盖范围 | 当前状态 | 后续动作 |
| --- | --- | --- | --- |
| 数据源配置读写 | `/api/gitlab-sync/configs`、`PUT /api/gitlab-sync/config` | ✅ 自动化已验证，⚠️ 待真实链路 | 页面保存配置并刷新回显 |
| 连接测试 | `/test-connection`、`/test-connection/by-config`、直连模式 | ✅ 自动化已验证，✅ 真实链路已验证 | 内网再对 CC/DGM 对应源直连验证 |
| 白名单选项 | 推荐表、全部表、自定义表、项目白名单字段 | ✅ 自动化已验证，⚠️ 待真实链路 | 页面切换各模式并确认请求体 |
| 全量同步 | `/full-sync`、`/full-sync/by-config`、表计划、worker、日志 | ✅ 自动化已验证，⚠️ 待真实链路 | 小表集真实同步冒烟 |
| 增量同步 | `/incremental-sync`、同步窗口、排他策略 | ✅ 自动化已验证，⚠️ 待真实链路 | 与补偿扫描互斥验证 |
| 补偿扫描 | scheduler、retry failed、排他策略 | ✅ 自动化已验证，⚠️ 待真实链路 | 确认同一时间只存在一个同步执行 |
| 取消同步 | `/cancel`、`/cancel/by-config`、run/table task 状态机 | ✅ 自动化已验证，⚠️ 待真实链路 | 启动长任务后取消 |
| 清理镜像 | `/purge`、镜像表清理 | ✅ 自动化已验证，⚠️ 待真实链路 | 测试前确认不影响用户数据 |
| 同步状态卡片 | 当前任务、计划批次、处理批次完成数、worker 状态 | ✅ 自动化已验证，⚠️ 待真实链路 | 页面观察不同同步状态 |
| 同步日志 | run events、table tasks、System Hook 唤醒日志 | ✅ 自动化已验证，✅ 真实链路已验证 | 数据库查看入口仍需补齐，见 NEW-002 |
| 同步并发抽象 | dispatcher、lease、state machine、policy、worker lease、thread budget | ✅ 自动化已验证 | 保持服务级覆盖 |

### G2 System Hook 真实链路

| 功能点 | 覆盖范围 | 当前状态 | 后续动作 |
| --- | --- | --- | --- |
| System Hook 注册状态 | `/system-hook-registration-status` | ✅ 自动化已验证，⚠️ 待真实链路 | 页面查看状态 |
| System Hook 注册 | `/register-system-hook`、`/register-system-hook/by-config` | ✅ 自动化已验证，⚠️ 待真实链路 | 本地 GitLab 管理员真实注册 |
| Hook 接收 | `POST /api/gitlab-sync/system-hook`、secret 校验、事件落库 | ✅ 自动化已验证，✅ 真实链路已验证 | 保持本地 GitLab 真实链路脚本 |
| 精准同步计划 | event -> precise plan -> table target | ✅ 自动化已验证，✅ 真实链路已验证 | 覆盖更多 event 类型 |
| 异步派发 | hook 唤醒同步 run，生成 table tasks | ✅ 自动化已验证，✅ 真实链路已验证 | 继续验证日志可追踪 |
| 项目内存白名单 | 只监听指定项目，非白名单项目快速丢弃 | ❌ 待补覆盖 | 已记录 NEW-001，方案见 ADR-001 |
| System Hook 日志可观测 | 同步日志包含 System Hook 信息 | ✅ 自动化已验证，✅ 真实链路已验证 | 数据库查看入口补齐后再验 |

说明：System Hook 单次事件 payload 是事件级别，不会把 10000 个项目整体打包发送。平台侧仍需要项目白名单做入口阻断，避免高频无关事件进入同步规划。

### G3 多源隔离与内网 CC/DGM 验证

| 功能点 | 覆盖范围 | 当前状态 | 后续动作 |
| --- | --- | --- | --- |
| 多源标识规范化 | `GitlabSourceInstanceSupport`、默认源、命名冲突 | ✅ 自动化已验证 | 保持单测 |
| 镜像表多源隔离 | 不同 source instance 使用隔离表名/表上下文 | ✅ 自动化已验证 | 小数据真实双源验证 |
| 事实表来源字段 | `source_instance` 写入、查询过滤、事实构建链路 | ✅ 自动化已验证 | 内网 CC/DGM 真实链路验证 |
| 查询下推隔离 | 按 source instance 过滤，避免跨源混入 | ✅ 自动化已验证 | 真实数据库双源抽样核对 |
| 代码走查多源看板 | source options、overview 查询 | ✅ 自动化已验证，⚠️ 待真实链路 | 页面切换不同源 |
| 外网无 CC/DGM 的限制 | 外网只验证多源能力，不验证 CC/DGM 名称本身 | ⏸ 后置专项 | 内网环境验证真实 CC/DGM |

说明：这里不再使用“DGM 多源”这种说法。平台要证明的是多源能力正常、不同源数据不互相污染；CC 和 DGM 是内网具体源名。

### G4 数据库查看

| 功能点 | 覆盖范围 | 当前状态 | 后续动作 |
| --- | --- | --- | --- |
| 表列表 | `/api/database-browser/tables`、本地表、事实表、镜像表目录 | ✅ 自动化已验证，⚠️ 待真实链路 | 页面打开并核对分类 |
| 行数据查询 | `/rows`、分页、排序、关键字 | ✅ 自动化已验证，⚠️ 待真实链路 | 页面查询多张表 |
| 表刷新 | `/refresh`、镜像表单表刷新 | ✅ 自动化已验证，⚠️ 待真实链路 | 选一张小表刷新 |
| 表定义抽象 | table catalog、definition factory、row mapper、SQL bundle | ✅ 自动化已验证 | 保持服务级覆盖 |
| 同步日志全量查看 | `sync_runs`、`sync_run_events`、`sync_run_table_tasks`、`gitlab_hook_events` | ❌ 待补覆盖 | 已记录 NEW-002 |

### G5 事实构建与事实刷新

| 功能点 | 覆盖范围 | 当前状态 | 后续动作 |
| --- | --- | --- | --- |
| 事实重建入口 | `POST /api/facts/rebuild`、`POST /api/integration-tests/rebuild` | ✅ 自动化已验证，⚠️ 待真实链路 | 小数据触发重建 |
| 构建任务查询 | `/api/facts/build-tasks/latest` | ✅ 自动化已验证，⚠️ 待真实链路 | 页面或接口查询最近任务 |
| Issue 诊断 | `/issue-diagnostics`、`/issue-source-readiness` | ✅ 自动化已验证，⚠️ 待真实链路 | 真实源同步后检查 |
| 构建操作保护 | `FactBuildOperationGuard`、并发保护 | ✅ 自动化已验证 | 与同步互斥关系继续观察 |
| 任务 worker | `FactBuildTaskService`、`FactRefreshTaskWorkerService` | ✅ 自动化已验证 | 保持服务级覆盖 |
| Issue 事实规则 | 分类、标签、函数、SLA、模板解析、归一化 | ✅ 自动化已验证 | 抽样对照真实 issue |
| 集成测试事实规则 | note parser、fact pipeline、统计规则 | ✅ 自动化已验证，⚠️ 待真实链路 | 真实数据抽样对照 |

### G6 评审数据

| 功能点 | 覆盖范围 | 当前状态 | 后续动作 |
| --- | --- | --- | --- |
| 评审记录列表 | `/api/review-data/records`、分页、排序、搜索、筛选组 | ✅ 自动化已验证，⚠️ 待真实链路 | 页面真实打开并筛选 |
| 评审记录详情 | `/records/{recordId}`、详情抽屉 | ✅ 自动化已验证，⚠️ 待真实链路 | 页面打开详情 |
| 记录新增、编辑、删除 | `POST/PUT/DELETE /records`、表单 dialog | ✅ 自动化已验证，⚠️ 待真实链路 | 使用测试数据闭环 |
| 问题项管理 | `/problem-items` 增删改查、问题面板 | ✅ 自动化已验证，⚠️ 待真实链路 | 使用测试记录闭环 |
| GitLab 上下文刷新 | `/records/gitlab-context/refresh`、job 查询 | ✅ 自动化已验证，⚠️ 待真实链路 | 真实 GitLab issue/MR 关联验证 |
| 搜索索引回填 | `/records/search-index/backfill`、search support | ✅ 自动化已验证，⚠️ 待真实链路 | 小批量触发回填 |
| 前端组合函数 | records/detail/export/route/actions/dialog/problem items | ✅ 自动化已验证 | 保持组件级覆盖 |

### G7 代码走查

| 功能点 | 覆盖范围 | 当前状态 | 后续动作 |
| --- | --- | --- | --- |
| 非法记录列表 | `/api/code-review/illegal-records`、分页、筛选、排序 | ✅ 自动化已验证，⚠️ 待真实链路 | 页面真实查询 |
| 非法记录导出 | `/illegal-records/export` | ✅ 自动化已验证，⚠️ 待真实链路 | 下载 CSV |
| 筛选选项 | `/illegal-records/filter-options` | ✅ 自动化已验证，⚠️ 待真实链路 | 页面筛选项加载 |
| 规则说明 | `/illegal-records/rule-explanation`、drawer | ✅ 自动化已验证，⚠️ 待真实链路 | 页面打开规则说明 |
| 规则配置与预览 | `/rule-config/preview`、local storage、editor | ✅ 自动化已验证，⚠️ 待真实链路 | 页面修改规则并预览 |
| 状态与刷新 | `/illegal-records/status`、`/illegal-records/refresh` | ✅ 自动化已验证，⚠️ 待真实链路 | 触发刷新并观察 |
| 多元看板 | `/multi-board/source-options`、`/multi-board/overview` | ✅ 自动化已验证，⚠️ 待真实链路 | 页面切源、图表渲染 |

### G8 客户问题

| 功能点 | 覆盖范围 | 当前状态 | 后续动作 |
| --- | --- | --- | --- |
| 客户问题记录 | `/api/customer-issues/records`、CC_PRODUCT、延期问题 | ✅ 自动化已验证，⚠️ 待真实链路 | 页面真实查询 |
| 记录导出 | `/records/export` | ✅ 自动化已验证，⚠️ 待真实链路 | 下载 CSV |
| 记录筛选选项 | `/records/filter-options` | ✅ 自动化已验证，⚠️ 待真实链路 | 页面筛选项加载 |
| 记录规则说明 | `/records/rule-explanation` | ✅ 自动化已验证，⚠️ 待真实链路 | 页面打开规则说明 |
| 非法记录列表 | `/illegal-records` | ✅ 自动化已验证，⚠️ 待真实链路 | 页面真实查询 |
| 非法记录导出与筛选 | `/illegal-records/export`、`/filter-options` | ✅ 自动化已验证，⚠️ 待真实链路 | 下载并筛选 |
| 非法记录规则说明 | `/illegal-records/rule-explanation` | ✅ 自动化已验证，⚠️ 待真实链路 | 页面打开规则说明 |

### G9 系统测试与集成测试

| 功能点 | 覆盖范围 | 当前状态 | 后续动作 |
| --- | --- | --- | --- |
| 系统测试 issue 查询 | `/api/question-metrics/issues` | ✅ 自动化已验证，⚠️ 待真实链路 | 页面真实查询 |
| issue 导出与筛选 | `/issues/export`、`/issues/filter-options` | ✅ 自动化已验证，⚠️ 待真实链路 | 下载 CSV |
| 系统测试非法记录 | `/illegal-records`、非法原因、筛选 | ✅ 自动化已验证，⚠️ 待真实链路 | 页面真实查询 |
| 非法记录导出与说明 | `/illegal-records/export`、`/rule-explanation` | ✅ 自动化已验证，⚠️ 待真实链路 | 下载并打开说明 |
| 系统测试多元看板 | `/question-metrics/multi-board` 前端图表 | ✅ 自动化已验证，⚠️ 待真实链路 | 页面图表巡检 |
| 集成测试项目/阶段选项 | `/api/integration-tests/project-options`、`/phase-options` | ✅ 自动化已验证，⚠️ 待真实链路 | 页面选择项目和阶段 |
| 集成测试汇总与明细 | `/summary`、`/details` | ✅ 自动化已验证，⚠️ 待真实链路 | 页面下钻明细 |
| 集成测试导出 | `/details/export` | ✅ 自动化已验证，⚠️ 待真实链路 | 下载 CSV |

### G10 统计看板与质量看板

| 功能点 | 覆盖范围 | 当前状态 | 后续动作 |
| --- | --- | --- | --- |
| 通用统计看板接口 | `/api/statistic-boards/{boardKey}` | ✅ 自动化已验证，⚠️ 待真实链路 | 逐个 boardKey 打开 |
| 明细下钻 | `/{boardKey}/details`、分页排序 | ✅ 自动化已验证，⚠️ 待真实链路 | 页面下钻 |
| 规则说明 | `/{boardKey}/rule-explanation` | ✅ 自动化已验证，⚠️ 待真实链路 | 页面打开说明 |
| 导出 | `/{boardKey}/export` | ✅ 自动化已验证，⚠️ 待真实链路 | 下载 CSV |
| 状态与刷新 | `/{boardKey}/status`、`/{boardKey}/refresh` | ✅ 自动化已验证，⚠️ 待真实链路 | 刷新并观察 |
| 系统测试统计看板 | 缺陷汇总、延期分析、缺陷原因、阶段统计 | ✅ 自动化已验证，⚠️ 待真实链路 | 四个页面逐一巡检 |
| 客户问题统计看板 | 缺陷汇总、原因分析、响应效率、按功能统计 | ✅ 自动化已验证，⚠️ 待真实链路 | 四个页面逐一巡检 |
| 质量看板 | 研发质量看板、其他看板、图表 panel | ✅ 自动化已验证，⚠️ 待真实链路 | 图表渲染和跳转巡检 |
| 看板抽象 | registry、CSV、rule flow、filter group、source value、metric calculator | ✅ 自动化已验证 | 保持服务级覆盖 |
| 前端看板组件 | toolbar、detail dialog、rule drawer、filter builder、列拖拽、排序、偏好 | ✅ 自动化已验证 | 保持组件级覆盖 |

### G11 采集表单与测试阶段定义

| 功能点 | 覆盖范围 | 当前状态 | 后续动作 |
| --- | --- | --- | --- |
| 外部代码走查表单 | `/external/code-review-form`、`CollectFormView` | ✅ 自动化已验证，⚠️ 待真实链路 | 浏览器带参数打开 |
| 表单详情 | `/api/collect-forms/detail` | ✅ 自动化已验证，⚠️ 待真实链路 | 页面加载详情 |
| 通知 payload | `/notification-payload` | ✅ 自动化已验证，⚠️ 待真实链路 | 接口真实调用 |
| 表单保存、删除、更新记录 | `/save`、`/delete`、`/update-record` | ✅ 自动化已验证，⚠️ 待真实链路 | 用测试 MR 闭环 |
| 表单审计 | `CollectFormAuditService` | ✅ 自动化已验证 | 真实链路确认审计记录 |
| 测试阶段定义列表 | `GET /api/testing-phases`、页面列表 | ❌ 待补覆盖 | 需要补后端/前端测试并真实打开 |
| 项目选项 | `/api/testing-phases/project-options` | ❌ 待补覆盖 | 需要补接口测试 |
| 测试阶段新增、编辑、启停、删除 | `POST/PUT/PATCH/DELETE /api/testing-phases` | ❌ 待补覆盖 | 用测试项目闭环 |

### G12 前端基础组件、组合函数与工具

| 功能点 | 覆盖范围 | 当前状态 | 后续动作 |
| --- | --- | --- | --- |
| 基础记录表 | `BaseRecordTable`、cell、filter fields、smart select、search input | ✅ 自动化已验证 | 真实页面联动验证 |
| 页面状态壳 | `PageStateShell`、loading/error/empty | ⚠️ 待真实链路 | 在慢接口和空数据页面观察 |
| 统计表组件 | `StatisticBoardView`、`BaseStatisticTable`、column group | ✅ 自动化已验证 | 看板真实页面巡检 |
| 图表组件 | `EChartPanel`、chart options/theme/runtime | ✅ 自动化已验证，⚠️ 待真实链路 | 浏览器确认图表非空 |
| 数据范围组件 | `DataScopeBar`、`DataScopeCompareDialog`、providers | ⚠️ 待真实链路 | 多源页面巡检 |
| 实时状态组件 | `SyncMetaBadge`、`useRealtimeWorkspaceStatus` | ✅ 自动化已验证，⚠️ 待真实链路 | 触发同步后观察 |
| 规则组件 | `RuleExplanationDrawer`、`CodeReviewRuleConfigEditor/Preview` | ✅ 自动化已验证，⚠️ 待真实链路 | 页面打开并预览 |
| API 请求封装 | timeout、abort、cookie、错误处理 | ✅ 自动化已验证 | 保持单测 |
| CSV 下载工具 | review/statistic/csv-download | ✅ 自动化已验证，⚠️ 待真实链路 | 浏览器下载 |
| 北京时间工具、表单聚焦、issue 链接工具 | 时间展示、交互辅助、链接生成 | ✅ 自动化已验证或页面间接覆盖 | 页面抽样观察 |

### G13 后端共享抽象与基础服务

| 功能点 | 覆盖范围 | 当前状态 | 后续动作 |
| --- | --- | --- | --- |
| 分页、排序、文本查询 | `PageSliceSupport`、`SortSupport`、`TextQuerySupport` | ✅ 自动化已验证 | 保持单测 |
| CSV 导出 | `CsvExportSupport`、`StatisticBoardCsvSupport` | ✅ 自动化已验证，⚠️ 待真实链路 | 各域下载抽样 |
| SQL 查询监控与 predicate | `SqlQueryMonitor`、`SqlPredicate` | ⚠️ 待真实链路 | 慢查询/异常查询专项观察 |
| 源连接与元数据 | `SourceConnectionTester`、`SourceMetadataInspector` | ✅ 自动化已验证，⚠️ 待真实链路 | 真实源连接抽样 |
| 主键签名与镜像 mapper | `PrimaryKeySignatureSupport`、`GitlabMirrorRecordMapper` | ✅ 自动化已验证 | 保持单测 |
| 模块字典与选项工厂 | `ModuleDictionaryService`、`OptionItemResponseFactory` | ⚠️ 待真实链路 | 选项接口抽样 |
| 实时工作区 | `RealtimeWorkspaceService` | ✅ 自动化已验证，⚠️ 待真实链路 | 页面观察同步元信息 |
| 操作审计 | `OperationAuditService` | ⚠️ 待真实链路 | 触发变更操作后查审计 |

## 执行波次

| 波次 | 范围 | 判定标准 |
| --- | --- | --- |
| W0 | 后端全量测试、前端 typecheck、前端全量测试 | 构建和自动化测试全部通过 |
| W1 | 登录、安全、菜单、路由、基础页面壳 | 浏览器逐页可打开，权限符合预期 |
| W2 | GitLab 直连、镜像同步、同步状态、同步日志 | 小表集同步成功，日志可追踪 |
| W3 | System Hook 本地 GitLab 真实链路 | GitLab WebHookLog 200，平台 run/task/log 可追踪 |
| W4 | 多源隔离、事实构建、数据库查看 | 不同源数据隔离，事实表不串源 |
| W5 | 评审数据、代码走查、客户问题、系统测试、集成测试 | 每个列表、筛选、导出、规则说明、下钻至少跑一遍 |
| W6 | 统计看板、质量看板、采集表单、测试阶段定义 | 页面、接口、导出、刷新、增删改闭环 |
| W7 | 内网 CC/DGM 真实双源验证 | CC/DGM 均可直连，数据互不污染 |
| W8 | 大数据量压力专项 | 大量同步后再跑 W1-W6 抽样回归 |

## 已知缺口与新问题

| 编号 | 问题 | 影响 |
| --- | --- | --- |
| NEW-001 | System Hook 项目白名单字段已存在，但入口尚未按内存白名单快速阻断 | 非目标项目高频事件仍可能进入平台处理链路 |
| NEW-002 | 数据库查看暂未暴露 `sync_runs`、`sync_run_events`、`sync_run_table_tasks`、`gitlab_hook_events` | 用户无法在页面查看完整同步日志和 hook 事件 |
| NEW-003 | 历史数据库行中仍可能存在旧英文原始消息 | 历史日志展示可能中英混杂 |
| GAP-001 | 测试阶段定义模块未找到足够自动化覆盖 | CRUD 和页面真实链路需要优先补测 |
| GAP-002 | 部分前端组件只有页面间接覆盖，缺少独立组件测试 | 可先通过真实页面冒烟覆盖，后续补单测 |
| GAP-003 | 外网环境无法验证内网 CC/DGM 真实双源 | 内网迁移后必须作为首轮真实链路验证项 |

## 下一步记录方式

每完成一个分组，将状态从“待真实链路”更新为“真实链路已验证”，并记录：

1. 测试时间。
2. 测试环境。
3. 使用的数据源或项目。
4. 涉及页面或接口。
5. 是否产生新问题。
6. 相关 run id、hook event id、日志表记录或截图位置。

只有 W1-W6 基础功能冒烟稳定后，再启动 W8 大数据量压力专项。
