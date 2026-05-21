# 全平台功能标记与分组冒烟测试矩阵

更新时间：2026-05-20

## 目标

在迁移到内网并执行大数据量同步前，先确认平台所有功能入口、后端服务、前端组件和关键抽象类具备基础功能测试与真实链路验证路径。后续如果在大量数据同步后出现异常，优先从数据量、索引、SQL、连接池、任务排队、GitLab 压力方向定位，而不是反复怀疑基础功能是否本来就不通。

本文档只做功能标记、分组和测试状态记录；数据量压力测试单独后置，不混入本轮功能冒烟。

## 测试口径

1. 内网部署使用 **GitLab PostgreSQL 直连模式**，不使用 Docker 方式连接源库。
2. 本地 Docker 容器环境只能证明代码走通 `sourceMode=DIRECT` 分支，不能替代内网真实直连验证。
3. 若后端运行在 Docker 容器内，`dbHost=qaflex-gitlab-cc-pg-proxy` 这类容器 DNS/代理地址记为“本地容器直连模式”，不得标记为“内网真实直连已验证”。
4. 内网真实直连的通过标准是：平台后端直接连接内网 CC/DGM 两个 GitLab PostgreSQL 地址，配置中 `sourceMode=DIRECT`，不依赖 Docker network、socat proxy 或 GitLab 容器名。
5. 当前代码/页面冒烟优先使用本工作区源码或确认过的当前构建版本；`18181` 是本机 Vite dev server，`18182` 是 Docker 静态构建前端，两者测试结果需分开记录。
6. 自 2026-05-20 14:29 起，功能真实链路测试的有效口径调整为 `18181` 当前 Vite 源码前端 -> `18080` 当前源码后端。此前打到 `18083/18182` 的结果只作为旧容器环境健康参考，不作为最新版本功能通过依据。

## 状态标记

| 标记 | 含义 |
| --- | --- |
| ✅ 自动化已验证 | 已有单元、集成或前端组件测试，并在 2026-05-20 本轮测试中通过 |
| ✅ 真实链路已验证 | 已通过 HTTP、页面、真实 GitLab、本地 GitLab 或真实数据库链路验证；涉及源库时需注明是本地容器直连还是内网真实直连 |
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
| 连接测试 | `/test-connection`、`/test-connection/by-config`、直连模式 | ✅ 自动化已验证，⚠️ 本地容器直连已验证 | 内网再对 CC/DGM 对应源做真实直连验证 |
| 白名单选项 | 推荐表、全部表、自定义表 | ✅ 自动化已验证，⚠️ 待真实链路 | 页面切换各模式并确认请求体 |
| 全量同步 | `/full-sync`、`/full-sync/by-config`、表计划、worker、日志 | ✅ 自动化已验证，⚠️ 待真实链路 | 小表集真实同步冒烟 |
| 增量同步 | `/incremental-sync`、同步窗口、排他策略 | ✅ 自动化已验证，⚠️ 待真实链路 | 与补偿扫描互斥验证 |
| 补偿扫描 | scheduler、retry failed、排他策略 | ✅ 自动化已验证，⚠️ 待真实链路 | 确认同一时间只存在一个同步执行 |
| 取消同步 | `/cancel`、`/cancel/by-config`、run/table task 状态机 | ✅ 自动化已验证，⚠️ 待真实链路 | 启动长任务后取消 |
| 清理镜像 | `/purge`、镜像表清理 | ✅ 自动化已验证，⚠️ 待真实链路 | 测试前确认不影响用户数据 |
| 同步状态卡片 | 当前任务、计划批次、处理批次完成数、worker 状态 | ✅ 自动化已验证，⚠️ 待真实链路 | 页面观察不同同步状态 |
| 同步日志 | run events、table tasks、System Hook 唤醒日志 | ✅ 自动化已验证，✅ 真实链路已验证 | 数据库查看已补齐 `sync_runs`、`sync_run_events`、`sync_run_table_tasks` |
| 同步并发抽象 | dispatcher、lease、state machine、policy、worker lease、thread budget | ✅ 自动化已验证 | 保持服务级覆盖 |

### G2 System Hook 真实链路

