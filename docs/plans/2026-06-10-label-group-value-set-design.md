# 标签组值集合设计

> 文档状态：已确认方案
> 日期：2026-06-10
> 说明：本文是标签组能力的唯一设计入口，包含已确认通识和对应实现方案。旧的对象分群、语义标签组、字段维度绑定标签组方案均不得继续实现。

> 修订：2026-06-23（业务方最终决定，覆盖本文与之冲突的旧表述）
>
> 本次修订确定四项变更，凡本文其他章节与下列各点冲突的，以本修订为准：
>
> 1. **适用范围默认全开，不对任何字段做显式排除**。所有筛选字段默认都能使用值类型匹配的标签组；不再保留“自由文本字段被锁上”的设计。
> 2. **标签组新增组级「适用范围」配置（二选一）**：选项 A `SAME_FIELD` 仅成员来源字段一致的筛选字段可用；选项 B `SAME_TYPE` 值类型一致即可用，不限字段名。默认 `SAME_TYPE`。这是组级显式配置，不是被废弃的成员级 `dimensionKey`。
> 3. **静态标签组成员允许手动输入任意文本值**，不再强制来自候选来源；同时保留从候选来源选择。用于敏感词、黑名单等自定义场景。原 §1.4、§2.4、§3.7 中“不得手动输入候选外成员”的限制作废。
> 4. **新增标签组关系「局部包含任意标签组」（`partialContainsAny`）**：对字段值做子串匹配，命中标签组任意成员即成立。使标签组可用于标题、备注等自由文本字段，充当傻瓜式敏感词过滤。
>
> 配套交互（承接 2026-06-23 确认）：标签组的接入点从筛选三段式的「值」控件前移到「关系」控件。关系下拉同时列出普通关系与标签组集合关系，两类用**颜色**区分，**不加「标签组/」文字前缀**；仅当选中标签组关系时，「值」控件才允许选择标签组，且值控件内只显示标签组名称，同样不加「标签组/」前缀。

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

静态标签组创建时先是未定型状态。用户可以先从任意候选来源选择值。第一个有效成员进入标签组后，系统根据该值推断 `valueType`：

```text
第一个成员 = 张三        -> valueType = STRING
第一个成员 = 2026-06-10  -> valueType = DATE
第一个成员 = 100         -> valueType = NUMBER
```

定型后，界面隐藏或弱化不匹配值类型的候选项。清空全部成员后，草稿可以回到未定型状态。

### 1.4 静态标签组成员来源（2026-06-23 修订）

静态标签组成员有两个并存来源，用户可任选其一或混用：

1. **从候选来源选择**：系统候选值、数据库事实值或平台登记的稳定枚举。用于提高录入效率、减少拼写错误。
2. **手动输入任意文本值**：允许输入数据库当前不存在的值并保存为成员，用于敏感词列表、常用黑名单等自定义场景。

字符串组可以同时包含用户名、用户简介、模块名、状态文本、手动输入的敏感词等不同业务含义的字符串。

系统不得限制为：

- 只能从某一个字段里选择成员。
- 用户名只能和用户名放在一组。
- 模块名只能和模块名放在一组。
- 成员必须来自候选来源（此前限制已作废，改为允许手动输入）。

系统仍需保证：

- 设置页同时提供“候选选择”和“手动输入”两条录入路径；手动输入仍要做值类型校验和 200 上限校验。
- 成员去重、值类型一致性、`valueType` 自动定型规则不变。

> 注：标签组“能被哪些筛选字段使用”由组级「适用范围」配置控制（见 §1.10），与成员来源无关。从模块来源添加成员，只决定该组是字符串组，不限制它只能用于模块字段（除非该组显式设为 `SAME_FIELD`）。

### 1.5 标签组类型

标签组按成员来源分为三类：

```text
STATIC：用户从候选来源选择成员，也可以嵌套同 valueType 的静态组
DYNAMIC：系统按规则计算成员
COMPOSITE：由多个同 valueType 标签组结果做 UNION 去重
```

三者最终对业务页面暴露的都是纯值集合。

### 1.6 动态标签组也是值集合

动态规则可以读取字段、加条件、跨数据源做逻辑关联、做统计和聚合，但最终输出仍然只是同值类型的成员值列表。

