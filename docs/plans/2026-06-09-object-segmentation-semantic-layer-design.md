# 动态/静态对象分群与语义层设计

日期：2026-06-09

## 结论

旧的业务筛选方案废弃。后续不再把业务能力设计成标签值映射、前端条件集合或任意 SQL 条件集合。

新方案分成两层：第一层是“语义标签组”，负责向页面和规则 DSL 提供稳定的业务选项；第二层是“对象分群”，负责基于这些选项和语义口径计算成员。用户面向业务对象创建动态分群、静态分群或静态成员快照，系统在后台基于统一语义口径、受控规则 DSL、语义标签组和预聚合事实表计算成员。用户仍然看到“草图、工程图、一级缺陷、P1、用户名、项目名、里程碑”等熟悉选项，但这些选项来自语义层，不再直接等同于数据库标签或 SQL 片段。

## 背景

平台当前主链路是：

```text
GitLab 镜像表 -> fact 构建 -> 统计看板 / 记录页 / 导出
```

平台规则汇总显示，核心复杂度并不在“标签值”本身，而在稳定复用的业务口径：

- 系统测试范围：CrownCAD 各版本下维护的系统测试/回归测试阶段标签。
- 系统测试排除规则：排除“功能屏蔽”“已拒绝”“建议”，以及关闭状态下的“申请否决”“需求如此”。
- 客户问题范围：`CC_Product` 项目中 2026-01-01 后创建的议题，不同场景再区分 open/open+closed。
- 横向对比范围：代码走查只统计 `MERGED` 且目标分支为 `dev` 的 MR；评审数据存在 `CC2025R1 -> CC2025R1&R2` 项目映射特例。
- 非法数据口径：严重程度、模块、模板回复、缺陷原因唯一性、负责人签字等规则共同决定是否合法。
- SLA 口径：P1/P2/P3 响应期限、预计解决时间、18 天解决上限、北京时间换算。
- 统计粒度差异：有的指标按议题去重计数，有的按模块拆分计数，有的按缺陷原因个数计数。

这些规则不能交给每个动态条件重复配置，也不能让业务方手写跨表 SQL。标签组也不能只设计成“从数据中动态发现选项”：严重程度、紧急程度、延期原因、闭环状态、非法类型、缺陷原因归并等静态标签组同样是业务规则的一部分，必须由平台规则和语义层统一维护。

## 目标

1. 支持动态分群：成员随镜像同步、fact 构建和规则刷新自动变化。
2. 支持静态分群：成员由人工选择、导入或审批固化，不随源数据自动增减。
3. 支持静态快照：在某个时间点固定保存动态计算或人工选择后的成员，用于留痕、导出、复盘和执行。
4. 支持动态标签组：模块、项目、里程碑、用户等选项可从事实层或汇总层生成，但必须经过语义标准化。
5. 支持静态标签组：严重程度、紧急程度、延期原因、闭环状态、非法类型等选项由平台规则定义，不能被源数据漂移改写。
6. 将复杂跨表关系、模板解析、去重规则、时间窗和异常口径集中到语义层。
7. 用户只配置受控条件，不接触 SQL、join、group by 或 `ods_gitlab_*` 表。
8. 高频规则跑在事实表或汇总表上，避免每次动态分群都扫明细镜像表。

## 非目标

- 不做任意 SQL 编辑器。
- 不延续旧业务筛选方案作为产品概念。
- 不让前端直接拼接跨表查询条件。
- 不把“静态标签组”降级为前端写死枚举；静态选项仍需由后端语义注册表返回，方便权限、审计、禁用和规则说明统一。
- 不在第一阶段引入完整外部 BI 语义层产品。
- 不承诺强实时；动态结果以平台最近一次镜像同步和事实构建时间为准。

## 核心概念

### 对象

对象是分群成员的类型。第一阶段建议支持：

- `issue`：议题
- `module`：模块
- `merge_request`：合并请求
- `review_record`：评审记录

后续可扩展：

- `user`：用户
- `project`：项目/版本
- `repository`：仓库

### 语义口径

语义口径封装“范围 + 过滤 + 业务解释”。例如：