| 功能点 | 覆盖范围 | 当前状态 | 后续动作 |
| --- | --- | --- | --- |
| System Hook 注册状态 | `/system-hook-registration-status` | ✅ 自动化已验证，⚠️ 待真实链路 | 页面查看状态 |
| System Hook 注册 | `/register-system-hook`、`/register-system-hook/by-config` | ✅ 自动化已验证，⚠️ 待真实链路 | 本地 GitLab 管理员真实注册 |
| Hook 接收 | `POST /api/gitlab-sync/system-hook`、secret 校验、事件落库 | ✅ 自动化已验证，✅ 真实链路已验证 | 保持本地 GitLab 真实链路脚本 |
| 精准同步计划 | GitLab System Hook 支持的 MR/push/repository update 事件，以及平台合成 payload | ✅ 自动化已验证，✅ 真实链路已验证 | 不再把真实 issue 创建/修改列为 System Hook 覆盖范围 |
| 异步派发 | hook 唤醒同步 run，生成 table tasks | ✅ 自动化已验证，✅ 真实链路已验证 | 继续验证日志可追踪 |
| System Hook 日志可观测 | 同步日志包含 System Hook 信息 | ✅ 自动化已验证，✅ 真实链路已验证 | 数据库查看入口补齐后再验 |

说明：System Hook 单次事件 payload 是事件级别，不会把 10000 个项目整体打包发送；僵尸项目没有事件时不会形成持续投递压力。GitLab System Hook 不包含 Issue events，issue 创建/修改由自动增量同步和补偿扫描保证最终一致。平台侧不再规划项目级入口过滤方案，System Hook 仍按实例级入口接收事件并通过 secret 校验、去重和同步互斥控制处理压力。

### G3 多源隔离与内网 CC/DGM 验证

| 功能点 | 覆盖范围 | 当前状态 | 后续动作 |
| --- | --- | --- | --- |
| 多源标识规范化 | `GitlabSourceInstanceSupport`、默认源、命名冲突 | ✅ 自动化已验证 | 保持单测 |
| 镜像表多源隔离 | 不同 source instance 使用隔离表名/表上下文 | ✅ 自动化已验证 | 小数据真实双源验证 |
| 事实表来源字段 | `source_instance` 写入、查询过滤、事实构建链路 | ✅ 自动化已验证 | 内网 CC/DGM 真实链路验证 |
| 查询下推隔离 | 按 source instance 过滤，避免跨源混入 | ✅ 自动化已验证 | 真实数据库双源抽样核对 |
| 代码走查多源看板 | source options、overview 查询 | ✅ 自动化已验证，⚠️ 待真实链路 | 页面切换不同源 |
| 外网无 CC/DGM 的限制 | 外网只验证多源能力，不验证 CC/DGM 名称本身 | ⏸ 后置专项 | 内网环境用非 Docker 直连方式验证真实 CC/DGM |

说明：这里不再使用“DGM 多源”这种说法。平台要证明的是多源能力正常、不同源数据不互相污染；CC 和 DGM 是内网具体源名。

### G4 数据库查看

| 功能点 | 覆盖范围 | 当前状态 | 后续动作 |
| --- | --- | --- | --- |
| 表列表 | `/api/database-browser/tables`、本地表、事实表、镜像表目录 | ✅ 自动化已验证，⚠️ 待真实链路 | 页面打开并核对分类 |
| 行数据查询 | `/rows`、分页、排序、关键字 | ✅ 自动化已验证，⚠️ 待真实链路 | 页面查询多张表 |
| 表刷新 | `/refresh`、镜像表单表刷新 | ✅ 自动化已验证，⚠️ 待真实链路 | 选一张小表刷新 |
| 表定义抽象 | table catalog、definition factory、row mapper、SQL bundle | ✅ 自动化已验证 | 保持服务级覆盖 |
| 同步日志全量查看 | `sync_runs`、`sync_run_events`、`sync_run_table_tasks`、`gitlab_system_hook_events` | ✅ 真实链路已验证 | 三张同步运行表和 System Hook 事件表均可在数据库查看中只读查询 |

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
| 测试阶段定义列表 | `GET /api/testing-phases`、页面列表 | ✅ 真实链路已验证 | `18181 -> 18080` 页面打开与接口查询通过 |
| 项目选项 | `/api/testing-phases/project-options` | ✅ 真实链路已验证 | `18181 -> 18080` 接口抽样通过 |
| 测试阶段新增、编辑、启停、删除 | `POST/PUT/PATCH/DELETE /api/testing-phases` | ✅ 真实链路已验证 | 使用临时 `projectId=99999901` 完成闭环 |

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
| W7 | 内网 CC/DGM 真实双源验证 | CC/DGM 均以非 Docker 方式直连，数据互不污染 |
| W8 | 大数据量压力专项 | 大量同步后再跑 W1-W6 抽样回归 |

