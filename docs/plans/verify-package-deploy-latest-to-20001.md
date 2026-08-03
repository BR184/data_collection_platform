# 最新主线整体测试、721 基线打包与 20001 部署计划

## 进度与中间物

- 状态：阶段 6 人工部署交付就绪。验证修复、正式 721 保数据更新包及隔离升级/回滚/再次升级演练全部通过；AI 到 `172.22.10.115` 的 SSH/HTTP 路径仍受阻，用户已确认 20001 当前无人使用并选择自行执行 `down/up`。已在包外生成完整现场 Compose 与 `.env`，显式绑定原 20001 PostgreSQL external volume，更新包本身继续遵守不携带真实 `.env` 的标准。
- 隔离原则：主工作树存在用户并行改动，本工作单元不读取其未提交实现作为修复来源，不回退、不覆盖、不纳入发布。所有修复、复验与打包源码均以本隔离工作树为准。
- 第一轮通过：后端生产包、Checkstyle、SpotBugs、JaCoCo 报告；前端 `npm ci`、类型检查、生产构建；Flyway 迁移集合不可变性、破坏性迁移检查及自测、迁移烟测、标签组矩阵、测试卫生、事实字段、profile 覆盖、产物位置、文本空白、`git diff --check`；Python 打包契约 25 项、对账单元测试 5 项；721 基线 `--plan-only`；旧发布脚本 Bash 语法；本地 `issue_fact.module_names` 只读探针。
- 第一轮失败：后端 `931` 项中 `21` 失败、`11` 错误、`9` 跳过；前端 `103` 个文件中 `13` 失败、`90` 通过，`365` 项中 `18` 失败并有 `16` 个异步错误；ESLint 3 项；前端高危依赖审计失败；schema/Flyway 漂移、API 契约漂移、冲突标记、验证账本缺失；Flyway profile 事实任务因测试 schema 无法解析 `public.gin_trgm_ops` 产生 9 个上下文错误。
- 第一轮运行问题：固定提交 JAR 的 25 个只读 API 中 24 个成功，`/api/question-metrics/illegal-records` 在 8,002 条 `issue_fact` 下超过 10 秒；`real_chain_api_smoke.py` 遇到 socket 超时时未生成完整报告而直接退出。
- 安全扫描：前端 `npm audit` 为 0 漏洞；后端升级同一维护线 Spring Boot `3.5.16`，并使用 Tomcat `10.1.57`、Jackson `2.21.5`、PostgreSQL JDBC `42.7.13`、Hutool `5.8.47`、POI `5.5.1`、Log4j `2.26.1` 和 Commons Lang `3.20.0`。OWASP Dependency-Check 的 NVD/CISA/RetireJS 分析及 CVSS 9 失败门槛通过；Sonatype OSS Index 因匿名请求返回 401 已显式关闭，不降低其它分析器或失败阈值。唯一剩余中危告警实际描述 Nu Html Checker，被 CPE 错配到 Hibernate Validator，不添加 suppression。
- 现场事实：用户已在内网确认 `172.22.10.115` 的 `20001/20002` 前后端均为 721。候选基线镜像为 `qa-flex-platform-backend/frontend:20260721-f18154c0-working`；部署前仍需用现场唯一 Compose 交叉确认。PostgreSQL 容器与数据卷不得重建、更换或删除。
- 阶段 2 已完成数据库测试单一事实源收敛：删除长期漂移的 `schema.sql` 和测试复制结构，Spring 集成测试统一由 Flyway 在隔离 schema 执行 `clean + migrate`；测试 `search_path` 同时包含 `public`，`pg_trgm` operator class 可见。新增 `V20260803_01` 将 `page_record_snapshots.source_version` 改为 `text`，迁移不可变性、破坏性迁移审查、Flyway 烟测和 `ReviewDataSearchIndexIntegrationTest` 已通过，原 4 个快照版本长度错误关闭。
- 阶段 2 已新增真实 PostgreSQL 全链回归：`IncrementalDeleteTargetedPublicationIntegrationTest` 参数化覆盖 `INCREMENTAL_SYNC` 和 `TABLE_REFRESH`，以每页 1 行验证同一 `RECONCILE` 任务续三页后删除 GitLab 一级缺陷标签链接，随后真实执行事实子运行和投影 worker。两种入口均确认 ODS tombstone、`issue_fact.severity_level` 清空、一级缺陷计数从 1 归零、版本化 outbox 发布、只推进相关全局/项目 generation、无关项目不变且不产生 `FULL_EPOCH`。
- 阶段 2 后端复验：新同步专项 72 项全部通过；依赖升级后的完整后端 948 项零失败、零错误、1 项按环境条件跳过，`BUILD SUCCESS`；Checkstyle 0 违规、SpotBugs 0 问题。测试使用本地 PostgreSQL `15433/qaflex` 的隔离 schema `qaflex_sync_tests`，测试源表已清理。
- 阶段 2 前端复验：103 个测试文件、365 项测试全部通过且无异步错误；ESLint、TypeScript、生产构建通过，`npm audit` 为 0 漏洞。客户问题列表、导出、规则说明和实时状态统一固定项目 325，标签组路由先规范化合法操作符再清洗，避免路由残留污染其他表格。
- 阶段 2 真实 API 复验：`SystemTestIllegalRecordService` 恢复数据库聚合候选和 SQL 分页后，最新 JAR 在 8,002 条 `issue_fact` 下以 36 ms 返回原超时接口；25 个只读 API 全部成功，总耗时 8.1 秒。探针现会把 socket timeout 记为单项失败，继续执行剩余端点并生成完整非零报告，回归测试通过。
- 阶段 3 工程与发布门禁：118 个 Flyway 迁移不可变性、破坏性迁移、API/事实字段/标签组/前端边界契约、工作树产物/运行产物/文本空白、`git diff --check`、同步 dry-run 和 35 项 Python 发布测试通过。过期且事实已归入权威文档的冲突计划已删除，不保留历史合并标记。
- 阶段 3 直接基线：完整 721 runnable 目录、Compose 解析和本机镜像均确认前后端为 `qa-flex-platform-backend/frontend:20260721-f18154c0-working`，两镜像均为 `linux/amd64`；以 20001/20002、`all` 事实重建声明执行 `--plan-only` 通过。
- 阶段 4 正式发布包：从干净提交 `5e2eb2e6` 生成 `qaflex-update-20260803T054734Z-066761e14130`，归档大小 `196,879,575` bytes，SHA-256 为 `bc155655414f9f23b3cef2e1d16f095af2e96b6f490d08b6130e8fc90d3cc9b0`，目标 Flyway `20260803.01`，发布清单要求 `all` 事实重建。打包器默认构建、镜像摘要、Compose、归档清单、Git Bash 语法、包内摘要和 25 项发布契约均通过。
- 阶段 5 隔离演练：独立 721 栈从 Flyway `20260720.06` 完成“备份 -> 升级 -> 应用回滚 -> 第二次备份 -> 再次升级”，两轮完整/关键表 dump 均非空且可恢复，`counts.diff` 均为空；PostgreSQL ID `a9d1928db80a74e70f227bb66cbccc1e9d58ae2940adbdf8a246a5521a971401` 与 external volume 全程不变，最终目标前后端健康、后端 UP、前端 200。
- 阶段 6 现场阻塞证据：22/20001/20002 TCP 均可达，但 Windows OpenSSH、Git OpenSSH 和 `ssh-keyscan` 均在 `kex_exchange_identification` 前被远端关闭，未进入用户名或密钥认证。不得绕过现场唯一 Compose、预部署备份和 PostgreSQL 容器不变检查直接替换应用。
- 阶段 6 人工交付：包外配置目录 `qaflex-update-20260803T054734Z-066761e14130-20001-config` 的 Compose 与更新包内文件摘要同为 `e67be7763c41844d35cbd0f40f7c6184dd7c09b0d3c4b7747870bbdbfb32b64b`；`.env` 由 20001 的 20260710 完整基线和 20260721 LDAP/721 更新链收敛，固定 20001/20002、LDAP `172.22.10.116:80`、原 Compose project 及 `..._qaflex_pgdata` volume。用户只能执行不带 `-v` 的 `down`，随后加载目标镜像并 `up -d`。

