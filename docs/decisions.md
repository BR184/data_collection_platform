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

## D-08 黄金基线回归：基线冻结与变更规则

- **状态**：已接受 / 已实施
- **决策**：
  1. 平台产出接口的行为由黄金基线锁定：冻结源数据夹具（GitLab 切片 + 平台种子，`backend/src/test/resources/golden-baseline/fixtures/`，manifest 记录行数与 SHA-256）走真实链路（同步 → 事实 → 统计），全部产出端点的响应/Excel/受影响表状态快照落盘，比对为严格模式（缺失即失败）。
  2. 覆盖清单机器强制：全部 Controller 端点必须在 `endpoint-catalog.yml` 登记（用例或 EXCLUDED+原因），护栏测试在默认套件运行，未登记端点门禁失败；不允许静默遗漏。
  3. 无意差异一律视为回归，修复实现而非更新快照；有意行为变更经 `-Dgolden.update=true` 重建受影响快照并 git diff 人工审阅，与变更同单元提交。**提交范围以 compare 失败清单为准**：update 模式会整批重写全部快照的易变字段（`generatedAt`/`queryDurationMs`/各类运行时间戳/JSON 键序），故只提交语义变更的快照文件，比对期已掩码字段的差异还原，使提交差异可逐行审阅（2026-10-08 实证：update 重写 71 份但语义变更仅 3 份）。运行态易变字段（如每次链路运行刷新的进度/事件时间）应补入目录 `ignore-paths`，与同组已掩码字段同口径。
  4. 夹具改动即新基线版本：重新抽取、更新 manifest、在决策记录留痕；golden 运行档禁止触碰外部活体（GitLab Web API、老平台 MySQL/Mongo），依赖活体的路径显式 EXCLUDED 并注明替代覆盖层。
  5. 基线版本状态（2026-09-02）：当前输出快照基线对应本地开发树 = 内网验证版（2026-08-06 全量包 `qaflex-full-20260806T111653Z-8269d2b61253`）+ 解耦前修复 + 解耦重构，**整体未进内网测试**，属开发期基线——可捕获解耦收尾期间的产出漂移，不能证明当前产出等于内网验证版产出。解耦版通过内网测试后必须 `-Dgolden.update=true` 重冻结快照（夹具不动）升级为可信基线。
  6. 2026-10-14 用户批准完整读发布模型的黄金测试/更新，并单独批准补齐兼容夹具完整装载证据。新增 `compatibility-completeness.sql.gz` 和 manifest 扩展版本 `20261014-read-publication`；原三份压缩夹具、业务行及固定业务日不变。新 readView 运行身份须先与真实租约/冻结域核对，再规范化随机 ID、来源指纹及运行时刻；修订 UUID 映射到真实域、代际起始序号及发布序号，区间/归属仍比对，既有业务掩码不变。该轮完整 compare 为 202 项、86 failures/6 errors（**历史状态，已被第 7 条替代**），当时业务排序与测试隔离仍待修复、未更新快照。用户要求暂停，由 AI 员工继续实施、经理审批。
  7. 2026-10-14 员工收口（经理最终收口审批通过，R1/R2 已独立核验）：在用户/经理显式授权下以 `-Dgolden.update=true` 生成快照（130 改 + 6 新，夹具与 `frozen_at` 未变），去更新模式严格复比 **202 项 0 failures / 0 errors 全绿**。阻塞 B 根因确证为 Spring 6.2 把 `@Scheduled` 方法包进 `Task$OutcomeTrackingRunnable` 导致暂停静默失效（已改为沿包装链解包并断言取消数为正，把静默失效转为硬失败）；阻塞 A 为兼容/正式两条读取路径缺稳定收尾键并误用物理修订 `id`（已改用声明业务身份 `legacy_source_id`／`business_order_id + visible_from`，并以“导出行多重集与数值不变”证明只是确定性化）。另把“当前时刻”改为可注入依赖：`PlatformConfiguration.platformClock()` 提供系统 UTC 时钟、`CustomerIssueRecordService` 构造注入，黄金 `FrozenTimeConfiguration` 以 `@Primary` 固定到清单 `frozen_at`——`retentionHours`/导出列 `U` 原属比对期掩码的墙钟易变字段（掩码保持原样，未放宽），冻结后不再随运行日历抖动快照文件。**当前基线状态（唯一入口）**：交付快照范围为 88 份语义变化 + 6 份新增；41 份仅比对期掩码字段变化的修改已按第 3 条从版本库原冻结资源逐文件还原（另有 1 份 `home/get____default.json` 属同类，经理审计清单未列，经同一口径核验为掩码内等价后一并还原），未手工编辑任何 JSON、未整体覆盖资源目录。还原证据：对 HEAD 与工作树两份同时施加该端点目录 `ignore-paths` 后逐键相等（清单与字段见 `.tmp-logs/golden-20261014-employee-masked-only-audit.json`）。按第 3 条纪律，纯掩码字段还原不需要重跑全链路。工作树混有其它工作单元改动，未提交、未推送；夹具与 `frozen_at` 未变，因此不构成新基线版本。本条绿灯只代表本地黄金验收，不代表目标内网接管、容量或生产部署通过。
  8. 2026-10-19 用户依据第 3 条与“未经用户确认更新快照”的禁止规定，明确确认将 1010 恢复候选的 23 项有意差异（16 份修改 + 7 份新增；逐路径见 `.tmp/sync-1010-current-20261016/restore-migration/golden-compare/review-20261019/golden-23-paths.txt`）纳入黄金基线。更新模式 `-Dgolden.update=true` 运行 203 项 0 失败/0 错误；按第 3 条只保留 23 份已确认快照，其余 241 份纯掩码/生成时刻抖动文件还原；去更新模式严格复比 203 项 0 失败/0 错误全绿。夹具、`frozen_at`、`baseline-manifest.json` 与端点目录未变，因此不构成新基线版本，只更新交付快照内容；候选已重新完整冻结（提交 `5ce737238c4d20d23e8de485d087556b101b161f`，tree `a17c93795f75961ac9de503883a5a5aeeae1646d`）。本条只代表本地黄金验收，不代表内网接管、容量或生产部署通过。
- **恢复候选基线与制品关联（2026-10-19）**：第8条是独立1010恢复候选的受审基线，不替换第7条D-26主工作树的基线或通用并列行规则。经理核对两份完整冻结之间恰为已确认的23份测试快照变化，生产、前端与构建部署资产零差异、运行JAR及归档摘要未变；本次允许将最终黄金验收关联到同一交付包，不为这次测试资源变化单独重包。该裁定仅针对本工作单元，精确制品、审批范围及现场前置条件见[进度](progress.md)。
- **并列行差异处理规则（本轮确立）**：compare 报出的顺序差异，先证明“行多重集与全部数值不变”，再判定是否为冻结序不可复现；若已证明冻结快照的并列序本身就是物理行序（同一组并列行的历史序方向不一致，无法由任何业务键复现），一律以“声明业务身份 + 确定性收尾”修实现，禁止为迎合旧行序保留物理 `id`，也不得据此改动夹具或掩码。
- **理由**：平台体积已大到改动引起的产出漂移无法人工察觉；快照严格模式+覆盖护栏+冻结夹具把"改一处别处悄悄变"转化为机器可检测的确定性问题。
- **关联**：`docs/architecture.md`（黄金基线回归测试章节）、`backend/src/test/resources/golden-baseline/endpoint-catalog.yml`。

## D-09 下拉框选项设置：配置/字段解耦与双套黑白名单判定

- **状态**：已接受 / 已实施（2026-09-03）
- **决策**：
  1. 下拉候选的人工干预统一走系统设置"下拉框选项设置"页面：可配置下拉字段在代码注册表（`DropdownOptionFieldRegistry`）显式登记（字段键、位置路径显示名、自动获取值池供应者），未注册字段不进入设置页面与判定管道；新增字段=注册一行+接入一行。
  2. 配置与字段解耦（`dropdown_option_configs` + `dropdown_option_field_bindings`）：多个字段可绑定同一配置共用（改一处多字段生效，配置详情展示使用者清单），字段可复制当前配置或新建空白配置后改绑实现拆分，原配置继续服务其余字段；配置名不存库，由绑定关系实时推导（单绑定=字段位置路径，多绑定=全部位置路径+共用提示）。
  3. 双套黑白名单：自动获取值与手动添加值各一套有序规则，判定语义相同——逐值自上而下取第一条命中规则（黑名单剔除、白名单保留），全部未命中时存在任一白名单则剔除（精确模式）、只有黑名单则保留；最终显示 = 自动值保留 ∪ 手动值保留。重叠值（既被自动获取又被手动添加）任一通道保留即显示——手动添加是对抗自动池规则收紧的持久固定；两套规则互不混用。
  4. 规则条件复用统计筛选引擎（伪字段 `optionValue`，单选项值包装为单元素多值行）与标签组成员语义；标签组条件在求值期实时展开（标签组编辑即时生效），展开失败按"不命中"降级并告警，不阻断候选读取；保存时校验标签组可用性与全部规则结构。
  5. 缓存正确性：配置内容与字段绑定任一变化都并入 `reviewDataSourceVersion()` 指纹使评审候选快照失效重建；未绑定或空配置 ≡ 直通现状（既有端点响应零变化）。列表筛选候选与表单候选仍各自独立，配置只作用于注册时声明的候选通道。
  6. 权限：读取/预览 = `system.dropdown_option.view`，保存/改绑 = `system.dropdown_option.manage`，两码仅授 SUPER_ADMIN/ADMIN。
- **理由**：候选来源写死导致业务需要的值（如组合项目名）无法进入下拉；单字段一配置的模型无法表达"多字段同规则"与"后续分家"的真实演进；双套规则避免"手动补充值被自动池规则误伤"与"自动值垃圾无法单独清理"两类诉求互相牵制。
- **关联**：`docs/plans/dropdown-option-config-20260903.md`、`docs/architecture.md`（业务域模块解析）、`docs/platform-page-business-rules.md`（评审数据候选规则）。

## D-10 事实构建分批发布与租约治理