## 已知缺口与新问题

| 编号 | 问题 | 影响 |
| --- | --- | --- |
| NEW-002 | 数据库查看暂未暴露 `sync_runs`、`sync_run_events`、`sync_run_table_tasks`、`gitlab_hook_events` | ✅ 已修复；`sync_runs`、`sync_run_events`、`sync_run_table_tasks` 已作为只读表暴露并通过真实接口查询 |
| NEW-003 | 历史数据库行中仍可能存在旧英文原始消息 | 已接受为历史数据兼容问题；前端展示层已有翻译时不作为当前待修复缺陷 |
| NEW-006 | 同步任务超时回收 SQL 存在 `finished_at` 字段歧义 | ✅ 已修复；超时子任务回收 SQL 使用 `task.finished_at`，单测覆盖 |
| NEW-009 | 浏览器真实路由冒烟发现 Element Plus radio 废弃 API 警告：`label act as value is about to be deprecated` | ✅ 已修复；`DataScopeBar.vue` 改用 `el-radio-button :value` |
| NEW-010 | 最小白名单源只同步 `users/projects` 时，全量同步后仍触发事实层刷新，并因缺少 issue/MR 源表产生 `PARTIAL_SUCCESS` | ✅ 已修复；自定义白名单未覆盖事实源表时跳过自动事实刷新 |
| NEW-011 | 同源同步互斥合并响应仍返回英文消息：`Refresh request was merged into an existing sync run for this source` | ✅ 已修复；同步合并和 fact refresh 复用响应均已中文化 |
| NEW-012 | `sourceInstance` 较长时，增量/补偿 run_id 可能超过 `sync_runs.run_id varchar(64)`，导致提交同步 500 | ✅ 已修复；run type 改为短别名，source 片段限制为 24 位，保留 32 位随机串，保证 run_id 不超过 64 |
| GAP-001 | 测试阶段定义模块原覆盖缺口已补测通过 | 保留历史记录；后续只需按常规回归维护 |
| GAP-002 | 部分前端组件只有页面间接覆盖，缺少独立组件测试 | 可先通过真实页面冒烟覆盖，后续补单测 |
| GAP-003 | 外网/本地 Docker 环境无法验证内网 CC/DGM 非 Docker 真实直连 | 内网迁移后必须作为首轮真实链路验证项 |

## 2026-05-20 分组测试记录

### 环境识别

| 项目 | 结果 | 结论 |
| --- | --- | --- |
| `18181` | 本机 `node ... vite --host 0.0.0.0 --port 18181`，加载 `/src/main.ts` | 当前工作区源码前端，用于验证最新前端代码 |
| `18182` | Docker 容器 `dcp-local-test-frontend-1`，加载 `/assets/index-BbYOi15y.js`，容器静态文件时间为 2026-05-08 | Docker 静态构建前端，不是当前最新前端；不含“数据镜像监控”模块 |
| `18083` | Docker 容器 `dcp-current-backend` 映射到容器内 `18080` | 可用于 HTTP 冒烟，但源库直连结果属于本地容器直连模式 |
| `18080` | 2026-05-20 14:39 从当前工作区源码重新启动，日志显示 `target/classes`，平台库为 `jdbc:postgresql://localhost:15433/qaflex` | 最新后端源码链路，后续真实 HTTP 冒烟以此为准 |
| GitLab 源配置 | 当前配置均为 `sourceMode=DIRECT`；2026-05-20 15:14 起，config 2/`cc` 已改为本机可达 `localhost:15434`，default/dgm 仍是本地容器 DNS | config 2 可用于最新本机后端直连链路测试；仍不等同于内网非 Docker 直连 |

### 已执行

