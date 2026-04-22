# 客户问题按功能展示缺陷数量进展

更新时间：2026-04-22

## 1. 本轮目标

补齐 `客户问题 -> 按功能展示缺陷数量` 页面，并继续遵循真实链路：

- ODS GitLab 镜像表
- `FactBuildService` 构建 `issue_fact`
- 统计 board 消费 `issue_fact`
- 前端复用 `StatisticBoardPage / StatisticBoardView`

## 2. 已完成内容

新增事实层字段：

- `issue_fact.function_name`
- `IssueFact.functionName`
- `IssueFunctionRules`

功能名识别规则：

- 优先支持老平台口径：标题开头 `【功能名】`
- 本地 demo 兼容 ASCII：标题开头 `[Function Name]`

新增统计板：

- board key：`customer-issue-by-function`
- 前端路由：`/customer-issues/issue-by-function`
- 页面类型：统计矩阵页

统计维度：

- 行维度：`模块 / 功能`
- 模块来自 `issue_fact.module_names`
- 功能来自 `issue_fact.function_name`
- 未标记模块进入 `未标记模块`
- 总计行按议题本身统计

统计指标：

- 问题数量
- 已修复/关闭
- 未关闭
- 申请延期
- 响应延期
- 功能占比
- 一级缺陷 / 二级缺陷 / 三级缺陷 / 建议类

## 3. 复用与边界

本轮没有新增自定义表格组件，没有复刻老平台动态透视表实现，而是复用现有统计 board 抽象。

老平台接口返回 `moduleName + functionName + issueCount`，前端再透视成“每个模块两列”。新平台第一版用统一统计矩阵表达同一业务问题，避免为单页重复造轮子。

## 4. Demo 链路

已更新 `scripts/seed-local-customer-issue-demo-data.sql`：

- demo issue 标题加入 `[Drawing Export]` 等功能名前缀
- 数据仍只写 ODS，不直接写 `issue_fact`
- 重建事实后由 `FactBuildService` 提取 `function_name`

注意：Windows PowerShell 直接管道到容器内 `psql` 时会污染中文标签，后续本地灌种子建议使用：

```powershell
docker cp scripts/seed-local-customer-issue-demo-data.sql qa-flex-postgres:/tmp/seed-local-customer-issue-demo-data.sql
docker exec qa-flex-postgres psql -U qaflex -d qaflex -f /tmp/seed-local-customer-issue-demo-data.sql
```

## 5. 验证结果

- 后端编译通过：`mvn -q -DskipTests compile`
- 前端构建通过：`npm run build`，仅保留既有 chunk size warning
- `POST /api/facts/rebuild?scope=issue&full=true` 成功，重建 `396` 条 issue fact
- demo 项目 `projectId=325` 的 5 条客户问题均已生成 `function_name`
- `GET /api/statistic-boards/customer-issue-by-function?projectId=325` 成功返回 5 条功能行 + 总计行
- 下钻 `平台||Platform Login / total` 成功返回 demo 议题 `#1203`

## 6. 下一步

客户问题副模块已经完成一轮补齐。后续更适合做横向回归：

- 确认客户问题所有入口不再是占位页
- 对系统测试、客户问题两个域做接口烟测清单
- 如交接文档后续确认存在独立功能字段来源，再把 `function_name` 的来源从标题提取扩展到对应 ODS 字段
