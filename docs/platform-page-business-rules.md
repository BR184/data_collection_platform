<!-- DOC_STATUS_START -->
> 文档状态：常驻业务规则
> 说明：数据采集平台通用页面、统计口径、规则说明、筛选条件、导出和下钻都必须遵守本文件。后续业务规则变化必须先更新本文件，再改代码和测试。
<!-- DOC_STATUS_END -->

# 数据采集平台页面业务规则总表

## 1. 使用规则

1. 本文件是平台页面设计和开发的常驻业务规则入口，适用于质量看板、评审数据、代码走查、系统测试、客户问题和系统设置中涉及业务数据展示的页面。老平台独立“集成测试”模块不恢复。
2. 页面上的统计、列表、筛选、导出、规则说明、下钻明细、非法数据判定和刷新状态，必须与本文件保持一致。
3. 如果代码、旧文档、页面文案和本文件冲突，以本文件为准；如果业务方确认口径变化，必须先更新本文件并补充测试。
4. 任何新增页面必须明确写出：统计范围、过滤规则、数据来源、去重口径、空值展示、占比规则、导出规则和下钻规则。
5. 不允许在页面、SQL 或前端临时逻辑里重复实现一套隐藏口径。业务语义应优先沉淀到事实层、规则层和共享查询层。

## 2. 来源和冲突处理

本文件整合以下规则来源：

- `C:\Users\admin\Desktop\数据采集平台规则汇总\数据采集平台规则汇总.txt`
- `C:\Users\admin\Downloads\产品客户问题响应管理机制.mm`

已统一的冲突：

1. 客户问题最长解决周期：管理机制文件写“15 个工作日”，平台规则汇总和当前事实层测试采用“最长 18 天”。平台页面和自动判定统一使用 18 天；15 个工作日只作为管理背景，不作为页面计算口径。
2. 客户问题响应判定：管理机制提到标签和调研模板，平台自动判定以评论区出现 `# 问题调研情况说明` 为已响应依据；标签可作为展示信息，但不能替代模板判定。
3. 客户问题闭环状态：管理机制写“需求如此”，平台规则和现有标签同时出现“需求如此/设计如此”。页面判断需兼容两种文案；新增规则优先使用“需求如此”。
4. 系统测试横向对比：本次源规则明确包含 CrownCAD、DGM 代码走查数据以及评审数据；旧文档中“不包含 DGM”的说法作废。

## 3. 全局统计和展示规则

1. 议题严重程度分为一级缺陷、二级缺陷、三级缺陷；紧急程度分为 `P1`、`P2`、`P3`，两套标签体系不能混用。
2. 如果一个议题包含多个模块标签：
   - 统计“总计”类指标时，同一议题只能计 1 次。
   - 按模块统计或效率统计时，可分别计入命中的模块，页面必须说明这是模块归属口径。
3. 展示和筛选维度必须使用事实层已归一化字段，不允许从 GitLab 原始标签、标题、页面名称或任意文本中临时猜测业务字段。
4. 标签派生字段必须对齐老平台解析规则：
   - 议题模块严格对齐老平台 `ParseDocumentServiceImpl.parseLabelMapByList(...)`：只识别中文全角 `模块：X`、`工具箱：X`，`工具箱` 归入模块维度；ASCII 冒号和连字符形式不生成议题模块。测试阶段始终是独立维度，不能作为模块候选。
   - 除模块外的议题标签前缀仍按各自已确认的事实字段规则解析，不得因为模块口径收紧而改变项目、状态、测试阶段、严重程度或类别等字段。
   - 议题裸标签只允许按老平台规则识别测试阶段关键字、`P1/P2/P3` 和延期原因枚举。
   - 合并请求模块严格对齐老平台 `CCModuleFetcher` / `DGMModuleFetcher`：识别 `模块[：|-]X`、`工具箱[：|-]X`，即中文全角冒号、竖线或连字符；不把 ASCII 冒号形式扩展为代码走查模块。多模块按原顺序保存并参与统计。
   - 裸标签、`XX模块` 后缀、导航名、页面名、统计模块名和未被上述前缀规则收束的文本都不得反推为模块、项目或其他业务维度。
5. GitLab 原生字段派生维度必须来自事实字段，例如项目、负责人、处理人、审查人、合并人、目标分支、里程碑；不能让用户手动输入后生成新维度。
6. 页面筛选下拉使用已归一化事实字段生成，按老平台下拉行为过滤空值、`未设定...`、`未标注...`、`GitLab接口报错` 等占位值；含 ` & ` 的组合值需要拆分后去重展示。
6.1 页面筛选优先级统一为：单独展示的范围下拉框高于条件筛选，条件筛选高于快速筛选。高优先级筛选已经覆盖的业务维度，低优先级筛选不得继续提供同一维度入口；例如页面顶部已有项目或测试阶段切换时，条件筛选和快速筛选都不得再展示项目或测试阶段。用户在快速筛选中已有取值后，又在条件筛选中完整设置同一业务维度时，页面必须提示存在重复筛选，并临时禁用、高亮对应快速筛选；若用户继续执行查询，则由条件筛选接管该维度，快速筛选项隐藏且不再参与查询。
6.2 `issue_fact.bug_status` 严格对齐老平台 `ParseDocumentServiceImpl.parseLabelMapByList(...)`：只从中文全角 `状态：X` 标签提取测试状态，多个状态以 ` & ` 合并，没有此类标签时保存“未设定议题状态”；裸状态文本、ASCII 冒号、连字符以及 GitLab 议题开闭状态均不得写入本字段。议题是否关闭只由 `issue_state/closed_at_source` 表达。测试状态的业务语义为同一议题上的有序状态成员集合：读取时以 `、`、`，`、`,`、`&` 分隔、去空和去重；`/` 是状态名称的一部分，`已修复/完成` 必须保持为一个成员。状态候选、普通筛选、标签组筛选、记录表标签和统计下钻均按成员处理，不拆分议题事实记录、不改写导出事实文本。普通筛选选择“已修复”时，仍按老平台口径命中含“已修复”“待合并”或“未更新”的成员；其他状态按成员匹配。
6.3 产品版本统一使用同一套归一化规则，适用于评审、系统测试、代码走查、客户问题和经授权的外部数据集：
   - 有效且已登记的 `product_version_id` 是权威值；只有缺失、无效或历史兼容数据没有该值时，才从项目名称或等价的上游项目字段推导。各页面、统计服务、快照和导出不得各自维护版本别名或特殊映射。
   - 文本推导先做 Unicode 兼容归一化、大小写归一化和空白清理，再按已登记产品版本的主体成员匹配。版本主体以 `YYYYR<n>` 为核心，`CC` 前缀、中文描述、括号、连字符和空格不改变主体；例如 `CC2026R4`、`2026 R4`、`CC信创版（2026R4）`、`二次开发平台（2026 R4）研发项目` 均命中 `CC2026R4`。
   - 一个项目名称包含多个版本成员时，按成员分别归属和统计，同一版本内只计一次；例如 `CC2025R1&R2` 同时归属 `CC2025R1`、`CC2025R2`，不能再使用旧的 `CC2025R1 -> CC2025R1&R2` 特例映射，也不能把组合文本当成第三个版本。
   - 未命中任何已登记版本主体、主体无法完整解析或版本成员存在歧义时，记录标记为“版本未确定”，不进入经授权的外部统计数据集或跨版本汇总；记录查询和非法数据诊断可以保留该记录供修正追溯。
7. 模块数据来源默认是全量议题中出现过的模块信息，除非页面单独声明只取当前项目或当前阶段。
8. 当前项目或测试阶段下没有对应模块数据时，数量显示为 `0`，不能隐藏模块行。
9. 如果页面需要默认模拟老平台模块展示范围，不得在代码中写死模块名单；应通过用户可维护的默认模块范围标签组实现，且事实层仍保留完整模块数据。
10. 占比显示规则：
   - 分子为 `0` 且分母不为 `0` 时，显示 `0`。
   - 系统测试缺陷汇总类统计中，分母为 `0` 时显示 `/`。
   - 评审、代码走查、缺陷原因类统计中，分母为 `0` 时显示 `0`。
11. 导出必须使用与页面一致的统计范围、过滤规则、排序规则和空值规则。导出不能绕过页面口径。
12. 所有正式导出默认使用 `.xlsx`。记录类页面必须优先对齐老平台 Excel BO 字段顺序；统计类页面若老平台存在专用导出 DTO 或模板，必须使用页面专用 workbook 模板。只有老平台没有对应导出格式且业务规则未另行指定时，才允许使用平台通用统计看板 workbook 导出。CSV 只能作为内部调试或明确声明的兼容格式，不能作为正式导出格式。
13. 导出文件需要展示标签组名和导出时展开的成员快照，例如“负责人 = 核心人员（标签组：张三、李四）”。为保持老平台主表字段顺序，记录类 Excel 可将该快照写入独立“导出条件”工作表；快照只写入导出文件，不写入数据库。
13.1 下载按钮文案和下载文件名必须对齐老平台；老平台 `createDownload(data, fileName)` 会在传入文件名后追加 `.xlsx`，新平台显示给用户的最终文件名需等价。当前已实现页面按下表执行：

| 页面 | 老平台按钮文案 | 新平台按钮文案 | 下载文件名规则 |
|---|---|---|---|
| 系统测试缺陷汇总 | 下载当前 | 下载当前 | `{测试阶段}-系统测试缺陷汇总统计.xlsx` |
| 系统测试缺陷汇总议题数据 | 下载议题数据 | 下载议题数据 | `{测试阶段}-全量议题数据.xlsx` |
| 系统测试缺陷汇总横向对比 | 系统测试横向对比excel下载 | 系统测试横向对比excel下载 | `{测试阶段}-系统测试数据横向对比数据.xlsx` |
| 系统测试缺陷原因分析 | 下载 | 下载 | `{测试阶段}-缺陷原因统计表.xlsx` |
| 系统测试申请延期缺陷分析 | 导出数据 | 导出数据 | `{测试阶段}申请延期缺陷原因分析.xlsx` |
| 系统测试议题阶段统计 | 无 | 无 | 老平台无导出按钮，新平台不得展示导出入口，也不得提供通用统计 workbook 作为替代。 |
| 系统测试议题查询 | 下载查询数据 | 下载查询数据 | `多元议题查询结果.xlsx` |
| 系统测试非法数据 | 下载查询数据 | 下载查询数据 | `多元议题查询结果.xlsx` |
| 系统测试议题多元看板 | 每张图自带下载 | 图表卡片下载 | `{项目名称}-{测试阶段}-{图表名称}.xlsx` |
| 客户问题缺陷汇总 | 下载当前 | 下载当前 | `{里程碑}-客户问题缺陷汇总统计.xlsx` |
| 客户问题缺陷汇总议题数据 | 下载议题数据 | 下载议题数据 | `{里程碑}-客户问题全量议题数据.xlsx` |
| 客户问题缺陷原因分析 | 下载 | 下载 | `{里程碑}-客户问题缺陷原因统计表.xlsx` |
| 客户问题缺陷非法数据 | 下载查询数据 | 下载查询数据 | `多元议题查询结果.xlsx` |
| CC_PRODUCT议题 | 下载查询数据 | 下载查询数据 | `（全量）CCProduct议题查询结果.xlsx` |
| 代码走查非法数据 | 下载代码走查非法数据 | 下载代码走查非法数据 | CC 为 `{项目名称}_{非法类型}_{模块}_{目标分支}_{作者}_{MR编号}_{所属项目}_{合并时间范围}代码走查非法数据.xlsx`；DGM 为 `{项目名称}_{非法类型}_{模块}_{目标分支}_{作者}_{MR编号}_{合并时间范围}{当前时间}_内核代码走查非法数据.xlsx`；空筛选项不参与拼接。 |
| 评审数据管理模板 | 下载 | 下载 | `模板文件.xls` |
| 评审数据管理列表 | 导出评审列表 | 导出评审列表 | `评审数据{当前时间}.xlsx` |
| 评审数据管理问题列表 | 导出问题列表 | 导出问题列表 | `评审问题详情.xlsx` |
| 单条评审问题详情 | 下载 | 下载 | `问题详情.xlsx` |

13.2 下载文件内部格式、sheet 表头、字段顺序和列顺序也必须对齐老平台，不得只对齐按钮文案和文件名。系统测试模块当前按以下老平台 DTO/页面格式执行：

| 页面 | 老平台格式来源 | 新平台对齐要求 |
|---|---|---|
| 系统测试缺陷汇总 | `ModuleTableRow` | 导出 `.xlsx` 使用老平台 `ModuleTableRow` 的 `@ExcelProperty` 顺序和多级表头：`模块名`、`一级缺陷/分类/回退`、`一级缺陷/分类/挂机`、`一级缺陷/分类/其他`、各严重程度修复数量/数量/修复率、P1/P2/P3、模块总缺陷数、占比、延期占比、已修复/未更新、修复率、关闭率、未关闭、申请延期、复测未通过、新发议题、遗留率。被老平台 `@ExcelIgnore` 或注释掉的列不得导出。 |
| 系统测试缺陷汇总议题数据 | `IssueExcelBo` | 导出字段顺序对齐老平台 `IssueExcelBo`；即使新平台事实层暂缺具体原因、修改方案、影响功能等字段，也必须保留空列，不得删除或重排。 |
| 系统测试缺陷原因分析 | `ExportModuleAndCauseTableRow` | 导出 `.xlsx` 使用两级表头：第一列 `模块`，后续按 `需求问题`、`设计问题`、`编码规范`、`打包问题`、`依赖问题`、`精度问题` 分组，叶子列顺序对齐 `ExportModuleAndCauseTableRow`。不得额外插入“需求归类/设计归类”等新平台中间表头。 |
| 系统测试申请延期缺陷分析 | `DefectAndPhaseTableRow` | 导出 `.xlsx` 使用单行表头，顺序为 `伦次`、`一级缺陷`、`二级缺陷`、`三级缺陷`、`建议类缺陷`、`总计`。其中 `伦次` 是老平台导出字段原文，不能擅自改成“延期原因”。 |
| 系统测试非法数据 | `IssueExcelBo` + 非法类型 | 导出字段先完整对齐 `IssueExcelBo`，最后追加 `非法类型`，字段顺序不得与页面临时列或新平台详情列混用。 |
| 系统测试横向对比 | 老平台横向对比导出 DTO/页面 | 导出必须保留老平台横向对比的多级表头、模块维度和字段顺序；不得退回通用统计看板 workbook。 |

