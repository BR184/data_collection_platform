# Filter Tag Groups Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.
>
> **Execution environment override (PM directive 2026-06-05):** 不要建立 git worktree 进行隔离实现。本方案的所有任务直接在 `D:\projects\data_collection_platform` 主工作树（当前 `main` 分支）上完成；不调用 `EnterWorktree`，也不在 `.claude/worktrees/` 下创建副本。如果 `executing-plans` 子技能默认要建 worktree，请跳过该步骤，直接进入任务实现。其余流程（任务分步、每步等待项目经理审批、验证项）保持不变。

**Goal:** 在不推翻现有议题查询固定字段筛选能力的前提下，将 `frontend/src/components/StatisticFilterBuilder.vue` 这种“字段 + 关系 + 值”的高级条件构建器从业务用户主入口中弱化或替换掉，并新增“标签组筛选”作为业务化、可收口的筛选入口，用来解决模块分类不一致、GitLab 标签脏数据、历史死项目数据干扰等问题。

**Architecture:** 前端保留现有 `BaseRecordTable`、`RecordTableFilterFields` 的固定字段筛选基础能力；`StatisticFilterBuilder` 继续作为统计板、管理员或复杂报表的高级条件构建器保留。议题查询页当前已经是固定字段筛选，不存在替换 `StatisticFilterBuilder`；评审数据管理、非法记录、客户问题等仍直接挂载 `StatisticFilterBuilder` 的业务页才是一期收口重点。模块标准化以现有 `ModuleDictionaryService` / `IssueFactNormalizationRules` 为 source of truth，标签组只做展示口径、选择入口和查询适配，不另起第二套“标准模块”字典。标签组的映射展开放在后端；前端只发送 `tagSelections`，不重复 `StatisticFilterBuilder` 的操作符语义。

**Tech Stack:** Vue 3、Element Plus、TypeScript、Spring Boot、JPA、PostgreSQL、GitLab 标签同步数据。

---

## 背景判断

当前新平台已经有两种筛选形态，且它们需要明确区分：

- 固定字段筛选：议题查询页通过 `frontend/src/views/SystemTestIssueSearchView.vue` 配置 `primaryFilters` 和 `advancedFilters`，再交给 `frontend/src/components/base/RecordTableFilterFields.vue` 渲染，以“主筛选 + 更多筛选折叠区”的方式提供项目、负责人、状态、严重程度、缺陷分类、里程碑、时间等下拉筛选。它速度快，适合作为业务用户日常入口。
- 高级条件构建器：`frontend/src/components/StatisticFilterBuilder.vue` 以“字段 + 关系 + 值”的方式表达更复杂的条件，适合统计板、复杂报表、管理员场景，但不适合作为业务用户的默认筛选样式。

用户对“搜索”的需求并不强，核心诉求更像是：

- 筛出自己想看的数据。
- 长期按某种口径查看数据。
- 对不同模块、轮次、里程碑、严重程度、负责人等维度进行对比。
- 导出当前口径下的数据表格。

因此后续不应继续强化“综合搜索 / 模块关键词”，也不应让 `StatisticFilterBuilder.vue` 这类高级条件构建器继续承担业务页主筛选入口，而应将筛选能力向“固定字段筛选 + 标签组收口 + 可保存视图”演进。

## 旧平台源码校准

本方案已按旧平台源码 `D:\projects\spidergitdata-dev` 做过一次口径校准。结论是：旧平台并不是把所有 GitLab label 放进一个池子后任意筛选，而是先把固定前缀标签解析成业务字段，再围绕这些业务字段生成下拉项、统计行和查询条件。

### 旧平台标签解析口径

旧平台核心标签分类来自 `D:\projects\spidergitdata-dev\src\main\java\com\huayun\entity\LabelName.java`：

```text
模块
工具箱
软件
项目
状态
测试阶段
严重程度
类别
紧急程度
延期原因
```

`D:\projects\spidergitdata-dev\src\main\java\com\huayun\service\impl\ParseDocumentServiceImpl.java` 的 `parseLabelMapByList` 会把带中文冒号 `：` 的 label 拆成 `key -> value`，例如 `模块：草图` 进入 `模块` 字段，`状态：已修复` 进入 `状态` 字段。没有冒号的标签只在特定规则下进入业务字段，例如包含“系统测试 / 回归测试 / 集成测试”的标签进入 `测试阶段`，紧急程度和延期原因通过固定枚举识别。

模块还有专门处理：

- `IssueServiceImpl.getCombinedLabelValue` 会把 `模块` 和 `工具箱` 两个来源合并成模块字段。
- 两者都存在且不同，使用 `&` 拼接。
- 两者相同或只有一个存在时，只保留一个模块值。
- 无有效模块时使用 `未设定模块`。

代码走查和 DGM 模块抽取还存在 `CCModuleFetcher`、`DGMModuleFetcher`，它们识别 `模块：X`、`工具箱：X` 和 `模块-X`、`工具箱-X`。旧平台源码没有证明普通议题标签解析支持英文冒号 `:`；新平台一期将兼容英文冒号作为容错增强，但文档和测试必须标注这是新平台增强，不是旧平台原有规则。

### 旧平台下拉收口口径

旧平台下拉项主要来自 `D:\projects\spidergitdata-dev\src\main\java\com\huayun\service\DropDownService.java`：

- 查询指定业务字段的 `DISTINCT` 值。
- 对多值字段按 `" & "` 拆分。
- 去重并保留首次出现顺序。
- 跳过以 `未设定` 开头的占位值。
- `fix_user` 额外跳过 `无合法评论`。
- 项目字段会跳过 `未标注项目名`。

这说明标签组的默认展示不应来自“全部原始 GitLab 标签”，而应优先来自归一化后的业务字段值或老平台等价字段口径。

### 旧平台模块匹配口径

旧平台模块匹配不是全局一种规则：

- 统计主表使用 `ModuleSplitUtil.isContainModule`，按 `&` 拆分、`trim` 后精确匹配目标模块。
- 普通议题明细查询使用 `SpiderIssueDataQueryBuilder.setModuleNameQuery`，对 `module_name` 做 `like`；当模块为 `曲线` 或 `曲面` 时额外排除 `曲线曲面`。
- 非法议题表部分接口对模块使用精确匹配。
- 客户问题延期表存在 `contains` 口径。

因此标签组实现不能简单规定“模块永远 contains”或“模块永远精确匹配”。应按页面数据域和旧平台接口语义决定匹配策略。

