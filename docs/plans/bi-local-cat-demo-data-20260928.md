# BI 本地 CAT 演示数据接入与阶段缺口盘点

> **文档版本**：v1.1（2026-09-28，阶段一已实施并验证；阶段二盘点已完成）
> **文档性质**：独立工作单元实施计划（本地开发夹具 + 本地验收；不改生产代码、不改任何产出契约）
> **权威依据**：`docs/bi-dashboard/README.md`（文档路由）、`集成测试平台接口使用手册.md`（CAT 接口原始事实）、`data-contracts.md`（映射与错误边界）、本仓源码实证
> **审查对象**：研发主管

---

## 进度与中间物

- **状态**：阶段一（假 CAT 服务端 + 真实链路驱动）已实施并端到端验证通过；阶段二（其它阶段缺口盘点）已完成并产出结论。待用户在 1920×1080 确认两页观感后收口。
- **恢复线索**：当前阶段=待用户浏览器确认；恢复后首条命令=`python scripts/mock-cat-server.py --port 18899`（前台常驻）再执行 `python scripts/seed-local-bi-cat-demo.py`；已有快照时无需重跑，直接访问 18181 `#/bi-dashboard/unit-test?productVersionId=10`。
- **已完成文件/变更清单**：
  - `scripts/mock-cat-server.py`（新增）：stdlib 单文件假 CAT 服务端，四接口按手册；14 模块 79 功能，模块计数/通过率按 95% 达标规则由功能反推（自洽）；UT 96.20% 达标、IT 87.34% 未达标；另有 `GET /healthz` 便于探测。
  - `scripts/seed-local-bi-cat-demo.py`（新增）：登录+CSRF → 保存配置（指向本地、关自动同步、打印原配置）→ 目录全量同步 → 轮询运行 → 保存映射（CAT 侧 ID 从已发布目录解析）→ 阶段全量同步 → 断言两页 `READY` 且达标/未达标并存；支持 `--catalog-only` / `--restore-config` / `--product-version-id`（逗号分隔多版本，**留空则跟随平台产品版本目录首项**）。
  - 映射目标是本单元唯一返工点（见“决策记录”）：初版把目标写死为 `CC2026R4`(id=10)，与实际默认版本不符，已改为跟随目录首项 `CC2026R3`(id=11)。
  - `docs/bi-dashboard/architecture.md`：新增“CAT 集成测试适配 → 本地演示数据”边界（只允许假上游 + 真实链路、生产代码禁 mock 分支、演示数据不进基线）。
  - `docs/bi-dashboard/progress.md`：本工作单元结论、用法与缺口盘点清单。
  - 未改动任何生产代码、未改动黄金基线、未新增数据库迁移。
- **测试状态**：无新增自动化测试（本单元不涉及生产代码；两脚本为本地开发工具，其正确性由实机链路验证）。实机证据（映射 `CC2026R3`=id 11）：两页 `READY`（`cat-mirror:11:UNIT_TEST:1` / `...:INTEGRATION_TEST:1`，14 模块 79 功能，UT 76/79、IT 69/79）；假服务端版本名与平台 business key 对齐后设置页建议唯一预填；下载授权 `authorized=true`、Excel 导出真实 `.xlsx`（4028B，PK 头）；停掉假服务端后两页仍 `READY`；浏览器两页渲染无控制台错误、无横向溢出。
- **当前阻塞/进行点**：无阻塞。用户决定先自行跑一遍确认观感，**暂不提交**，后续与其他改动分批提交；自动化浏览器窗口固定 531×552，纵轴标题换行属窄视口产物，`1920×1080` 最大化窗口观感由用户确认。是否把 CAT 映射扩到其它产品版本（当前仅 `CC2026R3`）待用户裁定。

---

## 目标与边界

### 用户原始需求

外网本地看不到 BI 部分图表与阶段的真实样式与数据，希望补充模拟数据。重点是**单元测试**与**集成测试**两页（内网采集平台与 CAT 平台未打通，页面恒无数据），要求按 CAT 接口手册的数据格式补齐；同时顺带盘点其它阶段缺数据的图表。

### 可验证的成功标准

1. `GET /api/bi/unit-test?productVersionId=10` 与 `GET /api/bi/integration-test?productVersionId=10` 返回 `status=READY`、非空 `sourceVersion`/`snapshotId`；模块矩阵与功能下钻两层均有数据，且同时出现达标（绿）与未达标（红）两种状态。
2. 浏览器在 18181 真实渲染两页：顶部指标带、模块矩阵（含 >12 项的缩放窗口与排序）、点击模块后的功能下钻均可见；至少一张图表可完成 PNG/Excel 下载（READY 后下载指纹存在）。
3. 全链路走真实镜像链路：`PUT /config` → `POST /full-sync`（目录）→ `PUT /mappings` → `POST /full-sync`（阶段快照）→ 页面读已发布快照；**页面与后端生产代码零改动**，不新增 mock 分支、开关、别名或回退。
4. 假服务端只在同步期在线；停掉后两页仍为 `READY`（快照已落库）。
5. 阶段二产出书面缺口清单（页面/图表 → 缺失事实 → 原因 → 建议），**不直接改数据**。

