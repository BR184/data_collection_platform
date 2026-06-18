# 系统测试议题查询页新旧平台差异记录

> 记录日期：2026-06-17  
> 新平台：`D:\projects\data_collection_platform`  
> 老平台：`D:\projects\spidergitdata-dev`
>
> 本文只记录会影响数据集合、字段展示、筛选能力、导出字段或功能可达性的差异。分页默认值、UI 样式和筛选组件形态不作为问题记录。

## 对齐目标

同一筛选条件下，新平台应与老平台及规则汇总保持：

1. 查询命中的议题集合和总数一致。
2. 筛选字段、筛选语义和候选值来源一致。
3. 主表字段、展开详情字段、字段名和字段值一致。
4. 导出字段、字段顺序和导出数据一致。
5. 议题编号可跳转到同一 GitLab 议题。
6. 议题查询页必须遵守规则汇总中的特殊说明：统计范围为所有议题，不套用系统测试默认过滤规则，完全按用户前端筛选条件查询。

## 老平台基线

### 页面入口和接口

老平台页面：

- `webapp/src/views/PageStandard/IssueSearch.vue`

老平台接口：

- 列表：`GET /issueStaticData/filter`
- 导出：`GET /issueStaticData/exportIssue`
- 下拉候选：`GET /getDownLabel/getModuleName`、`getFunctionName`、`getPhaseName`、`getAuthor`、`getHandler`、`getStatus`、`getSeverityLevel`、`getBugStatus`、`getCategory`、`getMilestone`

相关后端：

- `IssueStaticDataController.findFilter(...)`
- `IssueStaticDataController.exportByParam(...)`
- `SpiderIssueDataQueryBuilder`
- `DropDownLabelController`
- `DropDownService`
- `IssueExcelBo`

### 老平台筛选条件

老平台页面提供以下筛选：

- 更新日期：`updatedDate`
- 提交日期：`submissionDate`
- 模块名：`moduleName`
- 功能名：`functionName`
- 测试阶段：`phaseNameList`，页面多选
- 议题提交人：`author`
- 议题处理人：`handler`
- 议题状态：`status`
- 议题严重程度：`severityLevel`
- 测试状态：`bugStatus`
- 议题类别：`category`
- 里程碑：`milestone`
- 议题编号：`issuableReference`
- 议题标题：`issueTitle`

老平台实际查询语义：

- 更新日期、提交日期：`>=` 所选日期。
- 模块名、功能名、提交人、处理人、严重程度、测试状态、类别、标题、议题编号：多数为 `like` 模糊匹配。
- 状态、里程碑、项目 ID：等值匹配。
- 测试阶段多选：`testing_phase in (...)`。
- 测试状态选择“已修复”时，老平台额外匹配 `待合并`、`已修复`、`未更新`。
- 类别选择“建议和需求”时，老平台同时匹配 `建议` 和 `需求`。
- 模块选择“曲线”或“曲面”时，老平台额外排除“曲线曲面”组合模块。

### 老平台主表字段

页面可见字段：

- 议题编号
- 模块名
- 议题标题
- 议题状态
- 严重程度
- 测试状态

展开详情字段：

- 议题更新时间
- 议题提交时间
- 模块名
- 功能名
- 议题编号
- 议题标题
- 议题提交人
- 议题处理人
- 议题状态
- 测试状态
- 议题严重程度

议题编号点击后跳转：

- `http://172.22.10.233/cloudcad/crowncad/issues/{iid}`

### 老平台导出字段

老平台导出 `IssueExcelBo`，主要字段包括：

- 议题更新时间
- 议题提交时间
- 模块名
- 议题编号
- 议题标题
- 议题提交人
- 议题处理人
- 议题状态
- 测试状态
- 测试阶段
- 议题严重程度
- 议题类别
- 里程碑
- 议题指派人
- 优先级
- 延期原因
- 缺陷修复人
- 功能名称
- 修复状态
- 一级缺陷原因
- 二级缺陷原因
- 具体原因
- 修改方案
- 由修改其他缺陷造成的
- 修改该缺陷可能影响的功能
- 是否对可能影响的功能进行了测试
- 有无遗留问题或潜在的影响
- 是否更新了关联关系表
- 议题关闭时间

## 规则汇总基线

`docs/platform-page-business-rules.md` 明确：

