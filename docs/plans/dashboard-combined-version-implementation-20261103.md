# BI与质量看板合并版本实施方案

## 进度与中间物

- 阶段：2026-11-04审批回执`767cf44c`批准候选身份并准入首次非update黄金严格比较。已修正黄金清单、登记两个内部明细用例并完成一次`-Dgolden.update=false`全量比较：`205`项中`8`失败`2`错误，除已确认的quality-rd动作、BI四份`ruleVersion`和两个缺失快照外，另发现quality-rd规则文案与两个既有明细新增`isFixed`字段，待差异审批。本轮未生成/更新快照、未改夹具/掩码/EXCLUDED、未合main、未打包。技术验收基线仍为`aa62d219`，R01-a/b/c与R02关闭。
- 审批记录：回执`901614ea`确认经理独立复跑58项（含5项真实PostgreSQL、0跳过）全部通过；回执`767cf44c`纠正“无2027夹具即BI快照不变”的推断，指出四份BI快照的`$.data.ruleVersion`参与严格比较。吸收范围仅审批回执与执行单，不整体合入含表单审核记录的审核分支。
- 工作区：`D:/projects/dcp-dashboard-combined-20261103`，分支`看板合并版本范围-20261103`（从稳定main `f74fdef7`创建）。方案原定分支名`codex/dashboard-combined-version-20261103`，按AGENTS中文功能名称规则改名，基线提交与范围未变。
- 已完成：新增`dashboard-version-scopes.json`、`DashboardVersionScopeConfig/Loader/Scope/Service`及测试；质量看板候选/默认/请求、评审密度、系统测试阶段、集成测试、明细与导出接入；BI平台产品版本/评审/编码/系统测试适配器与来源指纹接入；规则版本升级；前后端定向测试新增与更新；BI与平台权威文档更新。
- 测试状态：首轮后端默认套件`1980/0/0/1 BUILD SUCCESS`；上一轮R01/R02整改后受影响定向`53/0/0/0`（含5项真实PostgreSQL）；本轮R01残留整改后定向`58/0/0/0`（含5项真实PostgreSQL、权限与会话边界），前端`typecheck`通过、路由与明细组件定向`33/0/0/0`。发布准备阶段首次黄金严格比较`205`项、`8`失败`2`错误、`0`跳过（第7节）；未重复默认套件，未更新快照、未打包、未部署。
- 页面与导出：隔离容器+夹具下完成质量研发看板、质量其他看板、独立系统测试R1/R2、BI系统测试四处页面核验；质量走查Excel导出2条成员记录、BI系统测试Excel导出8行并含组合范围与`scope:`指纹。R01/R02整改后复核：质量看板与BI系统测试显示`CC2027R1&R2`（BI `productVersionId=12`），CAT设置列原R1/R2且无组合项，改名普通版本按稳定key查询成功并返回改后显示名。R01残留整改后桌面点验：需求评审明细3条（5/25=0.20）、设计评审明细1条（2/8=0.25）、新发缺陷明细4条其中2条已修复（50%）、其他看板功能缺陷数量明细“统计范围”为`CC2027R1&R2`；其他看板Excel文件名与工作表均为显示名，无内部key。权限拒绝由定向测试覆盖。隔离资源已清理。
- 只读取证：本地15432与11-02数据副本无2027目录/系统测试事实，评审与走查仅有`CC2027R1`；目标内网不可达。因此生产目录、`2027R1&R2`评审别名和真实组合来源未核实，按方案登记但不写成现场事实。本地项目9目录`catalogs=1 groups=12 members=51`，只读指纹`aa551d49a5321891dc47dce404234f05`，本轮未写目录。
- 恢复：先执行`git status --short --branch`、`git worktree list`，读取平台/BI进度与本文；在`backend/`用绝对路径Maven运行定向或默认套件。
- 环境：未使用生产配置、真实GitLab、CAT或客户问题数据；临时日志与证据只在任务`.tmp/`及隔离临时库，完成后清理。

## 发布门禁资料（技术基线 aa62d219）

