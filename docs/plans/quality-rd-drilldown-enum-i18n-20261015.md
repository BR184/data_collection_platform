# 研发质量看板三张下钻表枚举中文化

## 进度与中间物

- 状态：R1 代码及单元复审通过，员工已完成三表本机 API/界面验收、两份 case 登记及一次 update 重建。黄金严格 compare 被候选缓存校验失败阻断，快照差异仍待审阅，禁止据 update 的 204/0 宣称发布门禁通过。
- 已完成文件/变更清单：
  - `backend/.../service/analytics/QualityRdAnalyticsDetailQueryService.java`：新增 `detailPage/withDisplayValues` 出口映射、`displayIssueState`、`displayMergeRequestState`、`ISSUE_DETAIL_LABELS`/`CODE_REVIEW_DETAIL_LABELS` 词表；`isOpen` 改为静态；`release-leakage-defects`、`fix-user-defects`、`quality-code-review-records` 三个视图改用出口映射。
  - R1 补正：`displayIssueState` 只识别 `open|opened|closed`（大小写无关），其余值原样透传，不再兜底为“已关闭”；`isOpen`、`leakageState` 与遗留率判据零改动。
  - 用词补正：`OPEN|OPENED` 由“已打开”改为“开放中”（依据见“决策记录”）。
  - `QualityRdAnalyticsDetailQueryServiceTest` 扩到 10 个用例：三列中文、未知议题状态透传（经理反例）、大小写、null/缺失保持 null、分页页码+size+total、严重程度排序仍按代码值、SQL 筛选下推原始值、MR 未知值 `APPROVED` 透传、MR 正式小写值。
- 测试通过状态：`mvn test -Dtest="com.data.collection.platform.service.analytics.*Test"` → **34/34 通过**（其中本测试类 10 个，既有 `QualityRdAnalyticsDashboardProviderTest` 9 个未改动全绿）。`git diff --check` 与 `scripts/check_text_whitespace.py` 干净。运行期间 18080/18181 未启停，收口后 `/actuator/health` 仍为 UP（注意：本次在共享 `backend/` 直接执行 Maven，重写了 `target/classes`，运行中 JVM 未重启）。
- 证据强度说明：严重程度沿用平台权威映射 `IssueDisplayValueSupport.displaySeverityLevel`，它按精确大写码匹配（`LEVEL1`），小写输入不会翻译；事实层写入为大写，故本轮不额外放宽大小写，也不在展示层另造一套。
- 实机验收（2026-10-15 10:4x，本机 18181/18080，admin 会话，未启停服务）：验收所用运行实例是 10:52:55 启动的 `spring-boot:run` JVM（PID 12712，`spring.devtools.restart.enabled=false`），已包含本次 10:19 源码改动与 10:26 编译产物。
  - 更正：本节初稿写“后端已在 10:28 由其他工作单元重启”不准确，10:28 只是日志文件 mtime；实测进程启动时间为 10:52:55，且 devtools 热重启被禁用，故我 10:26 的 Maven 编译不会自动重启他人服务。
- 共享服务记账（2026-10-15 12:31）：按用户指令与 AGENTS 门禁纪律，运行全平台黄金 compare 前停止开发服务（maven PID 9560、后端 JVM PID 12712、vite PID 8372），端口 18080/18181 已确认释放；日志 `backend/logs/golden-20261015-compare.log`。收口要求：用 `pwsh -File backend/run-backend.ps1`、`pwsh -File frontend/run-frontend.ps1` 重新拉起，并验证 `/actuator/health` 为 UP、18181 可访问，不得让用户面对停摆环境。

  - `release-leakage-defects`（total 3390）：议题状态=已关闭/未关闭、严重程度=一级/二级缺陷、遗留状态同步正确；界面表头 13 列全中文值。
  - `fix-user-defects`（total 25）：议题状态=未关闭、严重程度=二级缺陷。
  - `quality-code-review-records`（topic=reviewer-density，total 2942）：状态=已合并/开放中。
  - 排序语义实测：`sortField=severityLevel&asc` 返回顺序为 二级→三级→建议类（原始码 `LEVEL2<LEVEL3<SUGGESTION`），若按中文码位排序应为 三级→二级→建议类，实测证明排序仍走原始代码值。
  - 界面表头实测：该下钻表 12 列均无 `is-sortable`，即当前 UI 不提供点表头排序；出口映射对界面可见顺序零影响，排序保护只对 API/深链生效。
- 前端确认：`analytics-dashboard-detail-cell.ts` 对 `text` 列直接输出原值，无二次转换；空串渲染为 `-`，因此不注入“未设定”文案。

- 经理独立复验：隔离副本同步本轮服务与测试文件，analytics **34/34 通过、0 失败/错误/跳过**，日志 `.tmp/quality-rd-i18n-review-20261015/manager-r1-recheck.log`；仅隔离 POM 限定 analytics 测试编译，不代表完整默认套件通过。经理未重编译共享 target、未启停服务。
- 当前待办：定位候选校验失败并由对应单元修复；审阅本轮写盘 73 份与相对 HEAD 累计差异；修复后固定版本执行非 update 严格 compare。case 和生成已完成，不重复列为待办；两列不删、Excel 不扩入本单元。