- 议题查询页是特例：统计范围为所有议题，不套用系统测试默认过滤规则，完全按用户前端筛选条件查询。

这与老平台接口存在一个历史差异：老平台列表默认 `projectId=9`，且 `SpiderIssueDataQueryBuilder.setQuery()` 永远排除 `bug_status like 已拒绝`，`setFilterRejected(true)` 又排除 `bug_status like 已拒绝` 和 `category like 功能屏蔽`。后续实现前需要按规则汇总优先，或由业务确认是否继续保留老平台这两个硬过滤。

## 新平台现状

新平台页面：

- 菜单 key：`question-metrics-issue-search`
- 路径：`/question-metrics/issue-search`
- 页面：`frontend/src/views/SystemTestIssueSearchView.vue`

新平台接口：

- 列表：`GET /api/question-metrics/issues`
- 导出：`GET /api/question-metrics/issues/export`
- 下拉候选：`GET /api/question-metrics/issues/filter-options`

新平台后端：

- `QuestionMetricsController`
- `SystemTestIssueSearchService`
- `IssueFactRecordRepository`
- `IssueFactFilterGroupSqlSupport`

新平台已具备：

1. 页面入口。
2. 议题列表。
3. 议题编号跳转。
4. 基础筛选、条件筛选和标签组筛选。
5. 展开详情。
6. CSV 导出。
7. 刷新最新数据入口和同步状态。
8. 设置按钮。

## 已确认差异

### 1. 条件筛选和标签组筛选会把范围收窄到系统测试 scope

**影响范围：高。影响查询总数、结果集合和导出。**

规则汇总要求议题查询页查询所有议题，不套系统测试默认过滤规则。

新平台普通 SQL 分页路径使用：

```java
IssueFactRecordPageQuery.Scope.ALL
```

但只要使用条件筛选或标签组筛选，就会走内存路径：

```java
applyBaseFilters(loadScopedViews(listRequest.projectId()), ...)
```

而 `loadScopedViews(...)` 会执行：

```java
.filter(view -> systemTestScopeProfile.matches(view.scopeContext()))
```

这会把“议题查询”变成“系统测试范围内议题查询”，与规则汇总特殊口径冲突。

### 2. 默认项目范围存在口径冲突

**影响范围：高。影响首屏总数和无项目筛选时的结果集合。**

老平台 `IssueSearch.vue` 调用 `findByModuleNameAndPhaseName(...)` 时不传 `projectId`，后端默认：

```java
@RequestParam(required = false, defaultValue = "9") String projectId
```

因此老平台默认查询 CrownCAD 项目 `9`。

新平台在未传 `projectId` 时查询全部 `issue_fact`。

规则汇总又写明议题查询页统计范围为所有议题。这里需要业务裁定：

- 若按规则汇总：新平台“不限定项目”是正确方向，后续要修的是条件筛选路径不能回到系统测试 scope。
- 若按老平台页面行为：新平台需要默认限制 CrownCAD 项目 `9`，且下拉候选也要按该范围生成。

### 3. 功能名字段缺失

**影响范围：高。影响老平台已有筛选、详情和导出。**

老平台有：

- 功能名下拉和可输入筛选：`functionName`
- 查询条件：`setFunctionName(functionName)`，实际 `function_name like ?`
- 展开详情字段：功能名
- 导出字段：功能名称

新平台底层 `issue_fact.function_name` 已存在，`IssueFactRecord` 也读取了 `functionName`，条件 SQL 也支持 `functionName`，但页面和接口响应没有完整暴露：

- `SystemTestIssueSearchRowResponse` 没有 `functionName`。
- `SystemTestIssueSearchView.vue` 主筛选、展开详情没有功能名。
- `buildSystemTestIssueSearchConditionFields(...)` 没有功能名字段。
- CSV 导出没有功能名称。
- 下拉候选响应没有功能名候选。

### 4. 测试阶段筛选值与老平台不一致

**影响范围：高。影响测试阶段筛选结果。**

老平台测试阶段候选来自 `testingPhaseService.getAllTestingPhases()`，页面多选后直接传具体阶段字符串到 `phaseNameList`，后端按：

```java
queryWrapper.in("testing_phase", testingPhaseList)
```

新平台候选使用：

```java
IssueFactRecord::phaseFilterValue
```

