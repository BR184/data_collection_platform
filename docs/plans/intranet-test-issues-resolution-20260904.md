# 内网测试问题解决工作单元（2026-09-04）

## 进度与中间物

状态：**P0 现场问题已全部解决（用户 2026-09-07 同步内网结果）；剩余开放项 = D-2（20001 里程碑分组时机）、D-5（nginx 加固）、D-6（P2 工程修复排期），均待用户批复。**

- 本文档覆盖 2026-09-04 内网 30001 全新包（`qaflex-full-20260904T104638Z-8cf0508d8832`，代码基线 f66aff80）测试中暴露的三类问题，并核实了内网侧调查结论（6 张截图）。
- 已解决（内网侧执行，非本会话动作）：①登录 403 = 强制刷新恢复（P0-1）；②30001 里程碑分组 = 内网 AI 按迁移脚本逻辑手工重建并正确生效（P0-2，对应 D-1 实际走「手工重建」路径，与自动 bootstrap 幂等收敛的论证一致）；③FACT_REFRESH 互锁 = 等待自然收敛，事实层重建完成，无其他操作（P0-4，对应 D-3 方案 A 有效实证）。
- 本文 P1/P2 各项均未实施，仍为待批方案。

## 恢复线索

- 当前阶段：P0 已解决，等待用户批复剩余决策点（D-2/D-5/D-6，可选 D-4）；恢复后首步 = 用户逐项批复。
- 对应提交：内网 30001 包基线 f66aff80（本地 main）；本文档为进行中的工作单元产物。
- 相关记忆：`intranet-3port-deployment.md`（三实例拓扑与旧前端缓存陷阱）、`project-golden-baseline.md`（回归门禁）。

## 目标与边界

用户原始需求：内网测试 30001 新包时遇到全部问题（登录 403、CCProduct 里程碑分组为空、事实构建长时间无进度/反复重试），要求出具一份解决文档；**完成文档后不执行，等人工确认后再执行**。

成功标准：

1. 每个问题给出经代码证据链支撑的根因、可执行处置方案、验证标准与边界。
2. 处置方案按「是否需要改代码/重新打包」分层，用户可逐项批复。
3. 内网侧 AI 的调查结论逐条核实：一致的确认，不一致的（问题 2「永无补偿机制」、问题 3「心跳同线程」）以代码证据修正。

明确禁止：

- 未经用户确认在内网（尤其 20001 生产）执行任何 SQL、容器重启或参数变更。
- 动 30001/20001 以外的实例；删除任何项目容器或卷。
- 在本单元顺手修改黄金基线夹具/快照、目录或无关代码。

## 约束与背景

- 三实例拓扑（172.22.10.115）：18181 = 旧开发验证实例；20001 = 生产（DNS 指向，20260806 包，**保数据**）；30001 = 本次全新空数据测试实例（20260904 包）。
- 内网包 LDAP-only 登录；`GITLAB_DELETE_RECONCILIATION_ENABLED=false` 必须保持。
- 打包标准：`deploy/intranet-offline-packaging-standard.md`；compose 文件为单一权威文件（single-authoritative-file），现场手改属受控偏离，需留痕。
- 20001 的升级正解是保数据更新包原地升级，禁止切换实例或 DNS。
- 事实层就绪（fact build SUCCESS + 统计快照预热）是系统测试/客户问题/代码走查页面验收的前置条件；30001 本身是为验证一个缺陷而部署的，事实层就绪同样阻塞该项测试。

## 证据与根因

### 问题 1：登录报「当前账号无权执行该操作」（403 A0303）

**根因：浏览器缓存了旧 20260806 前端 index.html，旧前端与新后端（20260904 包）的 CSRF 令牌交付机制不匹配。**

证据链（本地与内网双向实证，内网侧结论一致）：