## 恢复线索

- 恢复后首条命令：仓库根目录 `git status --short -- backend/src/main/java/com/data/collection/platform/service/analytics/QualityRdAnalyticsDetailQueryService.java backend/src/test/java/com/data/collection/platform/service/analytics/QualityRdAnalyticsDetailQueryServiceTest.java`；先确认共享改动，再按隔离验证方式补正，不直接重编译共用开发服务的 target。
- 关联提交基线：`3a105ddf`（工作树含其他 AI 未提交改动，见“约束与背景”）。

## 目标与边界

- 用户报告：研发质量看板 3 张下钻表仍输出英文枚举值，需映射为中文。
  - 发布缺陷遗留率明细 `release-leakage-defects`：严重程度（LEVEL1/LEVEL2）、议题状态（opened/closed）。
  - 按修复人统计缺陷明细 `fix-user-defects`：同上两列。
  - 质量看板代码走查明细 `quality-code-review-records`：合并请求状态（MERGED/OPEN）。
- 验收标准：三张表对应列在接口响应中为中文；空值保持原样；排序与图表点击筛选行为不变。
- 明确禁止：不改动其他 AI 正在进行的工作树内容（客户问题统计下钻、延期问题下钻的中文投影属于其工作单元）。

## 约束与背景

- 工作树被其他 AI 大量占用：`QualityRdAnalyticsDetailQueryService.java` 本身已有未提交改动（只读版本绑定 `DatasetReadSelection`/`VersionedDatasetSql`），与本次显示映射互不相干；改动必须落在不同代码区域，不做无关整理。
- 本地实机值域（`qaflex-dev-postgres-15432` 只读查询，2026-10-15）：
  - `issue_fact.issue_state`：`closed`(8560)、`opened`(7458)。
  - `code_review_formal_records.merge_request_state`：`merged`、`closed`、`opened`。
  - `code_review_match_mode_records.merge_request_state`：`MERGED`(30275)、`OPEN`(232)、`APPROVED`(1)。
  - 结论：同一列存在大小写混排与个别异常值，映射必须大小写无关、未知值原样返回。
- 当前展示词表：严重程度见 `IssueDisplayValueSupport`；议题状态“未关闭/已关闭”沿用 `CustomerIssueRecordWorkbookExportSupport.displayIssueState` 与各统计板筛选选项；合并请求状态采用“已合并/开放中/已关闭”，本地 GitLab 官方语料依据见“决策记录”。此前把 Opened 的“已打开”当作状态词的依据已撤回。
- 本服务无 `reopened`/`locked` 处理逻辑，`open|opened` 即未关闭，与既有 `OPEN_ISSUE_PREDICATE` 一致。

## 证据与根因

- `QualityRdAnalyticsDetailQueryService.issueRows()` 直接把 `issue_state`、`severity_level` 原样放入行 Map；`codeReviewRecord()` 直接放入 `merge_request_state`。
- `QualityRdAnalyticsDetailView.issueColumns()` 把这三列声明为 `text` 直接渲染，前端无转换层。
- 关键约束：`release-leakage-defects` 的 `leakageState` 在 enricher 中读取 `row.get("issueState")` 判定未关闭。若在行映射阶段就把 `issueState` 改成中文，`leakageState` 会恒为“已关闭”。

## 方案与步骤

1. 显示映射统一放在“排序分页之后”的出口（`detailPage`），使排序、分页、`leakageState` 判定全部继续基于原始代码值，只有响应里的展示值变中文。
2. 议题状态：目标展示为 `open|opened` → 未关闭、`closed` → 已关闭，大小写无关；未知码不改业务含义（非空文本去首尾空格），null/全空白保持原值。实施版“其余非空 → 已关闭”已由经理反例否决并修正，不能把遗留率计算的兜底判据复用为已知展示状态。
3. 严重程度：委托 `IssueDisplayValueSupport.displaySeverityLevelOrBlank`。
4. 合并请求状态：`MERGED`→已合并、`OPEN|OPENED`→开放中、`CLOSED`→已关闭，大小写无关；未知值原样返回（如 `APPROVED`）。
5. 空值/空白值保持原样，不注入“未设定”类文案。
6. 仅覆盖用户点名的 3 个视图，`development-leakage-defects`、`integration-test-results` 不改动（其未展示这些列）。
7. 新增 `QualityRdAnalyticsDetailQueryServiceTest`：锁定三列中文、`leakageState` 未被破坏、按严重程度排序仍按代码值、图表点击筛选仍把原始值下推到 SQL。

## 决策记录

