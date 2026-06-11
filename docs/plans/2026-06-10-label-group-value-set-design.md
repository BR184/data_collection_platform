# 标签组值集合设计

> 文档状态：已确认方案
> 日期：2026-06-10
> 说明：本文是标签组能力的唯一设计入口，包含已确认通识和对应实现方案。旧的对象分群、语义标签组、字段维度绑定标签组方案均不得继续实现。

## 1. 已确认通识

### 1.1 标签组是同类型值集合

标签组不是“某个字段下的一组选项”，而是“某种值类型下的一组可复用值”。

```text
字符串标签组：核心人员
成员：张三、李四、王五
```

应用到 `review_owner` 字段时：

```text
review_owner IN [张三, 李四, 王五]
```

应用到 `issue_assignee` 字段时：

```text
issue_assignee IN [张三, 李四, 王五]
```

字段决定匹配位置，标签组只提供值。

### 1.2 成员只保存值本身

标签组成员不保存字段、角色、页面、来源维度或业务身份前缀。

不保存：

- `review_owner: 张三`
- `issue_assignee: 张三`
- `项目经理: 张三`
- `总监: 张三`
- `module: 草图`

`张三` 就是字符串 `张三`。如果业务上确实有重名人员，由使用者自行用账号、ID、邮箱或更明确的字符串区分。

### 1.3 用户不手动声明值类型

标签组必须有 `valueType`，但用户不需要也不应该手动填写。

静态标签组创建时先是未定型状态。用户可以先从任意候选来源选择值，也可以直接手动输入值。第一个有效成员进入标签组后，系统根据该值推断 `valueType`：

```text
第一个成员 = 张三        -> valueType = STRING
第一个成员 = 2026-06-10  -> valueType = DATE
第一个成员 = 100         -> valueType = NUMBER
```

定型后，界面隐藏或弱化不匹配值类型的候选项。清空全部成员后，草稿可以回到未定型状态。

### 1.4 静态标签组允许自由输入

静态标签组成员可以来自系统候选值，也可以由用户手动输入同值类型的自定义值。

候选值只用于提高录入效率和减少拼写错误，不是成员来源限制。字符串组可以同时包含用户名、用户简介、模块名、状态文本和自定义字符串。

系统不得限制为：

- 只能从某一个字段里选择成员。
- 用户名只能和用户名放在一组。
- 模块名只能和模块名放在一组。
- 成员必须来自数据库已存在值。

### 1.5 标签组类型

标签组按成员来源分为三类：

```text
STATIC：用户手动输入或选择成员，也可以嵌套同 valueType 的静态组
DYNAMIC：系统按规则计算成员
COMPOSITE：由多个同 valueType 标签组结果做 UNION 去重
```

三者最终对业务页面暴露的都是纯值集合。

### 1.6 动态标签组也是值集合

动态规则可以读取字段、加条件、做统计，但最终输出仍然只是同值类型的成员值列表。

```text
动态标签组：最近 30 天活跃处理人
规则：从最近 30 天议题中取 assigneeName 去重
输出：张三、李四、王五
```

这个动态组最终仍是字符串组 `[张三, 李四, 王五]`，不保存 `issue_assignee: 张三`，也不保存“最近 30 天”这个查询条件作为页面筛选条件。

```text
动态组的 valueType = 规则输出值的类型
```

动态组的 `valueType` 同样不由用户声明。优先由规则模板输出定义推断；缺少稳定元信息时，由首次计算出的第一个非空值推断。混合类型输出必须拒绝并提示拆分规则。

### 1.7 组合标签组

如果一个标签组 A 的结果等于其他几个动态标签组和静态标签组结果的去重集合，这种能力应作为“组合标签组”支持。

```text
组合标签组：重点关注人员
组成：
- 动态组：最近 30 天活跃处理人
- 动态组：当前版本延期处理人
- 静态组：管理层关注人员

重点关注人员 =
最近 30 天活跃处理人
UNION 当前版本延期处理人
UNION 管理层关注人员
去重
```

组合标签组最终仍然对外暴露纯值集合：

```text
[张三, 李四, 王五, 赵六]
```

组合标签组的约束：

1. 子组必须是同一个 `valueType`。
2. 第一版只支持 `UNION + 去重`。
3. 支持引用静态组、动态组和其他组合组。
4. 必须做循环引用检测，例如 A 引 B、B 又引 A 时拒绝保存。
5. 展开后的最终成员数量仍受 200 上限约束。
6. 子组更新后，组合组按当前子组结果重新展开。

