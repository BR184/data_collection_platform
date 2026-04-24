# CodeReview 阶段一重构设计

更新时间：2026-04-24

## 目标

本阶段只处理 `CodeReview` 非法记录查询链路的结构性技术债，保持以下外部契约不变：

- 后端接口 URL 不变
- 控制器查询参数名不变
- 前端请求参数结构不变
- 页面行为与现有测试预期不变

本阶段的目标是先把后端查询入口和过滤逻辑收口，为后续前端页面拆分与基础表格重构打底。

## 范围

纳入本阶段：

- `CodeReviewController` 主查询接口改为 request object pattern
- `CodeReviewIllegalRecordService` 收口长参数传递
- `CodeReviewIllegalRecordService` 内部过滤组解析逻辑向共享 support 靠拢
- `CodeReview` 相关 controller/service 测试补强

暂不纳入本阶段：

- `CodeReviewIllegalRecordsView.vue` 大组件拆分
- `BaseRecordTable.vue` 拆分
- 全局样式 `styles.css` 清理
- 接口参数改名或响应结构调整

## 方案选择

### 方案 A：只在 service 内部加一个私有参数对象

优点：

- 改动最小
- controller 不动

缺点：

- 控制器长参数问题仍保留
- 无法和仓库当前已完成的 request object pattern 对齐

### 方案 B：新增 `CodeReviewIllegalRecordQueryRequest`，controller 仍保留原查询参数绑定，但在边界处组装请求对象

优点：

- 不改 HTTP 契约
- 能消除 controller 到 service 的长参数透传
- 和最近 issue fact 记录查询链路的重构方向一致
- 后续继续抽 controller request binder 或 service 基类时更容易扩展

缺点：

- 需要同步更新 controller/service 测试

### 方案 C：直接把 controller 改成 `@ModelAttribute` 绑定完整查询对象

优点：

- 控制器更简洁

缺点：

- 需要确认 Spring 对现有参数默认值和空值绑定是否完全兼容
- 这一轮改动面偏大，不适合作为稳妥的第一刀

## 采用方案

采用方案 B。

原因：

- 它能在不改外部契约的前提下，先解决最明显的 SRP/KISS 问题。
- 它与仓库近期已完成的 request object pattern 一致，属于顺势收口，不是重新发明结构。

## 设计

### 1. 查询对象

新增 `CodeReviewIllegalRecordQueryRequest`，承载：

- 基础筛选条件
- 分页参数
- 排序参数
- `filterGroupJson`
- `ruleConfigJson`

控制器继续接收原有 `@RequestParam`，但只负责把参数装配为请求对象，再调用 service。

### 2. Service 职责收口

`CodeReviewIllegalRecordService` 保留编排职责，但从“超长参数方法”改为“单一请求对象入口”。

本阶段不强行把 service 拆成多个 public bean，避免改动面过大；但会把内部逻辑至少整理为更清晰的边界：

- 查询参数归一化
- 过滤组解析
- scoped rows 加载
- 规则判定
- 结果映射

### 3. 过滤组支持层

当前仓库里已经存在：

- `IssueFactRecordFilterGroupSupport`
- `StatisticFilterGroupSupport`
- 多个 service 内部重复实现的 `parseFilterGroup / normalizeCondition / matchesFilterGroup`

本阶段不追求一次性统一全仓 DSL 引擎，但会先把 `CodeReviewIllegalRecordService` 中“解析和归一化”部分抽到专用 support，减少 service 膨胀，并让后续与其他模块合并更容易。

优先原则：

- 先减少 `CodeReviewIllegalRecordService` 内部重复
- 不影响现有匹配语义
- 为后续统一 support 留接口和命名空间

### 4. 测试策略

需要补的测试包括：

- `CodeReviewControllerTest`
  - 主查询接口参数绑定
  - `status`
  - `refresh`
- `CodeReviewIllegalRecordServiceTest`
  - request object 入口行为不变
  - 过滤组解析与排序行为不回退

本阶段以“行为不变”为测试目标，不在测试里引入新的交互口径。

## 风险与控制

### 风险 1：request object 组装时漏参数

控制：

- controller test 覆盖主查询参数透传
- service test 校验排序、分页、过滤行为

### 风险 2：过滤组抽取后匹配语义变化

控制：

- 保持原字段白名单和操作符白名单不变
- 保持原日期和数值比较逻辑不变
- 先做局部 support，不做全仓统一替换

### 风险 3：后续前端阶段与本阶段衔接不顺

控制：

- 本阶段只改后端内部结构
- 不提前引入前端依赖的响应结构变化

## 阶段一完成标准

满足以下条件即可视为阶段一完成：

- `CodeReview` 主查询链路改为 request object pattern
- controller 不再直接透传超长参数列表到 service
- `CodeReviewIllegalRecordService` 的过滤组处理不再继续堆积在主 service 中
- 相关 controller/service 测试通过
- 对前端接口契约无破坏
