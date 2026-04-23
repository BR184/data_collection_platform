# 项目整体解耦蓝图

更新时间：2026-04-23  
适用项目：`D:\projects\data_collection_platform`

## 1. 目标

本蓝图面向当前项目已经进入“模块逐步补齐、功能逐步复用”的阶段性现实，目标不是推翻重做，而是在保持现有可用功能的前提下，把后续维护成本最高、最容易继续扩散耦合的部分先收口。

本轮解耦目标同时满足四个原则：

- 统一的是机制，不是把所有模块揉成一个大类
- 优先沉淀地基能力，而不是继续在页面层复制逻辑
- 保持真实链路可继续使用，不引入只服务 demo 的临时结构
- 改造路径必须支持分阶段迁移，避免大面积回归风险

## 2. 当前核心问题

### 2.1 服务类职责过重

当前后端存在多类“大而全”服务：

- `FactBuildService` 同时承担源数据抽取、字段归一、模块字典接入、事实构建编排
- `ReviewDataRecordService` 同时承担查询、筛选、汇总、主记录写入、问题项写入、SQL 拼装
- `CodeReviewIllegalRecordService` 同时承担列表查询、规则预览、规则说明、实时刷新

这类服务的共同问题是：

- 一处改动容易跨越多种职责
- 复用时只能复制代码，难以组合
- 测试边界不清晰
- 规则迭代和体验优化经常互相牵连

### 2.2 `issue_fact` 上层消费重复实现

目前系统测试查询、客户问题明细、客户问题非法数据都各自维护一套：

- `FACT_SQL`
- `mapIssueFact`
- `scope/filter/sort/page`
- `filter options`
- `rule explanation`

这说明事实层虽然已经存在，但“面向应用的共享读取层”还没有建立起来。

### 2.3 统计看板抽象层过薄

`AbstractStatisticBoardService` 解决了看板注册和统一入口问题，但没有继续往下抽出“问题事实型看板”的公共骨架，导致系统测试和客户问题看板在：

- 取数
- 规则流解释
- 模块维度聚合
- 详情下钻
- 实时刷新

这些部分出现大面积平行复制。

### 2.4 前端页面基底已有雏形，但数据编排仍分散

当前前端已经有：

- `BaseRecordTable`
- `BaseStatisticTable`
- `PageStateShell`
- `useRouteTableState`

这些基底方向是对的，但具体页面仍然各自控制：

- 何时拉筛选项
- 何时拉表格
- 何时拉规则说明
- 何时展示骨架

因此体验优化和功能演进经常需要逐页修补。

### 2.5 契约配置分散

前端 `api.ts` 和 `router.ts` 已经分别承担了大量跨模块契约拼接：

- 请求 query 组装
- 路由 query 白名单
- 页面 key 与 board key 之间的隐式映射

当前还能维护，但继续增长后会变成新的高耦合点。

## 3. 目标架构

## 3.1 后端分层

后端按以下五层收敛：

### A. Source / Mirror Layer

职责：

- 读取 ODS / mirror 原始数据
- 不承载业务范围定义

保留现有：

- `GitlabMirror*`
- `DatabaseBrowser*`

### B. Fact Build Layer

职责：

- 将源数据归一化写入 `issue_fact`、`merge_request_fact`
- 只负责字段语义沉淀，不负责页面展示

目标拆分：

- `FactBuildOrchestrator`
- `IssueFactBuildService`
- `MergeRequestFactBuildService`
- `IssueTemplateParsingService`
- `IssueFactNormalizationService`

### C. Fact Read Layer

职责：

- 提供面向应用的统一事实读取接口
- 承担统一映射、基础过滤、共享字段模型

目标新增：

- `IssueFactRecord`
- `IssueFactRecordRepository`
- `MergeRequestFactRecordRepository`

这是本轮第一优先级的“新地基”。

### D. Domain Profile / Application Layer

职责：

- 负责业务范围定义和业务语义组装
- 不直接散落 SQL 和字段映射

目标结构：

- `SystemTestScopeProfile`
- `CustomerIssueScopeProfile`
- `IssueRecordApplicationService`
- `CustomerIssueRecordApplicationService`
- `SystemTestIssueSearchApplicationService`
- `CustomerIssueIllegalRecordApplicationService`

说明：

- 这里优先使用组合，不再继续堆继承树
- profile 只负责“是否属于某业务域”
- application service 只负责“如何消费事实并输出当前模块结果”

### E. Board / Query Facade Layer

职责：

- 面向 controller 输出列表页、统计页、规则说明页所需结构
- 只组合下层能力，不再自己重造事实读取链路

目标收敛：

- 问题事实型统计看板基类
- 问题事实型记录页 facade

## 3.2 前端分层

前端按以下四层收敛：

### A. Shell Layer

职责：

- 全局壳子、路由切换体验、稳定布局