- **状态**：2026-09-07 生效（内网 30001 全量构建假超时互锁问题的根治，用户批准实施）。2026-10-12 分批构建、续租、进度事件原则继续有效；原地清理与批次公开语义已由 D-26 的候选修订模型替代。
- **决策**：全量事实构建（ISSUE/MR/集成测试）分批事务提交候选修订；运行中事实任务按批续期租约（owner 围栏）；run 心跳调度器按最大并发运行数定容；重试/租约超时/进度/完成事件写入 `sync_run_events` 供 UI 可见。全部事实及必需投影完成后统一发布，不公开半成品。
- **等价性与恢复**：历史黄金证据证明迁移前分批 upsert + 快照外清理与「删全部+重插」最终业务值一致。当前失败清理未公开候选，新合法持有者重建完整候选；已发布事实及父修订关系不被原地改写，历史可见性由版本区间保持。
- **动机**：旧形态下全量构建（内网约 4.6 万行、实测 4.6–17 分钟）必然超过 180s 任务租约且执行中无续租；run 心跳为共享单线程，被长事务饿死后 run 判 TIMEOUT → 任务重派 → 新旧事务行锁互锁 → 原事务 owner 围栏失败整体回滚丢全部工作。git 考古：机制 2026-05-09（e00c5998）引入、2026-08-03（2fd5c34e）随版本化发布重构定型，非近期修复回归。
- **边界**：定向批次内事实修订、客户成员/提交关系、搜索列、根清单与构建完成同事务；整轮候选的发布边界以 D-26 为准。手工构建仍由既有互斥控制，分批大小可配 `platform.gitlab-mirror.fact-full-build-chunk-size`（默认 2000）。定向目标生成/领取的性能边界仍以 D-13 为准。
- **禁止**：不得把构建中断后已提交的候选批次当成完整产出；不得引入原地修改已发布版本的补偿双轨。

## D-11 数据库备份管理页：执行域自治与恢复仅走 Runbook

- **状态**：2026-09-09 生效（方案经用户审批后实施；后端 8159c49b、前端 181c9fc2、打包 0ab971d0，恢复演练与黄金基线 184/184 全绿实证）。
- **决策**：备份执行域完全自治——三表复用 BiCAT 单行条件 UPDATE 抢运行权 + `backup_state` 租约续期模式，`pg_dump --format=custom` 在后端容器内执行（镜像内置 `postgresql-client-16`，与目标 PG 16 主版本一致），产物经 `pg_restore --list` 可恢复性校验后落位：LOCAL 经 bind mount 原子落宿主机目录（默认 `/opt/qaflex-backups`，独立于部署目录），REMOTE 经 sshj 上传备份服务器（TOFU 指纹校验通过才发凭据、`.part-` 临时名远端改名、大小+SHA-256 双校验，成品仅存远端一份）。轮转按份数（默认 14）且只匹配 `qaflex_<实例标识>_*.dump` 本实例模式。每日定时判期与孤儿 RUNNING 回收共用 60s 巡检；失败不自动重试。远程密码 AES-256-GCM 版本化密文落库，主密钥 `PLATFORM_BACKUP_SECRET_KEY`（base64 32B）由 fresh 包 `.env` 预生成、更新包现场手动补行；轮换密钥需页面重录远程密码。
- **恢复边界**：页面不做一键恢复（误点即全库覆盖）；恢复仅走 `deploy/runbooks/database-backup-restore.md`（停 backend → drop/create → pg_restore --exit-on-error → 起 backend → 页面验收）。custom 格式不向后兼容：恢复用 pg_restore 主版本 ≥ 产物 pg_dump 主版本（本地演练实证 PG18 产物不能被 PG16 pg_restore 读取；生产镜像 client-16 与 PG16 服务端天然一致）。
- **动机**：20001 生产库此前无任何自动备份；独立脚本/cron 路线无法安全驱动且不可配置（用户拍板放弃），页面化让备份时刻/备份服务器/保留策略可运维。测试连接绝不触发备份、REMOTE 未配置时默认本机落位均为用户确认的产品语义。
- **禁止**：页面一键恢复；后端容器挂 docker socket；失败自动重试；轮转触碰非本实例命名模式的文件；把远程密码明文落库或回显。

## D-12 BI 编码页静态代码扫描图表下线（待 PMD-Biome 重建）

- **状态**：2026-09-15 生效（用户裁定“先下线、数据太少、PMD-Biome 启用后再上线”；工作单元 SA 代码/测试/文档已实施，黄金基线 bi/coding 快照以更新模式重建后审阅）。
- **决策**：下线 BI 编码页“静态代码扫描结果”图表及其 BI 侧供数——前端删除该卡片与 `scanData`/`scanChart`/词条，后端删除 `BiCodingCalculator` 的 scanFacts/scanValues/static-scan 分区/`ScanPoint`/`scanTrend`/`scanCoverage`/CD-36、CD-37 溯源、`BiCodingSource.scanDataAvailable` 与适配器对 `scan_status`/`scan_bug_count` 的 SELECT、`BiDownloadAuthorizationService` 的 coding 白名单 `stacked-category-bar` 条目。按开发期演进红线一次性清理干净，不留死代码。
- **理由**：现有静态扫描数据仅 `scan_status`（三态）+ `scan_bug_count`（计数），无严重级别/分类/规则/文件/模块维度，前端只能按状态堆“记录数+问题总数”，量纲混淆无业务价值；`product.md` 旧述“阻断/严重/一般/提示堆叠”从未被数据支持（SURVEY-01 调研结论）。SonarQube 正被 PMD-Biome 替换，但 PMD-Biome 尚未接入平台（全库零引用）。
- **边界**：底层 `code_review_formal_records`/`code_review_match_mode_records` 的 `scan_status`/`scan_bug_count` 列保留（代码走查记录页 illegal-records 与其黄金基线快照合法消费，不受影响）；共享图表类 `StackedCategoryBarChart`、templateId `stacked-category-bar`（SystemTest 模块缺陷级别图复用）、`CategorySeriesData`/`sortCategorySeriesData` 均保留；最终 `RULE_VERSION` 为 `bi-coding-v5`，静态扫描下线本身不另行增加版本号，版本升级来自同一变更集随后冻结的 CD-38A 注释率聚合口径。
- **重建条件**：PMD-Biome 正式启用并以结构化 `issues[]`（severity/category/rule/文件行列/GitLab 深链）接入平台后，按严重级别/分类/工具/Top 规则/模块密度趋势全新设计重建，届时重新登记核对表 CD 口径与黄金基线快照。

## D-13 增量 FACT_REFRESH 长跑的版本归因与问题边界

- **状态**：2026-09-16 生效（用户要求将旧版/后续离线包与本地代码对照结论落档；本条是已确认诊断，不代表实现已修复）。
- **结论**：该问题不是“完全由某次更新新引入”或“从设计之初就已达到当前耗时”二选一，而是既有增量扩展性缺陷在后续更新和数据规模下被放大：
  1. `qaflex-full-20260803T122027Z-ad35f6c0e8c3` 已包含默认 200 根/批、FACT_REFRESH 内串行定向任务、粗粒度来源反向血缘、重型 Issue/MR CTE 和逐根版本锁；因此大目标量下的容量风险早已存在。该包 manifest 还是 `fresh-empty`、`facts.rebuildRequired=false`、工作树 clean，不能用“旧包当时很快”证明架构可扩展。
  2. 首个改变后续性能特征的明确更新是 2026-08-10 的 `c367258a`（随 `qaflex-update-20260810T065129Z-4ca35ca63f73` 版本线发布）：默认 MR 提交事实路径增加 `ranked_diffs` 窗口排序查询，定向 MR 批次额外执行该查询；待处理目标领取也从单次镜像运行范围扩大为来源范围历史待处理目标。它是放大因素，不是全部根因。
  3. 2026-09-14 的 3.6 万～4.5 万级 ISSUE/MR 目标洪峰是实际触发条件；目标来源的具体 ODS 表/列和每条 SQL 的耗时占比仍需内网按 `last_task_id → source_table` 聚合及 `EXPLAIN (ANALYZE, BUFFERS)` 确认，不能仅凭源码虚构。
  4. `qaflex-update-20260910T042253Z-94cd3a4d2a47` 的 manifest 只说明升级后必须人工执行 `issue` 范围事实重建；随包 README 明确“不触发 GitLab 全量同步”。这可能造成一次性重建工作，但不能单独解释持续的增量 FACT_REFRESH 长跑。
- **排除项**：问题不归因于已移除的老平台独立“集成测试”模块、CAT 集成测试客户端、BI 前端看板改动或 2026-09-10 的打包动作。`integration_test_fact` 仍是后端事实类型/兼容数据边界的一部分，但不是本次 GitLab ISSUE/MR 目标洪峰的证据。
- **边界**：D-10 只覆盖全量构建的大事务、租约和互锁治理；当前并未修复定向增量的目标洪峰、串行批处理、重型 CTE 或逐根锁成本。未经固定负载基准和内网 SQL 计划验证，不得通过盲目增大批次、关闭依赖或改快照来宣称修复。
- **后续要求**：任何性能修复必须先按事实类型、来源表、批次耗时和锁等待建立可复现基准，再单独设计增量路径变更；修复前保持 ISSUE/MR 事实正确性、乱序 fencing、空来源删除和来源级 READY 门禁不变。
- **关联证据**：`docs/architecture.md` 事实与统计/性能章节；`docs/progress.md` 2026-09-14 条目；`docs/plans/fact-refresh-root-cause-documentation-20260916.md`；发布包清单 `D:/projects/data_collection_platform_deploy/qaflex-full-20260803T122027Z-ad35f6c0e8c3/RELEASE-MANIFEST.json`、`D:/projects/data_collection_platform_deploy/qaflex-update-20260810T065129Z-4ca35ca63f73/RELEASE-MANIFEST.json` 和 `D:/projects/data_collection_platform_deploy/qaflex-update-20260910T042253Z-94cd3a4d2a47/RELEASE-MANIFEST.json`。

## D-14 延期标签写回的就绪判据：来源级发布收敛，而非父子运行