1. `AuthController.login` 只有三种出口：成功 200 / 密码错误 400「用户名或密码错误」/ LDAP 不可用 503「认证服务暂不可用」——代码上不存在 403 出口，403 只能来自 CSRF 安全层。
2. 新后端 CSRF 机制：`PlatformCookieNames.forInstance()` 按实例 ID 派生专属 Cookie 名（`QAFLEX_SESSION_<hash>` / `QAFLEX_XSRF_<hash>`，HttpOnly），令牌经 `X-XSRF-TOKEN` 响应头交付；不再设置旧版固定名 `XSRF-TOKEN`（JS 可读）cookie。新前端只从响应头取令牌存 sessionStorage。
3. 旧 20260806 前端 bundle 实测（docker run + grep）：`const uf="XSRF-TOKEN"` ——从固定名 cookie 读令牌。旧缓存页面在新后端下拿不到任何令牌 → 所有 POST（含登录）被 CSRF 层拒绝，返回 403 + `A0303`「当前账号无权执行该操作」。
4. 本地同镜像复现：带 CSRF 头登录 → 503（LDAP 不可达，预期）；不带 CSRF 头 → 403 A0303，文案与用户截图一字不差。干净浏览器无此问题（排除 CSRF 引导缺陷）。
5. nginx 配置（打包器 `nginx_config()`）`location /` 仅有 `try_files`，对 index.html 无 `Cache-Control` → 仅靠 ETag/Last-Modified 协商缓存，浏览器可长期复用旧 index.html；带 hash 的静态资源名变了会自动失效，但 index.html 引用关系不变时旧页面整体存活。
6. 同地址 30001 此前部署过旧包，升级/重部署后极易命中；内网侧 19:42 强制刷新后恢复，实证成立。

### 问题 2：CCProduct（项目 325）里程碑分组为空

**根因：一次性种子迁移在空库时播种 0 组；且 20260806 旧包确实无补偿机制，但 20260904 新包已内置自愈——内网侧「永无补偿机制」结论对 30001 不成立。**

证据链：

1. `V20260727_02__migrate_issue_scope_catalog_data.sql:107-165`：MILESTONE 分组由 `issue_fact`（project_id=325，未删除，milestone_title 非空）派生，business_key 按 `CC\d{4}R\d+` 归一化（大写去空白）。迁移只在建库首次执行：空库时 issue_fact=0 → 播种 0 组；目录行（issue_scope_catalogs）本身无条件插入，各实例均存在。
2. **为何只有 325 受影响（项目 9 测试阶段分组在任何实例均正常）**：两条维度数据来源不同——项目 9 的 TESTING_PHASE 分组链路（`V20260622_02` 硬编码 50 条阶段日历 VALUES → `V20260622_05` 派生 11 个父级分组 → `V20260727_02:24-105` 平移进目录表）全程零依赖 issue_fact，空库迁移照样种出全部分组；而项目 325 的 MILESTONE 分组只能在迁移执行时刻从 issue_fact 现有值派生（迁移=一次性快照）。设计动因：测试阶段日历是事先确定的固定业务配置可静态固化；客户里程碑是数据驱动的开放集合（随 GitLab 议题增长），只能从事实派生——这也是 d15ac937 补 bootstrap 自愈只针对 MILESTONE 的原因。
3. **版本分水岭（git 实证）**：
   - 2026-08-03 版本（20260806 包所含，`2fd5c34e`）：`CustomerIssueMilestoneCatalogReconciliationService.reconcilePublishedFactValues()` 开头即 `if (groups.isEmpty()) return 0;` ——空目录不建组，只有既有分组补成员。**20001 无自愈，结论成立。**
   - 2026-08-21 起（`d15ac937`，提交信息明确 "fix customer issue milestone catalog"，含于 20260904 包）：空目录 → `bootstrapCatalog()` 从已发布事实自动创建全部分组+成员（remark「由已发布客户事实初始化」）。**30001 有自愈。**