14. GitLab 显示的议题创建时间按 GMT 处理；计算响应和解决效率时必须转换为北京时间。
15. 页面级“刷新最新数据”不是强实时承诺。镜像表刷新成功不等于页面事实层已刷新，页面必须能区分镜像刷新、事实刷新和失败状态。
15.0.1 代码走查非法数据和系统测试页面的历史“最近同步”仍可按老平台 Mongo `ScheduledTimeRecord` 对齐：按 `typeName`、`typeValue`、可选 `projectId` 查询并按 `startTime` 倒序取最新一条；代码走查 CC 使用 `mergeRequest / CrownCAD / 9`，DGM 使用 `mergeRequest / DGM / 79`，系统测试按当前测试阶段使用 `issue / {testingPhase} / 9`。客户问题统计、客户问题非法数据、`CC_PRODUCT议题` 和延期问题改为读取本地最新成功 `ISSUE` 事实构建任务的完成时间：页面事实层才是当前展示数据的唯一来源，老平台 Mongo 的 `issue / CCProduct / 325` 调度记录不得覆盖或伪装为新平台本次刷新结果。
15.1 GitLab 直连镜像只保留一套本地 ODS 表，表名统一为 `ods_gitlab_<source_table>`，例如 `ods_gitlab_labels`、`ods_gitlab_projects`、`ods_gitlab_merge_requests`。系统设置中的 GitLab 数据镜像设置不再提供多数据源切换和来源标识配置；后端不再生成或读取 `ods_gitlab_cc_*`、`ods_gitlab_default_*`、`ods_gitlab_dgm_*` 等来源前缀镜像表。历史测试库可清理后重新导入 GitLab 数据，不要求平台迁移旧来源前缀镜像表。DGM 后续需要的 MR、静态扫描等数据通过独立 webhook 或外部导入链路进入事实层/外部指标表，不能恢复 GitLab 直连镜像多表切换。
16. 统计类页面下钻明细中，GitLab 标签或由 GitLab 标签派生出的字段（例如模块、严重程度、测试状态、延期原因、测试阶段、缺陷原因、原始标签列表）应按 GitLab 标签风格展示为彩色标签块。这只是展示层规则，字段值仍是普通文本；筛选、排序、导出、接口契约和事实层口径不得因此改变。若明细接口未提供 GitLab 原始颜色，前端使用标签文本生成稳定的兜底颜色。
17. 统计类页面下钻明细可以使用展开行降低主表横向拥挤，但不能因此丢失老平台明细中可见的数据。主表用于展示编号、标题、核心状态、严重程度、人员和关键时间等高频扫描字段；展开区用于承载模块、项目、阶段、里程碑、原始标签、长文本、完整时间和辅助分类等低频字段。主表和展开区必须复用同一份明细记录和同一套字段渲染逻辑，不能另起查询口径。
18. 统计类页面下钻明细中的议题编号、合并请求编号等 GitLab 跳转必须按事实记录的 `projectId + iid` 生成，并使用当前唯一 GitLab 镜像配置中的 GitLab Web 访问地址和 `ods_gitlab_projects` / `ods_gitlab_namespaces` 项目路径。不得只依赖平台当前 host、全局默认 `localhost` 或临时来源前缀镜像表猜测；缺少 GitLab Web 地址或项目路径时应降级为普通文本，不能生成错误链接。事实层仍可保留 `sourceInstance` 表示外部来源，但它不再驱动 GitLab 直连镜像表名。
19. 统计类页面下钻表的长文本列（如议题标题）必须有稳定宽度边界。允许用省略、tooltip、展开区或横向滚动承载完整内容，但不能让单个长文本列无上限吸收弹窗剩余宽度，导致其他列被挤压或表格布局不断扩张。
20. `issue_fact.reason_category` 对齐老平台 `spider_issue_data.cause`，只保存从第一条合法固定模板评论中按老平台 `IssueServiceImpl.getCause(...)` 解析出的原因段文本：评论首行必须包含原始标题 `### 1、修复状态`，并在规定原因段内只消费 `[x]` / `[X]` 勾选项。缺少标题、标题不在首行、粗体改写或缺少原因段边界均按非法模板处理，`reason_category` 保持空并进入“未按照模板回复”判定；不得从 GitLab 标签、普通评论或全部评论 `raw_payload` 推断、补齐原因。缺陷原因统计和非法“缺陷原因不唯一”均基于已解析的原因段文本做 `like`/分类匹配，不能只保存单一归一化原因枚举后丢失老平台原因段文本。
21. 已登录用户的会话不因前端无操作时长而弹出确认、自动退出或失效。登录态只允许由用户主动退出或服务端明确失效；接口真实返回未登录状态时，前端仍需恢复游客态并提示重新登录。

### 3.1 老平台页面数据链路定位索引

本索引用于从老平台源码中逐页定位筛选规则。老平台规则不是只写在一个后端接口里，常同时散落在 `Router.js` 路由、Vue 组件默认值、`request/issueApi.js` 请求参数、Controller 默认参数、`SpiderIssueDataQueryBuilder` / `ProjectIssueInfoQueryBuilder` / `SpiderCrowncadQueryBuilder`、DAO 方法和下钻组件中。修复新平台数据差异时，必须先按本索引在老平台逐段确认规则，再把确认后的口径沉淀到新平台事实层、规则层或共享查询层。

本索引不展开多元看板、个人质量图表、集成测试看板和纯图表统计页；这些页面若后续出现明确的新老数据差异，再单独补链路。系统测试、客户问题、代码走查非法数据、评审数据、阶段/里程碑设置和后台记录管理页需要保留链路定位。

研发质量看板对齐老平台 `PageNinePersonalQuality/NinePersonalQuality.vue` 的已接入指标：需求评审缺陷密度、设计评审缺陷密度、CC 代码走查缺陷密度、DGM 代码走查缺陷密度、发布缺陷遗留率、开发缺陷遗留率和新发缺陷修复率。老平台的集成测试通过率不属于该页面数据，不能以 0 或其它指标替代。下方图表包括按走查人统计代码走查缺陷密度、按被走查人统计代码走查缺陷密度、按修复人统计缺陷数、代码提交频次、指派人剩余缺陷数量。按修复人统计缺陷数只按非空且合法的 `fix_user` 分组，空值、`无合法评论` 和 `未设定...` 占位值不生成“未标注修复人”聚合柱。DGM 只进入顶部 DGM 代码走查密度以及走查人、被走查人、代码提交频次三张代码走查图，不进入按修复人和剩余缺陷两张系统测试图。代码走查图表在兼容模式读取老平台 MySQL 兼容快照，正式模式统一读取 `code_review_formal_records`；该视图对 CC/DGM 分别优先使用已交接的老平台事实，未交接时回退到对应 GitLab 正式事实。两种读源的表名、来源标识、项目别名和 MR 去重键必须集中隔离，兼容分支统一标注 `//兼容模式-MatchMode`，不得让兼容项目名或兼容表判断渗入正式查询。老平台未关闭议题状态为 `open`，新平台事实层保留 GitLab 语义为 `opened`，质量看板中表达“未关闭议题”的统计必须同时识别 `open` 和 `opened`。

老平台代码走查数据转正式数据是数据所有权交接，不是页面显示开关。老平台评审不参与批量交接：兼容态通过统一读模型合并正式评审与未被平台接管的快照，用户首次编辑快照时完整物化专家、描述、内容和问题项并归平台所有。代码走查交接后，非法数据、研发质量看板、多元看板、系统测试横向对比和外部数据集必须复用 `code_review_formal_records`，不得各自定义正式 CC/DGM 范围。

其他看板对齐老平台 `PageCodeWalkThroughInfo/PersonalQuality.vue`，展示且仅展示六类专题数据：功能缺陷数量、功能缺陷密度、质量达人榜、成员未修复缺陷率、发布缺陷遗留率、开发缺陷遗留率。该页面没有 DGM 数据源切换；其中功能缺陷密度和质量达人榜需要的代码新增行数固定取 CC 代码走查数据，其余统计只读取 CC 系统测试议题。集成测试数据不属于新平台当前模块或默认数据来源。图表可以使用更适合当前平台的 Apache ECharts 柱状图、排名图或趋势图，但数据种类、字段含义和计算口径不得与老平台分叉。

其他看板的细分口径如下：功能缺陷密度以需要代码走查的已合并 CC MR 按功能汇总去重新增行数，排除 `scan_status = 无需代码走查`，并以“系统测试议题功能名包含该代码功能名”汇总当前阶段缺陷；有代码行但缺陷为零的功能仍展示。质量达人榜成员范围来自可维护的 `quality_board_member_scopes`，分母是成员全部已合并 CC MR 的去重新增行数，分子按当前父级范围的每个子测试阶段分别统计修复人缺陷，逐阶段计算千行缺陷密度后取平均；成员无代码或无缺陷时保留零值行。发布缺陷遗留率与开发缺陷遗留率均为当前版本未关闭系统测试缺陷数 / 系统测试缺陷总数，仅识别 `open/opened` 为未关闭并排除已拒绝缺陷；开发缺陷遗留率不得读取或拼接集成测试数据。图表、详情和 Excel 必须共享同一行结果。

老平台公共散落点：

| 规则位置 | 老平台文件 | 需要重点核对的隐藏筛选 |
|---|---|---|
| 页面路由 | `webapp/src/Router.js` | URL 到 Vue 组件的映射；同名页面可能共用后端接口但前端默认参数不同。 |
| 请求封装 | `webapp/src/request/issueApi.js`、`webapp/src/request/CodeThroughDataApi.js` | 页面实际调用的 URL、HTTP 方法和参数名；例如客户问题按功能页面请求 `/dataAnalysis/getIssueCountByFunc`。 |
| 系统测试/客户问题统计入口 | `src/main/java/com/huayun/controller/DataAnalysisController.java` | `projectId` 默认值、`phase` 空值是否直接返回空、`milestone` 是否只作为可选参数透传。 |
| 议题记录/下钻入口 | `src/main/java/com/huayun/controller/IssueStaticDataController.java` | `filter`、`findByModuleAndTitle`、`findDelayIssue`、`findRespIssue`、`findIssueByFunction`、`getCCProductIssueInfo` 的参数默认值和不同 `projectId` 分支。 |
| 代码走查入口 | `src/main/java/com/huayun/controller/StaticDataController.java` | `/staticData/getIllegalData` 中 `name`、`projectName`、`targetBranch`、`defectInvestigationLevel`、数据源上下文和非法类型二次组装。 |
| 议题公共查询 | `src/main/java/com/huayun/service/SpiderIssueDataQueryBuilder.java` | `setQuery()` 默认 `notLike bug_status 已拒绝`；`setFilterRejected(true)` 额外排除 `category 功能屏蔽`；`setQueryFilter(true)` 调用公共排除；阶段、里程碑、模块、原因和标题规则都在这里拼接。 |
| CC_PRODUCT 记录查询 | `src/main/java/com/huayun/service/ProjectIssueInfoQueryBuilder.java` | `CC_PRODUCT议题` 使用独立表和独立 QueryBuilder，不走 `SpiderIssueData` 的客户问题统计范围。 |
| 公共排除 | `src/main/java/com/huayun/utils/QueryUtil.java` | 非 CC_PRODUCT 时排除 `功能屏蔽`、`已拒绝`、`建议`；所有项目排除 closed 的 `申请否决`、closed 的 `需求如此`。 |
| 代码走查查询 | `src/main/java/com/huayun/service/queryBilder/SpiderCrowncadQueryBuilder.java` | 总非法查询与指定非法类型查询互斥；默认 `status=MERGED`；`name` 为空、`CrownCAD` 或 `DGM` 时隐式限制 `merged_time > 2024-04-01 00:00:00`；排除 `module_name=无需标注`。 |
| 客户问题统计/下钻 DAO | `src/main/java/com/huayun/service/impl/SpiderIssueDataDAOImpl.java` | `getDelayIssue`、`getHasRespOrFixedIssue`、`getIssueByFunctionNameAndModule` 等下钻规则不完全相同；有的调用 `setQueryFilter`，有的只按项目/模块/里程碑/功能筛选。 |
| 评审数据 | `webapp/src/request/ReviewBoardAPI.js`、`src/main/java/com/huayun/controller/ReviewController.java` | 评审列表、导入、导出、问题详情导出、新增/删除/更新分散在同一组 `/review/*` 接口，字段顺序和身份键不能从图表统计反推。 |
| 阶段/里程碑设置 | `webapp/src/request/DefinitionTestingPhaseAPI.js`、`TestingPhaseService`、`MilestoneController` | 维护页影响系统测试父子阶段展开、默认阶段候选、客户问题里程碑候选和多个页面默认值；不是普通列表页。 |
| 后台记录管理 | `ActivityAPI.js`、`CrashReport.js`、`FeedBack.js`、`EnterpriseCheck.js`、`ProgramAPI.js` 及对应 Controller | 活动、崩溃、反馈、企业审核、程序审核等页面按自身请求文件定位筛选、分页、导出和状态更新规则，不套用议题/代码走查公共规则。 |

逐页老平台链路：