`phaseFilterValue()` 会把包含“系统测试/回归测试”的完整阶段截断为项目前缀，例如把具体轮次标签变成较短的筛选值。响应展示仍使用 `primaryPhaseLabel()`，筛选值和展示值可能不一致。

新平台顶部数据范围选择还是单选；老平台测试阶段是多选。虽然条件筛选理论上可通过 OR 条件实现多阶段查询，但当前默认可见筛选能力与老平台多选能力不等价，需要确认是否必须补回直接多选入口。

### 5. 模块字段展示分隔符不一致

**影响范围：中到高。影响用户看到的字段值和导出值。**

老平台 `SpiderIssueData.moduleName` 是原始展示字符串，多个模块常以 ` & ` 拼接。老平台下拉拆分候选时也按 ` & ` 拆，但表格和详情展示使用原字段。

新平台将 `IssueFactRecord.moduleNames()` 重新 join 为：

```java
String.join("、", view.moduleNames())
```

这会让同一条数据的模块显示值从老平台的 `A & B` 变成 `A、B`。此前其他非法数据页已按老平台修回 `&` 展示，本页也需要统一。

### 6. 模块筛选语义不一致

**影响范围：中。影响模块筛选命中集合。**

老平台模块筛选为 `module_name like ?`，并允许用户在下拉外手动输入。

新平台基础模块筛选是从候选中选择，后端按拆分后的模块集合等值命中：

```java
lower(',' || replace(coalesce(module_names, ''), ', ', ',') || ',') like '%,模块,%'
```

虽然新平台有综合搜索和条件筛选能力，但默认模块字段不是老平台的模糊匹配。尤其老平台对“曲线/曲面”额外排除“曲线曲面”的逻辑，新平台当前没有等价处理。

### 7. 老平台特殊筛选语义未全部复刻

**影响范围：中。影响特定筛选值的结果集合。**

老平台写死了以下特殊逻辑：

- 测试状态选择“已修复”时，同时匹配 `待合并`、`已修复`、`未更新`。
- 类别选择“建议和需求”时，同时匹配 `建议` 和 `需求`。
- 模块选择“曲线”或“曲面”时，排除“曲线曲面”。

新平台普通字段筛选多为等值或简单包含，目前没有看到上述特例在议题查询路径中复刻。

### 8. 提交时间字段命名和能力不一致

**影响范围：中。影响字段对齐和用户核对。**

老平台字段名为：

- 议题提交时间

新平台使用：

- 创建时间

如果新平台 `created_at_source` 确认就是老平台 `submission_date`，则这是字段文案和导出表头不一致；如果两者来源不同，则是数据字段不一致。

另外老平台筛选是单日期 `submission_date >= 日期`；新平台是创建时间区间筛选。区间筛选能力更强，但需要保证用户能复现老平台“某日之后提交”的查询。

### 9. 主表字段多于老平台，且部分字段名不一致

**影响范围：中。影响页面字段对齐。**

老平台主表只展示：

- 议题编号
- 模块名
- 议题标题
- 议题状态
- 严重程度
- 测试状态

新平台主表展示：

- 数据源
- 项目ID
- 议题编号
- 标题
- 项目名称
- 模块
- 测试阶段
- 严重程度
- 缺陷状态
- 状态
- 处理人
- 更新时间

新平台没有缺少老平台主表核心字段，但字段名和展示集合不完全一致：

- 老平台“议题标题”对应新平台“标题”。
- 老平台“议题状态”对应新平台“状态”。
- 老平台“测试状态”对应新平台“缺陷状态”。
- 新平台额外展示数据源、项目ID、项目名称、测试阶段、处理人、更新时间。

这些额外字段可能是新平台体验提升，但若严格 1:1 字段对齐，需要产品确认是否保留。

### 10. 展开详情字段未对齐

**影响范围：高。影响用户核对单条数据。**

新平台展开详情缺少老平台字段：

- 功能名
- 议题提交时间，当前显示为创建时间

新平台展开详情字段名与老平台不一致：

- 创建人 vs 议题提交人
- 状态 vs 议题状态
- 缺陷状态 vs 测试状态
- 创建时间 vs 议题提交时间
- 模块 vs 模块名

新平台额外展示：

- 项目
- 测试阶段
- 缺陷分类
- 里程碑
- 关闭时间
- 标签

额外字段是否保留需要确认；但缺少“功能名”和老字段命名不一致需要对齐。

### 11. 导出字段与老平台差异较大

