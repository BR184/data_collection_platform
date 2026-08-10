# 进度与中间物

- 状态：2026-08-06 用户已确认 CAT 数据实时性要求不高，单元/集成页面从请求时直连 CAT 调整为读取 BI 专属 CAT 镜像快照。CAT 镜像在系统设置的数据镜像页独立配置和触发，不进入 GitLab 镜像表、兼容模式或表级增量引擎。
- 已完成 CAT 设置责任收敛：连接/读取超时、响应上限和快照保留数改为后端部署策略，页面不再要求用户填写；CAT 目录发布后按真实 `defaultProject`、`curVersion`、版本语义和阶段语义生成可审查的系统建议，用户确认保存后才成为权威映射，已保存映射始终优先。
- 当前验证状态：后端全量测试 1118 项通过、0 失败、0 错误、1 跳过；CAT/BI 定向测试通过；前端 114 个测试文件、409 项测试通过，类型检查、ESLint、生产构建通过；后端生产 JAR、Checkstyle 和 SpotBugs 均通过。最新源码已在 `18080`/`18181` 运行，Flyway 已应用 `20260806.02`；CAT 设置页在 1920×1080 下无横向溢出、控制台错误或技术参数残留，未发布目录时保持明确的“尚未采集”和空映射状态。CAT 只能在内网联调，外网已用本地受控 HTTP 替身验证请求路径、字段映射、错误收敛、响应上限、阶段隔离和失败保留旧快照。
- 已完成文件/变更：新增 `BiPlatformCodeReviewSourceContextFactory`，编码 Adapter 在单次请求内冻结平台统一兼容模式并只读对应正式/兼容来源；系统测试来源与计算器删除虚构轮次提交总数字段，改用有效缺陷 ID 去重数并校验严重度、关闭状态两组守恒；人员负荷 DTO、计算器、Adapter 和前端统一使用 `assignee_name` 及“按指派人统计缺陷数”语义。相关后端、前端测试和 BI 权威文档已同步。
- 测试状态：后端 1118 项通过、0 失败、0 错误、1 跳过；Checkstyle、SpotBugs、单 JAR、前端 409 项、ESLint、TypeScript 与生产构建均通过。Spring 多构造器歧义已通过在 `BiCatMirrorManager` 生产构造器显式标注 `@Autowired` 解决，受影响的全量上下文测试不再级联失败。
- 当前进行点：进入内网配置真实版本/阶段映射并执行首次全量同步；本地运行态和系统设置 CAT 区块验收已完成。

## 恢复线索

- 当前阶段：CAT 请求时直连已替换为独立全量快照同步和镜像读取，自动门禁与本地浏览器验收已通过，等待内网真实数据验收。
- 恢复后首条命令：`git status --short && rg -n "BiCat" backend/src/main frontend/src docs/bi-dashboard`；若本地运行态未启动，先执行 `scripts/start-platform.ps1` 或仓库既有前后端启动入口。
- 架构依据：`docs/bi-dashboard/decisions/ADR-001-embedded-module-and-strong-package-boundary.md`。
- 公式依据：`docs/bi-dashboard/BI看板数据来源与计算口径核对表.md`。

## 目标与边界

- 目标：在数据采集平台内实现 BI 一级模块及需求、设计、编码、单元测试、集成测试、系统测试六页，正式效果与已确认静态页面一致，并以 Apache ECharts 6 实现 BI 专属可复用图表类型类。
- 成功标准：平台保持单 Maven/单 JAR；CAT 配置和运行状态进入数据镜像设置；同步只写 BI 专属表，按产品版本和测试阶段完整采集后原子发布；失败不替换上一成功快照；单元/集成页面只读最新发布快照；功能达标数保持“功能”单位，缺失用例计数不补零；CAT 同步异常不影响平台其它镜像、页面或启动；自动测试、构建和 PC 浏览器验收通过。
- 禁止：修改现有平台看板业务口径或图表组件；浏览器直连 CAT；页面请求回退直连 CAT；把 CAT 表混入 GitLab 镜像、兼容模式或现有同步状态；伪增量、mock、零值补齐、旧 Provider 回退、显示名业务键、页面级兼容别名、万能图表渲染器、Maven Reactor 或第二个部署单元。