## 恢复线索

- 当前阶段：阶段 6，等待用户使用已交付文件在现场完成不带 `-v` 的 `down/up` 并返回 Compose、Flyway、健康和 volume 验收结果；AI 网络入口恢复后再补做现场只读核验。
- 恢复后首条命令：先核对用户返回的 `docker compose ps` 和 PostgreSQL volume 证据；若 SSH 已恢复，再执行 `ssh-keyscan -T 8 172.22.10.115` 后进入现场只读验收。
- 相关基线：`2fd5c34e`；上一实现计划为 `docs/plans/implement-incremental-delete-detection-targeted-refresh.md`；发布规则为 `deploy/intranet-offline-packaging-standard.md`。

## 目标与边界

- 目标：修复第一轮登记的全部有效问题，完整复验后按 721 直接基线生成保数据更新包，在隔离栈完成升级、应用回滚和再次升级，再部署到 `20001/20002`。
- 成功标准：后端、前端、迁移、静态契约、安全检查、运行冒烟和打包门禁全部通过；隔离与现场升级中 PostgreSQL 容器 ID、external volume 和受保护数据保持不变；同步物理删除、精准事实发布及相关页面投影正确收敛。
- 禁止：不边修边改变第一轮问题集合；不保留错误旧契约的兼容分支；不执行 `docker compose down -v`；不删除、重建或替换现场 PostgreSQL；不在失败未清零、基线不明或备份不可恢复时打包部署。

## 约束与背景

- 技术栈为 Java 21、Spring Boot、MyBatis-Plus、Vue 3、TypeScript、Vite、PostgreSQL 16/Flyway、Docker Compose v2。
- 内部契约按目标版本直接收敛并同步全部调用者和测试；只有真实对外 API 才允许有期限的兼容设计。
- 测试依赖必须确定、隔离、可重复。`schema.sql`、Flyway 和测试 profile 不允许表达互相矛盾的数据库结构。
- 721 的旧式更新目录没有当前 `RELEASE-MANIFEST.json`，不能直接传给新版打包器；候选完整 721 包目录和现场 Compose 必须共同确定新版基线输入。