**影响范围：高。影响导出核对和离线分析。**

老平台导出为 Excel，字段来自 `IssueExcelBo`，包含功能名称、优先级、延期原因、缺陷修复人、缺陷原因拆解字段、影响范围、测试情况、关闭时间等。

新平台导出为 CSV，字段为：

- sourceInstance
- projectId
- 问题编号
- 项目
- 模块
- 测试阶段
- 严重程度
- 缺陷状态
- 状态
- 创建人
- 处理人
- 缺陷分类
- 里程碑
- 创建时间
- 更新时间
- 关闭时间
- 标题
- 链接

缺失老平台导出字段：

- 功能名称
- 议题指派人
- 优先级
- 延期原因
- 缺陷修复人
- 修复状态
- 一级缺陷原因
- 二级缺陷原因
- 具体原因
- 修改方案
- 由修改其他缺陷造成的
- 修改该缺陷可能影响的功能
- 是否对可能影响的功能进行了测试
- 有无遗留问题或潜在的影响
- 是否更新了关联关系表

导出文件类型从 Excel 变为 CSV 本身不一定是业务问题，但字段和字段值必须对齐。

### 12. 下拉候选来源不一致

**影响范围：中到高。影响用户能否选到老平台同样的值。**

老平台候选来源：

- 模块、处理人、类别、里程碑、测试状态：`spider_issue_data` 中 `project_id=9` 的 distinct 值，并按 ` & ` 拆分过滤占位值。
- 功能名：来自代码走查 `spider_crowncad_data` 的 `function_name` 且项目为 CrownCAD，这个历史实现比较特殊。
- 提交人：来自 GitLab 项目 `9` 的用户列表，不是只来自当前议题事实。
- 议题状态：固定 `open/closed`。
- 严重程度：固定 `一级缺陷/二级缺陷/三级缺陷/未设定严重程度`。

新平台候选来源是 `issue_fact` 当前查询范围内的字段值。若后续按规则汇总改为所有议题范围，则候选会比老平台更宽；若按老平台项目 9 对齐，则候选需要同步收口。功能名候选目前缺失。

### 13. 状态值展示可能不一致

**影响范围：中。影响字段值一致性。**

老平台状态原样展示 `open/closed`。

新平台表格中通过标签显示：

- `closed` 显示为“已关闭”
- 非 `closed` 显示为“未关闭”

展开详情中仍展示原始 `issueState`。如果用户在主表核对字段值，新平台主表与老平台不一致。

### 14. 导出没有复用老平台空结果行为

**影响范围：低到中。影响空结果导出体验。**

老平台导出无结果时返回 `null` 并打印“不存在目标要求的内容”。

新平台 CSV 导出会返回只有表头或标签组快照的文件。这个体验更完整，但如果要 1:1 复刻，需要确认空结果时是否仍允许导出空表头。

## 暂不作为问题

1. 分页默认值和分页组件样式。
2. 新平台支持排序、标签组筛选、综合关键词、刷新状态、设置按钮，这些属于体验增强；只要不改变数据口径，可保留。
3. GitLab 链接使用配置化服务生成，而不是老平台硬编码内网地址。只要跳转到同一议题即可，不要求保留硬编码 URL。
4. 新平台提供 CSV 下载；文件类型是否必须改回 Excel，等待字段对齐时统一裁定。

## 建议后续对齐顺序

1. 先裁定默认项目范围：按规则汇总“所有议题”，还是按老平台默认 CrownCAD 项目 `9`。
2. 修正条件筛选/标签组筛选路径，确保不意外套用系统测试 scope。
3. 补齐功能名字段的查询、候选、响应、详情和导出。
4. 对齐测试阶段候选与多选语义。
5. 对齐模块展示分隔符和老平台特殊筛选逻辑。
6. 对齐展开详情字段名和字段集合。
7. 对齐导出字段。

## 本轮对齐记录

> 更新时间：2026-06-17

已按规则汇总优先落地以下修正：