保留并继续深化：

- `App.vue`
- `router-state.ts`
- `PageStateShell.vue`

### B. Base Component Layer

职责：

- 通用表格、通用统计表、通用筛选器、通用骨架

保留并继续深化：

- `BaseRecordTable`
- `BaseStatisticTable`
- `StatisticFilterBuilder`

### C. Page Controller Composable Layer

职责：

- 统一页面数据编排，不混入展示组件

目标新增：

- `useRecordPageController`
- `useIssueRecordPage`
- `useRuleExplanationPanel`
- `useFilterOptionsLoader`

### D. Feature Page Layer

职责：

- 只定义字段、页面文案和特殊交互
- 不直接管理复杂加载时序

## 4. 迁移原则

### 4.1 不做一次性大迁移

每次只动一条共享链路，完成后立刻接入 1 到 3 个真实模块验证。

### 4.2 先建新层，再迁旧层

不直接在旧服务上硬改为“半抽象半业务”的状态。  
正确顺序：

1. 先补新抽象
2. 再让旧服务改为调用新抽象
3. 最后删除冗余逻辑

### 4.3 优先抽组合能力，不优先抽继承层级

这个项目模块类型很多，但稳定重复的是“读取事实、按范围过滤、按页面输出”。  
因此优先选择：

- repository
- profile
- support
- application service

而不是优先制造多个深层抽象父类。

### 4.4 用户体验改造跟着基底走

体验优化不再逐页打补丁，而是在：

- 页面状态壳
- 表格 loading 策略
- 过滤项与表格分层加载

这些基底层统一推进。

## 5. 分阶段实施方案

## 阶段一：事实读取层解耦

目标：

- 为 `issue_fact` 建立共享读取模型和 repository
- 收掉系统测试查询、客户问题明细、客户问题非法数据中的重复 `FACT_SQL + mapIssueFact`

输出：

- `IssueFactRecord`
- `IssueFactRecordRepository`
- 共享文本/时间/标签转换工具
- 共享 scope 过滤支持

验收：

- 三个服务查询结果保持不变
- 编译通过
- 页面功能不回退

## 阶段二：评审数据服务拆分

目标：

- 拆掉 `ReviewDataRecordService` 中混合的 query / command / summary / option / SQL helper

输出：

- `ReviewDataRecordQueryService`
- `ReviewDataRecordCommandService`
- `ReviewDataSummaryService`
- `ReviewDataFilterOptionService`

验收：

- 评审数据页面功能不变
- service 文件体积明显下降

## 阶段三：问题事实型看板模板化

目标：

- 把系统测试 / 客户问题看板中重复的聚合与说明流抽成共享 support

输出：

- `AbstractIssueFactStatisticBoardService` 或等价组合结构
- 聚合 bucket support
- rule flow snapshot support
- detail drilldown support

验收：

- 至少缺陷汇总、缺陷原因分析完成接入
- 板子定义与口径仍可独立扩展

## 阶段四：前端页面 controller 化

目标：

- 减少各页面自己控制加载节奏
- 让记录页与统计页的体验优化可复用

输出：

- 页面 controller composable
- API 模块拆分
- 记录页首屏/局部 loading 规范统一

## 阶段五：契约与清单收口

目标：

- 把 route meta、query contract、feature key 等收口

输出：

- feature manifest
- API domain module
- 路由 query contract 统一定义

## 6. 本轮直接实施切口

本轮在蓝图确认后，直接落地阶段一中的第一刀：

- 新建 `IssueFactRecord` 共享事实读取模型
- 新建 `IssueFactRecordRepository`
- 将以下服务切换到新底层：
  - `SystemTestIssueSearchService`
  - `CustomerIssueRecordService`
  - `CustomerIssueIllegalRecordService`

这样做的原因：

- 变更范围可控
- 直接命中重复代码最密集的区域
- 能验证“统一但不耦合”的方向是否成立
- 后续统计看板也能直接复用这一层

## 7. 风险与控制

主要风险：

- 字段映射迁移时造成查询口径偏差
- scope 迁移时造成系统测试/客户问题范围误伤
- 页面接口虽然不变，但返回内容细节可能因基础映射变化产生差异

控制方式：

- controller 和 API 不改
- 第一步只替换底层读取，不改变业务筛选口径
- 每迁移一个服务后都先编译验证
- 尽量保持原字段命名和原输出结构

## 8. 结论

当前项目不需要“推翻式重构”，而需要“地基优先、服务瘦身、页面轻编排”的渐进式解耦。

最正确的顺序不是先重写页面，也不是先造更大的抽象父类，而是：

1. 先把事实读取层做成共享能力
2. 再把应用服务改成组合这些能力
3. 最后把前端页面也切到统一 controller 基底

本轮从 `issue_fact` 共享读取层开始，符合当前项目的人力投入、风险控制和长期可维护性目标。