- 选择“出口映射 + 排序分页用原值”：避免中文列排序退化（`一级缺陷`/`三级缺陷`/`二级缺陷` 按码位排序会错序），同时修掉 `leakageState` 依赖原值的隐式耦合。
- 否决“在 `issueRows` 行映射里直接改中文”：会静默破坏 `leakageState`，并让严重程度列排序错序。
- 审查处理（已被下一条取代）：曾裁定保留“已打开”，理由是不能改成“未合并”，因为已关闭但未合并是另一种状态。
- 用词复审裁定（2026-10-15）：合并请求 `OPEN|OPENED` 使用 **“开放中”**，已关闭但未合并不属于该状态。证据链：
  - 员工报告转述用户在自有 GitLab 实例实测状态徽章为“开放中／已关闭”；经理本轮未操作该现场页面，不将转述写成自身实测。
  - 本地 GitLab 容器 `gitlab-data-web-1` 的官方中文语料逐字对应：`locale/zh_CN/gitlab.po:35071` `Open→开放中`、`:31641` `Merged→已合并`、`:11196` `Closed→已关闭`；同文件 `:35122` 另有 `Opened→已打开`，它用于时间线/筛选短语而非状态徽章，先前正是引用了这句，属于依据选取错误，现撤回。
  - 老平台源码逐字语义（`spidergitdata-dev/src/main/java/com/huayun/service/impl/MergeRequestDAOImpl.java:1611`）：“CLOSED 时为关闭状态，OPEN 时为当前正开启但未合并状态，MERGED 为已合并状态”，与“开放中”一致且不与“已关闭”混淆，满足原裁定的实质顾虑。
  - 经理本轮只读 `docker exec` 独立核实 `/opt/gitlab/embedded/service/gitlab-rails/locale/zh_CN/gitlab.po` 上述三个 msgid/msgstr，以及老平台 `parseStatus` 注释；官方词表支持当前用词。未独立核验 Opened 在所有页面的具体调用语境，不以词条本身证明徽章实际渲染。
  - 本仓库内“已打开”字样全部指“已打开的详情面板”（7 处注释），从未作为状态词，存在读感冲突。
  - 本地库实查：三张 MR 表的状态值零条中文，中文值只存在于内网老平台库，故徽章文本只能以用户实测与官方语料为据，未做内网实测。
- 议题状态列沿用平台既有唯一词表“未关闭／已关闭”（10 张下钻表一致），不随 MR 用词变动；全平台仅本表有“合并请求状态”列，无同类先例可对齐。

## 经理审查（2026-10-15）

- 范围：只审查及维护本计划/进度，未改业务实现、共享测试、来源数据或运行配置，未启停 18080/18181、未运行黄金或更新快照。首轮审查源码 SHA-256 为 `7C56393C80707888EE4BF1675B430CF1F3C9EDBB7062EFF0F575BBE3A7465553`；R1 复审源码为 `25F5159E584A862FDA9FA470EE1D9F75EB6AA7710BCEF2E9BA41F76FCB63E508`，测试为 `8F5A3B451571BBBD7A221A0A063F7EF666539BA493A966F3CA6F2E0B0B549662`，隔离副本与本轮共享源文件一致。
- 认可：三个入口在排序/分页后的响应出口复制 Map 并转换显示值，原始行不被修改；release 的 leakageState 仍在转换前计算，severity SQL 参数仍为 LEVEL1。两个共享版本读取改动未被本轮覆盖。严重程度复用现有权威词表，MR 兼容 OPEN/OPENED/大小写，未知 APPROVED 透传。
- 独立验证：隔离后端副本 `.tmp/quality-rd-i18n-review-20261015/backend`，analytics 原 29 项通过；另外添加两项仅供本轮审查的反例：真实第 2 页/每页 1 条保留原始级别顺序与 total 的用例通过，未知议题状态透传用例失败。合计 **31 项、30 通过、1 失败、0 error/skip**，日志 `manager-analytics-targeted-tests.log`。没有把复验说成全绿。
- 初次普通 Maven 调用在 testCompile 被其他领域 `GitlabSourceTimestampNormalizerTest` 对 `CustomerIssueActivityTimeRules` 的五个缺失方法引用阻断，尚未运行本域测试；随后只在隔离副本 POM 限定 analytics 测试编译，未修改或排除共享仓库测试。初次日志 `manager-analytics-tests.log` 保留，不将其归因于本显示修复。
- R1 复审：将员工当前服务与正式 10 项测试同步到同一隔离副本后执行 `mvn -o test -Dtest=com.data.collection.platform.service.analytics.*Test`，**34/34 通过**。未知议题状态、大小写、null、正式小写 MR、分页/total、原始排序与 SQL 代码值用例通过；`isOpen` 判据和遗留状态富集未因 R1 改动。代码及单元层批准，API/界面与黄金发布门禁仍待完成。
- 隔离纪律：员工本次共享 Maven 重写 target 的披露保留；health=UP 仅说明健康端点可用，不能证明共享 classpath、热重载或其他 AI 验证未受影响。后续复验使用独立源码及输出目录，不通过重启共享服务补验。

### R1：未知议题状态不能伪装成已关闭（已补正，复审通过）

