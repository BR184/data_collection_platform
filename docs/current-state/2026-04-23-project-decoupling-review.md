# 项目解耦审视记录

更新时间：2026-04-23  
适用项目：`D:\projects\data_collection_platform`

## 本次审视范围

本次不是新增业务功能，而是围绕当前整体代码结构，重新评估：

- 哪些模块已经有了可复用地基，但上层还在重复造轮子
- 哪些 service / board / page 已经开始出现职责混杂
- 哪些问题如果现在不收口，后续模块越多越难拆

## 本次确认的结论

### 1. 事实层方向是对的，但读取层还没有真正建立

当前项目已经形成了正确方向：

- `issue_fact`
- `merge_request_fact`
- `IssueScopeProfile`
- `StatisticBoardRegistry`

但在事实上层消费时，仍然存在多套重复实现：

- 系统测试查询
- 客户问题明细
- 客户问题非法数据

这些服务仍然各自维护 `FACT_SQL + mapIssueFact + scope/filter/sort`。

### 2. 后端服务类已经出现明显“大而全”趋势

当前风险最高的类包括：

- `FactBuildService`
- `ReviewDataRecordService`
- `CodeReviewIllegalRecordService`

它们共同特征是同时承担多种职责，已经不适合继续直接堆功能。

### 3. 统计看板已经有注册抽象，但缺少问题事实型共享骨架

`AbstractStatisticBoardService` 解决了入口统一问题，但系统测试和客户问题看板在聚合和规则解释上仍存在明显复制。

### 4. 前端体验优化已经开始沉淀到基底

当前已经具备较正确的方向：

- `PageStateShell`
- `BaseRecordTable`
- 路由懒加载
- 路由 loading 防闪

但页面 controller 层还没有建立起来，因此同类页面依旧会在加载编排上各写一套。

## 已确定的总体方案

本轮后续按以下顺序推进：

1. 先做 `issue_fact` 共享读取层
2. 再拆 `ReviewDataRecordService`
3. 再收系统测试 / 客户问题统计看板公共骨架
4. 再做前端页面 controller 与 API 分域拆分
5. 最后收口路由契约和 feature manifest

## 本轮立即实施项

蓝图已单独记录在：

- [2026-04-23-project-decoupling-blueprint.md](/D:/projects/data_collection_platform/docs/plans/2026-04-23-project-decoupling-blueprint.md)

本轮将直接开始第一阶段的第一刀：

- 新建 `IssueFactRecord`
- 新建 `IssueFactRecordRepository`
- 让系统测试查询、客户问题明细、客户问题非法数据接入新底层

本轮目标是“先把底层统一读取能力做对”，不是追求表面上改了多少类。