| 页面 | 路由/前端组件 | 前端默认值和请求 | 后端入口 | 查询与隐藏规则定位 |
|---|---|---|---|---|
| 系统测试缺陷汇总 | `/moduleTable`，`webapp/src/views/PageHome/ContentComponents/QuestionnaireInfo/ModuleTable.vue` | 调用 `getModuleTable`；系统测试页面使用 `phase` 和默认 `projectId=9`。 | `DataAnalysisController.getModuleTable(phase, projectId=9, milestone)` -> `SpiderIssueDataService.getModuleTable`。 | 统计查询继续落到 `SpiderIssueDataQueryBuilder` / DAO；一级缺陷下钻走 `IssueStaticDataController.findByModuleAndTitle`，标题分类在 `setBugNameQuery()`。 |
| 系统测试申请延期缺陷分析 | `/delayCauseInfo`，`webapp/src/views/PageDelayCause/DelayCauseInfo.vue` | 前端选测试阶段后调用 `issueApi.getDelayCause`，下钻复用 `ModuleTableDetail.getIssueInfoFromModule(..., true)`。 | `/dataAnalysis/delayCauseAndTestingPhase` 相关入口。 | 延期原因统计以 `DelayEnum` 和 `SpiderIssueDataDAO.getNumByDefectAndPhaseAndDelayCause` 为核心；仍要回到 `SpiderIssueDataQueryBuilder` 和 `QueryUtil.setQueryFilter` 查公共排除。 |
| 系统测试非法数据 | `/illegalIssueSearch` | 前端记录页按测试阶段、模块、非法类型、分页查询。 | `IssueStaticDataController.getIllegalIssue(phaseName, moduleName, illegalType, milestone, projectId=9)`。 | 先用 `TestingPhaseService.getByName(phaseName)` 展开阶段，再走 `SpiderIssueDataDAO.findIllegalIssue`；非法类型来自老平台 `illegal_list`/派生字段，不是页面临时判断。 |
| 系统测试缺陷原因分析 | `/getModuleAndCauseTable`，`webapp/src/views/PageStandard/ModuleAndCause.vue` | 调用 `getModuleAndCauseTable({ phase, projectId, milestone })`；系统测试空 `phase` 且 `projectId=9` 时后端直接返回空。 | `DataAnalysisController.getModuleAndCauseTable(phase, projectId=9, milestone)` -> `IssueService.getModuleAndCauseTable`。 | 原因映射在 `SpiderIssueDataQueryBuilder.setCauseQuery()`、`setMajorCauseQuery()`、`setMinorCauseQuery()`；包括新旧模板兼容词，如 `新增理解偏差/需求理解有误`、`编码逻辑：业务逻辑错误/编码逻辑错误`。 |
| 系统测试议题阶段统计 | `/getDefectAndPhaseTable`，`webapp/src/views/PageStandard/DefectAndPhaseTable.vue` | 前端传父级 `phase`。 | `DataAnalysisController.getDefectAndPhaseTable(phase)`。 | 后端 `TestingPhaseService.getByName(phase)` 展开子轮次，再逐轮次调用 `SpiderIssueDataDAO.getNumByDefectAndPhase(testingPhase, DefectLevelEnum, projectId=9)`。 |
| 系统测试议题查询 | `/issueSearch` | 前端默认不应传具体阶段；请求记录筛选入口。 | `IssueStaticDataController.filter(..., queryFilter, projectId=9)`。 | `SpiderIssueDataQueryBuilder.setQuery()` 永远先排除 `bug_status` 包含 `已拒绝`；页面默认 `queryFilter=false` 时不调用完整公共排除，但 Controller 仍调用 `.setFilterRejected(true)` 排除 `category 功能屏蔽`。 |
| 系统测试模块和分类表 | `/getModuleAndCategoryTable`，`webapp/src/views/PageStandard/ModuleAndCateGory.vue` | 前端先调 `getLabelPhase()`，默认取阶段列表第一项，再调 `getModuleAndCategoryTable({ phase })`；下钻传 `moduleName/phaseName/cause`。 | `DataAnalysisController.getModuleAndCategoryTable(phase)` 相关入口；下钻走 `ModuleTableDetail.getIssueInfoFromModule`。 | 与缺陷原因表相邻但表头按分类维度；阶段默认值来自前端第一项，不是后端默认；下钻原因仍走 `SpiderIssueDataQueryBuilder.setCauseQuery()`。 |
| 代码走查非法数据 | `/codeThroughDataTable`，代码走查 Vue 与 `CodeThroughDataApi.js` | 老平台请求 `/staticData/getIllegalData`；默认 `activeName=CC`、`nameSearch=CrownCAD`，`projectName` 是独立筛选，不能等同于 `name`。项目候选由 `DropDownLabelController.getProjectName(name)` 动态读取 `spider_crowncad_data` 中当前 `name` 的全部非空 `project_name`，不套非法记录、合并时间、模块或分支条件；排除 `未标注项目名` 和 `未设定...`，保留 `无需标注`，按 ` & ` 拆分去重，并为 CC/CrownCAD 补充 `广数CAM`、`CC2025R4`、`CC2026R1`、`CC 2025 R4&2026 R1` 四项。 | `StaticDataController.getIllegalData(targetBranch, name, moduleName, mergedUserName, author, issuableReference, illegalType, projectName, startMergedTime, endMergedTime, pageSize, pageNum)`。 | `spiderCrowncadDataService.getDevBranch(name,targetBranch)` 可能默认补 `dev`；`SpiderCrowncadQueryBuilder.setSearchIllegalQuery()` 拼总非法 OR 条件；`setIllegalTypeQuery()` 拼单非法条件且关闭总非法；末尾隐式加 `merged_time > 2024-04-01` 和 `module_name <> 无需标注`。项目候选口径只属于兼容模式-MatchMode 的 CC/CrownCAD 下拉，不得注入 DGM 或非兼容正式事实表候选；页面列表和筛选快照键必须包含 `formal` / `match-mode` 读模式，禁止切换后复用另一数据源的缓存。 |
| 客户问题缺陷汇总 | `/moduleTableCCProduct`，`webapp/src/views/PageHome/ContentComponents/QuestionnaireInfo/ModuleTableCCProduct.vue` | 前端 `projectId='325'`；先调 `/milestone/getAllMileStoneName`，默认取 `mileStoneList[0]`，再调 `getModuleTable({ projectId, milestone })`。 | `DataAnalysisController.getModuleTable(phase='', projectId=325, milestone)` -> `SpiderIssueDataService.getModuleTable`。 | 客户问题统计范围不来自系统测试阶段；明细下钻继续复用 `ModuleTableDetail`，部分入口走 `IssueStaticDataController.findByModuleAndTitle` 的 CC_PRODUCT 分支，加入 `submission_date >= 2026-01-01` 和 `setFilterRejected(true)`。 |
| 客户问题缺陷非法数据 | `/illegalIssueSearchCCProduct` | 前端按 `projectId=325`、里程碑、模块、非法类型分页查询。 | `IssueStaticDataController.getIllegalIssue(..., milestone, projectId=325)`。 | 阶段参数只作为旧入口兼容；真实范围应看 `milestone` 和 `projectId=325`；非法查询最终在 `SpiderIssueDataDAO.findIllegalIssue`，公共客户问题排除需对齐 `QueryUtil.setQueryFilter` 与 CC_PRODUCT 分支。 |
| 客户问题缺陷原因分析 | `/getModuleAndCauseTableCCProduct`，`webapp/src/views/PageStandard/ModuleAndCauseCCProduct.vue` | 前端 `projectId=325`；先取 `/milestone/getAllMileStoneName` 第一项，再调 `getModuleAndCauseTable({ projectId, milestone })`。 | `DataAnalysisController.getModuleAndCauseTable(phase='', projectId=325, milestone)` -> `IssueService.getModuleAndCauseTable`。 | 原因映射同 `SpiderIssueDataQueryBuilder`；客户问题里程碑和 2026 起始日期要在服务/DAO 侧确认，不能从系统测试 `phase` 推导。 |
| `CC_PRODUCT议题` | `/ccProductIssueTable`，`webapp/src/views/PageStandard/CCProductIssueTable.vue` | 调用 `findCCProductIssueInfo`；默认不是第一里程碑，而是全里程碑记录查询；页面默认 `submissionDate='2026-01-01'`。 | `IssueStaticDataController.getCCProductIssueInfo(...)`。 | 使用 `ProjectIssueInfoQueryBuilder` 和 `project_issue_info` 体系；默认 `.setSubmissionDate('2026-01-01')` 和 `.setFilterRejected(true)`，即只展示 2026-01-01 以来提交且 `bug_status` 不包含 `已拒绝` 的记录；不走 `SpiderIssueData` 客户问题统计公共排除。 |
| 客户问题历史议题查询备用入口 | `/issueSearchCCProduct`，`webapp/src/views/PageStandard/IssueSearchCCProduct.vue` | 老平台菜单中该入口被注释；页面自身默认 `projectId='325'`，调用议题查询请求封装 `findByModuleNameAndPhaseName(...)`，`queryFilter=false`。 | `IssueStaticDataController.filter(..., projectId=325)`。 | 这是历史/备用记录入口，不等同于当前 `CC_PRODUCT议题`；它走 `SpiderIssueDataQueryBuilder` 的 CC_PRODUCT 分支，会加 `submission_date >= 2026-01-01`，并与 `ProjectIssueInfoQueryBuilder` 链路不同。 |
| 客户问题延期问题 | `/getDelayIssueCCProduct`，`webapp/src/views/PageStandard/DelayIssueTable.vue` | 前端 `projectId='325'`；先调 `/milestone/getAllMileStoneName`，默认第一里程碑；再调 `/dataAnalysis/getDelayIssue`；下钻传 `moduleName/urgency/delayIssue/milestone/projectId`。 | `DataAnalysisController.getDelayIssue(projectId=325, milestone)` -> `SpiderIssueDataService.getDelayIssue`；下钻走 `IssueStaticDataController.findDelayIssue` -> `SpiderIssueDataDAO.getDelayIssue`。 | DAO 下钻规则：`project_id`、`module_name like`、`milestone`、`submission_date >= 2026-01-01`、可选 `urgency`、`delay_issue like`、排除 `illegal_list` 包含 `GitLab 接口报错`，再 `setQueryFilter`。 |
| 客户问题缺陷响应效率 | `/getIssueRespEfficiency`，`webapp/src/views/PageStandard/IssueRespEfficiency.vue` | 前端 `projectId='325'`；先取第一里程碑；再调 `/dataAnalysis/getIssueRespEfficiency`；下钻传 `moduleName/RespIssue/milestone/projectId`。 | `DataAnalysisController.getIssueRespEfficiency(projectId=325, milestone)` -> `SpiderIssueDataService.getIssueRespEfficiency`；下钻走 `IssueStaticDataController.findRespIssue` -> `SpiderIssueDataDAO.getHasRespOrFixedIssue`。 | DAO 下钻规则：响应看 `research_template_time is not null`；解决看 `bug_status like 已修复`；里程碑命中时加 `submission_date >= 2026-01-01`；再 `setQueryFilter`。 |
| 客户问题按功能展示缺陷数量 | `/getIssueByFunction`，`webapp/src/views/PageStandard/IssueShowByFunction.vue` | 前端 `projectId='325'`；先取第一里程碑；再调 `/dataAnalysis/getIssueCountByFunc`；下钻传 `moduleName/functionName/milestone/projectId`。 | `DataAnalysisController.getIssueCountByFunc(projectId=325, milestone)` -> `SpiderIssueDataService.getIssueCountByFunction`；下钻走 `IssueStaticDataController.findIssueByFunction` -> `SpiderIssueDataDAO.getIssueByFunctionNameAndModule`。 | DAO 下钻只按 `project_id`、`module_name like`、`milestone`、`submission_date >= 2026-01-01`、`function_name eq` 查询，当前老平台下钻方法没有调用 `setQueryFilter`，不能想当然套统计页公共排除。 |
| 议题测试阶段定义 | `/getDefinitionTestingPhase`，`webapp/src/views/PageDefinitionTestingPhase/DefinitionTestingPhase.vue` | 页面创建时调 `/testingPhase/getAll`；编辑阶段、子阶段、显示状态、排序分别调 `setShow/add/deleteByName/deleteByNameAndTestingPhase/sort/getByName`。 | `TestingPhaseController` / `TestingPhaseService` / `TestingPhaseDao`。 | 这是系统测试父级阶段、子轮次展开和默认阶段候选的来源；`getAllTestingPhase` 给其他页面下拉使用，不能从议题标签正则反推替代。 |
| CC_Product 里程碑维护/候选 | 客户问题各 Vue 组件调用 `getAllMileStoneName({ projectId })`，阶段定义请求中还有 `/milestone/getAllMileStone`。 | 客户问题统计页前端默认取返回列表第一项；`CC_PRODUCT议题` 默认不取第一里程碑。 | `MilestoneController` / 里程碑服务。 | 候选来自 CC_Product 项目真实里程碑；影响默认里程碑、URL、接口筛选、下钻和导出，不得混用系统测试阶段定义。 |
| 评审数据管理 | `/reviewBoard`，`webapp/src/views/PageReviewBoard/ReviewBoard.vue` 与 `ReviewBoardAPI.js` | 查询候选调 `/review/getAllModuleName`、`/review/getAllProjectName`、`/review/getReviewChargers`；列表调 `/review/getSearch`；导入导出调 `/review/importFile`、`/review/exportByParam`、`/review/exportProblemDetails`、`/review/exportById`。 | `ReviewController` 及评审服务/导入导出服务。 | 评审主记录、问题详情、描述、报告生成、附件下载分别有独立接口；导出字段顺序和导入身份键按评审规则章节，不从横向对比统计结果反推。 |
| 发布活动管理 | `/publishactivity`，`webapp/src/views/PagePublishActivity/PublishActivity.vue` 与 `ActivityAPI.js` | 按状态和活动名查询 `/activity/getPublishActivityList`；状态更新 `/activity/updateActivityStatus`。 | `ActivityController`。 | 后台活动记录页，只按活动状态/搜索文本/分页等自身条件处理，不套用议题或代码走查范围。 |
| 程序审核 | `/reviewProgram`，`webapp/src/views/PageReviewProgram/PublicProgramList.vue` 与 `ProgramAPI.js` | 默认 `programStatus=PROGRAM_IN_REVIEW`；列表查询 `/program/getpublicprograms`，参数为 `programStatus/searchText/pageSize/currentPage`；查看代码版本调 `/program/getprogramversion`；状态变更调 `/program/changeprogramstatus`。 | `ProgramController` / 程序审核服务。 | 程序审核是独立记录链路；默认只看未审核，搜索文本和状态切换会重置分页；不得套用评审数据或议题数据规则。 |
| 崩溃报告管理 | `/crashReport`，`webapp/src/views/PageCrashReport/CrashReport.vue` 与 `CrashReport.js` | 列表 `/crashReport/listCrashReport`；搜索 `/crashReport/selectCrashReport`；导出 `/crashReport/exportCrashReportInfo`；进度更新 `/crashReport/modifyProgress`。 | `CrashReportController`。 | 崩溃报告是独立运营记录，不走 GitLab 议题事实层；导出必须复用搜索条件。 |
| 用户反馈管理 | `/feedback`，`webapp/src/views/PageFeedBack/FeedBack.vue` 与 `FeedBack.js` | 列表 `/feedback/getfeedbacklist`；查看 `/feedback/viewfeedback`；回复 `/feedback/replyfeedback`；搜索 `/feedback/searchfeedback`；导出 `/feedback/exportfeedback`。 | `FeedbackController`。 | 反馈列表、详情回复和导出是独立记录链路；导出条件跟搜索条件走同一请求对象。 |
| 企业审核 | `/enterpriseCheck`，`webapp/src/views/PageEnterprise/EnterpriseCheck.vue` 与 `EnterpriseCheck.js` | 列表 `/enterprise/getAll?page=&pageSize=`；搜索 `/enterprise/search`；状态更新 `/enterprise/changestatus`；删除 `/enterprise/deleteItem`；图片 `/enterprise/getPic`。 | `EnterpriseController`。 | 审核状态、分页、搜索和图片查看是独立业务链路；不能套平台议题筛选或统计口径。 |
| 在线用户 | `/onlineuser`，`webapp/src/views/PageOnlineUser/OnlineUser.vue` | 路由存在但请求文件未单独命名；按页面内实际 API 调用继续定位。 | 在线用户相关 Controller。 | 若新平台实现该页，先从 Vue 内部请求和 Controller 重新确认字段与刷新规则。 |
| 运维操作页 | `/operation`，`webapp/src/views/Operation/Operation.vue` | 路由存在，属于运维操作入口。 | 账号/同步/运维相关 Controller。 | 不作为统计口径来源；涉及数据同步、重拉、清理等操作时必须单独核对副作用，不纳入页面展示数量对齐表。 |

