# BI与质量看板合并版本实施方案

## 当前状态与待办

结论：2026-11-04审核输入 `8c3d1c9f6c0b3f7776d67327025fcf8d17b5245c`。首次严格黄金比较205项为8失败、2错误，发布门禁未通过。用户已确认5份更新、2份新增的快照范围，并要求员工完成后先由审核方审批再继续。现在准入两处修复、定向回归和限定范围快照生成；生成结果送审，通过后再执行严格复比。main、打包及部署尚未准入。

| 现在可做的任务 | 负责人/执行约束 | 交付物 |
| --- | --- | --- |
| 修复规则说明 | 员工修改QualityRdAnalyticsDashboardProvider.loadRules及相应说明生成方法 | 普通CC2026R3恢复“CC2026R3 的集成测试与系统测试阶段；系统测试有效缺陷排除已拒绝记录”；组合范围明确列实际成员阶段，不能替换为不存在的组合阶段。公式和筛选不变 |
| 收回内部字段 | 员工修改QualityRdAnalyticsDetailQueryService.issueRows的输出边界 | is_fixed仍供isNewIssueFixed计算；enricher完成后不将内部isFixed返回客户端。同一共享投影的所有调用一并检查。新发明细保留fixState，原明细字段集合保持原样 |
| 最小回归 | 员工沿用原任务树与分支，先复现再修复，只跑QualityRdAnalyticsDashboardProviderTest及QualityRdAnalyticsDetailQueryServiceTest | 普通与组合规则说明正确；原两种议题明细及新发明细不含isFixed；新发fixState仍识别关闭且is_fixed=true等既有样本。不重复默认全量、58项、页面矩阵或突变自证 |
| 快照生成与审阅 | 用户已确认下表7份范围；员工完成上述修复和定向回归后可生成，完成即送审核方审批 | 冻结候选提交，展示实际快照差异及两个新JSON。若出现第8份业务快照变化或额外字段/数值变化，先查明并送审，不扩大授权范围 |
| 严格复比 | 审核方审阅生成结果并批准后，再去掉update执行严格compare | 全部205项通过、无缺失快照；同一修复状态在update前无需为重新列出已知差异再跑一次完整compare。保留原夹具、掩码、EXCLUDED和比较器 |
| 生产来源核对 | 员工整理，用户提供现场只读结果 | 第7节缺口仍保留；不以空本地库或隔离夹具代替现场事实 |

恢复：员工目录 `D:/projects/dcp-dashboard-combined-20261103`，分支 `看板合并版本范围-20261103`，原main基线f74fdef7；资料准备和整改复用。只吸收本审批状态，不整体合入包含表单记录的审核分支。

| 已核验范围 | 当前结论 |
| --- | --- |
| 身份 | 员工树干净，本地/origin/gitlab25任务分支均为8c3d1c9f；main三端为f74fdef7。aa62d219至本次HEAD生产代码差异为空，两个case已登记 |
| 技术验收 | R01-a/b/c及R02均关闭，经理原独立编译及58/58（含PG5项，0跳过）结论保留；本轮只读复核实际失败摘录、新响应与源码，未另跑测试 |
| 黄金覆盖 | 目录护栏2项通过；首次严格比较205项为5项预期差异、3项意外差异和2项新快照缺失。3项意外差异来自两处根因。现有夹具无2027组合成员，合并范围沿用已通过PG/页面证据 |
| 来源掩码 | BI目录忽略generatedAt/sourceVersion/snapshotId，没有忽略ruleVersion；质量其他看板响应未直接输出Provider规则版本，不凭版本升级就断言其JSON必变 |

### 快照差异裁定与用户已确认范围

以下按实际失败摘录、两个新JSON与源码核对。用户已确认5份更新和2份新增；另外3份既有快照通过修实现恢复，保持文件原样。用户将生成结果的下一步审批交给审核方，不自动授权合main或出包。