## 新平台现状校准

落地前必须先尊重新平台已经存在的归一化和筛选结构，否则标签组会变成第二套口径。

### 模块归一化 source of truth

新平台已经在 fact 构建阶段做模块归一：

- `backend/src/main/java/com/data/collection/platform/service/FactBuildService.java:362-365` 先通过 `IssueFactNormalizationRules.normalizeModuleNames(labels)` 从 GitLab labels 提取模块候选，再调用 `moduleDictionary.normalizeIssueModules(projectId, ...)`。
- `backend/src/main/java/com/data/collection/platform/service/ModuleDictionaryService.java` 读取 `module_dictionary`，支持 `COMMON`、`ISSUE`、`MERGE_REQUEST` 域和项目级规则。
- `FactBuildService.java:391-393` 把归一后的第一个模块写入 `issue_fact.module_name` / `primary_module_name`，把完整列表写入 `issue_fact.module_names`。

因此本计划对模块采用如下归口：

```text
ModuleDictionaryService / module_dictionary = 标准模块归一化 source of truth
IssueFactNormalizationRules.normalizeModuleNames = GitLab labels -> 模块候选的提取层
最终标准模块判定 = ModuleDictionaryService / module_dictionary
tag_group / tag_value = 标准模块和其他业务维度的展示、排序、启停、分组口径
tag_value_mapping = 非模块维度和历史/原始字段的补充映射；模块映射默认复用 module_dictionary，不重复维护同一批 alias
```

模块维度不使用 `tag_value_mapping(source_type='module_name')` 参与运行时归一化或查询展开；如需展示历史别名，只能通过 `module_dictionary_alias` 做展示层 join 或诊断引用，不能绕过 `module_dictionary`。

### 新平台多值分隔符

旧平台模块多值字段用 `&` 拼接，但新平台不是：

- `FactBuildService.java:393` 使用 `String.join(", ", moduleNames)` 写入 `issue_fact.module_names`。
- `FactBuildService.java:408` 同样使用 `String.join(", ", labels)` 写入 `issue_fact.label_names`。
- `IssueFactValueSupport.split` 和 `StatisticSourceValueSupport.split` 当前按逗号 `,` 拆分。
- `IssueFactFilterGroupSqlSupport.java:88` 和 `IssueFactRecordRepository.java:430` 当前通过 `lower(',' || replace(coalesce(module_names, ''), ', ', ',') || ',') like ?` 做逗号边界匹配。
- `IssueFactQueryService.java:64` 当前仍对 `module_names` 使用 `appendContains`，这是纯 substring contains，会让 `工具` 误命中 `工具箱`；Task 4a 必须把这条链路替换为逗号边界匹配或数组精确匹配。

所以“复刻旧平台 `&` 拆分精确匹配”不能直接照搬到 `issue_fact.module_names`。一期必须在 Task 3 前确定结构：

1. 继续使用 `module_names text`，按新平台逗号分隔做边界精确匹配。
2. 或新增 `module_name_array text[]`，用 GIN 索引承接高频精确匹配。
3. 或仅使用单值 `module_name`，放弃多模块展开语义。

推荐优先评估 `module_name_array text[]`；如果一期不改 schema，则文档、测试和实现都必须明确使用新平台逗号分隔，而不是旧平台 `&`。

### 当前筛选入口清单

已核对的页面现状：

| 页面 | 当前主筛选组件 | 一期动作 |
|---|---|---|
| `SystemTestIssueSearchView.vue` | `primaryFilters` / `advancedFilters` + `RecordTableFilterFields` | 增量补标签组；不是替换 `StatisticFilterBuilder` |
| `ReviewDataManagementView.vue` | `StatisticFilterBuilder` | 优先替换为标签组 + 固定字段入口 |
| `StatisticBoardToolbar.vue` / 多元统计板 | `StatisticFilterBuilder` | 保留 |
| `CodeReviewIllegalRecordsView.vue` | `StatisticFilterBuilder` | 视业务频率决定是否一期收口 |
| `issue-illegal-records/IssueIllegalRecordsPage.vue` | `StatisticFilterBuilder` | 视业务频率决定是否一期收口 |
| `CustomerIssueRecordsView.vue` | `StatisticFilterBuilder` | 视业务频率决定是否一期收口 |

Task 1 需要把这张表扩展成完整清单，再决定每个页面是“替换 builder”“补标签组”还是“保留现状”。

## 参考方案

### 飞书多维表格

飞书没有完全等同于 GitLab 标签组的功能，但它的组合能力适合参考：

- 单选、多选字段：字段是分类维度，选项是可筛选值。
- 视图筛选：同一数据表可以按不同筛选条件组织成不同视图。
- 字段编组：字段可以按业务维度归类，避免全部字段混在一起。
- 保存视图：筛选、分组、排序、字段显隐可以沉淀为长期使用的视图。

对本项目的启发是：标签组不应是一个混乱标签池，而应是“按业务维度组织的选项组”。

### GitLab Scoped Labels

GitLab 的 scoped label 适合作为标签建模参考，例如：

```text
module::工具模块
round::第三轮
milestone::M3
severity::严重
defectLevel::一级缺陷
status::未关闭
```

这类结构天然具有“标签组 + 标签值”的规则感。

### 阿里云效 / Jira

云效和 Jira 的价值主要在于“保存筛选结果”：

- 当前筛选条件可以另存为视图或过滤器。
- 视图可以区分个人视图和公共视图。
- 视图不只保存查询条件，也可以保存列、排序、分组、导出配置。

本计划一期只做标签组筛选和统一条件转换，不强制实现完整视图系统。

## 产品定义

### 高级条件构建器

高级条件构建器指 `frontend/src/components/StatisticFilterBuilder.vue` 这类“字段 + 操作符 + 值”的表达式编辑器。它是底层查询表达能力的一种前端编辑方式，面向复杂查询，不应作为业务页默认筛选样式。

典型结构：

```text
字段 + 操作符 + 值
```

示例：

```text
moduleName in [模块A, 模块B]
severityLevel = 严重
status != 已关闭
createdAt between 2026-05-01 and 2026-06-01
```

### 固定字段筛选

固定字段筛选指 `SystemTestIssueSearchView.vue` 中配置 `primaryFilters`、`advancedFilters`，并由 `RecordTableFilterFields.vue` 渲染出来的预设字段下拉筛选。它是业务用户更容易理解和操作的筛选入口。