新增或修改页面规则时，必须先在本索引补齐老平台链路定位；如果发现同一页面的表格、下钻、导出分别走不同老平台入口，应分别登记，不得用一个“页面公共规则”覆盖掉老平台实际差异。按当前约定，老平台 `Router.js` 中除登录、首页容器、404、`/integrationTable`、`/statisticsinfo`、`/codeWalkThroughInfo`、`/personalQuality`、`/ninePersonalQuality`、`/index` 等排除入口外，其余涉及数据列表、统计表、非法数据、设置、审核、反馈或导出的页面都必须在本索引中有定位。

## 4. 系统测试公共规则

系统测试类页面包括系统测试缺陷汇总、申请延期缺陷原因分析、系统测试非法数据、缺陷原因分析、议题阶段统计、议题查询、系统测试横向对比。

系统测试统计只使用 `issue_fact` 的议题/缺陷事实及平台已确认的缺陷修复率口径。该数据域没有执行用例数、通过用例数或用例通过率；`fix_rate`、`level1_rate`、`level2_rate`、`level3_rate`、`p1_fix_rate` 和 `p2_fix_rate` 均表示缺陷修复率，不得解释为测试用例通过率。平台页面和外部数据集不得以评审记录、修复率或零值补齐系统测试用例字段。

### 4.1 统计范围

1. 系统测试统计默认覆盖老平台 CrownCAD 项目 `9` 中，事实层 `testing_phase` 已归一化为对应系统测试或回归测试轮次的议题。GitLab 原始标签可用于生成 `testing_phase`，但页面查询、统计快照、下钻和非法数据记录页不能再用 `system_test_label` 或 `label_names` 兜底扩大范围。
2. 项目对应测试阶段维护位置是管理员模式下的“系统设置 - 议题测试阶段定义”。
3. 议题范围定义以 `issue_scope_catalogs / issue_scope_groups / issue_scope_members` 为唯一模型。目录固定项目和事实维度；范围组使用创建后不可修改的 `business_key` 作为 URL、查询和外部数据集参数，`display_name` 只负责显示；成员 `source_value` 是精确事实匹配值。系统测试维度匹配 `issue_fact.testing_phase`，客户问题维度匹配 `issue_fact.milestone_title`，不得互相兜底或从显示文本推导。
4. “议题测试阶段定义”必须支持阶段或里程碑及其成员的新增、编辑、删除、启停和自由排序。管理页只使用“阶段名称”“测试阶段名称”“里程碑名称”等业务用语，不显示稳定业务键、精确事实值或事实值发现结果；这些技术信息仅保留在内部模型和诊断接口中。停用记录仍可在管理页查看；业务页面只读取启用目录、启用范围和启用成员。范围顺序就是对应项目所有业务页面的下拉顺序，第一条启用范围是默认项；成员顺序是一个业务键展开为多个精确事实值时的稳定顺序。
5. 系统测试缺陷汇总、申请延期缺陷分析及横向对比中的缺陷汇总指标，必须先按父级阶段查表展开启用的子级测试阶段，再以“事实值包含任一子级成员”匹配 `testing_phase`；这是为了接纳老平台把多个同类阶段保存为 `阶段A & 阶段B` 的真实事实。缺陷原因分析是页面专用例外，老平台入口走 `SpiderIssueDataDAO.findAllByCauseExist`，CrownCAD 项目按展开后的具体测试阶段集合精确匹配 `testing_phase`。议题阶段统计也是页面专用例外，老平台入口走 `SpiderIssueDataDAO.getNumByDefectAndPhase`，父级阶段展开后必须逐子轮次精确匹配 `testing_phase`，不得使用包含匹配扩大范围。系统测试议题查询、系统测试非法数据等记录/非法类页面，必须按老平台记录入口使用展开后的具体测试阶段集合精确匹配 `testing_phase`，不得再用 `system_test_label` 或 `label_names` 兜底扩大范围。SQL、内存过滤、快照、下钻和导出必须共享同一页面模式，不能在查询后以另一模式二次过滤。其他只切换里程碑的页面，默认只展示启用父级阶段，不直接把子级轮次混入父级下拉。
6. 系统测试统计看板、系统测试非法数据和质量看板进入时默认选中项目 9 目录中第一条启用范围，不得保留固定版本回退。默认值必须同时体现在顶部控件、URL 查询参数、接口筛选条件和导出/下钻条件中；如果目录没有任何启用项，页面应展示待配置状态，不得隐式全量查询。`系统测试/议题查询` 是记录检索特例，默认测试阶段为空，表示查询 CrownCAD 项目下全部测试阶段。
7. 示例：`CC2026R1` 父级阶段可包含 `CC2026R1第一轮系统测试`、`CC2026R1第二轮系统测试`、`CC2026R1第三轮系统测试`、`CC2026R1回归测试`。

### 4.2 过滤规则

系统测试统计默认排除：

1. 携带 `功能屏蔽` 标签的议题。
2. 携带 `已拒绝` 标签的议题。
3. 携带 `建议` 标签的议题。
4. 携带 `申请否决` 且处于关闭状态的议题。
5. 携带 `需求如此` 且处于关闭状态的议题。

议题查询页是特例：统计范围为 CrownCAD 项目议题，不按统计看板默认要求必须命中系统测试/回归测试标签，也不默认锁定某个测试阶段；测试阶段为空表示“全部测试阶段”。该页对齐老平台 `IssueStaticDataController.filter` 中前端默认 `queryFilter=false` 但仍执行 `setFilterRejected(true)` 的记录入口：默认只排除 `bug_status` 包含 `已拒绝` 和 `category` 包含 `功能屏蔽` 的记录，不默认排除 `建议`、关闭的 `申请否决` 或关闭的 `需求如此`。未显式选择项目时默认使用老平台 CrownCAD 项目 `9`，不得默认查询所有项目。

系统测试记录类页面（议题查询、系统测试非法数据等）必须按老平台记录列表入口 `IssueStaticDataController.filter -> SpiderIssueDataQueryBuilder.buildPage` 对齐展示数据筛选规则。未显式选择项目时默认 `projectId=9`；显式选择测试阶段时先按“系统设置 - 议题测试阶段定义”展开父级阶段，再匹配具体 `testing_phase`。其中系统测试非法数据默认选择第一可用父级阶段；议题查询默认不选择阶段。导出、筛选候选、分页总数和页面列表必须复用同一套规则。

系统测试记录类导出必须使用 `.xlsx`，不得再用 CSV。`系统测试/议题查询` 导出字段顺序对齐老平台 `IssueExcelBo`；`系统测试/系统测试非法数据` 导出字段在同一套 `IssueExcelBo` 基础字段后追加“非法类型”。新平台事实层暂未持久化的缺陷原因、修改方案、影响范围等老平台扩展列必须保留为空列，不能删除列或用其它字段猜填。

### 4.3 系统测试缺陷汇总

1. 统计范围使用系统测试公共范围。
2. 总计表示指定缺陷类型下的议题数量。同一议题即使命中多个模块，总计也只计 1 次。
3. 模块列按老平台 `getModuleTable` 展示习惯生成，但新平台实现必须把“模块行目录”和“单元格统计范围”分开：选择测试阶段后，模块行目录来自 CrownCAD 系统测试所有启用父级阶段展开后的议题模块，并叠加默认模块范围标签组或用户显式模块条件；当前所选测试阶段、严重程度、优先级等筛选只参与单元格计数，不得把目录中的模块行提前隐藏。没有选择测试阶段时，CrownCAD 系统测试缺陷汇总不返回模块表。
4. 父表格单元格数字必须与点击后的下钻明细 `total` 保持一致：同一个模块行和指标列必须复用同一套事实层匹配口径。多模块议题可以同时归入多个模块行，但单个模块行内必须按事实层模块成员精确匹配，不得用模块名子串包含扩大父表格计数。
5. 建议类缺陷按领导确认后的新平台口径单独恢复：`category` 包含“建议”的议题只进入“建议类缺陷(个)”列，不进入一级、二级、三级、P1/P2/P3、模块总缺陷数、占比、延期占比、修复率、关闭率或其它常规指标。老平台建议类列通常为 0，是因为老平台为了避免“建议类 + 二级缺陷”污染二级缺陷数量而在公共过滤中整体排除了建议类；新平台这里是明确的业务例外，不得仅以“老平台为 0”为理由回退。
6. 下载议题数据必须使用定时任务后存储到本地数据库的数据，继续遵守本页统计范围和过滤规则。系统测试缺陷汇总的“全量议题数据”以老平台 `IssueExcelBo` 顺序为基础，在“修改该缺陷可能影响的功能”后依次增加“已知的受影响功能”“新识别的受影响功能”；新版修复模板中选中输出“是”、未选中输出“否”，旧模板无法判定输出“--”。该扩展只属于缺陷汇总全量议题数据；系统测试议题查询保持原 29 列，非法数据保持原基础列加“非法类型”，不得连带扩列。
7. 下钻明细字段以老平台 `ModuleTableDetail.vue` 为准。主表列为：议题编号、模块名、议题标题、议题状态、严重程度、测试状态；展开区为：议题更新时间、议题提交时间、模块名、议题编号、议题标题、议题提交人、议题处理人、议题状态、测试状态、议题严重程度。议题编号按老平台 `#28159` 这类形式展示并支持跳转 GitLab，但视觉上保持普通表格文本风格，不使用蓝色加粗下划线一类强链接样式。新平台可以用彩色标签、紧凑行高、浮动横向滚动条和展开区样式提升体验，但不能删减或改写这些字段含义。
8. 一级缺陷分类按老平台标题规则拆分：回退命中标题包含 `回退`、`倒退` 或 `（退`；挂机命中标题包含 `挂机`；其他一级必须是一级缺陷且标题不包含 `退`、`回退`、`倒退`、`挂机`。因此标题中出现“退出”等含 `退` 文案时，不计入其他一级。
9. 横向对比中的合并请求只统计目标分支为 `dev` 且状态为 `MERGED` 的数据。
10. 代码走查缺陷个数公式：规范性问题 + 性能问题 + 设计问题 + 逻辑问题 + 其他。
11. 评审数据与系统测试项目的版本关联统一按全局规则第 3 节 6.3 条执行：优先使用有效版本 ID，缺失时按项目名称主体展开已登记版本成员；组合版本分别统计，不再按 `CC2025R1` 做 `CC2025R1&R2` 特例查询。
12. P1、P2、P3 缺陷关闭率统一按“当前优先级已关闭缺陷数 / 当前优先级缺陷总数”计算；系统测试缺陷汇总统计导出必须在各优先级修复率后输出对应关闭率，分母为 0 时显示 `/`。

### 4.4 申请延期缺陷原因分析

1. 统计范围使用系统测试公共范围，表头按老平台保留一级缺陷、二级缺陷、三级缺陷、建议类缺陷和总计。
2. 延期原因类型固定为：技术卡点、方案卡点、资源卡点、数据异常、算法问题、机制问题、计算效率。
3. 延期原因来自标签内容。延期原因类型变化时，必须同步修改规则层代码、测试和本文件。
4. 建议类缺陷按领导确认后的新平台口径单独恢复：`category` 包含“建议”的议题只进入“建议类缺陷(个)”列，不进入一级、二级、三级、总计、占比或率类指标。老平台该列通常为 0，是因为老平台为了避免“建议类 + 二级缺陷”污染二级缺陷数量而在公共过滤中整体排除了建议类；新平台这里是明确的业务例外，不得仅以“老平台为 0”为理由回退。
5. 该页“总计”列展示为“总计(排除建议类)(个)”，计算公式为一级缺陷 + 二级缺陷 + 三级缺陷，不包含建议类。
6. 导出文件对齐老平台 `DefectAndPhaseTableRow`，第一列表头保留老平台原文 `伦次`，不按新平台页面展示文案改写。

### 4.5 系统测试非法数据

1. 未打一级缺陷、二级缺陷或三级缺陷的议题，判定为非法，非法类型为“未设定严重程度”。
2. 未打模块标签的议题，判定为非法，非法类型为“未设定模块”。
3. 未携带 `已修复/完成` 标签的议题，不进行议题评论解析。
4. 携带 `已修复/完成` 标签但未按模板回复的议题，判定为非法，非法类型为“未按照模板回复”。
5. 携带 `已修复/完成` 标签但缺陷原因数量为 0 或超过 1 的议题，判定为非法，非法类型为“缺陷原因不唯一”；缺陷原因数量按老平台 `CauseUtil.getMajorByCause(cause)` 的一级分类数量计算。
6. 同一议题有多个缺陷原因评论时，对齐老平台 `parseCauseAndFixedUserByIssueNotes`：按 GitLab notes 返回顺序取第一条可解析的 `### 1、修复状态` 评论，不按新平台自行改为全文搜索或最后一条。
7. 模块筛选来自全量议题模块信息；项目筛选来自“系统设置 - 议题测试阶段定义”。
8. 记录页主表展示字段对齐老平台 `IllegalIssueSearch.vue`：议题编号、模块名、议题标题、议题状态、严重程度、议题处理人、非法类型。测试阶段、功能名、提交/更新时间、提交人、测试状态等辅助字段放在详情中，不替换主表老字段。
9. 详情字段至少覆盖老平台展开行：议题更新时间、议题提交时间、模块名、功能名、议题编号、议题标题、议题提交人、议题处理人、议题状态、测试状态、议题严重程度。新平台可以追加项目、里程碑、非法判定、标签等增强字段，但不能删减这些老平台字段。

