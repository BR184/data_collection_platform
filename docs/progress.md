## progress.md 文档总则

> 本文档为项目的进度状态权威记录。AGENTS.md 中关于文档职责、更新触发条件和事实优先级的规则同样适用于本文件。
> AI 读取本文件时应将其中内容视为当前工作状态的事实；选择下一步任务、评估阻塞或引用历史结论时，必须以本文为准。本文档与 `product.md`、`architecture.md` 职责分离：产品需求与用户承诺写入 product；技术架构、模块边界与实现约束写入 architecture；当前阶段、已完成项、下一步、阻塞项、风险、最近验证证据及仍有现实影响的历史写入 progress，写入时每条开头标注本条内容所属类型，格式为[xxx]。三者不得交叉重复。
> 内容组织以当前阶段为核心，按“当前目标 → 已完成 → 下一步 → 阻塞与风险 → 最近验证 → 有效历史”顺序呈现。已完成项只记录对后续工作有影响的里程碑和已验证成果，不逐条复述任务细节。阻塞与风险只列当前存在且需关注的真实问题。验证证据须是可复现的测试结果、基准数据或运行截图，禁止引用推测或未经验证的说法。有效历史仅保留对当前决策仍有约束力的过往结论，并明确标注状态与现实影响，过时条目应及时删除。
> 更新触发：当前阶段变更、任一已完成项或下一步发生实质变化、新增或解除阻塞项、验证结果推翻先前结论、或有效历史条目失效时。临时任务、中间调试、重复性工作或已失去现实影响的流水账不得写入。
> 保持行文紧凑，以最小 token 传达当前状态的完整约束。禁止叙述性解释、重复架构或产品文档的内容，以及纯粹展示性的列表格式。所有陈述必须直接指导下一项工作决策，否则不得保留。

## 2026-09-08 20001 保数据更新包制作与本地升级演练

- [完成] 20001 现场（直接基线 20260803T122027Z-ad35f6c0e8c3 全新包，现场运行镜像逐字一致）保数据更新包生成：`qaflex-update-20260908T075021Z-c3539eefab51`，归档 197,383,444 bytes，SHA-256 `f55781200161e89e01a25af747cdec9e5d38cd1ea29bb2627b5102d24de0f9cc`；source.commit=05ecadda（工作树非干净=遗留文档/SQL 非发布代码），目标前后端镜像同 release-id，Flyway 20260803.01→20260903.01（12 迁移），facts={rebuildRequired:true, scope:issue}（客户问题统计排除建议类口径变更；MR 侧基线以来仅等价重构，不扩 all）。打包器全门禁+独立审计通过（契约测试 34/34、镜像内产物摘要、包内外校验和、归档结构合规、bash -n）。
- [验证] 本地隔离栈（project=qaflex-upgrade-sim-20001，端口 20111/20112/15533，基线镜像自基线包 docker load）完成 backup→upgrade→rollback→再 backup→再 upgrade 三腿演练：目标镜像双 healthy、后端 health UP、前端 200、Flyway=20260903.01、两次备份 counts.diff 均空、双 dump pg_restore --list 可读、PostgreSQL 容器 ID `8f200ca68ab075aeba2c54385d41b492bd395c4ebea4b548eb280b547a300bd7` 全程不变，栈最终停留目标镜像。演练期两次无害失败均已定位：sim `.env` 缺 `POSTGRES_VOLUME_NAME`/`BACKEND_LOG_VOLUME_NAME`（现场 -20001-config 模板已含，20001 现场升级前须确认现场 `.env` 具备此二变量，否则升级在同一检查点无害停止）；本机 8/3 演练残留 `qaflex-backend`/`qaflex-frontend` 固定名容器与新包 compose 冲突（按标准仅清理精确应用容器，未碰任何 postgres）。
- [限制] 未验证：LDAP 真实登录、内网真实数据规模与 issue 事实重建（现场升级后由用户在「数据镜像设置」提交 FACT_REFRESH，不走 GitLab 全量同步）。
- [发现] 打包生产构建再生成 `frontend/src/components.d.ts`，移除 ElPopover 声明——规则配置路由已于 5144c23c 有意下线，`CodeReviewIllegalRuleConfigView.vue` 及其测试/manifest 契约成为孤儿残留（仅测试引用）；dts 再生成与生产模块图一致，随本单元以 chore 提交。孤儿视图文件清理与否待用户拍板。

## 2026-09-08 评审问题同名专家覆盖缺陷修复（状态必填+占位守卫）

- [完成] 修复「新增评审问题」同名专家覆盖缺陷并放开同专家多条问题：根因是新增态状态可空→后端默认化为"未评审"→真实内容项停留"未评审"被 `findPendingProblemItem` 无限劫持覆盖。实施（方案 A+守卫，定稿时否决用户"下拉框加回未评审"提议——显式选"未评审"的真实条目会重新成为劫持目标，且后端 `requireUserSelectableProblemStatus` 本就拒绝该值）：`ReviewDataRecordCommandService` 创建路径改 `requireProblemStatus`（空/未评审拒绝）并删除 `defaultPendingStatus`；劫持谓词收紧为 `isDefaultPendingProblemItem`（只劫持系统纯占位行，30001 存量脏行不再被覆盖）；DTO `problemStatus` 补 `@NotBlank`；前端 `ReviewProblemItemFormDialog` 状态新增/编辑均必填、候选维持不含"未评审"；业务规则 6.x 第 13 条修订。计划 `docs/plans/review-problem-item-expert-override-fix-20260908.md`。
- [验证] 后端单测 7/7（新增 4 用例：空状态拒绝、手动未评审拒绝、同专家已有项走 insert、未评审+真实内容脏行走 insert）；搜索索引集成 4/4；后端默认套件 1250 全绿；前端 vitest 459/459、typecheck 干净。影响面实证：golden-create/update 显式传真实状态且夹具零"未评审"行、Excel 导入恒传"已关闭"或用户指定状态——零快照影响，不触发黄金基线。
- [风险] 30001 存量"未评审+有内容"脏数据不可自愈也不可恢复已丢内容：部署后需按计划文档第 6 步 SQL 人工核查，逐条编辑补选状态，被覆盖丢失的历史内容按原始记录重录。

## 2026-09-08 客户问题统计排除建议类（延期+缺陷汇总）

- [完成] 按领导 2026-09-08 指示，客户问题延期问题与缺陷汇总两看板统计层排除建议类：共享判定类改名 `SuggestionMetricSupport`（6 处系统测试看板调用点同步）；缺陷汇总整块常规指标 regular/suggestion 拆分（`CustomerIssueDefectSummaryBoardService`，FACT_SQL 接入 exclusion_reason、matchesMetric/toRowData 全面拆分、建议类列加 tooltip、补 module_total 下钻口径 case）；延期看板 exclude-filter 改 regular 判定（`CustomerIssueDelayIssuesBoardService`，补 severity_level/exclusion_reason 事实列）。两看板 RULE_VERSION 升级（v7/v5），内网存量快照将自动失效重建。事实层 is_excluded 与其余三个客户问题看板（响应效率/缺陷原因/按功能）不动；业务规则 5.1 第 4 条/5.2 第 6 条/5.3 第 15 条修订。计划 `docs/plans/customer-issue-suggestion-exclusion-20260908.md`。
- [验证] statistics 包 56 测试全绿；后端默认套件 1246 全绿（0 失败 0 错误，15433 测试库容器 `qaflex-test-postgres-15433` 重建后复跑，此前 68 个错误均为该容器缺失导致的上下文加载失败）；新增建议类用例 2 个（延期排除+汇总"建议+二级"混合排除）；两 GoldenMaster 期望重建（延期金标与旧版零内容差异，缺陷汇总仅建议类列 tooltip 变化）；本地库复核（15432 真实数据）：延期样本 815→461、缺陷汇总样本 1054→722、修复率 32.9%→42.8%。
- [风险] 黄金基线快照已重建（2026-09-08 发布门禁：更新模式+62 文件全量归一化审计+比对模式 180/180 全绿，审计轨迹见 `docs/plans/golden-baseline-rebuild-release-gate-20260908.md`）；系统测试缺陷汇总存在 module_total 下钻含建议类的既有不一致（本次未动系统测试侧行为，待后续拍板）；API 级界面验证需登录凭据，待用户在浏览器复核两看板数字。

## 2026-09-07 全新包 .env 直出

- [完成] 修复 4cac4c19（2026-07-27，混合提交顺带引入）造成的部署摩擦：fresh-empty 包恢复直接生成 `.env`（内容与原 `.env.example` 相同，仅发布级默认内网地址/端口与 qaflex 占位密码，无真实密钥），`.env.example` 概念全链路退役——用户部署不再需要手动 cp/改名。README 第 4 节删 `cp`/`vi` 步骤；required_files、包内 compose 解析校验、增量包禁带清单同步（增量包仍禁带 `.env`，现场 env 唯一事实源约束不变）；规范 `deploy/intranet-offline-packaging-standard.md` 同步 4 处。计划 `docs/plans/fresh-package-env-direct-20260907.md`。
- [验证] 打包器契约测试 34/34（fresh required 集合断言 `.env`、README 断言锁定 `cp .env.example` 步骤不再出现）；仓库四项门禁全绿。零业务代码，不触发黄金基线。

## 2026-09-07 同事 WIP 测试处置（ReviewDataRecordReadSupportTest）

- [决策] 用户实测评审数据页筛选（标题搜 bom 命中 BOM、负责人搜 xiao 命中 Chen xiaojun）证实现实现的大小写不敏感搜索正确；同事 2026-08-25 的 untracked WIP 测试期望与之相反（`%Alice%` 大小写敏感、`%41%` 剥离 #），其 RowMapper 用例针对已重构进 `ReviewDataMetricSqlExpressions` 的旧逻辑（现由黄金基线全链路锁定，覆盖更强）。经用户确认删除该文件（untracked，无 Git 痕迹，零代码改动，D-10 全量套件后常规套件可全绿）。

## 2026-09-07 全新包去除 PostgreSQL 镜像交付

- [完成] fresh-empty 打包器不再携带 `postgres_16-alpine.tar`（用户拍板：PG 镜像自初始部署加载后从未变更，115 目标机各实例共用同一本地镜像；历史全新包归档可满足真正新服务器场景）。打包器删除 fresh 模式的 PG `docker image inspect`/`docker save`、manifest `target.images.postgres` 与 required_files 条目；`docker-images/postgres_16-alpine.tar` 由仅更新包禁止升级为任何模式禁止交付；compose 的 PG 镜像引用收敛到 `POSTGRES_IMAGE` 常量单一事实源；包内 README 镜像加载节改为「目标机 `docker image inspect postgres:16-alpine` 守卫 + 缺失时从部署资料归档历史全新包加载」。附带修复既有契约测试对打包机 `backend/target` JAR 的产物依赖（补 mock 使测试独立，消除"仅当本机刚构建过才全绿"的脆弱性）。规范同步：`deploy/intranet-offline-packaging-standard.md` 全新包结构契约、发布策略例外段、「镜像与发布身份」新增 PG 镜像通用规则、发布验收统一为任何包不含 PG 镜像。计划 `docs/plans/fresh-package-drop-postgres-image-20260907.md`。
- [验证] 打包器契约测试 34/34（新增 4 项：fresh required_files 完整集合不含 PG tar、fresh 模式拒 PG tar 交付、fresh manifest `target.images` 仅 backend/frontend 且 baseline 为 null、fresh README 守卫断言；既有 incremental 拒 PG tar 断言随 forbidden 顺序更新）；`--mode fresh-empty --plan-only` 端到端解析正常；仓库四项门禁全绿。保数据更新包行为零变化（本轮零业务代码改动，不触发黄金基线）。