典型结构：

```text
项目名称 / 创建人 / 处理人 / 状态 / 严重程度 / 缺陷状态 / 缺陷分类 / 里程碑 / 时间
```

### 标签组筛选

标签组筛选是基于统一筛选条件封装出来的业务入口，面向用户。它的交互体验应更接近 `RecordTableFilterFields.vue` 的快速点选，而不是 `StatisticFilterBuilder.vue` 的字段表达式编辑器。

典型结构：

```text
标签组 = 旧平台等价业务维度 / 新平台归一化业务维度
标签值 = 该维度下的可选口径
```

示例：

```text
模块
- 工具模块
- 草图模块
- 报表模块

轮次
- 第一轮
- 第二轮
- 第三轮

严重程度
- 一般
- 严重
- 致命
```

标签组筛选在仍使用 `StatisticFilterBuilder.vue` 的业务页上应替代其默认入口，但不删除 `StatisticFilterBuilder` 组件本身。议题查询页当前已经是固定字段筛选，只需要增量补标签组入口。

### 两者关系

```text
标签组筛选 -> 生成业务化 tagSelections
固定字段筛选 -> 生成普通条件
高级条件构建器 -> 只在复杂场景生成复杂条件
业务页由后端转换器统一合并为数据域查询谓词
```

页面上用户看到的是“已选条件”，不需要区分条件来自标签组、固定字段筛选还是高级条件构建器。

业务页不默认渲染高级条件构建器；如果某页面保留 `StatisticFilterBuilder`，也应作为明确的复杂表达入口，而不是默认筛选方式。

注意：`frontend/src/components/statistic-board-filters.ts` 当前 `StatisticFilterDraftGroup` 只支持单层 `logic + conditions[]`，不能表达“同组 OR + 不同组 AND”的两层结构。业务页标签组不要直接扩展 `StatisticFilterBuilder` 的扁平条件模型，也不要新增 `inMappedValues` 后再要求统计板 builder 回显。推荐请求层新增 `tagSelections`，由后端按数据域转换为查询谓词；统计板继续只处理现有 `StatisticFilterGroup`。

## 一期范围

一期目标是小步落地，不引入完整飞书多维表格方案。

### 做

- 保留现有议题查询固定字段筛选。
- 评审数据管理、非法记录、客户问题等当前直接挂载 `StatisticFilterBuilder.vue` 的业务页，不再把高级条件构建器作为默认筛选样式。
- 将“高级筛选”文案和心智调整为“常用条件”或“更多条件”。
- 新增标签组筛选入口。
- 标签组按老平台认可的分类口径收口。
- 标签组支持把归一化业务字段值、明确前缀标签值和历史字段值映射到标准口径；不同业务维度之间不默认串映射，例如模块不默认从里程碑或全部原始标签中模糊匹配。
- 标签组选择以 `tagSelections` 进入业务页请求，由后端转换为对应数据域查询条件。
- 已选条件统一展示。
- 支持脏数据进入“未归类 / 历史停用 / 原始标签”分区。

### 不做

- 不做完整多维表格。
- 不做复杂多层嵌套条件组。
- 不做公式字段。
- 不做跨表自由建模。
- 不急于实现个人视图、公共视图、列配置持久化。
- 不把所有 GitLab 原始标签直接铺给用户。
- 不全局删除 `StatisticFilterBuilder`，它仍可留给统计板、复杂报表和管理员场景。
- 不让统计板 `StatisticFilterBuilder` 渲染或 round-trip 业务页专用的 `tagSelections` / `inMappedValues`。

## 标签组规则

### 1. 按业务维度分组

标签组不能是一个大池子，应优先按旧平台 `LabelName` 和现有业务字段组织：

- 项目维度：项目、数据源、历史项目状态。
- 模块维度：标准模块、原始模块名。
- 版本维度：测试阶段、轮次、里程碑、版本。
- 缺陷维度：严重程度、缺陷等级、缺陷分类、缺陷状态、紧急程度、延期原因。
- 评审维度：评审类型、评审类别、问题类别。
- 人员维度：负责人、创建人、处理人、评审专家。
- 时间维度：创建时间、更新时间、评审时间。
- 原始标签：仅作为诊断或补充入口展示 GitLab 原始标签、无法归类标签，不作为默认主筛选池。

### 2. 同组内默认 OR

用户选择同一个标签组内的多个标签时，表示满足任一。

```text
模块 = 工具模块 OR 草图模块
```

### 3. 不同组之间默认 AND

用户选择多个标签组时，表示同时满足。

```text
模块 in [工具模块, 草图模块]
AND 严重程度 = 严重
AND 状态 = 未关闭
```

### 4. 支持互斥组

部分标签组可配置为单选或互斥：

- 状态。
- 当前严重程度。
- 当前缺陷等级。
- 当前评审类型。

模块、里程碑、原始标签通常允许多选。

### 5. 脏数据不进入主标签组

无法识别或不推荐展示的原始值进入：

- 未归类。
- 历史停用。
- 原始标签。

用户可以通过搜索展开使用，但默认不污染主筛选面板。

旧平台会过滤 `未设定...` 这类占位值，不会把它们作为普通下拉项展示。新平台标签组应延续这个收口心智：占位值默认进入诊断/未归类区域，只有诊断页面或用户明确需要时才展示。

## 数据模型建议

### tag_group

用于定义标准标签组。

```text
id
group_key
group_name
domain
selection_mode
display_order
enabled
description
created_at
updated_at
```

字段说明：

- `group_key`：如 `standardModule`、`severityLevel`。
- `domain`：适用页面或数据域，如 `issue_search`、`review_data`、`all`。
- `selection_mode`：`single` 或 `multiple`。

### tag_value

用于定义标准标签值。

```text
id
group_id
value_key
display_name
display_order
enabled
deprecated
description
created_at
updated_at
```

### tag_value_mapping

用于把原始数据映射到标准标签值。

```text
id
tag_value_id
source_instance
source_type
source_field
source_value
match_type
unmapped_reason
enabled
created_at
updated_at
```

字段说明：