| 分组 | 测试项 | 结果 | 备注 |
| --- | --- | --- | --- |
| W0 | 后端全量 `mvn test` | ✅ 通过 | 400 tests, 0 failures, 0 errors, 4 skipped |
| W0 | 前端 `npm run typecheck` | ✅ 通过 | TypeScript 无错误 |
| W0 | 前端全量 `npm test` | ✅ 最新版本通过 | 2026-05-21 复跑 `npm test -- --run`：75 files、221 tests 全部通过，NEW-004 已关闭 |
| W0 | 单独复跑 `code-review-rule-config.mount-smoke.test.ts` | ✅ 通过 | 2 tests passed，说明全量失败更像测试环境回收/异步清理问题 |
| G0 | 后端基础/安全模块测试 | ✅ 通过 | 20 tests, 0 failures |
| G0 | 前端基础路由/manifest/request 测试 | ✅ 通过 | 5 files, 17 tests passed |
| G0 | 登录/当前用户 HTTP 链路 | ✅ 通过 | `18083` 登录 admin 成功，`/api/auth/current` 返回 ADMIN |
| G0 | 前端首页 HTTP | ✅ 最新链路通过 | `18181` 返回 Vite 源码入口，加载 `/src/main.ts`；`18182` 仅保留为旧容器参考 |
| G1/G2 | GitLab 同步/System Hook 后端模块测试 | ✅ 通过 | 147 tests, 0 failures |
| G1/G2 | 镜像设置前端模块测试 | ✅ 通过 | 12 files, 41 tests passed |
| G1/G2 | 同步只读接口 HTTP | ✅ 通过 | configs/source-health/whitelist/table diagnostics/system hook status/status 均 success |
| G1/G2 | 源库连接模式核对 | ⚠️ 本地容器直连 | API 配置为 DIRECT，但 host 为容器网络代理，内网非 Docker 直连待验证 |
| G3/G4/G5 | 多源隔离/数据库查看/事实构建后端模块测试 | ✅ 通过 | 63 tests, 0 failures |
| G3/G5 | 多源看板/集成测试分析前端冒烟 | ✅ 通过 | 2 files, 2 tests passed |
| G4/G5 | 数据库查看、事实任务、诊断、集成测试选项 HTTP | ✅ 通过 | 只读接口均 success |
| 最新链路复核 | `18181 -> 18080` 登录、镜像配置、连接测试、数据库查看、评审数据、代码走查、统计看板、测试阶段 | ✅ 通过 | 前端为当前 Vite 源码；后端为当前源码 Spring Boot；后续功能测试以该链路为准 |
| G6/G7/G8/G9 | 评审数据、代码走查、客户问题、系统测试、集成测试后端模块测试 | ✅ 通过 | 106 tests, 0 failures |
| G6/G7/G8/G9 | 业务页面前端模块测试 | ✅ 通过 | 14 files, 30 tests passed |
| G10/G11/G12/G13 | 统计看板、采集表单、测试阶段、共享抽象后端模块测试 | ✅ 通过 | 70 tests, 0 failures |
| G10/G12 | 统计看板、基础表格、共享组件前端模块测试 | ✅ 通过 | 8 files, 21 tests passed |
| G10 | 统计看板真实接口 | ✅ 最新链路通过 | 8 个 boardKey 的 overview/export 均成功；有数据的看板 details 成功，零数据看板跳过下钻 |
| G11 | 采集表单真实接口 | ✅ 最新链路通过 | `/api/collect-forms/detail`、`/notification-payload` 带必要参数调用成功 |
| G11 | 测试阶段定义 CRUD | ✅ 最新链路通过 | 使用临时 `projectId=99999901` 完成 create/update/disable/delete 闭环 |
| G4 | 数据库查看行查询 | ✅ 最新链路通过 | `gitlab_sync_configs` 与 `ods_gitlab_cc_environments` 行查询成功 |
| G4 | 数据库查看单表刷新 | ✅ 基线提示已修复 | 无全量基线的镜像表会禁用刷新并显示中文前置提示；具备基线的镜像表刷新仍可执行 |
| G7/G8/G9/G10 | 业务导出真实接口 | ✅ 最新链路通过 | 代码走查、客户问题、系统测试、集成测试、统计看板导出接口均返回文件内容 |
| G7 | 代码走查规则预览 | ✅ 最新链路通过 | `/api/code-review/illegal-records/rule-config/preview` 成功返回预览结果 |
| G6 | 评审数据 CRUD | ✅ 最新链路通过 | 临时评审记录 create/detail/problem item create/update/delete/record delete 闭环成功 |
| G1 | 自动同步测试隔离 | ✅ 已调整 | 为避免本地容器代理源库干扰冒烟，已将 cc/default/dgm 的 `autoSyncEnabled=false`；`enabled` 保持 true |
| G1 | config 2 本机直连连通性 | ✅ 最新链路通过 | config 2/`cc` 调整为 `sourceMode=DIRECT`、`dbHost=localhost`、`dbPort=15434` 后，`/api/gitlab-sync/test-connection/by-config?configId=2` 成功 |
| G2 | System Hook 最新后端真实投递 | ✅ 最新链路通过 | GitLab `SystemHook.execute` 投递到 `host.docker.internal:18080`，`WebHookLog.id=56 response_status=200`，平台 `gitlab_system_hook_events.id=10 processed=true` |
| G2 | System Hook 接收与同步日志 | ✅ 最新链路通过 | 平台 `sync_runs.id=289` 为 `SYSTEM_HOOK/SUCCESS`，同步日志包含 `System Hook 唤醒`；issue 相关结果来自模拟 payload，不作为 GitLab System Hook 支持 Issue events 的证据 |
| G2 | System Hook 环境反证 | ⚠️ 已定位为配置问题 | config 2 仍指向容器 DNS 时，GitLab 投递 `WebHookLog.id=55` 已 200，平台 run 288 失败于源库连接；改为 `localhost:15434` 后 run 289 成功 |
| G1 | 同步任务幽灵状态复查 | ✅ 已收敛 | 2026-05-21 最新库复查终态父 run 下 active 子任务数量为 0；NEW-006 超时回收 SQL 歧义已修复 |
| W1/W5/W6 | 浏览器真实路由冒烟 | ✅ 最新链路通过 | Playwright 打开 `18181 -> 18080` 共 26 个路由，覆盖质量看板、评审数据、代码走查、集成测试、系统测试、客户问题、数据镜像监控、数据库查看、测试阶段定义、外部采集表单和 404；无失败请求，报告见 `.tmp/browser-smoke-20260520/report.json` |
| G0 | 审批用户页面权限链路 | ✅ 最新链路通过 | 浏览器登录 `approval` 用户后访问受限路由被引导至质量看板，当前用户返回 `APPROVAL` |
| G1/G3 | 最小双源配置保存与诊断 | ✅ 最新链路通过 | 新增/复用 config 4 `smoke_cc` 与 config 5 `smoke_dgm`，均为 `sourceMode=DIRECT`、`whitelistTables=["users","projects"]`、`autoSyncEnabled=false`；连接测试、诊断和白名单校验均通过 |
| G1/G3 | 最小双源全量同步 | ✅ 最新链路通过 | config 4 `smoke_cc` run 296 `FULL_SYNC/SUCCESS`，计划/完成 2 表，扫描/应用 6 行；config 5 `smoke_dgm` run 298 `FULL_SYNC/SUCCESS`，计划/完成 2 表，扫描/应用 3 行 |
| G1 | 增量同步真实链路 | ✅ 最新链路通过 | config 4 `smoke_cc` run 300 `INCREMENTAL_SYNC/SUCCESS`，计划/完成 2 表；小数据无新增行时扫描/应用 0 行 |
| G1 | 同源同步互斥与取消 | ✅ 最新链路通过 | run 302 验证活动任务取消为 `CANCELLED`；run 303 期间再次提交增量被合并到已有 run，未创建并行同步，随后取消成功 |
| G1 | 失败重试与空取消 | ✅ 最新链路通过 | config 4 无失败任务时重试请求返回未创建新 run；无活动任务时取消请求返回未接受，状态保持 `IDLE` |
| G4 | 数据库查看单表刷新 | ✅ 最新链路通过 | `ods_gitlab_smoke_cc_users` 已具备基线后刷新成功，run 301 `TABLE_REFRESH/SUCCESS` |
| G3/G4 | 双源镜像表隔离 | ✅ 最新链路通过 | 实际生成独立表 `ods_gitlab_smoke_cc_users/projects` 与 `ods_gitlab_smoke_dgm_users/projects`；计数为 CC users=3、CC projects=3、DGM users=3、DGM projects=0，未混入同一张表 |
| G5 | 事实构建真实接口 | ✅ 最新链路通过 | 使用 config 2 触发 issue fact rebuild、latest task 查询和 integration fact rebuild 均成功 |
| G11/G13 | 采集表单与审计真实链路 | ✅ 最新链路通过 | 采集表单 save/update/delete 闭环成功；采集表单审计日志与操作审计日志均可查到新增记录 |
| G1 | 白名单外镜像清理 | ✅ 最新链路通过 | config 4 执行 purge excluding current whitelist 成功，保留当前 `users/projects` 小表白名单 |
| G5 | 最小白名单后的事实层自动刷新 | ✅ 已修复 | 自定义白名单未覆盖事实源表时跳过自动事实刷新，避免最小表集同步后产生误导性 `PARTIAL_SUCCESS` |
| G7 | 浏览器控制台兼容性告警 | ✅ 已修复 | `DataScopeBar.vue` 已改用 `el-radio-button :value`，回归测试通过 |
| G1 | 同步互斥响应中文化 | ✅ 已修复 | 同源同步合并和 fact refresh 复用响应均已中文化 |
| G1 | 每分钟自动补偿与页面抖动 | ✅ 最新链路通过 | config 2 `jt` 指向本地 GitLab 直连源，白名单 12 张核心表；run 20 `COMPENSATION/SCHEDULE/SUCCESS`，耗时约 1 秒，`recordCount=0`；Playwright 在 `/customer-issues/cc-product-issues` 80 秒采样中路由变化 0、全局 loading 0、表格行数稳定 2、API 5xx 0、请求失败 0、控制台错误 0 |
| G1 | 高频手动增量与页面抖动 | ✅ 最新链路通过 | 同一页面连续触发 5 次增量同步，HTTP 均 200；其中一次被合并到已有同源 run；页面路由变化 0、全局 loading 0、表格行数稳定 2、API 5xx 0、控制台错误 0 |
| G1 | 同步 run_id 长度边界 | ✅ 已修复并复测 | 新增单测覆盖长 `sourceInstance=jitter_smoke`；真实 API 提交增量 run 29 成功，生成 `sr_is_jitter_smoke_3a3f092874014a2f8bbb6aed02a31bc0`，长度 51，状态 `SUCCESS` |