- 首轮确定反例：release 明细输入 `issue_state=UNRECOGNIZED`，旧展示实现返回“已关闭”，与报告“未知值透传”不符；当前修正后保留 `UNRECOGNIZED`，正式反例测试已通过。MR 默认分支没有同类已关闭兜底。
- 补正：展示映射只识别 open/opened/closed，其余值透传；不要改原 `isOpen`、leakageState 或指标判断来修展示。为未知、null/空白、大小写及 MR opened 正式输入增加相称行为测试；保留已有排序/筛选断言，并把分页页码/size/total 补进正式回归。审查副本中的两个反例可供参考，不直接复制临时验证设施进生产目录。
- 验收：新旧已知值显示正确、未知不被发明为关闭、分页/总数/遗留状态/SQL 条件均不变；有可用会话后复验三张表实际 API 与界面，不为取得会话改认证模式或重启共享服务。

### 四项处理意见

1. **黄金快照**：可以集中到统一门禁窗口，但必须先让各相关工作单元修正并确认有意差异，不能将未审阅中间产物一起更新。目录已登记该 HTTP 路由，不代表覆盖了全部 viewKey：补 `release-leakage-defects`、`fix-user-defects` 两个稳定 case；在现有冻结输入中选择能命中枚举的场景，若只能得到空表先报告，不私改夹具。获明确更新授权后统一生成新增/变更快照并审阅，再严格 compare；本轮不执行。
2. **OPEN 用词**：本轮补充语料并复核后采用“开放中”，取代首轮“已打开”；同时识别 OPEN/OPENED 与大小写。APPROVED 未有业务释义，保留其代码含义，不发明“已批准”。
3. **列冗余**：本轮保留“遗留状态”和“议题状态”，删列属于独立产品/接口变化，不混入显示修复。
4. **Excel**：本轮不扩大代码走查工作簿的状态取值契约，列为独立后续项。现有 Excel 也已被黄金目录覆盖；若确认中文化，需单独明确范围、复用同一词表、保持表头/列序与原始模型，并审阅对应导出差异，不能称本次已经统一导出。

## 接口契约

- 无新增 API、表结构或迁移；仅 `GET /api/analytics-dashboards/{dashboardKey}/details/{viewKey}` 三列的取值由英文码改为中文标签。

## 风险与假设

- 产出变化会触及黄金基线纪律：`quality-code-review-records` 已有冻结快照，需按门禁以更新模式重建并经用户审阅；黄金门禁约 7 分钟，未经用户明确要求不得运行。
- 其他 AI 同期修改同一文件的其他区域，提交前必须只暂存本单元改动。
- 代码走查 Excel 导出（`QualityBoardWorkbookExportService`）仍写原始英文值，本次不改动。
- 存量口径待另案（本单元不修）：`issue_state` 为 null 时，遗留状态富集器用 `String.valueOf(row.getOrDefault("issueState", ""))` 得到字面量 `"null"`，因而判为“已关闭”。这属于遗留率判据层，本单元不改 `isOpen`/`leakageState`/指标判断；员工本地查询未见 null 行，不据此推断生产也不存在该问题。
- 黄金目录只登记了 `integration-test-results`、`quality-code-review-records` 两个质量看板明细 case，另两张表长期无产出锁定，补登记与快照生成必须同批完成。

## 全平台黄金门禁首轮 compare（2026-10-15 12:31—12:40）

- 命令：`backend` 目录 `mvn test -Pgolden-baseline -Dtest=GoldenBaselineChainTest`（**未带** `-Dgolden.update=true`，只比不写）；日志 `backend/logs/golden-20261015-compare.log`。
- 结果：**202 项，7 失败，0 错误，0 跳过**；链路 538.3 s，总耗时 9:11。运行前已停 18080/18181，运行后已用 `run-backend.ps1`/`run-frontend.ps1` 重新拉起，40 s 内验证 `/actuator/health`=`{"status":"UP"}`、前端 200。
- 差异归属（逐项，未改动任何快照）：
  1. `analytics-dashboards/…quality-rd-quality-code-review-records.json` — **本单元**：`mergeRequestState` `MERGED→已合并`、`OPEN→开放中`，即已批准的中文化。
  2. `statistic-boards/…system-test-defect-summary.json` — 统计表筛选单元：状态筛选选项 `label/value` 由反置（`label:"open"`,`value:"未关闭"`）改为正确（`label:"未关闭"`,`value:"open"`）。
  3. `statistic-boards/…rule-explanation__system-test-defect-summary.json` — 同单元：`data.version` `2026-09-10-v14 → 2026-10-14-v15`。
  4. `statistic-boards/…customer-issue-defect-cause.json` — 同单元：`filters[9].options` 由 0 项变 19 项（新增人员筛选候选）。
  5. `statistic-boards/…customer-issue-customer-statistics.json` — 客户问题统计指标升级单元：`columnGroups[0].columns` 由 6 列变 9 列（新增整体修复率等）。
  6. `statistic-boards/…rule-explanation__customer-issue-customer-statistics.json` — 同单元：`flowSteps` 由 6 步变 7 步（新增“客户需求范围”）。
  7. `bi/customer-issues` — BI 客户问题页：`dailyTrend[*]` 字段由 `createdCount/fixedCount` 变为 `cumulativeCreatedCount/…` 系列。
