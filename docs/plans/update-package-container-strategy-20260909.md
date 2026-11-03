# 更新包容器身份策略（2026-09-09）

## 进度与中间物

- 状态：**方案定稿待用户审批，未动任何代码**。
- 恢复线索：审批通过后从「方案与步骤」第 1 步开始；涉及文件 `scripts/package_intranet_offline.py`、`deploy/intranet-offline-packaging-standard.md`。
- 日常自动备份主题已拆至 `docs/plans/database-backup-automation-20260909.md`（独立脚本先行 + 备份设置页两阶段），本文档只保留容器身份策略。

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