- **状态**：2026-09-20 生效（用户批准方案并实施；本地真实链路已验证写回不再因本轮存在增量而跳过）。
- **背景**：写回前必须确认「本轮镜像增量已被事实发布」，否则标签差异会基于落后于镜像的 `issue_fact` 计算。该判据原先以 `sync_runs.parent_run_id` 查找镜像运行的 `FACT_REFRESH` 子运行（2026-07-06 建立时成立），但 2026-08-10 `c367258a` 起事实消费者改为来源级、不再绑定父镜像运行（提交时父运行编号恒为空），判据从此静默失效：只要本轮同步应用了增量（`applied_rows > 0`）就跳过整轮写回，只有零增量周期才会入队——表现为「越该写回越不写回」。缺陷因全局开关长期关闭、该路径无端点、相邻测试整体 mock 掉预写回结果而潜伏约六周。
- **选择**：判据改为来源级发布收敛——`source_instance + fact_type` 下不存在 `fact_change_heads.published_version < sync_run_fact_targets.change_version` 的目标，返回未发布目标数量；唯一读法收敛到 `SyncFactPublicationStateService.countUnpublishedTargets`，等待语义由「等事实子运行出现」改为「等目标发布收敛」，并删除原先 10 秒子运行出现窗口。事实族 BLOCKED 时其目标必然保持未发布，由收敛判据自然覆盖，不额外分支。
- **否决选项**：①把 `FACT_REFRESH` 重新绑定父镜像运行——逆反现行来源级消费者模型，属双轨；②在闸门内直接调用事实构建绕过发布门禁与来源互斥——破坏单一权威所有者；③关闭预写回同步开关绕过——会让写回基于陈旧标签发出多余写回并掩盖根因；④保留恒空的 `parentRunId` 形参与兼容重载——开发期不留死参数与兼容层。
- **同源清理**：删除 `submitFactRefresh` 的恒空父运行参数及其「按父运行复用/区分事实刷新」的分支（复用条件改为同互斥域内的活跃 `FACT_REFRESH`，符合来源级唯一消费者语义）；修复 `RealtimeWorkspaceRefreshProgressService` 中同样按 `parent_run_id` 关联事实子运行的失效联接——事实阶段状态统一由该镜像运行的发布栅栏 `sync_run_publication_fences` 决定，并删除因此不可达的事实运行身份字段与分支。
- **边界**：D-13 的「来源级 READY 门禁不变」继续有效；本决策不改变发布门禁本身，只改变写回前的等待判据。超时默认值不因本地观察调整（本地排队与发布耗时不构成内网容量结论），本次仅输出 `mirrorRunId`、未发布目标数、等待时长等测量日志，阈值待内网实测后单独定值。
- **后续要求**：任何「等待某子系统就绪」的判据都必须引用对应子系统的权威读法，禁止按运行间的父子/外键关系自行推断；跨子系统模型变更时必须同步搜索全部下游私有查询。
- **关联证据**：`docs/architecture.md` 事实与统计章节（写回前发布收敛判据）；`docs/platform-page-business-rules.md` 客户问题第 14 条；实现与测试 `CustomerIssueDelayPreWritebackSyncService`、`SyncFactPublicationStateServiceIntegrationTest`、`CustomerIssueDelayPreWritebackSyncServiceTest`；本地真实链路证据见 `docs/plans/delay-label-writeback-prewriteback-gate-fix-20260920.md`。

## D-15 客户问题延期重算的写入面：只写三列的定向更新

- **状态**：2026-09-20 生效（用户批准方案 A 并实施；本机真实链路全列 diff 已验证写入面边界）。
- **背景**：`refreshCustomerIssueDelayFactsForConfig` 的语义是「按当前时间重算三个延期布尔列」，但实现沿用了共用的整行 upsert，并连带替换客户成员关系、重写搜索列。其入参是从库中读入的记忆快照，必然落后于并发的事实发布；`issue_fact` 按自然键唯一、发布时删除旧行写入新行，因此旧快照的整行写入会静默回退已发布的其它列与成员关系、且发布状态已推进不会重发。三列本身被回退的影响还有一个额外出口：写回侧的延期标签比对会依据落后的布尔值发出错误写回。
- **选择**：延期重算改为只写 `is_response_delayed`、`response_overdue`、`is_resolve_delayed`、`updated_at` 四列的定向批量更新，按主键 `id` 定位；不再触碰其它列、`issue_fact_customer_members` 与搜索列。安全性来自「语句在结构上不具备覆盖其它列的能力」，不依赖任何运行期条件成立；事实目标替换后陈旧快照按 `id` 自然落空（影响 0 行），残留偏差由下一轮重算自愈。
- **否决选项**：①在共用 `batchUpsert` 上加版本守卫——该语句被事实构建、增量构建与事实发布共用，守卫不成立时发布会被**静默跳过**，比原缺陷更严重，且 `issue_fact` 无单调来源版本列，只能用墙上时钟近似；②追加时间戳 CAS 守卫——事实读取方法 `mapOpenCustomerIssue` 并未映射 fact 自身的 `updated_at`，且 `timestamp` 等值比较对精度与往返敏感，守卫不成立时是静默不更新，等于把「低概率瞬时偏差」换成更不可观测的「低概率漏更新」；③让延期重算只更新仍在 ODS 中存在的行为——不解决快照落后问题；④在延期路径内先重读整行再比较后写——仍需整行写能力，且把并发窗口进一步拉长。
- **边界**：三列的计算规则、议题范围（GITLAB / 来源实例 / project 325 / 未关闭 / `created_at_source >= 2026-01-01`）、共用 `batchUpsert` 的语义、事实构建与发布链路、写回协议均不变。若将来需要更强的写入保证，应在 `issue_fact` 上引入真正的单调来源版本列，而不是时间戳 CAS。
- **后续要求**：「从快照重算少量派生列」的路径一律不得复用面向权威发布的整行 upsert；新增此类重算时必须先确认写入面与快照新鲜度。
- **关联证据**：`docs/architecture.md` 事实与统计章节（延期重算写入面）；实现与测试 `FactBuildService.updateCustomerIssueDelayFlags`、`FactBuildServiceTest`（旧实现必红锚点）、`FactBuildServiceCustomerIssueDelayFlagsTest`（真库全列边界）；`docs/plans/customer-issue-delay-flag-narrow-update-20260920.md`。

## D-16 延期标签写回改为运行终态驱动的编排，并把异步执行器与调度线程池分离

- **状态**：2026-09-20 生效（用户批准方案并实施；默认参数真实链路已验证编排完成）。
- **背景**：延期标签写回在默认配置下**不可能完成**。`CustomerIssueDelayClosureScheduler` 的 `@Async` 方法解析到的执行器是 `ThreadPoolTaskScheduler`——应用没有显式异步执行器时它是上下文中唯一的 `TaskExecutor`——而调度池默认只有 1 个线程，于是"异步化"没有把工作从调度线程上摘下来。该方法在调度线程上轮询等待一个由同一线程池上的 `SyncRunDispatcherService` 派发的镜像运行，构成自锁：实测 `TABLE_REFRESH` 运行在超时同一瞬间才被派发，派发被推迟整 180 秒，且同期应有的自动增量同步一次都没有触发；线程栈显示阻塞帧为 `CustomerIssueDelayPreWritebackSyncService.sleep ← waitForMirrorRun ← refreshBeforeWriteback ← CustomerIssueDelayClosureScheduler.refreshCustomerIssueDelayFacts`。更普遍的问题是设计层根因：跨子系统收敛用「轮询 + 任意超时」表达，180 秒阈值无测量依据，内网镜像一旦超过它，即便修好自锁也会整轮跳过写回。
- **选择**：①收敛等待改为**运行终态事件驱动**的三段编排（提交前置刷新 → 等来源级发布收敛 → 重算延期事实并登记写回候选），删除全部轮询、`POLL_INTERVAL_MS`、`sleep`、活动状态集合与超时配置；收敛判据仍是 `SyncFactPublicationStateService.countUnpublishedTargets` 的版本栅栏（D-14）。②运行终态事件从「仅镜像运行」扩到**全部运行类型**：事实刷新分支在 `finishRun` 得到终态后补一次发布，`publishRunCompletion` 去掉镜像守卫（枚举里除 `FACT_REFRESH` 外全是镜像类型，因此受影响面精确等于事实运行），订阅方各自按 `mirrorRun()` 或运行类型过滤，不为事实运行另立事件类型。③新增具名应用异步执行器 `platformAsyncExecutor`（`ThreadPoolTaskExecutor`，线程数按可用处理器收敛在 2～8，队列无界以维持"异步提交不拒绝"的既有语义），`RealtimeWorkspaceService` 的两处 `@Async` 显式指定到它，调度方法只做常量时间的移交。
- **否决选项**：①调大 `spring.task.scheduling.pool.size`——治标，取值无测量依据，派发器仍在同一池内与其它阻塞任务互相挤占；②把轮询等待搬进专属线程——保留任意超时与线程占用，镜像超时仍整轮跳过，属换姿势的补丁；③为事实运行新增独立事件类型——同一语义两套订阅面；④把延期写回并入镜像完成事件直接执行——绕开"写回前必须先增量刷新 issues/notes/label_links/labels"的业务规则，且没有事实收敛保证。
- **关键约束**：**登记先于评估**。收敛判据只有在事实发布登记已提交之后才权威，否则镜像增量刚应用、目标尚未登记时会读到"未发布目标为 0"而提前写回。顺序由 `SyncRunCompletionListenerOrder` 常量集中声明并标注在两个监听器上，另有测试锁定取值关系。本次真实链路已实证该约束生效：第二轮编排的镜像终态事件读到 `unpublishedTargets=45` 后继续等待，直到 `FACT_REFRESH` 运行终态才推进阶段 3。
- **与方案的两处偏差（按目标版本择优）**：①在飞状态按**数据源配置**而非来源实例索引——`GitlabSourceInstanceSupport.sourceInstanceOf` 当前是常量，按来源实例会让多个数据源共用一把锁而互相阻塞，而阶段 1 提交与阶段 3 副作用本就是逐配置的。②在飞周期的"跨周期"判定用**调度周期序号**而非墙上时钟——语义等价（编排只拥有一个周期）且不引入 Clock 依赖与时间精度问题。
- **边界**：不改写回判据、差异计算、队列协议、标签白名单、重试/租约与 worker；不改 `SyncRunDispatcherService` 的派发语义与周期；不新增表与迁移；不改端点产出。`RealtimeWorkspaceService` 只改线程归属，其业务语义与状态机不变；迁到专属池后理论并发上升，但刷新最终经 `submitTableRefresh/submitRun` 提交，同互斥域的活跃运行会被复用，真正的并行镜像不会增加。
- **遗留与观察**：①写回 worker 自身仍是 `@Scheduled` 内联执行 GitLab HTTP 调用，仍会短时占用调度线程——属既有形态、本次未纳入范围，内网若出现 worker 单次调用耗时过长需单独评估。②`BackupScheduler`、`GitlabCompensationScheduler` 等若未来引入长时间阻塞仍会占用调度线程，本次不扩大改动范围。
- **关联证据**：`docs/architecture.md` 事实与统计章节与「性能、迁移与发布不变量」；`docs/platform-page-business-rules.md` 客户问题第 14 条；实现与测试 `CustomerIssueDelayClosureOrchestrator`、`CustomerIssueDelayClosureScheduler`、`CustomerIssueDelayPreWritebackSyncService`、`PlatformAsyncConfiguration`、`SyncRunCompletionListenerOrder`、`SyncRunWorkerService`；`docs/plans/delay-writeback-scheduler-starvation-fix-20260920.md`。

## D-17 客户问题统计板的控制参数边界与需求身份派生列