- 结论：本单元只贡献 1 处有意差异；其余 6 处分属两个已在 `docs/progress.md` 记录为审查通过/收口的其他工作单元，其快照尚未重建，因此整树门禁必然红。按门禁纪律停止，不用更新模式掩盖，也不代替他人裁定其产出。
- 下一步（待用户授权）：由差异归属方或用户确认 6 处为有意变更 → 一次性 `-Dgolden.update=true` 重建 → 逐文件审阅 diff → 去掉更新模式严格 compare 全绿 → 才可制作更新包。

## 更新模式重建尝试（2026-10-15 12:55—13:02，失败，未写快照）

- 前置：已在 `endpoint-catalog.yml` 补登 `quality-rd-release-leakage-defects`、`quality-rd-fix-user-defects` 两个 case（该文件同时含其他单元约 55 行未提交新增，属并行编辑热点）。
- 命令：`mvn test -Pgolden-baseline -Dtest=GoldenBaselineChainTest -Dgolden.update=true`；日志 `backend/logs/golden-20261015-update.log`。
- 结果：`Tests run: 1, Failures: 1, Errors: 1`，188.9 s 在**引导阶段**失败，未进入快照遍历。逐字根因：
  `java.lang.IllegalStateException: 候选预热缓存内容已变化，必须重建：CacheArtifact[kind=STATISTIC, id=7, consumerKey=customer-issue-defect-cause, checksum=ff53debb5fe0652462a6c273eb29551c4b836aa9204960b02a8ba510912eef82]`
  → `FACT_REFRESH 运行终态异常：FAILED`，抛出点 `GoldenBaselineSupport.awaitRunTerminal:349` ← `triggerFullSyncAndAwait:320` ← `GoldenBaselineChainTest.bootstrapChain:121`。
- 产物核验：运行前后对 `golden-baseline` 下 265 个资源做 SHA-1 逐一比对，**新增 0、变化 0、消失 0**，确认更新模式未写出任何快照，也未回退他人已有改动。
- 归属判断（待其单元确认，不作为定论）：`CacheArtifact`/候选预热属在途“读发布模型”（迁移 `V20261013_01__candidate_projection_artifacts.sql`），`consumerKey=customer-issue-defect-cause` 是统计表看板；本单元只改研发质量明细展示出口与测试，未触碰候选缓存、事实刷新或该看板。
- 关键待查事实：同一工作树 12:31 的 compare 运行**通过了引导**并跑完 202 项，本次 update 却卡在引导 ⇒ 该判定疑似依赖缓存态或执行顺序，不是稳定红。是否偶发尚未复测，不做根因推测。
- 服务处置：本次运行前停 18080/18181，失败后已用标准脚本重新拉起，5 s 内验证 `/actuator/health`=`{"status":"UP"}`、前端 200（新 PID 29464/28384）。失败运行未遗留 Testcontainers 容器（`qaflex-dev-postgres-15432`、`qaflex-test-postgres-15433` 为常驻实例，未触碰）。
- 打包：按用户指示未执行；且门禁引导未通过，本就不具备出包条件。

## 更新模式重试与重建（2026-10-15 13:11—13:21，成功）

- 用户裁定先重试一次判定偶发/必然。第二次运行 `-Dgolden.update=true` **通过引导并完成**：`Tests run: 204, Failures: 0, Errors: 0, Skipped: 0`，链路 541.2 s，总耗时 9:15，BUILD SUCCESS；日志 `backend/logs/golden-20261015-update2.log`。
- 判定（已由经理更正）：本节初稿写“第一次卡引导、第二次通过 ⇒ 属偶发、不是稳定红”，该推断不成立。可确证的只有事实：5 次运行中 3 次在 `bootstrapChain:121` 同一校验点中止，报同一工件（`kind=STATISTIC, id=7, consumerKey=customer-issue-defect-cause`）。经理随后用隔离 JDBC 场景证明存在**可稳定构造的触发机制**（同组人员顺序翻转即失败、后续新清单不能消除旧清单失效），因此“偶发”一词作废：结果取决于任务交错与候选顺序，而不是随机崩溃。该机制是否就是这三次现场失败的原因，仍待归属单元采证（实际 checksum、变化字段、写入任务）。
- 写盘核验（与运行前 265 资源 SHA-1 指纹比对）：**新增 2 个**（本轮登记的 `quality-rd-release-leakage-defects`、`quality-rd-fix-user-defects` 快照）、**重写 71 个**、**删除 0 个**。重写面覆盖全目录，符合“update 每轮重写全部易变字段、无法只更新单端点”的既有结论。
- 本单元自证（逐字段读快照内容）：新快照 `release-leakage-defects` total=55/20 行，议题状态集合 {已关闭,未关闭}、严重程度集合 {一级缺陷,二级缺陷}、遗留状态同步存在；`fix-user-defects` total=7/7 行，议题状态 {未关闭}、严重程度 {二级缺陷}、无遗留状态列（符合视图定义）；`quality-code-review-records` 状态列为 已合并/开放中。两份新快照数据非空、字段符合列契约。
- 员工当时提交的审阅面：报告本轮写盘 73 个文件，累计快照差异另行统计；重点包括 `customer-issue-customer-statistics`（+31304/−4006）、`customer-issue-defect-cause`（+296/−4）、规则说明、BI 与数据库目录等。四个看板文本 diff 中 `projectName/topic` 出现大量加删行；经理随后按 JSON 结构比较确认这些点位参数没有新增或改值，不能把文本重排当成参数新增。六份重点快照的语义抽核见后文，不以 diff 行数替代内容审阅。
- 服务处置：第二次停服（PID 29464/28384）已记账；重建后继续用同一停服窗口跑严格 compare，结束后统一拉起并验证。