## 约束与背景

- 后端统一位于 `com.data.collection.platform.bi`，分 `api`、`application`、`domain`、`infrastructure`；平台代码不反向依赖 BI。
- Spring 只管理薄 Controller、Runtime Manager 和 Runtime Factory；BI 内部服务、计算器和仓储由 Factory 显式延迟装配。
- 需求/设计/编码/系统测试只读取平台；单元/集成只读取 CAT。页面请求先冻结产品版本、筛选和来源版本，再生成整页。
- 平台产品版本以系统测试阶段目录稳定组 ID/业务键为入口；评审和代码走查的历史项目名按 ADR-006 规范化匹配，不做模糊显示文本匹配。
- 编码和系统测试的模块/人员图只在同一平台来源快照内分组，不跨平台关联实体；因此使用规范化后的来源维度值及原始显示值，不要求平台当前不存在的独立实体 ID，也不把显示值伪装成全局 ID。缺失维度进入显式未标注/未归类分组。
- GitLab 提交频次必须来自稳定提交 SHA 与真实提交时间，不得用合并请求行数、`commit_count` 或合并时间替代。提交原始表进入平台统一推荐同步、血缘和事实层后再由 BI 读取，BI 不在页面请求中调用 GitLab API。
- 兼容模式下人工走查以旧平台 `spider_crowncad_data` 的结构化结果为来源，但必须同步到 BI 独占兼容表；只有人工走查质量、问题分布、散点、扫描和注释率等走查事实族读取该表。代码趋势、人员贡献、模块增量等既有事实族继续读取 `code_review_match_mode_records`，提交趋势读取 GitLab 提交事实；三类来源按图表依赖隔离，不能把编码页整体切换到 BI 兼容表。BI 页面不直接连接老平台 MySQL，也不增加独立兼容开关。该同步的暂存、发布、错误状态和事务不得与现有 `code_review_match_mode_records` 共用，任一侧失败不能阻断或回滚另一侧。
- 系统测试有效缺陷、严重度、优先级、修复、延期、修复人和原因均严格按核对表 ST 编号计算；原因分类使用 BI 自有版本化资源。
- BI 不新增兼容模式开关；一次请求冻结平台现有兼容模式，需求/设计/编码与对应平台页面选择相同读源。内网真实环境以兼容模式常开为常态。
- 旧 `D:/projects/bi_dashboard` 全程只读，只作为布局、图表行为和视觉证据。
- CAT 当前协议不提供原子快照或来源版本。镜像发布版本只表示“数据采集平台在一个明确采集窗口内完整取得并原子发布的数据集”，不得宣称为 CAT 原生快照；页面追溯必须保留采集开始/结束时间和 CAT 范围 ID。
- CAT 手册中的 UUID 和名称仅是响应结构示例，不能写入默认配置；默认映射只能从已发布真实目录生成。唯一默认项目、平台当前产品版本对应的唯一当前 CAT 版本、唯一语义一致的版本和唯一 UT/IT 阶段可作为建议，歧义项保持待补充，不静默猜选。

## 证据与根因

