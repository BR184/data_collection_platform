# 更新包容器身份策略（2026-09-09）

## 进度与中间物

- 状态：2026-10-15 方向 B 已由员工实施并验证——打包器容器名改为逐服务继承直接基线，20101 兼容包重建并完成隔离栈连续升级/回滚/再升级演练（0 断言失败），详见下文「员工实施与验证」。20001 的独立包与演练仍待用户指令；09-09 的可选命名开关与失败续跑方案仍是未实施历史提案，不因本次实施自动获得授权。
- 恢复线索：先按下节“2026-10-15 当前实施边界”推进最小修复及两类基线回归，不直接实现历史提案中的全部步骤；涉及文件 `scripts/package_intranet_offline.py`、`scripts/test_package_intranet_offline.py`、`deploy/intranet-offline-packaging-standard.md`。
- 日常自动备份主题已拆至 `docs/plans/database-backup-automation-20260909.md`（独立脚本先行 + 备份设置页两阶段），本文档只保留容器身份策略。

## 2026-10-15 当前实施边界

- 用户确认的部署顺序：先在 115 的 20101 验证，再部署 20001；20101 当前升级基线为 `qaflex-full-20261010T013316Z-24fbf3eb6925.tar.gz`，20001 当前升级基线为 `qaflex-update-20260930T044004Z-e275334105bb.tar.gz`。这两份为对应实例的基线制品，不是让现场重装旧包或覆盖其现有数据库。
- 当前裁定走员工提出的 B 方向：将外部卷复用与容器命名解耦，先修打包器并重出适配 20101 的保数据包，完成演练后交用户现场测试。不能为绕过冲突把 20101 的直接基线改成 09-30，也不能让它占用全局 `qaflex-*` 名称。无需等待 `docker ps` 输出再决定这条方向；部署前仍核查目标真实身份。
- 代码核实：`compose_content` 中三条 `container_name: qaflex-*` 由 `external_postgres_volume` 同时开启；外部数据库卷已由 `.env` 的 `POSTGRES_VOLUME_NAME` 显式绑定，日志卷同理；backup/rollback 有 project/service 标签与真实卷校验。upgrade/rollback 仅对 backend/frontend 执行 `--no-deps --force-recreate`，PG-ID 不变是现有硬约束，不得删除或重建 PostgreSQL 来解命名冲突。
- 最小修复验收：20101 全量包基线继续由其原 project 隔离容器/网络，保留原 `.env`、三端口、实例 Cookie 身份、PG 实际卷和日志卷；外部卷开关不得产生固定全局名。20001 旧更新链的命名须按其直接基线和真实 project/service 标签验证，尤其不得把仍在用的 PG 声明身份随意改掉后仅凭一次 `--no-deps` 升级便声称长期一致。允许最小实现选择，但必须证明两类基线均成立，不能只删三行便宣布通用修复完成。
- 相称测试：更新错误锁定固定容器名的既有断言，新增 fresh 基线→更新与旧更新链→更新的模型/身份回归；有其它 project 的同名容器（包括 exited）时不得修改/删除它。20101 模拟栈完成 `backup→upgrade→rollback→backup→upgrade`，每阶段核对本实例 project/端口/卷、PG-ID、守恒项、健康与旧实例资源不变；20001 按 09-30 实际基线独立生成发布清单并另做升级/回滚演练。共用业务镜像可以复用，但不能把 20101 的基线清单/备份当作 20001 的凭证。
- 交付与恢复：当前 `qaflex-update-20261015T084319Z-40291abbadfb` 已在演练失败，不能作为验收通过包交付；修复后使用新发布身份并保留旧失败证据。演练栈现处于 upgrade #1 中断状态，重出包后先核对当前镜像、Compose 与恢复记录，按既有恢复入口恢复已验证基线，再备份并演练，不盲目接着执行旧失败步骤。共享 18080/18181 仅由原执行者完成其停服后的恢复和健康核验，经理不再额外启停。
- 验证边界：黄金 compare 204/0/0、隔离默认套件 1903/0/0、包内校验及 backup 成功为员工本轮报告，经理未独立重跑或复核全部产物；本轮已独立核实固定容器名耦合与无依赖重建路径。最终包必须与已验收业务版本指纹对应，并单独通过打包器回归、制品校验及连续升级/回滚验收；20101 验收通过不自动代替 20001 的现场准入。

