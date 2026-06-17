# 客户问题缺陷非法数据新旧平台差异记录

> 记录日期：2026-06-17
> 新平台：`D:\projects\data_collection_platform`
> 老平台：`D:\projects\spidergitdata-dev`
>
> 本文只记录“客户问题 / 缺陷非法数据”一个页面。分页默认值、UI 形态、筛选控件摆放方式不作为问题；会影响数据集合、字段展示、非法类型、筛选或导出的差异才记录。

## 规则来源

- 规则总表：`docs/platform-page-business-rules.md` 第 5.4 节“客户问题缺陷非法数据”。
- 老平台页面：`webapp/src/views/PageStandard/IllegalIssueSearchCCProduct.vue`。
- 老平台查询：`SpiderIssueDataDAOImpl.findIllegalIssue(...)`。
- 老平台非法生成：`IssueServiceImpl.getIllegalList(...)`、`IssueServiceImpl.getInvalidResearchTemplate(...)`、`IssueServiceImpl.getValidResearchTemplate(...)`。
- 老平台非法类型下拉：`DropDownLabelController.getIssueIllegalType(...)`、`IssueIllegalTypeEnum`。

## 老平台基线

### 主表字段

老平台主表可见字段为：

- 议题编号
- 模块名
- 议题标题
- 议题状态
- 严重程度
- 议题处理人
- 非法类型

展开区字段为：

- 议题更新时间
- 议题提交时间
- 模块名
- 功能名
- 议题编号
- 议题标题
- 议题提交人
- 议题处理人
- 议题状态
- 测试状态
- 议题严重程度

### 筛选条件

老平台顶部筛选条件为：

- 里程碑
- 模块名
- 非法类型

### 数据范围和查询口径

- 固定项目：`projectId = 325`。
- 按里程碑筛选时使用 `milestone = 当前里程碑`。
- 客户问题范围限定 `submission_date > 2026-01-01`。
- 继续执行老平台通用议题过滤 `setQueryFilter(query, projectId)`。
- 未选择非法类型时：`illegal_list is not null`。
- 选择非法类型时：`illegal_list like illegalType`。
- 模块筛选：`module_name = moduleName`。

### 非法类型集合

老平台 `IssueIllegalTypeEnum` 基础类型为：

- 未设定严重程度
- 未设定模块
- 未按照模板回复
- 缺陷原因不唯一

当 `projectId = 325` 时，下拉额外追加：

- 未按照要求填写缺陷调研模板

### 客户问题专属调研模板规则

老平台客户问题额外调用 `getInvalidResearchTemplate(...)`：

- 仅当评论中出现 `# 问题调研情况说明`，但没有任何一个合法模板时，追加非法类型“未按照要求填写缺陷调研模板”。
- 模板必须在问题类型中勾选“缺陷”或“需求”且只能勾选一项。
- 必填章节包含：问题原因、修改方案、一级缺陷的修改方案请模块负责人签字确认、计划解决时间、计划合并的版本分支。
- 非一级缺陷跳过“一级缺陷的修改方案请模块负责人签字确认”检查。
- 计划解决时间允许格式近似为 `YYYY年MM月DD日`、`YYYY.MM.DD`、`YYYY,MM,DD`、`YYYY，MM，DD`。

## 已观察的新平台现状

- 页面入口：`frontend/src/views/CustomerIssueIllegalRecordsView.vue`。
- 通用页面骨架：`frontend/src/views/issue-illegal-records/IssueIllegalRecordsPage.vue`。
- 后端服务：`backend/src/main/java/com/data/collection/platform/service/CustomerIssueIllegalRecordService.java`。
- 事实层非法生成：`IssueClassificationRules.illegalReasons(...)`，由 `FactBuildService` 写入 `issue_fact.illegal_reason / illegal_reasons`。

当前工作树中已经存在未提交改动：

- `CustomerIssueIllegalRecordRowResponse` 已补充 `sourceInstance`、`testingPhase`、`functionName`、`delayReason`、`delayCause`。
- `CustomerIssueIllegalRecordService` 已开始把“非法原因”改成“非法类型”，并按 `illegal_reasons` 多值展示/筛选。
- 前端类型 `CustomerIssueIllegalRecordRowResponse` 已同步新增字段。

以下差异按当前工作树实际代码继续记录。

## 待对齐差异

### 1. 主表字段已按老平台收敛

**影响范围：中。影响用户核对同一条非法数据时看到的字段集合。**

老平台主表只展示 7 个核心字段：议题编号、模块名、议题标题、议题状态、严重程度、议题处理人、非法类型。

新平台 `CustomerIssueIllegalRecordsView.vue` 主表已收敛为：

- 议题编号
- 模块名
- 议题标题
- 议题状态
- 严重程度
- 议题处理人
- 非法类型

主表里不应再回到老平台没有的 `所属项目`、`优先级`、`里程碑`、`创建人`、`更新时间`。

### 2. 展开/详情字段已补齐老平台字段并统一命名

**影响范围：中。影响用户核对非法来源。**

老平台展开区包含 `议题提交时间`、`功能名`、`测试状态` 等字段。