4. 调用点：`FactBuildService.java:223/248` —— `reconcilePublishedFactValues()` 在 ISSUE 事实发布事务内调用（全量与定向路径均在）。即 30001 上 ISSUE 全量事实构建事务成功提交的同一事务内，目录即自动建组，原子生效。
5. 三实例时序差异（内网侧数据，核实无误）：18181 于 7-27 迁移时 issue_fact 已有 3298 条 → 播种 9 组；20001 于 8-03 空库迁移 → 0 组（现库 3055 条带里程碑，可重建约 10 组 CC2024R3–CC2026R4）；30001 于 9-04 空库迁移 → 0 组。
6. 幂等性：`bootstrapCatalog` 仅在目录**完全无分组**时触发；若目录已有分组，走 `reconcileExistingGroups`（按归一化 business_key 匹配既有启用组、仅补缺失成员，不动管理员配置边界）。迁移脚本与 bootstrap 的 business_key 归一化规则一致，两条路径结果收敛。故内网侧已执行或未执行手工重建对 30001 均无害（用户曾指示内网 AI「按迁移脚本的逻辑重建」——若已执行，bootstrap 被跳过、后续补成员继续，结果一致；若未执行，等事实构建成功自动建组）。

### 问题 3：事实构建长时间无进度、反复重试、两 FACT_REFRESH 运行互锁

**根因（代码级机制链，全部本地实证；内网侧三缺陷定性正确，「心跳同线程」表述需修正为「单线程心跳调度器被长事务饿死」）：**

1. **单一大事务**：`FactTargetPublicationService.publishFull`（`@Transactional`）在同一事务内完成全量事实替换（内网约 45k 行 + 40+ trgm/GIN 索引刷新，实测 4.6–17+ 分钟）+ FULL_EPOCH 推进 + 投影任务入队 + 任务终态 + 发布状态结算。中途零提交、零进度事件；`finishOwnedTask` 的 SUCCESS 状态只在事务提交时可见 → UI 与库查询全程看不到进度（内网侧缺陷①定性正确）。
2. **任务租约 180 秒且执行中无续租**：`application.yml:95` `heartbeat-timeout-seconds: 180`（硬编码无环境占位符）；任务认领时一次性设 `lease_until = now + 180s`（`FactBuildTaskService.claimNextQueuedTask*`），执行期间没有任何续租代码——`FactBuildTaskService` 中不存在任务心跳续期方法。
3. **run 心跳为全运行共享的单线程调度器**：`SyncRunExecutorService` 用 `Executors.newSingleThreadScheduledExecutor` 每 60s（180/3）续 run 租约。内网侧「心跳与构建任务同线程」不准确——心跳在独立线程；但**单线程共享所有运行**，任一心跳 SQL 阻塞（连接池被长事务占满或行锁等待）即全部运行心跳饿死，效果等价（假超时成立）。
4. **假超时级联重试（互锁的机制链）**：run 租约过期 → `SyncRunLeaseService.recoverTimedOutRuns` 置 run 为 TIMEOUT → `FactBuildTaskService.recoverTimedOutQueuedTasks` 的「run 仍活跃」守卫解除 → RUNNING 任务（租约已过期，retry<3）被置 RETRY_WAITING 并清空 owner → 通用 worker（`FactRefreshTaskWorkerService.runOnce`，@Scheduled 5 秒）重新认领（新 owner、新事务）→ 新事务首条 INSERT ON CONFLICT 阻塞在原事务持有的 issue_fact 行锁（内网实测互锁 19.5/15 分钟）→ 原事务最终完成时 `finishOwnedTask` 因 owner 围栏（`where status='RUNNING' and lock_owner=?`）匹配 0 行而抛错 → **原事务整体回滚，全部构建工作丢失** → 重试事务解锁后重做。这解释了内网观察到的「构建完成却回滚重来」。
5. **取消为协作式**：取消检查点在任务边界，执行中的大事务无法中断；`pg_terminate_backend` 是唯一硬杀手段（杀掉即回滚，重试链自愈）。
6. **可观测性缺口**：自动重试只写 `fact_build_tasks.message`，不进 UI 可见的 sync_run 日志；构建全程无进度事件——数据库是唯一权威观察渠道（内网侧缺陷②定性正确）。
7. **缺陷引入时间线（git 考古，2026-09-07 补）**：不是近期修复的回归，而是两次架构升级累积的设计缺陷——2026-03-27 `cf2b6d21` 定下 180s 心跳默认值（至今未改）；2026-05-09 `e00c5998` 引入持久化任务队列（认领租约 180s、执行中无续租、超时自动重试）；2026-07-29 `b9249f36` 加「父运行活跃守卫」（run 一旦 TIMEOUT 守卫即失效）；**2026-08-03 `2fd5c34e`（版本化发布重构）是定型点**——全量替换、索引刷新（自该提交起从事务外补偿流程搬进事务内，`replaceAllFacts` 后紧跟 `refreshIssueFactSearchIndexesInBatches`）、FULL_EPOCH 推进、owner 围栏终态全部进入同一事务，从此租约被抢=整个事务回滚丢全部工作（7 月旧路径 `finishQueuedTask` 按 id 无围栏、在事务外，租约被抢原构建照常提交）。20260806 包已含全部机制；两包之间该路径执行机制零实质变化（run 心跳/租约服务 0 diff，发布服务仅 +11 行，FactBuildService 937 行 diff 为 8-21/9-04 解耦拆分、行为等价经 golden 180/180 佐证）。此前未触发的原因：20001 保数据增量链从未在新架构下跑过全量重建（日常定向批次远小于 180s）；18181 七月时代码为无围栏旧路径且数据量更小；30001 是首次「空库 4.6 万行全量（单次 4.6 分钟>180s）+ 新机制 + 双 FACT_REFRESH 并发」组合。近期修复（评审间歇故障 6707b433、黄金基线 f66aff80）均未触及此路径。