### 1. 候选冻结与基线

| 项目 | 结论 |
| --- | --- |
| 技术验收基线 | `aa62d21964761fd861f32a8f9b98f1e3136469c1`，经理独立复跑58/58通过 |
| 实现范围 | `f74fdef7..aa62d219`，66个文件；无Flyway迁移、无Controller路径新增或删除、无新表/新权限码 |
| main基线 | 本地、origin、gitlab25的main均为`f74fdef7`，开工后未漂移 |
| 资料提交后HEAD | 文档与黄金用例目录差异；发布候选冻结前须满足`git diff aa62d219..HEAD -- backend/src/main frontend/src`为空（测试资源不进入部署制品），并以审批后的最终HEAD作为打包SHA |
| 本轮边界 | 运行一次非update严格比较；未更新快照、未改夹具/掩码/EXCLUDED、未合main、未打包、未部署；不吸收审核分支中的表单记录 |

### 2. 发布影响清单

| 类别 | 有意变化 | 影响与处理 |
| --- | --- | --- |
| 版本身份 | 看板候选`value`为稳定key（组合为`cc-2027-r1-r2`），`label`为显示名；解析只接受稳定key/原目录ID | 已发布的显示名URL不再解析为组合；本分组尚未生产发布，无兼容对象。普通版本业务键不受影响 |
| 新明细场景 | `quality-review-records`：按`reviewType`展示成员项目名与组合评审来源，按记录ID去重，正式与兼容读源共用；`new-issue-defects`：与概览共用新发有效缺陷条件，标注“修复状态” | 仅质量看板内部路由；不改独立系统测试与客户问题页面规则 |
| 权限 | 两个新明细除看板查看权限外，分别要求`review.data.view`、`system_test.summary.view` | 无新增权限码、无目录/角色授予迁移；未获授权的用户应得到403，属预期 |
| 规则版本 | `bi-review-v4`、`bi-coding-v6`、`bi-system-test-v4`、`quality-rd-rules@3`、`legacy-personal-quality-v4` | 旧缓存按版本失效并重建；不改变指标公式 |
| 来源指纹 | BI来源版本含成员事实版本并集与分组配置指纹；质量看板来源版本含范围指纹与读模式 | 首次部署形成新指纹，旧下载授权/缓存失效；黄金比对对BI端点忽略`sourceVersion` |
| 导出 | 无新增导出端点；其他质量看板Excel文件名/工作表使用业务显示名；BI导出范围与形状不变 | 明细行集合与页面一致；评审/新发明细本身不提供导出，原评审页导出仍按原权限使用 |
| 前端 | 新增两个内部明细路由；明细路由允许`reviewType`；布局、筛选按钮与交互不变 | 不改权限要求，不扩数据访问范围 |
| 数据库与配置 | 无迁移、无数据回填；仅随包发布`dashboard-version-scopes.json` | 升级不需要执行SQL；回滚不需要恢复数据库结构 |

### 3. 端点登记核对

- 无新增HTTP端点：两个新明细复用已登记的`GET /api/analytics-dashboards/{dashboardKey}/details/{viewKey}`，默认套件端点覆盖护栏不需要新增路径条目。
- 黄金目录现有质量看板明细用例为：`integration-test-results`、`quality-code-review-records`、`release-leakage-defects`、`fix-user-defects`、`other-function-defect-count`、`code-review-multi-statistics`；尚未包含本轮两个新`viewKey`。
- 按回执`767cf44c`登记两个用例（沿用原冻结夹具，不添加2027行）：
  - `quality-rd-quality-review-records`：`dashboardKey=quality-rd&viewKey=quality-review-records&reviewType=需求说明书评审`
  - `quality-rd-new-issue-defects`：`dashboardKey=quality-rd&viewKey=new-issue-defects`（默认原目录范围）
- 两用例只验证现有夹具下的接口通路与字段契约；2027合并行为沿用已通过的PG与页面证据，本轮不增加2027夹具。
- 拟复核但不改登记：`GET /api/quality-board/other/overview`、质量看板明细与导出现有用例仍覆盖相邻入口。

