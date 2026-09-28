# 进度与中间物

- **原始核验完成**：报告基线的 R01—R12 均成立，保留报告的 3 项 P1、9 项 P2 分级；其中 9 项有原始核验的隔离运行证据，R01/R04/R05 为独立源码链确认，未冒充真库实测。
- **2026-09-22 核验时点工作树**：当时 R01—R11 的相关缺陷仍存在；R12 已被并行工作树补齐登记，当时只读校验通过（133 份），不是本单元修复，且尚未进入 HEAD。下文“当前工作树”均指该核验时点，不代表后续实施状态。
- **后续入口**：2026-09-24 第六轮独立复审与无上下文接手指令见[整改任务书第八、九节](platform-review-remediation-plan-20260922.md)：整批退回，七项功能缺陷、两项正式测试缺口和一项待裁定回滚政策已给出具体步骤。R01/R06/R11 前轮限定通过保留，R02 代码/脚本与 R12 的 134 项登记核对通过；实际场景及发布门禁未完成。后续定向测试与原始基线证据分别记录，不因修复改写本报告。
- **本轮实测**：8 个前端取证用例全部复现预期错误行为；新装环境→增量 Compose 两个负向解析失败、补齐两个合成卷名的对照解析成功；错误备份回滚退出 1 且现场替身文件已被覆盖；两项工程门禁分别对基线/工作树执行，结果见下文。
- **本单元受控文件只有本文**；临时副本与取证材料位于 `.tmp/review-verification-c818e720-20260922/`。未修改功能代码、既有测试、迁移、校验清单、黄金快照、共享权威文档或另一 AI 的活动计划；未提交/推送。
- **收尾检查**：隔离副本 2,159 个原始文件的 Git blob 摘要全部与固定提交相同；本文 UTF-8/LF、末尾换行、空白、12 项章节与文件引用检查通过。AGENTS 为 27,110 bytes，未触发压缩要求。
- **生命周期**：本文保留原始核验证据，不是新的产品/架构权威；后续实施与独立评审状态只在整改任务书维护。整体问题关闭后，将有效结论归入对应权威文档并按规则处理活动计划。为避免并行覆盖，本轮未编辑正在被另一 AI 修改的 `docs/progress.md`，其索引合并留到后续文档收口。

## 恢复线索

- 核验日期：2026-09-22。
- 固定基线：`c818e7202090d22f14b9ae687e3a41b83b34f82c`；原报告、本轮起始及证据核对时的本地 HEAD 一致。
- 原报告：用户附件 `C:/Users/admin/Downloads/评审报告.md`；SHA-256 `f615f3b031f20dfc9d8e22c735484d1c77dac99663619784ff2c5dfdffce10c8`。
- 固定源码：由 `git archive` 导出至 `.tmp/review-verification-c818e720-20260922/source/`，不包含新功能工作树；仅另加取证测试和专用 Vitest 配置，不修改原有源码。
- 原始运行证据：同级 `evidence/` 的 `frontend-probes.log`、`packaging-probes.log`、`baseline-check_*.log`、`worktree-check_*.log`、`source-comparison.json`。这些是本地临时附件，不进入版本控制；即便清理附件，本文仍保留因果链、复现顺序和结果。
- 恢复首条命令：`git status --short`。先核实并行改动与 HEAD，再读取用户已指派的整改范围；不得据本文直接启动修复或发布。
- 下文代码路径均相对仓库根，**行号对应固定基线**；不是承诺未来工作树行号不变。

## 目标与边界

用户要求对外部模型的整个平台评审报告进行验证并落档；报告不含另一 AI 正在实现的新功能，禁止干扰其工作，禁止使用任何 agent/subagent。本轮全部由主线程完成。

验收目标：R01—R12 各有独立源码证据、验证层级、适用版本与结论；原报告的全量测试、外部日志及已排除事项不自动视为本轮复验通过。仅记录待办与修复验收方向，不实施修复或批准产品/架构变更。

## 约束与背景