```text
动态标签组：最近 30 天活跃处理人
规则：从最近 30 天议题中取 assigneeName 去重
输出：张三、李四、王五
```

这个动态组最终仍是字符串组 `[张三, 李四, 王五]`，不保存 `issue_assignee: 张三`，也不保存“最近 30 天”这个查询条件作为页面筛选条件。

```text
动态组的 valueType = 规则输出值的类型
```

动态组的 `valueType` 同样不由用户声明。优先由用户选择的输出字段元信息推断；缺少稳定元信息时，由首次计算出的第一个非空值推断。混合类型输出必须拒绝并提示拆分规则。

动态标签组不是开发人员写死一批业务规则模板后让用户下拉选择。平台只提供通用规则构建能力：数据源登记、字段目录、逻辑关联关系、过滤条件、分组聚合、预览执行和权限/性能校验。具体规则由用户自己选择数据源和字段后配置，例如：

```text
动态标签组：过去 3 天活跃评审人
主数据源：评审记录表
输出字段：评审人
条件：评审时间 >= 最近 3 天
去重：是
```

也可以配置跨表聚合规则：

```text
动态标签组：2019 年注册且最近 3 天有提交的用户
数据源 A：用户表
条件 A：账号创建时间在 2019 年
数据源 B：commit 表
条件 B：提交时间 >= 最近 3 天
逻辑关联：用户表.username = commit 表.author_name
输出字段：用户表.username
聚合：按用户分组，统计 commit 数
统计门槛：commit 数 > 0 的用户
```

如果两个数据源之间没有任何稳定、可解释、可验证的逻辑关联字段，系统不能凭空计算“某个用户对应的 commit 数”这类跨表结果，只能分别计算各自结果或要求用户先配置逻辑关联。

#### 1.6.1 不使用数据库外键

本项目遵从阿里巴巴数据库设计规范，动态标签组不使用数据库外键，也不保留任何关于外键的功能：

1. 不在标签组规则模型中保存 foreign key、外键约束名或数据库外键元信息。
2. 不通过数据库外键自动发现可关联表。
3. 不在界面展示“外键关系”配置。
4. 不要求数据库表存在外键才能跨表查询。
5. 不通过外键级联删除、级联更新维护动态规则。

跨表规则只使用“逻辑关联关系”：由平台登记或用户显式配置两个字段之间的等值、包含、映射或规范化匹配关系。逻辑关联必须有清晰的字段、类型、匹配方式、空值处理、重复值处理和预览校验结果。

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

注意区分：本文 §1.10 引入的“适用范围”是**组级别的、用户显式选择的可用性开关**（这个组能被哪些字段使用），不是已废弃的“成员带 `dimensionKey`、按当前字段维度过滤成员”模型。适用范围作用在“整组能不能用在某字段”，不作用在“展开时筛掉哪些成员”；展开结果始终是全组成员，不因字段不同而变化。

### 1.10 标签组适用范围（按值类型默认全开 + 可选同字段限定）

字段能否使用某个标签组，基础规则只有一条：**字段值类型 == 标签组值类型**。在此基础上，每个标签组有一个用户可选的“适用范围”（`applicableScope`），二选一：

```text
SAME_TYPE（默认）：只要筛选字段值类型与本组 valueType 一致即可使用，不限字段名。
                  例：字符串组可用于模块、人名、状态、标题、备注等所有字符串字段。
SAME_FIELD     ：只有与本组登记的来源字段完全一致的筛选字段才能使用。
                  例：来源字段为“模块名称”的组，只能用在“模块名称”字段上。
```

约束与说明：