这不是恢复跨字段筛选方案。组合组只组合标签组结果，不保存字段、操作符或 AND/OR 页面条件。

### 1.8 标签组不是筛选方案

标签组不保存字段、操作符或 AND/OR 关系。

```text
评审负责人 = 张三
AND 项目 = CrownCAD
AND 模块 = 草图
```

这是页面筛选条件组合，不是一个标签组。如果后续需要保存这类组合，应另行设计“筛选方案”或“查询模板”。

### 1.9 废弃边界

不得继续实现或恢复以下旧方向：

- 对象分群。
- 语义标签组。
- 业务标签组。
- 跨字段筛选方案。
- `semantic_tag_*` 运行时模型。
- `segment_*` 运行时模型。
- `TagGroup` / `TagSelection` / `tag-groups` 旧命名。
- 成员带 `dimensionKey` 的标签组模型。
- 独立业务页面 `labelGroupFilters` 参数。

## 2. 具体方案

### 2.1 值类型

第一版支持或预留以下 `valueType`：

| valueType | 说明 | 示例 |
|---|---|---|
| `STRING` | 普通文本值 | 张三、CrownCAD、草图、用户简介片段 |
| `NUMBER` | 数字值 | 1、2、100 |
| `DATE` | 日期或日期时间值 | 2026-06-10 |
| `BOOLEAN` | 布尔值 | true、false |
| `ENUM` | 业务枚举的规范文本值 | P1、一级缺陷、需求如此 |
| `ARRAY` | 数组型值，后续按具体字段确认 | ["张三", "李四"] |

同一个标签组内不混用不同值类型。

### 2.2 数据库模型

`label_groups` 保存组元信息和系统推断出的 `value_type`：

```sql
create table label_groups (
  id bigserial primary key,
  name varchar(100) not null,
  value_type varchar(32),
  group_type varchar(16) not null default 'STATIC',
  description varchar(500),
  enabled boolean not null default true,
  created_by varchar(100),
  created_at timestamptz not null default now(),
  updated_by varchar(100),
  updated_at timestamptz not null default now(),
  constraint ck_label_groups_type check (group_type in ('STATIC', 'DYNAMIC', 'COMPOSITE')),
  constraint uk_label_groups_name unique (name)
);
```

`label_group_members` 只保存成员值，不保存 `dimension_key`：

```sql
create table label_group_members (
  id bigserial primary key,
  group_id bigint not null references label_groups(id) on delete cascade,
  member_value text not null,
  display_name varchar(255),
  sort_order int not null default 0,
  created_at timestamptz not null default now(),
  constraint uk_label_group_members_value unique (group_id, member_value)
);
```

标签组之间的引用单独存储，用于静态组嵌套静态组，以及组合组引用静态/动态/组合子组：

```sql
create table label_group_references (
  id bigserial primary key,
  parent_group_id bigint not null references label_groups(id) on delete cascade,
  child_group_id bigint not null references label_groups(id) on delete restrict,
  sort_order int not null default 0,
  created_at timestamptz not null default now(),
  constraint ck_label_group_references_not_self check (parent_group_id <> child_group_id),
  constraint uk_label_group_references_child unique (parent_group_id, child_group_id)
);
```

保存引用时必须在 service 层校验：

1. 父组和子组 `value_type` 相同；如果父组未定型，引用第一个子组后继承子组 `value_type`。
2. `STATIC` 父组只能引用 `STATIC` 子组。
3. `COMPOSITE` 父组可以引用 `STATIC`、`DYNAMIC`、`COMPOSITE` 子组。
4. `DYNAMIC` 父组不保存子组引用；动态规则可以把标签组作为输入参数，但输出必须物化为值。
5. 不允许形成循环引用。

如果允许保存空标签组，`value_type` 可以暂时为 `null`。如果第一版要求保存时至少一个成员，保存前必须已推断出非空 `value_type`。

动态规则单独建表，不污染静态组：

```sql
create table label_group_dynamic_rules (
  id bigserial primary key,
  group_id bigint not null references label_groups(id) on delete cascade,
  rule_template_key varchar(100) not null,
  rule_params_json text not null,
  output_value_type varchar(32),
  last_status varchar(32),
  last_error varchar(500),
  last_computed_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint uk_label_group_dynamic_rules_group unique (group_id)
);
```

