---
status: draft
owner: pm
date: 2026-06-09
type: requirement-gap
related:
  - docs/platform-page-business-rules.md
  - docs/plans/2026-06-09-object-segmentation-semantic-layer-design.md
  - docs/plans/2026-05-27-platform-functional-ux-review.md
---

# 用户需求单与新平台现状 GAP 分析

## 背景

2026-06-09 收到一份用户需求单，覆盖"老平台不满意点"、"新平台希望新增的能力"、"界面风格偏好"和"标签组筛选具体诉求"四个部分。需求单原始内容已归档（截图：用户研发节点跟踪 + 项目总结视角）。本文档把需求单逐条对齐新平台现状，把没做到的部分作为后续 backlog 输入。

本审计不重写设计、不输出工作量估算，仅作 GAP 罗列。具体落地方案、Phase 切分、工作量评估由后续设计文档承接。

## 需求来源原文摘要

- **使用场景**：日常工作跟踪（评审数据、代码走查数据、系统测试数据等）、项目总结（输出研发各环节数据和图表）。
- **老平台不满意**：效率低、不美观、有功能增加或修改时扩展不方便。新平台可以做成灵活配置，但要将常用功能固化下来。
- **老平台具体问题**：页面整体美观度不高；历史遗留数据无法准确获取；数据只能在固定时间获取，不能实时刷新；不能将议题的每个标签都导出；页面展示仅面向单分支，无法全分支覆盖。
- **新平台已感受到的问题**：没有结合业务需要固化功能，对使用人的要求较高。
- **优化建议**：结合老平台已有功能和实际工作需求，将使用频率高的操作固化成功能或做成默认配置，在此基础上可以提供灵活支持。
- **参考类型**：数据分析类平台（Power BI、阿里 DataV 等）。
- **流程建议**：增加演示频次，小步迭代更新。
- **标签组筛选具体要求**：保存常用筛选方案；标签较多时平铺比下拉收纳更直观。

## GAP 罗列

下表是需求单逐条对齐新平台当前实现的结论。Coverage 取值：`covered` / `partial` / `missing`。`partial` 表示有底层能力但未达到需求单的业务期望。

### 1. BI 风格视觉与项目总结视图

| 项 | 现状 | Coverage |
|---|---|---|
| 看板类页面 | 已有 `QualityBoardRdView`、`CodeReviewMultiBoardView`、`SystemTestMultiBoardView`、`StatisticBoardPage`、`QualityBoardOtherView` 等多个看板，使用 ECharts + Element Plus | partial |
| 项目总结独立视图 | 没有"项目总结"独立页面；研发各环节数据分散在各业务看板和记录页 | missing |
| BI 风格美观度 | 当前以表格 + ECharts 为主，缺少 Power BI / DataV 风格的卡片栅格、KPI 大数、配色主题、过渡动画 | partial |

**GAP 摘要**：

- 缺少一个面向"项目总结"的统一视图，把评审、代码走查、系统测试、客户问题、MR 等环节的关键指标聚合到一个页面，并支持按版本/项目切换。
- 现有看板视觉离 BI 工具仍有差距，主要差异点：缺少统一的 KPI 头部、卡片栅格化布局、深色/明色主题切换、过渡动画、可配置的图表组合。
- 需要明确"是仅做视觉升级，还是参考 BI 模式做可拖拽组件"——后者代价大但与"灵活配置"诉求一致。

### 2. 历史遗留数据准确获取

| 项 | 现状 | Coverage |
|---|---|---|
| 操作快照 | `CollectFormAuditService.snapshot()` 仅记录表单审计，不是数据快照 | missing |
| 业务数据历史版本 | 未找到面向"业务事实快照"的功能；新平台基于 GitLab 实时镜像，未持久化历史版本 | missing |
| 老平台历史数据迁移 | 未找到从老平台抽取历史数据的入口；现有业务页面仅展示当前镜像 | missing |

**GAP 摘要**：

- 老平台历史数据的导入路径在新平台中是空白。需要确认两种方案：(a) 把老平台数据库直接迁移到新平台事实表；(b) 新平台只承接 2026-XX 之后的数据，历史数据保留在老平台只读访问。
- 即使不做老平台迁移，"业务数据某一时刻的快照"在新平台也没有持久化机制，未来做"季度复盘""版本回顾"时数据已经被覆盖。这点与对象分群方案中的 `segment_snapshot` 思路相似，但 segment_snapshot 仅快照成员清单，不快照指标值。
- 建议把"事实层指标按业务节点（版本发布、季度结束）固化快照"作为独立设计，与 segment_snapshot 解耦。

### 3. 数据实时刷新