- 工作树已有客户维度统计/BI 新功能以及共享文档改动；本轮不审查新功能、不改变其进度与验收结论。
- 未停止/重启开发服务，未连接业务库或共享测试库，未运行 Maven、前端生产构建、黄金链；未执行同步、事实重建、真实备份/回滚或 GitLab/LDAP/CAT 网络操作。
- 前端使用 Node 24.18.0、Vitest 3.2.7、Vite 6.4.3、jsdom；通过独立副本的目录联接读取既有依赖，专用配置禁用测试缓存且将 Vite 缓存限定在取证目录，不使用项目的声明生成插件。用例执行约 545ms，冷启动总耗时约 68s；这不是性能基准。
- 脚本使用 Python 3.14.2；R02 为 Docker Compose v5.4.0 的 `config --quiet`，仅解析、无容器/卷创建。R03 执行生产生成的回滚脚本，但 Docker 全部被进程内替身替代，未知调用直接拒绝。
- 仅使用合成数据；R01 不读取真实敏感值。原报告列出的外部证据目录/日志未随附件提供，未取得也未宣称复核。

## 证据与根因

### 汇总

| 编号 | 级别 | 固定基线结论 | 本轮验证层级 | 当前工作树判读 |
| --- | --- | --- | --- | --- |
| R01 | P1 | 成立：数据库浏览响应越过声明列边界 | 源码闭环 | 仍存在 |
| R02 | P1 | 成立：新装 `.env` 缺增量 Compose 必填卷名 | 生产生成函数＋真实 Compose 解析 | 仍存在 |
| R03 | P1 | 成立：拒绝错误基线前已覆盖现场 Compose | 生产生成脚本＋Docker 替身＋临时文件 | 仍存在 |
| R04 | P2 | 成立：过期 RUNNING 投影任务无自有恢复路径 | 全表写入者与调用链核查 | 仍存在；并行新增 generation 方法不回收任务 |
| R05 | P2 | 成立：失租不停止备份主体，清理与执行可交错 | 心跳/巡检/文件/终态源码闭环 | 仍存在 |
| R06 | P2 | 成立：首绘等待不受超时/途中取消保护 | 真实 request＋冻结 rAF/可控时钟，2 例 | 仍存在 |
| R07 | P2 | 成立：旧列表结果可覆盖新筛选结果 | 真实评审组合函数，1 例；非法页源码 | 仍存在 |
| R08 | P2 | 成立：迟到字段配置可跨字段显示/提交 | 真实 Vue 组件交互＋保存请求替身，1 例 | 仍存在 |
| R09 | P2 | 成立：迟到详情接管编辑/新增模式 | 真实两类弹窗组合函数，3 例 | 仍存在 |
| R10 | P2 | 成立：主动刷新被 initialized 短路 | 真实认证状态组合函数，1 例＋调用方/后端契约 | 仍存在 |
| R11 | P2 | 成立：人员维度机器矩阵陈旧 | 基线和工作树检查器均 exit 1 | 仍存在 |
| R12 | P2 | 成立：三份旧迁移未登记 | 基线检查器 exit 1；工作树 exit 0 | 并行未提交改动已解决本项登记缺口 |

“探针通过”只表示成功观察到缺陷，不表示功能正确或验收通过。

### R01：数据库浏览器响应列白名单未实施

- 入口受数据库查看权限保护（`backend/src/main/java/com/data/collection/platform/controller/DatabaseBrowserController.java:23`），所以不是匿名访问漏洞，也不是任意 SQL 注入。
- 本地 `gitlab_sync_configs` 明确在表目录中，但只声明 `id/name/source_mode/whitelist_mode/updated_at/created_at` 六列（`backend/src/main/java/com/data/collection/platform/service/DatabaseBrowserSyncTableDefinitions.java:16`）。物理表另有 `db_password`、`api_token`、`system_hook_secret`，可由对应迁移与 `GitlabSyncConfig.java:33`、`:66`、`:72` 交叉核实。
- `DatabaseBrowserQuerySupport.java:77` 生成 `select *`；`DatabaseBrowserRowMapperFactory.java:15` 遍历全部结果列；`DatabaseBrowserService.java:132` 将完整 Map 列表放入响应，`:142` 的 columns 与 rows 相互独立。`entity/database/DatabaseTableRowsResponse.java:14` 没有裁剪逻辑；生产代码未发现统一响应过滤器为此补救。
- 对照正常配置响应：`controller/GitlabSyncControllerResponseMapper.java:49`、`:60`、`:62` 将上述敏感字段置空，证明“不回显”不是评审者新设的要求。
- **结论边界**：已授权数据库查看者能经该路径取得声明列外的敏感配置；本轮只静态验证数据流，未请求真实配置端点或展示任何真实凭据。
- **整改验收方向**：先明确本地表允许列，以显式投影与响应契约测试锁定；合成行中含敏感字段时响应不得出现，合法普通列、排序、分页保持原行为。