- `source_instance`：可空；`null` 表示全局映射，非空时只作用于指定 GitLab 来源，如 `cc`、`dgm`。多源环境必须先定义查找优先级：精确 `source_instance` 优先，全局兜底。
- `source_type`：`normalized_field`、`legacy_label_prefix`、`module_dictionary_alias`、`project_name`、`review_field`、`raw_gitlab_label`。模块标准化默认复用 `module_dictionary`，不要用 `tag_value_mapping(source_type='module_name')` 维护第二套模块 alias。`module_dictionary_alias` 仅允许作为展示层 join 或诊断引用，不影响标准模块归一化结果；运行时模块归一化仍只读 `module_dictionary`。
- `source_field`：原始字段名。
- `source_value`：原始值。
- `match_type`：`exact`、`contains`、`prefix`、`regex`。
- `unmapped_reason`：可空；用于记录未归类原因，如 `no_chinese_colon`、`prefix_not_in_dict`、`source_instance_unmapped`、`placeholder_value`，便于管理端补规则。

一期建议运行时优先使用 `exact`。`contains` 只能用于旧平台明确使用模糊匹配的明细查询或经产品确认的增强场景，并且必须有误命中测试。`regex` 可在管理端配置，但仅用于离线归类或批量映射维护；运行时映射加载阶段必须直接过滤 `match_type=regex` 的行，对用户查询路径透明。

### 标签组匹配策略

匹配策略不进 schema，作为代码内置 strategy registry 实现。`tag_group` 只保存策略名，运行时由 `MatchStrategyRegistry` 解析为按数据域分发的具体实现。理由：策略本身是技术决定（`split_exact_comma`、`like` 等都是 SQL 写法），不是 PM 自助配置项；同一个 group 在不同数据域可能有不同 SQL 谓词，`tag_group` 单行加几列表达不了多策略。如果将来真要给运营/管理员自助配置，再升级为 `tag_group_match_policy` 独立子表。

`tag_group` 新增字段：

```text
match_strategy_name  -- 如 "module_strategy_v1"、"plain_eq_v1"
```

策略 registry 在 Java 侧示意：

```text
module_strategy_v1:
  issue_search        -> module_name like, 曲线/曲面 排除曲线曲面
  issue_summary       -> module_names split_exact_comma (或 module_name_array array_exact)
  illegal_issue_search-> module_name eq
  review_data         -> 按评审数据自身字段处理

plain_eq_v1:
  all domains -> target_field eq
```

模块相关默认：

- 统计汇总：优先 `array_exact`；若一期不加数组列，则使用 `split_exact_comma`，按新平台 `module_names` 的逗号分隔做边界精确匹配。
- 普通议题明细：`like`，并保留 `曲线 / 曲面` 排除 `曲线曲面` 的特殊规则。
- 非法议题明细：按旧平台接口使用 `eq` 或对应接口原有策略。
- 评审数据：按评审数据自身字段口径定义，不直接套用系统测试模块规则。

### 标签映射缓存策略

`tag_group` / `tag_value` / `tag_value_mapping` 通过 Caffeine 缓存，避免每次查询读库。一期参考现有 `GitlabWhitelistService` 的缓存模式：

- 启动加载 + TTL 兜底（建议 30 分钟）。
- 管理端写入后显式 `evict`；只读 YAML/CSV 模式下提供 `/admin/reload-tag-mappings` 触发重载。
- 缓存按 `(domain, source_instance)` 维度切分，避免一次失效拖垮所有页面。

## 查询转换

用户选择：

```text
标准模块：工具模块
严重程度：严重
```

业务页请求携带：

```json
{
  "tagSelections": [
    {
      "groupKey": "standardModule",
      "valueKeys": ["tool"]
    },
    {
      "groupKey": "severityLevel",
      "valueKeys": ["serious"]
    }
  ],
  "fixedFilters": {
    "projectName": "Project A",
    "updatedAtStart": "2026-05-01",
    "updatedAtEnd": "2026-06-01"
  }
}
```

后端按数据域把 `tagSelections` 展开：

```text
(standardModule = 工具模块 OR standardModule = 草图模块)
AND severityLevel = 严重
AND fixedFilters...
```

具体使用哪种匹配方式由 `domain + tag_group + match_policy` 决定，而不是由前端硬编码：

```text
module_names split_exact_comma [...]
或 module_name eq [...]
或 module_name like [...]
或 module_name_array array_exact [...]
```

默认不应把标准模块展开为里程碑字段模糊匹配或全部 GitLab 原始标签模糊匹配，否则会偏离旧平台按业务字段收口的语义。模块标准口径优先来自 `module_dictionary` 和 `issue_fact.module_name/module_names`；原始 GitLab label 不参与模块维度映射，只用于非模块维度 fallback 和诊断。

注意：前端不应承担复杂映射展开，否则映射规则变更会导致前后端不一致。统计板继续使用现有 `StatisticFilterGroup`；业务页专用的 `tagSelections` 不要求 `StatisticFilterBuilder.vue` 识别或回显。

## 页面交互建议

### 议题查询页

保留当前快速筛选优势。

建议结构：

```text
常用条件
[更新时间] [项目] [状态] [负责人] [严重程度]

标签组
[模块] [轮次] [里程碑] [缺陷等级] [原始标签]

已选条件
模块：工具模块 / 草图模块  状态：未关闭  严重程度：严重

更多条件
展开后显示现有固定字段筛选；议题查询页不引入 StatisticFilterBuilder
```

### 评审数据管理页

建议优先补标签组，因为该页面更容易受到模块脏数据、历史项目数据影响。

优先标签组：

- 标准模块。
- 评审类型。
- 评审类别。
- 问题类别。
- 问题状态。
- 评审版本 / 轮次。
- 负责人 / 评审专家。

评审数据管理页不属于旧平台系统测试议题的 `LabelName` 规则直接覆盖范围。它可以复用“业务字段收口 + 标签组展示”的模式，但模块、评审类型、问题类别等口径应以评审数据自身字段和旧平台评审数据导出/导入结构为准。

### 统计分析页

统计分析页可以保留 `StatisticFilterBuilder` 作为高级入口，因为统计场景通常需要更强表达能力。

## 实施任务

### Task 1: 梳理现有筛选入口

**Description:** 明确议题查询、评审数据管理、非法记录、客户问题、统计板当前分别使用哪种筛选组件，形成一张筛选入口清单。该任务先输出事实清单，不预设“所有业务页都要替换 builder”。

**Acceptance criteria:**