### 4. 黄金拟变更清单（静态核对，以实际compare核定）

| 快照 | 已确认的有意变化或待核定项 | 原因 |
| --- | --- | --- |
| `snapshots/bi/get___requirements__default.json`、`get___design__default.json` | `$.data.ruleVersion`从`bi-review-v3`到`bi-review-v4`，参与严格比较 | 规则版本升级；该字段未被掩码忽略。业务数据差异另按实际结果核定 |
| `snapshots/bi/get___coding__day-all.json` | `$.data.ruleVersion`从`bi-coding-v5`到`bi-coding-v6`，参与严格比较 | 同上 |
| `snapshots/bi/get___system-test__default.json` | `$.data.ruleVersion`从`bi-system-test-v3`到`bi-system-test-v4`，参与严格比较 | 同上 |
| `snapshots/analytics-dashboards/get___{dashboardKey}__quality-rd.json` | 需求/设计及新发修复率动作的`viewKey`、参数改变；其他差异待实际核定 | 内部明细路由接线 |
| `snapshots/analytics-dashboards/get___{dashboardKey}_details_{viewKey}__quality-rd-quality-review-records.json` | 尚无快照，首次compare应报缺失；先提供实际响应 | 新内部明细场景 |
| `snapshots/analytics-dashboards/get___{dashboardKey}_details_{viewKey}__quality-rd-new-issue-defects.json` | 尚无快照，首次compare应报缺失；先提供实际响应 | 新内部明细场景 |
| BI版本目录、其他质量看板、相邻明细/导出、权限 | 本单元无已知业务变化，但**不预先断言逐字不变**，由实际compare核定 | 版本升级可能影响相邻响应 |
| CAT设置/映射 | 保持EXCLUDED原登记，无快照 | 本单元未接入CAT |

“无2027夹具”只说明两新用例覆盖现有范围，不代表BI快照不变：BI版本目录与四份BI阶段快照仍会因规则版本升级产生差异，必须由实际compare确认。

本阶段只执行非update严格比较并提交差异；快照生成/更新须另行审批。获批后执行`mvn.cmd test -Pgolden-baseline -Dtest=GoldenBaselineChainTest -Dgolden.update=true`生成，审阅diff后去掉update严格复比，全绿才进入打包。不得手工编辑快照或放宽掩码。

### 5. 升级与恢复说明

- 前置：按发布runbook停止应用服务并完成数据库备份；确认目标环境无未完成同步任务。
- 升级：替换后端与前端制品为该候选；后端首次启动加载分组资源，Flyway无新迁移；无需执行SQL、重建事实或导入目录。
- 首次访问：新规则版本与来源指纹使旧缓存/下载授权失效，页面会重新读取当前已发布数据；读取失败按既有读链规则处理，不降级为空范围。
- 验证：健康检查、两看板组合项与显示名、评审/新发明细权限正反例、其他质量明细与导出文件名、独立系统测试R1/R2保持、CAT设置仍为原目录。
- 回滚：同时回退后端与前端到上一发布包；本单元不改数据库结构或数据，回滚不需要恢复库；旧前端不认识新明细路由，禁止只回退一侧。
- 兼容提示：如现场保留过组合显示名URL，升级后将提示“版本不存在或已停用”，需改选稳定key；不影响原目录或其他版本。

### 6. 现场核对项（上线前）