## 严格复比未能完成：引导校验三次中止（2026-10-15 13:25—13:41）

- 重建后的两次严格 compare（`golden-20261015-recompare.log`、`golden-20261015-recompare2.log`）与 12:55 的首次 update 一样，卡在 `bootstrapChain:121`，异常与工件完全相同：`CacheArtifact[kind=STATISTIC, id=7, consumerKey=customer-issue-defect-cause, checksum=ff53debb5fe0652462a6c273eb29551c4b836aa9204960b02a8ba510912eef82]`。
- 五次运行对照（同一工作树、同一命令族）：
  | 时间 | 模式 | 引导 | 结果 | 候选相关日志行 |
  |---|---|---|---|---|
  | 12:31 | compare | 通过 | 202 项 / 7 差异 | 0 |
  | 12:55 | update | 失败 | 引导中止 | 3 |
  | 13:11 | update | 通过 | **204 项 0 失败**，快照写盘 | 0 |
  | 13:25 | compare | 失败 | 引导中止 | 3 |
  | 13:37 | compare | 失败 | 引导中止 | 3 |
- 观察（经理更正推论）：两次通过没有该错误日志，三次失败抛同一预期校验和；该方法成功时本来就不记日志，不能由日志缺席推断检查被跳过或已经证明竞态。记录的是清单中的预期 checksum，不是失败时缓存实际 checksum，原日志尚不能定位变化字段或写入者。
- 第四次尝试（用户为尽快进入内网测试而授权，14:33—14:36，`golden-20261015-recompare3.log`）：**仍卡在同一校验点**，195.7 s 中止，同一工件同一预期校验和。累计 6 次运行 4 次失败（12:55、13:25、13:37、14:36），失败全部在 190—196 s 区间。按承诺停止重试，未改任何快照；服务已第四次恢复并验证 UP/200（PID 28984/3356）。
- 可用事实（供出包风险判断）：13:11 那次成功的 update 运行**走完了全链路并产出全部 204 项响应**（update 只写不比），因此"当前代码能完成同步→事实→统计并出全部产出"已有实证；未通过的断言只有"产出与冻结快照一致"，且它当前**因引导校验间歇中止而根本执行不到**。
- 结论与影响：本轮快照集是 13:11 那次 **204/0 成功的 update 运行**产物，内容对应当前代码；但在该引导校验的触发机制完成归因并修复前，**无法完成"去掉更新模式严格 compare 全绿"这一步，门禁未收口，按标准不得制作更新包**。是否知情出包由用户裁定；经理已明确禁止用覆盖旧证明、跳过校验或反复重试收口。

- 服务处置：本单元累计三次停服（12:31、12:53、13:22 前后），最终已用标准脚本恢复并验证：`/actuator/health`=`{"status":"UP"}`（PID 7872）、前端 18181=200（PID 29324）。失败运行未遗留 Testcontainers 容器。

## 黄金引导失败经理调查（2026-10-15，机制核验完成，现场写入归因待补）