### 明确禁止（红线）

- 不改 `com.data.collection.platform.bi` 任何生产代码；不新增 demo/mock 开关或数据分支。
- 不用 SQL 直接伪造 `bi_cat_*` 已发布快照绕过采集器校验（必须保持“上游是假 CAT、链路本体真实”）。
- 不触发黄金基线更新：两页产出未变，`snapshots/bi/get___unit-test__default.json` 等保持既有空态基线。
- 不为演示修改产品口径、阈值（95%）或任何 `BI看板数据来源与计算口径核对表` 条目。
- 不在 `docs/` 存放临时报告；过程产物只放 `.tmp/`。

---

## 约束与背景

### 采集器硬校验（`BiCatSnapshotCollector`，违反即整次目录/阶段同步失败）

- 项目/版本/阶段/模块/功能 ID 在各自作用域内唯一，且不得为 null/空串。
- 阶段节点必须 `projectId`、`versionId` 与所属项目/版本一致；版本/阶段的 `group_id` 按手册（版本=`"0"`，阶段=版本 ID）。
- 模块 `moduleName` 必须与冗余字段 `name` 完全一致；`passFeatureCount`/`notPassFeatureCount` 非负；通过率必须在 0..100。
- 功能下钻响应 `totalCount` 必须**等于** `statisticsInfoList` 条数（即一次返回全量、不能分页）；`featureUniqueId` 非空且同模块内唯一。
- `data.result` 为空 → 快照 `EMPTY`（不是失败）；阶段整体失败不切换发布指针，旧快照继续可读。

### 映射与建议约束（`BiCatMirrorManager` / `BiCatScopeMappingRecommender`）

- `bi_cat_scope_mappings.product_version_id` 必须是平台真实产品版本（`issue_scope_catalogs/groups`，project 9，`dimension=TESTING_PHASE`），否则保存映射直接报错。
- 映射中的 CAT 项目/版本/两个阶段 ID 必须与**已发布目录**一致。
- 唯一建议依赖：默认项目唯一、版本名与产品版本 business key 语义匹配唯一、阶段名唯一 → 假目录按「1 项目 / 1 版本（名=`CC2026R4`）/ 阶段名=`单元测试`、`集成测试`」设计，使 `CC2026R4` 的建议被唯一预填。

### 本地环境事实（已实测）

- 开发库 `127.0.0.1:15432/qaflex`（容器 `qaflex-dev-postgres-15432`）当前：`bi_cat_mirror_configs` = 单行 `enabled=false`、`base_url=http://172.22.10.56:88`；`bi_cat_catalog_projects` / `bi_cat_catalog_publication` / `bi_cat_test_snapshots` / `bi_cat_test_publications` / `bi_cat_sync_runs` **全部 0 行**。
- 平台产品版本可选 `CC2024R2`…`CC2026R4SP1`（`issue_scope_groups` id 3…22）；本单元默认映射 `CC2026R4`（id=10）。
- CAT 出站无任何认证，`base_url` 只要求绝对 http(s) 且无 userinfo/query/fragment → `http://127.0.0.1:18899` 合法；四个接口路径可由 `PLATFORM_BI_CAT_*_PATH` 覆盖，默认值已与手册一致。
- 本地登录：`GET /api/auth/current` 取 `X-XSRF-TOKEN` 响应头 → `POST /api/auth/login`（`admin/admin123`）；`scripts/real_chain_api_smoke.py` 已有可复用的 cookiejar + XSRF 模式。

---

## 证据与根因

- **两页恒无数据的根因不是前端**：`BiCatTestSourceAdapter.load()` 只读本地已发布指针（`bi_cat_scope_mappings` JOIN `bi_cat_test_publications` JOIN `bi_cat_test_snapshots`），命中不到就抛 `BiCatContractUnavailableException` → `BiCatTestPageService` 产出 `INCOMPLETE`（消息尾部固定声明“不会使用数据采集平台或 mock 补齐”）。开发库指针表全空，故两页必然为空。
- **页面请求从不访问 CAT 网络**（类注释明示），因此“让本地上游产出合法快照”即可让页面 `READY`，无需触碰页面代码。
- **替换上游已被现有测试证明可行**：`BiCatMirrorSyncServiceTest` / `BiCatSnapshotCollectorTest` 用 JDK `HttpServer` + 把 `Config.base_url` 指向 `127.0.0.1:<随机端口>`，跑通了“目录 → 阶段 → 原子发布”完整链路；本单元只是把同样的桩提升为常驻本地服务。
- **顺带收益**：这条链路会在本地把“手册契约 vs 适配器/采集器校验”整条跑一遍，正是内网联调一直缺的那一步。