- [ ] 至少覆盖 `SystemTestIssueSearchView.vue`、`ReviewDataManagementView.vue`、`StatisticBoardToolbar.vue`、`CodeReviewIllegalRecordsView.vue`、`issue-illegal-records/IssueIllegalRecordsPage.vue`、`CustomerIssueRecordsView.vue`。
- [ ] 列出每个页面的筛选组件来源：`RecordTableFilterFields`、`StatisticFilterBuilder`、固定字段、自定义筛选或混合。
- [ ] 标记每个页面的一期动作：`增量补标签组`、`替换 builder 默认入口`、`保留 builder`、`暂不改`。
- [ ] 标记需要删除或弱化的字段，如 `综合搜索`、`模块关键词`。
- [ ] 明确议题查询页当前没有引入 `StatisticFilterBuilder`，Task 7 是增量补标签组，不是替换 builder。

**Verification:**

- [ ] 文档评审通过。

**Files likely touched:**

- `docs/plans/2026-06-05-filter-tag-groups-implementation-plan.md`

**Estimated scope:** Small

### Task 2: 定义标签组接口契约

**Description:** 定义标签组、标签值、映射关系和业务页 `tagSelections` 的 API 契约，先写前后端类型和接口测试。

**Acceptance criteria:**

- [ ] 后端可返回指定数据域的标签组。
- [ ] 每个标签组包含选择模式、展示顺序、标签值列表。
- [ ] 可区分标准标签、未归类标签、历史停用标签、原始标签。
- [ ] 可返回每个标签组在不同数据域下的匹配策略，如 `split_exact_comma`、`array_exact`、`like`、`eq`。
- [ ] 定义业务页请求 DTO：`tagSelections: { groupKey, valueKeys[] }[]`，并明确固定字段筛选继续使用各页面现有 DTO。
- [ ] 响应可返回未归类原因 `unmappedReason`，用于诊断和映射维护。

**Verification:**

- [ ] 后端接口测试通过。
- [ ] 前端类型测试或构建通过。

**Dependencies:** Task 1

**Files likely touched:**

- `backend/src/main/java/com/data/collection/platform/controller/*`
- `backend/src/main/java/com/data/collection/platform/service/*`
- `frontend/src/types/api/*`
- `frontend/src/api.ts`

**Estimated scope:** Medium

### Task 3: 建立标签组映射存储

**Description:** 新增标签组、标签值、标签映射的数据库表和基础管理能力；模块标准化默认复用现有 `module_dictionary`，不复制一套模块 alias 表。

**Acceptance criteria:**

- [ ] 新增 Flyway migration。
- [ ] 支持标准标签值映射多个原始值。
- [ ] 支持停用标签值或映射关系。
- [ ] `tag_value_mapping` 预留 `source_instance`，并定义 `source_instance` 精确匹配优先、全局映射兜底的策略。
- [ ] 模块组的数据来源写清楚：标准模块来自 `module_dictionary` / `issue_fact.module_name(s)`，`tag_value_mapping` 只做展示补充或非模块维度映射。
- [ ] `module_dictionary_alias` 仅用于展示层 join 或诊断引用，不影响标准模块归一化；运行时模块归一化仍只读 `module_dictionary`。
- [ ] `tag_group` 增加 `match_strategy_name` 字段，匹配策略以代码内置 `MatchStrategyRegistry` 实现，按数据域分发，不通过子表或 jsonb 存储。
- [ ] `tag_group` / `tag_value` / `tag_value_mapping` 走 Caffeine 缓存，启动加载 + 30 分钟 TTL；管理写入显式 `evict`，YAML/CSV 模式提供 `/admin/reload-tag-mappings` 重载入口。
- [ ] 明确 `issue_fact.module_names` 的匹配结构：新增 `module_name_array text[] + GIN`，或继续使用逗号分隔边界匹配；不得按旧平台 `&` 解析新平台字段。
- [ ] 一期至少提供只读配置列表或 YAML/CSV 启动加载机制，避免只能手工改库维护映射。
- [ ] 校验 `IssueFactNormalizationRules.normalizeModuleNames(labels)` 仍存在且与 `FactBuildService.java:362-365` 调用链一致，作为 Task 4a 单元测试的前置事实。

**Verification:**

- [ ] Maven 测试通过。
- [ ] Flyway migration 可在本地库执行。

**Dependencies:** Task 2

**Files likely touched:**

- `backend/src/main/resources/db/migration/*`
- `backend/src/main/java/com/data/collection/platform/entity/*`
- `backend/src/main/java/com/data/collection/platform/repository/*`
- `backend/src/main/java/com/data/collection/platform/service/ModuleDictionaryService.java`
- `backend/src/test/java/com/data/collection/platform/*`

**Estimated scope:** Medium

### Task 4a: 实现标签组请求 DTO 和转换器

**Description:** 后端新增标签组选择转换器，接收业务页 `tagSelections`，按 `domain + groupKey + match_policy` 转换为该数据域的查询谓词。该转换器负责“同组 OR + 不同组 AND”，不要求前端 `StatisticFilterDraftGroup` 表达嵌套逻辑。

**Acceptance criteria:**

- [ ] 同组多选按 OR 处理。
- [ ] 不同组之间按 AND 处理。
- [ ] 标准模块优先通过 `module_dictionary` 归一后的 `issue_fact.module_name/module_names` 查询，不直接维护第二套模块 alias。
- [ ] 未归类和原始标签可以参与查询。
- [ ] 模块标签组按数据域使用不同匹配策略，不能全局固定为 `contains`。
- [ ] 默认查询优先使用归一化业务字段，不直接扫所有原始 GitLab 标签。
- [ ] 运行时映射加载过滤 `match_type=regex` 的行；`contains` 必须只在明确允许的数据域使用。
- [ ] 仅替换 `IssueFactQueryService.java:64` 处 `moduleName` 字段的 `appendContains` 调用为逗号边界匹配或数组精确匹配；其他字段（如 `assigneeName`、`category`）保持现有 substring 语义，不修改 `appendContains` 工具方法本身。
- [ ] 原始 GitLab label 不参与模块维度映射，只用于非模块维度 fallback 和诊断。

**Verification:**

- [ ] 单元测试覆盖 `single`、`multiple`、映射展开、未归类查询。
- [ ] 单元测试覆盖新平台逗号分隔 `split_exact_comma` 或 `module_name_array array_exact`。
- [ ] 单元测试覆盖普通明细 `like`、`曲线 / 曲面` 排除 `曲线曲面`。
- [ ] 单元测试覆盖 `工具` 不误命中 `工具箱`，以及 `曲线` 不命中 `曲线曲面`。

