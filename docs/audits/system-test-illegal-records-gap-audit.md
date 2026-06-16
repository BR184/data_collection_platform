# 系统测试非法数据页新旧平台差异记录

> 记录日期：2026-06-15  
> 新平台：`D:\projects\data_collection_platform`  
> 老平台：`D:\projects\spidergitdata-dev`
>
> 本文只记录会影响数据一致性、字段一致性、可达性和非法判定口径的差异。分页默认值、UI 形态和普通交互差异不作为问题记录。

## 对齐目标

同一测试阶段/筛选条件下，新平台应与老平台保持：

1. 非法数据总数一致。
2. 非法类型行和筛选结果一致。
3. 主表字段、详情字段、字段值和展示顺序一致。
4. 导出字段和导出数据一致。
5. 默认进入页的统计范围一致。
6. 规则说明与实际非法判定一致。

## 老平台基线

### 页面入口和主接口

老平台对应页面：

- `webapp/src/views/PageStandard/IllegalIssueSearch.vue`

相关接口：

- `POST /issueStaticData/getIllegalIssue`
- `GET /getDownLabel/getIssueIllegalType`
- `GET /issueStaticData/exportIssue`
- 单条刷新：`updateIssueByProjectIdAndIssueId`

### 老平台非法类型

来自 `IssueIllegalTypeEnum` / `IllegalTypeList`：

- 未设定严重程度
- 未设定模块
- 未按照模板回复
- 缺陷原因不唯一

### 老平台主表和详情字段

主表可见字段：

- 议题编号
- 模块名
- 议题标题
- 议题状态
- 严重程度
- 议题处理人
- 非法类型

展开详情可见字段：

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

### 老平台核心口径

1. `phaseName` 来自 `getLabelPhase()`，页面创建时默认取列表第一项。
2. `getIllegalIssue` 默认项目固定为 `9`（CrownCAD）。
3. 非法查询条件本质上是 `illegal_list is not null`，再按 `illegal_list like <非法类型>` 过滤。
4. `illegal_list` 是多值字符串，单条议题可能同时包含多个非法类型。
5. 页面提供单条议题刷新按钮，可重新拉取当前条数据。

## 已确认接近一致的部分

1. 新平台非法类型已收束为 4 类，并保留老平台同名文案。
2. 新平台规则说明已改为基于 `issue_fact.illegal_reason` 的事实层解释。
3. 新平台已有导出、规则说明、详情抽屉和实时状态。
4. 新平台已把 `未设定模块`、`未设定严重程度` 等支持项纳入非法原因归一化。

## 已修正

1. 默认统计范围已绑定 CrownCAD 项目 `9`。
2. 默认测试阶段已按首个可用阶段自动收敛。
3. 非法类型已改为老平台 4 项固定枚举，筛选和导出不再依赖当前数据现身与否。
4. 详情已补回 `功能名`。
5. 单条刷新已补回，并按 `sourceInstance + projectId + issueIid` 精确刷新。
6. 非法类型多值展示已按老平台 `illegal_list` 使用英文逗号拼接。
7. 模块多值展示已按老平台模块字段使用 `&` 拼接。

## 当前状态

已完成本页真实链路和浏览器冒烟校验，当前未再发现新的老平台功能缺失。
