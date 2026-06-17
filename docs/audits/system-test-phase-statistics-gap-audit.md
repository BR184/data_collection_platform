# 议题阶段统计页新旧平台差异记录

> 记录日期：2026-06-17
> 新平台：`D:\projects\data_collection_platform`
> 老平台：`D:\projects\spidergitdata-dev`
>
> 本文只记录会影响数据集合、字段展示、统计口径、下钻明细或功能可达性的差异。分页默认值、UI 样式和筛选组件形态不作为问题。

## 对齐目标

同一测试阶段/筛选条件下，新平台应与老平台及规则汇总保持：

1. 轮次行集合一致。
2. 一级缺陷、二级缺陷、三级缺陷、建议类缺陷和总计数量口径一致。
3. 下钻明细命中的议题集合一致。
4. 下钻字段名、字段值和展示顺序与老平台通用议题详情保持一致。
5. 默认测试阶段和项目范围一致。
6. 规则说明与实际统计口径一致。

## 老平台基线

### 页面入口和主接口

老平台页面：

- `webapp/src/views/PageStandard/DefectAndPhaseTable.vue`

老平台接口：

- 主表：`GET /dataAnalysis/getDefectAndPhaseTable`
- 下钻明细：复用 `ModuleTableDetail.vue` 的 `issueStaticData/filter` 查询链路。

相关代码：

- `DataAnalysisController.getDefectAndPhaseTable(...)`
- `SpiderIssueDataDAOImpl.getNumByDefectAndPhase(...)`
- `DefectAndPhaseTableRow`
- `DefectLevelEnum`
- `QueryUtil.setQueryFilter(...)`

### 老平台主表字段

页面可见字段：

- 轮次
- 一级缺陷(个)
- 二级缺陷(个)
- 三级缺陷(个)
- 建议类缺陷(个)
- 总计(个)

`DefectAndPhaseTableRow` 导出/实体字段：

- 伦次
- 一级缺陷
- 二级缺陷
- 三级缺陷
- 建议类缺陷
- 总计

说明：老平台实体字段历史写作 `伦次`，页面显示为 `轮次`。

### 老平台统计口径

老平台 `DataAnalysisController.getDefectAndPhaseTable(phase)`：

1. 如果 `phase` 为空，返回空字符串。
2. 通过 `testingPhaseService.getByName(phase)` 获取该项目/阶段下维护的具体轮次列表。
3. 遍历轮次列表，每个轮次都生成一行。
4. 对每个轮次遍历 `DefectLevelEnum.values()`：
   - 一级缺陷：`severity_level like 一级缺陷`
   - 二级缺陷：`severity_level like 二级缺陷`
   - 三级缺陷：`severity_level like 三级缺陷`
   - 建议类缺陷：`category like 建议`
5. 固定项目为 CrownCAD：`ProjectList.CC_PROJECT_ID`。
6. 每个数量查询都调用 `QueryUtil.setQueryFilter(...)`：
   - 非 CC_PRODUCT 项目排除 `category like 功能屏蔽`
   - 排除 `bug_status like 已拒绝`
   - 排除 `category like 建议`
   - 排除 `bug_status like 申请否决` 且关闭
   - 排除 `bug_status like 需求如此` 且关闭
7. `DefectAndPhaseTableRow.total = critical + major + minor + suggestion`。

### 老平台默认行为

老平台页面 mounted 后：

1. 调用 `getLabelPhase()`。
2. 默认取 `testingPhaseList[0]`。
3. 立即按默认测试阶段查询。

因此老平台首次进入页面不是“全部系统测试阶段”，而是“阶段列表第一项”。

## 新平台现状

新平台页面：

- 菜单 key：`question-metrics-phase-statistics`
- 路径：`/question-metrics/phase-statistics`
- 统计板 key：`system-test-phase-statistics`
- 后端服务：`SystemTestPhaseStatisticsBoardService`

新平台已具备：

1. 页面入口。
2. 轮次维度统计表。
3. 一级缺陷、二级缺陷、三级缺陷、建议类缺陷、总计列。
4. 单元格下钻。
5. 规则说明。
6. 统一统计板导出能力。
7. 实时刷新状态。

## 待标记差异

### 1. 默认测试阶段范围不一致

**影响范围：高。影响首次进入页面的总数、行集合和下钻。**

老平台首次进入页面默认选中 `getLabelPhase()` 返回的第一项，并立即查询该阶段。

新平台当前无测试阶段筛选时，会保留全部可识别系统测试/回归测试轮次：

```java
"未填写时保留全部轮次。"
```

这会导致首屏数据范围比老平台更大。

### 2. 轮次行集合来源不一致

**影响范围：高。影响 0 值行、行顺序和用户核对。**