1. 议题查询 SQL 路径保持 `Scope.ALL`，普通条件筛选继续走事实层 SQL，不再因为添加条件筛选退回系统测试 scope。
2. 标签组筛选退回内存路径时改为读取全部 issue facts，不再调用系统测试 scope 过滤。
3. 功能名 `functionName` 已贯穿请求参数、列表响应、筛选候选、条件筛选、标签组字段、页面主表、展开详情和 CSV 导出。
4. 测试阶段筛选和候选改为使用完整 `testing_phase` / `primaryPhaseLabel()`，不再使用截断后的 `phaseFilterValue()`。
5. 模块展示值改为 ` & ` 拼接，页面拆分兼容 `、` 与 `&`。
6. 议题状态主表展示改为原始 `open/closed` 等事实值，不再翻译成“已关闭/未关闭”。
7. 老平台特殊筛选语义已进入事实层 SQL 查询：
   - `bugStatus=已修复` 同时匹配 `待合并/已修复/未更新`。
   - `category=建议和需求` 同时匹配 `建议/需求`。
   - `moduleName=曲线/曲面` 排除 `曲线曲面`。
8. 展开详情字段文案补齐老平台口径：功能名、议题提交时间、议题提交人、议题处理人、议题状态、测试状态、模块名。

仍保留为事实层缺口，不在本轮用空列或前端临时值伪造：

1. 老平台导出中的缺陷回复模板细项仍缺少事实字段来源，包括一级/二级/具体原因、修改方案、影响范围、测试情况、遗留问题、关联关系表等。
2. 若后续要求导出完全 1:1，需要先在采集/事实层补齐这些模板解析字段，再由议题查询导出读取事实字段。

验证记录：

1. 已执行后端编译命令 `mvn -q -DskipTests compile`，首次失败，失败原因集中在本轮新增 `functionName` / `useFullTestingPhaseFilter` 后的构造器和参数同步问题。
2. 已根据编译错误完成静态修复：补充兼容构造器、修正 filter group SQL 参数传递、补齐前端类型 mock。
3. 按 `AGENTS.md` “失败即停、禁止修复后自动重跑”规则，本轮未再次执行同一个后端编译命令；前端 typecheck 也未继续执行。

## 2026-06-17 最后一轮复查记录

### 复查依据

本轮重新对照以下来源：

- 规则汇总：`docs/platform-page-business-rules.md`
- 老平台前端：`D:\projects\spidergitdata-dev\webapp\src\views\PageStandard\IssueSearch.vue`
- 老平台列表/导出接口：`D:\projects\spidergitdata-dev\src\main\java\com\huayun\controller\IssueStaticDataController.java`
- 老平台导出字段：`D:\projects\spidergitdata-dev\src\main\java\com\huayun\entity\bo\IssueExcelBo.java`
- 新平台页面：`frontend/src/views/SystemTestIssueSearchView.vue`
- 新平台接口与服务：`QuestionMetricsController`、`SystemTestIssueSearchService`、`IssueFactRecordRepository`

### 已确认对齐

1. 议题查询页按规则汇总特殊口径走 `Scope.ALL`，不套系统测试默认排除规则；条件筛选和标签组筛选路径也不再回落到系统测试 scope。
2. 功能名已进入列表、筛选候选、条件筛选、响应、展开详情和导出。
3. 测试阶段候选和筛选使用完整 `testing_phase` / `primaryPhaseLabel()`，不再使用截断后的 `phaseFilterValue()`。
4. 模块显示使用 ` & ` 拼接，已向老平台展示格式靠齐。
5. 主表议题状态显示原始事实值 `open/closed`，不再翻译成“已关闭/未关闭”。
6. 老平台特殊筛选语义已在 SQL 查询层保留：
   - `bugStatus=已修复` 同时匹配 `待合并`、`已修复`、`未更新`。
   - `category=建议和需求` 同时匹配 `建议`、`需求`。
   - `moduleName=曲线/曲面` 排除 `曲线曲面`。
7. 展开详情已补齐老平台核心字段和文案：议题更新时间、议题提交时间、模块名、功能名、议题编号、议题标题、议题提交人、议题处理人、议题状态、测试状态、议题严重程度。

### 仍未完全对齐的功能缺口

1. **导出字段仍未 1:1 对齐老平台。**

   老平台 `IssueExcelBo` 导出字段包含：议题指派人、优先级、延期原因、缺陷修复人、修复状态、一级缺陷原因、二级缺陷原因、具体原因、修改方案、由修改其他缺陷造成的、修改该缺陷可能影响的功能、是否对可能影响的功能进行了测试、有无遗留问题或潜在的影响、是否更新了关联关系表等。

   新平台当前 `issue_fact` 已有 `priority_level`、`delay_cause`、`function_name`，但议题查询导出未全部输出；`fix_user`、原始缺陷原因文本 `cause` 和模板解析细项尚未沉淀到事实层，不能在导出层临时伪造。

   结论：这是事实层/导出层缺口。要完全对齐，需要在采集/事实层补齐老平台 `SpiderIssueData.cause`、`fixUser` 等来源字段，并复用老平台 `CauseUtil` 等价解析规则后再输出。