- `DataCollectionPlatformApplication` 自动扫描平台根包；`PlatformWebConfiguration` 通过 `RequirePermission` 和本地 Session/RBAC 保护 API。
- 前端路由、导航和访问控制分别由 `frontend/src/router.ts`、`feature-manifest/modules.ts`、`types.ts` 和 `access.ts` 统一驱动，新增 BI 必须走这些入口。
- `BiDashboardDatasetProvider`/`BiDashboardPayload` 仅服务撤回的进程外方案；真实消费者不存在，通用外部数据集框架和系统测试模块修复率 Provider 仍有独立职责。
- 本地数据库存在稳定产品版本组（例如 `CC2026R4` 组 ID 10、`CC2026R3` 组 ID 11）及轮次成员；评审 R4 大多缺少评审页数，代码走查 R4 缺少扫描和注释率，必须呈现不可计算/不完整而非零。
- 平台已有 `PageRecordSnapshotService`、`FactProjectionVersionService` 和阶段目录作为来源身份与范围证据；BI 通过窄适配器消费，不直接依赖平台页面 DTO。
- 2026-08-05 本地 PostgreSQL 中 `review_visible_records` 有 440 条记录；需求、设计、编码请求失败并非无数据，而是 PostgreSQL `integer/int4` 字段被 `ResultSet#getObject(column, Long.class)` 读取时抛出 `conversion to class java.lang.Long from int4 not supported`。
- 后端已把该异常收敛为页面级 `ERROR`，但前端继续按业务区块渲染，并把缺失区块回退为 `INCOMPLETE`，导致真实查询失败被误显示为“数据暂不完整”、来源未接入和规则未提供。
- 编码 Adapter 已读取 `author_name/module_name`，却把领域记录的 ID 置空并将作者/模块能力标志硬编码为 `false`；计算器因此清空人员贡献、模块增量和模块走查质量。开发库 2,450 条有效编码记录中作者字段全部存在，2,395 条具有模块。
- 系统测试 Adapter 只读取 `delay_reason`，未读取 `module_names/delay_issue/delay_cause/reason_category/category/label_names`，随后把模块、原因和延期能力全部标记为不可用。开发库 7,678 条有效系统测试事实中 7,310 条具有模块、1,297 条为申请延期；`CC2026R3` 3,207 条中 3,200 条具有模块。
- 静态页面的延期热力图语义是“七类延期原因 × 严重级别”，真实筛选字段为 `delay_issue`；核对表中的 `delayApplicationStatus` 不是该图必需输入，应由真实字段映射取代。
- 登录态真实页面中需求和设计区块仍被标为“来源一致性不足”；数据库证实 `review_visible_records` 没有重复 ID，但当前兼容读模式会按视图契约用负数 ID 隔离兼容记录，计算器错误使用 `reviewId > 0` 拒绝了 88/91 条需求说明书评审和全部 239 条设计说明书评审。正确身份不变量是 ID 非零，同一 ID 内容不得冲突。
- 编码 Adapter 固定读取 `code_review_formal_records`，未跟随平台兼容模式；本地兼容态的有效记录因此没有进入 BI。正确边界是复用平台唯一模式上下文选择 `code_review_match_mode_records` 或 `code_review_formal_records`，不是创建 BI 开关、双读或空结果回退。
- 系统测试 Adapter 将轮次提交总数固定为不可用，计算器继而清空整张轮次图；实际上同一轮次有效缺陷集合已经同时提供缺陷 ID、严重级别和修复状态，提交总数应直接取去重缺陷数并校验两组守恒等式。
- 老平台分别实现“修复人-缺陷数量统计”和“指派人剩余缺陷数量”：前者按合法修复状态评论作者，后者按当前指派人和未关闭状态。新平台 ODS 当前让 `handler_name` 与 `assignee_name` 同源，但 `fix_user` 仍独立来自评论。现有 BI 用 `fix_user` 同时计算总数和待修复数，必然让尚无修复评论的未关闭议题落入未知组。
- `CC2026R3` 的需求评审有 18 条、有效问题 212 个，设计评审有 56 条、有效问题 410 个；两类记录的 `review_scale_pages` 全部为 `0`。436 条兼容评审报告中 435 条持有 `description_ids`，但 `review_data_match_mode_descriptions` 为 0 条；本地连接老平台 MongoDB `172.22.10.72:27017` 持续超时，统一视图只能将页数回落为 `0`。因此模块密度、评审速率和散点缺少真实分母，不能补零伪造。
- 设计评审另有 29/56 条缺少独立评审工作量；当前 `BiReviewCalculator` 用全部概览输入共同控制整个 Summary，使本可独立展示的 410 个有效问题也被隐藏。问题类别计数 56/56 条均守恒并能展示，证明这是指标完整性过度联动，不是问题字段缺失。
- `CC2026R3` 兼容编码范围实际有 2,872 条记录、按 `(source_instance, merge_request_iid)` 得到 2,380 个稳定合并请求；作者 2,872/2,872 有值，模块 2,732/2,872 有值，走查日期 2,872/2,872 有值，2,863 条有正数工时。兼容同步把老表 `status` 同时写入 `merge_request_state` 和 `review_status`，全部 2,872 条均为 `MERGED`；BI Adapter 却只把 `review_status=COMPLETED` 视为走查记录，导致人工走查、问题分布、静态扫描和质量趋势整批为空。
- 编码计算器只以数值 `merge_request_iid` 去重，并用包含仓库显示名等字段的整条 Record 相等性判冲突；全部来源下有 14 个 CC/DGM 同 IID，另有 2 个来源内真实字段冲突，共形成 15 个计算器冲突键，进而把代码趋势、合并请求数、人员贡献和模块增量整批清空。兼容态权威身份必须包含 `source_instance`，正式态必须包含 `project_id + merge_request_id`，且各事实族只校验自身参与计算的字段。
- 兼容查询明确把 `comment_rate_source` 置为 `null`，而 Adapter 与计算器又强制要求该字段非空；这与核对表 CD-38“兼容态没有独立来源说明时只展示注释率”冲突，导致兼容模式下代码注释率区块必然为 `INCOMPLETE`。提交趋势和提交频次仍属于真实上游缺口：当前读源没有稳定提交 ID 与提交时间。
- 调查时 GitLab 推荐目录只有 23 张表且未包含 `merge_request_diffs`、`merge_request_diff_commits`；当前已扩为 25 张并补齐来源血缘、事实投影和 Adapter。两张新增表只构成提交增强能力，不属于平台通用 MR 事实硬依赖。
- 本地 `CC2026R3` 兼容记录 2,872 条中只有 2 条 `commit_count` 非空，正式记录 2,450 条全部为空；即使字段完整，合并请求级计数也无法还原按日/周的真实提交分布。现有质量看板“代码提交频次”按作者统计走查记录行数，语义不同，不能复用。
- 后端全量回归证明 `merge_request_diffs`、`merge_request_diff_commits` 被加入 `GitlabFactDependencyCatalog` 的通用 `MERGE_REQUEST` 必需表后，既有自定义代码走查白名单会被错误判定为不支持 MR 事实；同一错误还存在于 `GitlabSourceSchemaGuard.MERGE_REQUEST_FACT_SOURCE`，会让平台原有 MR 构建因 BI 提交增强表缺失而失败。
- 本地运行态中配置 1 使用 `RECOMMENDED`，但两张新增提交来源表尚无同步状态，`merge_request_commit_fact` 为 0 行；旧判断只看配置模式，错误返回 52 个 `commitCount=0` 的 `READY` 时间桶。当前范围已有 466 个 MR，因而这不是合法“零提交”，而是提交事实尚未发布。
- CAT 镜像实现新增第二个包级测试构造器后，Spring 在全量应用上下文中无法从两个构造器中选择注入入口，导致多个无关上下文测试级联报 `No default constructor found`。生产构造器现已显式标注 `@Autowired`；定向上下文回归与 1118 项全量测试均通过，未增加默认构造器或兼容装配分支。
- CAT 设置页当前直接暴露连接超时、读取超时、单次响应上限和每阶段快照保留数，迫使业务管理员承担传输与存储保护参数；映射表只合并数据库已保存值，未消费手册明确返回的 `defaultProject`、`curVersion` 和阶段树语义，因此首次目录发布后仍需逐格人工填写。