---

## 方案与步骤

### 阶段一：CAT 两页演示数据（本次主体）

1. **`scripts/mock-cat-server.py`**：stdlib 单文件假 CAT 服务端（`http.server`），按手册实现四个接口，统一 `{"code":200,"message":"success","data":…}` 信封。
   - 数据确定性生成（`--seed`，默认固定），无外部依赖。
   - 目录：1 个项目（`defaultProject=true`）→ 1 个版本（`name="CC2026R4"`，`curVersion=true`）→ 2 个阶段（`单元测试`、`集成测试`），字段严格满足采集器校验。
   - 阶段统计：14 个模块（超过 12 项以触达图表的缩放窗口），模块名用真实业务模块（草图/零件与特征/装配/工程图/钣金/曲面/焊件/模具/仿真/数据管理/参数化约束/导入导出/渲染与显示/图纸标准化）。
   - **数据自洽**：先造功能（每模块 3–9 个，通过率覆盖高/中/低与 95.00 边界），再由“通过率 ≥ 95% 视为达标”的规则反推模块 `passFeatureCount`/`notPassFeatureCount`/`testPassRate`，整体 `passRate` = 达标合计 / 统计合计（与手册“整体通过率由所有模块汇总得出”一致，不发明新公式）。
   - 单元测试阶段整体调成**达标**、集成测试阶段整体调成**未达标**，使两种状态样式都能看到；两阶段模块通过率分布不同，页面不雷同。
   - 支持 `--base-url`/`--port`/`--project-id`/`--version-id`/`--unit-phase-id`/`--integration-phase-id`。
   - 启动时打印醒目声明：本服务是**本地演示替身**，数据非真实 CAT 数据。
2. **`scripts/seed-local-bi-cat-demo.py`**：登录 + CSRF，然后按真实链路驱动：`PUT /api/bi-cat-mirror/config`（`enabled=true`、`baseUrl=http://127.0.0.1:18899`、`autoSyncEnabled=false`）→ `POST /full-sync` → 轮询 `GET /settings` 直到目录已发布 → `PUT /mappings`（用 `--product-version-id`，默认 10；CAT 侧 project/version/phase ID 从已发布目录读取，不硬编码）→ `POST /full-sync` → 轮询运行状态至终态 → `GET /api/bi/unit-test` 与 `/integration-test` 断言 `READY` 并打印 `sourceVersion`/模块数/功能数；失败时输出运行记录的失败原因。
   - `--catalog-only`：只发目录，便于调试映射。
   - `--restore`：把配置恢复为内网默认（`enabled=false`、`base_url=http://172.22.10.56:88`、关自动同步），并提示已发布快照仍可读。
   - 自校验：脚本结束时对两页响应做结构断言（status、整体计数、模块非空、功能非空、达标/未达标各至少一项），不满足即非零退出。
3. **验证与验收**：先跑脚本自校验；再由用户在 18181 用浏览器实机查看两页（顶部指标带、模块矩阵、缩放/排序、下钻、两种状态颜色），并下载一次 PNG/Excel；随后停掉假服务端复验两页仍 `READY`。
4. **文档**：`docs/bi-dashboard/progress.md` 追加本轮结论、用法与恢复线索；`docs/bi-dashboard/architecture.md` 只补入“本地演示数据工具”的一行引用（不展开过程）。固化用法与陷阱按纪律归入权威文档，不在 `docs/` 留临时说明。

### 阶段二：其它阶段缺口盘点（先盘点、后决定）

5. 对六个阶段页与客户问题页逐个取真实接口响应，记录 `status`/分区状态/图表数据量，定位“哪些图表因事实不足而视觉上无内容”，产出清单（页面 → 图表 → 缺失事实 → 是否已有 `seed-local-*.sql` → 建议动作）。**本步骤只读、只产出清单**；是否补数由用户逐项决定，另行开工作单元。

---

## 决策记录