1. 默认 `SAME_TYPE`，给用户最大复用性；`SAME_FIELD` 仅作精细控制时使用。
2. 适用范围是**字段可用性闸门**，不是成员过滤器：无论哪种范围，展开出来的成员集合都是全组成员，不会因为应用到不同字段而被裁剪。这与已废弃的“成员带 dimensionKey、按字段维度过滤成员”根本不同。
3. `SAME_FIELD` 需要本组登记一个来源字段标识（`sourceFieldKey`，取字段目录 key，例如 `moduleName`）。该 key 仅用于“这个组能用在哪个字段”的匹配，**不写进成员、不参与展开**。
4. 模块维度的同义字段（`模块`/`模块名`/`模块名称`/`moduleName`/`moduleNames`）在字段目录里属于同一维度 key，`SAME_FIELD` 按维度 key 判定一致，不因页面叫法不同而判为不同字段。
5. **任何字段都不被“显式排除”**。自由文本字段（标题、备注等）默认也能用标签组——通过 §2.6 新增的“局部包含任意标签组”关系，对它们做子串敏感词式过滤，是有意义的用法。
6. 适用范围只决定“该不该在这个字段的关系下拉里出现这个标签组”，与值类型匹配是 AND 关系：值类型不匹配的组永远不出现，值类型匹配的组再按 `SAME_TYPE`/`SAME_FIELD` 决定是否出现。

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
  applicable_scope varchar(16) not null default 'SAME_TYPE',
  source_field_key varchar(100),
  description varchar(500),
  enabled boolean not null default true,
  created_by varchar(100),
  created_at timestamptz not null default now(),
  updated_by varchar(100),
  updated_at timestamptz not null default now(),
  constraint ck_label_groups_type check (group_type in ('STATIC', 'DYNAMIC', 'COMPOSITE')),
  constraint ck_label_groups_scope check (applicable_scope in ('SAME_TYPE', 'SAME_FIELD')),
  constraint uk_label_groups_name unique (name)
);
```

`applicable_scope` 见 §1.10：`SAME_TYPE`（默认，按值类型通用）或 `SAME_FIELD`（仅同源字段可用）。`source_field_key` 仅在 `SAME_FIELD` 时有意义，存字段目录 key（按维度归一，如 `moduleName`），用于判定“该组能用在哪个字段”，**不写入成员、不参与展开**。`SAME_TYPE` 时 `source_field_key` 可为 null。

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

动态规则单独建表，不污染静态组。规则配置保存为受控 DSL，不保存 SQL 文本，不保存外键信息：

```sql
create table label_group_dynamic_rules (
  id bigserial primary key,
  group_id bigint not null references label_groups(id) on delete cascade,
  rule_config_json text not null,
  output_value_type varchar(32),
  last_status varchar(32),
  last_error varchar(500),
  last_computed_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint uk_label_group_dynamic_rules_group unique (group_id)
);
```

`rule_config_json` 是后端定义的受控 JSON DSL，表达数据源、输出字段、过滤条件、逻辑关联、分组、聚合、排序、去重和数量限制。它不是用户手写 JSON，而是由前端规则构建器生成并由后端校验。`output_value_type` 是系统字段，只能由输出字段元信息或首次有效输出推断后写入；创建/更新请求不得让用户手动填写。

逻辑关联关系建议单独登记：

```sql
create table label_group_rule_relations (
  id bigserial primary key,
  name varchar(100) not null,
  left_source_key varchar(100) not null,
  left_field_key varchar(100) not null,
  right_source_key varchar(100) not null,
  right_field_key varchar(100) not null,
  match_operator varchar(32) not null,
  normalizer varchar(64),
  enabled boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint uk_label_group_rule_relations unique (
    left_source_key, left_field_key, right_source_key, right_field_key, match_operator
  )
);
```

注意：该表登记的是平台级逻辑关联，不是数据库外键。不得添加外键约束，也不得把数据库外键作为关联来源。

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
3. 适用范围（`SAME_TYPE` / `SAME_FIELD`，默认 `SAME_TYPE`，见 §1.10）。
4. 备注。

交互规则（2026-06-23 修订）：

1. 初始不展示必填的值类型选择器。
2. 成员支持两种添加方式并存：
   - 从候选来源搜索选择（提高录入效率、减少拼写错误）。
   - 手动输入任意文本值（支持敏感词、黑名单等候选来源里没有的自定义值）。
   设置页可对手动输入值做轻量化提示（例如"该值当前不在候选来源中"），但不得拒绝保存。
3. 第一个有效成员进入草稿后自动定型 `valueType`。
4. 定型后弱化（不强制隐藏）不匹配值类型的候选项；手动输入仍受当前 `valueType` 约束（例如已定型 STRING 后不接受把成员强行解析成 DATE/NUMBER 的混类型）。
5. 清空全部成员后可回到未定型状态。
6. 选择 `SAME_FIELD` 适用范围时，需指定该标签组绑定的来源字段 key（`sourceFieldKey`）；选择 `SAME_TYPE` 时不需要。适用范围可在编辑时调整。
7. 静态组定型后，可以选择同 `valueType` 的静态标签组作为子组。
8. 同一组内成员值和子组展开值去重。
9. 单组展开后成员数量上限 200。

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

创建动态标签组的最小表单：

1. 标签组名称。
2. 结果来源。
3. 输出字段。
4. 过滤条件组。
5. 可选逻辑关联。
6. 可选分组、聚合、排序和结果成员上限。
7. 备注。

动态组交互规则：

1. 用户从可用数据源中选择结果来源，例如前端表格对应事实视图、业务事实表或 GitLab 镜像表；页面文案不得使用让人误解的“输出数据源”。
2. 用户选择输出字段，动态标签组最终只输出该字段或聚合后的同类型值。
3. 过滤条件必须复用平台已有条件筛选心智模型：字段 / 关系 / 值，并支持“满足全部 / 满足任意”的条件组。需要复杂表达时，允许像 NocoBase 条件筛选一样继续添加子条件组。
4. 跨数据源时必须选择或配置显式逻辑关联关系；没有逻辑关联时不允许保存跨表结果规则。
5. 逻辑关联使用字段到字段的可解释匹配，例如 `用户表.username = commit 表.author_name`，不是数据库外键。
6. 支持分组聚合，例如按用户分组统计最近 3 天 commit 数，再筛选 `commit 数 > 0`。
7. 保存前必须支持预览计算结果、成员数量、关联命中率、重复/空值处理提示和失败原因。
8. 系统根据输出字段元信息或首次计算结果推断 `valueType`，页面只展示推断结果，不让用户编辑。
9. 规则构建器不得要求用户手写 SQL、JSON、模板 key 或数据库外键名。
10. 后端必须校验数据源白名单、字段白名单、操作符白名单、逻辑关联、聚合限制、最大返回成员数和执行超时。
11. 逻辑关联配置不得出现手动输入字段名的输入框；左数据源、左字段、匹配关系、右数据源、右字段和规范化方式都必须来自后端登记目录或当前数据源字段目录。
12. 过滤条件值控件必须按字段值类型切换：枚举/候选值使用多列候选选择器，数字使用数字输入，日期使用日期控件，文本使用文本输入，多值使用明确的多值输入控件。
    - 文本字段不等于必须手动输入。字段目录中可登记 `candidateMode`：`STATIC` 表示稳定枚举候选，`DISTINCT` 表示从白名单事实表字段查询实际已存在值，`NONE` 表示自由文本。
    - `等于`、`不等于`、`包含任意` 等精确值关系优先使用候选选择器；`包含`、`不包含` 可基于候选补全但允许用户输入文本片段；`为空`、`不为空` 不展示值控件。
    - 候选项可以显示友好名称，但提交到规则 DSL 的必须是事实字段真实存储值，不能提交页面翻译值或临时文案。
13. `输出限制` 这类模糊文案不得继续使用。面向用户只展示“结果成员上限”和“预览条数”两种语义；结果成员上限控制最终动态组最多保留多少成员，预览条数只影响本次预览显示数量。
14. 动态组设置页采用与静态组、组合组一致的普通表单风格，默认只展示成员来源、成员字段、成员条件、结果设置和预览入口。跨源关联、关联数据条件、统计门槛和排序属于高级规则，必须折叠收起；只有用户主动展开或已有配置时才展示。
15. 页面必须实时生成规则摘要，例如“从评审记录中取评审人，条件为评审时间在最近 3 天内，去重后最多保留 200 个成员”，帮助用户确认规则含义。
16. 动态组设置页必须面向业务句子组织，而不是面向数据库查询组织。默认路径是“成员来自哪里 -> 成员值取哪个字段 -> 这些成员满足什么条件 -> 预览确认”。底层仍可生成 `filterGroup`、`relations`、`groupBy`、`aggregations`、`havingGroup`，但界面不得把它们作为第一心智直接平铺给用户。
17. 对“2019 年注册用户在过去 3 天活跃议题”这类规则，界面应引导为：成员来源选择“用户”，成员字段选择“用户标识”，成员条件选择“注册时间在 2019 年”；展开高级规则后，关联数据选择“议题事实”，关联条件选择“议题更新时间最近 3 天”，统计门槛选择“每个成员关联议题数 > 0”。用户不需要先理解 join、having 或 DSL 才能完成配置。
18. 动态组 UI 不得为了表达能力而形成和静态组、组合组割裂的独立配置台；不得使用多层大卡片、编号步骤、技术说明块堆叠。高级能力只作为同一表单下的折叠区存在。
19. 平台内所有下拉选择器的候选项默认采用平铺样式，不区分单选和多选；一行展示多个选项，空间不足时自动换行。多选下拉的已选值必须直接在选择框内完整展示，不使用 `+N` 折叠，也不在选择框下方重复展示已选标签；已选值超过一行时选择框自身增高换行。

### 2.5 业务页面应用

业务页面必须通过现有条件筛选组件应用标签组：

```text
字段 / 关系 / 值
```

不能新增独立“标签组筛选”区域，不能新增独立 `labelGroupFilters` 请求参数。

**标签组的入口在「关系」控件，不在「值」控件（2026-06-23 修订）。** 旧文「标签组只出现在值控件里」作废，改为：

1. 「关系」下拉同时列出两类关系，共存在同一个下拉里：
   - 普通关系（非标签组）：字段原有的 `等于`、`不等于`、`包含`、`为空` 等。
   - 标签组关系（集合 / 子串关系）：`包含任意一个`、`不包含任意一个`、`包含全部`、`不包含全部`、`局部包含任意标签组`。
2. 两类关系**用颜色区分**（标签组关系一种颜色，普通关系另一种），**不加任何文字前缀**。禁止 `标签组/包含任意一个` 这种带前缀写法。
3. 只有当用户在「关系」里选中了一个**标签组关系**时，右边「值」控件才切换为“选择已配置的标签组”；选普通关系时，值控件仍是字段原有的普通值输入 / 候选选择。
4. 「值」控件里展示标签组时**也不加 `标签组/` 前缀**，直接显示标签组名称。
5. 关系下拉里展示哪些标签组关系、以及值控件里能选哪些标签组，受该字段值类型与每个标签组的“适用范围”（见 §1.10）共同约束：仅当 `字段值类型 == 标签组值类型`，且标签组 `applicableScope=SAME_TYPE`（或 `SAME_FIELD` 且 `sourceFieldKey==当前字段`）时，该标签组才可选。

页面默认标签组不在设置页里单独展示“默认应用”表。系统默认标签组就是普通标签组列表中的一条记录，其名称代表作用页面，例如 `系统测试缺陷汇总`；列表中只增加“系统默认”标识。系统默认标签组由迁移预置为空的字符串静态组，用户编辑成员、启用/停用、备注时，直接编辑这条标签组。系统默认标签组不可删除，且名称、类型、适用范围、来源字段属于默认应用身份字段，不允许用户改写；误删或改名不应作为正常维护路径，恢复缺失默认组应由迁移或管理员恢复能力补齐。服务端可以按约定名称、页面 key、字段 key 和默认关系识别它的应用位置，但用户界面不再维护第二套默认应用配置。

标签组关系语义：

```text
包含任意一个（intersects）：字段值集合与标签组展开值集合有交集（精确相等）
不包含任意一个（notIntersects）：字段值集合与标签组展开值集合无交集
包含全部（containsAll）：字段值集合覆盖标签组展开值集合
不包含全部（notContainsAll）：字段值集合未覆盖标签组展开值集合
局部包含任意标签组（partialContainsAny）：字段文本中以子串方式命中标签组任意一个成员（非精确相等）
```

标量字段可以视为只有一个元素的集合。例如 `reviewOwner 包含任意一个 核心人员` 等价于 `reviewOwner IN [张三, 李四]`；多值字段例如 `reviewExpert 包含任意一个 核心人员` 则按字段值集合和标签组集合求交集。`局部包含任意标签组` 用于自由文本字段，例如 `标题 局部包含任意标签组 敏感词组`，把标签组当作傻瓜式敏感词 / 黑名单过滤器。

提交结构沿用现有 `filterGroup.conditions`，`operator` 取 `intersects` / `notIntersects` / `containsAll` / `notContainsAll` / `partialContainsAny`：

```json
{
  "fieldKey": "reviewOwner",
  "operator": "intersects",
  "valueType": "LABEL_GROUP",
  "labelGroupId": 1,
  "labelGroupName": "核心人员"
}
```

### 2.6 查询语义

标量字段：

```text
intersects LABEL_GROUP -> field IN expandedValues
notIntersects LABEL_GROUP -> field NOT IN expandedValues
containsAll LABEL_GROUP -> field 覆盖 expandedValues。标量字段只有一个值，所以只有展开值为空或只包含该字段值时才成立。
notContainsAll LABEL_GROUP -> field 未覆盖 expandedValues
partialContainsAny LABEL_GROUP -> field 文本中以子串命中任意成员，等价于 OR( field LIKE %m1%, field LIKE %m2%, ... )
```

多值字段：

```text
intersects LABEL_GROUP -> fieldValues 与 expandedValues 相交
notIntersects LABEL_GROUP -> fieldValues 与 expandedValues 不相交
containsAll LABEL_GROUP -> fieldValues 覆盖 expandedValues
notContainsAll LABEL_GROUP -> fieldValues 未覆盖 expandedValues
partialContainsAny LABEL_GROUP -> 任一字段值文本以子串命中任意成员
```

普通文本的 `contains`、`startsWith`、`endsWith` 不作为标签组值关系展示；标签组值只开放上面的集合关系与 `partialContainsAny`。

`partialContainsAny`（局部包含任意标签组）的实现与边界（2026-06-23 新增）：

1. 语义是“字段文本里出现了标签组任意一个成员的子串”，用于标题、备注等自由文本字段，让标签组充当敏感词 / 黑名单列表。
2. 后端展开成员后生成 `OR(field LIKE %member%)`，成员需做 LIKE 通配符转义（`%`、`_`、转义符）。
3. **性能边界**：LIKE 子串匹配无法走普通索引，成员越多 OR 链越长。沿用单组展开 200 上限；对超大文本字段应在字段目录侧限制是否开放该关系，必要时走内存过滤。
4. 仅对 `STRING` 值类型字段与 `STRING` 标签组开放；NUMBER/DATE/BOOLEAN/ENUM 不提供 `partialContainsAny`。

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

动态规则构建器需要单独登记数据源元信息，而不是复用字段矩阵：

- 数据源 key：系统内部唯一标识。
- 数据源名称：用户可见名称，例如“评审记录”“GitLab Commit 镜像表”。
- 字段目录：字段 key、中文名、值类型、是否可输出、是否可过滤、是否可聚合。
- 操作符目录：字段可用的过滤、聚合和排序操作。
- 逻辑关联目录：允许跨数据源关联的字段对和匹配方式。
- 权限与范围：哪些角色、页面或源实例可以读取该数据源。
- 性能边界：最大扫描范围、最大预览数量、最大展开成员数和超时限制。

所有关联目录都是逻辑关联目录，不是外键目录。

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
13. 动态组设置页不得暴露 `ruleConfigJson` 文本输入，改为数据源、字段、条件、逻辑关联、聚合和预览的规则构建器。
14. 删除任何关于数据库外键、外键约束名、外键自动发现和外键级联的动态规则设计或实现。
15. （2026-06-23 修订）取消“静态成员必须来自候选来源、不能手动输入”的硬限制：成员可手动输入任意文本值，也可从候选来源选择，两种并存。保存接口不再因“候选来源外成员”而拒绝。
16. （2026-06-23 修订）取消逐字段 `labelGroupEnabled` 开关作为“是否允许用标签组”的闸门：默认所有字段都能使用匹配 `valueType` 的标签组，是否可用改由“字段值类型 + 标签组 `applicable_scope`”决定。字段目录仍可保留 `labelGroupEnabled` 仅作为前端渲染/候选提示，不作为允许与否的判据。
17. （2026-06-23 修订）`label_groups` 新增 `applicable_scope`（`SAME_TYPE` 默认 / `SAME_FIELD`）和 `source_field_key`；`SAME_FIELD` 时校验当前筛选字段 key 等于 `source_field_key`。
18. （2026-06-23 新增）实现 `partialContainsAny` 集合关系（局部包含任意标签组），后端展开为转义后的 `OR(field LIKE %member%)`，仅对 STRING 字段与 STRING 组开放。
19. （2026-06-23 修订）标签组接入点从“值”控件迁到“关系”控件：关系下拉同时含普通关系与标签组集合关系，用颜色区分、不加“标签组/”文字前缀；仅当选中标签组关系，值控件才允许选标签组，且值控件内不加“标签组/”前缀。

## 4. 验收用例

1. 创建字符串标签组“核心人员”，用户没有手动选择 `valueType`。
2. 选择第一个候选成员 `张三` 后，系统自动定型为 `STRING`，并隐藏数字、日期等不匹配候选。
3. 清空全部成员后，标签组草稿可以回到未定型状态。
4. 在评审数据管理中选择 `reviewOwner 包含任意一个 核心人员（标签组）`，结果等价于 `reviewOwner IN [张三, 李四]`。
5. 在系统测试议题查询中选择 `assigneeName 包含任意一个 核心人员（标签组）`，结果等价于 `assigneeName IN [张三, 李四]`。
6. 同一个字符串标签组可应用到 `moduleName` 字段，系统不会因为成员曾经来自人员候选而拦截。
7. （2026-06-23 修订）创建字符串标签组时，用户既可手动输入数据库当前不存在的字符串（如敏感词），也可从候选来源选择；保存接口不因候选外成员而拒绝。
8. 动态标签组由输出字段元信息或首次计算结果推断 `valueType`，用户不手动填写。
9. 静态标签组可以嵌套同 `valueType` 的静态标签组，展开结果为自有成员和子组成员并集去重。
10. 组合标签组可以引用多个同 `valueType` 的静态/动态/组合标签组，展开结果为所有子组结果并集去重。
11. 组合标签组引用链出现循环时保存失败，并提示循环链路。
12. 子组更新后，组合标签组按当前子组结果重新展开。
13. 多值字段 `reviewExpert` 使用字符串标签组的“包含任意一个”时按集合相交匹配，“包含全部”时按集合覆盖匹配。
14. 标签组值模式下前端只展示集合关系（含 `partialContainsAny`），后端拒绝普通 `contains LABEL_GROUP`、`eq LABEL_GROUP` 等旧式关系。
15. 导出文件展示标签组名和导出时展开成员快照。
16. URL 恢复后仍通过 `filterGroup.conditions` 表达标签组条件。
17. 全仓不再出现新实现的 `labelGroupFilters` 独立请求参数。
18. 创建动态标签组时，用户通过数据源、字段、条件、逻辑关联和聚合控件配置规则，看不到 SQL、JSON、模板 key 或外键配置。
19. 用户可以配置“用户表中 2019 年创建账号的用户”与“commit 表中最近 3 天的提交”之间的逻辑关联，例如 `用户表.username = commit 表.author_name`，并按用户统计 commit 数。
20. 如果跨表规则没有任何显式逻辑关联路径，保存失败并提示需要配置可解释关联关系。
21. 系统中不得出现“外键关系”作为动态规则能力；没有数据库外键也可以通过显式逻辑关联支持跨表规则。
22. （2026-06-23 新增）标签组 `applicable_scope=SAME_TYPE` 时可用于任意同 `valueType` 字段；改为 `SAME_FIELD` 后，仅当筛选字段 key 等于 `source_field_key` 才出现在该字段的关系候选中。
23. （2026-06-23 新增）默认 `applicable_scope=SAME_TYPE`；用户在设置页可二选一切换 `SAME_FIELD` / `SAME_TYPE`。
24. （2026-06-23 新增）对“标题”字段配置 `标题 局部包含任意标签组 不友善词组`，命中含任一成员子串的标题；该关系仅对 STRING 字段开放。
25. （2026-06-23 新增）关系下拉中标签组关系与普通关系并存、以颜色区分、不带“标签组/”前缀；未选中标签组关系时值控件不展示标签组、不带前缀文案。