## 方案与步骤

1. [已完成] 删除废弃 BI 外部 Provider、Payload 和专属测试；将通用外部 API 测试夹具改为中性数据集名，验证通用框架不变。
2. [已完成] 新增 BI 领域状态、版本、追溯、页面 DTO、计算器和基础查询端口；建立 Runtime Manager/Factory、失败收敛和包依赖架构测试。
3. [已完成] 新增产品版本和六页 API；实现评审、编码、系统测试平台只读仓储及 CAT 未接入端口，逐节返回 `READY/EMPTY/NOT_APPLICABLE/INCOMPLETE/ERROR`。
4. [已完成] 新增 `bi.dashboard.view`、`bi.dashboard.download` Flyway 权限，并接入平台一级导航、六页路由和统一权限守卫。
5. [已完成] 在独立 `frontend/src/features/bi-dashboard` 内建立 ECharts Runtime、图表基类、15 个具体模板类、状态壳、筛选、完整数据 PNG 和六页组合视图。
6. [已完成] 为计算、Runtime、权限、API、路由、图表 option、状态和 PNG 完整数据模式补齐自动测试。
7. [已完成] 修复响应归属、1366px 高基数布局、标签/图例重叠和 PNG 空图，并完成 PC 浏览器验收。
8. [已完成] 修复版本解析及计算器对冲突、缺失和非法输入的完整性处理，并完成后端、前端、单 JAR 与仓库门禁验证。
9. [已完成] 更新 BI 进度和接入状态，删除已完成公式审计计划，清理本工作单元视觉验收产物并完成最终门禁检查；运行产物位置项仅保留已记录的 8 个既有根日志阻塞。
10. [已完成，待内网联调] 按当前用户确认让单元/集成页面通过各自显式 `testingPhaseId` 复用 CAT 四接口协议；实现强类型 DTO、受限 HTTP 客户端和独立阶段映射。手册没有的用例计数保持为空，模块功能达标数使用独立计数单位；来源身份补齐前真实数据可查看但页面保持 `INCOMPLETE` 且不可下载。
11. [已完成全量自动验证，待登录终验] 统一 BI JDBC 整数边界读取，兼容驱动返回的 `Integer`/`Long` 等 `Number` 且保留数据库 `NULL`；页面级 `ERROR` 使用单一明确错误状态和重试入口，不再渲染为区块不完整。
12. [已完成] 已删除编码/系统测试对虚构稳定作者、模块和延期状态字段的硬依赖；接入平台真实来源维度，显式承载缺失成员，并补齐模块一级/P1/P2 修复率、原因分类、延期原因与完整区块状态回归。全量自动验证、文档同步和六页浏览器终验均已完成。
13. [已完成] 编码页已跟随平台唯一兼容模式读源；轮次提交总数已改为有效缺陷去重数并校验守恒；人员负荷已按当前指派人统计总数、已修复数和待修复数，实际修复人不再承担未关闭议题归属；自动验证和浏览器终验均已完成。
14. [已完成调查] 已复现需求、设计、编码三页大面积 `INCOMPLETE`，取得数据库覆盖率、兼容同步映射和 Adapter/计算器拒绝条件证据；本地评审页数缺口按用户确认固定为内网复验项，不增加补零或本地分支。
15. [已完成] 以失败回归测试驱动编码强类型稳定身份、来源适配语义和设计独立度量完整性修复。
16. [已完成实现与全量回归] 将 GitLab `merge_request_diffs`、`merge_request_diff_commits` 纳入推荐同步和来源血缘，建立稳定提交事实并让 BI 按日/周聚合提交趋势与频次；提交增强已从平台通用 MR 硬依赖拆出，既有自定义代码走查配置保持可用。
17. [已实现，待内网数据验收] 新建 BI 独占人工走查兼容表和独立同步发布链路，完整映射 `spider_crowncad_data` 结构化字段；未读写、双写或改造现有 `code_review_match_mode_records`，同步失败保留上一版 BI 快照且不影响平台其它兼容页面。
18. [已完成本地验收] 自动化全量验证和 `18080`/`18181` 登录态验收已完成；“配置已启用但事实未发布”不再展示全零提交图，API 返回 `INCOMPLETE` 且提交点为空。内网再验证真实 GitLab 提交表和老平台 MySQL 覆盖率。
19. [已完成实现与定向回归] CAT 客户端、显式产品版本/阶段映射、单元/集成页面带单位计数语义已完成；后端 BI/CAT 85 项、前端 BI 29 项、类型检查、ESLint、Checkstyle 和 SpotBugs 均通过。CAT 来源身份和用例计数契约仍待内网补充。
20. [已完成实现、全量自动验证与本地浏览器验收，待内网数据验收] 建立 CAT 专属配置、同步运行、快照、模块、功能、原始响应和当前发布指针；实现定时全量、手动全量、全量补偿及失败保留上一版；系统设置增加 CAT 镜像区块，单元/集成页面删除请求时直连并只读发布快照。
21. [已完成] 收敛 CAT 设置页责任：隐藏后端传输/保留策略参数；新增服务端映射建议并在前端优先展示已保存映射、否则预填唯一系统建议；补齐歧义、不覆盖和保存后的回归测试，更新 CAT 数据契约、产品和架构说明并完成定向构建与 1920×1080 页面验收。