**与 2026-09-14 增量长跑的边界（2026-09-16 补）**：本问题 3 的主结论针对显式全量构建的大事务、租约超时和互锁，不等同于 9 月 14 日出现的定向增量目标洪峰。增量问题的已确认链条是“既有 200 根/批串行与粗粒度血缘边界 + 2026-08-10 `c367258a` 增加 MR commit facts 定向查询和来源范围历史目标领取 + 9 月 14 日 3.6 万～4.5 万目标洪峰”，权威归因见 `docs/decisions.md` D-13；不得引用本问题 3 的全量时间线把 9 月 10 日包、CAT 客户端或 BI 前端单独定性为增量长跑根因。

## 方案与步骤

### P0 现场处置（内网执行，不改代码不重打包）

**P0-1（问题 1，已验证恢复）**：命中 403 的浏览器强制刷新（Ctrl+F5）或清缓存；向测试人员说明「同地址重部署后旧页面缓存需强刷一次」。20001 下次原地升级后同理，需在升级通知中带一句。

**P0-2（问题 2，30001）**：确认事实构建收敛为 SUCCESS 后，里程碑分组随发布事务自动生成，无需手工重建。验证 SQL（只读）：

```sql
-- 事实任务状态
select id, fact_type, status, retry_count, affected_rows, message, error_message
  from fact_build_tasks order by id desc limit 10;
-- 分组是否已自动生成（预期：issue 构建 SUCCESS 后出现 CC2024R3–CC2026R4 组）
select g.business_key, g.display_name, g.sort_order, count(m.id) as members
  from issue_scope_groups g left join issue_scope_members m on m.group_id = g.id
  join issue_scope_catalogs c on c.id = g.catalog_id
 where c.project_id = 325 and c.dimension = 'MILESTONE'
 group by g.id order by g.sort_order;
```

若内网 AI 已手工重建过：同样用第二条 SQL 核对结果一致即可（幂等收敛，无需撤销）。