- **状态**：2026-09-24 技术契约已按整批实施指令落地；S11最终验收进行中。R1—R4业务细化仍缺独立人工裁定留痕；实现记录值不等于业务签批。
- **背景**：客户维度统计板需要 `groupBy`（按客户 / 客户×模块 / 客户×功能）、客户/模块/功能的 `ALL|MISSING|VALUE` 精确成员选择与业务日等"控制参数"。这些参数既不能当成业务筛选字段被 `StatisticFilterGroupSupport` 忽略（否则合法性无法校验），也必须进入规范化请求、快照键与下钻规格（否则不同分组共用同一快照、跨日仍命中"今日"结果）。同时"需求身份"必须只来自当前有效标签的精确成员关系，不能由标题、描述、`reason_category` 或 `category` 的模糊包含推断。
- **选择**：①把 `groupBy`、`customer`、`module`、`function`、`businessDate`、`population`、`sourceVersion` 登记为控制参数，与业务筛选条件分离，并由看板显式写入快照负载；业务日进入快照键，使"今日"指标跨日自然失效。②新增可空派生列 `issue_fact.is_customer_requirement`，在事实构建阶段由 `CustomerRequirementRules` 从原始 `label_titles` 精确派生（`“需求” OR “类别：建议”`，非客户项目写 false），查询与 BI 一律消费该布尔值，不再解析标签展示串。迁移以 NULL 表示"尚未重建"，禁止用默认 false 伪造历史分类。
- **否决选项**：①把控制参数放在前端、主表与下钻各自保存——快照键与下钻规格会与主表脱节；②沿用 `contains("需求")` 的模糊标签判断或直接用 `SUGGESTION` 身份当需求——会把"需求如此""新增需求问题"等误纳入；③为未重建的历史数据填默认 false——把未知伪装成否定；④新建需求事实表或独立抓取任务——同一个标签事实不应有第二个权威来源。
- **关键约束（本轮自查发现的回归）**：`sourceInstance` **不是**控制参数。它是既有查询参数，由 `IssueFactQueryService.applyCommonFilters` 下推到 SQL；把它列入控制参数会让 `withoutReservedFilters` 剥离它，使既有统计板的来源实例筛选静默失效。任何新增控制参数都必须先搜索同名既有查询参数。
- **边界**：`is_customer_requirement` 只在客户项目上派生，不改变非客户议题的业务分类；D/S/N/L/E 五个集合的资格仍由各领域纯规则唯一实现，统计板不复制第二套判断；旧看板的既有排除语义与公式不变。
- **后续要求**：标签删除或改名必须沿原血缘派生更新该布尔值，不能只靠部署时回填；移除标签后按当前事实退出需求集合，不保留历史身份。完成目标来源重建并验证后，客户范围不允许剩余 NULL。
- **关联证据**：`docs/plans/customer-issue-customer-statistics-and-bi-20260922.md`（R1/R4 与 S02/S03/S05）；`scripts/contracts/fact-field-contract.md`；`docs/bi-dashboard/BI看板数据来源与计算口径核对表.md` CI-20；实现与测试 `CustomerRequirementRules`、`IssueFactMapper.batchUpsert`、`CustomerIssueFactQueryService`、`CustomerIssueCustomerStatisticsBoardService`、`CustomerIssueStatisticsCalculatorTest`、`StatisticFilterGroupSupportTest`。

## D-18 统计板完整来源身份与快照一致性

- **状态**：2026-10-14 随 D-26 改为完整读发布点；2026-09 的当前来源资格硬拒机制已被替代。
- **选择**：真实读取来源闭包由服务端目录确定，页面持有公开完整发布点、冻结定义、业务日及规则身份。统计缓存按该组合区分；同一主表、控制候选、明细、规则与下载不能从当前可变事实补数据。读取在短 REPEATABLE READ 事务内持版本共享锁，回收跳过持锁或被活跃页面引用的版本。
- **发布**：候选预热写 PREPARED，真实持久产物证明与完整候选统一发布后才提升 READY；任务 SUCCESS 不能代替完整产物。同期失败、中止、未发布根或全量重建不会移动公开指针。
- **边界**：已有完整读点继续可读，生产 qualification 只控制生产资格、接管与状态披露；从未有合格完整产出时明确拒绝。不得把零值、候选缓存或当前原表认证成完整历史。已有统计公式、集合语义和黄金快照不据此改写。
- **证据**：读发布模型、业务真实预热、统计一致性边界和完整读取锁回归；最终部署/黄金门禁状态见进度文档。

## D-19 公共下钻契约的集合、来源一致性与维度控制参数

- **状态**：2026-09-22 生效（方案 S06/S07 实施并通过定向与前端全量测试）。第四轮起 ④ 的成员候选来源与缺失表达被更正：候选改由 `control-options` 端点在同一窄事实的完整基础范围上求解，缺失改用"类型 + 值"表达（见下"更正"）。浏览器验收已于第四轮在隔离测试库实例完成；黄金快照重建仍待授权执行。
- **背景**：下钻能力过去由"后端 `drilldown` 布尔 + 前端 `numericValue > 0` 推断"两处共同决定，因此 0 值单元格在旧板不可点击、在新板又需要可点击以解释空集合。明细响应也没有表达"这行属于哪个集合"，比率与周期的分子/分母/样本无法分别查看。同时主表与下钻之间没有来源代际绑定：主表读到版本 A 后来源推进到版本 B，下钻会用版本 B 的事实回答版本 A 单元格的语义。新板的行维度与成员选择又必须先于快照键确定才能保证不同分组不共用同一份结果。
- **选择**：①`StatisticCellData.drilldown` 成为唯一最终能力，旧板在原生产端用历史有效条件（声明为可下钻且数值大于 0）显式写入该值，前端两处数值推断删除，不新增任何 `boardKey` 特例。②`StatisticDetailResponse` 增类型化集合列表与当前集合（数量 `COUNTED`、比率 `NUMERATOR`/`DENOMINATOR`、周期 `SAMPLE`），空集合仍返回列定义、说明与 `total=0`，删除旧 9 参构造器并同步全部构造点，不保留空构造兼容层。③主表单元格携带行身份、业务日、来源选择与完整读身份；明细读取该身份保留的历史修订，不以当前 generation 前移拒绝旧下钻。主表、候选与已打开明细整批准备后换版，失败保留原完整画面；跨页下钻独立核验目标权限及来源包含关系，并复制父完整点到独立租约。④行维度与成员选择登记为控制参数（D-17），由页面配置声明、随 URL 往返、切换时失效明细键并回到第一页；成员候选的最终实现见本节"更正"。⑤范围目录已加载但没有启用项时显示目录状态并阻止取数，不再按"全部里程碑/阶段"放大范围。
- **否决选项**：①在前端按 `boardKey` 判断新板是否可点击——同一能力两套判断；②保留旧 9 参构造器并补空集合——为废弃签名留兼容层；③用空列表代替明确拒绝来回答过期来源的下钻——把两个代际的读取混在一起且无法察觉；④在明细请求中重新计算集合而不携带来源版本——下钻与主表可能分属不同版本；⑤**（第四轮更正）**把候选做成独立成员表或枚举——候选是同一窄事实在完整基础范围上的一次查询结果，不是第二套成员权威源；当时的否决理由是"当前响应行已携带稳定身份"，该理由不成立（行身份只覆盖结果行，筛掉成员后其余成员不可选）；⑥无启用目录时按全部范围返回数据——把"没有范围"当成"范围是全部"；⑦**（第五轮）**用更长的哨兵区分真实同名成员——同一参数承载两种语义，无论换多长的哨兵都存在相撞的成员名。
- **更正（第四轮 Q4 / 第五轮 R5-2，取代原 ④ 的成员候选与缺失表达）**：①候选来源改为 `GET /api/statistic-boards/{boardKey}/control-options`，在固定完整读身份边界内按"完整基础范围"（项目 325、创建下限、来源实例、生效里程碑成员 + `StatisticFilterEngine` 用户条件，成员选择一律 `all()`）求解，返回 `{scopeKey, sourceVersion, scopeReadable, reason, groups[]}`；候选只在范围签名或主表来源代际变化时重取，翻页与切维度不产生额外请求；来源资格失败或请求异常必须报因而不得渲染成"该范围没有成员"。②缺失与真实成员用"类型 + 值"表达：候选项 `StatisticBoardControlOption{kind, value, label}`（`MISSING` 时 `value` 恒为空串），请求参数分 `customer/module/function`（成员名）与 `customerKind/moduleKind/functionKind`（类型），`putSelection`/`parseSelection` 互为逆运算并写入主表 `detailParams` 与快照键，非法 `kind` 直接拒绝；真实成员名等于 `__missing__` 或等于"未标注…"文案时与缺失是两个可分辨候选项，既不被跳过也不折算成缺失。原 `missingSelectionValue` 哨兵与 `ambiguousMemberCount` 上报已删除，不保留双轨。③候选与主表必须同代际且**无论谁先返回都要核对**：主表先返回时，迟到的旧代际候选在到达时被丢弃并按当前代际重取一次，同一代际不重复重取，长期不一致明确报不可用；主表、明细、候选三条加载路径均以请求序号丢弃迟到响应，明细关闭或失效时作废在途请求，快速筛选变更只产生一次请求（页码已在第一页时自行发起，否则只写路由由监听统一重取）。
- **关键约束（本轮自查发现的缺陷）**：明细行成员校验必须在集合筛选**之前**、且在完整范围上进行，否则 0 数量、零分母、零样本等合法空集合会被误判为"该行不存在"而报错；组合维度（客户×模块/功能）必须按同一议题整体校验，成员各自存在但组合不存在仍属伪造行。
- **边界**：不改既有看板的计算口径与详情列；不新增缓存表或线程；空集合的判读统一由明细解释，主表数值仍按各自编码表达（0 与 null 区分不变）。
- **后续要求**：`PagePermissionKeyResolver` 新增 boardKey 时必须在同一工作单元补权限目录迁移；迁移会在下次启动/部署时生效，届时 `permission-settings` 相关黄金快照按授权流程重建。S07 的浏览器验收已于第四轮在隔离测试库实例完成（`qaflex_uat` + 独立端口 + 外部 Chrome，不经 API/mock 替代），发现并修复了 `StatisticBoardView.vue` 模板重复属性导致整页加载失败的真实缺陷。
- **关联证据**：`docs/architecture.md` 事实与统计章节；`docs/platform-page-business-rules.md` 客户问题统计页；实现 `StatisticDrilldownSupport`、`StatisticDetailCollection`、`StatisticDetailResponse`、`CustomerIssueCustomerStatisticsBoardService.doLoadDetail`、`useStatisticBoardControlParams`、`resolveStatisticBoardRouteScopeState`、`StatisticBoardControlBar`、`PagePermissionKeyResolver`、`V20260922_02`；测试 `CustomerIssueCustomerStatisticsDetailContractTest`、`StatisticDrilldownSupportTest`、`CustomerIssueCustomerStatisticsWiringTest`、`useStatisticBoardControlParams`、`StatisticBoardControlBar`、`statistic-board-route-scope`、五个看板 Golden Master。

## D-20 修复完成时间只由已核验的精确标签事件派生

