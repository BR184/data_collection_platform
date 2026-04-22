# 客户问题非法数据与真实链路 demo 进展

更新时间：2026-04-22

## 1. 本轮边界

本轮没有新增一键删除或清洗功能；前面误起步的本地清理脚本已经移除。后续仍按“测试数据可整体清理，但功能链路不内置危险清理入口”的边界推进。

## 2. 真实链路验证

- 已将 `scripts/seed-local-customer-issue-demo-data.sql` 写入本地 PostgreSQL ODS 表，而不是直接写 `issue_fact`。
- 已重启后端并执行 `POST /api/facts/rebuild?scope=issue&full=true`。
- `issue_fact` 当前合计 `396` 条，其中 `project_id = 325 / CC_Product` 有 `5` 条客户问题事实。
- 客户问题 `5` 条事实中，`milestone_title` 已全部回填，`reason_category` 已回填 `4` 条。
- `GET /api/statistic-boards/customer-issue-defect-summary` 已返回客户问题缺陷汇总数据，总计 `5` 条。
- `GET /api/statistic-boards/customer-issue-defect-cause` 已返回客户问题缺陷原因分析数据，总计 `4` 条有归因数据。

## 3. 已补齐页面

客户问题第一批页面当前已经补齐：

- `客户问题 -> 缺陷汇总`
- `客户问题 -> 缺陷原因分析`
- `客户问题 -> 缺陷非法数据`

本轮新增 `客户问题 -> 缺陷非法数据`：

- 后端入口：`GET /api/customer-issues/illegal-records`
- 筛选项入口：`GET /api/customer-issues/illegal-records/filter-options`
- 规则说明入口：`GET /api/customer-issues/illegal-records/rule-explanation`
- 前端路由：`/customer-issues/illegal-records`
- 页面复用 `BaseRecordTable`，不复制系统测试非法数据页面，也不改动系统测试非法数据占位。

## 4. 验证结果

- 后端编译：`mvn -q -DskipTests compile` 通过。
- 前端构建：`npm run build` 通过；仅保留既有 chunk size warning。
- 客户问题非法数据接口返回 `2` 条 demo 结果，原因分别为 `流程越位` 与 `缺失模块`。

## 5. 下一步建议

1. 继续补客户问题第二批表格型专题页：`CC_PRODUCT 议题` 与 `延期问题`。
2. 这两个页面仍优先走 `issue_fact + CustomerIssueScopeProfile + BaseRecordTable`，不要另起事实来源。
3. 等第二批完成后，再做更偏专题统计的 `缺陷响应效率` 与 `按功能展示缺陷数量`。