| 核对项 | 只读方法 | 通过标准 | 阻塞性 |
| --- | --- | --- | --- |
| 项目9目录 | 查询启用catalog/group/member | 存在`CC2027R1`锚点，R1/R2业务键与成员阶段符合配置；无重复或跨组冲突 | 阻塞组合入口 |
| 评审来源 | 查询`review_records.project_name`去重值 | 确认真实成员名称及是否存在`2027R1&R2`；与登记值一致 | 影响评审密度与明细 |
| 集成测试阶段 | 查询`integration_test_fact.testing_phase` | 存在成员各自的`<版本>集成测试`；不构造组合阶段 | 影响集成通过率 |
| 事实完整性 | 按成员阶段统计有效议题 | 与页面概览/明细一致；已拒绝记录排除 | 阻塞验收 |
| 角色权限 | 查询角色授予 | 需要下钻的用户同时具备看板查看与`review.data.view`/`system_test.summary.view` | 未授权返回403属预期 |
| 目录缺失分支 | 确认R2或锚点缺失时的候选 | 组合不可用或按锚点降级，不私自换锚点 | 影响候选显示 |
| CAT与独立系统测试 | 设置页与系统测试入口 | CAT仍为原R1/R2且未接入；独立系统测试仍分R1/R2 | 不通过即阻断 |
| 部署后缓存/导出 | 页面来源版本与下载 | 旧授权失效、新导出文件名带业务名且无内部key | 影响上线确认 |

生产2027目录、真实评审别名与集成测试来源仍为现场前置，不以隔离夹具计数替代。

### 7. 首次黄金严格比较结果（2026-11-04，非update）

| 项目 | 记录 |
| --- | --- |
| 命令 | `D:/projects/data_collection_platform/tools/maven/apache-maven-3.9.9/bin/mvn.cmd test -Pgolden-baseline -Dtest=GoldenBaselineChainTest -Dgolden.update=false`（`backend/`，JDK21） |
| 提交与工作区 | HEAD `a2752fd2`；运行期工作区含本文、`endpoint-catalog.yml`（新增2个case）及`GoldenBaselineChainTest.java`（仅为两个新用例落盘响应正文的取证代码，不参与比较）；`git diff`指纹`c8703530bd64bedcd0775f974043e9ec19e80b73f03de1e7dfec0fe38914f938` |
| 环境 | Testcontainers `postgres:16-alpine`双容器（平台库/源库）+随机端口应用；未连接或写入15432/15433、真实GitLab或CAT |
| 结果 | **205项：8失败、2错误、0跳过，BUILD FAILURE**，耗时06:34 |
| 证据 | `.tmp/golden-strict-20261104.log`；失败消息UTF-8摘录`.tmp/golden-failures-utf8.txt`；两个新用例响应`.tmp/golden-actual/` |
| 资产完整性 | `snapshots/`、`fixtures/`、`baseline-manifest.json`、掩码与EXCLUDED均无改动（`git diff`为空）；未执行update |

| 用例序号 | 快照 | 实际差异 | 性质 |
| --- | --- | --- | --- |
| [29] | `analytics-dashboards/get___{dashboardKey}__quality-rd.json` | `metrics[0]`、`metrics[1]`的`detail.viewKey`由`review-data-records`改为`quality-review-records`；`metrics[7]`的`viewKey`由`system-test-defect-summary`改为`new-issue-defects`，参数`testingPhase`改为`projectName` | 已确认有意（R01-b内部明细） |
| [162][163] | `bi/get___requirements__default.json`、`bi/get___design__default.json` | `$.data.ruleVersion`由`bi-review-v3`改为`bi-review-v4` | 已确认有意（回执`767cf44c`） |
| [164] | `bi/get___coding__day-all.json` | `$.data.ruleVersion`由`bi-coding-v5`改为`bi-coding-v6` | 已确认有意 |
| [165] | `bi/get___system-test__default.json` | `$.data.ruleVersion`由`bi-system-test-v3`改为`bi-system-test-v4` | 已确认有意 |
| [39] | 新用例`quality-rd-quality-review-records` | 快照缺失 | 预期（本轮不生成） |
| [40] | 新用例`quality-rd-new-issue-defects` | 快照缺失 | 预期（本轮不生成） |
| [33] | `analytics-dashboards/get___{dashboardKey}_rules__quality-rd.json` | `rules[6].scope`由`CC2026R3 的集成测试与系统测试阶段；系统测试有效缺陷排除已拒绝记录`变为`CC2026R3集成测试 与系统测试阶段；系统测试有效缺陷排除已拒绝记录` | **新发现，待裁定** |
| [37] | `...__quality-rd-release-leakage-defects.json` | 当前页20条记录（共55条）每条新增`isFixed` | **新发现，待裁定** |
| [38] | `...__quality-rd-fix-user-defects.json` | 全部7条记录每条新增`isFixed` | **新发现，待裁定** |