## 员工实施与验证（2026-10-15，方向 B 最小实现）

- 实施：`read_baseline_container_names(deployment_dir)` 逐服务读取直接基线 Compose 声明的 `container_name`，写入 `BuildContext.container_names`；`compose_content` 只按该映射输出 `container_name`（不再与 `external_postgres_volume` 耦合），`external_postgres_volume` 回归为仅决定卷外部性。增量模式 `--plan-only` 与正式构建都打印解析出的命名模型，出包前可肉眼确认隔离性。包内脚本按 Compose 标签寻址容器（`resolve_service_container`、`verify_container_compose_identity`），全仓库仅旧三行硬编码引用过固定名，因此该改动不影响备份/升级/回滚与 PG-ID 校验。
- 与 09-09 提案的差异（请经理复核）：本轮按 2026-10-15「最小修复」边界，**未引入** `--container-naming` 开关与 manifest `containerNaming` 字段——命名一律继承直接基线，避免开关允许与实例真实身份脱钩；提案第 2 项（升级脚本名字占用预检 + 中断续跑）**未实施**，仍待授权；标准只更新了命名规则，未写入预检/续跑契约。
- 两类基线实证（`--plan-only`，零写盘）：20101 基线（10-10 全量包，compose 无 `container_name`）→ 打印 `container naming: project-scoped (the baseline declares no fixed container names)`；20001 基线（09-30 更新包，三条固定名）→ 打印 `backend=qaflex-backend, frontend=qaflex-frontend, postgres=qaflex-postgres`。两类基线均成立，非「只删三行」。
- 相称测试：更新了原先锁定固定名的既有断言（改为基线声明命中），新增三条用例（项目隔离基线下增量 compose 不含 `container_name` 且仍引用外部卷；名字逐服务继承、注释行与非 `services` 段不污染；`resolve_context` 从 `--baseline-dir` 继承命名）。`python -m unittest scripts.test_package_intranet_offline` → 58 项 OK（10 项因本机无 Docker 跳过）。变异自检：把命名改回旧的 `external_postgres_volume` 硬编码后，compose 重新出现三条固定名，证明新断言必然咬住旧行为。
- 未触碰其他项目容器：本地演练栈旁存在 2 周前退出的全局 `qaflex-backend`/`qaflex-postgres`/`qaflex-frontend`，全程未删除或改名。
- 新包与演练：`qaflex-update-20261015T090845Z-f767474ac463`（归档 208,844,959 字节，SHA-256 `8f94ac63b6e644cf78cdcb76c6ab839471fadc8526bfe7ddd3c5e31f4075caf7`），直接基线 `qaflex-full-20261010T013316Z-24fbf3eb6925`，目标 Flyway `20261013.12`，`facts.rebuildRequired=true, scope=all`；独立审计（清单字段、归档结构与禁入项、`bash -n`、包内 `sha256sum -c`、包内无 `.env`/postgres 镜像/dump）通过。20101 隔离栈（project `qaflex-upgrade-sim-20101`，端口 20301/20302/15437）`backup → upgrade → rollback → backup → upgrade` **全部断言通过（0 失败）**：目标与基线镜像各自健康、后端 HTTP 200、Flyway `20260930.02 → 20261013.12`、两次 `counts.diff` 0 字节、PostgreSQL 容器 ID `e233cffa…` 全程不变、升级前后备份 dump 非空且 `pg_restore --list` 可读、迁移后端明确关闭后台调度且正常后端恢复 `PLATFORM_BACKGROUND_JOBS_ENABLED=true`。
- 演练环境约束（20001 演练须复用同一套做法）：① Docker Desktop 工具容器与主机不共享 loopback，包内脚本的 `127.0.0.1:<发布端口>` 探活需在工具容器内加 `host.docker.internal` 同端口转发（转发目标即同一发布端口，栈未发布或服务未就绪时同样失败）；② Windows Git Bash 的 MSYS `env` 使 `env -u VAR … docker compose …` 输出为空（实测带 `env -u` 长度 0、不带 4662 字节），而包脚本用 `env -u` 隔离外部环境变量，故 Git Bash 不能执行包脚本，必须用 Linux 工具容器；③ 夹具重置必须按 `deploy/runbooks/database-backup-restore.md:90,131` 先 drop/create 再恢复；用 `pg_restore --clean` 打到非空库会留下孤儿表，使后续升级的 `V20261012_01` 报 42P07（本轮一度误报为发布阻塞，已撤回）。
- 20001 未执行：按用户顺序待 20101 现场通过后，再以 09-30 基线出独立包、独立清单与独立演练；20101 的基线清单、备份与演练凭证不得作为 20001 的凭证。
- **20001 包与演练已完成（用户 2026-10-15 指示"本地测试通过后即开始制作 20001 包"，并强调包内必须自带完整部署指令）**：
  - 包 `qaflex-update-20261015T095058Z-55d27f35bafd`（归档 208,846,165 字节，SHA-256 `3d13a3de7047b003c6cec809d70d93313ed110f906907dfc4853204e001a2a72`），直接基线 `qaflex-update-20260930T044004Z-e275334105bb`，目标 Flyway `20261013.12`，`facts.scope=all`。包内 compose 按该基线继承**三条固定容器名** `qaflex-{postgres,backend,frontend}`；审计（清单、归档结构、`bash -n`、包内 `sha256sum -c`、无 `.env`/postgres 镜像/dump）通过。
  - 演练栈 `qaflex-upgrade-sim-20001-20261015` 按 20001 真实模型搭建：09-30 基线 `docker-compose.yml` 原样 + 自造 `.env`（project `qaflex-upgrade-sim-20001-20261015`，端口 20303/20304/15438，外部卷显式 `docker volume create`）+ 空库启动基线后端迁移到 `20260930.01`、读模型表 0 张。`backup → upgrade → rollback → backup → upgrade` **0 断言失败**：目标与基线镜像各自健康且后端 HTTP 200、Flyway `20260930.01 → 20261013.12`、两次 `counts.diff` 0 字节、PostgreSQL 容器 ID `0cde61e0…` 全程不变、备份 dump 可 `pg_restore --list`。**固定容器名在升级中被原地重建**（名字ownership 属本实例），未与其他栈冲突。
  - 本地环境处置（可逆、无删除）：该栈需要实例级固定名，而 `qaflex-{postgres,backend,frontend}` 三个名字被 2 周前已退出的旧演练栈 `qaflex-pkgtest-20260918-72ab3b365acb` 占用。仅执行 `docker rename` 改名保留（`retired-0918-qaflex-*`），未删除容器、未触碰任何 volume，也未停止任何在跑的栈。
  - **打包器 README 增强（应"包内必须自带完整部署指令"）**：`incremental_readme` 新增第 2 节的实例身份核对——基线声明固定名时逐个打印名字、状态、镜像与 `com.docker.compose.project` 标签，并要求占用者必须等于本实例 `COMPOSE_PROJECT_NAME`，明确"不要删除或停止其它实例的容器，也不要删除任何 `postgres` 容器"；基线无固定名时改为核对 `COMPOSE_PROJECT_NAME` 与三个 project 作用域容器。同时说明升级会**先替换现场 compose 再创建容器**，名字被占则须先回退。打包器回归 60 项 OK（10 项因本机无 Docker 跳过）。
  - 20101 适配包按新 README 重出为 `qaflex-update-20261015T100047Z-c691de911db4`（归档 208,847,679 字节，SHA-256 `72443746ed4fe18b3115bf71c8d782f869a6fef48470784e547d7cf2a66f39ed`）；与已演练包 `…T090845Z-f767474ac463` 逐文件比对确认差异**仅** release id／镜像 tag／包名（脚本、compose 无逻辑变化），并对其独立重跑演练以保持凭证与交付物一致。旧包保留不删。