### 4.6 缺陷原因分析

1. 统计范围和过滤规则使用系统测试公共规则。
2. 模块筛选来自全量议题模块信息。
3. 缺陷原因表头来自新缺陷回复模板内容的提取和总结。
4. 新旧模板兼容映射：
   - 新增理解偏差 = 新增理解偏差（新模板） + 需求理解有误（旧模板）
   - 新增需求 = 新增需求问题（旧模板）
   - 编码逻辑：业务逻辑错误 = 编码逻辑：业务逻辑错误（新模板） + 编码逻辑错误（旧模板）
   - 编译/打包/部署问题 = 编译打包问题（新模板） + 编译打包问题（旧模板）
   - 机制不支持 = 机制不支持（新模板） + 算法/机制不支持
5. 缺陷原因占比 = 指定原因缺陷数量总和 / 所有模块下所有缺陷原因个数总和。
6. 缺陷原因占比的分子和分母都是缺陷原因个数，不是议题个数。
7. 只要议题按要求填写了缺陷原因，无论是否携带 `已修复/完成` 标签，都纳入缺陷原因统计。
8. 缺陷原因统计只使用事实层 `reason_category` 中的老平台原因段文本；如果事实层未生成该文本，必须修复事实构建并重建事实层，不允许在看板 SQL 中长期回退到 `raw_payload` 全文评论。
9. CrownCAD 测试阶段筛选对齐老平台 `findAllByCauseExist`：父级阶段展开为启用子阶段后，计数、总计和下钻明细按事实层 `testing_phase` 精确匹配子阶段集合，不使用包含匹配扩大范围。
10. 导出文件对齐老平台 `ExportModuleAndCauseTableRow` 两级表头，不增加新平台中间分组层；字段顺序以该 DTO 的 `@ExcelProperty` 声明顺序为准。

### 4.7 议题阶段统计

1. 统计范围使用系统测试公共范围，表头按老平台保留一级缺陷、二级缺陷、三级缺陷、建议类缺陷和总计。
2. 项目筛选来自“系统设置 - 议题测试阶段定义”。
3. “轮次”来自项目对应测试阶段。
4. 建议类缺陷按领导确认后的新平台口径单独恢复：`category` 包含“建议”的议题只进入“建议类缺陷(个)”列，不进入一级、二级、三级、总计、占比或率类指标。老平台该列通常为 0，是因为老平台为了避免“建议类 + 二级缺陷”污染二级缺陷数量而在公共过滤中整体排除了建议类；新平台这里是明确的业务例外，不得仅以“老平台为 0”为理由回退。
5. 计数字段对齐老平台 `DefectLevelEnum`：一级、二级、三级缺陷按老平台解析出的严重程度字面值分别匹配 `一级缺陷`、`二级缺陷`、`三级缺陷`；不得把 `一级严重`、`二级严重`、`三级严重` 等新平台归一化别名扩展进本页统计。
6. 阶段匹配对齐老平台 `getNumByDefectAndPhase`：父级阶段先展开子轮次，每个子轮次按 `testing_phase = 子轮次` 精确统计。一个议题若被老平台解析成多个阶段拼接值，例如 `阶段A & 阶段B`，本页不得把它拆开计入某个子轮次。
7. 该页“总计”列展示为“总计(排除建议类)(个)”，计算公式为一级缺陷 + 二级缺陷 + 三级缺陷，不包含建议类。
8. 老平台该页面没有导出按钮，新平台也不得展示导出按钮；不能用平台通用统计导出替代老平台不存在的功能。

### 4.8 议题多元看板

1. 议题多元看板对齐老平台 `StatisticsInfo.vue` 中系统测试图表集合，数据来源为系统测试 `issue_fact`，默认项目为 CrownCAD `9`，默认测试阶段为“系统设置 - 议题测试阶段定义”中第一可用启用父级阶段。
2. 页面顶部必须展示当前项目和测试阶段，并提供项目选择和测试阶段选择。选择父级测试阶段时，先展开为启用子级测试阶段，再用于统计、图表、下载和详情入口。
3. 页面图表必须覆盖老平台 11 个分析项：缺陷严重程度分析、缺陷阶段分析、缺陷模块分析、缺陷原因占比分析、缺陷原因分析、模块修复率、未关闭缺陷占比、修复人-缺陷数量统计、申请延期模块分析、申请延期缺陷原因分析、回退模块缺陷占比。
4. 每张图表必须使用 Apache ECharts 渲染。UI 可按新平台设计系统优化，但不得删除老平台已有图表内容。
5. 图表中的数值统一保留两位小数；计数类数值也按 `0.00` 展示，百分比类数值按 `0.00%` 展示。
6. 每张图表卡片都必须提供数据下载，下载数据和图表展示来自同一套聚合结果，不能另写一套查询口径。文件名统一为 `{项目名称}-{测试阶段}-{图表名称}.xlsx`。
7. “修复人-缺陷数量统计”对齐老平台 `fix_user` 口径：修复人来自第一条合法 `### 1、修复状态` 评论的作者。新平台将该值持久化到 `issue_fact.fix_user`；`V20260710_01__backfill_issue_fact_fix_user.sql` 只对正式默认源的存量议题事实回填该字段，无需全量重建，也不得跨源修改兼容模式数据；没有合法评论时不得用议题处理人或负责人冒充修复人。
8. 本页只读取系统测试议题事实层 `issue_fact`，不参与代码走查 MR 兼容模式开关。代码走查/质量看板涉及兼容模式时应在对应服务中处理，不得把兼容模式分支塞入议题多元看板。
9. 本页只在明确包含“建议类缺陷”系列的图表中恢复建议类，包括缺陷严重程度分析、缺陷阶段分析、缺陷模块分析、修复人-缺陷数量统计、申请延期缺陷原因分析；其它图表的总数、占比、修复率、未关闭、回退和缺陷原因统计继续排除建议类，避免建议类污染常规缺陷指标。
10. 阶段成员匹配按老平台图表入口分别执行：缺陷阶段分析、缺陷模块分析、未关闭缺陷占比使用精确成员匹配；缺陷严重程度分析、缺陷原因占比分析、缺陷原因分析、模块修复率、修复人-缺陷数量统计、申请延期模块分析、申请延期缺陷原因分析、回退模块缺陷占比使用包含成员匹配。页面摘要卡使用包含成员范围。每张图表的 Excel 原始数据必须使用该图表自身的同一匹配模式。

### 4.9 系统测试横向对比

1. 统计范围包括 CrownCAD 各版本对应的需求评审、设计评审、CrownCAD 和 DGM 代码走查数据、缺陷原因分析、系统测试缺陷汇总数据。
1.1 评审数据与代码走查数据是两个并行数据域；代码走查只进入代码走查/质量看板相关指标，不计入普通评审阶段或普通评审类型总数。两类数据在本页可并列展示，但不得相互归类或用一方补齐另一方。
2. 代码走查过滤：合并请求状态为 `MERGED`，合并目标分支为 `dev`。
3. 缺陷原因分析过滤同“缺陷原因分析”。
4. 系统测试缺陷汇总过滤同“系统测试缺陷汇总”。
4.1 横向对比导出中的“建议类缺陷(个)”跟随系统测试缺陷汇总的新口径单独统计建议类；其它系统测试列、原因列、P1/P2/P3、总数、占比和率类指标继续排除建议类。
5. 需求评审缺陷密度 = 需求评审缺陷数 / 需求文档页数，四舍五入保留两位小数。
6. 代码走查缺陷密度 = 代码走查缺陷合计 / 代码走查行数，四舍五入取整两位小数。
7. 导出行目录只允许原子模块：基础目录读取 GitLab 项目 `9`、`79` 当前有效的全角 `模块：` 标签，并合并当前范围议题和评审中涉及的原子模块。代码走查 MR 的模块字段只用于更新已存在行，不能创建导出行。
8. 横向对比按模块全值精确汇总；模块名称相同的数据合并计算，名称不同则为独立行。代码走查中的 `工程图 & 平台` 等组合模块既不生成组合行，也不拆分到多个原子行重复计数。
9. 整行数据全为 `0`、`0.0` 或 `/` 时，判定为无效数据行，Excel 导出不展示。
10. 系统测试缺陷汇总页的“横向对比导出”属于正式导出功能，必须与普通导出一样复用页面当前项目、测试阶段、模块和系统测试公共过滤规则，不能绕过页面口径。
11. 横向对比导出字段集合和顺序对齐老平台 `NewDataHorizontalComparisonDTO`：不输出评审类别占比、评审工作量、评审效率、评审速率、代码走查类别占比、代码走查工作量、代码走查效率、代码走查速率和系统测试缺陷密度等新平台扩展字段。
12. 横向对比导出中密度、占比等小数统一保留两位；分母为 0 且按页面规则应展示 `/` 的修复率/关闭率类字段继续展示 `/`。
13. 横向对比中的 P1、P2、P3 缺陷关闭率均按“当前优先级已关闭缺陷数 / 当前优先级缺陷总数”计算，并分别紧跟对应优先级修复率输出；不得只导出 P1 关闭率。

## 4.10 代码走查非法数据

1. 代码走查非法数据默认对齐老平台 `codeThroughDataTable`：进入页面默认数据源为 `CC`，老平台 `name/nameSearch`（新平台兼容表字段 `repositoryName/repository_name`，页面展示为“所属项目”）默认 `CrownCAD`，`projectName/project_name` 是独立的“项目名称”筛选，不能等同于 `name`。所有非法类型判定前必须先限定合并请求状态为 `MERGED`，且兼容模式必须按老平台原表 `merged_time` 字段应用 `2024-04-01 00:00:00` 时间锁，不能用导入后展示用的 `merged_local_date_time` / `merged_at_source` 替代。基础范围只包含已合并 MR；`opened`、开放中、草稿开放中、`closed` 和已关闭 MR 一律排除，不能被项目、模块、关键字、非法类型、快速筛选或高级筛选重新带回。对齐老平台查询入口，基础范围排除 `module_name = 无需标注`；`project_name = 未标注项目名` 和 `module_name = 未标注模块名` 必须作为非法类型命中保留，不能在基础范围提前排除。该页不是议题记录页，不能套用 `projectId=325`。
2. 目标分支范围按所属项目行级收口：`repository_name/name` 为 `CrownCAD` 和 `DGM` 时只展示目标分支为 `dev` 的非法数据；其他所属项目展示所有目标分支的非法数据。页面显式选择目标分支时继续叠加该筛选，但不得因为 `source = cc` 或 `dgm` 把同一数据源下的其他所属项目隐式限制到 `dev`。
3. 非法类型包括且仅包括：未标注项目名称、未标注模块名称、无代码走查、未代码扫描、静态扫描问题未关闭、代码注释量未达标、静态扫描失败、注释率分析工具 Clang 分析错误、GitLab 接口报错。筛选项可显示老平台文案，但后端必须映射到事实字段统一判断；普通事实层中“未标注项目名称”和“未标注模块名称”按 GitLab 标签判定，缺少合法 `项目:xxx` / `项目：xxx` 标签即为未标注项目，缺少合法 `模块:xxx` / `模块：xxx` 标签即为未标注模块，不能只按事实层 `project_name` / `module_name` 空值判断；兼容模式 `MatchMode` 直接读取老平台 `spider_crowncad_data` 兼容表时，必须按老平台字段字面值判断，即 `project_name = 未标注项目名`、`module_name = 未标注模块名`。“未代码扫描”只匹配老平台 `sonar_qube_result = 未进行代码扫描` 的字面值，不得把 `NOT_SCANNED`、`UNSCANNED`、`未扫描` 等新平台技术状态码扩展进默认非法口径。
4. “无代码走查/代码走查异常”按老平台 `assignee` 占位值判断，只包含：`没有合法评论`、`代码走查时间或缺陷数异常`、`代码走查标题异常`、`代码走查记录行数异常`。不得把普通空走查时长、空走查状态或新平台派生空值直接扩大为非法。
5. 老平台配置 `defect.investigation.level=1`，因此默认总非法数据包含项目名和模块名非法；如果后续内网配置改为 2，必须先同步本文件再调整规则。
6. `静态扫描问题未关闭` 普通事实层可按 Bug 数量不为 `0` 判定；兼容模式 `MatchMode` 必须按老平台表字段 `bug_count_result = 静态扫描问题未关闭` 判定，不得因为 `bug_count` 数值非 0 额外扩大范围。`静态扫描失败` 仍按静态扫描结果字段命中 `静态扫描失败` 判定。
7. `代码注释量未达标` 普通事实层按注释率阈值判定：CC 低于 `15%`、DGM 低于 `20%`。兼容模式 `MatchMode` 必须按老平台表字段 `annotation_rate_result = 代码注释量未达标` 判定，不得重新根据 `annotation_rate` 数值扩展老平台未统计的数据。注释率、注释率分析工具 Clang 分析结果等来自外部工具写入平台数据库；当前外部工具写入 API 尚未完成时，新平台只能消费事实层已有字段，不能臆造工具结果。
8. 页面顶部数据源切换必须对齐老平台 `activeName=CC/DGM` 页签；切换后需要像老平台 `handleClick` 一样清空合并时间、合并请求编号、被走查人、目标分支、合并人、模块名、非法类型、项目名称和高级筛选，并按当前数据源重新加载候选。CC 页签额外展示“所属项目”筛选，对齐老平台 `name/nameSearch` 字段，默认值为 `CrownCAD`；DGM 页签固定请求 `name=DGM`，不展示“所属项目”列和筛选。主筛选里的“项目名称”才对齐老平台 `projectName/project_name` 字段，选择值按 `project_name` 精确匹配，不得用包含/模糊匹配导致 `CC` 命中 `CC-Plugin-APP` 等其他项目；字面值为 `CC` 的 GitLab 项目不是老平台代码走查非法数据页所属项目选项，必须从所属项目候选中排除，但不得排除 `CrownCAD` 或 `CC-Plugin-*` 等真实所属项目；不能用 GitLab `projectId` 替代 `projectName` 版本筛选。
9. 页面条件中的“被走查人”按老平台 `author` 字段筛选；不能因为新平台内部参数名为 `owner` 就改查 `owner_name`。责任人维度属于代码走查多元看板，不替代非法数据页的被走查人筛选。
10. 代码走查非法数据的详情展示只保留当前业务验收字段：走查编号、所属项目、走查时间、模块名、被走查人、走查人、被指派人、合并时间、合并人、合并目标分支、走查工作量（分钟）、新增走查代码行数（LOC）、缺陷数（个）、代码走查速率（LOC/H）、代码走查缺陷密度和代码走查效率；其中“所属项目”仅 CC 页签展示，DGM 页签按老平台固定 `name=DGM` 的页面隐藏。不得额外展示代码库、请求类型、项目 ID、功能名称、扫描状态、分类缺陷数、提交次数等冗余字段。
11. 代码走查非法数据页必须复用系统测试议题查询的展开快速筛选模式，并与“筛选条件”高级条件筛选同时生效。高级筛选字段中的 `keyword` 对用户展示为“合并请求内容”；非法类型字段的关系展示和语义均为“包含/不包含”，不得显示为“等于/不等于”。
12. 评审前临时兼容模式 `MatchMode` 默认开启，用于外部注释率工具和静态扫描工具正式接入新平台前的过渡。兼容模式的开关、老平台 MySQL 连接配置、老平台 MongoDB 连接配置、MySQL 导入表白名单和 MongoDB 导入集合白名单只维护在系统设置 / 数据库设置对应的独立表 `code_review_match_mode_db_settings`，不得继续混入 GitLab 数据镜像设置。老平台代码走查数据中 CC 默认来自 MySQL `gitlab_spider` 库，DGM 默认来自同主机同账号的 `gitlab_spider_dgm` 库，二者同样读取 `spider_crowncad_data` 并写入新平台独立兼容表 `code_review_match_mode_records`，通过 `source_instance=cc/dgm` 切换展示。系统设置页必须从当前 MySQL 数据库动态读取全部基础表供用户选择，用户选择的任意表导入到通用原始快照表；其中代码走查兼容表默认 `spider_crowncad_data`，被选中时额外刷新独立兼容表 `code_review_match_mode_records` 供代码走查非法数据页读取。评审数据来自老平台 MongoDB `spider` 库，默认集合为 `reviewReport`、`problemDetail` 和 `description`，必须通过 MongoDB 集合白名单选择并写入独立兼容表；普通兼容同步不写入或改动新平台事实表、GitLab ODS 镜像表或正式评审数据表。`review_data_read_mode` 独立控制评审读源：兼容态的列表、详情、筛选、统计和导出通过 `review_visible_*` 合并正式评审与未被 `PLATFORM_OWNED` 映射遮蔽的兼容快照，正式态只读正式评审。`code_review_read_mode` 只控制代码走查及其质量看板数据源，不能影响评审数据。兼容模式自动同步按“上次同步任务结束后再等待 10 分钟”触发，不按现实时间整点/整 10 分钟触发；同步过程中页面继续读取上一次已完成同步的稳定兼容表数据。系统设置页的“老平台数据转正式数据”入口只交接代码走查并写入 `merge_request_fact`；评审不参与批量交接，用户首次编辑负 ID 快照时按稳定 `legacy_id` 完整物化主记录、专家、描述、内容和问题项，并建立 `PLATFORM_OWNED` 所有权。老 Mongo 派生密度不搬运，正式指标由正式明细重算。单页刷新按钮必须跟随当前页面读源：评审兼容快照刷新只更新兼容快照，统一可见模型保持正式表与未接管快照并存；代码走查继续按当前 `code_review_read_mode` 刷新对应源。所有兼容实现必须标注 `//兼容模式-MatchMode`，评审后按临时代码整体删除。