### R02：新装→首次增量的环境契约缺口

- `scripts/package_intranet_offline.py:502` 的 `env_content()` 不写两个卷名；`:561` 的 external volume 模式要求 `POSTGRES_VOLUME_NAME`、`BACKEND_LOG_VOLUME_NAME`；升级在 `:1214` 用现场 `.env` 解析目标 Compose，解析失败发生在 `mv` 和停后端之前，但此前已经加载应用镜像。
- 本轮直接组合真实 `env_content()` 与 `compose_content(external_postgres_volume=True)`，清除进程环境中同名变量后执行只读 `docker compose ... config --quiet`。
- **结果**：原始新装 env → exit 1（两个必填变量缺失）；只补合成 PostgreSQL 卷名 → exit 1（日志卷变量缺失）；两个合成卷名均补齐 → exit 0。最后一项只证明解析条件，不证明任何真实卷存在，未创建卷。
- 打包器 `validate_layout():1571–1588` 人工注入两变量，只验证增量 Compose 自身，不能覆盖新装 env 到首个升级的组合契约。
- **边界**：不是所有增量升级都会失败；已人工保存真实卷名的现场可以通过该检查。项目进度中 2026-09-08 的缺卷名记录已提到同一缺口，本项不是新功能引入。
- **整改验收方向**：固定原现场资源身份，在未手工加工的新装 env 上验证首次升级；禁止猜卷名或新建空卷消除报错。

### R03：回滚校验顺序破坏失败安全

- `scripts/package_intranet_offline.py:1278` 入口只要求备份目录有 `.env` 与 Compose；`:1299–1302` 先复制、语法校验、覆盖现场文件；`:1304–1306` 才校验镜像基线。与升级 `:1157–1162` 的清单/摘要/包身份预检明显不同。
- 本轮生成真实 `rollback_helper()`，用两个独立临时目录模拟现场与错误备份。Docker 替身仅提供存活 postgres ID 和 Compose 内容读取；并未调用真实 Docker 服务。
- **结果**：脚本 exit 1，错误为 `restored backend image does not match`；现场替身 Compose 内容已经等于错误备份。拒绝操作没有保持原现场文件。
- **边界**：未执行真实回滚，不把“不自动回退 schema”或“失败后人工处置”算缺陷。缺备份清单的入口不足已静态确认，本轮没有额外声称运行了完整性绕过的第二种回滚场景。
- 此问题在 `docs/progress.md` 2026-09-18 的回滚负向验收中已有记录；本轮是独立复现，不重复立另一个问题。
- **整改验收方向**：所有完整性、适用基线与现场身份检查必须先完成，再原子替换；错误基线、缺失/损坏清单等拒绝分支需断言现场文件摘要和运行配置不变。

### R04：过期 RUNNING 投影没有恢复收敛路径

- `backend/src/main/java/com/data/collection/platform/service/FactProjectionTaskService.java:30–72` 的领取只接受 `QUEUED/RETRY_WAITING`，设置 lease_until 但不接纳过期 RUNNING；`:162–202` 仍把所有 RUNNING 算活动任务。
- `FactProjectionTaskWorkerService.java:28–55` 正常异常会尝试 `failOwned()`，但进程中断或失败状态写库失败并不能保证走到终态。`sync/SyncFactRefreshRunExecutor.java:131` 只能领取上述可领取状态，`:93` 遇活动投影保持等待。
- 搜索固定基线及当前 `backend/src/main` 的整张表引用：写入者为该任务服务与 `FactProjectionGenerationService.java:70` 的入队逻辑，没有独立过期回收器；后者只在更高 generation 到来时重置同构建任务的入队状态，不是按失效租约恢复。
- `sync/SyncRunLeaseService.java:158–195` 回收父运行只处理 `sync_run_table_tasks`，不处理投影表；`sync/SyncRunPublicationFenceService.java:322–354` 将未成功/未失败投影视为 PENDING。
- **本轮静态确认，未复跑原报告的 PostgreSQL/真实 claimNext 探针**。可复现状态应为：只有一个 lease_until 已过去的 RUNNING 投影、无更高成功代际；领取不到、汇总仍活动、对应 scope 未完成。
- **边界**：不声称所有后续同步永久阻塞；同范围更高 generation 的 SUCCESS 可满足旧栅栏（`:337–344`）。并行工作树的 `advanceIssueScopeGenerations` 只推进版本、不恢复投影，未修复本项。
- **整改验收方向**：统一租约回收/重试或明确失败收敛，具备 owner fencing；实测进程中断后的任务和栅栏最终收敛，并验证旧执行者不能提交新 owner 的结果。