- `system_test_issue_scope`：系统测试/回归测试议题范围。
- `system_test_exclusion_policy`：系统测试通用排除规则。
- `customer_issue_scope`：客户问题范围。
- `customer_open_issue_scope`：客户问题 open 范围。
- `merged_dev_mr_scope`：已合并到 dev 的 MR 范围。
- `illegal_issue_policy`：非法议题判定。
- `defect_reason_policy`：新旧缺陷原因模板归并。
- `ratio_display_policy`：分母为 0 时显示 `/` 或 `0` 的场景规则。

### 语义标签组

语义标签组是页面筛选、规则 DSL 和分群条件共用的业务选项目录。它不等同于 GitLab 标签，也不等同于旧方案里的任意字段映射。

每个标签组必须声明：

- `domain`：适用业务域，例如 `issue`、`review_data`、`merge_request`、`module`。
- `group_key`：稳定机器名，例如 `severity`、`urgency`、`module`、`delay_cause`。
- `source_mode`：`STATIC` / `DYNAMIC` / `HYBRID`。
- `value_source`：静态规则、事实字段、汇总表、人工维护或外部字典。
- `match_strategy`：精确匹配、数组精确匹配、模块拆分匹配、标准化字段匹配等受控策略。
- `rule_policy_key`：如果标签组承载业务口径，必须关联对应平台规则。
- `empty_value_policy`：是否允许“未设定”“未归类”“其他”等兜底值。

标签组输出给前端时必须同时返回 `schemaHash`、启用状态、禁用原因和规则说明入口，保证页面筛选、导出和下钻使用同一套选项。

### 动态标签组

动态标签组的值来自事实层或汇总层，适合数据本身会持续变化的业务属性。动态不代表无规则，生成前必须先经过语义标准化和平台范围约束。

第一阶段动态标签组建议包括：

- 模块：来自 `issue_module_fact`、评审模块事实和 MR 模块事实，名称需要经过模块字典归一化；系统测试总计按议题去重，模块行按模块展开。
- 项目/版本：来自事实层项目字段和测试阶段定义；系统测试项目以“议题测试阶段定义”为准，客户问题以 `CC_Product` 规则为准。
- 里程碑：客户问题页面按里程碑切换版本或范围；缺失里程碑的客户问题默认不进入客户问题统计范围。
- 用户/负责人：来自事实层归一化用户字段，后续支持代码活跃用户分群。
- 测试阶段：来自测试阶段定义和 `phase_filter_value`，不能只扫描 GitLab 标签文本。

动态标签组的当前值可以缓存，但缓存必须记录来源水位、生成批次和使用的语义口径；源数据变化后只更新当前值，不改写历史快照。

### 静态标签组

静态标签组的值由平台规则确定，不能因为当前数据中没有出现或出现了异常标签就自动增删。静态标签组同样通过后端语义注册表返回，前端不能自行写死。

第一阶段必须纳入的静态标签组：

- 严重程度：`LEVEL1`、`LEVEL2`、`LEVEL3`、`SUGGESTION`。页面显示为一级缺陷、二级缺陷、三级缺陷、建议类；不得与 P1/P2/P3 混用。
- 紧急程度：`P1`、`P2`、`P3`。客户问题未设定紧急程度时按 P3 响应期限处理，但不能把“未设定”永久映射为 P3 标签值。
- 系统测试排除类型：功能屏蔽、已拒绝、建议、关闭状态下的申请否决、关闭状态下的需求如此。
- 延期原因：技术卡点、方案卡点、资源卡点、数据异常、算法问题、机制问题、计算效率。
- 客户问题闭环状态：已修复/完成、申请延期、数据异常、需求如此、设计如此、未复现。
- 非法类型：未设定严重程度、未设定模块、未按照模板回复、缺陷原因不唯一、未按照要求填写缺陷调研模板、计划解决时间格式异常、一级缺陷缺少负责人签字。
- 缺陷原因标准项：来自新缺陷回复模板，并保留旧模板映射，例如“需求理解有误”归并到“新增理解偏差”，“编码逻辑错误”归并到“编码逻辑：业务逻辑错误”。
- 比例空值策略：系统测试缺陷汇总分母为 0 显示 `/`；评审、代码走查、缺陷原因类分母为 0 显示 `0`。

静态标签组允许管理员调整显示名称、排序、启用状态和说明，但不允许直接改变业务含义。业务含义变化必须先更新 `docs/platform-page-business-rules.md`，再补规则层代码和测试。

### 动态分群