- 目标与边界：核实异常机制、最小触发条件及修复交接，不代替读发布单元修改实现；不重跑黄金、不重建/回退快照、不启停共享服务，不在共享 target 编译。
- 已确认代码链：`FactTargetPublicationService.publishPreparedInternal:411` 调用 `CandidateProjectionArtifactService.verifyForPublication`；它对登记清单逐项重新读取 PREPARED 缓存，哈希不等才抛异常。`GoldenBaselineSupport.awaitRunTerminal` 是把生产 FACT_REFRESH 的 FAILED 转成门禁失败，不是原始出错点。
- 风险链：`complete` 收集同候选/来源版本的所有缓存，未限定本任务实际生成的产物；统计 `save` 的同唯一键 UPSERT 允许重写已登记行。人员候选又保留 SQL 首次出现顺序，而该看板加载事实没有 ORDER BY；候选数组顺序进入 definition_payload 哈希。此链可构造触发条件，但尚未证明就是三次现场失败的具体变化字段。
- 独立验证：在 `.tmp/golden-candidate-check-review-20261015/classes` 编译当前校验器与人员候选类，借用隔离副本依赖但不写共享 target；JDBC 替身的 5 个确定性场景全部符合预期：原内容/原清单通过、仅同组人员次序倒置失败、旧 A 清单与新 B 清单并存且当前为 B 时失败、颠倒清单遍历仍失败、全部清单与 B 一致通过。结果在 `result.log`；编译成功，首次启动因 PowerShell 拆分未引号保护的 JVM 参数失败，补引号后仅重跑启动成功。它证明触发机制，不冒充真实 worker/PG 黄金复现，现场 actual checksum、变化字段、scope/task 写入者仍待归属单元采证。
- 源码指纹：校验器 SHA-256 `184C907424E9E2AED2282BF65ED5E6A58607579ADDA6BCBD06DFAE768B4ACE34`；人员候选类 `6B910F69EA6C0481D1FA1FCAF1F2BDC4EB11E5C675251EB2D9658FC1F04C4C01`。校验器散列的是 definition/row/applied-filter 三块业务内容，不含 generatedAt/queryDuration 等 meta，不能先归因为普通时间戳波动。
- 六份重点快照语义抽核（相对 HEAD，非本轮写盘前指纹；结果 `snapshot-review.json`）：四个 analytics 看板只新增 `readView`，其它完整 JSON 字段/数组值一致，projectName/topic 仅文本重排；客户统计 80 行保持、50→71 列，按 rowKey/columnKey 对齐的原 4000 格 numericValue/displayValue 均未改，新增 1680 格，原格 detailParams 均变化（示例变化键为 sourceVersion，尚未逐个批准全部参数）；客户原因 24 列/26 行保持，人员候选由 0→19/0→74，另新增 readView 和两个元信息标量变化。大 diff 不等于大规模指标变化，也不等于已批准全部快照。当前清点为 130 份 tracked 修改和 8 份 untracked 新增，不与员工此前 129 的统计混用。
- 交接方案（未实施）：读发布模型单元主责，统计筛选单元协助。先补 expected/actual checksum、发布/来源版本、证明 scope/generation 与写入 task、三块 payload 的变化路径诊断；固定同一业务输入的人员候选顺序，保持已批准排序语义；同一候选缓存键明确唯一写入归属，任务只证明自身实际产物，证明后不允许另一任务异义覆盖。不能在发布前临时重算并覆盖旧证明、跳过 checksum、加 sleep/重试或放宽黄金掩码来变绿。
- 修复验收：可控输入顺序与可控任务交错/顺序执行均覆盖；重复相同写入稳定，真正内容变化被拒绝，旧公开指针与内容保留。先在隔离环境完成最小回归，再固定实现/目录/夹具/快照版本，审阅有意输出变化后执行一次非 update 严格 compare；仍有差异时解释修复，不反复 update/compare 碰运气。未增加接口/迁移或修改生产实现，未代替员工提交推送。
- 恢复首条命令：读取本节与 `backend/logs/golden-20261015-{compare,update,update2,recompare,recompare2}.log` 的异常/汇总；当前源码与运行工件未建立逐次完整指纹，不把“同一工作树”写成“同一代码版本”。

## 本单元三份快照审阅材料（2026-10-15，供经理逐项审阅，不含读发布模型单元产出）

### 固定验收版本指纹

代码根：仓库 `main`，HEAD `3a105ddf`；工作树非干净（含在途读发布模型等他人未提交改动），本单元改动未单独提交。

| 工件（相对仓库根） | SHA-256 |
| --- | --- |
| `backend/src/main/java/com/data/collection/platform/service/analytics/QualityRdAnalyticsDetailQueryService.java` | `25f5159e584a862fda9fa470ee1d9f75eb6aa7710bcef2e9ba41f76fcb63e508` |
| `backend/src/test/java/com/data/collection/platform/service/analytics/QualityRdAnalyticsDetailQueryServiceTest.java` | `8f5a3b451571bbbd7a221a0a063f7ef666539ba493a966f3ca6f2e0b0b549662` |
| `backend/src/test/resources/golden-baseline/endpoint-catalog.yml` | `cdf1f5e639415fceecd52d7a3f6b4ac78d5e79caf0775cc43be6cd5e6ec145e6` |
| `…/snapshots/analytics-dashboards/get___{dashboardKey}_details_{viewKey}__quality-rd-quality-code-review-records.json` | `f0b1f28077091d54bd26d0301e4b68f52f3d112a8ad1f33318a7989ca5e47b32` |
| `…get___{dashboardKey}_details_{viewKey}__quality-rd-release-leakage-defects.json` | `353d9e76159d56915709362763aa76d96c181e16b61421590d842856a7cc4739` |
| `…get___{dashboardKey}_details_{viewKey}__quality-rd-fix-user-defects.json` | `74515c17061f6c48009d032d0045b3e2a0def8e41913c2bb8b04f8c741a54958` |