`output_value_type` 是系统字段，只能由规则模板定义或首次有效输出推断后写入；创建/更新请求不得让用户手动填写。

### 2.3 API 方案

创建静态标签组时，请求不要求用户提交 `valueType`。后端根据成员推断并在响应中返回：

```json
{
  "name": "核心人员",
  "groupType": "STATIC",
  "description": "常用人员字符串集合",
  "members": [
    { "value": "张三", "label": "张三" },
    { "value": "李四", "label": "李四" }
  ],
  "childGroupIds": [2]
}
```

创建组合标签组：

```json
{
  "name": "重点关注人员",
  "groupType": "COMPOSITE",
  "description": "多个人员组的并集去重结果",
  "childGroupIds": [10, 11, 12]
}
```

响应需要展示直接成员、直接引用和展开后预览：

```json
{
  "id": 20,
  "name": "重点关注人员",
  "valueType": "STRING",
  "groupType": "COMPOSITE",
  "memberCount": 4,
  "childGroups": [
    { "id": 10, "name": "最近 30 天活跃处理人", "groupType": "DYNAMIC" },
    { "id": 11, "name": "当前版本延期处理人", "groupType": "DYNAMIC" },
    { "id": 12, "name": "管理层关注人员", "groupType": "STATIC" }
  ],
  "expandedPreview": [
    { "value": "张三", "label": "张三" },
    { "value": "李四", "label": "李四" },
    { "value": "王五", "label": "王五" },
    { "value": "赵六", "label": "赵六" }
  ]
}
```

响应：

```json
{
  "id": 1,
  "name": "核心人员",
  "valueType": "STRING",
  "groupType": "STATIC",
  "memberCount": 2,
  "members": [
    { "value": "张三", "label": "张三" },
    { "value": "李四", "label": "李四" }
  ]
}
```

业务页面按当前字段值类型查询可用标签组：

```text
GET /api/label-groups?valueType=STRING&keyword=&enabled=true
```

展开标签组：

```text
POST /api/label-groups/{groupId}/expand
```

请求：

```json
{
  "fieldKey": "reviewOwner",
  "fieldValueType": "STRING",
  "operator": "eq",
  "pageKey": "review-data-home",
  "sourceInstanceId": "default"
}
```

响应：

```json
{
  "groupId": 1,
  "groupName": "核心人员",
  "valueType": "STRING",
  "values": ["张三", "李四"]
}
```

### 2.4 设置页交互

创建静态标签组的最小表单：

1. 标签组名称。
2. 成员值。
3. 备注。

交互规则：

1. 初始不展示必填的值类型选择器。
2. 用户可以从任意候选来源搜索选择，也可以手动输入。
3. 第一个有效成员进入草稿后自动定型。
4. 定型后隐藏或弱化不匹配值类型的候选项。
5. 清空全部成员后可回到未定型状态。
6. 静态组定型后，可以选择同 `valueType` 的静态标签组作为子组。
7. 同一组内成员值和子组展开值去重。
8. 单组展开后成员数量上限 200。

创建组合标签组的最小表单：

1. 标签组名称。
2. 子标签组。
3. 备注。

组合组交互规则：

1. 第一个子组进入草稿后自动继承该子组的 `valueType`。
2. 定型后只展示同 `valueType` 的标签组作为可选子组。
3. 第一版只提供“并集去重”组合方式，不展示交集、差集或复杂表达式。
4. 选择子组后实时展示展开预览和去重后数量。
5. 出现循环引用时阻止保存并提示具体链路。
6. 子组被禁用、删除或动态计算失败时，组合组需要展示异常状态；查询时优先返回业务错误，不静默忽略问题子组。

### 2.5 业务页面应用

业务页面必须通过现有条件筛选组件应用标签组：

```text
字段 / 关系 / 值
```

标签组只出现在“值”控件里，不能新增独立“标签组筛选”区域，不能新增独立 `labelGroupFilters` 请求参数。

提交结构沿用现有 `filterGroup.conditions`：

```json
{
  "fieldKey": "reviewOwner",
  "operator": "eq",
  "valueType": "LABEL_GROUP",
  "labelGroupId": 1,
  "labelGroupName": "核心人员"
}
```

### 2.6 查询语义

标量字段：

```text
eq LABEL_GROUP -> field IN expandedValues
ne LABEL_GROUP -> field NOT IN expandedValues
```