- **状态**：2026-09-24 按客户问题整批实施指令完成代码实施；自动事实链回归通过。目标环境事件历史完整度与必要ISSUE重建仍待上线前验收，不能由测试克隆库状态替代。
- **背景**：共享`issue_fact.fixed_label_time`原先还会回退到`label_links`关联时间；只发生标签事件变化也不保证形成ISSUE根目标。关联时间不能代表真实完成加标时间，来源缺失与单议题无事件也不能合并为同一状态。
- **选择**：①当前状态精确含“已修复/完成”成员时，只从已全量核验的`ods_gitlab_resource_label_events`有效add事件取最大`created_at`；当前状态不再含成员则清空；删除`label_links`时间回退和两套SQL选择路径。②来源完整资格与单议题无对应事件的合法`null`分开：来源未完成全量核验或本轮依赖未终态则阻止ISSUE事实发布；不以建表/页面成功代替完整。③事件变更从before/after议题根取并集，标签改名/删除反查当前关联与历史事件关联，复用现有ODS事务、outbox、目标解析和投影发布；MR保留原信号补读，不引入MR修复时间。④旧ID修改、低ID补录及物理删除由现有补偿/删除对账处理，不新增定时全量刷新。
- **影响**：这是共享ISSUE事实来源契约变更；客户问题“今日解决”、BI日修复和客户响应/解决效率都消费该字段。合规事件数据值预期一致；依赖旧回退或非精确状态的旧时间会被清正，属于需披露的有意产出变化。发布前须核对目标环境事件覆盖、影响范围并完成必要ISSUE事实重建；本轮不做真实来源同步或重建。
- **验证证据**：`CustomerIssueRequirementLabelAutoChainIntegrationTest`在真实PostgreSQL覆盖加标、撤销、重加和相关归属变化；`SyncFactPublicationStateServiceIntegrationTest`锁定来源级完整性和发布资格；`GitlabSourceSchemaGuardTest`验证ISSUE来源不依赖`label_links.created_at/updated_at`。最终全量回归状态以活动计划记录。
- **关联文档**：`docs/plans/data-10-customer-issue-fixed-time-20260920.md`（S1/S2实施状态）；`docs/platform-page-business-rules.md`第5节；`docs/bi-dashboard/BI看板数据来源与计算口径核对表.md` CI-19、CI-46。

## D-21 响应延期豁免：回复模板优先，响应模板须带合法计划解决时间

- **状态**：2026-09-28 完成代码实施、定向回归、后端默认快速套件（1552 项全绿）、整体回归（后端 1552/0/0/1、前端 146 文件 677/680——3 项失败经受控还原实验归属数据库浏览器与平台评审整改单元的进行中改动、13 项仓库护栏与 `git diff --check` exit 0）、真实链路验证（隔离克隆库 + 本地 GitLab：875 条在辖议题实测加延期 70、转豁免 30，与预测一致；主路径全量重建与周期重算逐行一致，剔除延期列后的全行哈希零变化）与**本地真实写回**（恰好 70 加/30 摘，写前写后逐条比对 PASS，复跑 `enqueued=0` 不反复增删）；“领导统一格式”口径（见下）已并入本决策。黄金基线门禁已于 2026-09-28 经授权跑比对模式（196/19 失败/5 缺失）：本决策直接产出 8 个快照差异（延期诊断计数、延期记录页与导出、候选作者、延期专题规则版本与看板数据/导出），其余差异与缺失快照归属其他单元已提交但未重建的产物，重建须按产出门禁纪律另获授权。内网真实写回与黄金快照仍待按上线流程单独授权；写回前须先停老平台写回并核对 GitLab token 有效期（本地已实测 token 过期会 401 且读链路无感，核对清单见 `deploy/runbooks/gitlab-sync-orchestrator-runbook.md`）。
- **背景**：原口径下“评论区出现响应模板表头即认为已响应”，导致大量只填充表头、不填写计划解决时间的议题被豁免。领导 2026-09-28 明确：①响应模板缺少计划解决时间仍应打“响应已延期”；②“计划解决时间”的合法格式以 wiki（规则总表 5.4/11.4）为唯一标准，凡不符合即视为未写。另用户 2026-09-20/09-23 要求“按回复模板回复的同样不打”。两种模板在 wiki 中原本分属响应侧（响应模板）与解决侧（回复模板的“缺陷原因分析内容”）。
- **选择**：豁免判据改为 `存在回复模板 ∨ (存在响应模板 ∧ 计划解决时间合法)`，由 `IssueSlaRules` 单点派生、事实构建与周期重算共用。①**回复模板优先**：存在回复模板即豁免，不再检查响应模板的计划解决时间；不引入“按最新模板类型分流”（本地实测该分流只影响 2 条，且无任何平台先例）。②**格式同源**：合法性直接复用非法模板判定的 `IssueResponsePlanFieldRules.parsePlannedResolutionAt`，不新增第二套解析，避免“非法页判非法、延期页判已响应”的分叉。③**取值方向**：按最新一份响应模板，不可解析时回退更早模板，与老平台 `IssueServiceImpl.getPlanSolutionTime` 一致（其非法校验还把“没填”与“填错”分成两类文案）。④`has_response`、`research_template_time`、`fix_user` 与响应效率指标全部不动：它们表达“是否按响应模板回复过”和“第一条模板时间”，与“是否豁免延期”不是同一事实。
- **影响**：只影响项目 325 的 open 客户问题（`is_response_delayed`/`response_overdue` 受 `openCustomerIssue` 限制），解决延期、响应效率、修复人、其他项目输出不变。开发库样本（875 条）实测：**加标 70、摘标 30**；摘标是本功能首次真实执行的反方向写回。受影响缓存按规则版本失效：`customer-issue-delay-issues@2026-09-28-v6`、`customer-issue-records@2026-09-28-v9`（CC_PRODUCT议题）、`customer-issue-records@2026-09-28-v5`（延期专题）、`customer-issue-customer-statistics@2026-09-28-v2`。有意产出变化：延期看板、记录页/导出、按功能展示、客户维度统计的 `resp_delay_*`、`/api/facts/issue-diagnostics`。
- **前置条件**：老平台后端仍在运行期内每小时无条件写回 `响应已延期`/`解决已延期`（`CCProductScheduleTask` → `IssueServiceImpl.updateIssueDelayLabels` → 覆盖式 `setIssueLabels`），且其判据更宽（完全不看计划解决时间），因此新判据上线前必须先停掉老平台写回，否则两边互相覆盖、反复增删。该写回无运行期开关，只能改代码重新部署或停进程。
- **验证证据**：`IssueFactNormalizationRulesTest`（行为表 5 行、回退、wiki 白名单通过/不通过各类写法）、`IssueResponseTemplateParserTest`（与严格判据同源断言）、`FactBuildServiceCustomerIssueDelayFlagsTest`（false→true 加标、true→false 清标、无变化不写三列）、`CustomerIssueDelayRefreshScopeVersionTest`、受影响看板/记录页测试；真实链路证据见 `docs/plans/response-delay-dual-template-rule-20260920.md`（隔离克隆库，含逐行判定与全行哈希核对）。最终全量回归以活动计划记录为准。
- **关联文档**：`docs/plans/response-delay-dual-template-rule-20260920.md`；`docs/platform-page-business-rules.md` 5.3 第 4～5 条与 5.4、11.4；`docs/architecture.md`“事实与统计”。

## D-22 权限基础设施两条口径：程序化鉴权拒绝映射 403、默认授权必须与角色授权同批登记

- **状态**：2026-09-28 生效（BI 按页面拆分权限单元同批实施，见 `docs/bi-dashboard/decisions.md` D-04）。
- **背景**：①平台原有的页面级鉴权只有拦截器写响应一种形态（`PlatformAuthorizationInterceptor` 直接写 403/A0303）；`PlatformPermissionService.requirePermission` 与 `ReviewDataAuthorizationService` 以 Spring `AccessDeniedException` 表达「已登录但无权」，但 `GlobalExceptionHandler` 只有 `BizException`/参数类/`DataAccessException`/通用兜底，该异常会落入 `Exception` 兜底变成 500「服务处理异常」——与对外契约「无权限 403」不符，且让按请求体做程序化鉴权的端点无法返回正确状态码。②`V20260922_02` 只写 `platform_role_permissions` 未写 `platform_default_role_permissions`，而 `restoreDefaultPermissions` 是「清空角色授权 → 从 defaults 重播种」，缺 defaults 会让该权限在点「恢复默认权限」后从所有角色（含管理员）消失。
- **选择**：①在 `GlobalExceptionHandler` 显式映射 `AccessDeniedException` → 403 + `A0303`，消息取异常自带说明、为空时回落到「当前账号无权执行该操作」，与拦截器及 `platformAccessDeniedHandler` 同口径（`ReviewDataAuthorizationService` 的「只能删除本人创建的评审数据」等具体文案得以保留）；程序化鉴权一律用它表达拒绝，不新造异常类型。②权限类迁移必须同时登记 `platform_permissions`、`platform_role_permissions` 与 `platform_default_role_permissions`；其中 defaults 表达的是**该权限的平台默认口径，不是「当前持有角色」**——新权限的默认口径默认全员可见，故 defaults 写平台托管五角色全量，角色授权则按现状复制以保留既有定制（含上线期临时隐藏）。把「当前持有」抄进 defaults 会把临时隐藏固化成默认，使「恢复默认权限」无法回到全员可见。本单元同批补齐 `customer_issue.customer.view/export` 的 defaults（沿用其现有角色授权集合，使「恢复默认权限」回到实际默认口径而非清空），并以 `V20260929_01` 归位 BI 14 个页面码的 defaults。
- **影响**：评审删除等既有程序化鉴权路径的失败状态码由 500 变为 403（文案不变或更具体）；BI 下载端点在注解粗门禁之外可按请求体 `pageKey` 精确校验并稳定返回 403。不改变任何权限目录内容与角色授权本身。
- **验证证据**：`GlobalExceptionHandlerTest`（403/A0303 与空消息回落），`BiDownloadPagePermissionTest`（无权限 403、未登记 pageKey 400、齐备 200），BI 权限两个迁移在隔离库三场景验证（默认五角色全量、内网定制保留且 defaults 不漂移、defaults 缺口修正；见 `docs/progress.md`），`PermissionSettingsController` 相关快照在重建后于黄金门禁复验。
- **关联文档**：`docs/architecture.md`“认证与权限”；`docs/bi-dashboard/decisions.md` D-04；`docs/plans/bi-dashboard-permission-split-20260928.md`。

## D-23 延期标签写回的唯一开关：数据源页面开关，删除部署级全局开关