指纹按工作树字节计算（快照文件为 CRLF；git 归一化后哈希不同，复核须用同一方式）。

- 生成日志：`backend/logs/golden-20261015-update2.log` —— `Tests run: 204, Failures: 0, Errors: 0, Skipped: 0`，用例耗时 541.2 s，总耗时 9:15，13:11—13:21，`-Dgolden.update=true`。
- 其后两次严格 compare（`golden-20261015-recompare.log`、`-recompare2.log`）在引导阶段中止，**未改动任何快照**；本单元三份快照自 13:21 起未被再写。
- 映射实现位置：词表 `QualityRdAnalyticsDetailQueryService.java:46`，出口映射 `:537`，议题状态 `:562`，合并请求状态 `:571`；接入点 `:262`（发布遗留率）、`:321`（按修复人）、`:354`（代码走查）。

### 快照 1：`quality-code-review-records`（有意变更，需批准）

- case：`dashboardKey=quality-rd&viewKey=quality-code-review-records&topic=reviewer-density`（登记于本轮之前，本单元未改其参数）。
- 前后逐值对照（`git show HEAD:` 与工作树版结构化比对，非行数目测）：`total` 相同（2942）、`columns` 定义逐字段相同、**剔除 `mergeRequestState` 后首页 20 行完全相等**；状态列 20 行全部变化且仅这一列变化：`MERGED×19 → 已合并×19`、`OPEN×1 → 开放中×1`，逐行一一对应，无新增/丢失/换序。
- 首行样例：`mergeRequestIid=30015`，`targetBranch=release_2026R3`，状态“已合并”。
- 未知值保留**不在本快照内**：首页 20 行只含 `MERGED/OPEN` 两种取值，`APPROVED` 等未知值透传、大小写兼容、正式小写值由单测覆盖（`QualityRdAnalyticsDetailQueryServiceTest` 的 `codeReviewDrilldownTranslatesMergeRequestStateIgnoringCase`、`formalLowercaseMergeRequestStatesAreTranslated`）。审阅时请知悉这是快照覆盖面限制，不是未实现。

### 快照 2：`release-leakage-defects`（本轮新登记）

- 新增目录条目：`- id: quality-rd-release-leakage-defects` / `query: dashboardKey=quality-rd&viewKey=release-leakage-defects`。
- `total=55`，首页 `records=20`（page 1 / size 20），数据非空。
- 中文值域：议题状态 {已关闭 19, 未关闭 1}；严重程度 {二级缺陷 14, 一级缺陷 6}；遗留状态 {已关闭 19, 未关闭 1}。
- **遗留状态口径核对**：逐行判定“议题状态=未关闭 ⇔ 遗留状态=未关闭”，20 行不一致数 = **0**，即显示层中文化没有改变遗留状态判定；判据仍为原始码 `open|opened`（`isOpen` 未被本单元改动）。
- 首行样例：`issueIid=28735`，议题状态“已关闭”，严重程度“二级缺陷”，`priorityLevel=P1`，`bugStatus=历史遗留 & 已修复/完成 & 已测试通过`。

### 快照 3：`fix-user-defects`（本轮新登记）

- 新增目录条目：`- id: quality-rd-fix-user-defects` / `query: dashboardKey=quality-rd&viewKey=fix-user-defects`（不带筛选参数，覆盖该视图全量口径）。
- `total=7`，`records=7`（全部行入快照），数据非空。
- 中文值域：议题状态 {未关闭 7}；严重程度 {二级缺陷 7}；无“遗留状态”列（符合该视图列定义，`issueColumns(false)`）。
- 首行样例：`issueIid=30708`，议题状态“未关闭”，严重程度“二级缺陷”，`priorityLevel=P2`，`bugStatus=已修复/完成 & 未更新 & 申请延期`。
- 筛选下推已由单测锁定：图表点位传入 `severityLevel=LEVEL1` 时，SQL 仍按原始码 `btrim(severity_level) = ?` 过滤，不受显示中文化影响。

### 本单元审阅边界（不在三份快照内，请勿当作遗漏批准）

- 快照只锁首页 20 行（`fix-user-defects` 为全部 7 行）；排序、分页、未知值、null/空白处理由 10 项单测覆盖，另有本机实机 API/界面验收记录在前文。
- 未改动面：`development-leakage-defects`、`integration-test-results` 两视图（其列定义不含这三列）；代码走查 Excel 导出（`QualityBoardWorkbookExportService.java:140` 仍写原始英文值，另案）；`issue_state` 为 null 时遗留状态判“已关闭”的存量口径（判据层，另案）。
- 门禁主线仍由读发布模型单元推进。本单元**不预设修复后需要再次 update**：保留现有快照，待其修复后固定版本、完成本清单审阅，再执行一次非 update 严格 compare；只有确认存在新的有意产出变化时才另行申请重建。打包继续挂起。