新发现差异的根因：

- [33]：`QualityRdAnalyticsDashboardProvider`的`integrationScopeDescription`改为拼接目录中的实际阶段名（`CC2026R3集成测试`），再追加“ 与系统测试阶段”，因此丢失原有“ 的”与词序。属用户可见的规则说明文案变化，不是数据口径变化。
- [37][38]：`QualityRdAnalyticsDetailQueryService.issueRows`为支撑新发明细的`fixState`，对所有议题明细统一放入了`isFixed`；现有`release-leakage-defects`与`fix-user-defects`因此新增该字段。属附加字段，不是行集合或取值变化。

以上两项未列入已批准清单，本轮不据此宣称黄金通过；可选处置为“接受为有意变化并纳入快照更新”或“限制字段/修正文案后重新技术验收”，由审批方裁定。

两个新用例的实际响应（冻结夹具，原目录范围）：

| 用例 | 分页与规模 | 字段与取值 |
| --- | --- | --- |
| `quality-rd-quality-review-records`（`reviewType=需求说明书评审`） | `total=18`，第1页20条内18条 | 11列；18条均为`CC2026R3`需求说明书评审；`problemCount`合计212；`reviewScalePages`合计0，与快照中需求评审密度`0.0`一致 |
| `quality-rd-new-issue-defects` | `total=30`，第1页20条 | 13列，含`fixState`；当前页`已修复/未复现`16条、`未修复`4条；与快照中新发修复率`86.67%`（26/30）一致 |

响应正文完整JSON位于`.tmp/golden-actual/`：评审明细6,951字节，SHA-256 `f4b7af4bc2f48f27f224330e686022baf6fdbf59d44e45ce0a42143798597b72`；新发缺陷14,249字节，SHA-256 `d09ebff68446e4b541ef28df44e1fdae1d7c9dd5306217bd3bd95a4baefa215b`。未写入`snapshots/`。

后续：先审批上述实际差异（尤其是[33][37][38]），再授权`-Dgolden.update=true`生成；预计更新8份既有快照并新增2份，随后去掉update严格复比。本轮不生成、不合main、不打包。

## 1. 用户能看到什么

| 位置 | 实施后的行为 |
| --- | --- |
| 质量看板：研发质量、其他质量分析 | 原R1/R2入口收为一个 `CC2027R1&R2`；指标、图表、明细、下钻和导出使用同一个合并范围 |
| BI看板：需求、设计、编码、单元测试、集成测试、系统测试研发阶段 | 顶部版本提供同一个合并入口；测试轮次仍保留实际名称、顺序和原目录身份 |
| 独立“系统测试”模块及其缺陷、原因、延期、阶段、查询与导出 | 保持R1/R2原目录、默认顺序、筛选、匹配和统计结果 |
| CAT及BI中依赖CAT的单元测试/集成测试内容 | 保持当前未接入状态；不改CAT连接、设置、映射、同步、快照、统计或下载协议。BI公共版本栏统一显示组合名，不借此接入数据 |
| 客户问题模块及BI独立客户问题页 | 页面、范围、统计、导出及延期标签写回保持 |
| 其他版本、DGM及独立客户问题页 | 保持既有范围与公式；DGM只在原本参与对应质量/编码指标的位置随现有读源规则读取 |

GitLab里程碑和标签、ODS、事实中的原始版本与测试阶段均保留。看板合并是查询范围组合，不是改写历史归属。没有测试阶段的议题继续遵循当前页面规则，不因项目标签为组合版本而自动加入轮次统计。信创版归属本轮不重新定义，保留各读源当前行为并在送审清单列明差异。

## 2. 选定设计：看板专用分组，系统测试目录不变

选版本化分组资源，不修改共享阶段目录，因为共享目录还服务独立系统测试模块。选原始输入集合并集，不相加两个旧看板结果，因为一条组合记录可能同时属于R1和R2。