### R05：备份失租不终止主体执行

- `backend/src/main/java/com/data/collection/platform/service/backup/BackupStateRepository.java:48–56` 在 active_run_id 不匹配时抛错；`BackupOrchestrationService.java:390–403` 在独立心跳线程捕获后只写日志，没有将失败传递给备份执行线程/子进程。
- `BackupScheduler.java:91–110` 找到过期运行后标失败、释放运行权、清理 staging；`:123` 的删除对象与主流程 `BackupOrchestrationService.java:163` 的导出目标是同一个 `tmp-<runId>.dump`。
- 主体继续导出/验证/落位/轮转；本地原子落位 `:211`、远程上传 `:269`、轮转 `:214/:283` 前无归属验证。`BackupRunRepository.java:70`、`:75–114` 按 id 无条件更新阶段/成功/失败，可与巡检终态竞争。
- **结论边界**：源码足以确认“心跳延迟跨过租约→巡检先恢复→旧主体仍执行”的交错风险；单线程主体执行器不能阻止另一个巡检线程清理它。实际文件删除受平台锁行为影响，本轮未中断真实备份，也不声称已经发生备份丢失。
- **整改验收方向**：失租可取消主体及导出子进程，副作用与终态有有效 owner 边界，清理仅针对已停写产物；覆盖受控心跳延迟、巡检和主流程交错，不只调长租约。

### R06：首绘等待不受超时/途中取消保护

- `frontend/src/api-client/request.ts:259` 先等待首绘，`:265–280` 才安装计时器与 abort 监听；`:316–320` 的 Promise 只有 rAF 回调才能推进。导出另经 `:204–209` 外层等待；不应误述为所有导出必定等待两次绘制。
- **两个独立用例**：设置 timeoutMs=100，冻结 rAF，推进可控时钟 1000ms，fetch=0 且 Promise 未结束；另一例进入等待后调用 AbortController.abort()，同样未结束。释放绘制回调后，前者才成功发送，后者才抛 AbortError。
- **边界**：请求前已经 aborted 会跳过等待，不是本项触发条件；不是每个后台标签页必现，而是浏览器暂停该文档 rAF 的条件下存在无独立退出路径。
- 项目 2026-09-10 进度已有相同风险，未被本轮或新功能修复。
- **整改验收方向**：从请求入口覆盖超时/取消，同时保持已确认进度可见体验；是否采用有界首绘等待或彻底解耦须形成方案，不把外部报告的建议自动当作批准。

### R07：列表提交不受请求代次保护

- `frontend/src/composables/useRouteTableState.ts:100–113` 的 loaderRunId 仅保护 finally 关闭 loading，不保护 boundLoader 的副作用。
- 评审调用链 `views/ReviewDataManagementView.vue:338–352` → `views/review-data/useReviewDataRecords.ts:64–68`，无条件写入 records/total/summary。非法页 `views/issue-illegal-records/IssueIllegalRecordsPage.vue:259–262` 同样无条件写入，`:365–380` 也没有代次判断。
- **实测评审组合函数**：同时发出 old/recent 查询；recent 先返回记录 2/总数 22，随后 old 返回记录 1/总数 11，最终表格状态退回旧值。非法页本轮为源码确认，未作组件/浏览器复现。
- **边界**：不扩展为所有列表都缺保护，不将已有独立请求编号的客户问题页混入。
- **整改验收方向**：结果、摘要、错误与 loading 都绑定同一次查询归属；逆序完成、快速翻页/筛选和卸载应保留最新有效请求状态。

### R08：跨字段配置响应污染草稿与保存目标