## 决策记录

- 已选：一个公共页面响应信封承载来源身份、整页状态、分区状态和追溯；页面数据本体继续使用需求/设计、编码、测试达成、系统测试四种强类型 DTO，不使用无类型 Map。
- 已选：图表以小型抽象基类统一主题、tooltip、缩放、对比色和导出模式，每个 `product.md` 模板 ID 对应一个具体类；页面只组合实例和显式变体。
- 已选：产品版本支持 API 是六页之外的同域辅助入口；六个业务页面仍保持独立入口和独立来源版本。
- 已选：PNG 在浏览器端使用 ECharts 导出；下载前由图表类生成关闭窗口裁剪和 dataZoom 的完整数据 option，后端权限通过专用下载授权探针校验，不上传页面像素。
- 否决：继续扩展 `BiDashboardDatasetProvider`、直接复用平台 `EChartPanel`/看板 Service、以模块名称生成哈希 ID、CAT 空数据回退平台、为旧静态 mock 保留双轨。
- 已选：区分“跨系统业务实体 ID”和“单一来源快照内的分组维度值”。本轮图表只需要后者；使用源字段经空白规范化后的值分组并保留原显示值，不生成哈希或伪 ID。核对表英文名是需求语义候选，不是未经代码验证的物理字段契约。
- 已选：平台唯一兼容模式是 BI 的读源权威，BI 不拥有模式配置。轮次提交总数由轮次有效缺陷计算。人员负荷按当前指派人统计，合法修复评论作者只表示实际修复历史。
- 已选：提交明细作为平台 GitLab 事实能力扩展，不建立 BI 私有 GitLab 同步任务；人工走查使用 BI 独占的老库同步表和发布事务，只复用平台全局兼容开关及老库连接配置，不复用现有兼容记录表。两者都在 BI 基础设施边界映射为领域记录。
- 已选：CAT 单元/集成页面共用一个强类型传输客户端，但产品版本映射必须分别配置 `unitTestingPhaseId` 与 `integrationTestingPhaseId`；不按阶段中文名、当前版本或默认项目推断。
- 已选：测试达成计数改为可选的带单位值。当前 CAT 模块/整体只可提供“达标功能数 / 统计功能数”，功能级只提供通过率；任何一层都不得复用这些字段伪装“通过用例数 / 执行用例数”。
- 已选：CAT 数据改为 BI 专属镜像快照。同步与页面读取只有一条权威路径；CAT 原始响应和规范化事实按运行追溯，只有完整范围通过校验后才切换当前发布指针。当前协议只能执行全量快照，定时同步和补偿共享同一全量采集实现但保留不同触发原因。
- 已选：CAT 目录标志和名称只用于生成可编辑建议，不直接替代权威稳定 ID 映射。建议必须逐字段唯一；数据库已保存映射不被后续目录建议覆盖，用户确认保存后继续由现有目录一致性校验保护。
- 已选：CAT 地址和同步时间策略属于页面管理员配置；连接/读取超时、响应大小和快照保留数量属于后端部署级保护策略，前端不展示也不允许用户调整。
- 否决：把 CAT 当作 GitLab 数据库表注册、让 CAT 跟随兼容模式、同时保留实时直连和镜像回退、根据响应哈希伪装上游版本、在没有变更水位时提供“增量同步”按钮。