新增 `backend/src/main/resources/dashboard-version-scopes.json`，由强类型加载器校验后冻结。首版只登记一项：

| 属性 | 值或约束 |
| --- | --- |
| schemaVersion | 1 |
| key / displayName | `cc-2027-r1-r2` / `CC2027R1&R2`；key为稳定分组身份，名称只负责显示 |
| projectId | 当前CC测试阶段目录所属项目9，使用已核实的平台项目常量核验 |
| anchorBusinessKey | `CC2027R1`；在目录中查出真实ID，不写死环境ID |
| memberBusinessKeys | `CC2027R1`、`CC2027R2`；按稳定业务键解析原目录，不能按标签前缀猜归属 |
| sourceBindings | 按来源类型显式登记第7节核实的评审项目名、集成测试阶段值及所属分组；只是查询映射，保留原值，不散落在Java分支或把项目标签当阶段 |

新增平台共享的 `DashboardVersionScopeService`，仅供两个看板使用。返回不可变 `DashboardVersionScope`：分组key、显示名、锚点ID、已解析成员组ID/业务键、原测试阶段成员、配置及目录指纹。

- BI继续使用现有正数 `productVersionId`，合并入口取锚点R1的原目录ID；此ID在看板请求里解析为组合范围，在独立系统测试里仍表示原R1。原目录对象和业务键不改写。
- 质量看板继续使用 `projectName` 参数，组合入口的请求值为 `CC2027R1&R2`，经服务解析；不在查询中拿显示名拼来源标签。无需新增页面或重做布局。
- 看板候选将组合项放在原成员最靠前的位置，其余排序不变，默认项仍取最终候选首项；从两个看板候选中隐藏已被组合替代的单独R1/R2项。独立系统测试仍列出原两项。
- 未参与组合的版本由原目录形成单成员范围。BI旧R2链接按现有候选校验流程重新选取有效入口；服务端不保留R2别名查询组合数据。
- 只展开当前启用的原目录及成员；R2尚未定义时不制造轮次，后续管理员按原流程登记启用R2后自动进入合并范围。锚点不存在/停用则组合入口不可用，不私自换锚点。加载器拒绝重复key、重复锚点、交叉组合及跨项目成员。
- 测试阶段按原成员ID去重，按成员组顺序、组内顺序排列；相同sourceValue对应不同成员ID而造成轮次归属歧义时报告配置冲突，不能任取一个。

本轮不新增表或Flyway迁移，不新增管理页面。原目录由原入口维护，分组资源随源码发布；未来管理需求另行设计。

## 3. 接入改造清单

以下类/方法按main `f74fdef7` 核实。员工在开工基线复核名称漂移，按职责修改，不另建旧新双轨。

