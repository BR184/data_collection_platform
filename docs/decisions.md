# 架构决策记录（Decisions）

<!-- DOC_STATUS_START -->
> 文档状态：平台架构决策的单一持续维护记录
> 说明：记录数据采集平台"为什么这么设计"的架构决策。与 `product.md`（是什么/需求）、`architecture.md`（是什么/结构）、`progress.md`（当前状态/进度）分工：本文件记录决策与理由；BI 看板的决策在 `docs/bi-dashboard/decisions.md`。禁止按决策新开文件。
<!-- DOC_STATUS_END -->

## 使用规则

- 新增架构决策：在本文件末尾追加条目（编号 `D-NN`），说明背景（一句话）、选择、否决选项（如有）、当前状态、关联文档。
- 决策被替代或废弃：更新对应条目状态并压缩失效内容，不新建文件、不保留双轨结论。
- 平台决策统一在 `docs/decisions.md`，BI 决策在 `docs/bi-dashboard/decisions.md`；不复制到平台通用文档。

---

## D-01 LDAP 认证与本地授权

- **状态**：已接受 / 已实施
- **决策**：
  1. 保留平台自己的登录页，后端调用 LDAP 平台登录接口认证；浏览器只向平台提交凭据，不使用 LDAP JWT 访问平台接口。
  2. LDAP 只回答"用户是谁、账号是否可用、用户有哪些角色"；平台本地维护 RBAC（角色→权限多对多），权限检查以后端为最终裁决，前端只按同一权限控制可见性。
  3. 首次全量拉取用户基础信息与角色到本地镜像表，之后每次登录刷新该用户；LDAP 角色是只读主数据，平台不创建/删除/改名角色，也不把平台权限写回 LDAP。
  4. LDAP 不可用时已有 Session 和已加载权限继续有效，新登录失败；账号状态/角色变化在下次登录生效，当前不引入轮询或主动踢出。
  5. 多实例必须用独立 Session/CSRF Cookie 名称（Cookie 作用域不含端口），标准 Compose 以 `COMPOSE_PROJECT_NAME` 派生实例身份。
- **理由**：统一账号来源，同时保留平台独立授权与离线可用，避免绑定 LDAP 平台的会话机制。
- **关联**：`docs/architecture.md`、`docs/platform-page-business-rules.md`

## D-02 版本化外部数据集 API

- **状态**：已接受 / 已实施
- **决策**：
  1. `/api/external/v1/datasets` 只服务经过确认的进程外消费者，采用 Provider 注册、数据集白名单和独立无状态 Bearer 认证链。
  2. 外部接口默认关闭；每个消费者使用独立令牌摘要和最小数据集权限，生产调用限于 HTTPS 或受控内网链路。
  3. Provider 复用平台事实和统计服务，不复用页面 DTO，不接受写操作；新增 Provider 前必须记录真实消费者、数据责任、schema、权限、发布与移除条件。
- **理由**：平台对外只读数据能力按需、最小暴露，不为内部页面需要而创建外部 Provider。
- **关联**：`docs/architecture.md`

## D-03 CC_PRODUCT 客户成员与响应模板事实

- **状态**：已接受 / 已实施
- **决策**：
  1. `ods_gitlab_issues.description` 为客户名称来源；仅 description 无有效客户名称时用标题末尾 `——客户` 兜底。
  2. 客户拆分后经 `issue_customer_name_aliases` 规范化，别名匹配精确值（种子：`新世纪 -> 郑州新世纪`）。
  3. `issue_fact.customer_names` 保存稳定展示投影，`issue_fact_customer_members` 以 `(source_system, source_instance, project_id, issue_id, customer_name)` 保存多对多成员；客户筛选用成员关系 `exists`，不拆分事实记录。
- **理由**：客户是多对多事实，需要稳定成员表而非把多条客户名挤进单字段，保证筛选与展示一致。
- **关联**：`docs/platform-page-business-rules.md`

## D-04 同步运行时统一容量、租约与分页让行

