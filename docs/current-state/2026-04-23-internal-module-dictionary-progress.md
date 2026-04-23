# 统一模块字典内部化进展

## 本轮目标

按确认后的边界处理：

- 删除 `模块管理` 占位入口。
- 实现统一模块字典，但只作为事实层内部能力。
- 不新增页面，不新增业务 CRUD API，不让统计板直接查询字典。

## 已完成

导航与路由：

- 已从系统设置菜单移除 `模块管理`。
- 已删除 `/system-settings/module-management` 占位路由。

后端内部能力：

- 新增 `module_dictionary` 内部表。
- 新增 `ModuleDictionaryService`。
- `FactBuildService` 构建 `issue_fact` 时会将模块标签归一后写入：
  - `module_name`
  - `primary_module_name`
  - `module_names`
- `FactBuildService` 构建 `merge_request_fact` 时会将 `module_name` 归一后写入。

## 脏数据解决方案

内网当前存在人工填写不一致的问题，例如：

- `草图`
- `草图模块`

处理策略：

- 优先查 `module_dictionary`，命中字典别名则写入标准模块名。
- 未命中字典时，保守去掉常见通用后缀：`功能模块`、`业务模块`、`子模块`、`模块`。
- 不修改 ODS 源数据，只修改事实层归一化结果。

这样后续统计页仍然只消费事实层字段，不需要知道字典存在。

## 后续注意

真实内网接入后，如果发现更多历史别名，优先补 `module_dictionary` 内部数据，不要新增页面。