**P0-3（问题 2，20001 生产）**：旧包无自愈，二选一：
- 方案 a（推荐）：并入下次保数据升级——升级到含 d15ac937 的包后，首次成功的 ISSUE 事实构建自动 bootstrap（生产不动手工 SQL，风险最低）。
- 方案 b：现在手工执行内网侧 AI 拟好的迁移逻辑重建 SQL（需先人工复核其幂等守卫：分组 INSERT 应带「business_key 不存在」守卫，成员 INSERT 依赖唯一约束 on conflict do nothing）。执行前备份 `issue_scope_groups`/`issue_scope_members` 中 catalog_id=325 相关行，且只在 20001 维护窗口执行。

**P0-4（问题 3，30001）**：先查现状再选处置：
- 若任务仍在 RUNNING/RETRY_WAITING：优先**方案 A 等待自然收敛**（机制链第 4 条显示事务原子、重试最终成功；内网实测单次构建 4.6 分钟、互锁 19.5 分钟后已收敛）。
- 若确认死锁/长时间（>30 分钟）无进展：**方案 B** `pg_terminate_backend(pid)` 杀卡住的事务（测试实例可接受；回滚后重试链自愈），配合 `select pid, state, wait_event_type, wait_event, xact_start from pg_stat_activity where datname = current_database()` 定位。

### P1 配置缓解（内网可做，改运行参数不重打包，需用户单独批复）

**P1-1（问题 3）**：现场调大心跳超时，消除假超时误杀。在部署目录 `docker-compose.yml` 的 backend 服务 `environment:` 追加一行（该清单由打包器生成，现场手改属受控偏离，须在交付记录留痕）：

```yaml
      PLATFORM_GITLABMIRROR_HEARTBEATTIMEOUTSECONDS: "900"
```

然后 `sudo docker compose --env-file .env up -d --force-recreate backend`。生效验证：`docker exec <backend容器> sh -c 'env | grep GITLABMIRROR'`，并在下一次全量构建中观察不再出现 TIMEOUT→RETRY_WAITING 级联。

权衡说明：该值同时决定任务租约时长——调到 900 后，**真正死亡**的任务要 15 分钟才被回收重试；对 4.6–17 分钟的全量构建是合理交换。绑定关系（relaxed binding 覆盖 `platform.gitlab-mirror.heartbeat-timeout-seconds`）与既有已接线变量规律一致，但**未实测**，执行后必须用上面的 env 检查 + 实际构建行为确认。

### P2 工程修复（改代码 + 重新打包，建议独立排期，本单元只立方案不动代码）

按优先级：

- **F1 任务心跳续租**（止血，小改）：任务执行期间由独立线程周期续 `fact_build_tasks.heartbeat_at`/`lease_until`（复用 run 心跳模式），或全量任务认领租约直接取 `max(heartbeatTimeout, maxRunDuration)`。消除「执行中被判死」的根源。
- **F4 重试防抢占**（止血，小改）：任务执行加 per-task advisory lock（或重试认领前校验后端活跃事务），杜绝新事务与未结束原事务互锁、原事务无谓回滚。
- **F3 run 心跳调度器扩容**（小改）：`newSingleThreadScheduledExecutor` → 按并发运行数定容的调度池，消除全局心跳饿死。
- **F5 可观测性**（中改）：自动重试与租约超时写入 UI 可见运行日志；`fact_build_tasks` 增加进度字段（已处理行数/批次）由构建过程周期上报。
- **F2 大事务分批提交**（根治，大改，需专项评审 + 黄金基线重跑）：`publishFull` 拆为分块提交 + 阶段性进度落库，消除 17 分钟无提交窗口。涉及事实发布原子性语义（FULL_EPOCH、版本头、目标结算的跨批一致性），必须先出设计再动。

**P2-0（问题 1 加固，小改）**：`scripts/package_intranet_offline.py` 的 `nginx_config()` 中为 index.html 增加禁强缓存：

```nginx
    location = /index.html {
        add_header Cache-Control "no-cache";
        add_header Pragma "no-cache";
    }
```