| 位置 | 必须实施的改动 |
| --- | --- |
| `QualityBoardRdService.listProjectOptions/normalizeProjectName`、`QualityRdAnalyticsDetailQueryService.phaseOptions`、`QualityBoardOtherQueryService.enabledProjectNames/scope` | 看板候选、默认值和请求统一调用新服务；不能继续有一处独立列原R1/R2 |
| `QualityBoardRdService`的阶段计数、`QualityRdAnalyticsDetailQueryService`、`QualityBoardOtherQueryService`的批量/单项查询 | 用组合范围的原阶段成员过滤原始事实；保留各页面原有精确/包含成员策略，不修改 `SystemTestPhaseScopeResolver` 或全局 `SystemTestPhaseMembershipPolicy` 的语义 |
| `QualityBoardRdService.reviewDensity` | 查询成员版本的既有评审集合，并纳入经只读证据确认的组合评审项目名称（已有样例 `2027R1&R2`）；先按现有正式/兼容读源契约取得唯一可见记录，再按稳定评审身份去重并计算。不能因参数含CC而漏掉来源名称省略CC的组合记录，也不能借本轮清理无关评审逻辑 |
| `QualityBoardCodeReviewReadSupport.resolveProductVersionScope`及其明细/导出调用 | 将成员版本匹配到的来源项目名取并集，只查询一次。组合MR和多次走查保留原有来源、项目、MR/记录身份；dev与SP分支规则不变。保留已合main的新增行数去重修复 |
| `QualityBoardRdService.integrationPassRate/integrationNotPassCount`、`QualityRdAnalyticsDetailQueryService`相应明细 | 移除“组合显示名+集成测试”的来源推断。展开原R1/R2对应的既有集成测试来源值，再按原始行计算原公式；不得拼出不存在的组合阶段或平均两个已算百分比。真实组合来源值只有经查询确认并显式登记后才能读取 |
| `BiPlatformProductVersionAdapter.catalog/requireScope`、`BiProductVersionScope` | 适配看板服务；内部范围改为可携带成员业务键和原轮次列表的唯一模型，同步改全部调用与夹具，不保留单版本回退实现 |
| `BiPlatformReviewSourceAdapter.load`、`BiPlatformCodingSourceAdapter.load`与 `BiProductVersionMatcher`调用处 | 对原成员逐一应用已有匹配规则，用任一成员命中判定基础记录是否纳入，一次返回去重集合。不能把组合key传入目前只接受单个目标版本的matcher；代码提交仍按来源+项目+SHA去重，走查按既有稳定记录身份 |
| `BiPlatformSystemTestSourceAdapter.load` | 查询组合原轮次的并集，保持ST口径、有效缺陷排除规则和原阶段映射。组合后同一有效议题只计一次，不先分别计算R1/R2再累加 |
| `QualityBoardWorkbookExportService`、质量分析Provider/明细服务、`BiDownloadAuthorizationService`、`BiPlatformCurrentSourceVersionAdapter`及快照来源版本 | 复用同一次解析的组合范围。把分组资源版本/哈希、实际成员及目录版本加入现有读版本/快照/下载资格；保留原事实来源版本，不可用分组哈希代替数据版本。提升受影响规则版本，使旧单R1缓存失效；配置或目录变化后旧下载授权失效 |
| `QualityBoardRdView.vue`、`QualityBoardOtherView.vue`、`BiDashboardView.vue` | 仅适配服务端候选、显示名和必要类型；保持现有筛选、图表、布局与交互。页面不自行合并结果，表单UI、CAT设置和阶段内容完全不动 |

## 4. 口径与确定性例子

BI公式继续按本域核对表RQ/DS、CD、ST各编号执行，本轮只改变范围；质量看板继续按自身现有公式。合并后的分子、分母从去重后的基础数据重算，不强求不同模块原有公式得到相同值。

| 场景 | 预期 |
| --- | --- |
| R1阶段2条、R2阶段3条有效议题，另有其他版本1条、无阶段1条 | 两个看板各自满足其既有过滤的测试总量为5；独立系统测试仍R1=2、R2=3。无阶段与其他版本样本不因合并而加入 |
| 同一组合评审被两个成员规则同时匹配，有效问题4、评审页数20 | 去重后仍1次评审、4个问题、20页；密度=0.20，不变成8个问题/40页来掩盖重复。明细与导出也仅1条 |
| 同一MR新增1000行、两次走查分别60/30分钟，属于组合项目 | 来源明细仍保留两次走查；按已批MR累计速率的导出结果仍为 `1000÷90×60=666.67` 行/小时（按现有精度），不把新增行数算为2000；两个成员同时命中也不复制两遍 |
| 两个比例基础数分别1/2、9/10，目标指标原公式为合计分子÷合计分母 | 合并=10/12=83.33%，不是把50%与90%平均为70%。原本按基础行均值计算的其他指标仍按其原公式执行 |
| 只有R1，之后原目录正常登记启用R2并新增3条 | 分组资源无需修改；下一次读取/刷新纳入R2，范围指纹改变，页面与导出采用新范围 |
| CAT当前未接入、独立客户问题页使用原范围 | 改前改后状态与行为保持；不新增CAT连接/同步动作，不改客户统计或延期写回 |