## 2026-09-07 事实构建分批发布与租约治理（D-10）

- [完成] 内网 30001 暴露的「全量事实构建无进度 + 假超时反复重试 + 互锁回滚」已根治（用户批准实施，方案 = 解决文档 P2 的 F1/F2/F3/F5，F4 advisory-lock 论证否决）：全量构建改为分批事务提交（每批原子完成事实+客户成员/提交关系+搜索列刷新，默认 2000 行/批可配 `GITLAB_FACT_FULL_BUILD_CHUNK_SIZE`）→ 末端反连接清理快照外事实 → 短结算事务（FULL_EPOCH 推进+任务终态+发布结算）；批间续期任务租约（owner 围栏，失效即中止）并写 `FACT_BUILD_PROGRESS` 事件；重试/超时/完成事件写入 `sync_run_events`；run 心跳调度器由全运行共享单线程改为按最大并发数定容。`replaceAllFacts` 双轨删除，中断语义 = 已提交批次保留 + 重试幂等重做收敛。决策与等价性论证见 `docs/decisions.md` D-10；工作单元细节见 `docs/plans/fact-build-chunked-publish-20260907.md`。
- [验证] 后端全量默认套件 1247 项：唯一失败 = 同事 untracked WIP `ReviewDataRecordReadSupportTest`（非本单元，预期），本单元新增/受影响测试全绿（含 `FactTargetPublicationServiceIntegrationTest` 4 项：targeted 回滚、publishFull 结算、租约被盗中止不结算；`FactBuildTaskServiceTest` 续租 owner 围栏真实库用例）。runGuarded 事务包装移除后暴露的增量分支原子性缺口已在构建层补显式单事务（ISSUE 增量：事实+搜索列+目录对账同提交；MR 增量：替换+提交关系+搜索列同提交），语义与改动前严格等价。golden 更新模式曾拦截本单元快照外清理缺陷（身份分片互删，1200/1500 行快照被整体清空且任务 SUCCESS 无 WARN，默认套件不可见）——修复为会话临时表+`NOT EXISTS`（`IS NOT DISTINCT FROM`）全集反连接（内网 4.6 万行规模安全），并新增 `IssueFactSnapshotCleanupIntegrationTest`/`MergeRequestFactSnapshotCleanupIntegrationTest` 各 1200 行跨分片规模真实库回归。
- [验证] Checkstyle 0 违规、SpotBugs 0 问题、仓库四项门禁全绿。顺带清理 HEAD 既存的 3 个无用 import（`DropdownOptionFieldService`/`FactBuildServiceOrchestrationTest`/`FactSourceRowMapperTest`，早前提交门禁遗漏）。
- [基线状态] 黄金基线 180/180 全绿（更新模式重建 + git diff 人工审阅 + 比对模式复跑）。60 个快照文件差异经语义树终审全部归类闭合：掩码内易变字段（时间戳/UUID/Testcontainers 端口/retentionHours 墙钟派生/queryDurationMs）+ `Map.of` 构建的 detailParams 图表配置属性序文本噪声（JDK 不可变集合每次 JVM 迭代序随机，实测 10 次 4 种排列；JSONUnit 语义比对对对象属性序不敏感，不影响比对）+ 唯一实质差异 `/api/gitlab-sync/status` 的 fact 完成日志文案（本单元有意变更）。golden 门禁另立一功：其全链路断言拦截了本单元分片互删清理缺陷（默认套件不可见），修复后回归测试见上条。

## 2026-09-04 全新部署包 30001（缺陷测试用）

- [完成] fresh-empty 全新空数据包已生成：归档 `D:\projects\data_collection_platform_deploy\qaflex-full-20260904T104638Z-8cf0508d8832.tar.gz`（306,760,347 bytes，SHA-256 `f7f436d05381bc0bfb1d6846d5e0cb1995b89b213bd2cb48ed6df40af3c3cce0`）。包内内网参数沿用 20260806 全新包先例：平台 `172.22.10.115:30001`、后端 `30002`（127.0.0.1）、PostgreSQL `15434`（127.0.0.1）、LDAP `http://172.22.10.116:80`，`GITLAB_DELETE_RECONCILIATION_ENABLED=false` 显式保持，`COMPOSE_PROJECT_NAME=qaflex-20260904t104638z-8cf0508d8832` 强制注入 `PLATFORM_INSTANCE_ID`。代码基线 = 本地 main `f66aff80`（该树黄金基线 180/180 零差异，用户明确本次不再跑回归）；manifest 如实标注工作区非干净（仅同事 untracked WIP 测试文件，未阻塞构建，backendTestSourceFallbackUsed=false）。
- [验证] 打包器默认门禁全过：前端发布测试 17/17、typecheck、生产构建、后端 clean package、双业务镜像无缓存构建、镜像内 app.jar/index.html 摘要与本地生产产物核对一致、Compose 解析、SHA256SUMS 7 文件校验。独立审计：Flyway `20260903.01` 与最新迁移 `V20260903_01` 一致、目标镜像同 release-id、包外 `.sha256` 与实测归档哈希一致、归档清单严格符合全新包结构契约（无 `backend/`、`frontend/`、真实 `.env`、数据库 dump、运行日志）、打包器契约测试 30/30、四项仓库门禁全绿。
- [验证] 本地隔离部署演练已完成（2026-09-04 晚，忠实按包内 README 流程：解压到 `localtest-30001-20260904/` → docker load 三镜像 → .env → compose up）：postgres/backend/frontend 三容器全部 healthy，后端 `/actuator/health` UP，前端 HTTP 200，空库 Flyway 130 项迁移至 `v20260903.01`，经前端 30001 的 `/api/` 代理返回后端正常未登录 401（Nginx→后端接线正确）。唯一本地偏差：PG 主机端口 15434→15437（15434 被本机既有 GitLab 代理容器占用）；登录依赖内网 LDAP 本地不可达属预期。内网 30001 部署与缺陷测试由用户执行，现场命令以包内 `README-INTRANET-DEPLOY.md` 为准。
- [验证] 内网 30001 测试结果（2026-09-07 用户同步）：三类现场问题全部解决——登录 403 A0303（浏览器缓存旧 20260806 前端 vs 新后端 CSRF 交付机制不匹配，代码+本地镜像双向实证）强制刷新恢复；CCProduct 里程碑分组缺失（V20260727_02 空库播种 0 组）由内网 AI 按迁移逻辑手工重建生效；FACT_REFRESH 互锁（单一大事务+180s 任务租约无续租+假超时重试级联）等待自然收敛完成事实层重建。遗留工程缺陷未修：完整根因链与分层修复方案（P1 配置缓解/P2 五项工程修复/nginx no-cache 加固）见 `docs/plans/intranet-test-issues-resolution-20260904.md`，待批决策 = D-2（20001 里程碑分组时机）、D-5（nginx 加固）、D-6（P2 排期）。20001 保数据升级时将复现事实构建互锁（等待可收敛勿中途终止），且升级后首次成功的 ISSUE 事实构建会自动 bootstrap 里程碑分组。

## 2026-09-04 黄金基线套件更新（12 维度基线）

- [完成] 快照套件更新至当前实现（用户"版本趋于稳定"指令触发）：`endpoint-catalog.yml` 为 values 端点补 `person-page1` 用例（按镜像直取单元计划约定锁定 person 维度镜像直取行为）；`-Dgolden.update=true` 重建全量快照。唯一语义变化 = label-groups dimensions 16→12 维度（源自 58e770a7）；其余 60 文件差异经语义级全量审计全部为掩码易变字段刷新（时间戳/runId/testcontainers 随机端口/retentionHours 库龄）、`ignore-array-order` 已声明集合乱序与键序噪声。
- [修复] 更新后比对复跑暴露既有缺陷：`IssueFactRecordRepository.findForFilterOptions` SQL 缺 `order by id`（全量路径有、此漏拼），question-metrics issues/filter-options 的 assigneeNames 等 75 项候选顺序随查询计划漂移（PG 无排序行序不定 + 候选工厂 LinkedHashSet 首次出现序）——与基线建设期修复的"FACT_SQL 无 ORDER BY"同族漏网点。追加既有 `FACT_SQL_ORDER` 固定行序，候选集合不变仅顺序确定化（受影响快照已按新序重建）；受影响单测 12/12。
- [验证] 更新模式 180/180（第二轮 3 处 Windows 文件锁瞬态 Error，涉及文件均为掩码字段且 JSON 完整）；比对模式复跑 180/180 零差异（BUILD SUCCESS）——候选顺序稳定性经两次独立运行实证。夹具未动（baseline-manifest.json 零变化），基线版本不变，D-08 无需留痕。
- [状态] 已同单元提交 f66aff80（目录+快照+实现修复+文档）；推送 origin 与 18080 重启待用户决定。

## 2026-09-04 标签组成员候选全量分页加载

- [完成] 成员值候选从"单页 50 条"改"循环拉全部分页"（方案 `docs/plans/label-group-member-candidates-full-load-20260904.md`，仅改前端 `LabelGroupMemberPicker.vue` + 测试）：`fetchValues` 契约扩为 4 参（dimensionKey/keyword/page/size），按 200/页（后端单页上限）顺序拉取至 total 取满或空页终止，按 value 去重合并；`loadToken` 自增令牌丢弃 keyword/维度切换时旧分页循环的在途结果。后端零改动。背景：person 维度 461 行按字母序 ASCII 先于中文，首屏 50 条全拼音致用户误判缺中文人员。
- [验证] 组件测试 9/9（含新增多页合并与竞态丢弃用例；实施期修复前次遗留的测试桩参数遮蔽缺陷）；typecheck 绿；全量 vitest 459/459（首轮 1 例未捕获名失败，两轮复跑全绿，与既有偶发模式一致非回归）；UI 实机验证：候选来源=人员 触发 page=1..4&size=200 共 4 请求，下拉渲染 456 项与库内去重名（461 行含 5 重名）精确一致，中文名 140+ 位起可见，关键字"王"搜索 34 项命中。用户决策留痕：bot/系统账号不剔除；`carmazhao` name 前导空格脏值（全库唯一，GitLab 源数据原样同步，被选入组有精确匹配失配风险）暂不处理待用户厘清；排序语义不改。已提交 ad8bda1b。

## 2026-09-04 标签组候选来源改镜像库直取

