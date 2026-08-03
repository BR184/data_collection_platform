# ADR-003: CC_PRODUCT 客户成员与响应模板事实

## Status

Accepted

## Date

2026-07-22

## Context

CC_PRODUCT 的客户名称主要位于 GitLab issue description，当前事实构建未读取 description，只能从标题猜测，无法表达一个议题属于多个客户。响应模板中的计划解决时间和计划合并版本分支也未成为可查询事实；现有 `resolve_deadline_at` 是 SLA 截止时间，不能作为模板原值展示。页面记录快照会长期缓存静态结果，因此不能把“当前时间减提交时间”固化入快照。

## Decision

- `ods_gitlab_issues.description` 为客户来源；仅 description 无有效“客户名称”时使用标题末尾 `——客户` 兜底。
- 客户拆分后通过 `issue_customer_name_aliases` 规范化，别名匹配精确值；当前种子为 `新世纪 -> 郑州新世纪`。
- `issue_fact.customer_names` 保存稳定展示投影，`issue_fact_customer_members` 以 `(source_system, source_instance, project_id, issue_id, customer_name)` 保存多对多成员关系。客户筛选使用成员关系 `exists`，不拆分事实记录。
- 最新“问题调研情况说明”响应模板解析为 `planned_resolution_at`、`planned_resolution_text`、`planned_merge_version_branch`。计划解决时间只投影唯一且完整的合法日期；计划合并版本分支是来源文本事实，只折叠空白，不以分支命名规则过滤、改写或补齐。这些字段与 SLA 截止时间独立。
- 客户问题非法模板校验与事实投影职责分离：非法校验只接受一个或多个以 `&` 分隔的 `CCyyyyRn` 成员，但校验失败不能清空 `planned_merge_version_branch`。页面、详情和 Excel 因此能够展示问题原值，非法数据页仍按严格规则报告数据质量问题。
- 滞留时长表示当前尚未闭环缺陷的年龄：GitLab 已关闭或测试状态命中客户问题最终闭环成员时返回 `0`，其余记录由 `created_at_source` 与一次请求固定的 `asOf` 在运行时计算；页面快照只保存静态 `IssueFactRecord`。闭环前历史耗时属于解决周期，不冻结到滞留字段。
- CC_PRODUCT 专属字段只接入该专题的 API、筛选、前端和 Excel；延期专题不复用。

## Alternatives Considered

### 仅从标题匹配客户

拒绝。description 已有结构化客户名称，标题有历史遗留、日期尾缀和多个客户，不能作为主来源。

### 将多个客户写成单个逗号字符串并用模糊匹配筛选

拒绝。无法保证精确成员命中、统计易重复，且不支持可靠候选目录。

### 复用 `resolve_deadline_at`

拒绝。该字段受 SLA 上限和默认值影响，不等于用户填写的计划解决时间。

### 用非法模板校验结果生成计划合并分支事实

拒绝。格式校验用于诊断填写质量，事实投影用于保存最新来源值；以空字符串表达校验失败会破坏可解释性，并导致页面、详情和导出同时丢失原始信息。

### 将滞留时长随页面快照保存

拒绝。快照会过期，页面和导出会显示陈旧时长。

## Consequences

- 事实字段或客户成员规则变更后必须重建 `issue_fact`，成员关系由同一事实写入事务同步。
- 客户别名改动后也必须重建受影响事实，不能只修改查询层。
- 页面与导出共享同一响应投影；导出需要固定一个 `asOf`，保证跨页一致。
- 不符合 `CCyyyyRn` 规则的计划合并分支仍进入事实和展示响应，同时继续命中客户问题非法模板规则；两个结果不能互相替代。
