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
  3. 无意差异一律视为回归，修复实现而非更新快照；有意行为变更经 `-Dgolden.update=true` 重建受影响快照并 git diff 人工审阅，与变更同单元提交。
  4. 夹具改动即新基线版本：重新抽取、更新 manifest、在决策记录留痕；golden 运行档禁止触碰外部活体（GitLab Web API、老平台 MySQL/Mongo），依赖活体的路径显式 EXCLUDED 并注明替代覆盖层。
  5. 基线版本状态（2026-09-02）：当前输出快照基线对应本地开发树 = 内网验证版（2026-08-06 全量包 `qaflex-full-20260806T111653Z-8269d2b61253`）+ 解耦前修复 + 解耦重构，**整体未进内网测试**，属开发期基线——可捕获解耦收尾期间的产出漂移，不能证明当前产出等于内网验证版产出。解耦版通过内网测试后必须 `-Dgolden.update=true` 重冻结快照（夹具不动）升级为可信基线。
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

- **状态**：2026-09-07 生效（内网 30001 全量构建假超时互锁问题的根治，用户批准实施）。
- **决策**：全量事实构建（ISSUE/MR/集成测试）从「单一大事务原子发布」改为「分批事务提交 + 末端反连接清理 + 短结算事务」；运行中事实任务按批续期租约（owner 围栏）；run 心跳调度器从全运行共享单线程改为按最大并发运行数定容；重试/租约超时/进度/完成事件写入 `sync_run_events` 供 UI 可见。
- **等价性**：分批 upsert + 快照外清理与旧「删全部+重插」的最终状态逐行一致（黄金基线空库首建背书）；存量库上保留既有行 id，优于旧方案重建 id。中断恢复语义从「全量回滚」改为「已提交批次保留、重试幂等重做收敛」。
- **动机**：旧形态下全量构建（内网约 4.6 万行、实测 4.6–17 分钟）必然超过 180s 任务租约且执行中无续租；run 心跳为共享单线程，被长事务饿死后 run 判 TIMEOUT → 任务重派 → 新旧事务行锁互锁 → 原事务 owner 围栏失败整体回滚丢全部工作。git 考古：机制 2026-05-09（e00c5998）引入、2026-08-03（2fd5c34e）随版本化发布重构定型，非近期修复回归。
- **边界**：定向（增量）事实构建路径与 `replaceRootFacts` 的事实替换语义不变——其原子性由原 runGuarded 外层事务改为构建层显式单事务（事实+客户成员/提交关系+搜索列刷新+目录对账同事务）承担；手工构建（runGuarded）仅去掉外层事务包装，互斥仍由全局 advisory lock 承担；`fact_build_tasks` 表结构不变；分批大小可配 `platform.gitlab-mirror.fact-full-build-chunk-size`（默认 2000）。本条不覆盖定向目标生成/领取的性能边界，后者以 D-13 为准。
- **禁止**：不得为「构建中断后部分批次已提交」引入回滚/补偿双轨——重试幂等重做是唯一收敛路径。

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

## D-18 统计板快照的来源身份、读一致性与发布资格门禁