## 5. 客户问题公共规则

客户问题类页面包括客户问题缺陷汇总、客户问题缺陷原因分析、客户问题延期问题、客户问题缺陷非法数据、客户问题按功能展示缺陷数量、客户问题缺陷响应/解决效率和 `CC_PRODUCT议题`。

### 5.1 统计范围

1. 客户问题默认统计 `CC_Product` 项目 `325` 自 2026-01-01 之后创建的议题；项目范围必须来自老平台项目 ID `project_id=325`，不能因为 `project_name`、里程碑、测试阶段或标签文本包含 `cc_product` 就反推出客户问题范围。该 2026-01-01 起始日期必须覆盖客户问题统计、延期问题、按功能展示缺陷数量、缺陷响应/解决效率四类页面，不得在性能优化、快照、中间表或导出链路中绕过。
2. 客户问题统计默认只纳入携带里程碑的议题。
3. 客户问题采集频率为每小时执行一次全量议题爬取或同步任务。
4. 客户问题默认排除：
   - 携带 `申请否决` 且处于关闭状态的议题。
   - 携带 `需求如此` 且处于关闭状态的议题。
   - 携带 `设计如此` 且处于关闭状态的历史议题。
   - 不排除建议类问题；携带 `建议`、类别为建议类或严重程度归一为建议类的数据仍应进入客户问题统计。
5. `CC_Product` 中标题以“兼容性测试”开头的 open 议题，如果创建时间、里程碑和模板条件命中，也会进入延期判定；管理员走查时可按业务需要忽略处理，但平台不能在规则层默认排除。
6. 客户问题模块下除 `CC_PRODUCT议题` 外，客户问题缺陷汇总、客户问题缺陷非法数据、客户问题缺陷原因分析、延期问题、缺陷响应效率、按功能展示缺陷数量都必须提供顶部“里程碑/版本”切换。候选只来自项目 325 的 `MILESTONE` 议题范围目录；新事实里程碑不得在查询时动态混入候选，管理员需要纳入时直接在定义页维护里程碑名称。前端 URL、接口筛选、导出、下钻和统计快照统一使用稳定业务键参数 `milestoneTitle`，后端展开成员后精确匹配 `issue_fact.milestone_title`。
7. 客户问题上述顶部切换进入页面时默认选中项目 325 目录中第一条启用范围，不能由前端重新计算“最新 CC 版本”，也不能读取项目 9 的系统测试目录。默认范围必须同时体现在顶部控件、URL 查询参数、接口筛选条件、导出/下钻条件和后端补默认结果中。只有业务规则明确要求全里程碑的页面才能展示全部范围。
8. 客户问题记录类页面必须区分页面画像，不能把所有记录页套同一条公共排除：
   - `CC_PRODUCT议题` 对齐老平台 `IssueStaticDataController.findCCProductIssueInfo -> ProjectIssueInfoQueryBuilder`：默认 `projectId=325`，默认全里程碑，里程碑只是可选筛选；默认限定提交时间不早于 `2026-01-01`，并排除 `bug_status` 包含 `已拒绝` 的记录；不套用客户问题统计页公共排除。
   - 客户问题延期记录、客户问题缺陷非法数据等统计/非法类记录入口对齐老平台 `SpiderIssueDataDAO` + `QueryUtil.setQueryFilter`：默认 `projectId=325`，顶部范围只使用 `milestoneTitle` 业务键并展开匹配 `milestone_title`，不接受系统测试 `testingPhase` 作为范围别名。导出、筛选候选、分页总数和页面列表必须复用同一套规则。
9. 客户问题记录类导出必须保持老平台 Excel 字段顺序。`CC_PRODUCT议题` / 延期记录导出字段对齐老平台 `ProjectIssueInfoExcelBo`；客户问题缺陷非法数据导出字段对齐老平台 `SpiderIssueData` / `IssueExcelBo` 的议题基础字段并包含“非法类型”。导出文件格式为 `.xlsx`，不得把 CSV 当作正式兼容格式。
10. `CC_PRODUCT议题` 的默认页面列与 Excel 必须共用以下 22 列及顺序：议题编号、模块名、功能名称、议题标题、客户、议题提交人、议题处理人、议题指派人、议题状态、测试状态、测试阶段、严重程度、缺陷优先级、议题类别、里程碑、延期原因、缺陷修复人、计划解决时间、计划合并版本分支、提交时间、缺陷滞留时长（小时）、更新时间。禁止由 Excel 重新解析标签、标题或另建列映射；页面与导出均直接使用同一条记录响应中的事实字段。
11. `缺陷滞留时长（小时）` 表示当前尚未闭环缺陷的年龄：GitLab 议题已关闭（`issue_state=closed` 或存在关闭时间），或测试状态成员包含 `已修复/完成`、`申请延期`、`数据异常`、`需求如此`、`未复现` 及历史等价值 `设计如此` 时显示 `0`；其他议题按本次请求统一时点减提交时间取完整小时，提交时间缺失时留空。闭环前的历史耗时属于解决周期，不得冻结到滞留字段。页面、详情和 Excel 必须使用同一响应值。
11.1 `delay_cause` 的合法原因集合固定为老平台七类：技术卡点、方案卡点、资源卡点、数据异常、算法问题、机制问题、计算效率。`申请延期` 只表示延期/闭环状态，不是延期原因；CC_PRODUCT 无合法原因时，事实层按页面契约保存展示回退值“未设定类别”。多个合法原因按 GitLab 标签原始顺序去重后以无空格 `&` 拼接；读取时按成员拆分，候选、普通筛选、标签组筛选、系统测试统计与导出分组均不得把组合值当成单一类别。`CC_PRODUCT议题` 表格像测试状态一样将每个成员单独显示为标签，接口和 Excel 仍保留事实层组合文本。
11.2 上述字段的唯一事实来源为 `issue_fact`：功能名称=`function_name`、客户展示值=`customer_names`、计划解决时间=`planned_resolution_at`（显示文本=`planned_resolution_text`）、计划合并版本分支=`planned_merge_version_branch`、议题处理人=`handler_name`、议题指派人=`assignee_name`、测试阶段=`testing_phase`、延期原因=`delay_cause`、缺陷修复人=`fix_user`。客户筛选和候选使用 `issue_fact_customer_members` 的多值成员关系；其他字段直接使用对应事实字段。处理人与指派人必须是独立 API 字段、独立筛选项和独立排序字段；当前 GitLab ODS 仅提供一份规范指派身份时，两列值可以相同，但不得在页面或查询层合并。`testing_phase` 为空时页面和 Excel 显示“未设定测试阶段”，筛选该值只匹配原始空值；不得从标题、里程碑或标签推断阶段。客户、功能名称、缺陷原因、议题提交人、测试阶段、处理人、指派人、严重程度、缺陷优先级、议题状态、测试状态、议题类别、延期原因、缺陷修复人、计划解决时间、计划合并版本分支、缺陷滞留时长、提交时间和更新时间均需在快捷筛选中可用。计划解决时间、提交时间和更新时间使用独立日期范围，并通过字段标签或专属起止占位符明确所筛字段；同一页面后续增加其他时间范围时遵循相同规则。计划合并版本分支读取时将 `&`、半角逗号、全角逗号和顿号视为成员分隔符，去空、去重并保留首次出现顺序；候选、表格和详情均按独立成员呈现，筛选以完整成员不区分大小写匹配，不得使用子串包含。事实响应和 Excel 仍保留来源组合文本。缺陷滞留时长按请求统一时点使用可单边的非负整数范围，并在分页前过滤。延期专题不得展示或消费上述 CC_PRODUCT 专属筛选；SQL 分页、内存回退、分页总数和导出必须复用同一字段语义。
11.3 `客户` 的主来源为 GitLab issue description 中的“客户名称”；description 无有效客户名称时，才使用标题末尾 `——客户` 作为兜底。客户别名按精确值规范化：`极目数字（苏普耐）`、`极目数字(苏普耐)` 及其 `——新版本适配测试` 后缀变体统一为 `极目数字`，独立客户 `苏普耐` 保持原值。一个议题可以关联多个客户，事实和筛选保留多值成员语义，但页面与导出只保留一条议题记录。
11.4 `计划解决时间` 只读取最新“问题调研情况说明”响应模板中的对应字段，必须是唯一且完整的日期。允许 `2026年3月31日`、`2026.03.31`、`2026，03，31` 等写法，年月日分隔符允许使用 `.`、`,`、`，`、`、`、`/`、`·`、`` ` ``、`年`、`月`、`日`；合法日期写入 `planned_resolution_at`，页面和 Excel 显示去除首尾空白后的模板原文并按该事实日期范围筛选。缺失、包含多个日期、格式不合法或日历日期不存在时不形成计划解决时间；不得复用 SLA 的 `resolve_deadline_at`。
11.5 `计划合并版本分支` 只读取最新“问题调研情况说明”响应模板中的对应字段。事实字段 `planned_merge_version_branch` 只折叠连续空白并保留其余来源文本，不以 `CCyyyyRn` 严格格式校验结果清空或改写事实；页面、详情和候选读取时将 `&`、半角逗号、全角逗号和顿号作为成员分隔符，去空、去重并保留首次出现顺序，筛选按完整成员不区分大小写匹配，斜杠、下划线、连字符、点号和冒号保留为分支名的一部分。API 和 Excel 继续输出完整事实原文，严格模板校验规则见 5.4。
12. `CC_PRODUCT议题` 的议题状态对用户统一显示中文业务值：`open` 或 `opened` 为“未关闭”，`closed`（或事实关闭时间非空）为“已关闭”；不得在页面或 Excel 暴露 GitLab 原始 `OPEN/CLOSED` 枚举。延期问题继续保持其既有页面列与导出布局，不因 CC_PRODUCT 的列契约发生变化。

### 5.2 缺陷汇总

1. 页面左侧下拉框使用里程碑属性切换版本或范围。
1.1 客户问题里程碑下拉严格使用管理员目录顺序，默认选择第一条启用范围。初始化迁移可按 CC 年份和 R 数字生成初始顺序，但运行时不得重新排序。
2. 表格模块列对齐老平台 `DropDownService.getModuleNameFromSpiderIssueData(projectId=325)`：来自 `CC_Product` 项目全量议题中出现过的模块名，不随当前里程碑提前收窄；模块值需要兼容老平台 `模块A & 模块B` 和新平台事实层分隔符拆分，过滤 `未设定...` 占位模块并去重。当前里程碑或用户筛选只参与单元格计数，没有命中数据的模块行仍展示为 `0`。
3. 客户问题缺陷汇总复用系统测试缺陷汇总的严重程度、模块、非法判定等基础规则，但统计范围改为客户问题范围。
4. 客户问题缺陷汇总快速筛选必须覆盖模块名、议题编号、议题标题、严重程度、优先级、测试状态、议题类别、议题状态、议题提交人和议题处理人。模块名及其他枚举/人员字段使用客户问题事实范围生成的下拉候选值，不允许改为自由输入；顶部里程碑切换继续作为更高优先级的独立范围控件，不在快速筛选中重复出现。
5. P1、P2、P3 缺陷关闭率统一按“当前优先级已关闭缺陷数 / 当前优先级缺陷总数”计算；客户问题缺陷汇总统计及其导出必须在各优先级修复率后输出对应关闭率，分母为 0 时显示 `/`。
6. 客户问题“全量议题数据”以老平台议题字段顺序为基础，在“修改该缺陷可能影响的功能”后依次输出“已知的受影响功能”“新识别的受影响功能”，值遵循系统测试缺陷汇总第 6 条的“是/否/--”语义。测试阶段直接使用事实字段；事实为空时统一展示“未设定测试阶段”，不得从项目名、里程碑或其他标签猜测阶段。

#### 5.2.1 客户问题缺陷原因分析

1. 缺陷原因只读取 `issue_fact.reason_category` 中按老平台规则解析的第一条合法 `### 1、修复状态` 评论；调研模板、标签和其他评论不得作为缺陷原因兜底。
2. 表体按模块展开；同一议题包含多个模块时，在每个命中模块分别计数。模块为空或以“未设定”开头时不进入模块行。
3. 本页“共计”是各可见模块行逐列相加的模块归属合计，不使用议题去重总数；因此多模块议题可在“共计”中出现多次，无模块议题不进入“共计”。该规则是全局“总计类指标按议题去重”的页面级例外，用于对齐老平台缺陷原因统计表及其导出口径。
4. “比例”逐列使用对应“共计”值作为分子，所有原因列的“共计”之和作为分母；分母为 0 时显示 `0`。
5. 页面、导出和模块行下钻必须复用同一原因来源、模块展开和筛选口径。“共计”和“比例”不提供议题下钻，避免模块重复计数与去重议题列表产生数量歧义。