- [完成] 静态标签组候选值改镜像库表直取（方案 `docs/plans/label-group-mirror-candidates-20260904.md`）：删除 5 个旧人员维度（review_owner/review_expert/issue_assignee/customer_author/customer_assignee）合并为单一 person（镜像 users 表全量人名）；project 改镜像 labels"项目："标签全量解析（与新增评审项目名称下拉同源同值）；milestone 改镜像 milestones.title 全量；模块/测试阶段保持现状；复用 `ReviewDataMirrorOptionRepository`，零迁移；删除死代码 `existsStaticCandidateValue`。前端同步接线：review 页负责人/评审专家字段 `labelDimensionKey` 改 person，customer/system-test 页人员字段新增 `personConditionField` 携带 person，删除指向已删键的别名映射。
- [验证] 后端 labelgroup 测试 27/27；前端受影响 16/16、typecheck 零错误、全量 vitest 457/457；后端全量默认套件（注入测试库 env）1241 项 / 1 失败 / 0 错误——唯一失败为同事 untracked WIP `ReviewDataRecordReadSupportTest`，零回归；四项门禁脚本全绿。已提交 58e770a7 并推送；黄金基线 dimensions 快照重建见"黄金基线套件更新"条目。

## 2026-09-04 内网评审页间歇性故障修复

- [完成] 兼容模式同步治本（方案 `docs/plans/intranet-review-intermittent-failure-20260904.md`）：Mongo 5 处 replace 改"raw_payload 守卫 upsert + 键反连接点删"（未变行零写入，id/synced_at 稳定、指纹不漂移），MySQL raw 行同模式、代码走查表（无唯一键）改装载表原子换名接管（rename 后 `ALTER SEQUENCE ... OWNED BY 新表.id` 移交序列所有权，提交后 ANALYZE），全部 5 处 TRUNCATE 删除，零新增迁移。指纹 5+2 段 3s 短超时 + 首败短路 + 5 分钟桶降级标记，降级时 readOrRefresh 读最近快照（含 STALE）；readOrRefresh 增加进程内 single-flight 合并同键并发重建（失败共享）；match 仓库读路径点查化；评审写后异步预热 FILTER_OPTIONS；两个 @RestControllerAdvice 合并为单一 GlobalExceptionHandler（DataAccessException →"数据库操作失败，请稍后重试"，兼容模式记录缺失改 BizException）。
- [验证] 受影响单测 16/16；testcontainers 真实 PG 集成 6/6（Mongo 合并 4 + MySQL 换名/raw 合并 2，实证 id/synced_at 稳定、消失行点删、换名+序列移交+索引存在）；全量默认套件注入测试库 env 后 1239 项 / 1 失败 / 0 错误 / 1 跳过——唯一失败为同事 2026-08-25 untracked WIP（`ReviewDataRecordReadSupportTest` 期望值不一致），零回归。实施期发现并修复换名方案真缺陷（退役表持有序列所有权致 DROP 失败）。
- [状态] 已提交并推送 6707b433；当时并存的解耦第二阶段改动已随后归集提交 b66fe7dd。黄金基线门禁已在套件更新单元执行（180/180 零差异）。

## 2026-09-02 黄金基线回归测试系统

- [完成] 全链路黄金基线回归系统建成：冻结夹具（GitLab 源切片 Issue≈1200/MR≈1500 + 平台种子 16 表，manifest 记 SHA-256）→ Testcontainers 双 PostgreSQL 真实链路（全量同步→事实→统计）→ 165 端点目录（READ 62/OPTIONS 11/EXPORT 16/WRITE 33/EXCLUDED 43 全部显式登记原因）→ 严格快照 229 个（读/导出 139 + 写响应 33 + 写表状态 57）。运行入口 backend 目录 `mvn test -Pgolden-baseline -Dtest=GoldenBaselineChainTest`（需 Docker）；快照缺失即失败，重建须显式 `-Dgolden.update=true` + git diff 人工审阅；默认快速套件排除 golden-baseline 标签不受影响。
- [验证] 173 用例（139 读/导出 + 33 写 + 1 链路 smoke）在全新运行零差异（两轮确认：417.8s 与突变还原复绿 370.1s）；突变自证通过——破坏 `displaySeverityLevel` 标签 → 25 处失败全部为可读 diff（快照名+JSON 路径+期望/实际），删目录条目 → 覆盖护栏 2.3s 失败并精确指出未登记端点，还原均复绿。默认快速套件注入测试库 env 后 1196 项 / 1 失败 / 0 错误（唯一失败为同事未提交 WIP 测试，先前存在）；SpotBugs 0 bugs；Checkstyle 仅 2 处违规均在同事 untracked 文件；四项门禁脚本全绿。本单元零新增失败。
- [修复] 建设过程根治的生产级缺陷：导出 `sortField=null` 在不可变 Map 上 `getOrDefault` NPE ×3 处；match_mode 唯一约束迁移硬编码 public schema；查询非确定性 3 处同根因（`FACT_SQL` 无 ORDER BY、候选值 `string_agg` ×30 无序、`permissionsForRoles` distinct 无序）加排序根治；2 处 sortColumn null 防御。
- [运行] 实测全量含容器启动/真实同步/写用例约 7 分钟（测试本体 ~400s），高于 1 分钟理想目标，差距来自双容器+Flyway+真实同步+173 端点遍历的固有成本。
- [文档] `architecture.md` 新增"黄金基线回归测试"章节；`decisions.md` D-08 基线冻结与变更规则；计划 `docs/plans/golden-baseline-regression-20260901.md` 已收口。
- [基线状态] 2026-09-02 与用户核实：内网验证过的版本为 2026-08-06 全量包 `qaflex-full-20260806T111653Z-8269d2b61253`（该提交与空平台里程碑前向迁移分支 `codex/golden-baseline-predecouple` 的 `0ccd4b1c` 均在独立克隆谱系，不在本仓库）；内网测试发现的小问题（空平台 CCProduct 里程碑列表为空等）已在本地修复，解耦前修复与解耦重构均未再进内网。当前输出快照基线对应本地开发树，属开发期基线（能捕获解耦收尾期的产出漂移，不能证明与内网验证版产出一致）；解耦版内网测试通过后 `-Dgolden.update=true` 重冻结升级为可信基线（已记入 D-08 与 architecture.md）。
- [解耦状态] 2026-09-02 实证核对并更新 `decouple-round2-20260824.md`：R2/R3/R4 拆分件已实现并接线（阶段日历按"保留双语义"路径统一为 IssuePhaseCalendarLoader），实际进度领先文档；R5 经用户决定暂停；R6 收口待办（含 R4 红灯测试修正——经逐字符对比定性为测试期望值错误而非行为变化，解耦契约"产出与解耦前一致"由 golden 两轮零差异佐证）。
- [下一步] 工作树含同事未提交改动，本单元文件清单与提交动作待用户确认后执行；提交动作建议随解耦收尾一并规划。

## 2026-08-13 BI 编码走查数据源治本：移除重复抓取链路

- [完成] 编码页人工走查兼容态已改为直接复用兼容表 `code_review_match_mode_records`（与代码规模类同一读源），删除 BI 独占表 `bi_code_review_compatibility_records`、`_loading`、`_sync_state` 及 `BiCodeReviewCompatibilitySyncService`/`Scheduler`/`SnapshotRepository` 重复抓取链路（迁移 `V20260813_01`）。编码页底部走查/扫描/注释率看板不再依赖老平台 MySQL 实时同步，离线也能读兼容表数据。核对表、`data-contracts`、`architecture` 已同步：人工走查兼容态物理来源由 `bi_code_review_compatibility_records` 收敛为 `code_review_match_mode_records`，删除"BI 独占/不得回退"旧约束。
- [验证] 后端全量 1157 项零失败、零错误、1 项环境条件跳过；前端 typecheck 与生产构建通过；127 个 Flyway 迁移不可变性、破坏性审查通过。编码页 CC2026R3 底部走查看板（走查质量/问题分布/模块质量/散点/静态扫描）由 EMPTY 恢复为 READY；注释率与质量趋势因部分记录缺注释率保持 INCOMPLETE（真实数据状况，符合 CD-38）。走查行集合现按 `legacy_merged_time_source` 过滤 + MR IID 去重，与 MR 事实对齐。

## 2026-08-10 BI/CAT 与事实发布一致性修复

- [当前] 2026-08-06 内网 BI/CAT 缺口的生产实现与本地验证已完成：事实发布已改为依赖代际驱动的来源级消费，CAT 保存已原子化，BI 合法观测、提交稳定关联和系统测试 P1/P2 已按确认口径修复。下一步是生成新的内网隔离发布包并执行真实 CAT、BI 页面和约 280 万总表规模验收。
- [完成] BI 产品版本入口已改为后台目录首项默认、模块内部当前版本继承和显式深链优先三种单一语义；系统测试轮次只展示有有效缺陷事实的目录子集，代码走查质量图按评审/编码数据域使用正确单位，并以共享自适应值轴保留极值、压缩显著空白区间。该修复只修改 BI 页面、BI 计算器及通用路由参数契约，未修改平台其它表格。
- [迁移] 当前迁移头为 `V20260821_01`，128 个迁移已更新并通过不可变性校验；提交事实错误仓库名冗余列已删除，审计角色列已改为 `text`，关键运行引用默认限制删除。
- [下一步] 按内网离线发布标准生成全新隔离部署包；部署后执行自动增删改到 ODS/事实/投影、BI 六阶段页面和 CAT 正式四接口联调。CAT 证书链、认证和完整路径仍以维护方或浏览器 Network 的真实请求为准，不在客户端猜测或 trust-all。
- [验证] 只做直击真实控制流的确定性测试，本地数据量不冒充约 280 万总数据容量；发布前执行后端全量测试与静态分析、前端全量测试与构建以及仓库四项门禁。
- [验证] 当前代码状态后端 1138 项零失败、零错误、1 项环境条件跳过，可执行 JAR 构建成功；前端 115 个测试文件、421 项、ESLint、TypeScript 和生产构建全部通过。真实 PostgreSQL 删除链确认标签物理删除进入 ODS 后，由来源级事实运行清除 `issue_fact` 旧状态并发布目标和投影；READY SQL、失败目标释放、失败权威范围重放及 CAT 双表事务均有直接数据库回归。Flyway 126 项不可变性校验通过，本地结果不外推约 280 万容量。

## 2026-08-25 黄金线基线候选部署