动态分群保存规则定义和当前成员缓存。每次计算后覆盖当前成员，并保留计算记录。

### 静态分群

静态分群保存固定成员集合，成员来自人工选择、批量导入、动态分群固化或审批流程。静态分群不自动吸收新增成员，也不因源数据字段变化自动移除成员。

静态分群仍必须声明对象、业务场景和适用语义口径。原因是页面导出、下钻和规则说明仍要知道这些成员当时属于哪个业务范围，不能只保存一串 ID。

### 静态快照

静态快照是一次不可变留痕，可以来源于动态分群、静态分群或页面临时筛选结果。快照成员不会随源数据变化而变化。它和静态分群的区别是：静态分群可以被管理员维护成员；静态快照一旦生成只能作废或重新生成，不能原地修改。

### 规则 DSL

规则 DSL 是受控 JSON，不是 SQL。示例：

```json
{
  "entity": "issue",
  "scope": "customer_open_issue_scope",
  "conditions": [
    {
      "field": "isResponseOverdue",
      "operator": "EQ",
      "value": true
    }
  ],
  "refreshPolicy": {
    "mode": "SCHEDULED",
    "cron": "0 0 * * * *"
  }
}
```

模块分群示例：

```json
{
  "entity": "module",
  "scope": "system_test_issue_scope",
  "conditions": [
    {
      "metric": "level1IssueCount",
      "operator": "GT",
      "value": 10
    }
  ],
  "groupBy": ["projectName", "moduleName"]
}
```

静态标签组引用示例：

```json
{
  "entity": "issue",
  "scope": "system_test_issue_scope",
  "conditions": [
    {
      "tagGroup": "severity",
      "operator": "IN",
      "values": ["LEVEL1", "LEVEL2", "LEVEL3"]
    },
    {
      "tagGroup": "delay_cause",
      "operator": "IN",
      "values": ["TECHNICAL_BLOCKER", "RESOURCE_BLOCKER"]
    }
  ]
}
```

静态分群定义示例：

```json
{
  "entity": "module",
  "segmentType": "STATIC",
  "scenario": "system_test_cross_compare",
  "scope": "system_test_issue_scope",
  "members": [
    {
      "entityId": "module:sketch",
      "displayName": "草图",
      "reason": "本期横向对比重点模块"
    },
    {
      "entityId": "module:drawing",
      "displayName": "工程图",
      "reason": "本期横向对比重点模块"
    }
  ]
}
```

## 用户界面

用户看到的仍是业务熟悉的条件，而不是底层表结构。

创建动态分群时，推荐流程：

```text
分群对象 -> 业务场景 -> 规则条件 -> 刷新方式 -> 预览成员 -> 保存动态分群 / 固化静态快照
```

创建静态分群时，推荐流程：

```text
分群对象 -> 业务场景 -> 适用口径 -> 选择/导入成员 -> 校验成员 -> 保存静态分群 -> 可选生成快照
```

维护标签组时，推荐流程：

```text
业务域 -> 标签组 -> 来源类型 -> 规则说明 -> 选项维护/动态生成预览 -> 发布 schemaHash
```

动态标签组页面展示“由系统生成”，管理员只能配置启用、排序、兜底值和说明；静态标签组页面展示“由平台规则维护”，管理员可调整展示和禁用状态，但改变业务含义必须先走规则文档和测试变更。

示例：客户问题响应延期

```text
分群对象：议题
业务场景：客户问题

适用口径：
- CC_Product
- 2026-01-01 后创建
- 当前 open
- 排除关闭状态下的“申请否决 / 需求如此”

条件：
- 规则状态 = 响应已延期
- 紧急程度 包含 P1、P2
- 模块 包含 草图、工程图

刷新方式：每小时刷新
```

示例：代码活跃用户

```text
分群对象：用户
业务场景：代码活跃度

条件：
- 今日提交次数 > 10
- 近 7 天活跃天数 >= 3
- 所属项目 包含 CrownCAD

刷新方式：每天 08:00 刷新
```

UI 中的条件分三类：

- 业务属性：草图、工程图、一级缺陷、P1、项目、里程碑、用户名。
- 规则状态：响应已延期、解决已延期、非法数据、缺陷原因不唯一。
- 指标条件：今日提交次数、响应周期、解决周期、一级缺陷数、走查缺陷密度。