- **状态**：2026-09-29 生效（用户选定方案 A 并实施；业务规则第 14 条、架构文档写回段、runbook 与内网打包脚本同批更新）。
- **背景**：写回原为两层门禁——镜像设置页的数据源开关 `delay_label_writeback_enabled`（默认关闭）加上部署级 `platform.gitlab-mirror.delay-label-writeback-api-enabled`（`application.yml` 默认 false，内网离线打包脚本把它钉进 `.env` 与 compose）。用户在页面打开开关后仍不会写回，且页面文案未提示还有部署级开关，属「打开了却静默空转」的误导（也是该路径长期未在真实环境执行的原因之一，见 `docs/plans/delay-label-writeback-prewriteback-gate-fix-20260920.md`）。
- **选择**：①写回只由数据源开关唯一控制（页面开关 ∧ `web_base_url` ∧ `api_token`），删除全局开关层（配置项、`@Value` 注入、打包脚本钉值与相关测试属性一并删除，不留兼容读取）。②默认关闭不变（库列默认、创建/保存/读取归一化、页面初值均为 false）。③开关两个方向都给确认提示：开启方向说明会真实调用 GitLab API 增删延期标签、并在未填写 Web 地址时补充「保存后仍不会写回」；关闭方向说明仍监控延期事实但不再写标签、已排队任务会被跳过；开启状态下设置页常驻醒目说明。④应急停止手段保持可用且都不依赖前端：改库关闭开关（worker 执行前复核，立即生效，无需重启）、清空 `api_token`／`web_base_url`、`CUSTOMER_ISSUE_DELAY_WRITEBACK_WORKER_ENABLED=false` 或 `platform.gitlab-mirror.scheduler-enabled=false` 后重启、以及在 GitLab 侧撤销令牌（写回 401 转 `DEAD`，标签零改动）。
- **影响**：内网若在页面打开该开关，将真实写入 `web_base_url`／`api_token` 指向的 GitLab 实例（此前被全局开关拦下）；`GET/POST/PUT /api/gitlab-sync/config(s)` 的字段与语义不变，无端点产出变化。写回判据、差异计算、队列协议、重试/租约与编排流程均未改动。
- **否决选项**：①保留全局开关但把内网默认改为 true——多一层需解释的配置，其停止手段（改 env 重启）并不比改库更快；②保留两级仅补页面文案——与「页面开关为唯一控制」的要求冲突；③为已删除的全局开关保留兼容读取或默认 true 的过渡态——开发期不留双轨。
- **验证证据**：`CustomerIssueDelayLabelWritebackServiceTest`（唯一开关语义：开关关/空、地址空、令牌空、配置缺失各拒绝，三者齐备才允许）、`CustomerIssueDelayLabelWritebackPlannerTest`／`...QueueServiceTest`（构造器同步）、前端 `delay-writeback-switch-prompt.test.ts` 与 `mirror-settings-writeback-switch.test.ts`（两向确认文案、取消开启回退、开启后常驻警示）；全量结果见 `docs/progress.md` 本轮条目与 `docs/plans/delay-writeback-single-switch-20260929.md`。
- **关联文档**：`docs/platform-page-business-rules.md` 第 14 条；`docs/architecture.md`“事实与统计”；`deploy/runbooks/gitlab-sync-orchestrator-runbook.md`（凭证预检与应急停止）；`docs/plans/delay-writeback-single-switch-20260929.md`。历史计划中出现的「全局开关」表述（`docs/plans/delay-*-20260920.md`、`docs/plans/response-delay-dual-template-rule-20260920.md`）是当时的事实记录，不再代表现状。

## D-24 事实发布控制面单一权威化：`fact_change_heads` 版本栅栏，`sync_run_fact_targets` 降级为审计日志

- **状态**：2026-09-30 实施完成（用户批准方案并指派实施，方案 `docs/plans/fact-publication-authoritative-rework-20260930.md`）。本地正确性、五项仓库门禁与本地性能冒烟已完成；黄金基线 compare、内网升级与 S0 基准复测按发布流程另行授权。
- **背景**：一轮增量 200 行真实变动被放大为 3.4 万级事实目标（内网 INTEGRATION_TEST 34,209 待处理），`sync_run_fact_targets` 长到 26 GB，门控计数在 26 GB 上扫描使刷新期间统计页全空。根因是单一设计错误：**按轮累积的审计日志被提升为控制面**——待发布计数、领取、结算、归属状态机与失败释放全压在这张无界增长的表上；而权威待发布信号 `fact_change_heads`（每根每事实族一行、有界、`greatest()` 单调推进）早已存在。
- **选择**：
  ① **唯一控制面**：未发布即 `published_version < latest_change_version`，走 `fact_change_heads` 部分索引；领取按 `root_id` 稳定序 limit 批大小；结算取 `greatest(published_version, least(latest_change_version, 覆盖版本))`。D-26 在候选开启时固定根集合及逐根水位，统一完整发布时逐根结算；构建期间更高变化继续待发布，不能用构建结束时的新上界提前结算。
  ② **领取记录落 `fact_build_task_roots`**：领取查询排除在途归属，避免同批根重复派发。D-26 下构建成功批次的归属保留到整个候选发布/中止，零工作且不属于捕获输入的批次即时释放；失败候选清理后由后续运行重新领取。`releaseFailedFactAssignments`、QUEUED/PENDING 私有归属状态机与 `assigned_*` 旧读写已删除，未发布根权威仍是 heads，候选固定输入不取代它。
  ③ **发布围栏一并迁移**：`SyncRunPublicationFenceService` 的水位、项目维度待发布判定与"已发布→投影任务归属"三处原读 targets，改读 heads；`fact_change_heads` 增 `project_id`，`registerBatch` 以 `coalesce` 写入避免置空。
  ④ **日志降级**：`sync_run_fact_targets` 只按轮插入登记事实（同键再登记只刷新本轮登记事实，不再写状态列），新增 30 天窗口归档 job；判据 = 超过保留窗口 ∧ 该根版本栅栏已发布到不低于该行版本。刻意不以 `publication_status` 为条件——日志降级后没有代码再改写它，以它为条件会让新写入的行永不可归档。
  ⑤ **字段级血缘**（源头止血）：`GitlabSourceLineageCatalog` 每张表显式声明 `factRelevantColumns`，`GitlabFactChangeResolver` 按变化列求交，维表非事实列变化不再反向展开根；主键、关联键、删除/恢复恒为相关。
  ⑥ **生产资格与读取分离**：`qualification` 保留生产控制与接管的 refused/settling/ready 判据；公开读路径依据 D-26 已发布身份查询历史事实及数据集，refused 不再关闭已有完整产出的读取。GitLab 镜像实际逐页写当前表，终态或零计数不能证明内容未改动，不实施无证据 BLOCKED→READY；真实完整依赖核验后恢复资格。
- **影响**：无新增对外端点。`/api/statistic-boards/*` 与 BI 读接口响应新增两个**仅在降级窗口出现**的可选字段（正常态字段缺省，黄金快照预期零差异）。上线后**有意产出变化**：刷新窗口内统计页不再全空，改为展示上一完整发布点并披露待更新数量。内网首次升级后 `sync_run_fact_targets` 由归档 job 按语义安全条件收敛，不做手工 DML，也不对 26 GB 表做 drop 或高风险 DDL。
- **不变量**（测试锁定）：heads 版本单调不回退（`greatest`）；每根发布原子；乱序 fencing；空来源删除；来源级 READY 门禁；写回收敛判据（D-14）口径不变。
- **验证证据**：`FactBuildTaskServiceTest`（单根只派发一个批次、在途领取不被重复派发、失败运行收敛后接管）、`FactChangeTargetServiceIntegrationTest`、`FactTargetPublicationServiceIntegrationTest`（含全量结算与租约丢失回滚）、`SyncRunPublicationFenceServiceIntegrationTest`（含"已发布但版本脱节仍计待发布"）、`SyncFactPublicationStateServiceIntegrationTest`、`StatisticBoardSourceQualificationTest`（降级退回上一完整发布点；无完整发布点则拒绝）、`SourceFreshnessJsonContractTest`、`GitlabFactIssueSourceRootScopeEquivalenceTest`（全量 vs 根集合逐列一致）、`GitlabFactChangeResolverRelevantColumnsTest`、`FactTargetJournalArchiveServiceIntegrationTest`，以及链级 `CustomerIssueRequirementLabelAutoChainIntegrationTest` 与 `IncrementalDeleteTargetedPublicationIntegrationTest`；五项仓库门禁与全量快速套件结果见 `docs/progress.md`。黄金基线 compare 与内网 S0 基准复测待授权。
- **关联文档**：`docs/architecture.md`"事实与统计"；`docs/plans/fact-publication-authoritative-rework-20260930.md`；诊断脚本 `scripts/diagnose-fact-refresh-backlog.sql`；前置决策 D-13（版本归因与边界）、D-14（写回就绪判据）、D-18（快照读一致性）。

## D-25 业务日收敛为单一可注入来源，事实来源"全部/按根"双入口

- **状态**：已接受 / 已实施（2026-10-08，黄金门禁三项差分收口单元）。
- **背景**：2026-10-08 黄金基线 compare 出现两条"墙钟日期漂移"差异（客户统计板与客户问题页的 `businessDate` 及按日趋势窗口末端随运行当日变化），用户裁定"因日期不同而红肯定是不对的"。同批还暴露一条真实回归：全量合并请求事实构建把空根集合传给"按根收窄"装载器而被当成空结果，静默发布零条提交关系并清空 `merge_request_commit_fact`。
- **选择**：
  ① **业务日单一来源**：业务日（统计范围末端与快照业务日标记）统一由 `BusinessDateProvider` 提供；2026-10-15 R2 补正后，生产实现消费可注入平台 Clock 的瞬间并显式按 `Asia/Shanghai` 取当日，不依赖主机默认时区。禁止各处分别 `select current_date` 或 `LocalDate.now()` 取日（原三处已收敛：客户问题 BI 页、客户统计板默认控制参数、业务日参数缺省分支）。
  ② **可重复链路可注入固定业务日**：黄金基线链路以测试配置注入固定业务日，取值 = `backend/src/test/resources/golden-baseline/baseline-manifest.json` 的 `frozen_at` 本地日期（随夹具版本自更新，不散落常量）；生产语义不变。
  ③ **"全部"与"按根收窄"必须是两个显式入口**：事实来源 SQL 以 `xxxSourceSql()`（全量）与 `xxxSourceSqlForRoots(rootIds)`（按根）区分，按根入口空集合抛异常；禁止用空集合同时表达两种语义（对齐 ISSUE 侧既有双入口形态）。