（协商缓存保留，带 hash 的静态资源缓存策略不变。）随下一次打包合入即可，无单独回滚风险；合入后强刷一次即免疫此问题复发。

### 执行顺序建议

1. 用户批复后：P0-2/P0-4 在内网 30001 先行（只读核查 → 等待/终止决策）。
2. P0-3 按用户选择（推荐方案 a 并入升级）。
3. P1-1 若批复，按留痕流程执行并验证。
4. P2 各项按 F1/F4/F3 → F5 → F2-0（nginx）→ F2 顺序立项，各自独立工作单元 + 黄金基线门禁。

## 决策记录

已解决（2026-09-07 用户同步内网结果）：

- D-1：内网 AI 已按迁移脚本逻辑手工重建 30001 里程碑分组并正确生效——与「自动 bootstrap 幂等收敛」论证一致，无需回退或补做。
- D-3：FACT_REFRESH 互锁经等待自然收敛，事实层重建完成（方案 A 有效实证，无需 pg_terminate_backend）。

待用户批复的决策点：

| 编号 | 决策 | 选项 | 建议 |
|---|---|---|---|
| D-2 | 20001 里程碑分组 | 并入下次保数据升级自动 bootstrap（a）/ 现在手工 SQL（b） | a |
| D-4 | 心跳超时现场调参 900s | 执行 / 不执行（等工程修复） | 30001 构建已完成；仅当 20001 升级窗口前想缓解互锁噪声时执行 |
| D-5 | nginx no-cache 加固 | 随下次打包合入 / 单独出包 | 随下次打包合入 |
| D-6 | P2 工程修复排期 | F1+F4+F3 先行 / 全部随 F2 一起 | F1+F4+F3 先行止血 |

已定事实（无需再议）：问题 1 根因与强刷处置已实证并现场验证；问题 2 的版本分水岭（20260806 无自愈 / 20260904 有）为代码实证；问题 3 机制链经 30001 自然收敛事件验证（事务原子、重试最终成功）。

对 20001 升级窗口的联动提醒：升级到新包后，首次成功的 ISSUE 事实构建（增量即可触发 bootstrap）会自动补建里程碑分组；若升级流程触发全量事实刷新，将复现 30001 的超时→重试→互锁行为（等待可收敛，预计 15–20 分钟级），现场勿误判为故障而中途终止。

## 接口契约

本单元无新增 API、表结构或协议变更。若执行 D-2 方案 b，其 SQL 契约 = `V20260727_02__migrate_issue_scope_catalog_data.sql` 两条 INSERT（分组+成员）+ 幂等守卫，只允许影响 `issue_scope_groups`/`issue_scope_members` 中 catalog_id =（project_id=325 且 dimension='MILESTONE'）的行。

## 风险与假设

1. 30001 事实构建收敛 SUCCESS 已证实（2026-09-07 用户同步：等待自然收敛完成事实层重建，无手工干预）。
2. 内网 AI 已按迁移脚本逻辑重建 30001 里程碑分组并正确生效（2026-09-07 用户同步），与 bootstrap 幂等收敛论证一致。
3. `PLATFORM_GITLABMIRROR_HEARTBEATTIMEOUTSECONDS` 的 relaxed binding 路径由既有已接线变量规律推导，未实测——P1-1 执行后必须验证生效（见验证命令）。
4. 心跳超时调大同时延长真死任务的回收延迟（180s→900s：假死 15 分钟才重试）——已写入权衡说明，用户批复 D-4 时须知。
5. 20001 为生产库：任何 SQL（含只读核查）在维护窗口执行；方案 b 的备份先行。
6. F2（大事务分批）改动事实发布原子性语义，存在回归风险，必须独立设计评审 + 黄金基线全量门禁，不得与其他修复混装。
7. 现场手改 docker-compose.yml 与「单一权威文件」打包模型冲突——P1-1 只作为过渡缓解，工程修复（P2）合入后应回退现场改动。