组合查询取当前已发布完整读版本。SQL用成员集合/EXISTS筛选原行，不用成员JOIN将一条记录扩成多行；多个事实修订不能以裸issue_id随意选一条。复用现有发布读链和各读源稳定身份，不能扩大为全局事实重构。

## 5. 最小实施顺序与验收

1. 先只读核对实际数据库目录与来源，冻结第7节清单；在独立功能分支建立活动计划恢复信息，补入当前最新协作规则。先实现分组资源/解析服务及两个看板候选，锁定独立系统测试目录完全未改。
2. 再接质量/BI原始读源、明细、导出与来源指纹；用同一真实PG夹具核对上表2+3=5、组合记录去重和系统测试2/3负对照。沿用相关现有测试，不为每一张同源图表重复搭整套环境。
3. 前端仅做候选适配。桌面真实页面查看两个看板各一次、独立系统测试R1/R2各一次，并检查一个质量明细Excel与一个BI导出；确认CAT仍未接入、客户问题仍用原入口和范围即可，不搭CAT环境或重跑客户模块矩阵。记录请求范围、源记录身份与预期计数。
4. 跑受影响后端回归、前端typecheck/lint及相关单测；最终送审代码状态完成默认套件与构建。若只有文档调整复用不变代码的结果，失败需定位根因，不靠重复跑绿。
5. 更新端点目录与权威文档。本轮为开发送审，不主动运行黄金、不改快照、不合main、不打包。审批通过后的发布准备，再展示有意产出差异、按AGENTS获得快照更新授权并严格compare。

## 6. 员工交付与审批门禁

- 从开工时已核实的稳定main新开一个 `codex/dashboard-combined-version-20261103` 功能主分支及独立worktree；当前已知main为 `f74fdef7`。本方案所在审核分支还含表单审核文档，只吸收本文及本次规则修订，不整体合入审核分支。
- 本方案授权上述范围的实现与测试。沿同一分支完成阶段提交/整改，按职责提交并推送origin与gitlab25，回读两端SHA；不启用生产配置、不改真实GitLab、不借此接入未发布表单代码。
- 送审报告给出：基线/最终SHA、完整改动清单、实际分组配置、来源只读证据、自动测试结果、四处页面检查及导出对账、原目录前后指纹、运行资源清理结果。
- 审核方以“合并集合正确、原系统测试未变、组合记录不重复、缺失来源不伪造、页面/下钻/导出同范围”五项裁定。任何一项失败则修改后重审；批准前不合main、不出包。

## 7. 只读取证与剩余事项

| 需核实的事实 | 员工动作 | 影响 |
| --- | --- | --- |
| 真实R1/R2目录业务键、ID、启停与阶段成员 | 从明确归属的目标只读连接或生产导出副本读取 `issue_scope_catalogs/groups/members`，核对项目及关联；保存脱敏结果 | 确定资源绑定、当前R2是否存在及范围负对照；不能以开发种子冒充生产配置 |
| 标签→议题→当前已发布事实 | 从同一来源的ODS标签/关联、议题及当前发布事实抽样，按完整来源身份对账；精确列名从实际DDL核对 | 不更改标签解析；确认原阶段集合与新增范围可复现 |
| 组合评审名称、代码走查来源项目名、集成测试阶段真实值 | 查询当前可见评审与既有代码/集成测试读源，确认 `2027R1&R2` 等真实值及身份，不凭文字造映射 | 写明需要显式登记的真实来源值；暂缺的来源保持既有缺失语义 |
| 信创版（2027R1）与无阶段议题的新规则 | 本轮保持原行为，分别列出当前读源实际归属和计数 | 不阻塞已确定的组合实现；业务新口径继续由用户确认 |

尚无目标库只读证据时，可先实现并用隔离夹具验证通用分组，但不能把生产映射和现场计数写为已核实。风险集中在共享目录误改、组合记录重复、仅改名称而未改读源及缓存未失效，分别由范围隔离、原始集合去重、接入表和来源指纹处置。CAT版本适配留到未来正式接入时独立设计，不在本次范围内。