- **理由**：同一"当日"分散取自库时钟与 Java 时钟会让口径漂移并使产出快照随日历变红；把业务日收敛到单一可注入来源，既保持生产语义又让回归基线稳定。"空集合=全部"是隐式通配，一旦被改为"空集合=空结果"就静默断供且难以察觉，故"全部"必须是一等入口。
- **影响**：无对外 API 与表结构变化。生产业务日语义不变（仍为当日）；黄金链路快照的业务日与按日窗口固定为夹具冻结日。
- **验证证据**：`BiCustomerIssuePageServiceTest`、`CustomerIssueCustomerStatisticsBoardServiceTest`、`CustomerIssueCustomerStatisticsDetailContractTest`、`FactBuildServiceTest`（全量路径发未收窄查询、按根路径含 `target_roots`）、`GitlabFactMergeRequestCommitSourceRootScopeEquivalenceTest`（真实 PG 全量 vs 按根逐行一致、空根抛异常）；黄金门禁 compare 收口至 196/0/0，全量默认快速套件 1681/0/0。
- **关联文档**：`docs/decisions.md` D-08（黄金基线变更规则）、`docs/decisions.md` D-24（事实发布控制面）；`docs/architecture.md`"黄金基线回归测试"。

## D-26 完整读发布模型：行修订、数据集与原子发布

- **状态**：2026-10-14 P1–P5 实现与 P6 本地验证收口，完整默认和最后检查见进度文档；生产迁移/接管、黄金新增用例及真实目标容量未执行，当前不宣称已部署或可直接发布。
- **背景**：平台只保存“当前事实”，没有可寻址的历史完整版本，读侧一旦遇到未收敛的同步窗口就只能拒绝读取或退化为最近 READY 快照（`StatisticBoardSnapshotService:257-259` 对 `refused` 直接抛错不回退），用户会在同步期间看到“无数据/正在处理”。根因是读取可用性与数据新鲜度耦合在同一份“当前事实”上。
- **选择**：
  ① **物理模型**：事实行带修订身份与半开可见区间（`publication_epoch_id`/`created_by_publication_id`/`visible_from`/`visible_to`，物理 `id` 即修订身份），新增 `read_publication_domains`（当前指针 + 候选序号）、`read_publications`（持久发布身份，`BUILDING→PROJECTING→READY_TO_PUBLISH→PUBLISHED`，失败 `ABORTED`，回收 `RETIRING/RETIRED`）、`read_publication_fact_roots`（完整业务身份的修订/删除证明）、`read_publication_root_inputs`（整轮固定根集合、逐根水位与批次完成）、`read_publication_scope_generations`（候选投影身份及完成证明）、`read_definition_snapshots`（不可变定义）、`read_view_contexts`/`read_view_publications`（页面固定多域组合与租约）。
  ② **可见性单点**：公开读只接受 `PUBLISHED`（含仍在服务的 `RETIRING`），谓词为“代际相等 ∧ `visible_from ≤ S` ∧ (`visible_to` 为空或 `S < visible_to`)”；候选读由 `FactReadVisibility.visibleFacts` 单点合成——“基线可见排除候选触及根 UNION ALL 候选新修订”，且候选开启新 epoch（首次/全量）时基线恒不可见、只读本候选，判断不下发到各业务查询者。公开入口不构造候选上下文。根排除按完整业务身份（来源系统 + 来源实例 + 事实类型 + 项目 + 根号）匹配。
  ③ **原子发布**：单事务内校验候选状态与生产者租约、域基线 CAS、分配保留序号、关闭旧代际与受影响旧修订、打开候选新修订、提交范围 generation、置 `PUBLISHED`、推进域指针；任一步失败整体回滚，指针不移动。
  ④ **读取与回收锁序**：读取按 `publication_id` 序取 `FOR KEY SHARE`；续约先锁版本再锁视图。回收对初选对象显式 `FOR UPDATE SKIP LOCKED` 取排他锁后重新核验活跃引用与回收资格，不得仅凭锁前快照回收；普通状态 UPDATE 不能替代。锁不到的版本排除出回收集合。只删不覆盖任何保留序号的修订，保留集合为除本轮实际回收对象外的全部版本。先按精确父修订清子关系、再删父行，同批、同事务完成。
  ⑤ **初始版本接管**：仅用于安装/回填，在配置/控制证据/目标事实及关系的 `SHARE ROW EXCLUSIVE NOWAIT` 稳定输入栅栏内核验与采纳，普通查询继续。已有写入时保存点回滚已取锁，不破坏外层事务，返回不可接管。默认适配器要求 READY 非全量发布点、无未发布 heads 与在飞构建任务，并逐配置复用 `GitlabFactDependencyCatalog` 与 `familyReady` 核验全部必需依赖（含条件性 MR 提交增强表），缺项拒绝。不能把查询当时无任务当持续无生产的保证。不可采纳不写、不创建空域；栅栏不得用于日常同步或跨网络/用户等待，安装容量/WAL/耗时在目标负载另验。
  ⑥ **整轮候选与统一结算**：同一父 FACT_REFRESH 的同域增量批次共享首次 SQL 快照固定的根集合和逐根水位；其它运行/模式让位。未完成根发生输入漂移则整体中止，不收窄水位或发布子集。批次 SUCCESS 只代表构建成功；全部事实进入 PROJECTING，必需投影针对本发布/封印/定义/generation 提供完成证明后，唯一 `publishPreparedCandidate` 校验当前父运行租约，同事务提交事实区间、域指针、逐根 heads、全量意图修订 CAS、范围 generation 与页面栅栏。较新变化保持待发布。
  ⑦ **失败恢复与锁边界**：任务持久绑定候选。批次回滚后的中止在域锁内重查任务和父运行令牌，任务排他锁使用 SKIP LOCKED，不持域锁等待批次共享任务锁而形成逆序死锁；后续巡检收口未中止候选。已终态父运行释放候选；全量重试先逻辑中止上次半成品。事实中止在同一事务提交 ABORTED、原因及 `abort_cleanup_pending`，并释放投影、输入暂存与根归属；未公开修订的物理删除交由既有维护调度独立执行，每次事务最多 `gcBatchSize` 个精确父修订及其子关系（默认 500），按域→版本锁序且忙碌域让位，失败仅回滚本批，重启后从持久标识与剩余修订继续；清空后才撤销标识。ABORTED 修订不公开、不占活动候选名额。原始构建/发布异常始终为主异常，逻辑中止的二次异常作为 suppressed 并带发布身份记录，不能覆盖首错；不把失败改记成功或把失败父运行候选交给独立发布。数据集中止保持既有捕获清理契约，历史已发布修订仍按 P1 回收规则保留。人工继续保持原选择，不能闭合固定集合时显式 PARTIAL_SUCCESS 并释放候选；等待真实投影的运行保持 PAUSED。
  ⑧ **旁路、兼容与定义**：旁路 REPEATABLE READ 固定输入，GitLab 在途镜像或完整依赖不足时让位。延期重算按数据库行类型复制事实及父修订成员，只改三列延期判定，不丢 null 或其它业务字段。正式导入按来源实例建立 COMPAT_REVIEW 独立域，首次定向请求先产出整个来源完整集，成熟接管需成功正式导入正证据；兼容范围 generation 不修改 GitLab 命名空间。候选冻结计算/视图定义；基线按实际依赖比较：ISSUE 使用词典/客户别名/阶段起点，MR 仅词典，集成测试使用词典/阶段起止区间。计算定义变更需完整重算，无关定义不阻塞其它事实族；视图定义按候选重新投影，旧读沿用旧冻结定义。