## 证据与根因

1. 测试 schema 漂移：生产代码和迁移需要 `ods_gitlab_issues.milestone_id`、评审搜索字段、权限表等结构，当前 `schema.sql` 缺失，导致 10 个数据库集成测试错误并连带 SQL 推送链路失败。
2. Flyway profile 隔离错误：迁移将 `pg_trgm` 扩展安装在 `public`，测试连接只搜索 `qaflex_test_flyway`，未限定 operator class schema，迁移到性能索引时失败。
3. 后端契约回归：统计列/导出格式、非法记录 SQL 分页与筛选、代码走查源实例、阶段展开、非法过滤字段校验、时间归一化和调度器状态断言存在目标实现与测试不一致，需逐项依据当前业务规则确定权威行为。
4. 前端回归：统计布局/路由/规则说明/详情、刷新状态、非法记录页、标签组设置和表格样式测试失败；测试环境缺少 `ResizeObserver` 造成 16 个异步错误；另有 3 个未使用符号。
5. 工程门禁失真：schema 漂移 13 表/35 索引、API 动态路径误报、已完成计划残留冲突标记、已删除验证账本仍被默认检查器引用。
6. 依赖审计：`brace-expansion`、`postcss`、`vite` 为 high，`vitest` 为 critical，`echarts` 为 moderate；需通过受支持版本升级收敛，不使用 audit override 隐藏风险。
7. 运行性能：系统测试非法记录接口在本地 8002 条事实规模超过 10 秒；需以 SQL 查询路径和固定规模测量定位，不用缓存或提高超时掩盖。
8. 探针韧性：只读 API 探针仅捕获 `HTTPError/URLError`，未捕获 socket timeout，导致剩余接口未执行且报告丢失。

## 方案与步骤

1. 修复测试数据库单一事实源：从 Flyway/生产访问契约收敛 `schema.sql`，修正扩展 schema 可见性，恢复数据库集成测试。
2. 修复共享后端契约：按业务规则校正统计、非法记录、代码走查、阶段、过滤 JSON、时间和任务状态；先跑对应定向测试。
3. 修复前端目标契约与统一测试环境，清除未使用代码，升级有漏洞依赖并复跑定向测试、lint、类型检查和构建。
4. 修复仓库门禁与探针：schema/API 检查器只表达有效契约，删除已完成冲突计划或收敛为当前事实，更新验证账本入口，保证超时也形成完整报告。
5. 测量并优化系统测试非法记录 SQL 路径，加入固定规模基准或可重复查询证据，确认其他记录表不回归。
6. 在最终代码状态执行完整后端、前端、Flyway、静态、安全、运行和打包计划复验；任何失败返回对应步骤。
7. 读取现场唯一 Compose，生成正式包；在隔离栈完成 721 升级、应用回滚、再升级和数据守恒验证；最后在现场备份后升级并验收。

## 决策记录

- 已选独立 worktree 修复，避免主工作树并行改动污染 GitHub 固定提交的结论。
- 已选按共同根因集中修复，不逐个测试打补丁；`schema.sql`、动态 API 规范化和测试环境分别保持单一入口。
- 已选 `all` 事实重建声明作为当前打包计划上限，因为 721 之后议题、MR、共享事实发布契约均变化；正式打包前再以最终 diff 复核。
- 已选标准更新包与现场 `.env` 分离交付；用户本次人工 `down/up` 授权不改变更新包长期禁止覆盖实例配置的规则，且 PostgreSQL volume 继续使用原稳定名称。
- 否决把既存失败当作可忽略项；本轮目标是全部有效门禁清零。
- 否决把旧式 721 增量目录直接改造成新版基线；应选择可审计的完整部署目录或现场受控副本。

## 接口契约

- 当前不新增产品 API 或数据库对外契约。
- 测试初始化、门禁规范化和探针错误收敛属于工程契约调整；若业务 API/表结构在修复中确需变化，先更新本节并同步所有调用者、测试和权威文档。
- 打包与部署只使用 `scripts/package_intranet_offline.py` 及生成包内 `backup.sh`、`upgrade.sh`、`rollback.sh`。

## 风险与假设

- 主工作树并行改动可能已修复部分相同问题，但未经固定提交验证，不作为本工作单元事实，也不得直接覆盖合并。
- 本地开发库 Flyway 已比固定提交新，运行冒烟只验证只读兼容性；最终运行测试必须使用与最终源码一致的隔离数据库。
- 现场 SSH TCP 可连接，但远端在主机密钥交换前主动关闭；该问题与用户名、私钥和客户端实现无关。恢复 SSH 服务或访问策略前，现场部署明确阻塞，不猜测凭据、不绕过备份升级脚本。
- 真实 GitLab 删除验收涉及源端状态，只能使用授权的隔离对象；无授权时完成本地确定性链路并把现场验证列为部署验收项。
