# 全链路黄金基线回归测试系统

## 进度与中间物

- 状态：实施进行中（阶段 1-8 完成；阶段 9 收尾验证运行中：默认快速套件+静态分析+文档收口）。
- 已完成文件/变更：计划文档；`scripts/extract_golden_baseline_fixtures.py`；`scripts/gitlab_bounded_sql_export.py`（闭包修复 2 处）；`backend/src/test/resources/golden-baseline/`（manifest v3 + fixtures + endpoint-catalog.yml + snapshots）；`backend/pom.xml`（json-unit-assertj 4.1.0 test 依赖、surefire 默认排除 golden-baseline 标签、golden-baseline profile 含 golden.update 属性透传）；`backend/src/test/java/com/data/collection/platform/golden/` 下 `GoldenBaselineSupport.java`（双容器编排+夹具导入+DIRECT 种子+登录会话+raw 响应/导出字节客户端+终态等待+标量查询；请求超时 120s）、`GoldenBaselineChainTest.java`（RANDOM_PORT 主套件+链路 smoke 断言+@TestFactory 读/导出快照遍历执行器）、`GoldenEndpointCatalog.java`（YAML 目录模型+加载）、`GoldenSnapshotSupport.java`（快照引擎：JSON/Excel 规范化+JsonUnit 严格比对+显式更新模式）、`GoldenBaselineCoverageGuardTest.java`（覆盖护栏：默认套件运行，反射枚举⊆目录双向匹配+条目结构校验）；`backend/src/main/resources/db/migration/V20260901_01__fix_match_mode_unique_constraint_schema_scope.sql`（schema 约束 bug 修复，见决策记录）；生产 NPE 修复 3 处（`CodeReviewMatchModeRecordLoader.sortColumn`、`CodeReviewIllegalRecordSqlQueryBuilder.sortColumn`、`IssueFactRecordSortSupport.sortColumn` 加 null 防御）+ `IssueFactRecordSortSupportTest` 回归单测。
- 已验证：golden 全链路真实同步 SUCCESS（四跑修复 minRunId 等待逻辑后，`Tests run: 1, Failures: 0`，Maven 总耗时 2:40，测试本体 133.3s）；覆盖护栏双向匹配 165 端点全通过（目录参数修复后复跑仍 2/2 通过）；快照引擎编译+Checkstyle 通过（仅剩 2 个未跟踪外部文件的既有违规，非本单元文件）。
- 覆盖全景（endpoint-catalog.yml，最终）：165 端点 = READ 62 + OPTIONS 11 + EXPORT 16 + WRITE 33 + EXCLUDED 43；读/导出用例 139 个 + 写用例 33 个（+1 链路 smoke = 173 测试）；43 条排除全部显式登记原因（async-trigger/external-dependency/session/destructive/empty-runtime-config 五类）。
- 已发现并修复的生产级缺陷：① 黄金基线首跑即抓到导出路径 NPE——JDK 不可变 Map（`Map.copyOf` 产物）对 null 键 `getOrDefault` 抛 NPE，导出请求 sortField=null 是合法输入，3 处同型代码修复+回归单测；② `V20260702_12` 删除 match_mode 唯一约束时硬编码 public schema（详见决策记录）；③ 导出脚本依赖闭包缺陷 2 处（详见决策记录）。
- 种子清单定稿 v3：16 表。中间版 v2 曾补抽 5 张表（label_groups/issue_scope_catalogs/groups/members/platform_role_permissions）后导入主键冲突——核查确认它们全部是 Flyway 迁移播种表（V20260622_07/V20260727_02/V20260821_01/V20260720_01/V20260804_01），开发库行数即迁移产物而非人工数据，全部移出抽取清单，golden 库以迁移种子值为准；`collect_form_records` 开发库为空表，对应端点基线空态（如实记录）。
- 首跑结果：127/142 用例成功；15 个失败全部归因（NPE×2 已改代码；mirror-table-overview 页面/导出×2、expand×1 改排除+根因注释；quality-board projectName×3；动态规则 sourceKey/fieldKey×2 改合法键；multi-board 详情 topic×2 补参数；database-browser 表名×1 改白名单表 gitlab_sync_configs；诊断超时×2 上调客户端请求预算 30s→120s 后实测通过）。
- 第二跑（update 全量）：137/140 通过，仅剩 quality-board projectName×3 仍报"研发质量看板范围不存在或已停用"——深挖确认合法值是 `issue_scope_groups` 启用业务键而非 catalog 标签（见决策记录），改为 `CC2026R4`。诊断端点 120s 预算下正常返回，无超时。
- 第三跑（update 全量）：**140/140 全部通过（0 失败 0 错误），测试本体 395.7s，139 个读/导出快照全部生成**。
- 数据覆盖确认（用户问询"测试数据是否包含评审数据与代码走查"）：**包含**。用户提供的导入源文件与夹具物化表行数闭环——`spider_crowncad_data.json` 30,308 条 → `code_review_match_mode_records` 30,508（后续零星新增 +200）；`reviewReport.json` 435 条 → `review_data_match_mode_reports` 436；`problemDetail.json` 4,549 条 → `review_data_match_mode_problem_details` 4,550。快照实证：review-data records total=440、code-review illegal-records total=8,545。
- 快照路径 bug 修复：`snapshotName` 对根路径端点（GET /，controller 段为空）生成 `/get____default.json`，`Path.resolve` 前导斜杠解析为盘符绝对路径，快照写到 `D:\` 根目录——已修复（空段回退 `home/`），游离文件迁入 `snapshots/home/`。
- 零差异验证跑（首次）：140 用例 / 21 失败 + 1 错误，全部完成根因定位与修复，六类根因：
  1. **快照缺失错误 ×1（home）**：验证跑 JVM 编译了旧 `snapshotName` 代码（前导斜杠→盘符根路径）；修复已入树，重跑即愈。
  2. **数字标度表示差异 ×5**：服务端 BigDecimal 标度（`30.30`/`2.000`）与 Jackson Double 往返最短表示（`30.3`/`2.0`）被 JsonUnit 判不等——比对前对实际响应执行与快照写入一致的 Jackson 规范化往返（`prettyOrRaw`），双侧同构后消除。
  3. **墙钟派生字段 ×9**：retentionHours（滞留时长逐时递增）、导出 U 列缺陷滞留时长（含 DB `now()` 的 UTC/CST 8h naive 时差，整列掩码 `$..U` 并记录取舍）、gitlab-sync 配置行时间戳/lastFullSyncAt/dbPort（Testcontainers 映射端口逐次不同）、source-health 数组字段（**旧掩码缺 `[*]` 从未生效**）、table-sync-diagnostics 连接池指标/生成时间、database-browser lastSyncTime/db_port、dgm-gitlab-project-source updatedAt——逐端点补目录掩码。
  4. **stale 掩码 ×1**：gitlab-sync/status 旧掩码引用不存在的 `latestRun/currentRun` 形状，按真实响应形状（config+logs）重写。
  5. **DISTINCT 选项顺序非确定 ×1**：statistic-boards customer-issue-defect-summary 的 `definition.filters[*].options` 集合恒定但顺序跨运行轮转（无 ORDER BY 聚合）——目录新增 `ignore-array-order` 机制（JsonUnit `when(paths(...), then(IGNORING_ARRAY_ORDER))`）。
  6. **目录顺序打散 ×（诊断障碍）**：`Map.copyOf(LinkedHashMap)` 返回按 JVM salt 散列序的 MapN，动态用例索引与 YAML 序错位——改 `Collections.unmodifiableMap` 保序；失败信息此前不含快照名（`.as()` 描述不进 surefire 消息头），`AssertionError` 包装重抛自带快照名。
- 机制修正过程中的自查纠偏：ignore-array-order 最初误加在 analytics-dashboards details（该响应无 options 数组），经快照内容反查确认 #126 实际属于 statistic-boards 主看板，掩码迁移至正确端点。
- 中间验证跑（含 dgm 修复）：140 用例 / 1 失败——`issue-scopes/catalogs` 列表端点 `$.data[*].createdAt/updatedAt`（目录行由协调器每次重建，首轮 22 失败中因索引错位漏归因）；补目录掩码后（快照名已随 AssertionError 包装直接出现在失败消息中，定位零成本）。
- **最终零差异验证：140/140 全部通过（0 失败 0 错误），测试本体 348.8s，Maven 总时长 6:05**。读/导出基线确定性成立：快照 139 个在全新运行（双容器重建+真实同步+事实+统计链路）下零差异。实测时长与 1 分钟目标的差距及原因（Testcontainers 双容器+Flyway+全量同步+140 端点遍历的固有成本）记录于风险与假设。
- 当前进行点：**阶段 9 完成，工作单元全部验证收口，等待用户决策提交**（工作树混同事未提交重构，提交策略需用户定）。
- **收尾验证结论（2026-09-02）**：默认快速套件注入测试库 env（开发库 15432 + 隔离 schema qaflex_test）后 **1196 项 / 1 失败 / 0 错误**——唯一失败为同事未提交 WIP 测试 `ReviewDataRecordReadSupportTest`（untracked 文件，先前存在）；Checkstyle 仅 2 处未用 import 违规且均在同事 untracked 文件（此前已记录）；SpotBugs **0 bugs**；四项门禁脚本全绿（清理本单元 8 个临时残留文件）。**本单元零新增失败、零静态违规。**
- 突变自证（阶段 8）：行为破坏（LEVEL1 标签）→ 25 处可读 diff 失败 → 还原复绿 173/173；删目录条目 → 护栏 2.3s 失败精确指认 → 还原复绿 2/2。
- 写用例目录填写（33 个 WRITE 用例 + 2 个转 EXCLUDED，护栏 2/2 通过）：
  - **refresh-one×2（issue 维度）**：question-metrics/customer-issues 用 issue_fact 最小键四段变量（含 DB id），affected = issue_fact（scope 单行，掩码 fact_refreshed_at/created_at/updated_at）+ issue_fact_customer_members（scope 三键）+ issue_scope_groups/members（REPLAY 全表，覆盖 rebuild 幂等对账）。
  - **review-data×7**：记录 CRUD 声明全子表（review_records/experts/problem_items/descriptions/contents）；记录删除为软删（表快照 deleted=true 可见）；createPendingProblemItems=true 扩大问题项覆盖；backfill 用 batchSize=50。
  - **collect-forms×3**：save 用唯一新上下文插入 1 行；delete 在空表上删除不存在上下文 → data=false 确定性契约；update-record 不存在 id → 400 确定性契约（校验前置）。
  - **issue-scopes×13**：目录/组/成员 CRUD 全覆盖；变量选"组最多的目录"与"成员最多的组"保证 order 用例 id 列表完整（string_agg）；新建目录用 projectId=999999、组用 businessKey=golden-group、成员用 sourceValue=golden-member-value 规避唯一约束。
  - **label-groups×3**：新建 STATIC 组（applicableScope 合法值 SAME_TYPE/SAME_FIELD，非 GLOBAL）；系统默认组名称/适用范围被锁定（isSystemDefaultGroup），更新只改描述+成员整体替换；删除最小 id 组。
  - **permission×2**：NORMAL_USER 设两个权限码 + 恢复默认；platform_role_permissions REPLAY。
  - **gitlab-sync config PUT**：更新现有行（uk_source_instance 禁止新建）；dbHost/dbPort 为运行期容器变量；响应与表快照掩码 db_port/墙钟列。
  - **match-mode settings PUT**：写入不可达假配置（save 仅落库不连接外部）；**dgm-source PUT**：写入假数据源配置。
  - **转 EXCLUDED×2（本轮决策）**：① code-review illegal-records/refresh-one——快照实证 settings.enabled=true（迁移播种 values(1,true,true)），兼容分支调用老平台 MySQL/HTTP 活体（172.22.10.72），golden 禁止触碰外部系统；enabled=false 的本地 rebuildMergeRequestFactByIid 分支与 issue 维度 refresh-one 复用同一 rebuild 引擎。② match-mode-db-settings/formal-import——确认文本与乐观锁校验通过后即连接外部 MySQL 活体（种子 mysqlHost=172.22.10.72 非空，此前"配置不完整→确定性 400"的判断不成立），且导入会改写正式事实归属。
  - **引擎小改**：AffectedTable.scope 支持 {{变量}} 替换（snapshotScopedTable），issue 维度表快照 scope 用运行期事实键。
  - **首跑暴露 1 处修复**：`reviewRecordWithItemsId` 变量查询用错列名 `record_id`（实际为 `review_record_id`，外键引用 review_records(id)）——该变量在 #6 读用例阶段未被任何用例引用，本次首跑首次真实执行即暴露；修复后重跑 update 首跑。
  - **update 重跑 6 错误（4 类根因已修复并经重跑验证 173/173 通过）**：
    1. `pg_get_serial_sequence` 对无 id 列的复合自然键表（issue_fact_customer_members、platform_role_permissions）**直接报错而非返回 NULL** → realignIdSequence 前置 pg_attribute id 列存在检查，无 id 列跳过（涉及 refresh-one×2、permission×2 共 4 个错误）。
    2. collect-forms update-record 确定性 400（表单记录不存在，校验前置）被客户端 requireBodyOk 拒绝 → 新增 `exchangeAnyJson`（嵌入 httpStatus 的 `{"httpStatus":N,"body":{...}}` 结构），写用例响应快照统一嵌入状态码——确定性 400 校验拒绝与 200 成功同为行为契约。
    3. label-groups DELETE 系统默认组被 isSystemDefaultGroup 校验拒绝（400）→ 用例改判 `default-group-denied` 400 契约（校验前置不改表），计划文档"删除最小 id 组"描述同步作废。
    4. 无调用者的 `exchangeRawJson`（requireBodyOk 版本）已删除，避免下一个写用例误用非契约化客户端。
  - **update 重跑（修复后）：173/173 全部通过（0 失败 0 错误），测试本体 372.7s，Maven 总时长 6:21**。写用例快照落盘：33 个响应快照 + 57 个表状态快照（合计 90 个新快照，总快照 229）。
  - **快照审阅发现的墙钟字段补掩码（16 个端点）**：写响应 DTO 携带行级 createdAt/updatedAt（协调器重建行/新建行的本运行时刻），零差异必炸——响应快照扫描脚本（ISO 时间戳值匹配）定位 19 个含时间戳快照，其中 3 个端点（gitlab-sync config、match-mode-db-settings、dgm-source）已有掩码，为其余 16 个端点补 `$.data.createdAt/updatedAt` 族掩码（issue-scopes 9 + label-groups 2 + collect-forms save 1 + review-data 4，groups PUT/PATCH 另含 `$.data.members[*].createdAt/updatedAt`）；辅助扫描（duration/port/timestamp 类键名）确认无其他易变字段（mysqlPort/reviewDurationMinutes 来自请求体、dbPort 已掩码）。护栏复跑 2/2 通过。
  - **首次写用例零差异验证：173 用例 / 27 失败，4 类根因全部定位并修复**：
    1. **B 类 ×19（路径空间错位，含 3 个已有掩码端点）**：WRITE 响应快照树为 `{"httpStatus","body"}` 包装，目录 ignore-paths 却按 ApiResponse 空间书写（`$.data.*`），JsonUnit 全部落空——update 模式只写不比，掩码从未被校验过。修复：19 端点 49 个路径项统一改 `$.body.*` 前缀，目录头注释固化路径空间约定（READ=`$.data.*`，WRITE=`$.body.*`）。
    2. **A/C 类 ×4（表快照缺时间戳掩码）**：refresh-one 的 issue_scope_groups/members、permission 的 platform_role_permissions 表快照缺 ignore-columns——迁移播种/协调器重建行的 created_at/updated_at 是本运行时刻。修复：4 个 affected-tables 声明补 `[created_at, updated_at]`。
    3. **D 类 ×4（生产查询非确定性，写用例引擎 REPLAY issue_fact 改变物理布局/计划后暴露）**：`IssueFactRecordRepository.FACT_SQL` 无 ORDER BY（规则说明 samples 取列表前 3 条，跨运行整组换血）；`IssueFactFilterValuesQuerySupport` 两段候选值 SQL 的 30 处 `string_agg(value)` 无 `order by`（候选值顺序随计划轮转）。**生产级修复**：FACT_SQL 加 `order by id`、全部 string_agg 加 `order by value`（候选值字典序展示，语义改善）；相关单测 30/30 通过。受影响读快照（samples×3、候选值顺序若干）由 update 重跑重生成后审阅。
  - **SQL 修复的自查纠偏（update 跑 #2 污染 → #3 重生成）**：首版修复把 `order by id` 直接拼在 FACT_SQL 末尾，而 `IssueFactQueryService.query` 的契约是在 selectSql 末尾**继续追加** `and ...` 过滤与额外谓词——拼出的 `where deleted = false order by id and project_id = ?` 语法错误，被 findByFilters 的 catch 静默吞成空列表（update 模式不比对，跑完全绿但快照被污染：rule-explanation inputCount 全 0、samples 空）。审阅快照时发现 samples=0 + 列表 total 正常的矛盾暴露问题；修复：恢复 FACT_SQL 纯 where 形状，排序经 6 参 query 的 suffixSql 传入（`... and (...) order by id`），并注释固化该契约。教训：**改共享 SQL 模板必须先读 query() 拼接链**，"update 模式全绿"不等于"快照内容正确"，重生成后必须内容审阅。
  - **零差异复跑 #3：173 / 2 失败，继续收敛**：① permission PUT 响应 `roles[4].permissionCodes` 顺序轮转——`PlatformPermissionService.permissionsForRoles` 的 `select distinct permission_code` 无 order by（第 3 处同根因），加 `order by permission_code` 根治；② match-mode settings 表快照键集不匹配——**表快照的 ignore-columns 是生成期剔除而非比对期忽略**，掩码改动必须经 update 重跑重生成冻结快照（与响应 ignore-paths 的比对期语义不同）。dgm 表的 created_at 后经 DDL 实证**该列不存在**（快照键集 14−3 自洽），无需掩码。
  - **update 跑 #4（掩码重生成）：173/173 通过（400.9s），快照审阅通过**：match-mode 主表键集 14−4 掩码列自洽；dgm 表 11 键 = 14 列 − 3 掩码列；permission PUT/restore 响应 permissionCodes 全角色字典序、表快照 260 行二列有序。
  - **零差异 #4 终验通过：173/173 全部通过（0 失败 0 错误，测试本体 417.8s）——阶段 7 完成**。全部 229 个快照（读/导出 139 + 写响应 33 + 写表状态 57）在全新运行（双容器重建+真实同步+事实+统计+写用例）下零差异。写基线确定性成立。
- **突变自证（阶段 8）**：
  - **突变 A（行为破坏）**：`IssueDisplayValueSupport.displaySeverityLevel` 的 LEVEL1 标签 `"一级缺陷"` → `"一级缺陷突变"`（纯格式化函数，43 个冻结快照含其输出）→ golden 套件 **173 用例 / 25 失败**，全部集中在读/导出快照比对；每条失败消息均为可读 diff——快照名 + 精确 JSON 路径 + 期望/实际值（如 `statistic-boards/get___{boardKey}__system-test-defect-summary.json | data.definition.filters[4].options[0].label | expected: "一级缺陷" but was: "一级缺陷突变"`）。还原后**复绿 173/173（370.1s）**，文件与 HEAD 逐字一致。
  - **突变 B（覆盖缺失）**：临时删除目录首条 `GET /` 条目 → `GoldenBaselineCoverageGuardTest` 2.3s 即失败，消息精确指出未登记端点：`以下 Controller 端点未登记黄金基线目录（EXCLUDED 也必须显式登记 + 原因）Expecting empty but was: ["GET /"]`。还原后护栏复绿 2/2。**阶段 8 完成：测试系统自证具备捕获回归与覆盖缺失的能力。**
- 写用例引擎设计（已实现，GoldenWriteCaseSupport + 目录模型扩展）：
  - **目录模型**：WriteCase(id/query/body/affectedTables) + AffectedTable(table/scope/ignoreColumns/restore)；restore 五档 AUTO(→REPLAY)/REPLAY/TRUNCATE/DELETE(须 scope)/NONE；WRITE 条目必须有 ≥1 write-case（覆盖护栏强制，无法快照的行为须显式改判 EXCLUDED）。
  - **双源 REPLAY**：种子文件内表按 `-- table:` 注释分块重放；不在种子文件的表（迁移播种/协调器重建/同步产物：issue_scope_*、label_groups 族、platform_role_permissions、gitlab_sync_configs、code_review_match_mode_db_settings、collect_form_records、审计表、issue_fact、merge_request_fact 等）在 @BeforeAll 同步完成后运行期捕获整表基线，重放=DELETE(关闭外键触发器 session_replication_role=replica，TRUNCATE 无法绕过外键引用检查)+分批回插+id 序列 setval 校准。
  - **无序安全**：每个写用例执行前预还原（起点干净）、finally 后还原（不污染后续用例与读快照），写用例与读快照的相对执行顺序无关紧要。
  - **表状态快照**：`select * [where scope] order by 全列`，剔除声明易变列（时间戳审计列等），序列化为 {"table","rows"} JSON 走既有 assertJsonSnapshot；快照名 = 用例响应快照名 + `__<表名>.json`。
  - **HTTP 客户端**：GoldenHttpSession 的 exchangeAnyJson(method,path,body) 支持任意方法写请求，响应嵌入 HTTP 状态码（400 校验拒绝同为契约）。
  - **新增运行期变量**：reviewRecordWithItemsId/problemItemId（review_problem_items 最小 id）、issueKeySource/issueKeyProjectId/issueKeyIid（issue_fact 最小 (source_instance,project_id,issue_iid)，refresh-one 用例键）、settingsUpdatedAt（match-mode 单例行 updated_at ISO 格式，formal-import 乐观锁入参）。

## 恢复线索

- 当前阶段：阶段 8 完成（突变 A 25 处可读 diff 失败→还原复绿 173/173；突变 B 护栏 2.3s 失败→还原复绿 2/2）；阶段 9 进行中（architecture/decisions/progress 已更新，四项门禁已过，默认套件+静态分析运行中）。
- 恢复后首条命令：`cd backend && ../tools/maven/apache-maven-3.9.9/bin/mvn.cmd test -Pgolden-baseline -Dtest=GoldenBaselineChainTest`（Docker 需在运行；快照已存在则严格比对，预期全绿）。写用例快照缺失时严格模式即失败，重建须显式 `-Dgolden.update=true` 后 git diff 审阅。
- 用户批准的完整方案存于会话计划文件；本文档是仓库内的权威工作单元计划。
- 相关背景计划：`docs/plans/establish-golden-baseline-20260825.md`（黄金线环境部署，已完成）、`docs/plans/replace-compatibility-mode.md`（兼容模式将最终删除，基线不纳入 MySQL/Mongo 活体同步）。

## 目标与边界

- 用户原始需求（2026-09-01 确认）：把当前可信版本的全部数据源固定为测试数据，走平台完整链路（同步→事实→统计），遍历**所有**产出数据的接口（表格数值、Excel 下载每个单元格、下拉框/筛选选项、统计看板）并保存为基线；以后任何修改/重构后重跑一遍并与基线机器 diff，差异即回归。未覆盖的接口是高风险项，必须有机器强制的覆盖清单，不允许静默遗漏。
- 已确认决策：① 完整链路（源数据→同步→事实→输出），非只测输出层；② 写接口纳入（固定输入执行，快照响应+执行后关键表状态，隔离还原）；③ 基线数据从本地开发栈 15432 抽取。
- 成功标准：`mvn test -Pgolden-baseline` 全绿且连续两次全新运行零差异；突变测试证明破坏行为必被捕获（可读 diff）；覆盖护栏 100% 登记（EXCLUDED 需显式原因）；默认快速套件不受影响；Checkstyle/SpotBugs/四项门禁通过。
- 明确禁止：不自建测试框架（用 JUnit5/Testcontainers/JsonUnit/POI）；不修改冻结后的夹具（改动=新基线版本+用户确认）；不触碰同事未提交文件；不在未确认时提交/推送；默认套件禁止变慢。

## 约束与背景

- 数据源：① GitLab 源库 `gitlab-data-web-1` 的 `gitlabhq_full_import_test`（socket proxy 15434 只读）；② 兼容表物化于平台库（读路径不依赖 MySQL/Mongo 存活）；③ CAT 镜像表 `bi_cat_*`；④ 平台自身 PostgreSQL（开发库 15432，容器当前 Exited(0)）。
- 接口面：20 个 Controller、约 176 端点（97 GET/60 POST/14 PUT/5 DELETE），Excel 导出端点 ≥17。
- 复用资产：`AbstractStatisticBoardGoldenMasterTest`（金标模式：易变字段掩码+Excel 单元格语义指纹）、`backend/src/test/resources/golden/`、`scripts/gitlab_bounded_sql_export.py`（有界闭包切片）、`scripts/real_chain_api_smoke.py`（登录/CSRF 范式）、`scripts/check_api_contract_drift.py`（端点枚举范式）、Testcontainers 已在 pom。
- 运行时长预算：容器+链路 ≤90s、遍历+快照 ≤60s、写用例 ≤60s，总量 ≤4 分钟；与用户"1 分钟"目标的差距如实报告（已用共享容器/一次同步摊销优化）。
- 数据规模：切片 Issue≈1200、MR≈1500、notes 每父 4 条，必含项目 9/325；平台种子 curated 表清单有序 COPY；夹具压缩后目标 ≤20MB。

## 证据与根因

- 平台无任何输出快照对比机制（既有金标仅覆盖 5 个统计看板的服务层合成夹具，2026-08-27 提交 9b6877a0）。
- `docs/progress.md` 2026-08-24 已约束："解耦版本完成内网部署并通过现场测试后，建立唯一行为基线，逐项记录所有按钮、功能和显示结果"——本工作单元即该约束的工程化落地。
- 兼容模式读源已收敛为平台物化表（2026-08-13 记录），MySQL/Mongo 实时同步链路已删除（编码页），故基线冻结物化表即可覆盖兼容数据域。
- 黄金线栈 30201/15436 为空库+GitLab 同步起步，兼容表数据不全；本地 15432 数据最全（用户已确认从中抽取）。

## 方案与步骤

1. 【进行中】创建本计划文档。
2. 夹具抽取与冻结：`docker start qaflex-dev-postgres-15432` → 编写 `scripts/extract_golden_baseline_fixtures.py`（GitLab 切片复用有界导出新增 `golden_baseline` 配置档；平台种子 curated COPY：`code_review_match_mode_records`、`review_data_match_mode_*`、`review_records` 族、`collect_form_records`、`bi_cat_*`、标签组）→ 夹具落 `backend/src/test/resources/golden-baseline/fixtures/` → 生成 `baseline-manifest.json`（行数+SHA-256）。核查 `bi_cat_*` 数据量，空则 BI 基线为空态快照并记录。
3. 运行时骨架：pom 加 `json-unit-assertj`（test）+ `golden-baseline` profile（默认套件排除该标签）；`backend/src/test/java/com/data/collection/platform/golden/GoldenBaselineSupport` 双 PG 容器（静态共享）+ Flyway + 夹具导入 + `gitlab_sync_configs` DIRECT 种子 + 登录/CSRF 客户端 + 全量同步触发与终态断言；golden 配置档禁用调度/富化/回写。
4. 端点目录与覆盖护栏：反射枚举端点 → `backend/src/test/resources/golden-baseline/endpoint-catalog.yml`（分类 READ/OPTIONS/EXPORT/WRITE/META/EXCLUDED+原因、用例参数、ignorePaths）→ `GoldenBaselineCoverageGuardTest` 100% 登记断言。
5. 快照引擎：`GoldenSnapshotSupport` 规范化+严格比对（缺失=失败）+ `-Dgolden.update=true` 显式重建 + 聚合失败报告；Excel→规范化 JSON。
6. 读/导出基线化：`GoldenBaselineChainTest` 全链路后 @TestFactory 遍历 READ/OPTIONS/EXPORT 生成快照 → 抽样审阅 → 复跑零差异。
7. 写接口用例：`GoldenWriteCaseSupport` 固定 payload → 执行 → 等待异步级联 → 快照响应+受影响表（主键有序、易变列掩码）→ 夹具还原。
8. 突变自证：破坏 1 个端点格式化 → 必须可读 diff 失败；删 1 条目录项 → 覆盖护栏失败；还原复绿。
9. 文档收尾：`docs/architecture.md` 新增章节（运行入口/新增端点流程/基线重建规则）、`docs/decisions.md` 新增决策条目、`docs/progress.md` 更新、覆盖报告、时长实测、默认套件+门禁全量验证。

## 决策记录

- 已选：JUnit5 + Spring Boot Test + Testcontainers（已有）+ JsonUnit（唯一新增测试依赖，成熟 JSON 快照对比库）+ POI（已有）；不引入 ApprovalTests（与仓库 golden/ 布局冲突）。
- 已选：快照严格模式（缺失=失败，显式 `-Dgolden.update=true` 重建），区别于既有统计金标的"缺失自动生成放行"——落实"没记录=高风险项"。
- 已选：覆盖护栏用反射枚举 Controller 端点机器强制 100% 登记（EXCLUDED 也必须显式+原因），且护栏**不加 golden-baseline 标签、进默认快速套件**（无容器秒级）——新增端点未登记时日常 `mvn test` 立即失败，而非仅在 golden 档暴露。
- 已选：POST 只读预览类端点（规则预览、动态规则预览）归 READ 类并用 body 传 JSON 请求体（ReadCase 增加可选 body 字段）；用例 query 中的键若与路径变量同名则替换路径而非进查询串；`{{configId}}/{{recordId}}/{{catalogId}}/{{groupId}}` 为运行期种子 ID 占位符。
- 已选：写接口表级还原（从夹具 DELETE+重 INSERT 受影响表），非整库回滚。
- 已选：golden 档禁用后台调度、自动同步、代码走查 HTTP 富化、延迟回写 worker（确定性+时长预算）；此类路径标注"由其他测试层覆盖"。
- 已选：GitLab 切片保持源库忠实快照（含孤儿引用），golden 运行时载入用 `SET session_replication_role = replica`（标准 PG 数据恢复技术）。依据：切片是有界窗口，FK 完全闭合需全库导出；源库本身即含孤儿（导入型测试库 FK 未强制：project_group_links→已删组 6 条、notes→reviews 20 条等），同步链路在生产读到同一数据，孤儿只存在于同步不读的表；孤儿盘点 8 类已记录（issue_user_mentions→notes 2185、issues→work_item_types 1200 已通过全量导出 work_item_types 消除、merge_request_user_mentions→notes 1028、todos→notes 660、merge_requests→merge_request_diffs 430、system_note_metadata→description_versions 61、notes→reviews 20、project_group_links→namespaces 6）。
- 已选：修复共享导出脚本的两处依赖闭包缺陷（project_group_links 引用的 group 命名空间及父级未收集、work_item_types 未在导出清单），而非在 golden 侧过滤——切片对真实组的引用完整性是脚本本身的正确性问题。
- 已否决：只测输出层（用户确认完整链路）；把 MySQL/Mongo 活体同步纳入基线（替代计划将删除该链路，冻结物化表即覆盖读路径）；二进制哈希比对 Excel（不可读 diff）；在切片里剔除孤儿行（会失真于源库快照，且逐表修补不可穷尽）。
- 已定：夹具体积 25.2MB（超 20MB 目标 26%）；legacy_mysql_imported_rows 为兼容读路径核心事实无法压缩，判定可接受并记录于 manifest。
- 已选：任何环境都确定性失败（400/500）且无数据产出的用例不进基线，从目录移除并在用例位注释根因（如 mirror-table-overview 未登记在 `PagePermissionKeyResolver` 静态权限映射）；依赖运行期配置且 golden 库恒为空态的端点标 `empty-runtime-config` EXCLUDED（如 label-groups expand，label_group_members 无迁移种子），成员生成后的行为留给写用例阶段评估——排除必须显式登记，不留静默缺失。
- 已选：quality-board 的 projectName 合法值 = `issue_scope_groups` 的启用业务键（按 sort_order 第一个为 `CC2026R4`），而非 `issue_scope_catalogs.project_name`（CrownCAD 只是目录标签，校验走 groups 业务键链路 `IssueScopeCatalogService.listEnabledBusinessKeys`）；版本组与成员均为迁移种子（V20260622_02→05→V20260727_02），golden 库与开发库一致。

## 接口契约

- 不修改任何生产 HTTP API、表结构或业务行为。
- 新增测试资产：`golden-baseline` JUnit 标签与 Maven profile；`endpoint-catalog.yml` 目录格式（端点 id/method/path/cases/category/ignorePaths/tables/exclusionReason）；`baseline-manifest.json` 格式（代码版本、夹具 SHA-256、行数、端点/快照计数）；快照文件路径约定 `<controller>/<endpoint>-<case>.json`。
- 快照文件本身构成回归契约：内容变更必须来自有意的代码行为变更并通过 `-Dgolden.update=true` 重建+人工审阅。

## 风险与假设

- 兼容表可能引用切片外实体：基线以"自洽输入→输出"为准（回归检测语义正确），抽取尽量按切片内项目过滤，残留如实记录。
- 写接口级联面不确定：用例声明等待与还原范围，无法隔离的写操作标 EXCLUDED+原因（显式而非静默）。
- 15432 容器停止 5 天：抽取前 `docker start`，非破坏性，抽完保持原状（不停容器，由用户管理）。
- 异步链路确定性：同步/事实终态轮询收敛后才快照；禁用全部后台任务。
- 同事未提交改动在工作树：本单元只新增文件+修改 pom.xml（改前 `git diff` 核对）；完成后提交动作先征求用户确认。