- **状态**：2026-09-22 生效（方案 S04 实施并通过真库验证；下钻/下载携带来源版本并匹配拒绝的部分随 S06 落地）。
- **背景**：统计板快照以来源版本为键，但来源版本过去由调用点隐式使用默认来源生成：`StatisticBoardSnapshotRequestFactory.issueRequest` 写死 `DEFAULT_SOURCE_INSTANCE`，`StatisticBoardSnapshotService.issueFactSourceVersion` 也把传入范围一律按默认来源求值。实际查询却可能读取其它来源实例（请求带 `sourceInstance` 筛选）或读取全部来源，于是"读取了但不在版本里"的来源变化无法让旧 READY 快照失效。此外版本在事实读取之前取用，存在"事务前取旧版本、事务内读到新事实、再写入旧键"的窗口；来源处于不完整发布窗口（依赖未就绪、全量重建已请求未结算、仍有未发布目标）时，缓存命中会继续把部分提交数据当成完整来源对外输出。
- **选择**：①新增 `StatisticBoardReadScopeResolver` 作为"实际读取范围"的唯一解析入口：显式选择来源时只声明该来源，未选择来源时覆盖全部已存在代际的来源（`fact_projection_generations` 去重，新来源一发布即产生新代际）；`SnapshotRequest` 以范围集合取代来源版本字段，来源版本改由 `FactProjectionVersionService.combinedSourceVersion` 在事务内按来源分组生成，删除隐式默认来源重载与写死的 default 来源。②`readOrRefresh` 用只读 REPEATABLE READ `TransactionTemplate` 覆盖发布资格校验、版本读取、快照命中与事实读取，快照写入在该事务之外并绑定事务内读到的版本，因此并发发布只会产生一个可被取代的旧版本条目，不会把新事实写进旧键。③新增 `SyncFactPublicationStateService.qualification`（依赖未就绪 / 全量重建未结算 / 仍有未发布目标 → 不可读），读路径命中缓存也校验，不合格抛业务异常并携带原因，不产出新 READY、不以零值或旧缓存冒充结果；来源尚无任何发布记录时，只有"从未产生事实投影"才算可证明的完整并保持可读，**已有事实投影却没有发布记录属于完整性无从证实的未知状态，一律拒绝**（第四轮更正：原记的"来源从无发布记录时保持可读"过宽）。④延期三列定向更新虽不经过发布链路，也在同一事务内按真正写入的行推进范围 generation（复用 `FactProjectionScopeResolver`），写入 0 行不推进、无关范围不被波及。
- **否决选项**：①在 `issueRequest` 保留默认来源并另加"多源"分支——同一语义两套入口，且旧调用点继续隐含默认来源；②只把来源版本拼进 `scope_key`——版本仍可能不含实际来源，多源读取的变化照样漏失效；③在事实读取之后取版本——读到的事实与写入的键仍可能分属两个版本；④门禁只检查冷读——命中缓存正是缺陷发生的路径；⑤用 `isReady()` 判定可读——它是"有没有待提交工作"的混合语义，与"能否对外输出"不同；⑥为不完整窗口返回零值——把"没有数据"与"数据不可信"混为一谈。
- **关键约束**：门禁对全部统计板生效，来源处于不完整发布窗口时旧看板端点同样明确报错，因此"任何情况下都有数字"的旧行为不再成立；`save(SnapshotRequest, response)`（快照预热写路径）不经过门禁，它在不完整窗口内写入的 READY 只会在重整结算后因版本变化成为失效条目，且窗口内读取本身已被拒绝。
- **边界**：不新增缓存表、线程或定时刷新链；不改变快照表结构、目录分类、掩码与既有看板的计算口径；`ScopeKey` 文本格式不变，仅来源版本取值随实际来源集合变化（部署后表现为一次性缓存未命中）。
- **关联证据**：`docs/architecture.md` 事实与统计章节；实现 `StatisticBoardReadScopeResolver`、`StatisticBoardSnapshotService.readOrRefresh`、`FactProjectionVersionService.combinedSourceVersion`、`SyncFactPublicationStateService.qualification`、`FactBuildService.invalidateDelayRefreshScopes`、`FactProjectionGenerationService.advanceIssueScopeGenerations`；测试 `CustomerIssueDelayRefreshScopeVersionTest`、`CustomerIssueScopeVersionInvalidationTest`、`StatisticBoardSourceQualificationTest`、`SqlPushdownRealChainTest`。

## D-19 公共下钻契约的集合、来源一致性与维度控制参数