**Dependencies:** Task 3

**Files likely touched:**

- `backend/src/main/java/com/data/collection/platform/entity/*TagSelection*`
- `backend/src/main/java/com/data/collection/platform/service/*TagGroup*`
- `backend/src/main/java/com/data/collection/platform/service/*Filter*`
- `backend/src/main/java/com/data/collection/platform/repository/*`

**Estimated scope:** Medium

### Task 4b: 按数据域接入标签组转换

**Description:** 将 Task 4a 的转换器按数据域接入业务查询链路。先接评审数据和议题查询，再视 Task 1 清单接入非法记录、客户问题、代码走查非法数据等页面。

**Acceptance criteria:**

- [ ] `ReviewDataRecord*` 列表查询使用 `tagSelections`。
- [ ] `ReviewDataRecord*` 导出使用同一套 `tagSelections`。
- [ ] `ReviewDataFilterOptions` / `getReviewFilterOptions` 加载按同一数据域和 `sourceInstance` 口径过滤。
- [ ] `IssueFactRecordList*` / `SystemTestIssueSearch*` 列表查询使用 `tagSelections`。
- [ ] `IssueFactRecordList*` / `SystemTestIssueSearch*` 导出使用同一套 `tagSelections`。
- [ ] `SystemTestIssueSearchFilterOptions` / IssueFact filter options 加载按同一数据域和 `sourceInstance` 口径过滤。
- [ ] `SystemTestIllegalRecord*` 是否一期接入由 Task 1 清单明确；如果接入，列表、导出、筛选项三条链路必须全部接入。
- [ ] `CustomerIssue*` 是否一期接入由 Task 1 清单明确；如果接入，列表、导出、筛选项三条链路必须全部接入。
- [ ] `CodeReviewIllegalRecord*` 是否一期接入由 Task 1 清单明确；如果接入，列表、导出、筛选项三条链路必须全部接入。
- [ ] 统计板不渲染业务页标签组条件；如果后端响应中存在标签组生成的条件，不走 `StatisticFilterBuilder` round-trip。
- [ ] 跨页筛选条件默认不传递；业务页到统计板的一键带条件跳转一期不做。
- [ ] 现有统计板单元格 → 议题列表的 drill-down 仍走 `filterGroup`，不混用 `tagSelections`；议题列表收到 `filterGroup` 时按现有路径处理，不强制转换为 `tagSelections`。
- [ ] 旧 URL 查询参数和旧 DTO 在兼容期内仍可用。

**Verification:**

- [ ] 每个接入域至少有查询单测和导出 smoke。
- [ ] 议题查询和评审数据查询接口仍兼容旧参数。
- [ ] 多源 `sourceInstance=cc/dgm/default` 下映射策略一致且互不污染。

**Dependencies:** Task 4a

**Files likely touched:**

- `backend/src/main/java/com/data/collection/platform/service/*Issue*`
- `backend/src/main/java/com/data/collection/platform/service/*ReviewData*`
- `backend/src/main/java/com/data/collection/platform/service/*IllegalRecord*`
- `backend/src/main/java/com/data/collection/platform/controller/*`

**Estimated scope:** Large

### Task 5: 新增标签组筛选前端组件

**Description:** 构建轻量标签组筛选组件，用分组面板展示标准标签值，并将选择结果输出为 `tagSelections`。

**Acceptance criteria:**

- [ ] 标签组按业务维度展示。
- [ ] 支持单选、多选、搜索标签值。
- [ ] 支持隐藏历史停用标签。
- [ ] 已选条件统一显示在现有 `activeFilterTags` 区域。
- [ ] 支持把当前 `tagSelections + fixedFilters` 保存到 localStorage 快照集合；一期不入库、不做公私视图。
- [ ] localStorage 快照集合最多保留 3 个 pinned 快照，快照 TTL 为 30 天；一期不提供重命名。
- [ ] localStorage 快照包含 `schemaVersion: 1` 和 `schemaHash`；`schemaHash` 由后端在 `/api/tag-groups?domain=…` 响应里返回（基于 `(groupKey, valueKey)` 列表稳定排序后取摘要），前端只透传不独立计算。
- [ ] 进入页面且 URL 无 `tagSelections` query 时，自动恢复 active snapshot，并用 router replace 模式回填 `tagSelections + fixedFilters`。
- [ ] 自动恢复成功时不弹全局 toast；手动点击“恢复快照”成功时弹 toast。
- [ ] 加载快照时如果 `schemaHash` 不一致或包含 unknown `groupKey/valueKey`，在标签组面板内显示 warning，提示“快照过期/口径已变化，已忽略 X 个失效条件”，并保留其余可解析条件。
- [ ] 标签组面板默认折叠规则按页配置；当选中数 > 0 时强制展开，避免已选条件被折叠隐藏。
- [ ] 恢复快照时同步回填 `fixedFilters`，使固定字段筛选与标签组口径一致恢复。
- [ ] 未归类标签展示 `unmappedReason`，便于用户或管理员判断需要补哪类映射规则。

**Verification:**

- [ ] Vitest 覆盖选择、取消、单选互斥、多选组合。
- [ ] Vitest 覆盖快照集合、30 天 TTL、自动恢复 source、手动恢复 source、schema mismatch/unknown key 面板 warning、剩余条件和 `fixedFilters` 可恢复。
- [ ] 页面无文本溢出和筛选区重排异常。

**Dependencies:** Task 4a

**Files likely touched:**

- `frontend/src/components/*TagGroupFilter*.vue`
- `frontend/src/components/base/BaseRecordTable.vue`
- `frontend/src/types/record-table.ts`
- `frontend/src/views/SystemTestIssueSearchView.vue`
- `frontend/src/views/ReviewDataManagementView.vue`

**Estimated scope:** Medium

### Task 6: 评审数据管理页接入标签组

**Description:** 在评审数据管理页优先接入标准模块、评审类型、问题类别、问题状态等标签组，并替换该页当前默认展示的 `StatisticFilterBuilder`。

**Acceptance criteria:**