- **状态**：已接受 / 已实施
- **决策**：
  1. `SyncExecutionBudget` 是 worker 和 DIRECT 池容量的唯一解析入口；最终 worker 数写入 `sync_runs.resolved_worker_count`，运行期间不重读配置。
  2. DIRECT 池按 `configId` 管理，配置提交后精确退休旧池；活动镜像运行期间禁止修改连接/线程容量字段。
  3. 调度器每次领取运行生成唯一 owner，首次心跳、续租、暂停、终态提交均校验 owner；表任务用可续租 lease，源查询与平台事实写入按页让行。
- **理由**：容量、租约和并发按单一事实源收敛，避免运行快照、池规格和实际并发漂移，保证长任务可让行。
- **关联**：`docs/architecture.md`

## D-05 增量物理删除探测与精准派生发布

- **状态**：已接受 / 已实施（2026-07-31 起；性能硬阈值待内网固定负载基准）
- **决策**：
  1. 正常同步只保留一条派生链：时间增量或精确信号写入 → 批量权威范围收敛 → 主键存在性探测 → 事务内登记版本化根目标并写 ODS → 有界根 ID 事实替换 → 新旧稳定范围投影发布。
  2. `GitlabSourceLineageCatalog`、`GitlabFactDependencyCatalog` 是来源表/主键/权威关系/事实依赖的唯一事实源；新推荐表或事实 SQL 依赖必须显式登记，不存在默认忽略分支。
  3. 镜像运行采用带持久状态的 `SCAN/AUTHORITATIVE -> RECONCILE` 屏障，只有生产者和权威范围全部成功才继续。
- **理由**：物理删除需要精准探测和原子发布，避免全表对账拖慢增量，保证删除语义在 ODS/事实/投影一致。
- **关联**：`docs/architecture.md`

## D-06 产品版本归一化与评审数据域边界

- **状态**：已接受 / 已实施
- **决策**：
  1. 有效且已登记的 `product_version_id` 优先作为版本身份；缺少有效 ID 的历史/兼容记录用项目名称主体匹配（Unicode/大小写/空白归一，识别 `YYYYR<n>` 主体）。
  2. 一个项目名称命中多个主体时展开为多个版本成员，同一版本内只计一次（如 `CC2025R1&R2` 归入两个版本，不映射为虚构版本）。
  3. 无法命中/无法解析/歧义版本标记为未确定，不进入需要确定版本的统计、外部数据集或跨版本汇总，但可在诊断中追溯。
  4. 所有页面、统计服务、快照、导出和 Provider 共用这套规则与规则版本，不允许页面级特殊映射。
- **理由**：老平台版本写法不统一，需要一套可复现的归一化规则保证跨页面/导出/统计一致。
- **关联**：`docs/product.md`、`docs/platform-page-business-rules.md`

## D-07 统计看板筛选引擎单一语义

- **状态**：已接受 / 已实施
- **决策**：
  1. 统计看板筛选操作符语义唯一归属 `service/statistics/engine/StatisticFilterEngine`；看板经 `StatisticFieldDescriptor` 注册表声明字段绑定，禁止私有实现 matches*/操作符分支。
  2. 领域特有判定（里程碑、bugStatus/delayCause 成员表、testingPhase 阶段成员）经描述符 override 钩子注入，不进入通用操作符。
  3. 行为等价由金标矩阵（`AbstractStatisticBoardGoldenMasterTest` 子类，快照落盘 `src/test/resources/golden/`）锁定；校验层已拒绝未知字段/操作符并丢弃空值条件，引擎相应分支为防御性兜底。
  4. `MirrorTableOverviewBoardService` 豁免迁移：其展示层汇总行筛选为大小写敏感+数值操作符方言，强行统一会改变用户可见行为。
- **理由**：原 11 个事实看板各自复制约千行筛选实现且 SQL/内存双轨无对齐保障；单点引擎消除口径漂移温床，新看板成本降至定义+聚合。
- **关联**：`docs/plans/statistics-board-framework-refactor.md`、`docs/architecture.md`（统计板契约）。
