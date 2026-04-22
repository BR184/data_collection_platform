# 客户问题记录型专题页进展

更新时间：2026-04-22

## 1. 本轮目标

在客户问题第一批页面已经补齐后，继续补第二批表格型专题页：

- `客户问题 -> CC_PRODUCT 议题`
- `客户问题 -> 延期问题`

本轮仍坚持复用现有抽象：

- 后端只读 `issue_fact`
- 数据范围复用 `CustomerIssueScopeProfile`
- 前端复用 `BaseRecordTable`
- 不新增独立事实来源，也不复制系统测试页面实现

## 2. 已完成内容

新增客户问题通用记录查询接口：

- `GET /api/customer-issues/records?topic=cc-product`
- `GET /api/customer-issues/records?topic=delay`
- `GET /api/customer-issues/records/filter-options?topic=...`
- `GET /api/customer-issues/records/rule-explanation?topic=...`

新增前端共用页面：

- `CustomerIssueRecordsView.vue`
- `/customer-issues/cc-product-issues`
- `/customer-issues/delay-issues`

两个路由共用同一个页面，根据 `pageKey` 自动选择 topic。

## 3. 当前数据口径

`CC_PRODUCT 议题`：

- 先用 `CustomerIssueScopeProfile` 限定客户问题范围
- 不再做额外专题过滤
- 展示客户问题范围内的全部议题

`延期问题`：

- 先用 `CustomerIssueScopeProfile` 限定客户问题范围
- 再保留以下任一命中的记录：
  - `issue_fact.delay_issue = true`
  - `issue_fact.is_response_delayed = true`
  - `issue_fact.is_resolve_delayed = true`

## 4. 验证结果

- 后端编译：`mvn -q -DskipTests compile` 通过。
- 前端构建：`npm run build` 通过；仅保留既有 chunk size warning。
- `topic=cc-product` 接口返回 `5` 条 demo 议题。
- `topic=delay` 接口返回 `2` 条 demo 议题。
- 延期专题当前覆盖：
  - `申请延期`
  - `响应延期`

## 5. 下一步建议

客户问题模块当前剩余未真实化的页面：

- `缺陷响应效率`
- `按功能展示缺陷数量`

建议下一步先做 `缺陷响应效率`，因为 `issue_fact` 已经具备 `has_response / response_overdue / is_response_delayed / resolve_sla_days / resolve_deadline_at / is_resolve_delayed` 等字段，地基相对更完整。