- [ ] 可按老平台标准模块筛选评审数据。
- [ ] 新平台脏模块不会默认铺满筛选区。
- [ ] 已选标签组条件和常用条件统一展示。
- [ ] 查询、展开问题清单、导出使用同一份 `tagSelections`。
- [ ] 页面默认展示标签组面板，`StatisticFilterBuilder` 收口到默认折叠的“高级条件”折叠区。
- [ ] 常用快速口径可从 localStorage 自动恢复；自动恢复静默，手动恢复弹 toast，schema mismatch 使用面板内联 warning。
- [ ] 恢复快照时同步回填评审数据固定字段 `fixedFilters`。
- [ ] 列表、导出、筛选选项接口均透传同一份 `sourceInstance`，保持多源标签映射口径一致。
- [ ] 后端通过 `V20260608_01__tag_groups_review_data_seed.sql` 幂等写入 6 个 `review_data` 标准标签组和必要 `tag_value` 行；不重建模块 alias。

**Verification:**

- [ ] 导入老平台 Excel 后，可按标准模块筛出对应数据。
- [ ] Playwright 验证标签组筛选后列表、问题清单、导出结果一致。

**Dependencies:** Task 4b, Task 5

**Files likely touched:**

- `frontend/src/views/ReviewDataManagementView.vue`
- `frontend/src/views/review-data-management.ts`
- `backend/src/main/java/com/data/collection/platform/controller/ReviewDataController.java`
- `backend/src/main/java/com/data/collection/platform/service/*ReviewData*`

**Estimated scope:** Medium

### Task 7: 议题查询页轻量接入标签组

**Description:** 不推翻现有议题查询固定字段筛选，仅增量补充模块、里程碑、严重程度等标签组入口。该页当前没有引入 `StatisticFilterBuilder.vue`，所以这里不是替换 builder。

**Acceptance criteria:**

- [ ] 保留现有快速下拉筛选。
- [ ] 标签组入口体验与 `RecordTableFilterFields.vue` 的快速下拉筛选一致。
- [ ] 标签组筛选与现有固定字段筛选可同时使用。
- [ ] 页面默认折叠标签组面板，除非页面配置展开或已有选中标签；选中数 > 0 时强制展开。
- [ ] 无 `tagSelections` query 时自动恢复 active snapshot，使用 replace 模式回填 `tagSelections + fixedFilters`，自动恢复静默。
- [ ] schema mismatch/unknown key 在标签组面板内联 warning，手动恢复快照时弹 toast。
- [ ] `综合搜索`、`模块关键词` 的删除或弱化方案经过确认后实施。
- [ ] 模块标签组默认展示值来自归一化业务字段，不展示 `未设定...` 和跨字段污染标签。
- [ ] 旧 URL 参数如 `moduleName`、`keyword`、`testingPhase` 在兼容期内仍可解析；新 `tagSelections` URL 参数上线后设置明确清理窗口，例如 4-8 周。

**Verification:**

- [ ] Playwright 验证筛选、重置、导出。
- [ ] 旧 URL 查询参数兼容，并有 feature flag 控制新旧参数优先级和清理时间。

**Dependencies:** Task 4b, Task 5

**Files likely touched:**

- `frontend/src/views/SystemTestIssueSearchView.vue`
- `backend/src/main/java/com/data/collection/platform/controller/*Issue*`
- `frontend/src/components/base/BaseRecordTable.vue`

**Estimated scope:** Medium

### Task 8: 筛选文案和入口收口

**Description:** 将“高级筛选”按页面语境调整为“更多条件”或“常用条件”，并明确 `StatisticFilterBuilder.vue` 的高级条件构建器只在统计板、复杂报表、管理员配置等复杂表达场景出现，降低用户理解成本。

**Acceptance criteria:**

- [ ] 议题查询页不再把固定字段筛选叫成复杂高级筛选。
- [ ] 评审数据管理等当前直接使用 builder 的业务页不再默认展示 `StatisticFilterBuilder.vue` 的条件构建器样式。
- [ ] 议题查询页文案按“当前就是固定字段筛选 + 标签组增量入口”处理，不再写成替换 builder。
- [ ] 条件构建器只在确实需要复杂表达的页面出现。
- [ ] 已选条件展示不区分来源。

**Verification:**

- [ ] 前端测试通过。
- [ ] 手工检查页面文案一致。

**Dependencies:** Task 5

**Files likely touched:**

- `frontend/src/components/base/BaseRecordTable.vue`
- `frontend/src/views/SystemTestIssueSearchView.vue`
- `frontend/src/views/ReviewDataManagementView.vue`
- `frontend/src/views/CodeReviewIllegalRecordsView.vue`
- `frontend/src/views/issue-illegal-records/IssueIllegalRecordsPage.vue`
- `frontend/src/views/CustomerIssueRecordsView.vue`

**Estimated scope:** Small

## Checkpoints

### Checkpoint 1: 契约完成

After Tasks 1-3:

- [ ] 标签组模型和映射关系确定。
- [ ] 后端能返回标签组配置。
- [ ] 模块口径归口到 `module_dictionary`，标签组不重复维护模块 alias。
- [ ] 新平台 `module_names` 使用逗号边界匹配还是新增数组列已决策。
- [ ] 多源 `source_instance` 映射策略已确定。
- [ ] `tag_group_match_policy` 已按一期 `MatchStrategyRegistry` 内置策略方案落地；`tag_group.match_strategy_name` schema 已确定。
- [ ] `tag_group` / `tag_value` / `tag_value_mapping` 缓存模式已确定（启动加载 + Caffeine + TTL + evict / reload 入口）。

### Checkpoint 2: 查询转换完成

After Tasks 4a-4b:

- [ ] 业务页 `tagSelections` 可以按数据域转换为查询谓词。
- [ ] 标准标签可以展开为归一化字段、字典字段或允许的数据域映射查询。
- [ ] 旧筛选参数兼容。
- [ ] 统计板 builder 不渲染业务页标签组专用条件。

### Checkpoint 3: 前端接入完成

After Tasks 5-8:

- [ ] 评审数据管理页可按标准标签组筛选。
- [ ] 议题查询页保留快速筛选体验。
- [ ] 已选条件统一展示。
- [ ] 导出按当前筛选结果工作。

## 风险和缓解