### 新增问题

| 编号 | 问题 | 状态 |
| --- | --- | --- |
| NEW-004 | 前端全量 `npm test` 在所有测试主体通过后，Vitest 捕获 `CodeReviewIllegalRuleConfigView` 相关 teardown 后未处理异常：`document is not defined`，涉及 Element Plus loading/message | ✅ 已关闭；2026-05-21 最新版本复跑 `npm test -- --run`，75 files、221 tests 全部通过 |
| NEW-005 | `18182` Docker 前端不是最新版本，静态构建产物时间为 2026-05-08，包内未包含当前源码已有的“数据镜像监控”模块 | ✅ 不作为产品问题；有效测试口径为 `18181 -> 18080`，18182 仅保留为旧容器环境记录 |
| NEW-006 | 最新后端启动后，`SyncRunLeaseService.markTimedOutRunTasks` 在调度恢复时触发 SQL 错误：`column reference "finished_at" is ambiguous` | ✅ 已修复；SQL 使用 `task.finished_at`，单测通过 |
| NEW-007 | 数据库查看中镜像表可见且可触发单表刷新，但未完成全量同步基线时返回 400：`手动刷新表需要先完成一次全量同步基线` | ✅ 已修复；无基线镜像表提前禁用刷新并显示中文前置提示 |
| NEW-008 | `sync_runs` 无活动任务时，`sync_run_table_tasks` 仍残留 22 条 `QUEUED`，对应 run 139/270/271/278/280 已为 `FAILED` 或 `TIMEOUT` | ✅ 已收敛；当前库无幽灵子任务，NEW-006 修复恢复收敛风险 |
| NEW-009 | 浏览器真实路由冒烟时，`/code-review/illegal-records` 触发 Element Plus radio 废弃 API warning：`label act as value is about to be deprecated` | ✅ 已修复；`DataScopeBar.vue` 使用 `value` |
| NEW-010 | 最小白名单源 `users/projects` 全量同步成功后仍自动触发事实层刷新，因缺少 issue/MR 源表产生 `PARTIAL_SUCCESS` | ✅ 已修复；事实刷新监听器会跳过未覆盖事实源表的自定义白名单 |
| NEW-011 | 同源同步互斥合并响应仍返回英文消息：`Refresh request was merged into an existing sync run for this source` | ✅ 已修复；旧英文响应源码扫描无残留 |
| NEW-012 | `sourceInstance` 较长时，同步 run_id 生成值超过 `sync_runs.run_id varchar(64)`，增量/补偿提交失败 500 | ✅ 已修复；`SyncRunSubmissionService` 使用短 run type 别名与截断 source 片段生成 run_id，单测和真实 API 均通过 |

## 下一步记录方式

每完成一个分组，将状态从“待真实链路”更新为“真实链路已验证”，并记录：

1. 测试时间。
2. 测试环境。
3. 使用的数据源或项目。
4. 涉及页面或接口。
5. 是否产生新问题。
6. 相关 run id、hook event id、日志表记录或截图位置。

只有 W1-W6 基础功能冒烟稳定后，再启动 W8 大数据量压力专项。
