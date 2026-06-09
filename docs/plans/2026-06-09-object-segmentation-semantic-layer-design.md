# 动态/静态对象分群与语义层设计

日期：2026-06-09

## 结论

旧的业务筛选方案废弃。后续不再把业务能力设计成标签值映射、前端条件集合或任意 SQL 条件集合。

新方案定义为“对象分群”：用户面向业务对象创建动态分群或静态快照，系统在后台基于统一语义口径、受控规则 DSL 和预聚合事实表计算成员。用户仍然看到“草图、工程图、一级缺陷、P1、用户名、项目名、里程碑”等熟悉选项，但这些选项来自语义层，不再直接等同于数据库标签或 SQL 片段。

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

这些规则不能交给每个动态条件重复配置，也不能让业务方手写跨表 SQL。

## 目标

1. 支持动态分群：成员随镜像同步、fact 构建和规则刷新自动变化。
2. 支持静态快照：在某个时间点固定保存成员，用于留痕、导出、复盘和执行。
3. 将复杂跨表关系、模板解析、去重规则、时间窗和异常口径集中到语义层。
4. 用户只配置受控条件，不接触 SQL、join、group by 或 `ods_gitlab_*` 表。
5. 高频规则跑在事实表或汇总表上，避免每次动态分群都扫明细镜像表。

## 非目标

- 不做任意 SQL 编辑器。
- 不延续旧业务筛选方案作为产品概念。
- 不让前端直接拼接跨表查询条件。
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

### 动态分群

动态分群保存规则定义和当前成员缓存。每次计算后覆盖当前成员，并保留计算记录。

### 静态快照

静态快照保存某次计算或人工选择后的固定成员。快照成员不会随源数据变化而变化。

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

## 用户界面

用户看到的仍是业务熟悉的条件，而不是底层表结构。

创建动态分群时，推荐流程：

```text
分群对象 -> 业务场景 -> 规则条件 -> 刷新方式 -> 预览成员 -> 保存动态分群 / 固化静态快照
```

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
  - `computed_run_id`
  - `computed_at`

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
  -> 动态分群计算
  -> 当前成员缓存
  -> 可选固化静态快照
```

动态分群计算可以复用现有后台任务与运行状态思路，但应与 GitLab 镜像同步运行单元解耦。

## 示例规则映射

### 系统测试缺陷汇总

- 对象：`issue` 或 `module`
- 范围：`system_test_issue_scope`
- 排除：`system_test_exclusion_policy`
- 指标：一级/二级/三级缺陷数
- 特殊规则：总计按议题去重；模块展示按模块拆分。

### 系统测试非法数据

- 对象：`issue`
- 范围：`system_test_issue_scope`
- 条件：`isIllegal = true`
- 维度：非法类型、模块、项目、阶段。

### 缺陷原因分析

- 对象：`issue_reason`
- 范围：`system_test_issue_scope`
- 口径：`defect_reason_policy`
- 指标：缺陷原因个数，不按议题个数。

### 客户问题响应延期

- 对象：`issue`
- 范围：`customer_open_issue_scope`
- 条件：`isResponseOverdue = true`
- 规则：P1 24 小时、P2 48 小时、P3 72 小时；未设定按 P3。

### 横向对比

- 对象：`module`
- 场景：系统测试横向对比
- 组合指标：评审缺陷密度、代码走查缺陷密度、系统测试缺陷数、缺陷原因数。
- 特例：`CC2025R1` 评审数据映射到 `CC2025R1&R2`。

## 迁移策略

1. 旧业务筛选方案停止演进。
2. 新文档、新计划、新接口命名使用 `segment`、`cohort` 或 `object segmentation`，不再使用旧筛选概念命名。
3. 已经进入数据库历史的 Flyway 迁移文件不直接改名；后续通过新增迁移引入 `segment_*` 表，并逐步废弃旧表。
4. 运行代码中的旧组件和 API 不在文档清理阶段半拆；等实现迁移时统一改名、改接口、改测试。
5. 老方案文档和本地 demo 脚本从仓库删除，避免未来误用。

## 验收标准

- 新方案文档不使用旧筛选方案作为产品概念。
- 用户 UI 只展示业务对象、业务场景、属性、规则状态和指标。
- 后端只接受受控规则 DSL，不接受用户 SQL。
- 动态分群能预览、保存、刷新和查看当前成员。
- 静态快照能从动态结果固化，并保留成员和生成时间。
- 每个规则结果能追溯到语义口径、数据水位和计算批次。