| 项 | 现状 | Coverage |
|---|---|---|
| 后台定时同步 | 已有 `GitlabCompensationScheduler` 配合 cron 触发；增量/全量同步 endpoint 6 个 | covered |
| 手动刷新触发 | 前端 `MirrorSettingsView`、`MirrorRunMonitorPanel` 提供管理员手动触发 | covered |
| 业务页面"刷新"按钮 | 各看板和记录页有刷新按钮，但本质是重拉接口数据 | partial |
| 推送式实时（WebSocket / SSE） | 未找到 WebSocket 或 SSE 推送通道 | missing |

**GAP 摘要**：

- 后端同步和管理员触发能力齐备；用户视角的"实时"目前实际是"刷新页面后看到最近一次同步的结果"。
- 需求单"实时刷新"应理解为：业务事件发生后，业务页面在分钟级以内可见，而不是真正的毫秒级推送。改进点是：(a) 关键页面增加"上次数据时间"水位提示；(b) 关键页面提供"立即同步该项目"窗口级触发，不必让用户去管理员页。
- 推送式实时不在用户当前场景内，不建议在本周内承诺。

### 4. 议题标签全量导出

| 项 | 现状 | Coverage |
|---|---|---|
| 评审数据导出 | `ReviewDataExcelExportService.exportReviewRecordsWorkbook` / `exportProblemDetailsWorkbook` 已存在 | covered |
| 议题标签字段是否完整 | 未在代码中找到明确证据，待 AI 员工核查 `IssueExportService` 或对应 service 实现是否包含原始标签数组、严重程度、紧急程度、模块、阶段、闭环状态等全部维度 | partial |
| 导出参数追溯 | 当前导出未记录筛选条件 hash 或 segment_id；与对象分群方案的导出审计要求未对齐 | partial |

**GAP 摘要**：

- 需要 AI 员工先做一次现状盘点：议题导出 sheet 的列清单 vs 议题在 GitLab 中实际拥有的标签清单，确认缺漏哪几列。
- 即使列齐了，"每个标签都导出"还要求标签是结构化字段（严重程度、紧急程度、模块单独成列），不是塞在一个 `labels` 字符串里。这一点与语义标签组方案天然契合，可作为对象分群方案落地的衍生收益。
- 导出参数追溯属于 [对象分群方案 Phase 6](2026-06-09-object-segmentation-semantic-layer-design.md) 已规划范围，本文档不重复。

### 5. 全分支覆盖

| 项 | 现状 | Coverage |
|---|---|---|
| 代码走查 target_branch 过滤 | `CodeReviewIllegalRecordsView` 与 `code-review-api.ts` 已支持单分支过滤 | covered |
| MR 多分支并行视角 | 未找到"多分支对比"页面；MR 走查相关页面仍按单源单分支展示 | missing |
| 横向对比中的分支维度 | 横向对比当前以模块/项目/版本为维度，未把分支作为独立维度 | missing |

**GAP 摘要**：

- 单分支过滤能力齐备，差距在于"业务方希望同时看 dev / release / hotfix 几条分支的指标对比"。这与对象分群方案中 `merged_dev_mr_scope` 的单分支限定是一致的——目前业务上只看 dev，但用户在期望未来能扩展。
- 短期：在已有走查/MR 页面增加"分支多选"过滤器，配合 group by 分支展示对比卡片。
- 长期：把"分支"列入语义维度，与"项目/版本/模块"对齐，作为动态标签组之一（与 [对象分群方案 §动态标签组](2026-06-09-object-segmentation-semantic-layer-design.md) 的 `project_version` / `milestone` 同级）。

### 6. 常用功能固化与默认配置

| 项 | 现状 | Coverage |
|---|---|---|
| 快捷入口 / 首页快速操作 | 未找到"首页快捷入口"或"个人收藏页面"机制 | missing |
| 默认视图 / 预设配置 | 当前各业务页面的筛选/排序/列宽不会持久化用户上次选择 | missing |
| 业务场景预设 | 未找到"系统测试缺陷汇总-CrownCAD-CC2026R1"这种"开箱即用"的预设业务场景 | missing |
| 角色级默认 | 未找到 ADMIN / APPROVAL / 普通用户的差异化默认视图 | missing |

**GAP 摘要**：

- 这是需求单里指向最明确的痛点："新平台太灵活，对使用人要求高"。直接对策是给前端引入 user-preference 持久化层 + 业务场景模板。
- 与"对象分群"和"语义标签组"方案天然耦合：把"系统测试缺陷汇总"做成一个`scenario_key + 默认 scope_chain + 默认标签组组合`的预设，用户进入页面时直接看到结果，再点"调整"展开筛选。
- 缺失的几样具体能力：
    - 个人收藏 / 最近使用页面（前端 LocalStorage + 后端可选）
    - 业务场景模板（后端 `scenario_template` 表，记录默认 scope/筛选/排序/列）
    - 用户偏好持久化（每个用户在每个页面的最近一次筛选）