| 快照 | 裁定 |
| --- | --- |
| bi/get___requirements__default.json、bi/get___design__default.json | 拟更新：仅$.data.ruleVersion从bi-review-v3到bi-review-v4 |
| bi/get___coding__day-all.json | 拟更新：仅$.data.ruleVersion从bi-coding-v5到bi-coding-v6 |
| bi/get___system-test__default.json | 拟更新：仅$.data.ruleVersion从bi-system-test-v3到bi-system-test-v4 |
| analytics-dashboards/get___{dashboardKey}__quality-rd.json | 拟更新：需求/设计及新发修复率动作的viewKey、参数改变；原指标值保持 |
| analytics-dashboards/get___{dashboardKey}_details_{viewKey}__quality-rd-quality-review-records.json | 拟新增：total=18，当前页18条、11列，problemCount合计212、reviewScalePages合计0；与密度0.0一致 |
| analytics-dashboards/get___{dashboardKey}_details_{viewKey}__quality-rd-new-issue-defects.json | 拟新增：total=30，当前页20条、13列，fixState为16条已修复/未复现及4条未修复。生成前去掉所有行中的内部isFixed；当前页计数不能单独证明全体26/30 |
| analytics-dashboards/get___{dashboardKey}_rules__quality-rd.json | 不更新：修复普通版本规则说明，恢复原响应 |
| analytics-dashboards/get___{dashboardKey}_details_{viewKey}__quality-rd-release-leakage-defects.json、对应quality-rd-fix-user-defects.json | 不更新：移除响应中意外新增的isFixed，恢复原字段集合 |
| 其余既有快照 | 保持；CAT设置/映射仍沿现有EXCLUDED登记，不借本轮改夹具、掩码或比较规则 |

执行入口：员工backend目录、已验证的JDK21和 `D:/projects/data_collection_platform/tools/maven/apache-maven-3.9.9/bin/mvn.cmd`。本轮先 `test -Dtest=QualityRdAnalyticsDashboardProviderTest,QualityRdAnalyticsDetailQueryServiceTest`；修复通过后使用 `test -Pgolden-baseline -Dtest=GoldenBaselineChainTest -Dgolden.update=true`。生成结果交审核方审阅，获批后再以相同入口改为 `-Dgolden.update=false` 严格复比。

现有update会重写全部用例快照，未提供按case生成的过滤入口。运行前在自有.tmp保存原快照，生成后仅接收上表7份；其他生成文件若只有既有掩码覆盖的易变差异，按运行前原文件完整恢复，不手工编辑JSON。发现额外业务差异则保留生成证据并送审，不能只为满足7份清单而丢弃差异。比较器、目录case、夹具、掩码和EXCLUDED本轮保持。

运行使用现有黄金隔离恢复/真实同步链，保护其他会话服务，结束后恢复自己调整过的入口。第一次严格运行及正文见员工.tmp/golden-strict-20261104.log、.tmp/golden-failures-utf8.txt与.tmp/golden-actual/，过程不再逐轮复制进正文。员工同步当前状态、差异分类与稳定key契约，正文中残留“显示名作请求值”的旧表述须纠正；不为文档整理另起审批轮次。

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
- 质量看板继续使用 `projectName` 参数：组合入口value为稳定key `cc-2027-r1-r2`，普通版本value为原业务键，label为displayName。解析、默认、下钻、导出以及服务间传递均使用稳定身份，不能把显示名重新送回解析器；不在查询中拿显示名拼来源标签。无需新增页面或重做布局。
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
5. 更新端点目录与权威文档。开发期不主动运行黄金；当前已进入发布准备，按顶部准入执行首次非update比较。展示实际有意差异并按AGENTS取得生成/更新授权后，才进入快照生成、严格复比、main及正式包。

## 6. 员工交付与审批门禁

- 开工隔离已完成，当前整改复用上方员工分支/worktree，不因复审另建分支；分支命名沿最新AGENTS的中文功能名称规则。原方案中的codex分支名不再适用。本方案所在审核分支还含表单审核文档，只吸收本文及本次规则修订，不整体合入审核分支。
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