## 接口契约

- `GET /api/bi/versions`：返回稳定产品版本 ID、业务键、显示名和默认版本。
- `GET /api/bi/requirements?productVersionId=...`
- `GET /api/bi/design?productVersionId=...`
- `GET /api/bi/coding?productVersionId=...&granularity=day|week&source=all|cc|dgm&repositoryId=...`
- `GET /api/bi/unit-test?productVersionId=...`
- `GET /api/bi/integration-test?productVersionId=...`
- `GET /api/bi/system-test?productVersionId=...`
- `POST /api/bi/download/authorize`：校验查看与下载权限，并确认下载对应的页面、图表和来源版本；PNG 像素由当前已授权页面在本地生成。
- 页面响应包含 `pageKey`、`status`、`sourceVersion`、`snapshotId`、`ruleVersion`、`generatedAt`、`sections`、`traces`、`data`；`generatedAt` 不充当来源版本。
- CAT 已说明的下游接口为项目目录、版本/阶段树、模块统计和功能下钻四个 POST 查询；BI 运行时使用后面三个接口校验显式映射并读取页面，项目目录只用于运维核对。完整路径、字段映射及缺口以 `docs/bi-dashboard/data-contracts.md` 为准。
- `GET /api/bi-cat-mirror/settings` 在已保存 `mappings` 之外返回 `mappingSuggestions`；建议字段允许为空并携带建议状态，前端不得把建议冒充已保存配置。`PUT /api/bi-cat-mirror/mappings` 仍只接受用户确认后的完整稳定 ID 映射。
- GitLab 提交事实表固定为 `merge_request_commit_fact`，稳定提交身份为 `source_instance + project_id + commit_sha`；物理记录保留 `merge_request_id` 关系，避免同一提交参与多个 MR 时被定向发布误删。
- BI 人工走查兼容域固定使用 `bi_code_review_compatibility_records`、`bi_code_review_compatibility_records_loading` 和 `bi_code_review_compatibility_sync_state`。三者只服务 BI 人工走查事实族，不读取、写入、回填或双写 `code_review_match_mode_records`；发布事务只替换 BI 目标表并推进 BI 自有 `published_version`。编码页其它图表继续按原契约读取现有兼容表。