- 建议作为独立"默认配置与业务场景预设"设计文档承接，与对象分群方案解耦但参考其 scenario_key 概念。

### 7. 标签组筛选：保存方案 + 平铺展示

| 项 | 现状 | Coverage |
|---|---|---|
| `StatisticFilterBuilder` 条件组合 | 已支持多条件组合与展开/折叠 | covered |
| 保存常用筛选方案 | 未找到 saved filter / saved view / preset 机制 | missing |
| 标签平铺 vs 下拉切换 | 未找到平铺/下拉切换；当前以 `el-checkbox-group` 为主 | missing |
| 与对象分群方案对接 | 对象分群方案已规划"保存为分群"入口，但保存对象是分群成员而非筛选条件，二者不能互替 | partial |

**GAP 摘要**：

- "保存常用筛选方案"语义上接近一个"轻量级动态分群"，但不要求计算成员，只要求保存条件本身（条件 hash、命名、所属页面、所属用户）。建议设计 `saved_filter_preset` 独立表，避免和 `segment_definition` 混用。
- 平铺/下拉切换属于纯前端组件改造，建议在 `StatisticFilterBuilder` 上加一个 `displayMode: "tile" | "dropdown"` 属性，按标签组的 `value_count` 自动选择默认模式（>=20 项默认下拉，<20 项默认平铺）。
- 后端只需暴露"标签组当前选项数量"——这一字段对象分群方案中已经有（`semantic_tag_group_build_run.value_count`），可直接复用。

### 8. 流程建议：演示频次与小步迭代

不是产品功能，但记录在此供 PM 排期参考：

- 用户期望增加演示频次、小步迭代。当前各 Phase 设计粒度（参考 [对象分群方案 §计划粒度与估算口径](2026-06-09-object-segmentation-semantic-layer-design.md)）已经按 5-14 工作日切分，可在每个 Phase 验收后安排一次业务方演示，避免一次性交付半年成果。

## GAP 汇总表

| 编号 | GAP 类别 | 当前 coverage | 是否阻塞业务 | 是否已被现有设计覆盖 | 优先级建议 |
|---|---|---|---|---|---|
| G1 | 项目总结独立视图 | missing | 中 | 否 | P1 |
| G2 | BI 风格美观度升级 | partial | 低 | 否 | P2 |
| G3 | 业务事实历史快照 | missing | 中 | 否（segment_snapshot 不覆盖指标） | P1 |
| G4 | 老平台历史数据迁移 | missing | 高 | 否 | P0（需要业务侧确认方案） |
| G5 | 业务页面"立即同步"窗口触发 | partial | 中 | 否 | P1 |
| G6 | 议题标签字段完整性核查 | partial | 高 | 部分（语义标签组方案隐式覆盖） | P0（需要先盘点现状） |
| G7 | 多分支对比视角 | missing | 中 | 否 | P2 |
| G8 | 个人收藏 / 最近使用页面 | missing | 中 | 否 | P1 |
| G9 | 业务场景模板（scenario_template） | missing | 高 | 部分（对象分群有 scenario_key 但无模板） | P1 |
| G10 | 用户偏好持久化 | missing | 高 | 否 | P1 |
| G11 | 保存筛选方案（saved_filter_preset） | missing | 高 | 否（不能复用 segment_definition） | P1 |
| G12 | 标签组平铺/下拉切换 | missing | 中 | 否 | P2 |

P0 表示需要在本周/下周内由业务方确认或后端先盘点现状，P1 是用户感受最强的痛点，P2 可在长期规划中承接。

## 后续动作建议

1. **G4 / G6**：AI 员工各做一次盘点 PR，输出现状报告，不实施改动。
    - G4 报告：老平台还在用的页面/数据范围，新平台已覆盖哪些、未覆盖哪些。
    - G6 报告：议题导出 Excel 的列清单 + GitLab 议题原始字段清单，标出缺失。
2. **G3 / G8 / G9 / G10 / G11**：作为"用户体验固化"专项方案，独立写一份设计文档承接，建议日期 `2026-06-1X-platform-defaults-and-presets-design.md`。
3. **G1 / G2**：合并入"项目总结视图与视觉升级"专项，独立设计文档承接。建议先不上 BI 拖拽编辑器，先做一版"固化指标 + 卡片栅格"。
4. **G5 / G7 / G12**：可作为单页改造任务进入下一周 sprint backlog，无需独立设计文档。
5. **演示频次**：建议每个 Phase 验收后安排 30 分钟业务演示。

本文档不替代 [对象分群方案](2026-06-09-object-segmentation-semantic-layer-design.md)；对象分群方案聚焦"语义层 + 分群计算"，本文档覆盖"用户体验、视觉、默认配置、历史数据"四类需求单议题。两份文档协同，不冲突也不替代。