### 5.3 延期问题

1. 延期问题页只统计 `CC_Product` 项目自 2026-01-01 之后创建且仍处于 open 状态的议题。
1.1 表格模块行目录对齐老平台 `DropDownService.getModuleNameFromSpiderIssueData(projectId=325, phase=null)`：来自 `CC_Product` 项目全量议题中出现过的模块名，不随当前里程碑、open 状态、延期命中结果或 `GitLab 接口报错` 过滤提前收窄；模块值需要兼容老平台 `模块A & 模块B` 和新平台事实层分隔符拆分，过滤 `未设定...` 占位模块并去重，再额外保留 `未设定模块` 行。当前里程碑、open 状态、延期命中和页面筛选只参与单元格计数及下钻，没有延期数据的模块行仍展示为 `0`。
2. 紧急程度必须使用 `P1`、`P2`、`P3`。未打紧急程度标签的议题按 `P3` 响应期限参与延期事实判定，但对齐老平台 `SpiderIssueDataService.getDelayIssue` 现行聚合实现，不进入延期问题统计表的 `P3` 列和总计列。
3. 响应期限：
   - `P1`：24 小时
   - `P2`：48 小时
   - `P3` 或未设定紧急程度：72 小时。该规则只用于判断是否延期；统计表计数仍只纳入紧急程度命中 `P1`、`P2`、`P3` 的议题。
4. 响应检测规则：评论区出现 `# 问题调研情况说明` 即认为已响应。
5. 超过响应期限且未响应时，议题应标记或展示为“响应已延期”；按模板响应后取消“响应已延期”。
6. 解决闭环状态包括：`已修复/完成`、`申请延期`、`数据异常`、`需求如此`、`未复现`。页面也应兼容历史文案 `设计如此`。
7. 解决期限：
   - 默认最长 18 天。
   - 如果缺陷调研模板中填写预计解决时间，且不超过 18 天，以模板预计解决时间为准。
   - 如果模板预计解决时间超过 18 天，仍按 18 天处理。
8. 超过解决期限，且未按要求填写缺陷原因分析内容时，议题应标记或展示为“解决已延期”。
9. 打上 `申请延期`、`数据异常`、`需求如此`、`未复现` 或 `已修复/完成` 后，可以取消“解决已延期”。
10. 总数统计必须包含已设定模块和未设定模块的议题，但紧急程度只统计命中 `P1`、`P2`、`P3` 的议题；未设定紧急程度不进入 `P3` 和总计。老平台前端提示文案曾写“总数行统计未设定紧急程度”，但后端现行聚合函数实际只统计包含 `P1/P2/P3` 的记录，新平台以老平台后端实际结果为准。
11. 延期问题页每一项需要支持排序。
12. 对齐老平台 `getDelayIssue`：客户问题延期问题页需要排除非法类型包含 `GitLab 接口报错` 的议题；`illegal_reason` 为空或不包含该类型的记录保留。
13. 同一模块“已超期/已延期”缺陷达到管理阈值时，页面可以提示关注：
    - 响应已超期超过 3 个：模块负责人需要加强关注。
    - 某一功能已延期缺陷超过 5 个：模块负责人应组织会议评审并推进优化。
14. 平台可按延期事实字段与 GitLab 上的 `响应已延期` / `解决已延期` 标签做差异写回，但写回必须同时满足两个开关：后端全局开关 `CUSTOMER_ISSUE_DELAY_LABEL_WRITEBACK_API_ENABLED=true`，以及对应数据源 `delay_label_writeback_enabled=true` 且配置了 `web_base_url` 和 `api_token`。写回关闭时仍刷新并监控延期事实，不调用 GitLab 写接口。写回开启时必须先增量刷新 `issues`、`notes`、`label_links`、`labels` 并完成事实刷新，再把差异写入 PostgreSQL 持久化队列，由后台 worker 调用 GitLab API。队列任务只允许通过 `add_labels` / `remove_labels` 增删 `响应已延期`、`解决已延期` 两个标签，不得覆盖、删除或重排其他标签；同一议题只保留一条未完成写回任务。GitLab 5xx、429 和网络错误可按退避策略重试，401/403/404/400 直接标记为不可自动重试。内网开发测试包默认关闭全局开关，即使页面误开数据源写回或误填 token，也不得调用 GitLab 写接口。未显式批准真实写回前，只验证事实字段、统计结果、去重差异和日志，不触碰 GitLab 真实标签。

### 5.4 客户问题缺陷非法数据

客户问题缺陷非法数据先复用系统测试非法数据规则，并追加以下规则：