- `frontend/src/views/DropdownOptionSettingsView.vue:108–110` 切字段并发加载，`:138–154` 的迟到配置直接覆盖共享草稿/版本；`:385` 左侧仍可选择。`:244–248` 保存使用当前 selectedFieldKey 与共享草稿，不验证草稿字段归属。
- **实测真实组件**：先完成 A 初载，点击 B（挂起响应），再点击 A（即时完成），最后释放 B。左侧及标题仍选 A，配置标签已为 B，手动值为 B-only。经手动值组件编辑后点击“保存配置”，API 替身收到目标 A、手动值 `[B-only, user-edit]`、版本 3。
- A/B 用不同 configId、相同 version；后端 `service/dropdown/DropdownOptionFieldService.java:139–145` 按当前字段找绑定，只核对 version，没有客户端草稿来源信息。因此相同版本不能阻止错字段草稿通过此检查。
- `:338` 候选和 `:362–366` 预览也未检查请求归属；本轮运行只复现配置/提交路径，不宣称候选与预览全部已独立复现。
- **边界**：保存停在 API 替身，未写真实设置库；运行证据证明错误提交参数，持久化后果由后端契约静态确认。
- **整改验收方向**：配置/候选/预览均绑定字段和代次，草稿显式拥有字段身份；逆序响应不可改变当前草稿，保存目标必须与草稿所属一致。

### R09：迟到详情接管编辑/新增操作

- `frontend/src/views/review-data/useReviewRecordDialog.ts:38–49` 详情返回后无条件切换对象、表单与编辑模式；`:58–67` 按此可变模式选择更新或创建。页面新增按钮 `views/ReviewDataManagementView.vue:542–547` 及 `useReviewDataPageActions.ts:69–78` 没有详情加载互斥。
- **三例实测**：① A 编辑请求挂起→B 编辑先完成→A 迟到，editingRecordId 从 B 回到 A；② A 编辑挂起→打开新增→A 迟到，新增转编辑，submitRecord 将拟新增 payload 送到 updateRecord(A)，未调用 createRecord；③ 问题项 A 编辑挂起→B 新增完成→A 迟到，当前记录及问题项恢复为 A，模式变回编辑。
- 第③路径位于 `useReviewProblemItemDialog.ts:32–57`，与主记录同族；以上提交均为替身，没有更改真实评审记录。
- **整改验收方向**：打开/新增/切换/关闭都失效旧操作，详情只能更新仍匹配的对象与模式；补关闭后迟到不重开、编辑互切、编辑→新增及提交目标不漂移的回归。

### R10：权限保存后的刷新调用被缓存短路

- `frontend/src/views/PermissionSettingsView.vue:96/:127` 在保存/恢复默认后调用 loadCurrentUser；`frontend/src/composables/auth-state.ts:31–34` 在 initialized=true 时直接返回缓存。
- **实测**：第一次 current API 返回 old.permission，替身随后准备 new.permission；第二次 loadCurrentUser 未请求 API，调用次数仍 1，返回权限仍 old.permission。
- 后端 `backend/src/main/java/com/data/collection/platform/controller/AuthController.java:49–55` 确实会重读本地权限并更新 Session，但前端第二次调用未到达此入口。产品要求见 `docs/product.md:68`。
- **边界**：这不是后端授权绕过，也不改变 D-01 已接受的 LDAP 身份/角色下次登录生效语义；问题仅针对已保存的本地 RBAC 变化。
- **整改验收方向**：明确首次初始化与主动刷新两个调用契约；保存/恢复后当前用户、菜单、按钮和路由同步变化，无需整页刷新。

### R11：标签组矩阵未跟随人员维度收敛

- `backend/src/main/java/com/data/collection/platform/service/labelgroup/LabelDimensionCatalogService.java:44` 定义 person；`scripts/contracts/label-group-dimension-matrix.yml:17–45` 仍保留五个人员旧键且未登记 person。
- 基线与当前工作树运行 `python -B -X utf8 scripts/check_label_group_dimension_matrix.py` **均 exit 1**：缺代码维度 customer_assignee、customer_author、issue_assignee、review_expert、review_owner；YAML 缺 person，共六条差异，与报告一致。
- **边界**：代码统一 person 是已接受的模型；这只证明工程门禁失败，不证明线上人员筛选结果必错。
- **整改验收方向**：矩阵与页面字段映射跟随现行模型更新并跑现有检查及人员字段契约，不恢复废弃人员维度、不关闭检查。

### R12：报告基线迁移登记缺口，当前工作树已补齐

- 基线运行 `python -B -X utf8 scripts/check_flyway_migration_immutability.py` **exit 1**，只有三条 UNLOCKED：
  - `V20260901_01__fix_match_mode_unique_constraint_schema_scope.sql`
  - `V20260903_01__dropdown_option_configs.sql`
  - `V20260909_01__database_backup_management.sql`