| Risk | Impact | Mitigation |
|---|---|---|
| 标签组变成新的混乱标签池 | 高 | 强制按业务维度分组，脏数据进入未归类/原始标签 |
| 标签组模块映射和现有 `module_dictionary` 形成两套标准模块 | 高 | 模块标准化归口到 `ModuleDictionaryService`；标签组只做展示/选择/查询适配 |
| 映射规则维护成本高 | 中 | 一期运行时只做 exact；提供只读列表或 YAML/CSV 加载，后续再加管理写界面 |
| 前端和后端查询逻辑不一致 | 高 | 前端只表达选择，后端负责映射展开 |
| 扁平 `StatisticFilterGroup` 无法表达同组 OR + 组间 AND | 高 | 业务页使用 `tagSelections` DTO，后端转换；统计板 builder 不回显标签组条件；一期不做跨页筛选条件传递 |
| 旧 URL 查询参数失效 | 中 | 保留旧参数兼容，新增 `tagSelections` 参数渐进接管；上线 4-8 周后通过 feature flag 清理旧语义 |
| 用户分不清常用条件和标签组 | 中 | 已选条件统一展示，入口文案避免“两个筛选系统” |
| 死项目数据污染筛选项 | 中 | 默认隐藏历史停用标签，提供搜索展开 |
| 多源 GitLab 标签习惯不同导致映射冲突 | 高 | `tag_value_mapping.source_instance` 可空；精确来源优先，全局兜底 |
| 模块匹配策略一刀切 | 高 | 按数据域配置 `split_exact_comma`、`array_exact`、`like`、`eq` 等策略，复刻旧平台接口差异 |
| `contains` 或 `regex` 造成慢查询或误命中 | 高 | `regex` 只用于管理/离线；`contains` 必须显式允许并覆盖 `工具/工具箱` 等误命中测试 |
| 原始 GitLab 标签污染主筛选 | 高 | 默认只展示归一化业务字段值，原始标签仅进诊断/补充入口 |

## 已定决策

- localStorage 快照一期保存为集合模式，最多 3 个 pinned 快照；快照 TTL 为 30 天；一期不允许用户重命名。
- 自动恢复触发条件为进入页面且 URL 无 `tagSelections` query；自动恢复使用 replace 模式，不污染浏览器历史。
- 自动恢复成功静默；手动点击“恢复快照”成功弹 toast；schema mismatch 或 unknown key 只在标签组面板内联 warning，不弹全局 toast。
- 标签组面板默认折叠/展开由页面配置；评审数据管理页默认展开，议题查询页默认折叠；只要选中数 > 0 就强制展开。
- 恢复快照必须同时回填 `fixedFilters`；评审数据页覆盖 `keyword/title/projectName/moduleName/reviewOwner/reviewType/problemStatus/reviewExpert/filterGroup/sourceInstance`，议题查询页覆盖现有固定字段筛选。
- 普通议题明细一期继续使用新平台逗号边界匹配，避免 `工具` 误命中 `工具箱`；不复刻旧平台 `&` 分隔，也不回退到无边界 contains。
- `module_names` 一期继续使用逗号边界匹配，不新增 `module_name_array text[] + GIN`。
- `tag_value_mapping.source_instance` 冲突时始终来源映射优先，全局映射兜底。
- snapshot 一期按页面和数据域隔离，不做跨页/跨项目共享；业务页到统计板的一键带条件跳转一期不做。

## Open Questions

- `module_dictionary` 是否需要补一个只读/导入管理入口，承接老平台“标准模块”的最终维护？
- 标准标签组是否需要支持跨数据域复用，例如议题查询和评审数据管理共用模块口径？
- 一期只读配置页和 YAML/CSV 启动加载二选一还是都做？
- `综合搜索` 和 `模块关键词` 是直接删除，还是先折叠为“快速搜索”兜底？

## 推荐一期结论

一期不要做完整飞书多维表格，也不要重做复杂搜索系统。

推荐路线：

```text
现有统一筛选条件作为地基
标签组作为业务收口入口
老平台分类作为标准标签口径
ModuleDictionaryService 作为标准模块归一化 source of truth
新平台归一化业务字段作为主要映射来源
原始 GitLab 标签只作为诊断和补充映射来源
业务页通过 tagSelections 交给后端转换，统计板继续使用现有 filterGroup
```

这样既保留现有议题查询“快速下拉”的效率，也能解决新平台标签和模块脏数据导致的分类失控问题。

## Sources

- 飞书多维表格单选和多选字段：https://www.feishu.cn/hc/zh-CN/articles/624094577996
- 飞书多维表格分组和筛选：https://www.feishu.cn/hc/zh-CN/articles/360049067904
- 飞书多维表格视图：https://www.feishu.cn/hc/zh-CN/articles/360049067931
- GitLab Labels / Scoped Labels：https://docs.gitlab.com/user/project/labels/
- 阿里云效视图管理：https://help.aliyun.com/zh/yunxiao/user-guide/view-management
- 旧平台标签枚举：`D:\projects\spidergitdata-dev\src\main\java\com\huayun\entity\LabelName.java`
- 旧平台标签解析：`D:\projects\spidergitdata-dev\src\main\java\com\huayun\service\impl\ParseDocumentServiceImpl.java`
- 旧平台下拉收口：`D:\projects\spidergitdata-dev\src\main\java\com\huayun\service\DropDownService.java`
- 旧平台模块拆分匹配：`D:\projects\spidergitdata-dev\src\main\java\com\huayun\utils\ModuleSplitUtil.java`
- 旧平台议题查询构建：`D:\projects\spidergitdata-dev\src\main\java\com\huayun\service\SpiderIssueDataQueryBuilder.java`
- 新平台模块字典：`backend/src/main/java/com/data/collection/platform/service/ModuleDictionaryService.java`
- 新平台 fact 构建：`backend/src/main/java/com/data/collection/platform/service/FactBuildService.java`
- 新平台模块/标签多值拆分：`backend/src/main/java/com/data/collection/platform/service/IssueFactValueSupport.java`
- 新平台统计源多值拆分：`backend/src/main/java/com/data/collection/platform/service/statistics/StatisticSourceValueSupport.java`
- 新平台统计筛选模块边界匹配：`backend/src/main/java/com/data/collection/platform/service/IssueFactFilterGroupSqlSupport.java`
- 新平台记录列表模块边界匹配：`backend/src/main/java/com/data/collection/platform/service/IssueFactRecordRepository.java`
- 新平台旧模块 contains 链路：`backend/src/main/java/com/data/collection/platform/service/IssueFactQueryService.java`
- 新平台议题查询页筛选入口：`frontend/src/views/SystemTestIssueSearchView.vue`
- 新平台评审数据管理筛选入口：`frontend/src/views/ReviewDataManagementView.vue`
- 新平台统计筛选草稿类型：`frontend/src/components/statistic-board-filters.ts`