## 目标与边界

- 诉求：更新包前后端固定容器名与同机其他端口平台实例重复导致部署失败，且失败后现场 compose 已被替换、重跑因基线失配被拒，必须回退才能继续——不合理。要求打包时可选择两种部署方案：(A) 先停掉旧前后端容器再部署新前后端（PG 不停）；(B) 什么都不停，直接部署更新。
- 成功标准：两种容器身份策略可按需选择打包；容器名冲突在任何现场变更之前被预检拦截（零变更失败，处置后直接重跑）；升级中断可续跑不必回退。
- 禁止：不推送远端；不动三件遗留物（fresh-package 计划 M、legacy-status 计划、audit SQL）；脚本不得自动停止/删除其他项目的容器（只预检+人工处置指引）；交付包不得使用 `--skip-*` 调试参数。

## 约束与背景

- 20001 现场 = 20260803 全新包基线：compose 无 container_name、项目内隐式卷（`qaflex_pgdata`/`qaflex_backend_logs` 由 project 作用域管理）。
- 根因（代码实证）：打包器 incremental 模式无条件 `compose_content(ctx, external_postgres_volume=True)`（package_intranet_offline.py:1321），对 postgres/backend/frontend 三服务强加固定名 `qaflex-*` + 外部命名卷。对旧更新链现场（18181）这是"沿用其原容器名"；对全新包基线现场（20001）则变成"改变其容器身份"并与 18181 的固定名冲突。
- 附加缺陷（本次调查发现）：现模板对 postgres 也强加 `container_name: qaflex-postgres`，而升级全程从不重建 PG 容器 → 现场（含本地演练栈）长期处于"compose 声明名 ≠ PG 容器实名"分叉；任何后续 `docker compose up -d` 都会尝试按声明名重建 PG 容器（违反 PG-ID 不变不变量；名字被其他实例占用时直接失败）。
- 升级脚本失败语义（upgrade.sh 实证）：基线镜像校验（:43-45）在 compose 原子替换（:73-77）之前，替换又在一切容器操作之前 → 任何替换后失败都使重跑被基线校验拒绝，回退是唯一出路。
- 标准既有规则：PG 容器 ID 不变是硬不变量；容器名冲突只能处理精确的前后端应用容器、不得借此删 PostgreSQL。