2. **顶部测试阶段仍是单选，不是老平台直接多选。**

   老平台 `IssueSearch.vue` 的“测试阶段”是多选，传 `phaseNameList` 后端按 `testing_phase in (...)` 查询。新平台可通过条件筛选 OR 组合复现多阶段查询，但顶部显性入口仍为单选。若要求用户无需进入条件筛选即可复刻老平台操作，需要将议题查询页的测试阶段入口改为多选，并在后端请求层支持多个 `testingPhase`。

3. **下拉候选来源与老平台仍不完全一致。**

   老平台部分候选值来自 `spider_issue_data project_id=9`，提交人来自 GitLab 项目用户列表，功能名历史上来自代码走查数据源；新平台候选来自 `issue_fact` 当前范围。规则汇总要求议题查询为所有议题范围，因此新平台当前方向符合规则汇总，但如果业务要求完全复刻老平台候选列表，需要单独裁定“候选值按老平台项目 9 收口”还是“按规则汇总全量事实展开”。

4. **导出空结果行为不同，暂不建议强行复刻。**

   老平台空结果导出返回 `null`；新平台会返回带表头的 CSV。该差异不影响数据集合和字段值，但若产品要求 1:1，需要统一空结果下载行为。

### 冒烟测试与真实链路测试记录

本轮尝试按最新代码拉起后端并验证议题查询链路：

1. 先请求 `http://localhost:18080/api/auth/current`，结果为“无法连接到远程服务器”，确认 `18080` 未可用。
2. 检查前端 `18181`，返回 HTTP 200，前端 dev server 存活。
3. 停止当前仓库下无监听端口的旧 Java 进程后，通过 `backend/run-backend.ps1` 拉起最新后端。
4. 后端启动在 Maven 编译阶段失败，`18080` 未监听。

失败证据：

- 日志：`logs/backend-issue-search-smoke-20260617.out.log`
- 典型错误：`FactBuildService.java` 中大量 `IssueFact` / `MergeRequestFact` 的 `setXxx/getXxx` 方法找不到，例如 `setModuleNames`、`setFunctionName`、`setTestingPhase`、`getFixed`、`setSourceSystem` 等。

结论：

- 本轮无法完成 API 冒烟和页面真实链路测试，阻断原因是后端最新代码编译失败，不是议题查询业务接口返回异常。
- 按 `AGENTS.md` 测试策略“失败即停、禁止修复后自动重跑”，本轮未继续绕路重跑验证。

## 2026-06-18 筛选能力复用判断

### 结论

议题查询页的高级筛选能力适合抽成记录类表格的通用能力，但不应直接套到统计类表格。

### 依据

老平台记录类页面的筛选方式高度相似：顶部或侧边提供多个字段筛选，字段包括编号、标题、模块、功能、人员、状态、严重程度、测试状态、类别、里程碑、提交/更新时间等；用户的目标是收窄一批明细记录。新平台议题查询已经把这些能力沉到 `filterGroup`、字段候选、标签组值控件和事实层 SQL 查询中，具备复用基础。

统计类表格的入口语义不同。系统测试缺陷汇总、缺陷原因分析、议题阶段统计等页面首先需要的是页级数据范围切换，例如项目/测试阶段/轮次；主表数字来自固定统计口径和规则说明。老平台统计类页面多数没有任意字段高级筛选，强行把记录类高级筛选铺到统计板上，容易把“数据范围选择”和“临时明细过滤”混在一起，导致用户误以为统计口径也被自由改写。

### 后续设计边界

1. 记录类表格可以复用议题查询的高级筛选：字段候选、条件组、标签组、查询、重置、导出都走同一套事实层过滤能力。
2. 统计类表格先保留页级上下文条和少量明确业务筛选。是否引入高级筛选必须逐页确认，并在规则说明中解释筛选影响的统计范围。
3. 系统测试缺陷汇总这类页面缺的是老平台项目/测试阶段级联切换，不应把它当作普通高级筛选补丁处理。