多值字段：

```text
eq LABEL_GROUP -> fieldValues 与 expandedValues 相交
ne LABEL_GROUP -> fieldValues 与 expandedValues 不相交
```

`contains`、`startsWith`、`endsWith` 是否支持标签组值，需要按字段和性能单独确认；第一版不默认开放。

同字段普通值和标签组成员折叠为一个集合时，去重后上限仍为 200。

展开标签组时必须递归展开引用：

```text
STATIC 展开 = 自有成员值 UNION 静态子组展开值
DYNAMIC 展开 = 最近一次规则计算出的成员值
COMPOSITE 展开 = 所有子组展开值 UNION 去重
```

展开过程中必须检测循环引用，并在最终成员数超过 200 时返回业务错误。

### 2.7 等价值

客户问题闭环状态中：

```text
需求如此 == 设计如此
```

当标签组成员包含规范值 `需求如此` 时，查询展开应同时匹配 `需求如此` 和 `设计如此`。

### 2.8 字段矩阵定位

`docs/plans/2026-06-10-label-group-dimension-matrix.yml` 只作为页面字段目录和候选来源登记：

- 记录页面有哪些字段可筛选。
- 记录字段 key、字段中文名、字段值类型。
- 记录候选值从哪里查询。
- 记录多值字段的匹配模式。
- 记录页面是否允许该字段使用标签组。

历史命名 `dimensions` / `dimensionKey` 只能理解为字段目录 key，不能用于标签组成员唯一性或展开过滤。

## 3. 纠偏清单

需要删除或纠正已经实现错的代码：

1. 删除 `label_groups.dimension_key`。
2. 删除 `label_group_members.dimension_key` 或停止把它作为运行时语义使用。
3. 删除创建/更新标签组请求中的组维度和值类型必填。
4. 删除成员请求中的 `dimensionKey` 必填。
5. 删除按当前字段维度过滤标签组成员的展开逻辑。
6. 删除业务页面独立 `labelGroupFilters` 参数。
7. 删除业务页面独立标签组筛选区。
8. 删除“当前字段无兼容成员”的概念，改为“字段值类型与标签组值类型不兼容”。
9. 设置页从“先选标签类型/值类型再选值”改为“先选值，首个有效值自动定型，随后隐藏不匹配候选”。
10. 前后端类型统一为 `valueType` + `members[].value`，其中 `valueType` 由系统推断和返回，不由用户填写。
11. 新增标签组引用模型，支持静态组嵌套静态组，以及组合组引用同 `valueType` 的静态/动态/组合子组。
12. 所有引用保存和展开必须做循环检测、同类型校验、去重和 200 上限校验。

## 4. 验收用例

1. 创建字符串标签组“核心人员”，用户没有手动选择 `valueType`。
2. 选择或输入第一个成员 `张三` 后，系统自动定型为 `STRING`，并隐藏数字、日期等不匹配候选。
3. 清空全部成员后，标签组草稿可以回到未定型状态。
4. 在评审数据管理中选择 `reviewOwner eq 核心人员（标签组）`，结果等价于 `reviewOwner IN [张三, 李四]`。
5. 在系统测试议题查询中选择 `assigneeName eq 核心人员（标签组）`，结果等价于 `assigneeName IN [张三, 李四]`。
6. 同一个字符串标签组可应用到 `moduleName` 字段，系统不会因为成员曾经来自人员候选而拦截。
7. 创建字符串标签组时，可以手动输入数据库当前不存在的字符串。
8. 动态标签组由规则模板输出或首次计算结果推断 `valueType`，用户不手动填写。
9. 静态标签组可以嵌套同 `valueType` 的静态标签组，展开结果为自有成员和子组成员并集去重。
10. 组合标签组可以引用多个同 `valueType` 的静态/动态/组合标签组，展开结果为所有子组结果并集去重。
11. 组合标签组引用链出现循环时保存失败，并提示循环链路。
12. 子组更新后，组合标签组按当前子组结果重新展开。
13. 多值字段 `reviewExpert` 使用字符串标签组时按集合相交匹配。
14. `contains LABEL_GROUP` 第一版不开放时，前端不展示，后端拒绝。
15. 导出文件展示标签组名和导出时展开成员快照。
16. URL 恢复后仍通过 `filterGroup.conditions` 表达标签组条件。
17. 全仓不再出现新实现的 `labelGroupFilters` 独立请求参数。