## 证据与根因

- 用户现场失败链：基线校验通过 → 镜像加载 → compose 原子替换 → `compose stop backend` → `compose up --force-recreate backend` 尝试创建固定名容器 → 名字被其他端口实例占用 → `set -e` 退出。现场终态 = 新 compose + 本项目旧 backend 已被 force-recreate 移除 + 新 backend 未建成，重跑被 `backend baseline image does not match` 拒绝。
- 打包器事实：`compose_content()` 为单模板+布尔开关（:535-546），fresh=项目内卷无固定名，incremental=恒固定名+外部卷。

## 方案与步骤

1. **打包器新增 `--container-naming {inherited,fixed}`**（仅 incremental 模式；默认 `inherited`）：
   - `inherited`（推荐默认，= 方案 B「都不停，直接部署」）：新 compose 的三服务 `container_name` 与卷拓扑逐字继承基线 compose——fresh 基线现场得到"无固定名 + 项目内隐式卷"（现场 `.env` 不再需要 `POSTGRES_VOLUME_NAME`/`BACKEND_LOG_VOLUME_NAME`）；旧链基线现场自然保留其固定名与外部卷。前后端镜像 tag 与模板新增运行参数（`PLATFORM_INSTANCE_ID` 注入、`GITLAB_DELETE_RECONCILIATION_ENABLED` 等）照常更新。同项目同名容器原地重建，构造上不可能与其他端口实例冲突。
   - `fixed`（= 方案 A「先停旧前后端再部署，PG 不停」）：前后端沿用固定名 `qaflex-backend`/`qaflex-frontend`（部署前提 = 两名字空闲；被其他实例占用时先人工停掉该实例的前后端容器，任何实例的 PG 都不停）；**postgres 服务身份在两种模式下都恒继承基线**，废除对 PG 强加固定名，消除 PG 意外重建地雷。
   - `RELEASE-MANIFEST.json` 记录 `compose.containerNaming`；README 按模式写明前置条件与失败处置。