1. 未按要求填写 `缺陷调研模板`，判定为非法，非法类型为“未按照要求填写缺陷调研模板”。
2. 除“计划解决时间”和“一级缺陷的修改方案请模块负责人签字确认”外，调研模板中的问题都需要有回复内容；不满足时判定为非法。
3. 计划解决时间有且只能填写一个日期时间戳。
4. 计划解决时间允许的示例：`2026年3月31日`、`2026.03.31`、`2026，03，31`。
5. 年月日分隔符允许使用：`.`、`,`、`，`、`、`、`/`、`·`、`` ` ``、`年`、`月`、`日`。
6. 客户问题非法模板校验只接受一个或多个 `CCyyyyRn` 版本标识；多个版本以 `&` 分隔，例如 `CC2026R4 & CC2026R5`。任一成员不符合格式时判定调研模板非法，但不能据此清空事实字段。`planned_merge_version_branch` 只折叠最新模板字段的空白并保留其余来源文本；页面主表和详情按 11.5 的读取成员语义渲染独立标签，Excel 使用同一事实文本。读取侧接受逗号或顿号不表示这些写法通过严格非法模板校验。
7. 一级缺陷必须有模块负责人签字确认；二级缺陷、三级缺陷不要求负责人签字。
8. 记录页主表和详情字段沿用系统测试非法数据的老平台字段集合，但范围固定为 `CC_Product` 项目及客户问题公共规则；顶部范围切换按 CC_Product 真实里程碑匹配，不改变展示字段含义。

### 5.5 按功能展示缺陷数量

1. 统计范围为 `CC_Product` 自 2026-01-01 以来创建的议题，open 和 closed 都统计。
2. 页面按功能筛选缺陷，展示各模块下所有功能对应缺陷数量。
3. 页面需要支持按版本区分。
4. 数量必须支持下钻到明细。
5. 对齐老平台 `getIssueCountByFunction`：只统计功能名非空的议题；模块字段按 `&` 兼容拆分后分别计入对应模块，平台事实层用逗号保存多模块时按同一语义展开；模块为空或以“未设定”开头的数据不进入本统计。
6. 同一模块下功能排序按老平台口径执行：问题数量倒序，其次按功能名排序。下钻明细必须使用当前单元格对应的模块和功能直接过滤，不能按整页数据重新推导行号。

### 5.6 缺陷响应/解决效率

1. 统计范围为 `CC_Product` 自 2026-01-01 以来创建的议题。
2. 响应周期只统计已按 `缺陷调研模板` 回复的议题。
3. 响应周期 = 议题创建时间到第一条调研模板回复时间，单位为小时，取整数。
4. 解决周期只统计已打 `已修复/完成` 的议题。
5. 解决周期 = 议题创建时间到标注 `已修复/完成` 的时间，单位为天，保留 1 位小数，例如 `3.5天`。标注时间对齐老平台 `resource_label_events` 中最新一条 `状态：已修复/完成` 标签 `add` 事件时间；事件表尚未同步时只能临时降级为当前标签关联时间，内网正式对齐必须同步事件表并重建事实层。
6. 支持按版本查看。
7. 如果一个议题被打了多个模块标签，例如 `工程图 & 平台`，应分别在工程图和平台中计算效率。
8. 主表模块行来源对齐老平台 `getModuleNameFromSpiderIssueData(projectId, null)`：取 `CC_Product` 全量模块目录，拆分多模块，过滤空值和以 `未设定` 开头的模块；当前版本只影响周期指标计算，不影响模块行是否展示。
9. 该页排除规则对齐老平台 `QueryUtil.setQueryFilter` 在 `CC_Product` 下的行为：不排除建议类问题，不排除已关闭的 `设计如此`；仅排除关闭状态下携带 `申请否决` 或 `需求如此` 的议题。
10. 对齐老平台展示：某模块在当前版本下没有可计算响应周期或解决周期样本时，对应周期显示为 `0`，但该单元格不提供下钻入口。

### 5.7 客户问题闭环和质量要求

这些规则主要用于页面说明、规则解释、待办提醒和非法数据判定辅助：

1. 所有客户反馈缺陷均应完成闭环处理，最终状态为 `已修复/完成`、`需求如此/设计如此`、`未复现`、`数据异常`、`申请延期` 之一。
2. 已修复缺陷必须按模板说明缺陷原因、解决方案和影响范围。
3. 需求如此/设计如此缺陷应填写证据，指派需求同事确认是否纳入需求管理，并去掉缺陷级别标签。
4. 未复现缺陷应通过复现步骤、日志、原始项目数据等手段尽力复现；连续两个版本均未复现时可关闭。
5. 数据异常缺陷应说明异常原因、可能引发功能和步骤；可修正时说明修正办法，需要重建模型时添加 `重建模型` 标签并附模型。
6. 申请延期缺陷必须按模板回复、正确标注延期原因，并经过模块负责人确认。
7. 如与 CrownCAD 产品遗留缺陷一致，应标记对应遗留缺陷号，并按正常缺陷跟踪处理和关闭。
8. 所有修复缺陷应确保正确修复，且不引发其他功能回退或新缺陷。
9. 缺陷修复后应按描述步骤复测，在议题中说明复测通过并附截图。
10. 底层代码或底层算法修改、影响范围超过单功能的缺陷，必须执行自动化测试并附报告链接。
11. 需要客户产品版本升级的缺陷，应提前进行兼容性测试；涉及文件版本号变更时通知模块负责人，并添加 `需兼容测试` 标签。

## 6. 评审数据规则

1. 普通评审阶段只包含以下五类已确认类型：需求评审、设计评审、单元测试用例评审、集成测试用例评审、系统测试用例评审。`code_review_formal_records` 中的代码走查记录属于独立数据域，不属于普通评审阶段。
1.1 当前数据中的来源类型映射为：`需求说明书评审` -> 需求评审；`设计说明书评审` -> 设计评审；`单元测试用例评审` -> 单元测试用例评审；`集成测试用例评审` -> 集成测试用例评审；`系统测试用例评审` -> 系统测试用例评审。该映射只对已确认类型生效。
1.2 无法确认等同关系的类型不得根据标题、模块名、页面候选或“其他”桶自动归类，暂存原始 `review_type`，不进入五类评审统计或评审质量目标计算。
1.3 2026-08-04 调查基线（数量会随同步变化，仅用于追溯样例）：

| 未确认来源类型 | 调查数量 | 至少一个真实数据样例（`review_visible_records.id` / 标题） |
|---|---:|---|
| `需求规格说明` | 22 | `-22` / `【焊件模块】需求规格说明书评审` |
| `测试要点评审` | 2 | `-330` / `【速览模块】流式传输测试要点评审` |
| `独立评审` | 1 | `5` / `外网回归测试评审` |
| `项目计划评审` | 1 | `-289` / `【二次开发模块】项目计划评审` |
| `其他` | 4 | `-335` / `【企业版】企业文件夹测试要点评审`；`-376` / `【二次开发】CrownCAD 拓扑模块API接口封装方案` |

1.4 评审导入/兼容解析把未识别来源类型降级为 `其他` 时，必须保留原始标题和记录 ID；`其他` 是待人工拆分的追踪桶，不能整体当作需求、设计、用例或代码走查。
1.5 `产品用户手册` 当前仅存在候选项、没有实际记录，不作为已观测评审类型；出现第一条真实记录后，必须先补充样例并取得人工归类确认，再进入统计。
1.6 系统测试横向对比及所有外部导出按统一产品版本规则处理：有有效版本 ID 时直接使用；缺失时按全局规则第 3 节 6.3 条从项目名称主体展开多个版本成员；不得保留 `CC2025R1` 到 `CC2025R1&R2` 的页面特例。
2. 需求评审缺陷密度 = 需求评审缺陷数 / 需求文档页数，四舍五入保留两位小数。
3. 普通评审数据页面的评审缺陷密度 = 有效问题数 / 评审规模页数，四舍五入保留两位小数；评审规模为空、为 0 或小于 0 时按 0 处理。
4. 普通评审数据页面的评审效率 = 有效问题数 / 清单评审工作量合计，四舍五入保留两位小数；清单评审工作量合计为空、为 0 或小于 0 时按 0 处理。
5. 普通评审数据页面的评审速率 = 评审规模页数 / 清单评审工作量合计，四舍五入保留两位小数；清单评审工作量合计为空、为 0 或小于 0 时按 0 处理。
5.1 评审兼容快照只读取老平台 Mongo 的原始评审记录、问题清单和评审规模明细。`reviewDataReadMode=compatibility` 时，列表、导出、质量看板和系统测试横向对比合并正式评审表与未被平台接管的兼容快照；`formal` 时只读正式评审表。用户首次编辑负 ID 快照时，系统原子物化完整评审聚合并标记为 `PLATFORM_OWNED`，此后该快照不再参与兼容读源；定时同步只更新兼容快照。两类来源的 `reviewDefectDensity`、`reviewEfficiency`、`reviewRate` 共用有效问题重算口径，不得直接展示 Mongo `reviewReport` 中可能陈旧的派生密度、效率、速率字段；只有缺少问题明细时，才允许用老平台 report 中的基础计数字段兜底后重新计算。
6. 普通评审数据页面的有效问题数排除问题状态为 `已拒绝`、`未评审`、`无问题` 的清单项，并排除问题类别为 `无问题` 的清单项。
7. 评审数据管理页的“加权重的评审缺陷密度”对齐老平台 `ReviewReportService.parseReviewReport(...)`：`(文档规范 + 完整性 * 1.5 + 功能性 * 2 + 可行性 * 2) / (评审规模页数 / 有效问题数)`；其中分母使用 Java 整数除法语义，分母为 0 时结果为 0。
8. 评审问题详情导出按逐条问题明细平铺，每个当前读源可见且未删除的问题项一行，同一评审的父字段随问题项重复；没有问题项的评审不生成空数据行。Sheet 名为“问题详情”，固定 15 列及数据来源依次为：`项目名称 -> review record.projectName`、`评审文档的类型 -> review record.reviewType`（等价老平台 `sourceType`）、`评审的工作产品 -> review record.reviewProduct`、`模块名称 -> review record.moduleName`、`评审专家 -> problem item.reviewerName`、`评审工作量 -> problem item.workloadHours`、`评审类别 -> problem item.reviewCategory`、`在文档中的位置 -> problem item.documentPosition`、`问题类别 -> problem item.problemCategory`、`问题描述 -> problem item.problemDescription`、`建议解决方案 -> problem item.suggestedSolution`、`责任人 -> problem item.ownerName`、`不接受理由 -> problem item.rejectionReason`、`问题状态 -> problem item.problemStatus`、`更新日期 -> problem item.updatedAt`；更新时间格式为 `yyyy-MM-dd HH:mm:ss`，空值写空单元格。全局筛选导出与单条评审导出共用该列契约，只保留各自的记录范围语义。
9. 老平台评审数据管理页和数据模型中存在的部分字段没有被老平台现有两个导出完整输出：
   - 评审汇总导出 `ExportReviewDTO` 只输出评审工作产品、评审类别、文档类别、文档类型、缺陷数量、类别统计、密度/效率/速率、评审规模、不达标原因、有效独立问题数、有效会议评审数量和所属项目；不输出评审日期、评审负责人、评审专家、模块名、作者、版本和逐条问题明细。
   - 评审问题详情导出按第 8 条输出父评审的项目、类型、工作产品、模块和逐条问题数据；不输出评审日期、评审负责人、作者、版本和问题关闭日期。
   - 因此从任一老平台导出导入新平台时，文件中缺失字段必须按导入服务兜底或后续补全处理；这些后补字段不得作为导入身份键的组成部分。
10. 评审数据导入身份键应优先使用老平台导出中稳定存在且不会被后续补全改变的字段，例如来源格式、所属项目/项目名、评审工作产品/标题、评审类别、文档类别和文档类型。评审日期、负责人、评审专家、作者、版本、问题状态、模块名和逐条问题明细字段即使在问题详情导出中可见，也属于可变或可后补属性，不能用于判断是否为同一条历史评审记录。
11. 评审数据管理页必须区分“列表筛选候选”和“新增/编辑候选”。列表项目、模块候选遵循当前评审读源：兼容态来自正式评审表与未被平台接管的兼容快照，正式态只来自正式评审表。标签组动态规则继续保持 `review_records`、`review_problem_items` 对外源键不变，内部统一通过 `review_visible_records`、`review_visible_problem_items` 读取当前读源，禁止在通用标签组 SQL 引擎中增加兼容开关分支。新增/编辑评审里的项目名称继续按已确认的项目候选来源提供；模块严格对齐老平台，只读取 GitLab 项目 ID 9、79 中以中文全角 `模块：` 开头的标签并展示冒号后的原值，同时允许手工录入候选外模块。评审模块录入、导入、兼容同步、保存、列表和导出均保留原始业务值，只做空白清理，不删除末尾“模块”。人员来自 `ods_gitlab_users`。新增/编辑评审里的评审版本下拉固定提供 `V0.1`、`V0.2`、`V0.3`、`V0.4`、`V0.5`、`V0.6`、`V1`、`V2`、`V3`，并允许用户手动输入其他版本号，例如 `V10.1`。历史评审导入数据只作为列表筛选和非标签来源字段的补充兜底，不能把新增评审可选范围限制为已经导入过的评审记录。
12. 编辑评审记录时，评审专家表和清单中的系统占位问题项必须同步：新增专家可自动补充“未评审/独立评审/无问题/待评审”的空占位项；删除专家时只允许移除该专家尚未填写过的系统占位项，已经录入具体位置、问题、建议、责任人、不采纳原因、状态或非 0 工作量的真实清单项必须保留。删除评审问题时，如果该评审专家在当前评审记录下已经没有任何未删除的问题项，评审专家字段也必须同步移除该专家。
13. 清单里新增或编辑评审问题时，问题状态候选项不得让用户手动选择“未评审”；“未评审”只作为系统默认状态使用。新增评审问题时如果用户没有选择问题状态，后端保存时默认标注为“未评审”；编辑已有评审问题时必须选择明确的问题状态。
14. 评审数据管理页必须复用系统测试议题查询的展开快速筛选模式，并与“筛选条件”高级条件筛选同时生效；快速筛选、条件筛选、导出和分页排序必须复用同一套记录查询参数。
15. 评审数据管理页“是否达标”判断对齐老平台：达标判断：评审缺陷密度介于[0.2~0.6]。

## 7. 标签组筛选规则

标签组是按值类型组织的一组可复用筛选值，不是字段维度集合，也不是跨字段查询模板。其长期设计约束归 `docs/architecture.md`；标签组不保存页面字段维度，也不向候选目录注入不存在的业务值。

1. 标签组本身只保存系统推断出的值类型和成员值。用户不需要也不应该手动声明 `valueType`；空组处于未定型状态，选择第一个有效候选成员后，由该成员类型确定标签组值类型。
2. 标签组成员不带字段前缀，不保存 `review_owner: 张三`、`issue_assignee: 张三` 这类身份限定。`张三` 就是字符串 `张三`；如果业务上确有重名人员，使用者应以更明确的字符串、账号、ID 或邮箱加入标签组。
3. 静态标签组成员必须来自平台登记的候选来源或数据库事实值，不能由用户凭空输入创造。候选值既是录入入口，也是成员来源依据；定型后界面应隐藏或弱化不匹配值类型的候选项。
4. 动态标签组同样不让用户声明 `valueType`；用户基于已登记的数据源、字段、逻辑关联关系、过滤条件和聚合方式自行配置规则，系统根据输出字段或首次计算出的第一个非空值推断值类型，混合类型输出必须拒绝并提示拆分规则。动态标签组不得设计为开发写死规则后让用户下拉选择。
5. 标签组可以包含任意同值类型成员。例如字符串组可同时包含用户名、用户简介、模块名、状态文本和自定义文本；系统不得强制“用户名只能和用户名放一组”。
6. 一个标签组内不混用不同值类型。字符串、数字、日期、布尔、数组等应分别建组，避免应用到字段时出现无法解释的比较语义；如果用户清空全部成员，系统可让标签组回到未定型状态并重新由首个成员定型。
7. 标签组不保存字段、操作符或 AND/OR 关系；“评审负责人=张三 AND 项目=CrownCAD AND 模块=草图”属于页面筛选条件组合，不属于一个标签组。
8. 页面应用标签组时，必须复用现有条件筛选组件，在“字段 / 关系 / 值”的“值”控件中选择标签组，不能新建独立标签组筛选区。
9. 页面应用语义固定为“当前字段按当前操作符匹配标签组成员值”。例如 `review_owner = 人员组` 展开为 `review_owner IN [张三, 李四]`；`issue_assignee = 人员组` 展开为 `issue_assignee IN [张三, 李四]`。
10. 页面字段能否使用某个标签组，由系统推断并保存的字段值类型、字段是否支持标签组和操作符决定，不由成员原始来源字段决定。
11. `STRING` 是字段值类型，不等于页面必须渲染为普通文本输入框；支持标签组的文本型字段必须能在“值”控件中选择同值类型标签组，并保留普通文本输入能力。
12. `模块`、`模块名称`、`模块名`、`moduleName`、`moduleNames` 在标签组应用语义中属于同一个模块维度，只要字段值类型为字符串且声明支持标签组，就可应用字符串标签组。
13. 指标字段、统计表头、占比、合计、缺陷密度、工作量、页面说明和规则说明不得被自动当作候选来源；静态标签组成员也不能通过手动输入绕过候选来源校验。
14. 应用标签组后的查询、导出、下钻、排序和二次筛选必须复用页面原有事实层查询口径。
15. 页面默认应用标签组时，系统默认标签组必须作为普通标签组列表中的一条记录维护，不单独展示“默认应用”配置表。系统默认标签组由迁移预置为空的字符串静态组，名称代表作用页面，例如 `系统测试缺陷汇总`；列表中只额外显示“系统默认”标识。默认关系可以由服务端按页面名称和字段规则识别，但用户侧只维护这一条标签组的成员、启用状态和备注。系统默认标签组不可删除，名称、类型、适用范围、来源字段不可改写；缺失默认组应由迁移或管理员恢复能力补齐，不能要求用户手工复刻。只要该系统默认标签组启用且有效，就应与用户显式筛选条件同时生效。用户如需调整默认范围，应编辑该系统默认标签组成员或停用该标签组，而不是让单次筛选绕过默认范围。
16. 第一批系统默认标签组预置范围排除质量看板、其他看板、议题多元看板、代码走查多元看板、系统测试议题查询和系统设置页；包含：评审数据管理、代码走查非法数据、系统测试缺陷汇总、申请延期缺陷分析、系统测试非法数据、系统测试缺陷原因分析、议题阶段统计、客户问题缺陷汇总、客户问题缺陷非法数据、客户问题缺陷原因分析、CC_PRODUCT议题、延期问题、缺陷响应效率、按功能展示缺陷数量。
17. 不得复用已废弃的对象分群、语义标签组、跨字段筛选方案或相关旧运行时命名。
18. 系统候选值建议过滤空值、`未设定...`、`未标注...`、`GitLab接口报错` 等占位值；标签组成员保存时必须能在平台登记候选来源中找到依据。
19. 标签组第一版为全局共享筛选项，不绑定个人、页面或源实例；个人收藏、最近使用、角色默认视图、跨字段筛选方案和业务场景模板独立设计，不能复用标签组表。
20. 标签组成员变化后，历史数据重新查询按当前成员展开；如果业务方要求历史口径冻结，必须等待业务事实历史快照方案落地后再扩展。
21. 单个标签组成员数量上限为 200，超过时提示拆分，避免过长查询拖慢页面。
22. 同字段普通值和标签组成员折叠为同一个 `IN` 条件时，去重后的最终值数量上限同样为 200；超过时返回业务错误，不能静默截断。
23. 客户问题闭环状态中 `需求如此` 和 `设计如此` 为等价值。标签组成员保存规范值 `需求如此` 时，查询展开同时匹配 `需求如此` 和 `设计如此`。
24. 标签组筛选参与导出时，导出文件中的快照展示遵守全局导出规则第 13 条。
25. 多值字段应用标签组时，匹配语义为“字段值集合与标签组成员集合相交不为空”。例如评审专家为“李四、王五”的记录，在评审专家字段选择包含“张三、李四”的字符串标签组时应命中。
26. 标签候选值和标签组展开接口必须显式支持 `pageKey` 和可选 `sourceInstanceId`；不传 `pageKey` 时按全部兼容页面和全部源聚合，传入 `pageKey` 时按该页面数据范围过滤，不能从 session 隐式读取页面或源实例状态。
27. 动态标签组遵从阿里巴巴数据库设计规范：不使用数据库外键，不依赖外键自动发现关联，不提供任何“外键关系”配置或功能。跨表规则只能使用平台显式登记或用户显式配置的逻辑关联字段，由服务层校验完整性、循环、空值、重复值和性能边界。

## 8. 页面设计强制清单

新增或修改页面前必须检查：

1. 页面是否明确展示或可打开规则说明。
2. 页面统计范围是否与本文件对应章节一致。
3. 页面筛选条件是否只包含当前口径允许的条件。
4. 页面导出是否复用同一查询和同一规则。
5. 页面下钻明细是否能解释聚合数字来源。
6. 页面空值、`0`、`/`、百分比和无效行处理是否符合全局规则。
7. 页面是否正确处理 `sourceInstance`、项目、阶段、里程碑、模块和标签来源。
8. 页面刷新状态是否区分镜像层和事实层。
9. 规则变化是否补充后端规则层测试、查询测试、前端 API 或页面测试。
10. 是否同步更新本文件、相关页面规则说明和测试样例。
11. 如果页面支持标签组筛选，是否在现有条件筛选“值”控件中展示同值类型标签组，并按当前字段执行匹配。