- **落地排序**：P1 加法式保留自然键（历史阶段）；P2 与全部写入点一起将三张主事实表自然键替换为修订唯一键、关系主键替换为父修订+成员/提交。根清单采用发布+来源系统/实例+事实类型+非负项目号+根号完整身份，兼容项目号 0 不伪造。
- **读取协议与页面**：`/api/read-views` 创建、续约、释放和 fork 只接受业务范围与客户端幂等键；身份绑定账号/认证会话/当前权限。fork 保留完整来源、业务日和原历史期限，按父画面当时的来源集合及冻结模式核验目标页闭包和当前权限；随后新增来源或切模式不能污染旧下钻。常规响应增加独立 readView 披露，下载增加读取身份头；主表/候选/已开明细/规则完整准备后整体接受，失败保留旧画面，五秒准备上限不关闭读取。镜像与兼容数据集用强类型历史行恢复，CAT 引用既有不可变快照；管理表编辑和来源库预览仍属当前控制面。
- **定义与安装**：无事实 delta 的定义变化也登记正式完整重建意图，原生/兼容数据集通过持久请求重新发布；后续成功增量镜像不能把未结算全量意图降成空增量。安装镜像沿用正常完整运行证据入口，不依赖自动镜像开关。启动不直接执行接管/数据集生产；正常后台每轮只选择一个事实域或 CAT 快照，本地请求只补当前启用来源。读生产及回收共用具名单线程 `readPublicationScheduler`，与默认 `taskScheduler` 分离，固定延迟周期完成后再调度，不累计异步提交。关闭平台调度时不执行安装；首次接管不完整须单独核验域指针，HTTP health 不证明业务完整。标签普通候选、动态规则候选/预览/成员计算使用完整来源及冻结定义，不能扫描全部历史或半成品镜像。
- **仅迁移运行边界**：`PLATFORM_RUN_MODE=service` 为普通服务；`migrate` 使用不扫描业务组件的无 Web 上下文，仅执行配置中的 Flyway 并校验、关闭资源退出。升级使用一次性 `compose run --rm --no-deps -T`，有进程超时和专属令牌容器清理，失败不得继续启动新服务；Flyway/守恒/PG 身份核验通过后才启动服务。关闭后台调度不能替代此隔离。迁移不触发事实、数据集接管或重建。
- **完整数据集的物理复用**：完整对账是输入与增删改闭包契约，不等于强制新物理代际；有稳定键且关系模式不变时沿用 epoch，只捕获新增/变化行与删除证明，仍保护旧视图。首次、模式/键变化或无稳定键时才建新 epoch；事实全量候选保持原有新 epoch 契约。全部数据相同可直接复用旧公开点，不用新生产运行号重新发布旧点。此规则不消除全源判等/捕获扫描，不承诺目标规模吞吐。
- **生产退避**：镜像新捕获及 prepared 恢复发布失败均捕获首错、保持旧指针/原候选，按域持久记录 `producer_retry_after`（沿用本地请求的一分钟间隔）与 `producer_last_error`；失败域让位，后续域可继续，重启不清除退避。初次事实/CAT 接管沿用该控制；接管事务拒绝时自身不写，控制面可另行创建无公开指针的域以记录退避，不能将其视为空数据集认证；成功清错。原生/兼容请求保留既有租约与一分钟退避，二次清理失败不得遮盖首错或跳过失败记账。
- **容量与运维验收边界**：完整读模型的验收覆盖正常增量、全量重建、失败恢复、历史回收及完整备份/恢复；额外数据集副本与事实历史须分别统计产生量、保留引用和回收收敛，稳定业务规模下不能放任无引用积压持续增长。基础一致性测试通过不代表目标容量通过；大库下刷新和备份耗时/失败需作为同一数据生命周期问题治理，不能以单独延长超时、提高并发或跳过灾备内容替代。性能结论以真实目标规模证据为准，既有完整读与在用历史保护不因减量静默取消。
- **影响与验收**：新增自然业务身份详情端点与少量本页候选端点、独立 readView 及读取资源协议，属于具体可审核的接口变更。既有业务公式未改；黄金快照/夹具/掩码未更新，新增端点已登记，发布前需按黄金纪律生成新用例并审核有意接口变化。生产迁移与接管需另行授权。
- **不变量**（测试锁定）：已发布内容不可被生产者原地改写；单域最多一个活动候选；候选必须绑定基线与捕获水位；指针移动与版本状态、序号分配原子提交；读取期间回收不能删除在用修订（含同 epoch 其他仍被保留的版本）；回收/中止先清子关系再删父修订；全量候选不混入旧 epoch；不同来源或项目的同号根互不覆盖；bootstrap 无完整性正证据不认证。
- **候选产物归属证明**（2026-10-15 补正，写出上一条“正证据”的取证口径）：候选预热产物（统计快照、记录快照）的证明必须在**同一写入事务内**、对**同一次写入并读回的内容**冻结——载荷、行主键、消费方、来源版本、证明范围与代际；`complete` 只落盘本任务冻结的证据，不得重读当前行后把内容认证为自身产物（否则“A 写 a → B 覆盖为 b → A complete”会把 b 认证为 A 的产物，两份清单同时对当前内容成立）。发布前对当前全部 `PREPARED` 行做**双向核验**：每份已证明证据与当前内容一致、且每条 `PREPARED` 行都有证据；异义覆盖在发布门禁被拒、候选中止、旧公开版本保留。载荷摘要沿用既有单列摘要口径，不拆列、不新增证据类型。人员候选等‘首次出现顺序’派生值必须由事实查询的稳定次序（`order by business_order_id, id`）保证，否则同一数据会算出不同校验和。
- **验证证据**：P1 收口 93/0/0；P2 后端默认 1747/0/0、1 条既有环境条件跳过，最后清理并发修正与计算定义修正分别定向 35/0/0、62/0/0（0 skipped）。真实 PG 完整 141 条迁移链；新增回归覆盖整轮固定输入/漂移、旧版稳定、同域让位、失败/取消/令牌转移、清理逆序锁风险、投影证明失效、READY 幂等发布、各事实族计算定义及兼容独立域。门禁与可复现日志见活动计划及进度；未运行黄金/浏览器/容量验收。
- **候选产物归属补正证据**（2026-10-15）：隔离源码副本默认套件 1903/0/0；非 update 严格 compare 204/0/0，跑前跑后 `backend/src/test/resources/golden-baseline/` 271 份资源 SHA-256 逐字节一致（未写盘、未重建快照）。新增 `CandidateArtifactWriteOwnershipIntegrationTest` 7 项（真实 PostgreSQL + Flyway）：跨范围覆盖发生在完成前、覆盖发生在证明后、同内容重复写稳定、无证明产物、已证明行被删、失租不产生产物、写入清单随执行上下文清理；把归属改回“完成后重读当前行”后该组反例失败（突变自证）。缺陷原因板事实查询的稳定次序由 `CustomerIssueDefectCauseBoardServiceOrderTest` 锁定。
- **关联文档**：`docs/plans/read-path-never-block-stale-fallback-20261010.md`；`docs/architecture.md`“事实与统计”；`docs/decisions.md` D-18（快照读一致性）、D-24（事实发布控制面）。

- **手工重建意图与镜像证据**（2026-10-15，发布包提交失败修复）：手工全量重建允许先登记未结算意图；首次来源状态为 BLOCKED，镜像运行未知时显式为 NULL，不使用事实运行号、0 或任意历史运行冒充镜像。`V20261015_01__manual_fact_rebuild_pending_mirror.sql` 仅对 `source_fact_publication_states.latest_mirror_run_id` 放开非空，用 CHECK 限定缺省只可处于 `BLOCKED AND full_publication_requested`，原真实运行外键和依赖状态表非空约束保留。已有 READY/镜像身份不因再次请求被覆盖，后续正常镜像终态补齐真实身份且不清除未结算意图。旧 INSERT 的待插入行先触发 PostgreSQL NOT NULL，故已有冲突行同样失败；不能把同步后重试当成修复。真实迁移升级及正反例由 `ManualFactRebuildPublicationIntentIntegrationTest` 锁定；登记成功仍不代表来源完整或完整版本已发布。

## D-27 客户活动时间按列固定语义，完成事实投影显式使用上海时区

- **状态**：2026-10-15 经理审批通过，创建映射、业务日隔离及完成事实投影已实施；最终 BI v4、客户统计 v6。本地开发验收收口，不代表生产数据处理或发布准入通过。
- **背景**：本地来源创建列为 UTC naive，完成加标事件为 timestamptz；原 JDBC `getTimestamp`→`Timestamp.toLocalDateTime()` 让完成事实随写入 JVM 默认时区变化。本机完成事实为上海墙上值，不构成跨环境保证。同一 UTC 周日 16:30，经上海/UTC 默认时区会得到不同的本周解决资格。
- **选择**：保留不同列的显式语义：`created_at_source` 消费已经核实的 UTC 墙上值，活动规则映射到上海日历；`fixed_label_time` 在**来源事件→事实写入边界**显式落为上海墙上值，消费者直接按上海自然日/自然周分桶。事件时刻从带时区类型保留其瞬间，不依赖 JVM、连接或数据库的默认时区；非带时区来源须先核实真实语义，不按类型猜测。事实复制、旧修订读取不二次转换，空值保持未知。
- **理由与范围**：固定现有本机读者的输入语义即可消除本次缺陷，避免为新增周指标重解释全平台所有时间列。两种入口对应不同列，不得演变为同列新旧回退或依据主机动态选择。通用 Timestamp 帮助器的其它消费者、解决周期等既有行为先盘点，不借本单元全面修改。
- **旧数据与发布**：对照来源事件盘点当前有效事实是否符合目标语义，必要时通过已有候选重建、投影证明和原子发布生成新修订；不得统一对历史值加减八小时或改写已发布修订。固定计算定义/规则身份与页面读取身份，旧版本生命周期沿 D-26；生产重建、目标库接管和黄金更新按原授权边界执行。
- **验收**：真实 JDBC 事件→实际事实 mapper→今日/本周/累计完成，至少覆盖 UTC 周日 16:30、上海周一零点前一瞬，以及上海/UTC 写入进程和不同连接会话时区的同瞬间结果一致。仅手工构造上海 naive 值不能作为事实投影已通过的证据；本地真实链路造数与恢复保留可定位材料。
- **验证证据**：`backend/src/test/java/com/data/collection/platform/service/IssueFixedLabelTimeProjectionTimeZoneTest.java` 以生产列类型建 ODS（事件列 `timestamptz`、议题时间列 `timestamp`），走实际 `GitlabFactSourceSqlProvider.issueSourceSql()` 与 `IssueFactSourceRowMapper`，在上海/UTC/洛杉矶写入进程与 UTC/上海/纽约连接会话时区的九种组合下断言同一瞬间得到同一上海墙上值、周/日/累计分桶一致、无事件仍为 `null`，并锁定创建列的 naive 墙上值不被偏移猜测；临时还原旧投影后该反例在 UTC 写入进程即失败（3 项中 2 项），日志见 `.tmp-logs/d27-red-on-old-implementation.log` 与 `.tmp-logs/d27-targeted-tests.log`（定向 72 项/0 失败）。本机存量事实由上海主机投影得到，与新语义同值，未做批量重建。
- **经理复验**：隔离副本四类 22 项全部通过，0 失败/错误/跳过；其中真实 JDBC 投影 3 项覆盖九种组合，报告在 `.tmp/d27-manager-review-20261015-121614/target/surefire-reports/`。三份统计 Excel 独立核对表头及总计通过，缺失证据补齐，活动计划退役；未重编译共享 target。
- **关联文档**：`docs/architecture.md`“事实与统计”；`docs/progress.md` 客户问题统计指标升级审批条目。

## D-30 缺陷原因采集与合规判定分层

- **状态**：2026-10-20用户确认并要求落档；需求/架构决策生效，实现尚未修改。本次不执行代码修复、数据库写入、重算或黄金更新。
- **背景**：Wiki要求符合检查条件的已修复议题原因0项或多于1项判非法；本地156条有效回复中146条单选、4条跨大类多选、6条未选。新老代码把空格拼接的分类按`&`拆分，多个分类也可能得到长度1，造成漏报；数据库存在多选和旧代码未报错均不能证明业务合法。多个独立原因也可能是事实，强制删成一个会损失原始信息。
- **选择**：①采集层允许一个或多个真实原因，按稳定ID集合保存，不强制单选、不截断、不猜主因；必填、合法ID、去重等字段约束仍执行，历史空选原样保留。②合规层严格执行Wiki，在原有业务范围/状态门槛下，0项或超过1项标记“缺陷原因不唯一”；原始保存成功不等于合规通过。按具体原因集合正确计数，不复刻分隔符bug，也不直接计文本别名命中次数。③统计层按既有原因统计口径处理，不将所有处理强制变成一议题一原因；现有Wiki的原因占比分母为原因个数，不能借校验修复改成议题个数。④保留历史原值和追溯，修正派生判定，不删除多选或为四个样本设置特例。
- **否决**：延续老平台漏报作为兼容规则；把新表单强制单选来掩盖真实多因；自动保留首项/主因；因非法而一律丢弃采集数据或擅自改统计公式。此前“强制单选”建议撤销。
- **后续规则变化**：若业务正式允许多原因合法，应先统一修订Wiki、合规规则及统计定义，再同一工作单元调整实现；不采用个案豁免或暗中扩大合法范围。
- **实施验收与影响**：覆盖零/一/多原因、别名等价、同大类/跨大类、未到检查状态不误报、历史值保留，以及保存多选仍可被合规标异常。修复会改变受影响非法标志、筛选/导出及异常计数，应展示差异并遵循既有黄金纪律；不得以修复判定为由直接改原始数据、效率公式或延期写回。目标数据库与真实内网仍须按发布流程核验。
- **关联文档**：[产品表单采集](product.md#表单采集目标定位2026-10-20-用户明确范围)；[架构业务模块](architecture.md#业务模块)；[统一表单方案v5.1](plans/unified-form-system-plan-20261009.md) §2.3业务边界、§2.5裁定索引、§3.2字段、§3.5选择器及附录A.1调查。
