# 申请延期缺陷分析页新旧平台差异记录

> 记录日期：2026-06-15
> 新平台：`D:\projects\data_collection_platform`
> 老平台：`D:\projects\spidergitdata-dev`
>
> 本文只记录会影响数据一致性、字段一致性或功能可达性的差异。分页默认值、UI 形态、筛选控件使用方式等不作为问题记录。

## 对齐目标

同一测试阶段/筛选条件下，新平台应与老平台保持：

1. 延期原因行集合一致。
2. 一级缺陷、二级缺陷、三级缺陷、建议类缺陷和总计数量一致。
3. 当前汇总导出字段和字段值一致。
4. 下钻明细命中的议题集合一致。
5. 下钻表格字段名、字段值和展示顺序与老平台通用议题详情保持一致。
6. 页面规则说明和统计口径与 `docs/platform-page-business-rules.md` 一致。

## 老平台基线

### 页面入口和主接口

老平台存在两个相关入口：

- 独立菜单：`PageHome/Home.vue` 中的 `申请延期缺陷分析`，路由组件为 `webapp/src/views/PageDelayCause/DelayCauseInfo.vue`。
- 多元看板 Tab：`webapp/src/views/PageStatisticsInfo/StatisticsInfo.vue` 中的 `申请延期缺陷原因分析`，图表组件为 `webapp/src/views/PageStatisticsInfo/charts/DelayCauseChart.vue`。

本轮对齐以新平台 `question-metrics-delay-analysis` / `system-test-delay-analysis` 对应的“延期原因维度缺陷分析”为基线。

老平台相关接口：

- 表格主数据：`GET /dataAnalysis/getDefectAndDelayCauseTable`
- 图表主数据：`GET /dataAnalysis/delayCauseAndTestingPhase`
- 表格导出：`GET /dataAnalysis/exportDefectAndDelayCauseTable`
- 图表导出：`GET /dataAnalysis/exportDelayCauseAndTestingPhase`
- 下钻明细：复用 `ModuleTableDetail.vue` 的 `issueStaticData/filter` 查询链路。

### 老平台固定延期原因

来自 `DelayEnum`：

- 技术卡点
- 方案卡点
- 资源卡点
- 数据异常
- 算法问题
- 机制问题
- 计算效率

### 老平台主表字段

`DelayCauseInfo.vue` 表格可见字段为：

- 延期原因
- 一级缺陷(个)
- 二级缺陷(个)
- 三级缺陷(个)
- 建议类缺陷(个)
- 总计(个)

`DefectAndPhaseTableRow` 导出字段为：

- 伦次
- 一级缺陷
- 二级缺陷
- 三级缺陷
- 建议类缺陷
- 总计

说明：这里导出实体首列历史字段名是 `伦次`，但页面首列显示为 `延期原因`。新平台若导出统计板，应优先按页面可见语义展示 `延期原因`，但需要记录老平台 Excel 历史字段名差异。

### 老平台核心口径

老平台 `DataAnalysisController.getDefectAndDelayCauseTable()`：

- 先通过 `testingPhaseService.getByName(phase)` 获取该阶段下的具体轮次/阶段集合。
- 遍历 `DelayEnum.values()`，无论某个原因数量是否为 0 都生成一行。
- 遍历 `DefectLevelEnum.values()`：
  - 一级缺陷：`severity_level like 一级缺陷`
  - 二级缺陷：`severity_level like 二级缺陷`
  - 三级缺陷：`severity_level like 三级缺陷`
  - 建议类缺陷：`category like 建议`
- 延期原因来自 `delay_cause`，老平台代码注释明确写明：“延期统计分析为了规范化，仍沿用之前的统计标准，只统计含有相应延期原因的标签数据”。
- 项目固定为 CrownCAD 项目：`ProjectList.CC_PROJECT_ID`。

## 已确认一致或基本具备

### 1. 新平台已有页面入口

新平台已有：

- 菜单页：`question-metrics-delay-analysis`
- 路径：`/question-metrics/delay-analysis`
- 看板键：`system-test-delay-analysis`

### 2. 新平台已有基础主表、下钻、规则说明和导出

新平台 `SystemTestDelayAnalysisBoardService` 已提供：

- 主表字段：延期原因、一级缺陷、二级缺陷、三级缺陷、建议类缺陷、总计。
- 下钻接口：`/api/statistic-boards/system-test-delay-analysis/details`
- 规则说明：`/api/statistic-boards/system-test-delay-analysis/rule-explanation`
- 通用汇总导出：`/api/statistic-boards/system-test-delay-analysis/export`
- 实时刷新状态。

