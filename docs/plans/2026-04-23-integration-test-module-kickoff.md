# 集成测试模块启动说明

更新时间：2026-04-23  
适用项目：`D:\projects\data_collection_platform`

## 1. 为什么从集成测试开始

在当前主线解耦完成后，除以下两类明确后置内容外：

- 各类看板统一实现
- `系统测试非法数据` 正式页面实现

剩余仍处于正式占位状态、且适合作为下一阶段功能补齐入口的模块，基本只剩：

- `集成测试 -> 集成测试数据分析`

因此，后续“功能补齐阶段”的第一刀，建议从集成测试模块开始。

## 2. 老平台功能形态结论

本次只参考老平台功能，不复用老平台代码。通过查看老平台以下文件：

- `webapp/src/views/PageHome/ContentComponents/QuestionnaireInfo/IntegrationTable.vue`
- `webapp/src/views/PageHome/ContentComponents/QuestionnaireInfo/IntegrationTableDetail.vue`
- `webapp/src/request/IntegrationApi.js`
- `src/main/java/com/huayun/controller/IntegrationDataController.java`

可以确认老平台的“集成测试数据分析”不是普通占位页，也不是系统测试/客户问题那类 issue 统计板。

它的核心功能包括：

1. 按测试阶段选择数据范围
2. 展示模块维度汇总表
3. 支持按模块打开详情列表
4. 支持导出全量数据
5. 支持导出横向对比结果

## 3. 老平台页面结构摘要

### 3.1 汇总层

老平台汇总表按模块聚合，核心列大致包括：

- 模块名称
- 执行用例总数
- 通过用例数
- 初始未通过用例数
- 本次未通过用例数
- 问题用例数
- 例外问题数
- 集成测试通过率

### 3.2 详情层

老平台详情弹窗按记录展示，核心字段大致包括：

- 议题编号
- 议题标题
- 模块名称
- 功能
- 执行人
- 执行用例总数
- 通过用例数
- 初始未通过用例数
- 本次未通过用例数
- 问题用例数
- 例外问题数
- 通过率
- 功能标签
- 合法性校验结果

### 3.3 数据语义

老平台不是简单统计 issue 数量，而是从 issue/comment 中解析出一组“集成测试记录字段”，再计算：

- 用例数量
- 通过率
- 合法性
- 模块/功能维度聚合

这说明它本质上是一条独立的数据链路，而不是 issue_fact 的一个简单视图。

## 4. 与新平台现有抽象的关系

## 4.1 不能直接复用 issue_fact 统计板抽象

当前新平台的系统测试、客户问题统计板，主要围绕：

- `issue_fact`
- 模块维度 issue 聚合
- 缺陷分类/原因/延期/阶段统计

而集成测试模块关注的是：

- 集成测试记录本身
- 用例执行结果
- 通过率
- 功能/模块维度的测试执行统计

两者语义不同，因此不应强行塞进现有 issue-fact statistic board。

## 4.2 也不能直接套当前记录页抽象

当前 `BaseRecordTable + useRecordPageController` 更适合：

- 条件筛选型记录列表
- 服务端分页
- 关键字搜索
- 条件标签回显

而集成测试首页更接近：

- 顶部阶段筛选
- 模块维度汇总表
- 点击模块看详情

也就是说，它不是“纯记录列表页”，而是“汇总页 + 详情页”的复合页面。

## 4.3 可以复用的底座

虽然不能强套现有两类抽象，但以下底座仍然可以直接复用：

- `feature-manifest.ts`
- 全局 shell / loading / route 契约体系
- `PageStateShell`
- 通用请求层 `api-client/*`
- 条件/分页/排序 DTO 设计方式
- 详情列表页若单独拆出，可部分复用 `BaseRecordTable`

## 5. 当前新平台的真实现状

截至 2026-04-23，新平台中：

- 前端只有 `integration-test-home` 占位路由
- 后端没有任何集成测试专用 controller / service / repository
- `schema.sql` 中没有集成测试事实表或明细表
- 当前平台也没有“集成测试通过率”相关 API 或 DTO

结论：

当前缺的不是“页面壳子”，而是整条数据链路。

## 6. 推荐实现顺序

为了保持“统一但是不耦合”，集成测试模块建议按下面顺序推进。

### 阶段 A：先补数据链路

先明确集成测试数据的本地承载方式，建议新建独立事实层，而不是混入 `issue_fact`：

候选命名：

- `integration_test_fact`
- 或 `integration_test_record`

建议至少承载：

- issue / issue_iid / issuable_reference
- testing_phase
- module_name
- function_name
- executor
- execute_case
- pass_case
- not_pass_case
- not_pass_case_now
- problem_case
- exception_count
- pass_rate
- legal
- function_labels
- source payload / source note id / updated_at

### 阶段 B：再补后端查询接口

后端建议先实现 MVP 三件套：

1. 测试阶段下拉接口
2. 模块汇总列表接口
3. 模块详情分页接口

导出接口可以后补，不必与首版页面强绑定。

### 阶段 C：最后补前端页面

前端首版建议形态：

1. 顶部测试阶段筛选
2. 主表展示模块维度汇总
3. 点击模块打开详情抽屉或详情弹窗

不建议首版直接复刻老平台的全部导出和横向对比能力。

## 7. 首版 MVP 建议边界

首版只做：

- 测试阶段选择
- 模块汇总表
- 模块详情列表
- 合法性字段展示

首版不做：

- 横向对比导出
- 多 Sheet 导出
- 定时采集任务页面化
- 与个人质量页联动

## 8. 一句话判断

集成测试模块不是现有 issue_fact 统计页的简单复用项，而是下一条需要单独建设数据事实链路的业务模块。