老平台行集合来自 `testingPhaseService.getByName(phase)`，即管理员维护的项目阶段定义。只要阶段定义里存在某个轮次，就会生成一行，即使数量为 0。

新平台当前按实际命中议题动态建桶：

```java
buckets.computeIfAbsent(phaseKey, key -> new AggregateBucket(key, rowLabel)).accept(issue);
```

如果某个配置轮次当前没有命中议题，新平台会隐藏该行。若存在事实层未纳入阶段定义但标签文本里有“系统测试/回归测试”的轮次，新平台也可能展示老平台不会展示的行。

### 3. 项目范围不一致

**影响范围：高。影响所有数量。**

老平台本页固定使用 CrownCAD 项目：

```java
ProjectList.CC_PROJECT_ID.getProjectId()
```

新平台当前 `SystemTestPhaseStatisticsBoardService` 在无 `projectId` 筛选时读取全部 `issue_fact`，再按 `系统测试/回归测试` 标签收口。若镜像库中存在其他项目也携带系统测试或回归测试标签，新平台可能把非 CrownCAD 数据纳入统计。

### 4. 系统测试公共排除规则未完整应用

**影响范围：高。影响所有列数量。**

老平台每个数量查询都调用 `setQueryFilter(...)`，规则汇总 4.2 也要求系统测试默认排除功能屏蔽、已拒绝、建议、申请否决关闭、需求如此关闭。

新平台当前 `buildRuleFlowSnapshot(...)` 只执行：

```java
.filter(IssueSource::inSystemTestScope)
.filter(issue -> StringUtils.hasText(issue.primaryPhaseLabel()))
```

`FACT_SQL` 虽然读取了 `is_excluded`，但 `IssueSource` 没有保存该字段，后续也没有 `.filter(issue -> !issue.excluded())`。这会把老平台应排除的数据计入阶段统计。

### 5. 建议类缺陷口径不一致

**影响范围：中到高。影响建议类列和总计。**

老平台 `DefectLevelEnum.Suggestion` 使用：

- 字段：`category`
- 匹配值：`建议`

新平台当前用：

```java
boolean isSuggestion() {
  return isSeverity("SUGGESTION");
}
```

如果事实层没有把 `category like 建议` 映射为 `severity_level = SUGGESTION`，建议类数量会不一致。

同时需注意：老平台 `setQueryFilter(...)` 又会排除 `category like 建议`。这会导致老平台“建议类缺陷(个)”列在公共过滤后可能为 0。新平台必须复刻老平台实际查询行为，不能为了让建议列“看起来合理”而擅自纳入建议类。

### 6. 总计口径与规则汇总存在冲突

**影响范围：高。影响总计列。**

老平台 `DefectAndPhaseTableRow.total` 为：

```java
critical + major + minor + suggestion
```

规则汇总 4.7 当前写明：

- 议题阶段统计只统计一级缺陷、二级缺陷、三级缺陷。
- 指定轮次总计 = 一级缺陷数量 + 二级缺陷数量 + 三级缺陷数量。

新平台当前为：

```java
long total = issues.size();
```

这比老平台和规则汇总都更宽，会把未归类严重程度等数据也计入总计。

该项需要业务确认最终口径：

- 若严格复刻老平台页面：总计 = 一级 + 二级 + 三级 + 建议。
- 若严格遵守规则汇总 4.7：总计 = 一级 + 二级 + 三级，并应移除或弱化建议类列。

无论采用哪一种，新平台当前 `issues.size()` 都需要修正。

### 7. 下钻字段少于老平台通用议题详情

**影响范围：中。影响用户核对统计来源。**

老平台下钻复用 `ModuleTableDetail.vue`，通用展示字段包括：

- 议题编号
- 模块名
- 议题标题
- 议题状态
- 严重程度
- 测试状态
- 延期原因
- 议题更新时间
- 议题提交时间
- 议题提交人
- 议题处理人

新平台当前详情字段为：

- 议题编号
- 标题
- 测试阶段
- 严重程度
- 模块
- 所属项目
- 创建人
- 状态
- 更新时间

缺少或未按老平台字段名展示：

- 测试状态
- 延期原因
- 议题提交时间
- 议题处理人
- 模块名/议题标题/议题状态等老平台字段名。

### 8. 下钻总计列匹配范围过宽

**影响范围：中到高。影响点击总计后的明细集合。**

老平台总计来自 `critical + major + minor + suggestion`，点击各严重程度列按对应字段查询。老平台页面总计列不可点击。

新平台当前 `total` 单元格可下钻，并且匹配条件是：

```java
case "total" -> issue -> true;
```