- **状态**：2026-09-22 生效（方案 S06/S07 实施并通过定向与前端全量测试）。第四轮起 ④ 的成员候选来源与缺失表达被更正：候选改由 `control-options` 端点在同一窄事实的完整基础范围上求解，缺失改用"类型 + 值"表达（见下"更正"）。浏览器验收已于第四轮在隔离测试库实例完成；黄金快照重建仍待授权执行。
- **背景**：下钻能力过去由"后端 `drilldown` 布尔 + 前端 `numericValue > 0` 推断"两处共同决定，因此 0 值单元格在旧板不可点击、在新板又需要可点击以解释空集合。明细响应也没有表达"这行属于哪个集合"，比率与周期的分子/分母/样本无法分别查看。同时主表与下钻之间没有来源代际绑定：主表读到版本 A 后来源推进到版本 B，下钻会用版本 B 的事实回答版本 A 单元格的语义。新板的行维度与成员选择又必须先于快照键确定才能保证不同分组不共用同一份结果。
- **选择**：①`StatisticCellData.drilldown` 成为唯一最终能力，旧板在原生产端用历史有效条件（声明为可下钻且数值大于 0）显式写入该值，前端两处数值推断删除，不新增任何 `boardKey` 特例。②`StatisticDetailResponse` 增类型化集合列表与当前集合（数量 `COUNTED`、比率 `NUMERATOR`/`DENOMINATOR`、周期 `SAMPLE`），空集合仍返回列定义、说明与 `total=0`，删除旧 9 参构造器并同步全部构造点，不保留空构造兼容层。③主表单元格 `detailParams` 携带行身份、业务日、来源选择与来源版本；明细请求重新解析当前范围与来源版本并匹配，缺少或已变化时明确拒绝，前端保留旧主表并提示整表刷新。④行维度与成员选择登记为控制参数（D-17），由页面配置声明、随 URL 往返、切换时失效明细键并回到第一页；成员候选的最终实现见本节"更正"。⑤范围目录已加载但没有启用项时显示目录状态并阻止取数，不再按"全部里程碑/阶段"放大范围。
- **否决选项**：①在前端按 `boardKey` 判断新板是否可点击——同一能力两套判断；②保留旧 9 参构造器并补空集合——为废弃签名留兼容层；③用空列表代替明确拒绝来回答过期来源的下钻——把两个代际的读取混在一起且无法察觉；④在明细请求中重新计算集合而不携带来源版本——下钻与主表可能分属不同版本；⑤**（第四轮更正）**把候选做成独立成员表或枚举——候选是同一窄事实在完整基础范围上的一次查询结果，不是第二套成员权威源；当时的否决理由是"当前响应行已携带稳定身份"，该理由不成立（行身份只覆盖结果行，筛掉成员后其余成员不可选）；⑥无启用目录时按全部范围返回数据——把"没有范围"当成"范围是全部"；⑦**（第五轮）**用更长的哨兵区分真实同名成员——同一参数承载两种语义，无论换多长的哨兵都存在相撞的成员名。
- **更正（第四轮 Q4 / 第五轮 R5-2，取代原 ④ 的成员候选与缺失表达）**：①候选来源改为 `GET /api/statistic-boards/{boardKey}/control-options`，在 `withinConsistentSourceRead` 边界内按"完整基础范围"（项目 325、创建下限、来源实例、生效里程碑成员 + `StatisticFilterEngine` 用户条件，成员选择一律 `all()`）求解，返回 `{scopeKey, sourceVersion, scopeReadable, reason, groups[]}`；候选只在范围签名或主表来源代际变化时重取，翻页与切维度不产生额外请求；来源资格失败或请求异常必须报因而不得渲染成"该范围没有成员"。②缺失与真实成员用"类型 + 值"表达：候选项 `StatisticBoardControlOption{kind, value, label}`（`MISSING` 时 `value` 恒为空串），请求参数分 `customer/module/function`（成员名）与 `customerKind/moduleKind/functionKind`（类型），`putSelection`/`parseSelection` 互为逆运算并写入主表 `detailParams` 与快照键，非法 `kind` 直接拒绝；真实成员名等于 `__missing__` 或等于"未标注…"文案时与缺失是两个可分辨候选项，既不被跳过也不折算成缺失。原 `missingSelectionValue` 哨兵与 `ambiguousMemberCount` 上报已删除，不保留双轨。③候选与主表必须同代际且**无论谁先返回都要核对**：主表先返回时，迟到的旧代际候选在到达时被丢弃并按当前代际重取一次，同一代际不重复重取，长期不一致明确报不可用；主表、明细、候选三条加载路径均以请求序号丢弃迟到响应，明细关闭或失效时作废在途请求，快速筛选变更只产生一次请求（页码已在第一页时自行发起，否则只写路由由监听统一重取）。
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
