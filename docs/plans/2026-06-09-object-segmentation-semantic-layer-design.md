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
9. 支持保存常用筛选方案：用户在业务页面配置的常用条件可以保存、命名、复用，并在标签较多时通过平铺、搜索和分组降低筛选成本。

## 非目标

- 不做任意 SQL 编辑器。
- 不延续旧业务筛选方案作为产品概念。
- 不让前端直接拼接跨表查询条件。
- 不把“静态标签组”降级为前端写死枚举；静态选项仍需由后端语义注册表返回，方便权限、审计、禁用和规则说明统一。
- 不在第一阶段引入完整外部 BI 语义层产品。
- 不承诺强实时；动态结果以平台最近一次镜像同步和事实构建时间为准。

## 计划粒度与估算口径

下文工作日估算按 1 名熟悉本仓的后端/前端全栈开发者计算，包含开发、自测、目标单测、文档更新和一次 PM/业务确认返工缓冲，不包含长时间业务口径等待、生产压测排队和外部系统不可用等待。

可并行任务的前提是：接口契约、表结构和业务口径已冻结；并行分支不得改同一批核心模型和迁移文件。涉及 `semantic_tag_*`、`segment_*`、事实表、规则 DSL 的任务必须先读本文件和 `docs/platform-page-business-rules.md`。

真实关键路径为：

```text
Phase 1 -> max(Phase 2, Phase 3) -> Phase 4 -> Phase 5 -> Phase 6 -> Phase 7
```

单人开发按顺序推进；若有 2 名开发者，Phase 2 事实表扩展与 Phase 3 持久化/API 可并行，预计可缩短 5-7 个工作日。Phase 4 必须等待动态标签组选项所需的关键事实表和 Phase 3 持久化能力完成。

本周交付目标按 MVP 收敛：优先完成 Phase 1、Phase 3 的持久化/API、8 个已定义静态标签组落库、Phase 2 关键事实表字段契约和 Phase 5 受控模板骨架。完整动态刷新、三对象真实成员计算、前端集成和运营化门禁继续按后续 Phase 推进，不把未定义清楚的标签组提前塞进 Phase 3。

## Hash 与版本字段术语

为避免 hash 字段语义混淆，所有实现必须遵守以下命名：

| 字段 | 所属对象 | 生成来源 | 用途 | 变化影响 |
|---|---|---|---|---|
| `rule_doc_hash` | `semantic_scope_definition`、`semantic_tag_group` | 对应业务规则文档段落内容 hash | 检查规则文档和语义定义是否漂移 | 变化时触发规则复核，不直接改历史快照 |
| `rule_doc_version` | `semantic_tag_group` | PM 发布的规则版本号，例如 `2026-06-09-system-test-v1` | 展示和发布审计 | 变化时记录发布说明 |
| `schema_hash` | `semantic_tag_group`、`semantic_tag_group_build_run` | 标签组定义和值 key、启用状态、映射 schema 的稳定 hash | 前端选项缓存、动态标签组刷新结果追踪 | 变化时相关动态分群进入兼容性检查 |
| `tag_schema_hash` | `segment_definition` | 创建分群时引用的一组标签组 schema hash 聚合值 | 判断分群规则是否仍兼容当前语义标签组 | 不兼容时标记 `NEEDS_REVIEW` 或 `NEEDS_REVALIDATION` |
| `scope_hash` | 动态标签组刷新和分群计算任务 | `scope_key` 或 `scopeChain + compositionMode + source_instance` 的稳定 hash | 计算锁、缓存 key、批次去重 | 变化时视为新的计算范围 |
| `dsl_hash` | 临时页面筛选、导出和快照 | 规范化后的 DSL AST hash | 追溯临时筛选来源 | 只用于追溯，不替代 `segment_id` 或 `snapshot_id` |

对外 API 使用 camelCase，例如 `schemaHash`、`tagSchemaHash`、`scopeHash`、`ruleDocHash`、`ruleDocVersion`、`dslHash`；数据库字段使用 snake_case。不得混用 `schemaHash` 指代 `rule_doc_hash`，也不得用 `scope_hash` 表达标签组 schema 版本。

### scope_hash 规范

`scope_hash` 必须由规范化输入稳定生成，避免计算锁和缓存 key 因实现差异失效：

- 输入结构固定为 `{ "mode": "SINGLE" | "CHAIN", "scopeKey": string | null, "scopeChain": string[], "compositionMode": string | null, "sourceInstance": string }`。
- `scopeChain` 顺序敏感，不排序；`["customer_issue_base_scope", "open_state_filter"]` 与 `["open_state_filter", "customer_issue_base_scope"]` 生成不同 hash。
- 单一 `scope_key` 与 `scopeChain=[scope_key]` 不等价，前者使用 `mode=SINGLE`，后者使用 `mode=CHAIN`。
- `source_instance` 缺省时统一写入字符串 `default`，不得使用空字符串或 null 参与 hash。
- 所有字符串先做 Unicode NFC 规范化，再按字段名稳定排序序列化为紧凑 JSON，最后计算 SHA-256 hex。

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

语义口径不能无限平铺枚举。第一阶段必须引入“口径链”组合模型，将基础范围、状态过滤、里程碑过滤、排除策略拆成可组合节点，避免业务方提出“客户问题 + 已关闭 + 特定里程碑”时新增大量一次性 scope。

口径链示例：

```json
{
  "scopeChain": [
    "customer_issue_base_scope",
    "closed_state_filter",
    "milestone_required_filter"
  ],
  "compositionMode": "AND"
}
```

每个口径节点必须声明：

- `scope_key`：稳定机器名。
- `scope_type`：`BASE_SCOPE` / `FILTER` / `EXCLUSION_POLICY` / `METRIC_POLICY`。
- `entity_type`：适用对象。
- `description`：业务解释。
- `rule_doc_reference`：对应规则文档位置。
- `rule_doc_hash`：对应规则段落 hash。
- `allowed_parent_types`：允许组合在哪些口径之后。
- `sql_template_key`：后端预定义 SQL 模板名，不能保存任意 SQL。

建议新增语义口径注册表：

```java
public interface SemanticScopeRegistry {
  SemanticScope resolve(String scopeKey);
  SemanticScopePlan compose(List<String> scopeChain, CompositionMode mode);
  void validateCompatibility(String entityType, List<String> scopeChain);
}
```

配套表：

```sql
create table semantic_scope_definition (
    scope_key varchar(128) primary key,
    scope_type varchar(64) not null,
    entity_type varchar(64) not null,
    description text not null,
    sql_template_key varchar(128) not null,
    rule_doc_reference varchar(255),
    rule_doc_hash varchar(64),
    enabled boolean not null default true,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);
```

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

动态标签组缓存必须有明确失效策略：