- [否决] 独立项目 `D:/projects/data_collection_platform_golden_20260821` 的 `d15ac937` 候选未通过空平台业务验收：`CCProduct/325/MILESTONE` 目录为 0 组、0 成员，客户问题页面和阶段定义页的 325 里程碑列表仍为空；该候选不作为黄金线。
- [根因] `V20260821_01` 只从既有 `issue_fact` 回填，运行期协调器只在非空 325 事实首次发布后建目录；本地开发库因已有 1512 条 325 事实而生成 9 组、9 成员，曾显示正常不能证明空平台修复。项目 9 的测试阶段目录在独立空库中已有 11 组、50 成员，与 325 目录不是同一数据状态。
- [调查] 全部分支、reflog、stash 和不可达对象均没有另一版完整修复；现有 Git 历史中不存在同时包含 `projects/users` 修复且满足空平台 325 里程碑验收的提交。
- [完成] 两套本地 GitLab 数据库、ODS 和开发目录一致确认项目 325 的 9 个活动里程碑；已从解耦前 `c093db20` 创建独立分支 `codex/golden-baseline-predecouple`，提交 `0ccd4b1c` 仅增加空目录前向迁移、迁移回归、校验清单和架构契约，并继承 `3a3a4ff3` 的 `projects/users` 修复。已有任意人工组时迁移不补种、不覆盖。
- [验证] 迁移测试 2 项和 Flyway 烟测 19 项通过，Checkstyle/SpotBugs 零问题；Flyway 不可变性/破坏性/配置覆盖、文本和产物门禁通过。全新包 `qaflex-full-20260825T033623Z-61991b4f5011` 为 clean `0ccd4b1c`、Flyway `20260825.01`，17 项前端发布测试、类型检查、生产构建、后端生产包、镜像摘要、Compose、归档及 30 项打包器测试通过，归档 SHA-256=`a9b6c6aea49bddfd147392619356326c7eb3f45e2cf387dd1c3dc1d960916dab`。
- [验证] 新包已在 `30201/30202/15436` 以全新 volume 启动，三容器健康、后端 `UP`、前端 HTTP 200；127 个迁移完整成功，项目 9 目录为 11 组/50 成员，项目 325 目录为 9 组/9 成员且顺序为 `CC2026 R3` 至 `CC2024 R3`。
- [运行] 黄金线已恢复在 `30201/30202/15436`，LDAP 已恢复在 `28081/28082`；常用 GitLab `gitlab-data-web-1` 健康，通过 `qaflex-gitlab-cc-socket-proxy:15434` 以 `DIRECT`/peer 只读链路连接 `gitlabhq_full_import_test`。源库实查为 260 个项目、8005 个 Issue、10000 个 MR 和 66165 条标签关系；全量同步 `665/665`、扫描 621476 行、应用 310738 行成功，事实刷新 `6/6`、应用 18005 行成功，ODS 与事实层均为 Issue 8005、MR 10000。System Hook、API Token 和自动同步仍关闭。
- [下一步] 使用 LDAP 登录 `http://localhost:30201`，验收阶段定义页和客户问题页面的里程碑可见结果、验证 `projects/users` 同步终态并开始覆盖数据与黄金线采集。若页面数量少于 ODS/事实层，按页面项目、版本和状态过滤条件定位；当前不推送，远端尚未由用户指定。

## 2026-08-24 解耦第一轮收口

- [当前] 第二轮解耦已进入 R2；R1 仅补事实任务服务行为测试，不改生产代码。`FactBuildTaskServiceTest` 在 `qaflex_clean` 隔离库实跑 14 项全绿，已锁定 advisory lock、SKIPPED、重试终态、运行汇总和来源 scope 归一语义；下一步按 `docs/plans/decouple-round2-20260824.md` 拆分阶段日历、事实行映射与搜索索引职责。
- [修复] 2026-08-24 本地系统测试无数据的根因是平台数据源配置连接了不完整的 `gitlabhq_production`，而 Docker GitLab Rails 实际运行库为 `gitlabhq_full_import_test`；已将本地 `gitlab_sync_configs.id=1.db_name` 切换到实际业务库。全量镜像运行 `2269` 成功（665/665，扫描 700527、应用 359543），事实刷新运行 `2271` 成功（6/6，应用 18005），项目 9 带测试阶段事实恢复至 4983/5774 条，系统测试非法数据 `CC2026R3` 页面返回 1816 条。当前本地样本没有 `CC2026R4` 测试阶段数据，R4 默认筛选为空属于数据状态，不是同步代码回归。
- [完成] 2026-08-21 客户问题 325 里程碑目录修复已并入当前代码：运行期首次事实发布可初始化目录，标准 CC 版本按业务键归一化，已有人工目录只补同键成员；`V20260821_01` 覆盖已有事实但目录为空的升级场景，`GitlabSourceSchemaGuard` 强制校验里程碑源结构并删除空值 SQL fallback。相关协调器、迁移、目录/业务键、源结构/事实 SQL、Flyway 和事实构建回归均通过。
- [完成] 阶段三完成 `CodeReviewIllegalRecordService` 职责拆分：Excel 导出、筛选选项装配、响应映射分别外移，删除零调用死代码；公开控制器契约、导出列布局和兼容模式分支未改变。
- [完成] 阶段四、五完成前端职责外移：`BaseRecordTable` 列宽/展示纯逻辑和 `MirrorSettingsView` 健康展示、配置指纹、诊断编排分别进入独立模块；页面模板和 API 契约未改变。
- [验证] 后端 Java 21 编译和全量 `mvn test` 在新建隔离库 `qaflex_clean` 上通过：260 个测试套件、1169 项，0 failures、0 errors、1 项条件跳过；包含 `FilterEngineSqlParityTest`。此前复用历史库 `qaflex` 的 10 个套件失败均由旧 schema 不完整造成，不作为源码回归结论。
- [验证] 全局 Checkstyle、SpotBugs 通过；前端 122 个测试文件、446 项、TypeScript、ESLint 和生产构建通过。
- [验证] `check_worktree_artifacts.py`、`check_runtime_artifact_locations.py`、`check_text_whitespace.py`、`check_frontend_api_boundary.py`、Flyway 不可变性/破坏性检查和 `git diff --check` 全部通过。
- [验证] 当前 `18080` 后端以 LDAP 配置连接 Docker GitLab `gitlab-data-web-1`，真实全量 `300/300`、事实刷新 `6/6`（写入 15392 行）、增量 `20/20` 均完成；`resource_label_events` 游标为 `[\"387476\"]`、待修复为否、错误为空。
- [验证] 真实 API 烟测通过 25 个只读端点；`18181` 页面实测评审数据、代码走查、系统测试、客户问题均可加载筛选区和数据，代码走查返回 8545 条、评审 440 条、客户问题接口 906 条、系统测试接口 3608 条。
- [约束] 旧 `qaflex` 测试库保留原状但不作为全量测试基线；后续后端全量验证使用干净数据库或明确隔离 schema。当前工作树包含他人未提交改动，不提交、不推送、不回滚。
- [范围] 当前工作树的 statistics 目录另有 7 个文件共 11 行未使用 import 删除，属于合并后的既有同事改动；本轮解耦未改变其业务逻辑，验证时保留并覆盖该状态。
- [修复] 合并后补齐同步执行器在“全量扫描 + 单调主键”终态的游标语义：非空终态保存本页最终主键，空终态写入 `[]` 清除旧游标；新增 `SyncRunTablePageCommitServiceTest` 2 项覆盖非空和空页，避免下一轮重复使用陈旧游标。
- [限制] `FilterEngineSqlParityTest` 依赖外部 PostgreSQL 凭据；本次独立复核因 `localhost:15433` 凭据/上下文未建立而产生 3 个环境性 Error，不作为源码回归结论。此前在隔离库 `qaflex_clean` 上的 1169 项全量结果包含该测试并全部通过。
- [约束] 解耦版本完成内网部署并通过现场测试后，建立唯一行为基线，逐项记录所有按钮、功能和显示结果；测试数据由本地 GitLab 生成并覆盖全部业务场景，代码走查非法数据至少覆盖每种非法类型、多个非法类型同时命中及其他已定义组合各一条。后续变更须使用相同数据和操作步骤与基线做结果对比。

## 2026-08-21 深度代码审计与第一轮重构

- [完成] 已完成第一轮全仓静态扫描、核心链路深读和基于真实行号的违规清单；后续阶段在不改变业务公式、事实发布代际、数据库迁移或外部 CAT/GitLab 契约的边界内完成。
- [完成] 前端请求客户端已合并超时/取消执行路径，JSON 边界由 `any` 收敛为 `unknown`；BI API 客户端已归入 `frontend/src/api-client/`；日期显示、异常消息提取和两个生产 `any` 已收敛到可测试工具/辅助函数。
- [完成] `FactBuildService` 不再手动创建事实来源 SQL provider/query executor，两个协作者由 Spring 注入；`FactBuildServiceTest`、来源 SQL/查询执行器测试已同步装配。
- [验证] 前端 typecheck、异常/日期/标签组定向 15 项测试和本轮定向 ESLint 通过；Docker Java 21/Maven 定向后端测试通过并完成生产/测试源码编译。
- [风险] 工作树包含用户已有大量删除、迁移和文档修改，本工作单元继续保留；不执行破坏性 Flyway、真实全量同步、发布或推送。

## 当前目标

- [完成] GitLab 交接快照已从实际 Compose bind mount `D:/gitlab-data` 与 `D:/gitlab-data-dgm`（均 GitLab CE 16.11.10）一致停机生成，不使用较旧的 `D:/数据采集平台数据` 副本。归档 `D:/gitlab-handover-backups/gitlab-handover-20260825T065156Z.tar` 为 14,437,099,520 bytes，SHA-256=`c4f8965b7d107463c8f09d5623725c968ad87239dc40456e1a2aeedea644d99f`；保留 `config + data`、Compose 和三份评审 JSON，仅排除顶层日志与运行 socket/Gitaly 临时端点。两套 `gitlab:check` 通过且归档后健康恢复；DGM 密钥检查通过，CC 已有 WebHook/User/Project 等加密字段不可解密，交接必须作为既有风险。隔离恢复演练尚未执行；不将含密钥数据推送到代码仓库。
- [目标] 旧发布 `20260803T054734Z-066761e14130` 已被后续修复替代；新发布 `20260803T082443Z-2ab4bea80a7b` 及其 20001 完整 Compose/`.env` 已完成，当前等待用户人工部署与现场验收。
- [目标] 2026-08-05 GitLab 镜像回归修复：代码、完整自动化门禁、本地 GitLab 10 分钟自动 Issue/标签增删链路及全新隔离发布包均已完成。下一步在内网 `172.22.10.115:30001` 部署并执行约 280 万全部同步表总量的删除反熵基准、无事件静默删除和前台让行验收；删除反熵在验收前保持关闭，容量结论不使用本地小规模数据外推。
- [目标] 在不破坏已完成 LDAP、本地 RBAC、兼容模式隔离、事实层和导出对齐工作的前提下，继续完成老平台口径核验、内网部署验证和正式模块稳定化。
- [调查] 2026-08-05：20001 `issues` 滞后的异常版本行为由 `2fd5c34e` 中日常物理删除修复引入；它把约 280 万全部同步表总量的全表 `RECONCILE` 接入普通增量/单表刷新并引入 `DELETE_ONLY + SUCCESS + 0 行`。现场 30 秒单表、20 分钟全量和三天运行事实排除“单轮积压”作为唯一根因；确定的控制面根因是运行范围扩大后，同类触发只复用旧窗口而无尾部补跑，非完整运行又可推进 `last_incremental_sync_at`，绿色终态不校验 `issues`、来源上界和表 checkpoint 覆盖。全局时钟与复用缺陷早于该提交潜伏存在，`2fd5c34e` 放大并暴露；10 分钟配置和 `553f7a49` 均不是原因。
- [调查] 2026-08-06：全新包内网 BI/CAT 根因调查已闭环。CAT `500` 是本地 `timestamptz/text` 绑定和双表非事务；系统测试 P1/P2、设计、扫描和质量趋势分别是错误全局门禁或前端空序列；编码提交同时存在首次事实失败不恢复及仓库名/产品版本错配。系统测试暂态“未归类”和提交事实空表均由 `2fd5c34e` 引入的事实提前发布策略触发；修复还必须覆盖下一轮镜像并发、事实族依赖资格、旧失败权威范围和历史 outbox 自动收敛，不能只前移父运行检查。BI/CAT 原始实现来自 `main@4f145def` 的未提交工作树并打入 `20260806T111653Z` 全新包；相关生产修复与本地验证现已完成，待重新打包和内网验收。
- [目标] 以 LDAP v0.3 作为内网账号、状态与多角色来源，确保空平台首次部署后可直接使用 LDAP 登录并建立平台本地 Session。