- **选“假 CAT 服务端 + 真实链接”，否决“页面加 mock”**：页面加 mock 直接违反 `product.md`（“正式数据不得使用静态 mock/零值补齐”）与最终验收标准（“没有 mock、零值补齐、跨来源拼接”）；而假上游不动生产代码，且顺带验证手册契约。
- **否决“SQL 直插 `bi_cat_*` 快照”**：虽与既有 `scripts/seed-local-*.sql` 同性质且更省事，但会跳过采集器校验与原子发布，无法证明“手册数据能通过真实链路”，演示价值与风险都不划算。
- **选 Python stdlib 单文件脚本**：仓库 `scripts/` 已有 `.py` 先例（`real_chain_api_smoke.py` 等），零新依赖；PowerShell 需额外处理 `.ps1` 的 UTF-8 BOM（仓库有明文约束），不值当。
- **演示数据自洽而非任意值**：模块计数与整体通过率由功能通过率按手册 95% 规则推导，避免演示数据本身自相矛盾被误读为口径证据。
- **映射目标必须跟随平台默认版本（本单元返工点）**：初版把映射目标写死为 `CC2026R4`(id=10)，理由是“看起来是默认版本”——实际那是验收 URL 里显式带了 `?productVersionId=10` 造成的假象。真实默认由 `BiPlatformProductVersionAdapter.catalog()` 的 `groups.getFirst()` 决定，当前是 `CC2026R3`(id=11)；映射到非首项版本会让用户从 BI 导航进入时仍看到 `INCOMPLETE`（用户实际遇到）。现改为脚本默认跟随目录首项，并支持逗号分隔多版本。
- **保存映射是整体替换**：`BiCatMirrorManager.saveMappings` 走 `replaceMappings`，提交多个版本必须放在同一次请求里；切换映射目标会移除旧版本映射（旧快照行保留但页面 JOIN 不到映射，回到 `INCOMPLETE`）。
- **同步后必须刷新页面才能看到新数据**：平台既定行为是已打开页面不自动替换数据，页面需点「刷新当前页面」或重载；否则会持续显示旧的 `INCOMPLETE` 响应。这是本轮排查用户反馈的直接原因之一，必须在交付说明里明确。
- **两页状态刻意分化**（UT 达标 / IT 未达标）：为了让两种状态样式都能被看到，属演示设计，需在脚本文档中显式声明，不得当作业务结论。
- **不动黄金基线**：产出未变，两页快照保持空态基线；若有人误用 `-Dgolden.update=true` 重建，会把本地演示数据固化进基线，属明确禁止项。

---

## 接口契约

- 假服务端默认监听 `127.0.0.1:18899`，四路径与手册一致：
  - `POST /integrationSearch/getAllProject`
  - `POST /testingPhase/getAllByProjectId?projectId=<id>`
  - `POST /integrationSearch/getStatisticsInfoByTPId?testingPhaseId=<id>`
  - `POST /getFeatureInfoByModuleId`（body `{"moduleId":…,"testingPhaseId":…}`）
- 驱动脚本参数：`--base-url`（默认 `http://localhost:18181`）、`--username`/`--password`（默认 `admin`/`admin123`）、`--cat-base-url`（默认 `http://127.0.0.1:18899`）、`--product-version-id`（逗号分隔多版本；留空 = 平台产品版本目录首项）、`--catalog-only`、`--restore-config`。
- 数据落地：`bi_cat_mirror_configs`（1 行，被驱动脚本更新）、`bi_cat_catalog_*`（目录）、`bi_cat_scope_mappings`（1 行）、`bi_cat_test_snapshots`/`bi_cat_test_modules`/`bi_cat_test_functions`/`bi_cat_test_publications`（两阶段快照与发布指针）、`bi_cat_raw_responses`（原始响应留存）、`bi_cat_sync_runs`（运行记录）。
- 页面 `sourceVersion` 形如 `cat-mirror:<productVersionId>:<UNIT_TEST|INTEGRATION_TEST>:<publishedVersion>`；规则版本 `bi-cat-test-v2` 不变。

---

## 风险与假设

- **假设**：本地 `admin` 具备 `SYSTEM_MIRROR_CONFIG` / `SYSTEM_MIRROR_SYNC` 权限（待实测；不具备则脚本明确报错并提示所需权限）。
- **假设**：本地后端 18080 与前端 18181 已启动；未启动时先按仓库约定用 `pwsh` 拉起，不擅自停服。
- **风险**：若保持 `autoSyncEnabled=true` 而假服务端离线，会按调度累积失败运行记录 → 驱动脚本结束时显式关闭自动同步。
- **风险**：演示数据进入本地开发库后，后续若在开发库上做 BI 截图或评审，需明确标注其为本地演示数据（非内网事实）。
- **风险**：`PUT /config` 会覆盖本地既有配置（当前为内网地址 + 禁用）；`--restore` 负责恢复，执行前先打印原配置以便核对。
- **敏感只读**：不修改任何既有业务表；仅写入 `bi_cat_*` 专属表。