## 待标记差异

### 1. 延期原因行集合来源不一致

**影响范围：高。影响主表行集合、0 值行和导出。**

老平台固定遍历 `DelayEnum.values()`，即 7 个延期原因都应展示，即使某个原因当前数量为 0：

- 技术卡点
- 方案卡点
- 资源卡点
- 数据异常
- 算法问题
- 机制问题
- 计算效率

新平台当前按 `snapshot.finalSources()` 中实际出现的 `issue.delayCause()` 动态建桶：

```java
buckets.computeIfAbsent(issue.delayCause(), AggregateBucket::new).accept(issue);
```

这会导致没有命中议题的延期原因行被隐藏，和老平台固定枚举行不一致。

### 2. 总计行显示不一致

**影响范围：中。影响主表字段值和用户对照老平台。**

老平台 `DelayCauseInfo.vue` 没有额外追加“共计/总计”整行，只在每个延期原因行里展示 `总计(个)` 列。

新平台当前在有数据时追加了 `共计` 行：

```java
rows.add(new AggregateBucket(TOTAL_ROW_LABEL, TOTAL_ROW_KEY).acceptAll(snapshot.finalSources()).toRowData());
```

如果新平台保留这行，导出和主表会比老平台多一行。UI 增强可以接受，但这里会影响“行集合一致”和导出结果一致，需要标记。

### 3. 延期原因字段显示值可能不一致

**影响范围：高。影响行名、筛选、导出和下钻。**

老平台行名来自 `DelayEnum.getFindName()` / `DelayEnum.getName()`，固定为规范枚举值。

新平台行名直接来自事实层 `issue_fact.delay_cause`。如果事实层未完全按老平台 `DelayEnum` 归一化，或出现多个延期原因、别名、空格、历史文案，新平台会展示老平台没有的行名，也可能漏掉老平台固定行。

该问题应在事实层或本页枚举聚合层处理，不能让页面展示任意原始值。

### 4. 测试阶段筛选值来源和默认值需核对

**影响范围：中到高。影响统计范围。**

老平台测试阶段下拉来自：

- `getLabelPhase()`
- 页面 mounted 后默认选中 `testingPhaseList[0]`
- 查询时通过 `testingPhaseService.getByName(phase)` 展开具体阶段集合

新平台测试阶段选项来自 `issue_fact.testing_phase`、`system_test_label` 和标签候选，再通过 `phaseFilterValue()` 去掉“第 N 轮系统测试/回归测试”后缀。

需要确认：

- 新平台默认未选择时是否统计全部系统测试范围，而老平台默认选中第一项。
- 新平台阶段值是否和老平台 `getLabelPhase()` 返回值完全一致。
- 新平台 `phaseFilterValue()` 的字符串裁剪是否会把不同阶段错误合并。

如果默认范围不同，同一首次打开页面的数据总数会不一致。

### 5. 主表排序规则不一致

**影响范围：中。影响行顺序和导出顺序。**

老平台后端图表接口 `delayCauseAndTestingPhase` 会按：

1. `sum` 倒序
2. `critical` 倒序

表格接口 `getDefectAndDelayCauseTable` 按 `DelayEnum.values()` 顺序生成。

新平台当前按延期原因名称字母序排序：

```java
.sorted(Comparator.comparing(AggregateBucket::rowLabel, String.CASE_INSENSITIVE_ORDER))
```

如果新平台页面定位为老平台独立表格，应按 `DelayEnum` 顺序；如果定位为多元看板图表，应按 `sum/critical` 排序。当前字母序与两种老平台表现都不一致。

### 6. 建议类缺陷统计口径可能不一致

**影响范围：中到高。影响 `建议类缺陷` 列和总计。**

老平台建议类用 `DefectLevelEnum.Suggestion`：

- 字段：`category`
- 匹配：`建议`

新平台当前建议类用：

```java
"SUGGESTION".equalsIgnoreCase(issue.severityLevel())
```

如果事实层把建议类放在 `category` 而不是 `severity_level = SUGGESTION`，新平台建议类数量会少算。按老平台应以 `category like 建议` 为准，除非事实层明确保证已映射为 `SUGGESTION`。

### 7. 系统测试默认排除规则与老平台延迟原因统计需复核

**影响范围：高。影响所有数量。**

老平台本页查询通过 `SpiderIssueDataQueryBuilder` 和 `spiderIssueDataDAO.getNumByDefectAndPhaseAndDelayCause()`，并且代码注释强调“只统计含有相应延期原因的标签数据”。

新平台当前先执行：