静态标签组选项在 UI 中必须表现为受控枚举，不允许因为当前查询结果为空就消失。例如严重程度必须始终展示一级缺陷、二级缺陷、三级缺陷、建议类；延期原因必须始终展示七个固定原因。动态标签组选项可以随语义层刷新变化，但页面必须能展示“当前无数据”的 `0` 行，不能破坏平台规则中的模块行、总计行和导出口径。

## 数据层设计

第一阶段建议在现有 fact 层旁补充以下事实/汇总表：

- `issue_module_fact`：一个议题多个模块时拆成多行，用于模块维度统计。
- `issue_reason_fact`：模板解析后的缺陷原因，一条原因一行。
- `issue_rule_fact`：议题规则判定结果，例如非法类型、响应延期、解决延期。
- `customer_issue_sla_fact`：客户问题响应/解决周期、首次响应时间、预计解决时间。
- `module_metric_day_fact`：模块级每日汇总。
- `review_metric_fact`：评审缺陷密度、加权密度、模块、负责人等。
- `mr_review_metric_fact`：MR 走查缺陷数、走查行数、目标分支、合并状态。

后续如果支持用户行为分群，可增加：

- `user_day_commit_stats`：用户每日提交数、仓库数、活跃标记。
- `user_week_activity_stats`：用户周活跃摘要。

标签组数据层分为定义、值、映射和生成记录：

- `semantic_tag_group`：标签组定义，包含 `domain`、`group_key`、`label`、`source_mode`、`rule_policy_key`、`selection_mode`、`match_strategy_name`、`enabled`、`sort_order`。
- `semantic_tag_value`：标签组值，包含 `value_key`、`label`、`value_type`、`canonical_value`、`enabled`、`disabled_reason`、`sort_order`。
- `semantic_tag_value_mapping`：标准值到事实字段、原始标签或外部字典的映射，禁止承载任意 SQL。
- `semantic_tag_group_build_run`：动态或混合标签组生成记录，保存来源水位、schemaHash、值数量、失败原因。

当前代码中的 `tag_group`、`tag_value`、`tag_value_mapping` 可以作为第一阶段兼容实现，但新方案文档和后续接口语义应向 `semantic_tag_*` 收敛，避免继续把标签组理解为旧平台标签文本映射。

## 持久化模型

建议新增分群模型，不复用旧筛选表名：

- `segment_definition`
  - `id`
  - `name`
  - `segment_type`：`DYNAMIC` / `STATIC`
  - `entity_type`
  - `scenario_key`
  - `scope_key`
  - `rule_json`
  - `refresh_policy_json`
  - `member_source`：`RULE` / `MANUAL` / `IMPORT` / `SNAPSHOT`
  - `source_segment_id`
  - `tag_schema_hash`
  - `enabled`
  - `created_by`
  - `created_at`
  - `updated_at`

- `segment_compute_run`
  - `id`
  - `segment_id`
  - `status`
  - `started_at`
  - `finished_at`
  - `source_data_watermark`
  - `member_count`
  - `error_message`

- `segment_member_current`
  - `segment_id`
  - `entity_type`
  - `entity_id`
  - `display_name`
  - `member_payload_json`
  - `member_source`
  - `pinned`
  - `added_by`
  - `added_reason`
  - `computed_run_id`
  - `computed_at`

- `segment_member_audit`
  - `id`
  - `segment_id`
  - `entity_type`
  - `entity_id`
  - `action`：`ADD` / `REMOVE` / `IMPORT` / `RECOMPUTE`
  - `reason`
  - `operator`
  - `created_at`

- `segment_snapshot`
  - `id`
  - `source_segment_id`
  - `name`
  - `entity_type`
  - `snapshot_reason`
  - `created_by`
  - `created_at`

- `segment_snapshot_member`
  - `snapshot_id`
  - `entity_type`
  - `entity_id`
  - `display_name`
  - `member_payload_json`

## 计算链路

```text
GitLab 镜像同步
  -> fact 构建
  -> 规则事实/汇总事实刷新
  -> 动态标签组生成 / 静态标签组读取
  -> 动态分群计算
  -> 当前成员缓存
  -> 可选固化静态分群或静态快照
```

动态分群计算可以复用现有后台任务与运行状态思路，但应与 GitLab 镜像同步运行单元解耦。

静态分群不进入定时重算链路，但进入成员校验链路：