- fact 构建完成后发布 `FactBuildCompletionEvent`，按 `source_instance` 标记相关标签组 stale。
- 手动刷新只刷新管理员选择的标签组，不触发全域重算。
- 定时刷新必须检查来源水位，水位未变化时只更新 `checked_at`，不重复计算。
- 并发刷新通过计算锁控制，同一 `group_key + source_instance + scope_hash` 同时只能有一个计算任务。
- API 必须返回 `cacheAge`、`dataWatermark`、`isStale`、`buildRunId`，页面可提示“选项基于最近一次事实构建”。

`semantic_tag_group` 建议补充：

```sql
cache_ttl_seconds integer not null default 3600,
cache_invalidation_trigger varchar(255) not null default 'FACT_BUILD_COMPLETE,MANUAL',
last_computed_at timestamp,
last_checked_at timestamp,
last_data_watermark timestamp,
computing_lock_key varchar(255)
```

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

静态标签组变更必须执行规则文档同步流程：

1. 业务方提出口径变更。
2. PM 更新 `docs/platform-page-business-rules.md`，明确规则版本和影响页面。
3. 代码变更同步更新语义注册表、规则事实构建、测试和文档 hash。
4. CI 执行 `scripts/check_semantic_tag_rule_drift.py`，对比规则文档段落 hash 与 `semantic_tag_group.rule_doc_hash`。
5. staging 验收通过后发布，记录 `rule_doc_version`。

`semantic_tag_group` 建议补充：

```sql
rule_doc_version varchar(64),
rule_doc_hash varchar(64),
admin_override_allowed boolean not null default false
```

### 动态分群

动态分群保存规则定义和当前成员缓存。每次计算后覆盖当前成员，并保留计算记录。

### 静态分群

静态分群保存固定成员集合，成员来自人工选择、批量导入、动态分群固化或审批流程。静态分群不自动吸收新增成员，也不因源数据字段变化自动移除成员。

静态分群仍必须声明对象、业务场景和适用语义口径。原因是页面导出、下钻和规则说明仍要知道这些成员当时属于哪个业务范围，不能只保存一串 ID。

### 静态快照

静态快照是一次不可变留痕，可以来源于动态分群、静态分群或页面临时筛选结果。快照成员不会随源数据变化而变化。它和静态分群的区别是：静态分群可以被管理员维护成员；静态快照一旦生成只能作废或重新生成，不能原地修改。

### 常用筛选方案

常用筛选方案是业务页面上的轻量预设，用于保存用户经常复用的一组筛选条件。它不等同于动态分群：常用筛选方案默认不预计算成员，也不维护成员缓存；用户应用方案时，系统把保存的受控 DSL 重新应用到当前页面查询或提示用户保存为动态分群。

常用筛选方案必须保存：

- `preset_id`：稳定 ID。
- `preset_name`：用户可读名称。
- `owner_user_id`：创建人。
- `visibility`：`PRIVATE` / `TEAM` / `PUBLIC`。
- `entity_type`、`scenario_key`、`scope_key`。
- `dsl_json`、`dsl_hash`、`tag_schema_hash`。
- `source_data_watermark_at_save`：保存时页面数据水位，仅用于提示，不作为当前查询水位。
- `created_at`、`updated_at`、`last_used_at`。