```java
.filter(issue -> !issue.excluded())
.filter(IssueSource::hasDelayCause)
```

需要确认 `issue_fact.is_excluded` 与老平台该页使用的过滤条件完全一致，尤其是：

- 功能屏蔽
- 已拒绝
- 建议类是否应排除
- 申请否决/需求如此等关闭态过滤

如果事实层 `is_excluded` 是全局系统测试过滤，可能会把老平台本页仍需要统计的建议类过滤掉，进而影响建议类和总计。

### 8. 下钻字段少于老平台通用议题详情

**影响范围：中。影响用户核对统计来源。**

老平台本页下钻复用 `ModuleTableDetail.vue`，可见字段包括：

- 议题编号
- 模块名
- 议题标题
- 议题状态
- 严重程度
- 测试状态
- 延期原因
- 展开区：议题更新时间、议题提交时间、模块名、议题编号、议题标题、议题提交人、议题处理人、议题状态、测试状态、议题严重程度

新平台当前下钻字段为：

- 议题编号
- 标题
- 测试阶段
- 延期原因
- 严重程度
- 模块
- 所属项目
- 创建人
- 状态
- 更新时间

缺少或未按老平台字段名展示：

- 议题标题
- 议题状态
- 测试状态
- 议题提交时间
- 议题提交人
- 议题处理人
- 模块名

### 9. 下钻严重程度/建议类匹配语义需要对齐

**影响范围：中。影响点击数字后的明细集合。**

老平台下钻：

- 一级/二级/三级：传 `severityLevel = 一级缺陷/二级缺陷/三级缺陷`
- 建议类：传 `category = 建议`
- 延期原因：传 `delayCause = 当前行`
- 测试阶段：传 `phaseName = 当前 testingPhase`

新平台下钻：

- 一级/二级/三级：按 `severityLevel = LEVEL1/LEVEL2/LEVEL3`
- 建议类：按 `severityLevel = SUGGESTION`
- 延期原因：按 `rowKey.equals(issue.delayCause())`

建议类仍存在与第 6 项相同的事实字段映射风险。

### 10. 图表能力没有在本页等价呈现

**影响范围：低到中。功能可达性差异。**

老平台“申请延期缺陷原因分析”在多元看板中以 ECharts 堆叠柱状图展示，并带：

- 测试阶段下拉
- 下载按钮
- 图例/tooltip/dataView/brush/magicType stack

新平台当前页面是统一统计表格，不是图表页；多元看板里有“延期原因分布”横向条形图，但只展示 `total` Top 8，不展示一级/二级/三级/建议类堆叠数据。

如果业务要求新平台本页完全覆盖老平台多元看板图表能力，需要补等价堆叠图或确认统一表格替代图表属于可接受 UI 差异。

### 11. 导出入口和导出字段需核对

**影响范围：中。影响用户离线核对数据。**

老平台存在两个导出：

- `exportDefectAndDelayCauseTable`：表格导出，字段来自 `DefectAndPhaseTableRow`。
- `exportDelayCauseAndTestingPhase`：原因分析导出，字段来自 `CauseAndLevelDTO`。

新平台当前只有统计板通用 CSV 导出。需要核对：

- 导出是否使用与页面完全一致的统计范围。
- 是否会多导出新平台 `共计` 行。
- 首列字段名是否为老平台页面语义 `延期原因`，还是通用行头。
- 建议类字段名是否应为 `建议类缺陷` / `需求&建议类` / `建议类缺陷(个)`。

### 12. 缺少老平台“空 phase 返回空字符串”的等价边界

**影响范围：低到中。影响空筛选状态。**

老平台接口在 `phase` 为空时直接返回 `""` 或 `null` 导出。

新平台默认未选择测试阶段时会保留全部系统测试阶段。这个体验更好，但会导致空筛选状态下数据范围与老平台不同。若要 1:1 对齐老平台默认行为，需要明确默认测试阶段或空阶段行为。

## 暂不记录为问题

- 页面布局从图表/表格变为统一统计板的 UI 差异。
- 分页默认值。
- 按钮样式、图标样式差异。
- 统一条件筛选组件替代老平台单一下拉的交互差异。

## 2026-06-15 复查结论

本轮仅完成代码对比和差异记录，未修改代码。优先级最高的待对齐项是：

1. 固定 7 个延期原因行，不隐藏 0 值行。
2. 明确并对齐空/默认测试阶段范围。
3. 建议类缺陷按老平台 `category like 建议` 口径确认或修正。
4. 下钻字段与老平台通用议题详情对齐。
5. 导出字段和是否包含额外 `共计` 行需与老平台确认并对齐。