```text
人工选择 / Excel 导入 / 动态结果固化
  -> 按 entity_type 校验成员存在性
  -> 按 scope_key 标记成员当前是否仍在业务范围内
  -> 写入 segment_member_current
  -> 写入 segment_member_audit
  -> 可选生成不可变快照
```

静态成员校验只能标记“当前已不在范围内”“源数据已删除”“名称已变化”等状态，不能自动删除成员。删除必须由管理员明确操作并留下审计。

## 示例规则映射

### 系统测试缺陷汇总

- 对象：`issue` 或 `module`
- 范围：`system_test_issue_scope`
- 排除：`system_test_exclusion_policy`
- 指标：一级/二级/三级缺陷数
- 特殊规则：总计按议题去重；模块展示按模块拆分。
- 标签组：严重程度是静态标签组；模块、项目、测试阶段是动态或混合标签组，但模块行缺失时仍按规则显示 `0`。

### 系统测试非法数据

- 对象：`issue`
- 范围：`system_test_issue_scope`
- 条件：`isIllegal = true`
- 维度：非法类型、模块、项目、阶段。
- 标签组：非法类型是静态标签组；模块、项目、阶段来自语义层动态标签组。

### 缺陷原因分析

- 对象：`issue_reason`
- 范围：`system_test_issue_scope`
- 口径：`defect_reason_policy`
- 指标：缺陷原因个数，不按议题个数。
- 标签组：缺陷原因标准项是静态标签组；旧模板原因只通过映射归并，不能作为独立新增业务值自动出现。

### 客户问题响应延期

- 对象：`issue`
- 范围：`customer_open_issue_scope`
- 条件：`isResponseOverdue = true`
- 规则：P1 24 小时、P2 48 小时、P3 72 小时；未设定按 P3。
- 标签组：紧急程度是静态标签组；模块、里程碑是动态标签组；未设定紧急程度是规则状态，不是第四个紧急程度。

### 横向对比

- 对象：`module`
- 场景：系统测试横向对比
- 组合指标：评审缺陷密度、代码走查缺陷密度、系统测试缺陷数、缺陷原因数。
- 特例：`CC2025R1` 评审数据映射到 `CC2025R1&R2`。
- 标签组：横向对比模块可使用静态分群指定重点模块，也可使用动态模块标签组汇总全部模块；导出时整行全为 `0`、`0.0` 或 `/` 的无效行不展示。

## 迁移策略

1. 旧业务筛选方案停止演进。
2. 新文档、新计划、新接口命名使用 `segment`、`cohort` 或 `object segmentation`，不再使用旧筛选概念命名。
3. 当前 `tag_group` / `tag_value` / `tag_value_mapping` 可作为兼容层继续服务现有页面，但新增设计按 `semantic_tag_*` 语义收敛。
4. 静态标签组优先从 `docs/platform-page-business-rules.md` 中固化，动态标签组优先从 fact/汇总表生成，二者都通过同一 API 返回。
5. 已经进入数据库历史的 Flyway 迁移文件不直接改名；后续通过新增迁移引入 `semantic_tag_*` 和 `segment_*` 表，并逐步废弃旧表。
6. 运行代码中的旧组件和 API 不在文档清理阶段半拆；等实现迁移时统一改名、改接口、改测试。
7. 老方案文档和本地 demo 脚本从仓库删除，避免未来误用。

## 验收标准

- 新方案文档不使用旧筛选方案作为产品概念。
- 用户 UI 只展示业务对象、业务场景、属性、规则状态和指标。
- 后端只接受受控规则 DSL，不接受用户 SQL。
- 标签组方案同时覆盖动态标签组和静态标签组；静态标签组不能只写在前端枚举中。
- 严重程度和紧急程度两套标签组明确分离，不能把一级/二级/三级缺陷与 P1/P2/P3 混用。
- 延期原因、客户问题闭环状态、非法类型、缺陷原因标准项等静态标签组与平台规则一致。
- 动态标签组能记录来源水位、schemaHash 和生成批次。
- 动态分群能预览、保存、刷新和查看当前成员。
- 静态分群能人工选择、导入、从动态结果固化，并保留成员审计。
- 静态快照能从动态分群、静态分群或页面筛选结果固化，并保留成员和生成时间。
- 每个规则结果能追溯到语义口径、数据水位和计算批次。