新平台通用详情抽屉已调整为：

- `创建时间` 已切换为 `议题提交时间`。
- `更新时间` 已切换为 `议题更新时间`。
- `缺陷状态` 已切换为 `测试状态`。
- `功能名` 保持条件展示，字段来自事实层。
- 详情中展示 `议题处理人`。

### 3. 条件筛选字段命名已改为“非法类型”

**影响范围：中。影响筛选含义一致性。**

老平台筛选项叫“非法类型”，规则总表也使用“非法类型”。

新平台 `buildCustomerIssueIllegalConditionFields(...)` 当前插入字段：

- `illegalReason`，标签为 `非法原因`。

需要改为：

- 字段 key 可继续使用 `illegalReason` 作为技术字段，但页面标签应显示 `非法类型`。

这项已按页面层调整并与老平台命名对齐。

### 4. 非法类型下拉来源已按老平台固定集合兜底

**影响范围：中到高。影响用户能否筛出老平台支持的非法类型。**

老平台非法类型下拉不是只来自当前结果集，而是固定枚举：

- 未设定严重程度
- 未设定模块
- 未按照模板回复
- 缺陷原因不唯一
- CC_Product 额外：未按照要求填写缺陷调研模板

新平台当前 `getFilterOptions()` 已合并老平台固定非法类型集合与事实层实际命中的类型：

- 未设定严重程度
- 未设定模块
- 未按照模板回复
- 缺陷原因不唯一
- 未按照要求填写缺陷调研模板

### 5. 新平台事实层仍可能生成老平台没有的“流程越位”

**影响范围：高。直接影响非法数据总数和非法类型分布。**

新平台 `IssueClassificationRules.illegalReasons(...)` 当前在以下条件追加 `流程越位`：

- 未关闭。
- 不含 `待合并`、`需求如此`、`建议`、`需求`。
- 不含 `申请延期`。

老平台客户问题缺陷非法数据页的非法类型枚举和页面规则中没有“流程越位”；老平台 `getIllegalList(...)` 相关 `bugStatus` 检查也处于注释状态。

该项已在客户问题非法页查询侧做了收口，仍需后续事实重建后再核对历史脏数据。

### 6. 客户问题专属“缺陷调研模板”非法生成已落到事实层

**影响范围：高。直接影响非法数据总数和非法类型。**

规则总表第 5.4 和老平台代码都要求追加“未按照要求填写缺陷调研模板”。

当前新平台事实层客户问题分支已包含：

- 未设定严重程度
- 未设定模块
- 未按照模板回复
- 缺陷原因不唯一
- 流程越位

客户问题专属调研模板完整性、计划解决时间、一级缺陷负责人签字确认规则已落到事实规则层，且：

- 只在客户问题范围生效。
- 不改变系统测试非法数据页口径。
- 生成非法类型为老平台固定文案：`未按照要求填写缺陷调研模板`。

### 7. 计划解决时间规则需以老平台和规则总表统一口径实现

**影响范围：高。属于客户问题专属非法判定细节。**

规则总表写明：

- 计划解决时间有且只能填写一个日期时间戳。
- 分隔符允许 `.`, `,`, `，`, `、`, `/`, `·`, `` ` ``, `年`, `月`, `日`。

老平台代码当前实际正则更窄：`\\d{4}[年.,，]\\d{1,2}[月.,，]\\d{1,2}[日]?`。

本项目规则总表优先级高于旧代码中更窄的实现；对齐时应以规则总表允许的分隔符为准，同时保持老平台已有合法示例继续合法。

### 8. SQL 分页路径对非法类型筛选已改为多值语义

**影响范围：中到高。影响多非法类型记录的筛选命中。**

当前 `CustomerIssueIllegalRecordService` 已在存在 `illegalReason` 参数时绕开 SQL 分页，改走内存多值匹配，这是正确方向。

但底层 `IssueFactRecordRepository.appendIllegalFilters(...)` 非系统测试分支仍是：

- `appendEqIgnoreCase(where, args, "illegal_reason", query.illegalReason())`

当前客户问题非法页已经按 `illegal_reasons / illegal_reason` 多值集合做了白名单筛选。

### 9. 导出字段已补齐并与页面口径一致

**影响范围：中。影响导出和页面一致性。**

当前未提交改动已把导出表头中的 `非法原因` 改为 `非法类型`，并补充 `功能名`、`测试阶段`、`延期原因`。

导出已与主表和详情字段同口径：

- 导出使用同一筛选范围。
- 导出中 `非法类型` 展示多值全集。
- 导出字段覆盖老平台展开区字段。

### 10. 单条刷新已补齐

客户问题非法数据页已补充单条刷新接口与页面入口，行为与系统测试非法数据页保持一致。

## 暂不记录为问题

- 分页默认值不同。
- 条件筛选组件样式不同。
- 新平台额外提供关键字搜索、规则说明、刷新状态、详情抽屉、标签组筛选等体验增强；只要不改变数据集合和字段含义，可以保留。
- 新平台页面标题为“客户问题非法数据”，老平台文件名是 `IllegalIssueSearchCCProduct`；标题文案不影响数据口径。