## 已完成

- [完成] 2026-08-10：`projects/users` 自动增量失败已按根因闭环。动态 ODS 索引改由运行期 schema 控制面维护，全部白名单结构在任务入队前完成；维表反向根详情和事实 outbox 改为有界批量；瞬时来源/数据库故障复用原表任务和父运行退避，确定性契约错误仍直接失败，游标、水位、删除核对和事实依赖门禁不因失败前移。
- [验证] 2026-08-10：后端全量 1161 项零失败、零错误、1 项环境条件跳过；`projects/users` 反向解析、全部依赖索引实际创建、无效并发索引修复、批量事实版本、任务/父运行重试及 DIRECT/Docker 异常语义的定向与 PostgreSQL 集成测试通过。Checkstyle/SpotBugs 零问题，126 个 Flyway 迁移不可变性、破坏性审查、Profile 覆盖和隔离 schema 完整迁移链通过。本地结果仅验证控制流和数据库契约，不外推内网约 280 万总表容量。
- [完成] 2026-08-10：同一 Chrome 访问同主机不同端口的平台实例时，Session/CSRF 已按稳定平台实例身份隔离。标准 Compose 将跨更新不变的 `COMPOSE_PROJECT_NAME` 注入 `PLATFORM_INSTANCE_ID`，后端派生不暴露部署名的专属 Cookie 名；直接运行按应用名与监听端口隔离。前端删除固定 Cookie 读取，改为接收 `X-XSRF-TOKEN` 响应头并按 Origin 保存；LDAP 认证、账号、本地授权和业务接口不变。
- [验证] 2026-08-10：同一 CookieJar 交替访问两个真实嵌入式 Tomcat 时，两边 Session 计数均独立保持 `1→2` 且两个专属 Cookie 同时存在；后端 1143 项零失败、零错误、1 项环境条件跳过，Checkstyle/SpotBugs 零问题并完成生产 JAR；前端全量 115 文件/421 项、类型检查和生产构建通过，最终请求层 15 项通过；离线打包器 30 项、真实链冒烟脚本 1 项及跟踪产物、文本、差异门禁通过。最新版后端已在 `18080` 健康运行，响应使用 `QAFLEX_XSRF_*` 和 `X-XSRF-TOKEN`，不存在共享 `XSRF-TOKEN`。运行产物位置仍仅被仓库根目录既有 8 个 `.tmp-*.log` 阻塞，本轮未删除用户文件。
- [完成] 2026-08-06：全新空数据包 `qaflex-full-20260806T111653Z-8269d2b61253.tar.gz` 已生成，大小 `306,658,041` bytes，SHA-256 `71d339998867cea32d3f47c9ad4a53613bda420e232f761381980dd166f34d7b`，目标 Flyway `20260806.02`。包内内网参数为平台 `172.22.10.115:30001`、后端 `30002`、PostgreSQL `15434`、LDAP `http://172.22.10.116:80`，删除反熵显式关闭；Compose project 为发布级唯一值且不写固定容器名，容器、网络、数据库卷和日志卷均按 project 隔离。
- [验证] 2026-08-06：三张镜像均为 `linux/amd64`，包内 7 项与包外 SHA-256、归档清单、30 项打包器测试、前端 17 项发布测试/类型检查/生产构建、后端 clean package 和镜像内产物摘要通过。本地隔离栈仅因现有 GitLab 代理临时覆盖数据库端口为 `35432`、LDAP 为本机可达地址，包内参数未改；三服务 healthy，后端/前端 HTTP 200，Flyway `20260806.02`，30001 登录首屏无控制台 warning/error，原 `qaflex-*` 三容器 ID 和健康状态未变。该结果只验证部署链和资源隔离，不替代内网 280 万容量验收。
- [完成] 2026-08-06：系统测试横向对比与客户问题缺陷汇总统计已补齐 P2/P3 缺陷关闭率，统一按当前优先级已关闭数除以总数；系统测试与客户问题“全量议题数据”已在影响功能列后补齐“已知的受影响功能”“新识别的受影响功能”，新版模板输出“是/否”、旧模板输出“--”；客户问题空测试阶段统一输出“未设定测试阶段”。普通系统测试查询和非法数据布局保持原契约，客户问题统计快照版本已同步提升。调查未使用用户明确排除的本地历史下载文件。
- [验证] 2026-08-06：四类导出目标测试 15 项、共享原因解析/客户阶段/系统测试查询与非法数据回归 29 项通过；Java 21 生产与测试源码编译通过，Checkstyle 零违规，SpotBugs 零问题，跟踪产物、文本空白/行尾和 `git diff --check` 通过。最新工作树后端已重新启动并在 `18080` 返回 `UP`；运行产物位置检查仍仅被仓库根目录既有 8 个 `.tmp-*.log` 阻塞，本轮未删除用户文件。
- [完成] 2026-08-06：评审数据管理“导出问题列表”已从 16 列按评审聚合表改为与老平台页面问题清单一致的 15 列逐问题明细；全局筛选导出和单条评审导出共用同一写出契约，每个问题项一行并重复项目、评审文档类型、工作产品和模块父字段。用户明确判定为错误历史版本的本地下载目录文件未参与调查或实现。
- [验证] 2026-08-06：`ReviewDataExcelExportServiceTest` 4 项零失败，覆盖完整 15 列顺序、全部代表性字段、更新时间格式及同一评审两个问题输出两行；Java 21 生产编译、全局 Checkstyle 与 SpotBugs、跟踪产物、文本空白和 `git diff --check` 通过。运行产物位置检查仍仅被仓库根目录既有 8 个 `.tmp-*.log` 阻塞，本轮未删除用户文件。
- [完成] 2026-08-03：从 GitHub 干净提交 `d2d3d4af` 和 721 直接基线生成更新包 `qaflex-update-20260803T082443Z-2ab4bea80a7b.tar.gz`（`196,891,673` bytes，SHA-256 `302b823442493fcac93476256f80d0121d2666a3f81aa162b85c4f7e9fb17621`），目标 Flyway `20260803.01`、事实重建 `all`。双镜像 `linux/amd64`、包内/包外摘要、归档清单、Linux Bash 语法、8 项包内校验和 25 项打包器测试通过。隔离栈完成 721 备份/升级/应用回滚/第二次备份/再次升级，两轮 dump 可恢复、`counts.diff` 为空，PostgreSQL ID 与 volume 全程不变，最终新前后端健康。
- [完成] 2026-08-03：包外 20001 配置目录 `qaflex-update-20260803T082443Z-2ab4bea80a7b-20001-config` 已生成；Compose 与包内权威文件 SHA-256 同为 `70f4161e4e385b33663ff29b6836f502f3ce7fccc49e0347b02a4c4be8da832a`，解析到新前后端镜像、20001/20002、LDAP 116、原 Compose project、PostgreSQL external volume 和日志卷；`.env` 未进入更新归档或 Git，禁止 `down -v`。
- [验证] 2026-08-03：新发布候选已在 `origin/main@1f977f49` 上合入 `CC_PRODUCT` 查询/计划分支成员和严格缺陷原因修复；后端定向 72 项通过，真实 PostgreSQL 删除全链同时覆盖普通增量与单表刷新，完整后端 957 项零失败、零错误、1 项条件跳过，Checkstyle/SpotBugs/Flyway profile 通过。完整前端 105 文件、374 项、TypeScript、ESLint、生产构建和高危依赖审计通过；118 份迁移及仓库契约门禁、同步 dry-run、25 项打包器测试通过。旧 release `20260803T054734Z-066761e14130` 已替代，禁止继续交付。
- [验证] 2026-08-03：系统测试与客户问题的缺陷原因归一化已收敛为唯一严格模板入口；缺标题、标题不在首行、粗体改写、缺少原因段、标签和自由文本均不再生成 `reason_category`，合法固定模板结果保持。该事实规则变化要求部署后按发布清单执行 issue 事实重建；本次包因 721 以来还包含共享事实变化，继续声明 `all`。
- [验证] 2026-08-03：`CC_PRODUCT议题` 的计划合并版本分支已统一按 `&`、半角/全角逗号和顿号读取成员；候选、精确筛选、表格与详情共享完整成员语义，事实原文、API、Excel 和严格非法模板校验保持不变。后端定向 23 项、前端成员与表格 8 项、CC_PRODUCT 领域与页面挂载 25 项、类型检查、目标 ESLint、生产构建、Checkstyle、SpotBugs 和前端质量检测通过；真实页面确认组合值在表格与详情拆为独立标签且控制台无错误，当前完整前端套件亦全部通过。
- [完成] 2026-08-05：GitLab 日常物理删除收敛已从普通增量的全表对账改为“快速增量 + 事件定向权威刷新 + 独立删除反熵”。普通增量不再创建全表 `RECONCILE` 或无来源读取的 `DELETE_ONLY`；只有完整覆盖必需表固定上界的成功增量推进全局时钟，活动增量之后的到期触发持久合并为一个尾部补跑。每轮实际白名单持久化为运行选择快照，使 `RECONCILE_ONLY` 子表无需伪扫描仍可由父对象变化触发权威刷新。标签最后一条关系删除已通过真实 PostgreSQL ODS tombstone、Issue 事实清除和投影发布全链验证；删除反熵来源失败不删除、不推进表级删除时间，并可在页边界和时间片为前台运行让行。
- [完成] 2026-08-06：针对真实烟测锁定的时间语义与可空快照回归完成修复。DIRECT JDBC 按 PostgreSQL 物理类型名区分 `timestamp`/`timestamptz`，DOCKER 偏移文本与 DIRECT 统一归一 UTC；`MirrorRowChange` 保留数据库 `NULL` 并复制为不可修改快照。46 项定向单元回归和真实 PostgreSQL 16 JDBC 时间集成 4 项通过；旧 Issue `42418` 的少 8 小时与可空行 NPE 根因已消除。
- [验证] 2026-08-06：当前工作树完整后端 1118 项零失败、零错误、1 项环境条件跳过，目标删除发布 PostgreSQL 集成 2/2 通过，Checkstyle/SpotBugs 为 0，可执行 JAR 构建成功；完整前端 114 个文件、409 项、ESLint、TypeScript 和生产构建通过。124 条 Flyway 不可变性、破坏性审查、事实字段、API 漂移和测试卫生门禁通过。全局前端 API 边界仅被并行 BI 文件 8 处 `/api` 直连阻塞，运行产物位置检查仍仅被根目录既有 8 个 `.tmp-*.log` 阻塞，本工作单元未修改这些文件。
- [验证] 2026-08-06：DIRECT 配置保持 10 分钟自动同步且全程未手工刷新。Issue `cloudcad/cc-product#2549`（全局 ID `42419`）由自动增量/事实运行 `524/525` 写入 ODS 和事实；标签新增事件 `390837` 由 `528/529` 写入 active 关系和事实；最后标签移除事件 `390838` 由 `530/531` 把关系 `338176` tombstone 并清空 Issue 标签事实，两类事实目标均发布。同 ID 的 3 条 Merge Request 标签关系保持 active。无事件删除仍须通过 280 万内网基准后启用独立反熵。
- [历史] 2026-08-03：`5e2eb2e6` 曾生成更新包 `qaflex-update-20260803T054734Z-066761e14130` 并通过隔离升级/回滚/再次升级；该包及其 20001 配置未包含后续修复，现仅保留审计，禁止继续交付。新的 20001 配置仍须绑定原 Compose project、PostgreSQL external volume 和日志卷，禁止 `down -v`。

