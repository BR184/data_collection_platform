# 统一模块字典内部设计

## 背景

`模块管理` 不是老平台正式功能，也不在新平台已确认功能计划中。它不应该作为独立页面暴露。

但系统测试、客户问题、代码走查、评审数据都会使用“模块”作为统计维度。当前模块来源主要来自 GitLab 标签、标题或历史字段，存在别名、历史名称、中文/英文混用和项目差异。

因此“统一模块字典”应作为内部事实层能力，而不是一个系统设置页面。

## 目标

统一模块字典只服务于数据归一化：

- 将源数据里的模块标签、历史名称、别名映射为标准模块名。
- 让 `issue_fact.module_name / primary_module_name / module_names` 更稳定。
- 让 `merge_request_fact.module_name` 更稳定。
- 让统计板继续消费事实字段，不直接依赖字典表。

## 非目标

当前阶段不做：

- 不新增 `系统设置 -> 模块管理` 页面。
- 不开放模块字典 CRUD API。
- 不让业务统计板直接查询模块字典。
- 不把模块字典做成统计结果表或展示型报表。

## 落地形态

已落为内部配置表：

- `module_dictionary`

核心字段：

- `dictionary_domain`：适用域，例如 `COMMON`、`ISSUE`、`MERGE_REQUEST`
- `project_id`：可为空；为空表示全局规则
- `standard_module_name`：标准模块名
- `alias_name`：源数据可能出现的模块名或标签
- `enabled`：是否启用
- `priority`：匹配优先级，数字越大越优先
- `remark`：维护说明

## 数据流

当前链路：

1. GitLab ODS / 本地录入数据进入源层。
2. `FactBuildService` 构建事实层。
3. 构建过程中加载统一模块字典。
4. 按 `domain + project_id + alias` 将源模块归一为标准模块。
5. 写入 `issue_fact` / `merge_request_fact`。
6. 统计板只消费事实字段。

## 脏数据处理策略

当前采用两层保守处理：

1. 字典别名优先：例如在 `module_dictionary` 中配置 `standard_module_name = 草图`、`alias_name = 草图模块`，事实构建时会写入 `草图`。
2. 安全后缀归一：没有命中字典时，会保守去掉 `功能模块`、`业务模块`、`子模块`、`模块` 等通用后缀；例如 `草图模块` 会归一为 `草图`。

这类归一只发生在事实构建阶段，不改 ODS 源数据。

## 维护方式

当前不提供页面和业务 API。需要维护真实别名时，走内部 SQL 或后续迁移脚本。

示例：

```sql
insert into module_dictionary(dictionary_domain, project_id, standard_module_name, alias_name, priority, remark)
values ('COMMON', null, '草图', '草图模块', 100, '内网手工填写常见别名')
on conflict do nothing;
```

## 验收标准

- 没有新增可见菜单页面。
- `模块管理` 已从导航移除。
- 事实重建后，同一模块的历史别名能归一到同一个标准模块名。
- 系统测试、客户问题、代码走查现有统计页不需要改查询逻辑即可受益。
