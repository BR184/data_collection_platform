# 客户问题缺陷响应效率进展

更新时间：2026-04-22

## 1. 本轮目标

补齐 `客户问题 -> 缺陷响应效率` 页面，继续坚持复用现有统计板链路：

- 后端读取 `issue_fact`
- 数据范围复用 `CustomerIssueScopeProfile`
- 前端复用 `StatisticBoardPage / BaseStatisticTable`
- 支持统计、筛选、规则说明和下钻明细

## 2. 已完成内容

新增统计板：

- board key：`customer-issue-response-efficiency`
- 前端路由：`/customer-issues/response-efficiency`
- 页面类型：统计矩阵页

统计维度：

- 行维度：模块
- 未标记模块会归入 `未标记模块`
- 总计行按议题本身统计

统计指标：

- 缺陷总数
- 已响应
- 未响应
- 响应超期
- 响应延期
- 响应率
- 解决延期
- 解决未延期
- 解决延期率

## 3. 数据口径

当前口径直接消费事实层字段：

- `issue_fact.has_response`
- `issue_fact.response_overdue`
- `issue_fact.is_response_delayed`
- `issue_fact.is_resolve_delayed`
- `issue_fact.resolve_sla_days`
- `issue_fact.resolve_deadline_at`

响应效率页面只展示事实层已经归一化出的结果，不在页面层重新推导 SLA。

## 4. 验证结果

- 后端编译：`mvn -q -DskipTests compile` 通过。
- 前端构建：`npm run build` 通过；仅保留既有 chunk size warning。
- `GET /api/statistic-boards/customer-issue-response-efficiency` 成功返回统计板。
- demo 数据总计 `5` 条客户问题：
  - 已响应 `4` 条
  - 未响应 `1` 条
  - 响应超期 `1` 条
  - 响应延期 `1` 条
  - 响应率 `80.00%`
- 下钻 `__total__ / unresponded` 成功返回 demo 议题 `#1203`。

## 5. 下一步建议

客户问题模块剩余未真实化页面：

- `按功能展示缺陷数量`

建议下一步补它。它可以继续复用 `issue_fact + CustomerIssueScopeProfile`，行维度优先按功能/模块标签聚合；如果老平台“功能”口径不是模块标签，则需要再把功能识别规则沉淀到事实层或 profile 辅助规则中。