- 没有 CHANGED 或 MISSING：不应描述为已登记迁移被篡改或已发生数据库损坏。
- 当前工作树同命令 **exit 0，133 migrations locked**。`git diff -- scripts/flyway-migration-checksums.json` 显示只追加这三项和新功能的 `V20260922_01`、`V20260922_02`，原登记值保持不变。
- **状态**：报告针对 c818e720 正确，但“当前仍未登记”已经过时；补齐属于另一 AI 的未提交改动，本轮未重写清单或评审新迁移业务语义。后续发布仍须在最终版本重新跑门禁，不能将工作树通过等同 HEAD 已修复。

## 版本核对与验证范围

1. 从原报告的源码链接提取并比较 26 个实际文件；按 LF 归一后计算 SHA-256，固定基线与当前工作树只有 `scripts/flyway-migration-checksums.json` 不同。其余 25 个报告直接引用文件相同，证据保存于 source-comparison.json。
2. 补查报告未直接链接的控制器、表目录、DTO、弹窗调用方、备份终态仓库、投影 worker 与 generation 入队。相关缺陷链未被新功能补救；并行 generation 差异仅增加定向 Issue 范围版本推进，不改旧投影入队和租约恢复。
3. 本轮没有取得原评审环境的原始日志或探针附件。报告中的后端 1389、前端 531、Python 57、build/lint 全绿及 PostgreSQL 投影探针仅是外部报告陈述，**不是本轮实测或重新认证的全量通过结果**。
4. 未做真实浏览器端到端验收、真实敏感数据读取、备份中断、数据库任务恢复、升级/回滚部署、黄金链、内网容量测试。后续修复须补相应验收，本次不会用模拟测试替代这些结论。
5. 原报告的“已排除”章节不逐项背书为本轮审计完成：本轮确认 D-01/D-10 的既定边界，以及 R02/R03/R06 的历史重复记录；其他排除事项保持原报告引用，不新增 BI/CAT 业务裁定或性能版本归因。

## 方案与步骤

- 已完成：基线锁定、独立副本、12 项源码复核、无副作用运行探针、当前工作树差异与文档落档。
- 2026-09-22 后续方案已保存并细化为[平台评审详细整改方案与 AI 员工执行任务书](platform-review-remediation-plan-20260922.md)，逐项设计、W00—W10 执行步骤、交付证据与评审退回流程统一在该文档维护。用户已明确：**其他 AI 员工实施，本 AI 负责交付后的独立评审；有问题继续给出整改方案并复验**。首轮交付后的评审见该文档第八节；本文继续负责原始核验证据，不另行维护第二套实施方案。
- 各修复必须先锁定相应复现条件、增加描述正确目标行为的回归，再实现；本文取证用例刻意断言旧错误，不应原样进入默认套件作为“正确行为”。
- 发布验收仍按仓库既定黄金基线与离线部署流程触发；本报告核验不授权更新快照或真实发布。

## 决策记录

- 已选：固定已提交版本核验，当前工作树仅做差异和只读校验；避免把新功能差异归咎于旧版报告。
- 已选：单独维护本文，保护另一 AI 正在编辑的 AGENTS、progress、architecture、decisions、BI 文档和活动计划。文档职责索引/归档迁移待并行工作收口后统一处理。
- 否决：照抄外部结论代替独立取证；为验证报告在共享服务/库做故障注入；启动全量构建/黄金链；顺手修复或提交他人改动。
- 分工已确认：其他 AI 员工实施，本 AI 独立评审并持续给出问题整改方案；具体接手范围、尚待定稿的技术/产品选择及排期由整改任务书跟踪。本单元不启动实现，方案细化不等于已经修复或通过评审。

## 接口契约

本单元无 API、函数、数据表、交互协议、权限设置或运行配置变更。

## 风险与假设

- 当前工作树仍未提交，观察时点后的并行变更可改变缺陷状态；开始修复或发布前应重新核对涉及文件。
- R01/R04/R05 为源码确认，未重演真实凭据暴露、数据库中断或备份租约丢失；不能据此报告真实事故已发生。
- 复现证明存在指定错误交错，不给出其线上发生概率；本机固定输入不外推内网规模与容量。
- 本轮未产生长期协作规则变更：用户“不用子 agent、保护并行工作”的要求作为当前单元边界执行，未擅改 AGENTS 或写入永久个人偏好。