如果保留总计下钻，应至少与最终总计口径一致，不能把未归类严重程度议题展示进去。如果对齐老平台页面，也可以将总计列设为不可下钻。

### 9. 阶段筛选选项来源不一致

**影响范围：中到高。影响用户可选范围和默认范围。**

老平台阶段选项来自 `getLabelPhase()` / 阶段定义。

新平台阶段选项来自 `issue_fact.testing_phase`、`system_test_label` 和 `label_names` 动态抽取后裁剪：

```java
phaseFilterValue(...)
```

这可能导致：

- 已配置但当前无数据的阶段无法选择。
- 有事实标签但未在阶段定义中维护的阶段被展示。
- 字符串裁剪把不同阶段合并成同一个选项。

### 10. 规则说明与实际口径不一致

**影响范围：中。影响用户理解和排查。**

新平台规则说明当前写：

- “未填写时保留全部轮次”
- “总计 = 一级 + 二级 + 三级 + 建议类 + 其他未归类严重程度议题”
- “建议类缺陷按 issue_fact.severity_level = SUGGESTION”

这些描述与老平台默认阶段、规则汇总 4.7、老平台 `category like 建议` 口径均存在差异。修正统计口径后，规则说明也需要同步修正。

### 11. 多元看板阶段图相关能力未在本页闭环

**影响范围：中。属于相关能力，不完全等同独立页面。**

老平台多元看板中还有“缺陷阶段分析”图表及导出：

- `GET /dataAnalysis/getDefectAndPhaseHistogram`
- `GET /dataAnalysis/exportDefectAndPhaseHistogram`

新平台多元看板当前有“阶段分布”图，但不提供该图的独立导出和原始数据 Excel。该项更适合放在 `议题多元看板` 审计中统一处理；本页先记录关联，不作为独立页面立即修正项。

## 暂不记录为问题

- 老平台表格高度、按钮样式、固定列样式与新平台不同。
- 新平台统一条件筛选能力强于老平台，不作为缺口；但筛选后的数据口径必须一致。
- 新平台额外提供统一统计板导出、规则说明、实时刷新状态，这些是增强能力，不属于老平台缺失功能。

## 当前结论

`议题阶段统计` 目前尚未达到“已与老平台和规则汇总完全对齐”的状态。优先级最高的待确认/待对齐项是：

1. 默认测试阶段应按老平台取阶段列表第一项，还是按新平台全量轮次。
2. 总计最终采用老平台 `一级+二级+三级+建议`，还是规则汇总 `一级+二级+三级`。
3. 行集合和筛选选项是否统一改为阶段定义来源。
4. 是否固定 CrownCAD 项目范围并套用系统测试公共排除规则。
5. 下钻字段和总计下钻行为是否按老平台通用议题详情对齐。

## 2026-06-17 对齐实现记录

本轮已按老平台和规则汇总完成以下实现：

1. 默认项目范围已固定为老平台 CrownCAD 项目 `9`；如果请求显式传入 `projectId`，才按请求项目查看。
2. 测试阶段筛选选项改为来自 `testing_phase_calendar` 中启用的阶段定义，不再从事实标签动态抽取。
3. 未选择测试阶段时，自动使用阶段定义选项第一项作为默认阶段，并通过 `appliedFilters` / `filterGroup` 回填到页面。
4. 主表轮次行改为由阶段定义预建，当前阶段下某个轮次没有数据时也保留 0 值行。
5. 统计范围补齐系统测试公共排除规则，使用 `issue_fact.is_excluded = false`，避免功能屏蔽、已拒绝、建议、申请否决关闭、需求如此关闭等数据进入统计。
6. 统计结果只保留阶段定义中存在的轮次，未维护在阶段定义里的临时标签不会生成额外行。
7. 总计口径按规则汇总 4.7 收口为 `一级缺陷 + 二级缺陷 + 三级缺陷`，不再使用当前轮次全部 issue 数。
8. `建议类缺陷(个)` 列保留老平台可见表头；公共排除规则生效后，默认会与老平台实际过滤行为一致，建议类数据通常不进入本页统计。
9. 总计列改为不可下钻，避免出现老平台没有的总计明细入口，也避免总计明细范围与总计数不一致。
10. 下钻字段补齐老平台通用议题详情字段：模块名、议题标题、议题状态、严重程度、测试状态、延期原因、议题提交时间、议题提交人、议题处理人、更新时间。
11. 规则说明已同步为阶段定义、默认阶段、公共排除规则和总计口径。

验证：

- 后端编译：`mvn -q -DskipTests compile` 通过。

仍需后续在 `议题多元看板` 审计中处理的关联项：

1. 老平台多元看板“缺陷阶段分析”图表导出和原始数据 Excel 能力。