2. **upgrade.sh 模板两道防线**：
   - **名字占用预检**（置于镜像加载与 compose 替换之前）：解析包内 compose 显式声明的每个前后端 `container_name`；被本项目容器占用 = 允许（原地替换语义）；被其他项目容器占用 = 打印占用容器 ID/镜像/状态与处置指引后失败，现场零变更；未声明 container_name 的服务名为项目作用域名，构造上不可能跨项目冲突，无需检查。
   - **断点续跑**：现场 `docker-compose.yml` 与包内 compose 字节一致时识别为中断续跑——跳过替换，重新校验备份绑定、PG 容器 ID、活动任务、磁盘、名字预检后，从"停止 backend"步骤整体重放（Flyway 版本化、计数重采、健康重等均幂等）。任何中途失败处置后直接重跑，不再必须回退。
3. **deploy/intranet-offline-packaging-standard.md 更新**：容器身份与卷拓扑继承规则（更新包唯一允许的 compose 变化 = 前后端镜像 tag + 新增运行参数；PG 服务身份永不改变）、两策略参数语义与适用场景、预检与续跑契约；替换现"可继续使用其原容器名"的模糊表述。
4. **验证**：打包器契约测试（inherited/fixed 三形态 compose 断言、预检与续跑模板断言、manifest 断言、fresh 模式不回归）全绿；全部生成脚本 `bash -n`；用 `--skip-*` 调试参数生成演练专用（不交付）脚本，在本地隔离栈实测两条路径：① 目标名被外项目容器占用 → 预检拒绝且现场 compose/容器零变更 → 人工释放名字 → 重跑成功；② compose 替换后人为中断 → 重跑识别续跑并完成升级。**重建 20001 正式包与完整八步演练按用户后续指令另行执行**（若现场尚未回退，先用现包 rollback.sh 恢复基线）。

## 决策记录

- 已拍板（用户 2026-09-09）：20001 重建更新包采用 `inherited` 策略（无需停任何实例、18181 不受影响、现场 `.env` 无需卷名变量）。
- 备份相关决策（每日一次、仅 20001、保留份数）已随备份主题迁至 `docs/plans/database-backup-automation-20260909.md` 决策记录。
- 已定：PG 服务身份恒继承基线（废除强加 `qaflex-postgres`）；预检只查显式声明名；不自动处置其他项目容器。
- 否决：脚本自动停止/删除其他项目容器（越权且违反标准红线）；预检也查项目作用域默认名（构造上不可能跨项目冲突）。

## 接口契约

- 打包器 CLI：`--container-naming {inherited,fixed}`，默认 `inherited`，fresh 模式不适用该参数。
- `RELEASE-MANIFEST.json`：`compose` 节新增 `containerNaming` 字段。

## 风险与假设

- 假设：占用固定名的其他实例源自历史更新链（无法本地实证）；预检设计不依赖该假设——无论占用者是谁都在变更前拦截。
- 风险：inherited 模式给 20001 注入 `PLATFORM_INSTANCE_ID` 会使会话 Cookie 名称切换为实例专属，升级后全部用户需重新登录一次（同时根治 20001↔18181 同主机跨端口 Cookie 串扰）；须在 README 与交付说明写明。
- 风险：续跑需覆盖多种中断中间态（compose 已替换/backend 半建/frontend 未动）；以"从停 backend 起整体重放"统一覆盖，依赖各步骤幂等性。
- 未验证：真实内网多实例并存的预检行为（本地以构造的占用容器模拟）。