当 `tag_schema_hash` 与当前标签组 schema 不兼容时，页面应用方案前必须提示用户复核；不能静默丢弃已保存条件，也不能继续使用过期 value_key。

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
      "semanticTagGroup": "severity",
      "operator": "IN",
      "values": ["LEVEL1", "LEVEL2", "LEVEL3"]
    },
    {
      "semanticTagGroup": "delay_cause",
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

规则 DSL 必须先转 AST，再由后端白名单模板生成参数化 SQL。禁止直接拼接用户输入 SQL。Phase 1 只支持预编译模板，Phase 5 再开放受控 DSL 到 SQL。

执行器接口：

```java
public interface SemanticQueryExecutor {
  List<SegmentMember> executeTemplate(String templateName, Map<String, Object> params);
  List<SegmentMember> executeDsl(SegmentRuleDsl dsl, ExecutionContext context);
  ExecutionPlan explain(SegmentRuleDsl dsl);
  void validateComplexity(SegmentRuleDsl dsl);
}
```

安全规范：

- 字段、指标、操作符、标签组和口径 key 必须来自注册表白名单。
- 所有值只作为 SQL 参数绑定，不进入 SQL 片段。
- 禁止用户 DSL 表达 join、subquery、rawSql、orderByExpression。
- 每个模板必须声明最大扫描行数、最大返回成员数和超时时间。
- `EXPLAIN` 预估扫描行数超过 100 万时要求管理员确认；预估执行时间超过 30 秒时拒绝创建。
- 单个动态分群默认成员上限为 50,000；第一阶段建议软上限 10,000，超过则要求保存为静态快照或拆分规则。

`segment_definition` 建议补充：

```sql
execution_plan_json text,
execution_cost_estimate_json text,
estimated_cost numeric(18, 4),
max_execution_time_ms integer not null default 30000,
max_allowed_members integer not null default 50000
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

保存常用筛选方案时，推荐流程：

```text
业务页面筛选 -> 保存常用方案 -> 命名和选择可见范围 -> 保存 DSL 与 schemaHash -> 下次从常用方案快速应用
```

维护标签组时，推荐流程：

```text
业务域 -> 标签组 -> 来源类型 -> 规则说明 -> 选项维护/动态生成预览 -> 发布 schemaHash
```

动态标签组页面展示“由系统生成”，管理员只能配置启用、排序、兜底值和说明；静态标签组页面展示“由平台规则维护”，管理员可调整展示和禁用状态，但改变业务含义必须先走规则文档和测试变更。

业务页面的标签筛选控件必须适配标签数量：

- 标签值数量不超过 12 个时优先平铺为 checkbox / segmented checkbox，不使用单个下拉框隐藏选项。
- 标签值数量为 13-50 个时使用可搜索平铺面板，支持“全选当前组 / 清空当前组 / 已选置顶”。
- 标签值数量超过 50 个时使用搜索、分组、虚拟滚动和已选摘要，仍要提供常用值快捷区，避免用户只能在长下拉框里逐项寻找。
- 多个标签组同时出现时，页面必须提供“保存常用方案”和“应用常用方案”入口；保存内容为受控 DSL、`dsl_hash`、`tag_schema_hash` 和可见范围，不保存旧 `tagSelections`。
- 常用方案应用后必须展示已应用方案名，并允许用户继续微调后另存为新方案或保存为动态分群。

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

事实表扩展阶段必须在动态标签组刷新和动态分群真实计算前完成。建议在现有 fact 层旁补充以下事实/汇总表：

- `issue_module_fact`：一个议题多个模块时拆成多行，用于模块维度统计。
- `issue_reason_fact`：模板解析后的缺陷原因，一条原因一行。
- `issue_rule_fact`：议题规则判定结果，例如非法类型、响应延期、解决延期。
- `customer_issue_sla_fact`：客户问题响应/解决周期、首次响应时间、预计解决时间。
- `module_metric_day_fact`：模块级每日汇总。
- `review_metric_fact`：评审缺陷密度、加权密度、模块、负责人等。
- `mr_review_metric_fact`：MR 走查缺陷数、走查行数、目标分支、合并状态。

`customer_issue_sla_fact` 必须显式保存时区换算字段，响应和解决效率统一按北京时间计算：

```sql
created_at_utc timestamp,
created_at_bjt timestamp,
first_response_at_bjt timestamp,
resolved_at_bjt timestamp,
response_elapsed_hours numeric(12, 2),
resolution_elapsed_days numeric(12, 2),
urgency varchar(8) not null,
response_due_at_bjt timestamp,
resolve_due_at_bjt timestamp,
response_overdue boolean not null,
resolve_overdue boolean not null,
sla_timezone varchar(32) not null default 'Asia/Shanghai'
```

`module_metric_day_fact` 必须冻结最低字段契约，供模块动态标签组和模块对象分群复用：

```sql
metric_date date not null,
source_instance varchar(64) not null,
standard_module_name varchar(255) not null,
metric_key varchar(128) not null,
metric_value numeric(18, 4) not null,
fact_build_run_id bigint not null
```

横向对比必须覆盖 `CC2025R1` 评审数据映射到 `CC2025R1&R2` 的业务特例，不能只在页面层硬编码。

后续如果支持用户行为分群，可增加：

- `user_day_commit_stats`：用户每日提交数、仓库数、活跃标记。
- `user_week_activity_stats`：用户周活跃摘要。

标签组数据层分为定义、值、映射和生成记录：

- `semantic_tag_group`：标签组定义，包含 `domain`、`group_key`、`label`、`source_mode`、`rule_policy_key`、`selection_mode`、`match_strategy_name`、`enabled`、`sort_order`。
- `semantic_tag_value`：标签组值，包含 `value_key`、`label`、`value_type`、`canonical_value`、`enabled`、`disabled_reason`、`sort_order`。
- `semantic_tag_value_mapping`：标准值到事实字段、原始标签或外部字典的映射，禁止承载任意 SQL。
- `semantic_tag_group_build_run`：动态或混合标签组生成记录，保存来源水位、`schema_hash`、值数量、失败原因。

旧 `tag_group`、`tag_value`、`tag_value_mapping` 只作为历史 Flyway 迁移记录存在，不作为第一阶段兼容实现。运行时代码和后续接口必须直接面向 `semantic_tag_*` 收敛，避免继续把标签组理解为旧平台标签文本映射。

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

- `segment_filter_preset`
  - `id`
  - `preset_name`
  - `owner_user_id`
  - `visibility`：`PRIVATE` / `TEAM` / `PUBLIC`
  - `entity_type`
  - `scenario_key`
  - `scope_key`
  - `dsl_json`
  - `dsl_hash`
  - `tag_schema_hash`
  - `source_data_watermark_at_save`
  - `last_used_at`
  - `created_at`
  - `updated_at`

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

动态分群成员刷新不能采用无控制的全量 `delete + insert`。第一阶段默认使用“双缓冲批次替换”：

1. 将新成员写入 `segment_member_stage`，带 `computed_run_id`。
2. 写入完成后在事务内切换 `segment_definition.active_run_id`。
3. 查询当前成员时按 `active_run_id` 读取。
4. 后台只保留最近 3 个成功批次，过期批次异步清理。

性能约束：

- 单次刷新写入量超过 100,000 行时必须降级为后台长任务并提示用户。
- 动态分群计算不能占用 GitLab sync worker pool。
- `segment_member_current` 或 stage 表必须按 `segment_id`、`computed_run_id`、`entity_type` 建索引；当 `segment_member_current` 总行数超过 500 万，或单个 segment 成员超过 50 万时，启动按 `computed_at` 或 `segment_id` 分区评估。

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

计算事件链路必须与现有 sync 引擎解耦：

```java
@EventListener
public void onFactBuildComplete(FactBuildCompletionEvent event) {
  semanticTagGroupRefreshService.scheduleRefresh(event.sourceInstance());
}

@EventListener
public void onSemanticTagGroupRefreshed(SemanticTagGroupRefreshEvent event) {
  segmentComputeService.scheduleDynamicSegments(event.affectedGroupKeys());
}
```

要求：

- 不直接复用 `SyncRunCompletionEvent` 触发分群计算。
- 分群计算使用独立线程池和队列表，避免拖慢 2 秒级 sync 调度。
- 标签组刷新失败不能回滚 fact 构建；分群计算失败只标记对应 `segment_compute_run` 失败。
- 每个阶段都记录输入水位、输出批次、耗时和错误信息。

建议新增：

```java
@Configuration
public class SegmentComputeConfig {
  @Bean
  public ThreadPoolTaskExecutor segmentComputeExecutor() {
    // 与 sync worker pool 隔离
  }
}
```

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

## 前端路由与权限

分群和语义标签组管理页归入系统设置模块，避免与业务记录页混在一起。第一阶段建议：

- `segment-management`：`/system-settings/segments`，ADMIN 可管理，APPROVAL 只读。
- `semantic-tag-group-management`：`/system-settings/semantic-tag-groups`，ADMIN 可维护展示属性，业务含义只读。
- `segment-snapshot-list`：`/system-settings/segment-snapshots`，ADMIN 可作废，APPROVAL 可查看和导出。

已有页面集成：

- 系统测试缺陷汇总：增加“保存为分群 / 保存为快照”入口。
- 客户问题记录：增加“从分群加载”筛选器。
- 记录页导出：导出参数必须记录 `segment_id`、`snapshot_id` 或临时 DSL hash。
- 所有新增按钮必须有 loading、失败提示和权限禁用状态。

## 向后兼容与 schema 变更

本仓已经删除旧 `tag_group` 运行时代码和最终 schema；历史 Flyway 迁移只作为不可变历史保留。新方案不做旧标签组双写，也不再以旧接口作为兼容层。向后兼容只针对新 `semantic_tag_*` schema 内的版本变化。

`tag_schema_hash` 变化时必须执行兼容性检查：

```java
public interface SegmentSchemaCompatibilityChecker {
  CompatibilityResult check(
      SegmentDefinition segment,
      String oldSchemaHash,
      String newSchemaHash);
}
```

规则：

- 新增标签组选项：兼容，动态分群可自动重算。
- 禁用或删除选项：不兼容，相关分群标记为 `NEEDS_REVIEW`。
- 标签组 key 改名：必须提供迁移脚本和管理员确认。
- 语义口径规则变化：相关动态分群标记为 `NEEDS_REVALIDATION`，静态快照不变。
- 静态分群成员不因 schema 变化自动删除，只显示“当前口径已变化”的风险提示。

## Phase 1 实施计划

预计工作日：4-6 个工作日。

阻塞依赖：旧错误标签组实现已删除；`semantic_tag_*`、`segment_*` 基础命名已确认；PostgreSQL/Flyway 校验链路可用。

可并行任务：数据库迁移与服务骨架可并行评审，但最终 schema 和 service DTO 必须由同一人收敛；前端不应在 Phase 1 并行开发业务页面。

Phase 1 目标是建立基础设施，不实现完整复杂 DSL。

### 数据库迁移

新增表：

- `semantic_scope_definition`
- `semantic_tag_group`
- `semantic_tag_value`
- `semantic_tag_value_mapping`
- `semantic_tag_group_build_run`
- `segment_definition`
- `segment_compute_run`
- `segment_member_current`
- `segment_member_stage`
- `segment_member_audit`
- `segment_snapshot`
- `segment_snapshot_member`

核心索引：

- `semantic_tag_group(domain, group_key)`
- `semantic_tag_value(group_id, enabled, sort_order, value_key)`
- `segment_definition(entity_type, scenario_key, enabled)`
- `segment_member_current(segment_id, computed_run_id, entity_type, entity_id)`
- `segment_member_stage(computed_run_id, segment_id, entity_type, entity_id)`
- `segment_compute_run(segment_id, status, started_at)`

### 服务骨架

- `SemanticScopeRegistry`
- `SemanticTagGroupService`
- `SemanticQueryExecutor`
- `SegmentCostEstimator`
- `SegmentComputeService`
- `SegmentSchemaCompatibilityChecker`

### 初始能力

- 注册至少 3 个语义口径：系统测试议题范围、客户问题基础范围、客户 open 范围。
- 注册至少 3 个静态标签组：严重程度、紧急程度、非法类型。
- 支持创建动态分群定义，但第一阶段可只保存规则和预览空成员。
- 支持创建静态快照空壳和审计记录。

### Phase 1 验收

- Flyway 迁移可在 staging 数据库运行。
- 后端能返回静态标签组选项和 schemaHash。
- 能创建、查询、禁用一个分群定义。
- 单元测试覆盖 `SemanticScopeRegistry`、`SemanticTagGroupService`、`SegmentComputeService` 基础路径。
- 覆盖率目标：新增服务行覆盖率不低于 80%。

Phase 1 只是基础设施阶段，不是方案终点。后续阶段必须继续完成持久化、动态刷新、受控 DSL 计算、前端集成和运营化门禁。

## Phase 2 实施计划

预计工作日：7-10 个工作日。

阻塞依赖：Phase 1 基础 schema 已通过 Flyway/schema 漂移检查；`docs/platform-page-business-rules.md` 中系统测试、客户问题、评审、MR 走查口径已冻结；已有 fact 构建任务可扩展。

可并行任务：事实表 DDL 与 ETL 服务可以并行；系统测试/客户问题事实和评审/MR 事实可以拆两条线；字段契约和回填策略必须先集中评审。

Phase 2 目标是扩展事实/汇总层，为动态标签组刷新、模块条件分群、缺陷原因分群、SLA 分群和横向对比提供稳定数据来源。没有这些表，后续动态标签组和动态分群只能退回扫明细镜像表，不能进入 Phase 4/5。

### 事实表迁移

新增事实/汇总表：

- `issue_module_fact`
- `issue_reason_fact`
- `issue_rule_fact`
- `customer_issue_sla_fact`
- `module_metric_day_fact`
- `review_metric_fact`
- `mr_review_metric_fact`

核心字段要求：

- 所有表必须包含 `source_system`、`source_instance`、`source_data_watermark`、`fact_build_run_id`、`fact_refreshed_at`。
- 议题类事实必须包含 `project_id`、`issue_id`、`issue_iid`、`entity_key`、`deleted`。
- 模块事实必须保存标准模块名、原始模块名、模块字典版本和模块排序。
- 缺陷原因事实必须保存模板版本、原始原因、标准原因、归并策略 key。
- `customer_issue_sla_fact` 必须保存 UTC 与北京时间字段，统一使用 `Asia/Shanghai`；同时冻结 `urgency varchar(8) not null`、`response_overdue boolean not null`、`resolve_overdue boolean not null`、`response_due_at_bjt timestamp`、`resolve_due_at_bjt timestamp` 字段契约。
- `module_metric_day_fact` 必须保存 `metric_key`、`metric_value`、`metric_date`、`standard_module_name`、`source_instance`、`fact_build_run_id`，Phase 5 模板只能引用这些已冻结字段。
- MR 事实必须保存目标分支、合并状态、走查行数、缺陷数和来源摘要。

核心索引：

- `issue_module_fact(source_instance, project_id, standard_module_name, deleted)`
- `issue_reason_fact(source_instance, standard_reason_key, deleted)`
- `issue_rule_fact(source_instance, rule_key, rule_value, deleted)`
- `customer_issue_sla_fact(source_instance, urgency, response_overdue, resolve_overdue)`
- `module_metric_day_fact(metric_date, source_instance, standard_module_name)`
- `review_metric_fact(source_instance, project_name, module_name, review_date)`
- `mr_review_metric_fact(source_instance, target_branch, merge_request_state, merged_at_source)`

### ETL 来源

- `issue_module_fact` 来自 `issue_fact.module_names`、`module_dictionary` 和系统测试阶段定义。
- `issue_reason_fact` 来自缺陷回复模板解析结果，复用现有缺陷原因分析规则。
- `issue_rule_fact` 来自非法数据判定、系统测试排除规则、响应延期、解决延期等规则服务。
- `customer_issue_sla_fact` 来自 `issue_fact`、评论解析、客户问题范围和 P1/P2/P3 响应规则。
- `module_metric_day_fact` 来自议题、评审和 MR 事实的按日汇总。
- `review_metric_fact` 来自 `review_records`、`review_problem_items`，包含 `CC2025R1 -> CC2025R1&R2` 映射。
- `mr_review_metric_fact` 来自 `merge_request_fact` 和代码走查外部指标。

### 回填策略

- 新表先建空表和索引，再用独立 fact build 任务回填，不能在 schema migration 中直接跑大批量 DML。
- 回填按 `source_instance + project_id + updated_at_source` 或业务日期分批，每批记录输入水位和输出行数。
- 回填失败只标记对应 fact build 任务失败，不影响已有页面。
- 回填完成前，动态标签组 API 必须返回 `isStale=true` 或 `sourceReady=false`，不能静默使用不完整数据。
- 首次回填完成后对比现有页面统计：系统测试排除、模块拆分、缺陷原因、客户 SLA、评审/MR 横向对比。

### Phase 2 验收

- 7 张事实/汇总表完成 Flyway migration、`schema.sql` 同步和漂移检查。
- 每张表至少有一个 ETL 单元测试和一个字段契约测试。
- 初次回填任务可按 source_instance 分批运行并记录水位。
- 模块动态标签组可从 `issue_module_fact` 查询候选项。
- 客户 SLA 抽样 50 个议题，北京时间响应/解决计算准确率 100%（端到端复核见 Phase 7）。
- 系统测试模块拆分统计总数与原逻辑误差小于 1%。

## Phase 3 实施计划

预计工作日：5-7 个工作日。

阻塞依赖：Phase 1 服务骨架和 controller API 已完成；Phase 2 事实表不必全部完成，但静态标签组和分群定义持久化可以先落地。

可并行任务：repository 实现、controller 契约测试、静态标签组初始化脚本可并行；最终 API 响应结构由后端统一收敛。

Phase 3 目标是把 Phase 1 的内存骨架接入数据库和稳定 API，让分群定义、静态标签组、审计和快照具备真实持久化能力。Phase 3 仍不开放复杂 DSL 计算，只保存受控规则定义和空预览结果。

### 持久化接入

- 新增 `SemanticScopeRepository`，读写 `semantic_scope_definition`，并支持从代码内置定义同步到表。
- 新增 `SemanticTagGroupRepository`，读写 `semantic_tag_group`、`semantic_tag_value`、`semantic_tag_value_mapping`。
- 新增 `SegmentDefinitionRepository`，读写 `segment_definition`、`segment_compute_run`、`segment_member_current`、`segment_member_audit`。
- 新增 `SegmentSnapshotRepository`，读写 `segment_snapshot`、`segment_snapshot_member`。
- `SegmentComputeService` 从内存 map 切换为 repository，保留当前 controller API 响应结构。

### API 完整化

- `GET /api/semantic-tag-groups/static` 返回静态标签组、值、schemaHash、ruleDocVersion、ruleDocHash。
- `GET /api/segments` 支持 `entityType`、`scenarioKey`、`enabled`、分页和排序。
- `POST /api/segments` 创建动态或静态分群定义，但动态分群本阶段只保存规则，不计算真实成员。
- `PATCH /api/segments/{id}/disabled` 禁用分群，写入审计。
- `POST /api/segment-snapshots` 创建静态快照空壳，Phase 6 再填充来自业务页面或动态分群的成员来源。

### 数据初始化

- 初始化至少 10 个预定义语义口径，并逐项映射到本文件“背景”列出的业务口径：

| scope_key | 对象 | 对应业务口径 | Phase 交付 |
|---|---|---|---|
| `system_test_issue_scope` | `issue` | 系统测试/回归测试议题范围 | Phase 3 注册，Phase 5 计算 |
| `system_test_exclusion_policy` | `issue` | 功能屏蔽、已拒绝、建议、申请否决、需求如此等排除规则 | Phase 3 注册，Phase 5 计算 |
| `customer_issue_base_scope` | `issue` | `CC_Product` 2026-01-01 后客户问题基础范围 | Phase 3 注册，Phase 5 计算 |
| `customer_issue_open_scope` | `issue` | 客户问题 open 范围 | Phase 3 注册，Phase 5 计算 |
| `merged_dev_mr_scope` | `merge_request` | `MERGED` 且目标分支为 `dev` 的 MR 范围 | Phase 3 注册，Phase 5 计算 |
| `review_project_mapping_policy` | `review_record` | `CC2025R1 -> CC2025R1&R2` 评审映射特例 | Phase 3 注册，Phase 5 模板预留 |
| `illegal_issue_policy` | `issue` | 非法数据判定规则 | Phase 3 注册，Phase 5 计算 |
| `defect_reason_policy` | `issue` | 缺陷原因模板归并 | Phase 3 注册，Phase 5 计算 |
| `customer_issue_sla_policy` | `issue` | P1/P2/P3 响应、预计解决、18 天上限和北京时间换算 | Phase 3 注册，Phase 5 计算 |
| `module_split_count_policy` | `module` | 按模块拆分计数 | Phase 3 注册，Phase 5 计算 |
| `ratio_display_policy` | `issue` | 分母为 0 时显示 `/` 或 `0` 的场景规则 | Phase 3 注册，Phase 5 计算 |

- Phase 3 只初始化 8 个已经在“静态标签组”小节定义清楚业务含义的静态标签组。剩余静态标签组不得凭空补名，必须在 Phase 6 随业务页面集成补齐 value_key 列表、规则说明和 `rule_doc_hash` 后再落库：

| group_key | 对应业务含义 | Phase 交付 |
|---|---|---|
| `severity_level` | 严重程度/一级二级三级缺陷 | Phase 3 |
| `urgency` | 客户问题 P1/P2/P3 紧急程度 | Phase 3 |
| `system_test_exclusion_type` | 系统测试排除类型 | Phase 3 |
| `delay_cause` | 延期原因 | Phase 3 |
| `customer_issue_closure_status` | 客户问题闭环状态 | Phase 3 |
| `illegal_type` | 非法数据类型 | Phase 3 |
| `defect_reason_standard` | 缺陷原因标准项 | Phase 3 |
| `ratio_empty_value_policy` | 分母为空/为 0 的展示策略 | Phase 3 |

候选但暂不初始化的静态标签组包括 `issue_state`、`testing_phase`、`module_source_type`、`response_sla_status`、`resolution_sla_status`、`mr_merge_state`、`review_problem_status`。这些组由 Phase 6 根据具体页面需要补齐，不得在 Phase 3 以空值或猜测值写入。

- 初始化数据必须通过新增 Flyway data migration 或受控启动同步器完成，不能由前端写死。
- 初始化脚本必须能幂等执行，不得覆盖管理员后续调整的展示名称、排序和启用状态。
- 新增 `scripts/check_semantic_tag_rule_drift.py`，在初始化数据落地时对比规则文档段落 hash 与 `semantic_tag_group.rule_doc_hash`、`semantic_scope_definition.rule_doc_hash`，避免 Phase 3 之后规则文档变更长期无人发现。

### Phase 3 验收

- 重启后已创建的分群定义仍可查询、禁用和审计。
- 静态标签组 API 返回内容来自数据库，并与 `docs/platform-page-business-rules.md` 的业务含义一致。
- `schemaHash` 在标签组 key、值 key 或启用状态变化时稳定变化。
- `scripts/check_semantic_tag_rule_drift.py` 能在本地发现规则文档 hash 与初始化数据不一致。
- API 单元测试覆盖 controller、repository、service 的创建、查询、禁用、异常路径。
- 旧 `TagGroup`、`TagSelection`、`tagSelections`、`tag-groups` 裸命名不出现在运行时代码中；`semantic-tag-groups` 作为新语义 API 允许存在。

## Phase 4 实施计划

预计工作日：6-8 个工作日。

阻塞依赖：Phase 2 至少完成 `issue_module_fact` 和相关水位字段；Phase 3 已有动态标签组定义和值持久化能力；事实构建完成事件可发布。

可并行任务：事件/队列、刷新服务、动态标签组选项查询可以并行；并发锁和 build run 状态必须统一设计。

Phase 4 目标是让动态语义标签组具备刷新能力，并把事实构建完成事件、缓存失效和后台计算队列串起来。Phase 4 聚焦“标签组选项刷新”，不直接计算动态分群成员。

### 动态标签组刷新

- 实现 `SemanticTagGroupRefreshService`，按 `group_key + source_instance + scope_hash` 刷新动态标签组选项。
- 动态标签组来源优先使用 fact 或汇总表。Phase 4 至少交付 5 个动态标签组：

| group_key | 对象 | 来源表 | 刷新范围 |
|---|---|---|---|
| `module` | `issue`、`module` | `issue_module_fact`、`module_metric_day_fact` | 按 source_instance 和系统测试/客户问题 scope 刷新 |
| `project_version` | `issue`、`merge_request` | `issue_fact`、`review_metric_fact`、`mr_review_metric_fact` | 按 source_instance 刷新项目/版本选项 |
| `milestone` | `issue` | `issue_fact`、`issue_rule_fact` | 按项目和 source_instance 刷新 |
| `owner_user` | `issue`、`review_record` | `issue_fact`、`review_metric_fact` | 按业务页面权限范围刷新 |
| `testing_phase_dynamic` | `issue` | `issue_rule_fact`、`issue_module_fact` | 按系统测试 scope 刷新事实中实际出现的阶段 |

- 刷新任务写入 `semantic_tag_group_build_run`，记录 `source_data_watermark`、`schema_hash`、`value_count`、耗时和失败原因。
- 支持手动刷新、定时刷新和事实构建完成后的 stale 标记。
- 同一标签组同一范围同一来源只允许一个计算任务并发运行。

### 事件与队列

- 新增 `FactBuildCompletionEvent`，事实构建成功后发布，不直接复用 `SyncRunCompletionEvent`。
- 新增 `SemanticTagGroupRefreshEvent`，由事实构建完成、手动刷新或定时任务触发。
- 标签组刷新使用独立线程池和队列表，不占用 GitLab sync worker pool。
- 刷新失败不能回滚 fact 构建，只更新 build run 状态并暴露给页面。

### 缓存与水位

- API 返回 `cacheAge`、`dataWatermark`、`isStale`、`buildRunId`。
- 来源水位未变化时只更新 `last_checked_at`，不重复计算。
- 刷新成功只更新当前标签组选项，不改写历史静态快照。

### Phase 4 验收

- 手动刷新模块动态标签组后，API 能返回最新模块选项和 build run 信息。
- 事实构建完成事件能把相关动态标签组标记为 stale。
- 5 个动态标签组均能记录 `schema_hash`、`source_data_watermark` 和 `scope_hash`。
- 标签组刷新总选项数小于 1,000 时耗时小于 60 秒。
- 刷新失败可在 API 中看到失败原因，不影响事实构建成功状态。
- 并发刷新同一标签组时只有一个任务实际执行。

## Phase 5 实施计划

预计工作日：10-14 个工作日。若只交付 `issue` 对象基础计算约 8-10 个工作日；本方案把 `module` 和 `merge_request` 真实成员计算并入 Phase 5，因此按 10-14 个工作日排期。

阻塞依赖：Phase 2 事实表完成关键字段和回填；Phase 3 分群定义持久化完成；Phase 4 动态标签组选项刷新可用；DSL 白名单字段完成评审。

可并行任务：DSL AST/parser、SQL 模板、成本估算、成员双缓冲写入可以并行；最终执行器和安全测试必须由同一条主线收敛。

Phase 5 目标是开放受控 DSL 和动态分群真实成员计算，首批必须支持 `issue`、`module`、`merge_request` 三种对象。此阶段开始写入 `segment_compute_run`、`segment_member_stage`、`segment_member_current`，并使用双缓冲批次切换当前成员。

### DSL 与模板执行

- `SegmentRuleDsl` 先解析为 AST，再由白名单模板生成参数化 SQL。
- 第一批支持条件：语义口径、静态标签组值、动态标签组值、时间范围、状态、模块、负责人、项目/版本、里程碑。
- 禁止用户 DSL 表达 join、subquery、rawSql、orderByExpression。
- 所有字段、指标、操作符、标签组和口径 key 必须来自注册表白名单。
- 所有用户输入值只作为 SQL 参数绑定，不进入 SQL 片段。
- AST 字段白名单按对象拆分维护：`issue` 只能使用 `issue_fact`、`issue_module_fact`、`issue_reason_fact`、`issue_rule_fact`、`customer_issue_sla_fact` 暴露字段；`module` 只能使用 `issue_module_fact`、`module_metric_day_fact` 暴露字段；`merge_request` 只能使用 `mr_review_metric_fact` 和 MR 基础事实字段。

### SQL 模板登记

Phase 5 新增模板必须先登记，再允许 DSL 引用。模板登记表至少覆盖：

| templateName | entityType | 参数 | 参数化字段白名单 | 估算行数 |
|---|---|---|---|---|
| `system_test_issue_scope` | `issue` | `sourceInstance`、`projectIds`、`phaseKeys`、`includeRegression` | `issue_fact.project_id`、`issue_rule_fact.rule_key`、`issue_module_fact.standard_module_key` | 小于 200,000 |
| `customer_issue_base_scope` | `issue` | `sourceInstance`、`sinceDate`、`projectId` | `issue_fact.project_id`、`issue_fact.created_at_source`、`customer_issue_sla_fact.urgency` | 小于 100,000 |
| `customer_issue_open_scope` | `issue` | `sourceInstance`、`sinceDate`、`issueState` | `issue_fact.state`、`customer_issue_sla_fact.response_overdue`、`customer_issue_sla_fact.resolve_overdue` | 小于 50,000 |
| `module_metric_scope` | `module` | `sourceInstance`、`projectIds`、`metricDateRange`、`moduleKeys` | `issue_module_fact.standard_module_key`、`module_metric_day_fact.metric_date`、`module_metric_day_fact.metric_key` | 小于 300,000 |
| `defect_reason_scope` | `issue` | `sourceInstance`、`reasonKeys`、`templateVersion` | `issue_reason_fact.standard_reason_key`、`issue_reason_fact.template_version` | 小于 200,000 |
| `merged_dev_mr_scope` | `merge_request` | `sourceInstance`、`targetBranch`、`mergeState`、`dateRange` | `mr_review_metric_fact.target_branch`、`mr_review_metric_fact.merge_request_state`、`mr_review_metric_fact.merged_at_source` | 小于 150,000 |

模板名必须复用语义口径或业务场景 key，禁止临时起名。新增模板需要同步补 AST 白名单、字段契约测试和 `SegmentComputeServiceTest`。

### 成本估算

- `SegmentCostEstimator` 执行 `EXPLAIN` 或模板级估算，记录 `execution_plan_json`、`execution_cost_estimate_json`、`estimated_cost`。
- 预估扫描行数超过 100 万时要求管理员确认。
- 预估执行时间超过 30 秒时拒绝创建或要求转后台任务。
- 单个动态分群默认成员上限 50,000；初始上线软上限 10,000，超过则建议固化为静态快照或拆分规则。

### 成员计算

- 计算开始前调用 `SegmentSchemaCompatibilityChecker`，校验 `segment_definition.tag_schema_hash` 与当前引用标签组的 `schema_hash` 是否兼容；若返回 `NEEDS_REVIEW` 或 `NEEDS_REVALIDATION`，跳过本次计算，并将 `segment_compute_run.status` 标记为 `FAILED`、`error_message` 标记为 `SCHEMA_INCOMPATIBLE`。
- 动态计算先写入 `segment_member_stage`，带 `computed_run_id`。
- 写入完成后在事务内切换 `segment_definition.active_run_id`。
- 查询当前成员时按 `active_run_id` 读取。
- 后台只保留最近 3 个成功批次，过期批次异步清理。

### 审计字段扩展

- 新增 Flyway migration `V2026XXXX__segment_member_audit_extend.sql`，扩展 `segment_member_audit`。
- 新增字段：`tag_schema_hash varchar(64)`、`source_data_watermark timestamp`、`error_message text`。
- 成员计算成功、计算失败、手工导入、静态快照固化都要写入足够审计信息，满足 Phase 7 审计回放。

### Phase 5 验收

- `issue` 对象端到端样例固定为：`system_test_issue_scope + severity_level IN (LEVEL1, LEVEL2) + module IN (草图, 工程图)`，并能计算出真实成员。
- 能分别创建 `issue`、`module`、`merge_request` 三种对象的动态分群，并通过 `issue_module_fact`、`module_metric_day_fact`、`mr_review_metric_fact` 计算真实成员。
- 成员数小于 10,000 时计算时间小于 30 秒。
- 单次成员刷新写入量控制在 100,000 行/分钟以内。
- 动态计算失败只标记对应 `segment_compute_run` 失败，不影响上一次成功成员。
- 标签组 schema 不兼容时跳过计算，并记录 `SCHEMA_INCOMPATIBLE`。
- 至少 20 种 DSL 条件组合有单元测试，覆盖非法字段、非法操作符、非法 raw SQL；三种对象类型各至少 5 个计算用例。

## Phase 6 实施计划

预计工作日：8-12 个工作日。

阻塞依赖：Phase 3 API 稳定；Phase 4 动态标签组选项可用；Phase 5 动态分群计算已支持 `issue`、`module`、`merge_request` 三种对象；权限矩阵已确认。

可并行任务：系统设置页面、业务页面入口、导出参数追溯可以并行；公共 API client 和类型定义必须先统一。

Phase 6 目标是前端管理页和业务页面集成，让用户能管理分群、查看标签组选项、从业务页面保存分群或快照，并在导出中保留分群参数。

### 系统设置页面

- 新增 `segment-management` 页面：路径 `/system-settings/segments`，ADMIN 可创建、禁用、刷新，APPROVAL 只读。
- 新增 `semantic-tag-group-management` 页面：路径 `/system-settings/semantic-tag-groups`，ADMIN 可维护展示属性，业务含义只读。
- 新增 `segment-snapshot-list` 页面：路径 `/system-settings/segment-snapshots`，ADMIN 可作废，APPROVAL 可查看和导出。
- 所有页面必须有 loading、失败提示、权限禁用态和刷新水位提示。

### 业务页面集成

- 系统测试缺陷汇总、客户问题记录、议题查询、评审数据管理等页面增加“从分群加载”入口。
- 系统测试缺陷汇总和记录页增加“保存为分群 / 保存为快照”入口。
- 系统测试缺陷汇总、客户问题记录和议题查询页增加“保存常用方案 / 应用常用方案”入口，保存用户常用筛选条件，不预计算成员。
- 页面筛选条件保存为临时 DSL hash，生成快照时保留当时条件和成员。
- 导出参数必须记录 `segment_id`、`snapshot_id`、`preset_id` 或临时 DSL hash。
- 常用筛选方案可以继续保存为动态分群；此时必须经过 Phase 5 成本估算和 schema 兼容性检查。

### 常用方案 API

- 新增 Flyway migration `V2026XXXX__segment_filter_preset.sql`，创建 `segment_filter_preset` 表。
- 新增索引：`segment_filter_preset(owner_user_id, scenario_key, updated_at)`、`segment_filter_preset(visibility, scenario_key, updated_at)`、`segment_filter_preset(dsl_hash)`。
- 新增 `GET /api/segment-filter-presets`，按 `entityType`、`scenarioKey`、`visibility` 查询当前用户可用方案。
- 新增 `POST /api/segment-filter-presets`，保存页面筛选条件为常用方案，写入 `dsl_json`、`dsl_hash`、`tag_schema_hash` 和保存时水位。
- 新增 `PATCH /api/segment-filter-presets/{id}`，重命名、修改可见范围或用当前筛选覆盖方案。
- 新增 `DELETE /api/segment-filter-presets/{id}`，删除个人方案；团队/公开方案删除必须校验权限。
- 新增 `POST /api/segment-filter-presets/{id}/apply`，返回规范化 DSL 和 schema 兼容性结果，由前端应用到当前页面查询。

### 前端约束

- 前端只展示业务对象、业务场景、属性、规则状态和指标，不展示底层 SQL。
- 前端不得自行写死静态标签组选项，必须读取后端语义标签组 API。
- 前端路由 query 只能保存稳定 ID、schemaHash 和用户选择，不保存旧 `tagSelections`。
- 标签筛选控件按选项数量选择展示形态：不超过 12 个值优先平铺；13-50 个值使用可搜索平铺面板；超过 50 个值使用搜索、分组、虚拟滚动和已选摘要。
- 常用筛选方案保存的是受控 DSL、`dsl_hash`、`tag_schema_hash` 和可见范围；加载时若 schema 不兼容，必须提示用户复核。

### 静态标签组补齐

- Phase 6 根据实际接入页面补齐不少于 7 个静态标签组，使全量静态标签组总数达到至少 15 个。
- 候选组包括 `issue_state`、`testing_phase`、`module_source_type`、`response_sla_status`、`resolution_sla_status`、`mr_merge_state`、`review_problem_status`。
- 每个新增组必须先在本文件或 `docs/platform-page-business-rules.md` 中定义业务含义、`value_key` 列表、排序、禁用策略和 `rule_doc_hash`，再进入初始化 migration 或受控同步器。
- `testing_phase` 保持 `STATIC`，只表达固定阶段分类；Phase 4 的 `testing_phase_dynamic` 保持 `DYNAMIC`，表达事实表中实际出现的版本细分阶段，二者不得共用 `group_key`。

### Phase 6 验收

- 分群列表页加载时间小于 2 秒。
- 用户能从系统测试缺陷汇总保存一个动态分群，并在分群管理页看到它。
- 用户能保存一个常用筛选方案，刷新页面后重新应用，并继续微调后另存为新方案。
- 标签值较多时页面以平铺/可搜索面板展示选项，不把所有选项塞进单个长下拉框。
- 用户能在模块统计和 MR 横向对比相关页面加载对应对象分群。
- 用户能把一个动态分群固化为静态快照，并导出快照成员。
- 全量静态标签组总数达到至少 15 个，且新增组均有 `value_key` 列表和规则文档 hash。
- 导出文件和导出审计能追溯到 `segment_id`、`snapshot_id`、`preset_id` 或临时 DSL hash。
- 前端类型检查、路由测试和关键页面 smoke 测试通过。

## Phase 7 实施计划

预计工作日：5-8 个工作日。

阻塞依赖：Phase 2-6 功能基本完成；staging 有足够数据可做性能基线和抽样核对；业务方能参与验收。

可并行任务：规则 drift CI 集成、权限审计、性能基线、发布说明可并行；发布门禁必须最后统一收敛。

Phase 7 目标是把语义层和分群能力运营化，完成规则文档 drift CI 集成、权限审计、性能基线、质量回归和发布门禁。

### 规则文档闭环

- 将 Phase 3 已落地的 `scripts/check_semantic_tag_rule_drift.py` 纳入 CI 和 `verify-local.ps1`。
- 静态标签组业务含义变化必须先更新 `docs/platform-page-business-rules.md`，再更新语义注册表和测试。
- Phase 7 只补 CI 集成、性能基线和发布门禁，不再把 drift 脚本本身作为首次交付物。

### 权限与审计

- 创建、禁用、刷新、固化快照、导出分群成员都写入操作审计。
- ADMIN 可管理，APPROVAL 可查看和导出，普通用户权限按现有页面规则收敛。
- 审计记录包含操作者、操作类型、对象 ID、规则 hash、`tag_schema_hash`、数据水位和失败原因；字段由 Phase 5 的 `V2026XXXX__segment_member_audit_extend.sql` 提供。

### 性能与质量

- 建立动态标签组刷新、动态分群计算、分群列表页、快照导出的性能基线。
- 对 50 个客户问题 SLA 议题做人工抽查，准确率必须 100%。
- 系统测试排除规则与现有页面结果一致。
- 模块拆分统计总数与原逻辑误差小于 1%。
- GMT 到北京时间转换字段和测试无遗漏。

### 发布门禁

- `verify-local.ps1` 覆盖后端编译、关键单测、前端 typecheck、schema/Flyway 漂移、API contract drift、规则文档 drift。
- staging 必须完成动态标签组刷新、动态分群计算、静态快照导出、权限禁用态和审计回放验收。
- 生产发布前保留回滚方案：运行代码回滚不删除 `semantic_tag_*` 和 `segment_*` 表；成员计算任务可暂停；旧历史 Flyway 不修改。

### Phase 7 验收

- CI 能阻止规则文档和静态标签组定义漂移。
- 所有分群关键操作都有审计记录。
- 性能指标达到总体验收标准。
- 权限矩阵在系统设置页和业务页面均生效。
- 发布说明明确本阶段能力、限制、回滚步骤和后续待办。

## 迁移策略

1. 旧业务筛选方案停止演进。
2. 新文档、新计划、新接口命名使用 `segment`、`cohort` 或 `object segmentation`，不再使用旧筛选概念命名。
3. 当前旧 `tag_group` / `tag_value` / `tag_value_mapping` 运行时代码和最终 schema 已删除；历史迁移文件只作为 Flyway 历史保留，不作为新功能兼容层。
4. 静态标签组优先从 `docs/platform-page-business-rules.md` 中固化，动态标签组优先从 fact/汇总表生成，二者都通过同一 API 返回。
5. 已经进入数据库历史的 Flyway 迁移文件不直接改名；后续通过新增迁移引入 `semantic_tag_*` 和 `segment_*` 表，并逐步废弃旧表。
6. 运行代码不得重新引入旧 `TagGroup`、`TagSelection`、`tagSelections`、`tag-groups` 命名；新增功能统一使用 `semantic_tag_*` 和 `segment_*` 命名。
7. 老方案文档和本地 demo 脚本从仓库删除，避免未来误用。

## 验收标准

- 新方案文档不使用旧筛选方案作为产品概念。
- 用户 UI 只展示业务对象、业务场景、属性、规则状态和指标。
- 后端只接受受控规则 DSL，不接受用户 SQL。
- 标签组方案同时覆盖动态标签组和静态标签组；静态标签组不能只写在前端枚举中。
- 严重程度和紧急程度两套标签组明确分离，不能把一级/二级/三级缺陷与 P1/P2/P3 混用。
- 延期原因、客户问题闭环状态、非法类型、缺陷原因标准项等静态标签组与平台规则一致。
- 动态标签组能记录来源水位、`schema_hash` 和生成批次，对外 API 返回 `schemaHash`。
- 动态分群能预览、保存、刷新和查看当前成员。
- 静态分群能人工选择、导入、从动态结果固化，并保留成员审计。
- 静态快照能从动态分群、静态分群或页面筛选结果固化，并保留成员和生成时间。
- 每个规则结果能追溯到语义口径、数据水位和计算批次。
- Phase 3 初始化至少 10 个预定义语义口径和 8 个已定义静态标签组；Phase 4 初始化 5 个动态标签组；Phase 6 补齐全量至少 15 个静态标签组。
- 首个动态计算上线版本至少支持 `issue`、`module`、`merge_request` 三种对象类型的真实成员计算。
- 支持至少 20 种规则 DSL 条件组合的单元测试。
- 动态分群成员数小于 10,000 时计算时间小于 30 秒。
- 标签组刷新总选项数小于 1,000 时耗时小于 60 秒。
- 单次成员刷新写入量控制在 100,000 行/分钟以内。
- 分群列表页加载时间小于 2 秒。
- 系统测试排除规则与现有页面结果一致。
- 客户问题 SLA 计算人工抽查 50 个议题准确率 100%。
- 模块拆分统计总数与原逻辑误差小于 1%。
- GMT 到北京时间转换字段和测试无遗漏。
- CI 增加规则文档 drift 检查。
- 新增不少于 30 个 `SegmentComputeServiceTest` 用例。