- [完成] 2026-07-31：延期原因已按老平台规则收口：`delay_cause` 只保存七类合法原因，单独 `申请延期` 仍保留延期/闭环状态判定但客户问题回退为 `未设定类别`；多原因按标签顺序以 `&` 保存，客户筛选、候选、统计和 CC_PRODUCT 标签展示按成员处理，系统测试筛选继续保留事实、兼容字段和原始标签回退。本轮追加验证延期原因成员语义 44 项、系统测试汇总回退 2 项、前端目标文件 7 项、类型检查、生产构建、目标文件 ESLint、Checkstyle 0 违规和 SpotBugs 0 问题；目标实例仍需部署后执行 issue 全量事实重建。
- [完成] GitLab 同步运行时已统一容量、连接池、租约和分页调度：`SyncExecutionBudget` 同时约束运行 worker 与按 `configId` 管理的 DIRECT Hikari 池，运行保存不可变 worker 快照；运行和表任务均使用唯一 owner、心跳续租及条件终态，分页镜像写入、水位、任务完成和续页原子提交；全量补偿按可恢复 `SCAN/RECONCILE` 执行，并在已提交分页边界为增量和用户单表刷新让行。源端扫描统一为索引感知的真实类型复合主键 keyset、固定上界、JSON 游标和持久页码，旧文本排序与哈希分片已删除；同步命令仅使用已保存配置，不隐式写配置。当前/历史失败、连接池、阶段、cursor、重试和租约诊断已分离，未知总量不显示伪百分比；System Hook 精准刷新未纳入新调度。长期契约见 `docs/decisions.md`（D-04）。
- [验证] 2026-07-29：本地管理员真实链路完成增量 `484`、单表 `486/493`、全量 `488/490` 及自动事实 `485/487/489/492/494/495`；全量 `490` 两次让行后恢复并在 2 分 36 秒完成，前台增量 10 秒、单表 6 秒，`issue_fact=8002`、`merge_request_fact=10000`，全量期间系统测试与客户问题 `CC2026R3` 均返回 29 行稳定事实。运行 `484..495` 共 12 个成功运行、2,316 个表任务，失败、超时、重试均为 0，DIRECT 池空闲后为 `0/3`。管理员页面实测未保存配置会阻止同步命令并保持最大运行编号 `495`，刷新后恢复持久化配置。`resource_label_events` 后段分页由旧 `Seq Scan + Sort` 1,982ms 改为主键索引扫描 11ms；百万行等价 2,000 页续接测试、同步核心 85 项、前端动作/挂载 13 项、类型检查、定向 ESLint、生产构建、Checkstyle、SpotBugs、后端打包、Flyway 不可变性/破坏性审查/审查自测/Profile 覆盖和工作树门禁通过。内网 270 万行绝对吞吐仍须按 ADR 用 2/4/6 worker 实测。
- [完成] 已以用户确认的正式镜像 `20260728T100101Z-04f008f067c3` 为直接基线生成同步运行时保数据更新包 `qaflex-update-20260729T093338Z-72635b164fee.tar.gz`（195,940,925 bytes，SHA-256 `a34520768f63d65df2504b4dedcb257c1ec6aef5955b3d2fd9eb7930f46e846d`）；目标 Flyway 为 `20260729.03`，不要求事实重建或 GitLab 全量同步。14 项前端发布测试、类型检查/生产构建、后端干净生产包、无缓存 linux/amd64 镜像、镜像内摘要、Compose、25 项打包契约、Bash 语法、包内/归档校验和 Flyway 门禁通过。隔离栈从 `100101` 完成“独立备份→升级→应用回滚→第二次独立备份→再次升级”，两份完整/关键表 dump 均可恢复、`counts.diff` 均为空、后台调度最终为 true，PostgreSQL ID `e7f1632a...b53707` 与代表行数 `1/1/0/270` 全程不变；最终前后端为目标镜像且 healthy，后端 UP、前端 200。
- [完成] 系统测试横向对比导出已改为原子模块目录建行，代码走查组合模块不再生成或拆分导出行；其他质量看板的质量达人榜、功能缺陷密度和开发缺陷遗留率已按老平台页面口径统一，开发缺陷遗留率只读取系统测试事实。GitLab diff 行数由可恢复、限并发、按源隔离的后台状态机补齐并在事实互斥内定向发布，页面不实时访问 GitLab，确定性截断不会写入不完整指标。
- [验证] 2026-07-30：统计、导出、阶段匹配、MR/议题标题隔离、补齐状态机、真实本地 HTTP 往返、事实 SQL 和 Flyway 共 61 项定向测试通过；本地 PostgreSQL 迁移及可回滚真实应用链路完成 `RETRY -> RUNNING -> ENRICHED -> SUCCESS`，新增/删除行数写入外部指标后自动发布到对应 MR 事实并完整恢复测试数据。最新 JAR 成功校验 109 份迁移并升级到 `20260730.04`，队列默认值、按源索引和项目 9 隔离实库验证通过；Checkstyle 0 违规、SpotBugs 0 问题、Java 21 干净生产包及 JAR 内容检查通过。Chrome 真实页面确认开发缺陷遗留率只展示系统测试分子/分母且 `1676 / 3391 = 49.42%`，质量达人榜保留 10 名成员，功能密度页在本地待补齐数据下正常显示空态。本地 GitLab 16.11.10 当前过载，官方 API 与旧 Web diff 路径均约 11 秒后断开，内网真实 GitLab 历史补齐吞吐与两张代码规模图表绝对值仍需部署后验收。
- [完成] 2026-07-30：GitLab 物理删除收敛已统一到复合权威范围模型。Issue/MR 根实体及其指派、审核、指标、评论和标签集合由同一目录驱动，评论和标签分别按多态复合范围隔离；System Hook 删除、父资源增量和 GitLab 16.11 标签事件共用该模型。事实影响可从 `lookup_scope_json` 与根实体 tombstone 恢复父目标，Issue/MR 定向与全量发布均按来源实例先删除旧投影再写当前快照，空来源会删除旧事实及 Issue 客户成员。未在页面、统计、项目或标签名称上增加修补分支。
- [完成] 2026-07-31：自动事实刷新父子运行身份已收口为单一持久链：`fact_build_tasks.run_id` 只归属 `FACT_REFRESH` 子运行，worker 以该 ID 加载并校验子运行，再只从 `sync_runs.parent_run_id` 取得镜像父运行并查询父运行表任务。错误的 `mirrorRunId` 任务语义和无运行归属入队入口已删除；缺失父子关系、类型/配置/来源不一致及已有镜像工作量却缺失表任务链均显式失败，合法无变更与无关表仍返回空影响。未修改 ODS、事实字段、统计口径、页面或外部 API。
- [验证] 2026-07-30：同步/事实核心回归 93 项通过，修正现场核验发现的 GitLab 16.11 `resource_label_events.action` 整数枚举（`add=1`）；Java 21 的 644 个主源码和 204 个测试源码编译通过，Checkstyle 0 违规、SpotBugs 0 问题。Flyway 112 份迁移不可变性、破坏性迁移审查及自测、Profile 覆盖、事实字段、文本空白、跟踪产物和 `git diff --check` 通过。完整 Spring 集成测试在临时 `15433` PostgreSQL 可用后仍被并行工作单元的 `schema.sql` 引用缺失 `review_data_match_mode_report_description_refs` 阻断；另有 UTC 和统计断言既存失败，不计为本次通过。运行产物位置门禁仍仅被根目录既有 8 个 `.tmp-*.log` 阻断，未删除用户文件。
- [完成] 保数据更新包与正式目录已统一为单一完整 Compose：正式目录只保留 `docker-compose.yml` 和现场 `.env`，包内同名完整 Compose 使用固定 project 与 external PostgreSQL volume；备份发现分层 Compose 即拒绝，升级/回滚分别原子替换/恢复完整 Compose且不修改 `.env`。发布清单以 `compose.model=single-authoritative-file` 记录该契约，长期规则见 `deploy/intranet-offline-packaging-standard.md`。
- [完成] 已基于正式 `20260728T100101Z-04f008f067c3` 镜像生成保数据更新包 `qaflex-update-20260729T015548Z-98a927725b0b.tar.gz`（SHA-256 `8320b1aae4692985be6741ceb7afbd274655a3df5deb07bf92e0382487130aea`）；应用 JAR/前端摘要与直接基线一致，本次仅改变发布模型，不要求事实重建或 GitLab 全量同步。
- [完成] 已为 18181 已 down 实例生成不依赖 override 的单一完整重启配置：`D:/projects/data_collection_platform_deploy/qaflex-18181-restart-config/docker-compose.yml` 与同目录 `.env`。配置固定使用最终 `20260728T100101Z-04f008f067c3` 前后端镜像、LDAP/CSRF/后台调度正式参数和 18181/18080/15432 端口；Compose project 与 714 原部署一致，PostgreSQL 卷通过 `external: true` 精确绑定既有 `qa-flex-platform-intranet-20260714-runnable-empty-18181-18080-working_qaflex_pgdata`，名称错误时拒绝启动而不会创建空库。`docker compose config` 与镜像解析通过；该现场 `.env` 含数据库口令，仅作为用户明确要求的手工替换文件，不进入 Git。
- [完成] 内网 `qaflex-update-20260728T081900Z-fbaa04942c07` 首次升级因新后端启动后的 60 秒增量同步使 ODS 在 69 秒迁移校验窗口正常增长而误报行数破坏；失败包会留下目标 override/新后端与旧前端的混合应用状态，不能成为成功基线。现场须用第一次失败时 `counts.diff` 所在备份目录执行应用回滚，恢复最后一次完整成功的 `20260728T031131Z-cde8a2a0cb36` 前后端；不执行数据库恢复，正常新增 ODS 数据保留。
- [完成] 已生成修正版内网保数据更新包 `qaflex-update-20260728T100101Z-04f008f067c3.tar.gz`：包内独立 `backup.sh` 先生成并校验全库与关键表 custom-format dump、恢复目录、配置/容器/Flyway/行数证据和备份级 SHA-256；`upgrade.sh` 必须绑定有效预备份，停止旧后端后记录迁移起点，以全局后台调度关闭模式完成 Flyway/守恒校验，再显式恢复正常调度并切换前端。包内 README 是完整现场命令唯一入口，包含上次失败状态恢复、校验、备份、升级、验收、issue 事实重建和应用回滚；无需 GitLab 全量同步。
- [完成] 系统测试阶段成员匹配已收口为单一策略：缺陷汇总、延期分析和横向对比缺陷汇总使用包含成员语义；缺陷原因、阶段统计、议题查询和非法数据保持精确成员语义；多元看板按 8 张包含成员图表、3 张精确成员图表分别处理，页面与 Excel 原始数据共享相同范围。旧 SQL/内存 helper 已删除，请求内阶段目录只编译一次为不可变成员快照；不修改 `issue_fact`，无需事实重建，统计规则版本变更会使相关旧快照失效。
- [完成] 保数据离线更新包的后续制作流程已固化到 `deploy/intranet-offline-packaging-standard.md`：每次必须确认实例直接基线和基线代码差异，决定事实重建范围，依次执行计划解析、正式构建、产物独立审计，并在专用隔离栈完成升级、应用回滚和再次升级；交付前验证目标镜像、Flyway、健康、非空备份、行数守恒和 PostgreSQL 容器不变。包内 README 继续作为现场命令唯一来源。
- [完成] 已基于上一成功更新包 `qaflex-update-20260727T095530Z-e476d47a78af` 生成最新版保数据离线更新包 `qaflex-update-20260728T031131Z-cde8a2a0cb36.tar.gz`；目标 Flyway 为 `20260727.04`，部署后需人工重建 `issue` 事实，不触发 GitLab 全量同步。
- [里程碑] 已建立 GitLab 镜像、ODS、事实层、统计板运行时、前端 feature manifest、领域 API 和共享记录页底座。
- [里程碑] 质量看板、评审数据、代码走查、集成测试、系统测试、客户问题和系统设置入口已形成统一平台结构；仍需按产品和架构文档持续核验数据口径。
- [里程碑] LDAP 接入采用平台自有 Session；用户基础信息和角色从 LDAP 获取，平台本地维护细粒度权限及权限并集。
- [里程碑] LDAP Session 与外部 Bearer API 已拆分为两条独立 Spring Security 过滤链；LDAP 为默认 Provider，本地认证必须显式启用，外部 API 客户端配置不完整时启动失败。
- [里程碑] 权限设置页面已支持五个 LDAP 角色排序、权限编辑和恢复默认权限；登录态显示 LDAP 姓名，认证切换会重新挂载当前页面并刷新页面数据。
- [里程碑] CC/DGM 及老平台评审数据兼容路径已与正式数据源分离，相关代码保留 `兼容模式` / `Match mode` 标记。
- [里程碑] 评审数据导出、系统测试/客户问题统计导出、文件命名和模块来源等对齐规则已集中到业务…5869 tokens truncated…signees` 被标记为 `FULL_ONLY`，普通增量与手工表刷新均跳过；本地仅有 2026-07-03 全量任务，之后 236 次表刷新均未更新该关系，且 System Hook 关闭、全量补偿从未实际运行。本轮未修改业务代码或数据。
- [验证] 2026-07-28：客户问题议题指派人同步修复完成 Java 21 `clean compile`；权威范围新增/改派/空源取消、越界预检、事实影响范围、普通增量派生、System Hook 复用、其他精确表隔离和父运行动态任务汇总共 16 项定向测试通过；Checkstyle 0 违规、SpotBugs 0 问题，文本空白、跟踪产物和 `git diff --check` 通过。未执行本地或内网写入型同步/事实重建；运行产物位置检查仍仅被根目录既有 8 个 `.tmp-*.log` 阻塞，未删除用户文件。
- [验证] 2026-07-28：最终更新包大小 195,904,774 bytes，SHA-256 为 `cf31f39e658c1141c305b37b32648b31f143c1fb61cbf10be33428104b0c23bf`；前端 14 项发布测试、类型检查和生产构建，后端干净生产包，镜像内产物摘要，Compose，包内 7 文件校验，23 项打包契约测试和禁止内容扫描通过。隔离栈从上一成功包完成升级、应用回滚和再次升级，PostgreSQL ID `e7f1632a...b53707` 全程不变，两次 `counts.diff` 均为空，最终后端 UP、前端 200、Flyway 为 `20260727.04`。未执行事实重建或 GitLab 同步；根目录既有 8 个 `.tmp-*.log` 仍使运行产物位置检查失败，未删除用户文件。
- [验证] 2026-07-28：事实重建性能调查通过代码链路、本地 `sync_runs`/`fact_build_tasks`、PostgreSQL 统计和日志交叉确认。最近一次本地手工全量运行总耗时 180.973 秒，其中三类事实构建 98.900 秒、构建后同步快照阶段约 82.073 秒；8002 条议题与 10000 条 MR 在统计窗口内分别发生约 16005/20000 次更新，符合“全字段 upsert + 搜索影子字段二次 update”的双写路径。相同运行刷新 59 份统计快照和 14 份记录快照；本轮未执行写入型重建、未修改业务代码，内网各阶段绝对耗时仍需增加分段计时后实测。
- [验证] 2026-07-28：客户问题四类统计的稳定键/事实成员回归在修复前 5 项全部复现失败，修复后连同缺陷原因筛选契约、记录页和非法数据页共 25 项通过；Java 21 编译、Checkstyle（0 违规）和 SpotBugs（0 问题）通过。最新后端在 18080 健康运行，本地管理员真实 API 链路确认 R1/R2/R3 的四类统计均生成新规则版本非零快照；R3 缺陷汇总/原因/延期/响应效率分别有 486/76/152/35 个非零单元格，按功能统计保持 257 个非零单元格，系统测试 R3 保持 928 个非零单元格，CC_PRODUCT 与非法数据记录接口保持可读。
- [验证] 2026-07-27：客户里程碑稳定键、目录持续对账、事实发布失败回滚和跨连接 MVCC 可见性测试通过；客户缺陷原因、延期、响应效率和非法数据 9 项回归通过，事实构建/自动 worker/同步执行器定向回归通过。隔离 PostgreSQL 升级探针确认 `CC2026R3` 已启用范围会补入 `CC2026 R3`，不会因大小写变体重复成员，也不会补入停用 R4 或未定义 R5。Java 21 编译、Checkstyle、SpotBugs、Flyway 不可变性和文本门禁通过；6 分钟构建耗时本轮未宣称缩短，修复目标是构建期间读一致性与客户统计正确性。
- [验证] 2026-07-27：按 20260724 现场应用基线生成最终短名更新包 `qaflex-update-20260727T095530Z-e476d47a78af.tar.gz`（195,895,874 bytes，SHA-256 `d4e6cb80ccffaf7e5a1db0c8ddd8c18f72a0081757048490a6c3c0c12eddd937`）；前端 14 项发布测试/类型检查/生产构建、后端干净生产包、无缓存镜像、镜像内摘要、Compose、7 项包内校验和、Bash 语法及 23 项打包契约测试通过。隔离栈从 20260724 完成最终包升级、健康应用回滚和再次升级，PostgreSQL ID `e7f1632a...b53707` 始终不变，两次 `counts.diff` 为空，Flyway 为 `20260727.03`，最终 19080/19181 为 UP/200。包内说明已区分“打包基线交付物”和“现场 Compose 部署目录”，按校验、解压、目录确认、基线镜像确认、升级、健康、备份和回滚逐步给出命令。未执行 issue 事实重建或 GitLab 同步。
- [验证] 2026-07-27：兼容模式开启/关闭双模式本地真实链路验收通过。管理员从数据库查看页两次刷新 `ods_gitlab_issues`，分别形成 `TABLE_REFRESH 477 -> FACT_REFRESH 478` 与 `TABLE_REFRESH 479 -> FACT_REFRESH 480`，父子运行均为 `SUCCESS`，每次自动执行 ISSUE、MERGE_REQUEST、INTEGRATION_TEST 三类事实任务且计划/完成 3/3；开关两侧系统测试议题均为 5735 条、CC_PRODUCT 均为 1149 条，项目 9 阶段目录与项目 325 里程碑目录一致，`issue_fact` 两项目仅有 `source_instance=default`。兼容开启时老平台 MySQL/Mongo 按本地环境预期连接失败，但未阻断 GitLab 镜像、事实刷新或两个业务页面，兼容设置已恢复为开启且自动同步开启。自动刷新监听器/执行器/worker、阶段范围和客户问题共 47 项后端测试通过，提交器自动事实刷新 2 项通过，阶段定义前端 3 项及类型检查通过；提交器整类仍有 9 个沿用旧 source-instance 测试数据的既有失败，不计为本功能通过。
- [验证] 2026-07-27：短包名契约 22 项测试与 Python 编译通过；两次连续全新包及一次基于 r3 历史长名称目录的更新包 `plan-only` 均生成不同 `release-id`，两类名称分别符合 `qaflex-full-*` / `qaflex-update-*`，更新包仍正确读取现有前后端镜像基线。
- [验证] 2026-07-27：本地隔离 R5 真实链路验收通过。Chrome 管理页新增项目 9 阶段及精确测试阶段后，系统测试议题查询返回唯一匹配事实，研发质量看板可选择该阶段且“发布缺陷遗留率”明细直接显示同一议题；清理后目录、事实及相关页面/统计快照均为零残留，系统测试与质量看板恢复原目录和指标，浏览器控制台 0 错误。后端阶段展开、范围配置、统计筛选和质量看板 16 项定向测试、前端管理页 3 项测试及类型检查通过。
- [验证] 2026-07-27：离线打包契约 19 项、Python 编译、Ubuntu 24.04 Bash 升级/回滚语法和 Docker Compose 发布覆盖解析通过；真实 20260724 更新目录被正确解析为前后端 `20260724-8c1c39ad-working` 基线。文本空白与 `git diff --check` 通过；运行产物门禁仍仅被仓库根目录既有 8 个 `.tmp-*.log` 阻塞，未删除用户文件。
- [验证] 2026-07-27：新精简规则 r3 更新包完成本地真实部署闭环：归档 195,895,660 bytes，较 20260724 旧包减少约 24.1%，解压后 7 个文件 SHA-256 全通过且无裸 JAR/`dist`、`.env`、PostgreSQL 镜像或 deb；从 20260724 基线首次升级、等待健康的应用回滚和第二次升级均成功。Flyway 到 `20260727.03`，两次保护表 diff 均为空，PostgreSQL 容器 ID `e7f1632a...b53707` 始终不变，最终前后端在 19080/19181 healthy。实测同时修复回滚提前成功和同日同 commit 镜像 tag 覆盖风险；后续命名已由自动唯一 `release-id` 取代人工 label。未执行 issue 事实重建或 GitLab 同步。
- [验证] 2026-07-27：CC_PRODUCT 滞留时长闭环规则 13 项、客户记录服务 12 项、SLA 闭环回归 24 项，共 49 项测试通过；Java 21 生产源码编译、全局 Checkstyle（0 违规）和 SpotBugs（0 问题）通过。页面、详情和 Excel 共享响应均覆盖闭环归零，未修改其他统计或表格链路。
- [验证] 2026-07-27：议题范围目录 Flyway 全量烟测通过；统一目录、客户问题记录与质量看板 40 个定向测试通过；后端生产编译、Checkstyle、清理构建后的 SpotBugs、前端类型检查、定向 ESLint 和生产构建通过。完整后端测试仍包含并行滞留时长工作单元的独立状态，需按该计划收口后再执行全量。
- [验证] 2026-07-27：议题范围目录最终审查删除了无基准且存在事务提交前失效窗口的进程内缓存，目录表保持唯一读取事实源；Java 21 生产/测试源码编译、Checkstyle（0 违规）和 SpotBugs（0 问题）复验通过。当前 Docker 29 无法被项目 Testcontainers 版本识别，`IssueScopeCatalogServiceTest` 的 3 项行为测试本次被环境跳过，不记为通过。
- [验证] 2026-07-27：议题测试阶段定义页 3 项定向 Vitest、类型检查、定向 ESLint 和生产构建通过。Chrome 真实管理员链路验证项目 9 使用“阶段名称 / 测试阶段名称”、项目 325 使用“里程碑名称”，新增弹窗均只填写名称；页面不再显示稳定业务键、精确事实值和未纳入目录区块，无横向溢出或控制台错误，未提交表单或修改真实目录数据。
- [验证] 2026-07-27：本地 `18181` 登录超时定位为开发 PostgreSQL 容器 `qaflex-dev-postgres-15432` 异常退出，后端 Hikari 连接池为空并等待约 30 秒，早于前端 15 秒请求超时；恢复原容器及其既有数据卷后，`15432`、`18080` 和 `18181` 均可用，认证状态接口端到端响应约 4-7ms，本地 LDAP 身份镜像 270 用户及权限映射 299 条可读。`18182` 残留 Vite 实例已停止，未修改认证代码、业务数据或其他平台端口。
- [验证] 2026-07-27：自动事实刷新修正通过 Maven 21 容器编译、Checkstyle、事实刷新监听器/执行器/worker 定向测试，以及提交服务同父复用与不同父排队测试；整类提交服务测试仍有 10 个与本次无关的既存默认源基线失败。`git diff --check` 和文本空白检查通过。
- [验证] 2026-07-23：Chrome 真实用户链路从数据镜像设置完成确认短语、五秒保护和全事实重建；运行 `476`（`FACT_REFRESH / SUCCESS`）与事实任务 `477` 均成功，计划/完成 1/1，写入 18002 条，议题/代码走查/集成测试事实分别为 8002/10000/0，浏览器控制台无新增错误。迁移 `20260723.01` 后 15 条极目数字事实全部归一，客户下拉仅保留“极目数字”。真实筛选发现并修复新增字段被旧路由白名单静默删除的问题；修复后 URL 保留 `customerName`、列表返回 15 条且全部客户列为“极目数字”，定向 Vitest 23 项、类型检查和 ESLint 通过。
- [验证] 2026-07-23：CC_PRODUCT 加载韧性修复定向 Vitest 14 项、客户问题相关前端 26 项、类型检查、定向 ESLint 和生产构建通过；桌面/移动端 Playwright 截图验收无运行时错误，桌面端列表不再被整页骨架阻塞。移动端宽表横向滚动保持既有契约。
- [验证] 2026-07-24：20260724 保数据更新包完成生成；前端发布测试 14 项、生产构建、后端 `-DskipTests clean package`、业务镜像、Compose、138 项 SHA256 和归档均通过。本地升级模拟栈后端/前端 healthy，Flyway `20260720.06 -> 20260723.01`，PostgreSQL 容器 ID 与关键表行数守恒，别名迁移写入 5 条；未执行 issue 事实重建，避免改写本地事实数据。
- [验证] 2026-07-23：客户归一化与快速筛选修复后，Java 21 后端解析器、Flyway 和客户问题服务定向测试 30 项通过；前端客户问题字段/列/行/快速筛选 9 项通过，定向 ESLint、类型检查和生产构建通过；Flyway 不可变性、事实字段契约、文本空白和 `git diff --check` 通过。运行产物位置检查仍被工作树原有根目录 `.tmp-*.log` 阻塞，本轮未删除这些用户文件。历史事实仍需在部署迁移后执行事实层重建。
- [验证] 2026-07-23：手工事实层重建改为 `FACT_REFRESH` 后台运行，`/api/facts/rebuild` 立即返回运行编号；运行任务与 `fact_build_tasks` 关联，成功后刷新统计和记录页快照。后端控制器、提交互斥、统一全量预检和执行器定向测试通过，Checkstyle、SpotBugs、跳过测试生产包通过；前端 12 项相关 Vitest、定向 ESLint、类型检查和生产构建通过。未执行真实重建，避免改写本地 ODS 或事实数据。
- [验证] 2026-07-22：新增 `V20260722_05` 将客户问题响应时效索引纳入 Flyway，Flyway 迁移不可变性、破坏性迁移审查、事实字段契约和 13 项迁移烟测通过；全局 schema 漂移检查已无本轮缺项，剩余差异属于并行迁移的既有未收敛项。
- [验证] 2026-07-22：CC_PRODUCT 记录字段对齐后端定向 51 项、前端定向 10 项、后端 Java 21 编译、Checkstyle（0 违规）、SpotBugs（0 问题）、前端类型检查与生产构建均通过；Excel 回归覆盖 22 列顺序、中文议题状态、空测试阶段显示及处理人筛选透传。目标环境仍需执行 `scope=issue, full=true` 重建并用真实 ODS 验收字段值。
- [验证] 2026-07-22：文档系统完成逐份职责与现行代码/规则核验；删除的阶段性材料无保留文档、脚本或代码引用，保留的直连同步、认证、字段、迁移和发布文档均已明确其当前适用范围。
- [验证] 2026-07-22：CC_PRODUCT 客户字段定向后端 53 项、前端 10 项、前端生产构建通过；隔离 PostgreSQL 已迁移至 `20260722.02`，#1415 等价记录同时按“郑州新世纪”和“高晶电器”筛选均返回一条，页面显示客户、49 小时滞留、计划解决时间和计划合并分支，延期接口客户候选为空。
- [验证] 2026-07-22：事实层重建入口前端 API/确认状态/页面挂载共 7 项 Vitest、定向 ESLint 和生产构建通过；后端接口、操作守卫、全量源表预检、集成测试事实与客户问题记录页共 43 项定向测试、Checkstyle、SpotBugs 通过。未执行真实全量重建，避免改写本地事实数据。
- [限制] 2026-07-22：`FactBuildTaskServiceTest` 依赖的测试 PostgreSQL `localhost:15433` 未启动，Spring 容器在进入测试逻辑前连接被拒绝；该数据库依赖套件需在测试库可用后补验。
- [验证] 2026-07-22：CC_PRODUCT 实时刷新在隔离 PostgreSQL 与真实浏览器链路中完成“游客强制登录 → 客户问题 → 刷新最新数据”验收；父任务 `477` 与 ISSUE 事实子任务 `478` 均成功，页面在事实完成后更新为 1149 条并保留 `#2476`，最近同步为本地事实完成时间。后端重启造成 Session 失效后，页面仅显示一条“请先登录”、打开登录弹窗，未出现加载失败或新增控制台错误。前端相关 21 项 Vitest、定向 ESLint 与生产构建通过；本地认证 Provider 仅用于隔离链路，LDAP 仍需内网验收。
- [验证] 2026-07-22：CC_PRODUCT 实时事实链路后端定向 17 项、前端轮询定向 9 项、Checkstyle、SpotBugs、后端生产包和前端生产构建通过；内网仍需在已有 ODS 数据上执行一次事实重建并复验 #2684。
- [验证] 2026-07-22：测试状态成员后端定向 58 项、前端状态解析与标签组件 4 项、后端编译与前端类型检查通过；本地 PostgreSQL 已验证 `CC_PRODUCT #67` 的 `历史遗留、申请延期` 同时命中两个独立成员且仅保留一条议题事实。
- [验证] 2026-07-22：老平台正式交接后端定向 37 项、前端交互定向 8 项、Checkstyle、SpotBugs、类型检查和生产构建通过；隔离 PostgreSQL 完整迁移到 `20260721.03`，真实数据验证 CC/DGM 各自按“已交接老平台事实优先、未交接 GitLab 事实回退”切换且无跨源影响。
- [验证] 2026-07-22：全量前端 Vitest 仍有 18 个既存失败，ESLint 仍有 4 个既存未使用变量错误；本次不将全量套件标记为通过，相关失败需独立工作单元处理。
- [验证] 2026-07-21：LDAP v0.3 隔离环境 MySQL、OpenLDAP、用户 API 均为 270 名用户，334 个导入变更全部成功；新平台容器登录、Session、禁用拒绝/恢复、多角色权限并集、270 名用户/5 个角色/300 条角色关系镜像和旧 `admin123` 拒绝均通过，浏览器登录后页面加载且控制台无错误。
- [验证] 2026-07-21：已按真实 LDAP 地址 `http://172.22.10.116:80` 重新生成 Ubuntu 24.04 amd64 空平台包及 2026-07-10、2026-07-14 基线保数据更新包；前端 13 个发布测试、生产构建、后端干净打包、镜像构建/导出、Compose、空数据扫描和 SHA-256 通过，三份归档旧地址命中为 0。
- [验证] 2026-07-21：保数据更新包从 2026-07-14 基线的独立 volume 升级成功；Flyway `20260713.02 -> 20260720.06`，PostgreSQL 容器 ID 不变，issue/MR/正式评审/兼容评审/代码走查代表行数守恒，应用回滚及再次升级均成功。
- [验证] 2026-07-21：升级后 LDAP 全量身份镜像为 270 用户、5 角色、300 用户角色关系，权限目录 69 项，LDAP 管理员登录成功并获得 69 项权限，旧本地 `admin/admin123` 被拒绝；统一评审视图同时读取正式和兼容记录。
- [验证] 2026-07-21：打包脚本 9 个契约测试、前端 13 个发布测试、生产构建、后端 `clean package`、源码/jar 89 个 Flyway 文件集合校验、Compose 合并、归档空数据扫描和 SHA-256 均通过；本地空镜像库执行全事实重建时因缺失 ODS 源表安全拒绝且事实行数未改变，真实重建仍需内网镜像数据验收。
- [验证] 2026-07-21：撤销系统测试版本推导后，Java 21 编译、Checkstyle、Flyway 烟测及父子阶段展开/范围边界/议题查询/多元看板/横向导出共 30 个定向测试通过；本地全量重建 `issue_fact` 8002 条，R3 查询 3370 条，R4 维护表含 7 个子阶段，当前本地源没有带 R4 具体测试阶段的议题，因此 R4 查询为 0，4 条仅带“项目：CC2026R4”的议题未被错误纳入。
- [验证] 前端 `npm.cmd run typecheck` 通过。
- [验证] 前端权限设置、认证壳、权限 API 等定向 Vitest 测试通过；生产构建通过。
- [验证] 后端 `mvn -DskipTests compile`、Flyway 迁移烟测和接口权限契约测试通过。
- [验证] `python scripts/check_label_group_dimension_matrix.py` 通过。
- [验证] 权限设置页已通过本地页面加载和角色排序视觉检查；认证切换页面重新挂载修复已通过前端构建与回归测试。
- [限制] 尚未用内网真实数据库完成本轮所有统计和导出结果的最终验收，不将本地测试结果表述为内网业务对齐完成。
- [限制] 当前开发机无法路由到内网 LDAP `172.22.10.116:80`；包内地址和容器配置已校验，真实网络连通与登录仍须在内网部署后验收。

## 有效历史

- [历史/仍有效] 老平台是统计、字段、导出和模块展示口径的基准；新平台只改进架构和体验，不擅自改写业务结果。
- [历史/仍有效] 评审普通类型、代码走查边界和产品版本归一化以 `docs/decisions.md`（D-06）为决策来源。
- [历史/仍有效] LDAP 认证、本地 Session、本地 RBAC、`created_by` 归属和兼容模式边界已由 `docs/decisions.md`（D-01）确认。
- [历史/仍有效] 版本化外部数据集 API 只适用于经确认的进程外消费者且默认关闭。