## 风险与假设

- CAT 手册仍未给出认证、用例计数、通过率精确公式和快照能力；当前按手册实现无认证只读 POST，并保留路径配置以适配内网部署前缀。若内网实际要求认证或返回结构不同，必须补充正式契约后修改单一客户端，不增加旁路或回退链。
- CAT 真实目录尚未在本地发布，建议算法只能用受控目录夹具验证；内网仍需确认实际版本和阶段命名能否形成唯一建议，任何歧义都应留给用户选择而不是扩大名称匹配范围。
- 评审页数、扫描状态、注释率和稳定模块映射缺失会使部分指标不可计算；真实缺口必须对用户可见。
- `merge_request_diff_commits` 会在同一合并请求的多个 Diff 版本中重复出现提交；事实构建必须限定权威 Diff 并按来源、项目和 SHA 去重，不能直接对原表计数。
- 外网本地 GitLab 提交明细是最小验证数据，老平台 MySQL 不可达；本地只证明同步、去重和计算逻辑，真实覆盖率必须在内网兼容模式常开环境验收。
- 外网本地 GitLab 评论镜像不完整，只能验证分类和聚合逻辑；`fix_user`、`reason_category` 及原因词典覆盖必须在内网同步完成后验收，不能以本地覆盖率判断生产数据质量。
- 同 JVM 不隔离 OOM、死锁和共享数据源耗尽；本工作单元只处理普通初始化/查询异常收敛，不提前引入线程池、缓存或资源配额。
- 平台工作树已有用户未提交文档和运行产物；本工作单元不得恢复、删除、暂存或提交无关内容。
- 浏览器端 PNG 权限授权不能替代数据接口权限；授权响应只允许当前已加载来源版本，不能暴露额外数据。
- 运行产物位置门禁被仓库根目录既有 8 个 `.tmp-*.log` 阻塞；这些用户文件不属于 BI 工作单元，不能为追求全绿擅自移动或删除。
