# BI 看板字段级数据来源与计算口径核对表

> 权威边界：本表已经用户人工确认。凡 BI 看板涉及数据筛选、去重、聚合、派生、比率、占比、累计、达标判断或不可计算状态，必须直接遵循本表对应编号的“公式或规则”及其引用链；其它文档、旧实现和平台共享服务不得覆盖本表公式。
>
> 维护说明：每一行只对应一个看板显示字段、PNG 图表输入或必需维度。覆盖截至 2026-08-04 已确认的需求，不包含已废弃的跨阶段“六类评审问题总数对比”。“实现状态”和“人工核对”列用于跟踪当前权威实现，不改变公式权威；未勾选不表示公式未确认。物理字段必须以已验证的表结构、实体和查询为准，英文候选名不要求与代码逐字一致，但语义等价时必须记录真实 `table.column` 和“等价映射”；完全未进入当前权威链路的字段必须标记“当前未使用”。

## 实现状态说明

| 实现状态 | 含义 |
| --- | --- |
| 直接使用 | 物理字段、上游聚合或已冻结契约与本行语义完全一致，当前权威实现直接消费。 |
| 等价映射 | 真实物理字段名与早期候选名不同，但代码、表结构和查询已证实业务语义等价；本行记录真实 `table.column`。 |
| BI计算 | 当前权威实现在 BI 服务端按本行公式计算，不要求上游提供同名结果。 |
| 当前未使用 | 字段可能存在或曾出现在方案中，但当前页面 DTO、计算器和图表没有消费。 |
| 上游缺失 | 当前公式所需基础字段或契约尚未由责任上游提供。 |
| 待确认 | 字段已出现于接口或需求，但语义、映射、一致性或基数仍未冻结，当前不能进入 `READY` 统计。 |

## 计算责任

- CAT 全权提供单元测试和集成测试页面的数据；数据采集平台事实不得参与补齐。当前两页按各自显式阶段 ID 复用 CAT 统计协议，页面只消费 CAT 已提供的稳定字段。
- 集成测试手册已说明项目、版本/阶段、整体/模块/功能通过率和模块功能达标数量等传输字段，但没有三层执行/通过用例数或基础记录。只有 CAT 聚合语义与本表对应编号一致时才直接消费；不能用功能数量或通过率反推用例数。
- BI 可计算模块/功能聚合、时间桶累计、比率、占比和达标状态；去重、有效状态排除、多维归属和大规模原始数据聚合在平台共享服务或 BI 应用服务完成，不在浏览器加载全量明细后计算。
- 每个计算值需要记录计算来源（`upstream` 或 `bi`）、输入字段和公式版本；分母不足或基础字段缺失时返回不可计算/待接入状态，不以 `0` 冒充。
- 上游同时提供预聚合值和基础字段时，BI 可以做一致性校验；出现差异必须报告数据质量问题，不能静默选择另一套结果。
- `集成测试平台接口使用手册.md` 只确认 CAT 传输字段和上游行为，不覆盖本表公式；手册未提供的用例计数、来源身份和精确通过率公式必须保持缺口状态。

## 公共范围与维度

| 编号 | 阶段 | 字段 | 类型 | 来源 | 原始字段 | 公式或规则 | 实现状态 | 人工核对 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| G-00 | 全局 | 项目 ID | 请求/维度 | CAT | 项目目录 `data[].id`；阶段子节点 `projectId` | 集成测试项目稳定 ID 已说明；运行时必须使用用户已保存映射并传入。唯一 `defaultProject` 只能生成首次可编辑建议，不能在请求时兜底。单元测试项目字段仍待接口。 | 待确认 | [ ] |
| G-00A | 全局 | 项目名称 | 显示维度 | CAT | 项目目录 `data[].name` | 与 G-00 对应；仅用于显示和映射核验，不作为业务键。 | 待确认 | [ ] |
| G-01 | 全局 | 产品版本 ID | 维度 | 数据采集平台 / CAT | 平台 `issue_scope_groups.id`；CAT 版本顶级节点 `id`、阶段子节点 `versionId` | 六页使用同一产品版本语义；平台直接使用稳定目录 ID。CAT 运行时必须使用已保存映射，不要求共享批次；唯一版本语义匹配或平台默认版本的唯一 `curVersion` 仅可生成首次可编辑建议。 | 待确认 | [ ] |
| G-02 | 全局 | 产品版本名称 | 显示维度 | 数据采集平台 / CAT | 平台 `issue_scope_groups.display_name`；CAT 版本顶级节点 `name` | 仅用于显示和映射核验，不替代稳定 ID；平台字段已直接使用，CAT 映射仍待确认。 | 待确认 | [ ] |
| G-03 | 全局 | 研发阶段 ID | 请求/维度 | 数据采集平台 / CAT | 平台 `issue_scope_members.id`；CAT 测试阶段子节点 `id`、统计参数 `testingPhaseId` | 平台系统测试轮次直接使用稳定目录成员 ID；CAT 同版本多阶段的选择/合并规则待确认，单元测试接口未提供。 | 待确认 | [ ] |
| G-04 | 全局 | 研发阶段名称 | 显示维度 | 数据采集平台 / CAT | 平台 `issue_scope_members.display_name`；CAT 测试阶段子节点 `name` | 按 G-03 映射显示，仅用于校验和展示。 | 待确认 | [ ] |
| G-05 | 全局 | 数据集键 | 数据状态 | 数据采集平台 | 早期候选 `datasetKey` | 当前页面响应以 `pageKey` 区分页面，不消费独立 `datasetKey`。 | 当前未使用 | [ ] |
| G-06 | 全局 | 数据结构版本 | 数据状态 | 数据采集平台 | 早期候选 `schemaVersion` | 当前响应使用 `ruleVersion` 和强类型 DTO，没有独立 `schemaVersion`。 | 当前未使用 | [ ] |
| G-07 | 全局 | 数据生成时间 | 数据状态 | BI / CAT | BI `BiPageResponse.generatedAt` | 由 BI 生成响应时间，仅用于显示和追溯，不能代替一致快照；CAT 未提供来源生成时间。 | BI计算 | [ ] |
| G-08 | 全局 | 数据快照 ID | 数据状态 | 数据采集平台 / CAT | 平台 `PageRecordSnapshotService` / `issueFactSourceVersion` 发布身份；CAT 等价稳定身份待提供 | 平台四页使用冻结后的已发布来源身份；CAT 四接口未提供，不能用响应哈希或调用时间代替。 | 待确认 | [ ] |
| G-08A | 全局 | 来源版本 | 数据状态 | 数据采集平台 / CAT | 平台 `PageRecordSnapshotService` / `issueFactSourceVersion`；CAT 等价稳定身份待提供 | 记录当前页面自己的来源版本，不要求六页采集时刻一致。 | 待确认 | [ ] |
| G-08B | 全局 | 业务规则版本 | 数据状态 | BI | 各计算器和分类器 `RULE_VERSION` | 由 BI 权威实现输出；规则变化时用于追溯和失效判断。 | BI计算 | [ ] |
| G-08C | 全局 | 数据状态 | 数据状态 | BI / CAT | BI `BiDataStatus`；CAT 响应 `code/message/data` | BI 按来源完整性和公式边界计算 `READY/EMPTY/NOT_APPLICABLE/INCOMPLETE/ERROR`；CAT 错误分类仍待补充。 | BI计算 | [ ] |
| G-09 | 全局 | 模块业务键或快照分组维度 | 维度 | 数据采集平台 / CAT | 平台 `review_visible_records.module_name`、编码代码规模兼容态 `code_review_match_mode_records.module_name` / 正式态 `code_review_formal_records.module_name`、编码人工走查兼容态 `code_review_match_mode_records.module_name`、`issue_fact.module_names`；CAT `data.result[].id` | 跨系统关联和 CAT 下钻使用稳定模块 ID；平台四页只在单一冻结来源快照内分组，直接使用名称字段且不伪造全局 ID。缺失值进入“未标注模块”。 | 等价映射 | [ ] |
| G-10 | 全局 | 模块名称 | 显示维度 | 数据采集平台 / CAT | 平台同 G-09；CAT `moduleName`、冗余 `name` | 平台快照分组值同时作为显示名；CAT `name` 只做一致性校验，冲突时不可计算。 | 等价映射 | [ ] |
| G-11 | 全局 | 功能 ID | 维度 | 数据采集平台 / CAT | CAT 功能统计 `featureUniqueId` | `id` 在多功能展示节点下可能为空，不作回退；展示节点与真实功能的基数关系待样例确认。 | 待确认 | [ ] |
| G-12 | 全局 | 功能名称 | 显示维度 | 数据采集平台 / CAT | CAT 功能统计 `name` | 仅作显示；当前仅集成测试接口已说明。 | 待确认 | [ ] |
| G-13 | 编码 | 代码来源 | 筛选维度 | 数据采集平台 | `code_review_match_mode_records.source_instance` / `code_review_formal_records.business_source` / `merge_request_commit_fact.source_instance` | 可选 CC、DGM；筛选同时作用于编码页三类事实。物理字段由平台兼容模式及事实族决定，同一请求不得跨模式拼接。 | 等价映射 | [ ] |
| G-14 | 编码 | 仓库 ID | 筛选维度 | 数据采集平台 | 当前上游未提供稳定仓库 ID | 与版本、来源共同限定编码统计范围；禁止用仓库显示名称代替。 | 上游缺失 | [ ] |
| G-15 | 编码 | 仓库名称 | 显示/筛选维度 | 数据采集平台 | 兼容态 `code_review_match_mode_records.repository_name` / 正式态 `code_review_formal_records.repository_name` | 当前 Adapter 读取该字段，但页面 DTO 和图表未消费。 | 当前未使用 | [ ] |
| G-16 | 编码 | 目标分支 | 筛选规则 | 数据采集平台 | 代码规模兼容态 `code_review_match_mode_records.target_branch` / 正式态 `code_review_formal_records.target_branch`；人工走查兼容态 `code_review_match_mode_records.target_branch` | 当前代码趋势和人工走查只查询合并到 `dev` 的记录。 | 直接使用 | [ ] |

## 需求评审

| 编号 | 阶段 | 字段 | 类型 | 来源 | 原始字段 | 公式或规则 | 实现状态 | 人工核对 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| RQ-01 | 需求 | 评审记录 ID | 明细维度 | 数据采集平台 | `review_visible_records.id` | 单次评审散点图的一条记录对应一个稳定非零 ID；正式记录为正数，兼容来源由统一视图使用负数命名空间隔离，零值才是无效身份。 | 等价映射 | [ ] |
| RQ-02 | 需求 | 评审日期 | 明细维度 | 数据采集平台 | `review_visible_records.review_date` | 用于散点明细和按时间追溯。 | 等价映射 | [ ] |
| RQ-03 | 需求 | 评审模块快照维度 | 明细维度 | 数据采集平台 | `review_visible_records.module_name` | 需求页只在同一评审快照内按模块名称值聚合，不要求不存在的模块 ID；缺失值进入“未标注模块”。 | 等价映射 | [ ] |
| RQ-04 | 需求 | 被评审页数 | 原始度量 | 数据采集平台 | `review_visible_records.review_scale_pages` | 缺陷密度和评审速率的分母/分子。 | 等价映射 | [ ] |
| RQ-05 | 需求 | 独立评审实际工作量（小时） | 原始度量 | 数据采集平台 | `review_visible_problem_items.workload_hours`，且 `review_category='独立评审'` | 评审速率的分母；不使用评审开始至结束的自然时长。 | 等价映射 | [ ] |
| RQ-06 | 需求 | 有效评审问题数 | 计算输入 | 数据采集平台 / BI | `review_visible_problem_items.id/problem_status/problem_category` | BI 查询按评审记录聚合；排除状态“已拒绝、未评审、无问题”及类别“无问题”的问题。 | BI计算 | [ ] |
| RQ-07 | 需求 | 评审缺陷密度 | 计算值 | BI | RQ-04、RQ-06 | `有效评审问题数 ÷ 被评审页数`；页数小于等于 0 显示 `/`。 | BI计算 | [ ] |
| RQ-08 | 需求 | 评审速率 | 计算值 | BI | RQ-04、RQ-05；可直接提供 `reviewRate` | `被评审页数 ÷ 独立评审实际工作量（小时）`；工作量小于等于 0 显示 `/`。 | BI计算 | [ ] |
| RQ-09 | 需求 | 缺陷密度目标下限 | 质量目标 | BI 规则 | `BiReviewCalculator.MIN_DENSITY` | 固定为 `0.20`。 | BI计算 | [ ] |
| RQ-10 | 需求 | 缺陷密度目标上限 | 质量目标 | BI 规则 | `BiReviewCalculator.MAX_DENSITY` | 固定为 `0.60`。 | BI计算 | [ ] |
| RQ-11 | 需求 | 缺陷密度达标状态 | 计算值 | BI | RQ-07、RQ-09、RQ-10 | `0.20 ≤ 评审缺陷密度 ≤ 0.60` 为达标；评审速率没有已确认目标，不生成速率达标状态。 | BI计算 | [ ] |
| RQ-12 | 需求 | 文档规范问题数 | 类别计数 | 数据采集平台 / BI | `review_visible_problem_items.problem_status/problem_category` | 对有效问题中 `problem_category='文档规范'` 的记录计数。 | BI计算 | [ ] |
| RQ-13 | 需求 | 完整性问题数 | 类别计数 | 数据采集平台 / BI | `review_visible_problem_items.problem_status/problem_category` | 对有效问题中 `problem_category='完整性'` 的记录计数。 | BI计算 | [ ] |
| RQ-14 | 需求 | 功能性问题数 | 类别计数 | 数据采集平台 / BI | `review_visible_problem_items.problem_status/problem_category` | 对有效问题中 `problem_category='功能性'` 的记录计数。 | BI计算 | [ ] |
| RQ-15 | 需求 | 可行性问题数 | 类别计数 | 数据采集平台 / BI | `review_visible_problem_items.problem_status/problem_category` | 对有效问题中 `problem_category='可行性'` 的记录计数。 | BI计算 | [ ] |
| RQ-16 | 需求 | 各问题类别占比 | 计算值 | BI | RQ-06、RQ-12 至 RQ-15 | `该类别有效问题数 ÷ 全部有效问题数`；用于本阶段类别分布，不比较跨阶段总数。 | BI计算 | [ ] |
| RQ-17 | 需求 | 模块评审页数 | 模块计算输入 | BI | RQ-03、RQ-04 | 按来源快照内模块维度汇总页数。 | BI计算 | [ ] |
| RQ-18 | 需求 | 模块有效问题数 | 模块计算输入 | BI | RQ-03、RQ-06 | 按来源快照内模块维度汇总有效问题数。 | BI计算 | [ ] |
| RQ-19 | 需求 | 模块评审工作量 | 模块计算输入 | BI | RQ-03、RQ-05 | 按来源快照内模块维度汇总独立评审工作量。 | BI计算 | [ ] |
| RQ-20 | 需求 | 模块评审缺陷密度 | 模块计算值 | BI | RQ-17、RQ-18 | `模块有效问题数 ÷ 模块评审页数`。 | BI计算 | [ ] |
| RQ-21 | 需求 | 模块评审速率 | 模块计算值 | BI | RQ-17、RQ-19 | `模块评审页数 ÷ 模块评审工作量（小时）`。 | BI计算 | [ ] |
| RQ-22 | 需求 | 模块缺陷密度达标状态 | 模块计算值 | BI | RQ-20、RQ-09、RQ-10 | 按 RQ-11 的区间规则判定。 | BI计算 | [ ] |
| RQ-23 | 需求 | 单次评审散点横坐标 | 图表计算值 | BI | RQ-04、RQ-05 | 值等于 RQ-08。 | BI计算 | [ ] |
| RQ-24 | 需求 | 单次评审散点纵坐标 | 图表计算值 | BI | RQ-04、RQ-06 | 值等于 RQ-07。 | BI计算 | [ ] |

## 设计评审

| 编号 | 阶段 | 字段 | 类型 | 来源 | 原始字段 | 公式或规则 | 实现状态 | 人工核对 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| DS-01 | 设计 | 评审记录 ID | 明细维度 | 数据采集平台 | `review_visible_records.id` | 单次评审散点图的一条记录对应一个稳定非零 ID；正式记录为正数，兼容来源由统一视图使用负数命名空间隔离，零值才是无效身份。 | 等价映射 | [ ] |
| DS-01A | 设计 | 评审日期 | 明细维度 | 数据采集平台 | `review_visible_records.review_date` | 用于散点明细和按时间追溯。 | 等价映射 | [ ] |
| DS-01B | 设计 | 评审模块快照维度 | 明细维度 | 数据采集平台 | `review_visible_records.module_name` | 设计页只在同一评审快照内按模块名称值聚合；缺失值进入“未标注模块”。 | 等价映射 | [ ] |
| DS-02 | 设计 | 被评审页数 | 原始度量 | 数据采集平台 | `review_visible_records.review_scale_pages` | 设计评审缺陷密度和速率的计算输入。 | 等价映射 | [ ] |
| DS-03 | 设计 | 独立评审实际工作量（小时） | 原始度量 | 数据采集平台 | `review_visible_problem_items.workload_hours`，且 `review_category='独立评审'` | 设计评审速率的分母。 | 等价映射 | [ ] |
| DS-04 | 设计 | 有效评审问题数 | 计算输入 | 数据采集平台 / BI | `review_visible_problem_items.id/problem_status/problem_category` | 有效问题排除规则同 RQ-06。 | BI计算 | [ ] |
| DS-05 | 设计 | 评审缺陷密度 | 计算值 | BI | DS-02、DS-04 | `有效评审问题数 ÷ 被评审页数`。 | BI计算 | [ ] |
| DS-06 | 设计 | 评审速率 | 计算值 | BI | DS-02、DS-03；可直接提供 `reviewRate` | `被评审页数 ÷ 独立评审实际工作量（小时）`。 | BI计算 | [ ] |
| DS-07 | 设计 | 缺陷密度目标下限 | 质量目标 | BI 规则 | `BiReviewCalculator.DESIGN_MIN_DENSITY` | 固定为 `0.30`（2026-09-09 起与需求评审口径分离）。 | BI计算 | [ ] |
| DS-08 | 设计 | 缺陷密度目标上限 | 质量目标 | BI 规则 | `BiReviewCalculator.DESIGN_MAX_DENSITY` | 固定为 `0.80`（2026-09-09 起与需求评审口径分离）。 | BI计算 | [ ] |
| DS-09 | 设计 | 缺陷密度达标状态 | 计算值 | BI | DS-05、DS-07、DS-08 | `0.30 ≤ 评审缺陷密度 ≤ 0.80` 为达标。 | BI计算 | [ ] |
| DS-10 | 设计 | 文档规范问题数 | 类别计数 | 数据采集平台 / BI | `review_visible_problem_items.problem_status/problem_category` | 本类别有效问题计数。 | BI计算 | [ ] |
| DS-11 | 设计 | 完整性问题数 | 类别计数 | 数据采集平台 / BI | `review_visible_problem_items.problem_status/problem_category` | 本类别有效问题计数。 | BI计算 | [ ] |
| DS-12 | 设计 | 功能性问题数 | 类别计数 | 数据采集平台 / BI | `review_visible_problem_items.problem_status/problem_category` | 本类别有效问题计数。 | BI计算 | [ ] |
| DS-13 | 设计 | 可行性问题数 | 类别计数 | 数据采集平台 / BI | `review_visible_problem_items.problem_status/problem_category` | 本类别有效问题计数。 | BI计算 | [ ] |
| DS-14 | 设计 | 各问题类别占比 | 计算值 | BI | DS-04、DS-10 至 DS-13 | `该类别有效问题数 ÷ 全部有效问题数`。 | BI计算 | [ ] |
| DS-15 | 设计 | 模块评审页数 | 模块计算输入 | BI | DS-01B、DS-02 | 按来源快照内模块维度汇总。 | BI计算 | [ ] |
| DS-16 | 设计 | 模块有效问题数 | 模块计算输入 | BI | DS-01B、DS-04 | 按来源快照内模块维度汇总。 | BI计算 | [ ] |
| DS-17 | 设计 | 模块评审工作量 | 模块计算输入 | BI | DS-01B、DS-03 | 按来源快照内模块维度汇总。 | BI计算 | [ ] |
| DS-18 | 设计 | 模块评审缺陷密度 | 模块计算值 | BI | DS-15、DS-16 | `模块有效问题数 ÷ 模块评审页数`。 | BI计算 | [ ] |
| DS-19 | 设计 | 模块评审速率 | 模块计算值 | BI | DS-15、DS-17 | `模块评审页数 ÷ 模块评审工作量（小时）`。 | BI计算 | [ ] |
| DS-20 | 设计 | 模块缺陷密度达标状态 | 模块计算值 | BI | DS-18、DS-07、DS-08 | 按 DS-09 的区间规则判定。 | BI计算 | [ ] |
| DS-21 | 设计 | 单次评审散点横坐标 | 图表计算值 | BI | DS-02、DS-03 | 值等于 DS-06。 | BI计算 | [ ] |
| DS-22 | 设计 | 单次评审散点纵坐标 | 图表计算值 | BI | DS-02、DS-04 | 值等于 DS-05。 | BI计算 | [ ] |

## 编码与人工代码走查

老平台 KLOC 口径已确认并作为 BI 统一规则：老平台 Controller、Service、Vue 页面和导出 DTO 把“代码走查缺陷密度”“模块千行缺陷率”“走查人/被走查人千行缺陷率”作为同一指标的不同维度。总体、模块和代码提交范围使用 `有效走查缺陷总数 × 1000 ÷ 被走查新增代码行数总数`；走查人、被走查人维度先按每条走查记录计算 `该记录有效缺陷数 × 1000 ÷ 该记录被走查新增代码行数`，再按老平台规则对记录密度取平均。代码规模图的 KLOC 仍为 `去重新增代码行数 ÷ 1000`，不能把人员维度平均密度改成总量比，也不能用代码规模 KLOC 替代缺陷密度。BI 不再建立同义的独立“编码阶段缺陷密度”或第二套“千行代码缺陷率”。

本节在请求开始时冻结平台唯一兼容模式，但按事实族区分物理来源：代码趋势、人员贡献、模块增量、人工走查质量、问题分布、散点、扫描和注释率在兼容态统一读取 `code_review_match_mode_records`、正式态统一读取 `code_review_formal_records`；提交趋势和频次读取 `merge_request_commit_fact`。三类事实按各自事实族独立计算，不得互相补数据。

| 编号 | 阶段 | 字段 | 类型 | 来源 | 原始字段 | 公式或规则 | 实现状态 | 人工核对 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| CD-01 | 编码 | 合并请求 ID | 明细维度 | 数据采集平台 | 兼容态 `code_review_match_mode_records.source_instance + merge_request_iid` / 正式态 `code_review_formal_records.project_id + merge_request_id` | 代码行数、合并请求数使用本节已冻结读源的稳定去重键；任一必需成员缺失时该事实族不完整。 | 等价映射 | [ ] |
| CD-02 | 编码 | 合并时间 | 明细维度 | 数据采集平台 | 兼容态 `code_review_match_mode_records.merged_at_source` / 正式态 `code_review_formal_records.merged_at_source` | 日/周趋势的分组时间。 | 等价映射 | [ ] |
| CD-03 | 编码 | 提交作者快照维度 | 明细维度 | 数据采集平台 | 兼容态 `code_review_match_mode_records.author_name` / 正式态 `code_review_formal_records.author_name` | 个人代码贡献只在当前冻结来源快照内按规范化作者名称值分组，不把名称伪装成全局员工 ID；缺失值进入“未标注作者”。 | 等价映射 | [ ] |
| CD-03A | 编码 | 提交作者名称 | 显示维度 | 数据采集平台 | 同 CD-03 | 与 CD-03 使用同一来源值作为显示名称。 | 直接使用 | [ ] |
| CD-04 | 编码 | 新增代码行数 | 原始度量 | 数据采集平台 | 兼容态 `code_review_match_mode_records.added_lines` / 正式态 `code_review_formal_records.added_lines` | 同一合并请求有多条走查记录时只计一次。 | 等价映射 | [ ] |
| CD-05 | 编码 | 新增代码千行数 | 计算值 | BI | CD-04 | `去重新增代码行数 ÷ 1000`。 | BI计算 | [ ] |
| CD-06 | 编码 | 累计新增代码行数 | 计算值 | BI | CD-02、CD-04 | 依时间排序后，逐时间桶累加 CD-04。 | BI计算 | [ ] |
| CD-07 | 编码 | 合并请求数 | 计算值 | BI | CD-01 | 当前筛选范围内 `去重合并请求 ID` 数量。 | BI计算 | [ ] |
| CD-08 | 编码 | 贡献员工数 | 计算值 | BI | CD-03 | 当前筛选范围内去重作者快照维度数量；“未标注作者”作为一个显式成员。 | BI计算 | [ ] |
| CD-09 | 编码 | 日/周代码增量 | 计算值 | BI | CD-02、CD-04 | 按日或周分组，汇总去重合并请求的新增代码行数。 | BI计算 | [ ] |
| CD-10 | 编码 | 模块代码增量 | 计算值 | BI | 兼容态 `code_review_match_mode_records.module_name` / 正式态 `code_review_formal_records.module_name`、CD-01、CD-04 | 按来源快照内模块名称维度汇总去重合并请求的新增代码行数；缺失值进入“未标注模块”。 | BI计算 | [ ] |
| CD-11 | 编码 | 仓库新增代码千行数 | 计算值 | 数据采集平台 | 兼容态 `code_review_match_mode_records.repository_name` / 正式态 `code_review_formal_records.repository_name`、CD-01、CD-04 | 当前页面 DTO 和图表没有仓库代码量序列，已读取的仓库名称不参与计算。 | 当前未使用 | [ ] |
| CD-12 | 编码 | 个人代码贡献量 | 计算值 | BI | CD-03、CD-01、CD-04 | 按作者快照维度汇总去重新增代码行数。 | BI计算 | [ ] |
| CD-13 | 编码 | 提交 ID | 明细维度 | 数据采集平台 GitLab 事实层 | `merge_request_commit_fact.source_instance + project_id + commit_sha` | 提交频次和按时间分布的稳定键；同一提交进入多个 MR 时仍按来源、项目和 SHA 去重，不能用显示文本去重。 | 等价映射 | [ ] |
| CD-14 | 编码 | 提交时间 | 明细维度 | 数据采集平台 GitLab 事实层 | `merge_request_commit_fact.committed_at_source` | 使用 GitLab 原生提交时间定位提交集中阶段，不用 MR 合并时间替代。 | 直接使用 | [ ] |
| CD-15 | 编码 | 提交频次 | 计算值 | BI | CD-13、CD-14 | 按当前日/周粒度统计每个时间桶内去重提交数量。 | BI计算 | [ ] |
| CD-16 | 编码 | 代码走查记录 ID | 明细维度 | 数据采集平台 | 兼容态 `code_review_match_mode_records.id`（来源血缘为 `source_instance + legacy_source_id`）/ 正式态 `code_review_formal_records.id` | 人工代码走查的单次散点、问题分布和去重使用当前冻结人工走查来源内的稳定记录 ID。 | 等价映射 | [ ] |
| CD-16A | 编码 | 人工走查日期 | 明细维度 | 数据采集平台 | 兼容态 `code_review_match_mode_records.code_walkthrough_date` / 正式态 `code_review_formal_records.code_walkthrough_date` | 单次散点、扫描、注释率和走查缺陷密度趋势的业务日期；缺失时只影响依赖日期的序列。 | 等价映射 | [ ] |
| CD-17 | 编码 | 被走查代码行数 | 原始度量 | 数据采集平台 | 兼容态 `code_review_match_mode_records.added_lines` / 正式态 `code_review_formal_records.added_lines` | 已完成人工走查记录的速率和千行密度输入。 | 等价映射 | [ ] |
| CD-18 | 编码 | 人工走查实际工时（分钟） | 原始度量 | 数据采集平台 | 兼容态 `code_review_match_mode_records.review_duration_minutes` / 正式态 `code_review_formal_records.review_duration_minutes` | 走查速率的分母；转换为小时需除以 60。 | 等价映射 | [ ] |
| CD-19 | 编码 | 人工走查有效问题数 | 计算输入 | 数据采集平台 | 兼容态 `code_review_match_mode_records.defect_count` / 正式态 `code_review_formal_records.defect_count` | 使用当前冻结人工走查读源已聚合的有效缺陷数。 | 等价映射 | [ ] |
| CD-20 | 编码 | 代码走查缺陷密度（千行代码缺陷率） | 计算值 | BI | CD-17、CD-19 | `有效走查问题数 × 1000 ÷ 被走查代码行数`；目标区间 `[3.00, 12.00]`（2026-09-09 更新）。 | BI计算 | [ ] |
| CD-21 | 编码 | 人工走查速率（行/小时） | 计算值 | BI | CD-17、CD-18；可直接提供 `reviewSpeedLocPerHour` | `被走查代码行数 ÷ (实际工时分钟 ÷ 60)`。 | BI计算 | [ ] |
| CD-22 | 编码 | 人工走查速率（KLOC/小时） | 计算值 | BI | CD-17、CD-18；可直接提供 `reviewSpeedKlocPerHour` | `被走查代码 KLOC ÷ (实际工时分钟 ÷ 60)`。具体展示单位待页面定稿时选择。 | BI计算 | [ ] |
| CD-23 | 编码 | 人工走查缺陷密度目标下限 | 质量目标 | BI 规则 | `BiCodingCalculator.MIN_REVIEW_DENSITY` | 固定为 `3.00`（2026-09-09 更新）。 | BI计算 | [ ] |
| CD-24 | 编码 | 人工走查缺陷密度目标上限 | 质量目标 | BI 规则 | `BiCodingCalculator.MAX_REVIEW_DENSITY` | 固定为 `12.00`（2026-09-09 更新）。 | BI计算 | [ ] |
| CD-25 | 编码 | 人工走查缺陷密度达标状态 | 计算值 | BI | CD-20、CD-23、CD-24 | `3.00 ≤ 人工走查缺陷密度 ≤ 12.00` 为达标。 | BI计算 | [ ] |
| CD-26 | 编码 | 模块人工走查缺陷密度 | 模块计算值 | BI | 兼容态 `code_review_match_mode_records.module_name` / 正式态 `code_review_formal_records.module_name`、CD-17、CD-19 | 按来源快照内模块名称维度计算 `模块有效走查问题数 × 1000 ÷ 模块被走查代码行数`。 | BI计算 | [ ] |
| CD-27 | 编码 | 模块人工走查速率 | 模块计算值 | BI | 兼容态 `code_review_match_mode_records.module_name` / 正式态 `code_review_formal_records.module_name`、CD-17、CD-18 | 按来源快照内模块名称维度计算 `模块被走查代码行数 ÷ 模块实际工时（小时）`。 | BI计算 | [ ] |
| CD-28 | 编码 | 单次人工走查散点横坐标 | 图表计算值 | BI | CD-17、CD-18 | 当前使用 CD-22 的 KLOC/小时。 | BI计算 | [ ] |
| CD-29 | 编码 | 单次人工走查散点纵坐标 | 图表计算值 | BI | CD-17、CD-19 | 值等于 CD-20。 | BI计算 | [ ] |
| CD-30 | 编码 | 代码规范问题数 | 类别计数 | 数据采集平台 | 兼容态 `code_review_match_mode_records.code_specification_count` / 正式态 `code_review_formal_records.code_specification_count` | 当前筛选范围内本类别问题计数。 | 等价映射 | [ ] |
| CD-31 | 编码 | 代码逻辑规范问题数 | 类别计数 | 数据采集平台 | 兼容态 `code_review_match_mode_records.code_logic_specification_count` / 正式态 `code_review_formal_records.code_logic_specification_count` | 当前筛选范围内本类别问题计数。 | 等价映射 | [ ] |
| CD-32 | 编码 | 性能规范问题数 | 类别计数 | 数据采集平台 | 兼容态 `code_review_match_mode_records.performance_specification_count` / 正式态 `code_review_formal_records.performance_specification_count` | 当前筛选范围内本类别问题计数。 | 等价映射 | [ ] |
| CD-33 | 编码 | 设计规范问题数 | 类别计数 | 数据采集平台 | 兼容态 `code_review_match_mode_records.design_specification_count` / 正式态 `code_review_formal_records.design_specification_count` | 当前筛选范围内本类别问题计数。 | 等价映射 | [ ] |
| CD-34 | 编码 | 其他代码走查问题数 | 类别计数 | 数据采集平台 | 兼容态 `code_review_match_mode_records.other_specification_count` / 正式态 `code_review_formal_records.other_specification_count` | 当前筛选范围内本类别问题计数。 | 等价映射 | [ ] |
| CD-35 | 编码 | 代码走查问题类别占比 | 计算值 | BI | CD-19、CD-30 至 CD-34 | `该类别问题数 ÷ 全部有效走查问题数`。 | BI计算 | [ ] |
| CD-36 | 编码 | 静态扫描状态 | 原始状态 | 数据采集平台 | 兼容态 `code_review_match_mode_records.scan_status` / 正式态 `code_review_formal_records.scan_status` | 必须区分未执行、执行失败、执行成功且无问题、执行成功有问题。 | 等价映射 | [ ] |
| CD-37 | 编码 | 静态扫描问题数 | 原始度量 | 数据采集平台 | 兼容态 `code_review_match_mode_records.scan_bug_count` / 正式态 `code_review_formal_records.scan_bug_count` | 当前筛选范围内静态扫描发现的问题数。 | 等价映射 | [ ] |
| CD-38 | 编码 | 代码注释率 | 原始/计算值 | 数据采集平台 | 兼容态 `code_review_match_mode_records.comment_rate` / 正式态 `code_review_formal_records.comment_rate/comment_rate_source` | 直接使用上游返回的注释率；兼容态不携带来源说明时只展示合法注释率，不臆造来源，质量目标未确认。 | 等价映射 | [ ] |
| CD-39 | 编码 | 代码走查缺陷密度趋势 | 时间桶计算值 | BI | CD-16A、CD-01、CD-17、CD-19 | 按人工走查日期的日/周时间桶使用 CD-20 总体公式；同一合并请求的被走查代码行只计一次，不把各记录密度直接相加。 | BI计算 | [ ] |

## 单元测试

| 编号 | 阶段 | 字段 | 类型 | 来源 | 原始字段 | 公式或规则 | 实现状态 | 人工核对 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| UT-01 | 单元测试 | 测试执行记录 ID | 明细维度 | CAT | 当前手册未提供 | 当前页面使用 CAT 聚合接口，不使用明细执行记录去重。 | 当前未使用 | [ ] |
| UT-02 | 单元测试 | 测试模块 ID | 维度 | CAT | `data.result[].id` | 模块级质量达成清单和功能下钻请求键。 | 等价映射 | [ ] |
| UT-02A | 单元测试 | 测试模块名称 | 显示维度 | CAT | `data.result[].moduleName`、`data.result[].name` | 两个名称字段必须一致，否则区块不完整。 | 等价映射 | [ ] |
| UT-03 | 单元测试 | 测试功能 ID | 维度 | CAT | `statisticsInfoList[].featureUniqueId` | 模块下功能明细的稳定 ID。 | 等价映射 | [ ] |
| UT-03A | 单元测试 | 测试功能名称 | 显示维度 | CAT | `statisticsInfoList[].name` | 由 UT-03 对应的功能显示名称。 | 等价映射 | [ ] |
| UT-04 | 单元测试 | 执行用例数 | CAT 候选聚合值 | CAT | `executedCaseCount` | 目标字段；整体、模块和功能层级及是否由 CAT 聚合，待接口契约确认。 | 上游缺失 | [ ] |
| UT-05 | 单元测试 | 通过用例数 | CAT 候选聚合值 | CAT | `passedCaseCount` | 目标字段；整体、模块和功能层级及是否由 CAT 聚合，待接口契约确认。 | 上游缺失 | [ ] |
| UT-06 | 单元测试 | 未通过用例数 | BI 展示派生值 | BI 数据适配/计算层 | UT-04、UT-05 | 仅用于页面展示，CAT 不需要返回该数量。 | 上游缺失 | [ ] |
| UT-07 | 单元测试 | 通过率 | CAT 聚合值 | CAT | `data.passRate` | 百分比范围校验后直接使用；当前精确分子分母由 CAT 内部聚合，BI 不反推。 | 等价映射 | [ ] |
| UT-08 | 单元测试 | 通过率目标 | 质量目标 | BI 规则 | `BiCatTestSourceAdapter.TARGET_RATE` | 固定为 `95.00%`。 | BI计算 | [ ] |
| UT-09 | 单元测试 | 整体达标状态 | 计算值 | CAT + BI | UT-07、UT-08 | `通过率 ≥ 95.00%` 为达标。 | BI计算 | [ ] |
| UT-10 | 单元测试 | 模块执行用例数 | CAT 候选聚合值 | CAT | `moduleId`、`executedCaseCount` | 目标字段；模块层返回方式待接口契约确认。 | 上游缺失 | [ ] |
| UT-11 | 单元测试 | 模块通过用例数 | CAT 候选聚合值 | CAT | `moduleId`、`passedCaseCount` | 目标字段；模块层返回方式待接口契约确认。 | 上游缺失 | [ ] |
| UT-12 | 单元测试 | 模块未通过用例数 | BI 展示派生值 | BI 数据适配/计算层 | UT-10、UT-11 | 仅用于页面展示，CAT 不需要返回该字段。 | 上游缺失 | [ ] |
| UT-13 | 单元测试 | 模块通过率 | CAT 聚合值 | CAT | `data.result[].testPassRate` | 百分比范围校验后直接使用。 | 等价映射 | [ ] |
| UT-14 | 单元测试 | 模块达标状态 | 模块计算值 | CAT + BI | UT-13、UT-08 | `模块通过率 ≥ 95.00%` 为达标。 | BI计算 | [ ] |
| UT-15 | 单元测试 | 功能执行用例数 | CAT 候选聚合值 | CAT | `moduleId`、`functionId`、`executedCaseCount` | 目标字段；功能层返回方式待接口契约确认。 | 上游缺失 | [ ] |
| UT-16 | 单元测试 | 功能通过用例数 | CAT 候选聚合值 | CAT | `moduleId`、`functionId`、`passedCaseCount` | 目标字段；功能层返回方式待接口契约确认。 | 上游缺失 | [ ] |
| UT-17 | 单元测试 | 功能未通过用例数 | BI 展示派生值 | BI 数据适配/计算层 | UT-15、UT-16 | 仅用于页面展示，CAT 不需要返回该字段。 | 上游缺失 | [ ] |
| UT-18 | 单元测试 | 功能通过率 | CAT 聚合值 | CAT | `statisticsInfoList[].testPassRate` | 百分比范围校验后直接使用。 | 等价映射 | [ ] |
| UT-19 | 单元测试 | 功能达标状态 | 功能计算值 | CAT + BI | UT-18、UT-08 | `功能通过率 ≥ 95.00%` 为达标。 | BI计算 | [ ] |

## 集成测试

| 编号 | 阶段 | 字段 | 类型 | 来源 | 原始字段 | 公式或规则 | 实现状态 | 人工核对 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| IT-01 | 集成测试 | 测试执行记录 ID | 明细维度 | CAT | 当前手册未提供 | 当前页面使用 CAT 聚合接口，不使用明细执行记录去重。 | 当前未使用 | [ ] |
| IT-02 | 集成测试 | 测试模块 ID | 维度 | CAT | 模块统计 `data.result[].id` | 模块级质量达成清单和功能下钻的稳定请求键。 | 等价映射 | [ ] |
| IT-02A | 集成测试 | 测试模块名称 | 显示维度 | CAT | `data.result[].moduleName`；冗余 `name` | 两个名称字段必须一致，否则区块不完整。 | 等价映射 | [ ] |
| IT-03 | 集成测试 | 测试功能 ID | 维度 | CAT | 功能统计 `featureUniqueId` | 功能明细的稳定 ID，不使用 `id` 作为回退。 | 等价映射 | [ ] |
| IT-03A | 集成测试 | 测试功能名称 | 显示维度 | CAT | 功能统计 `name` | 由 IT-03 对应的展示名称。 | 等价映射 | [ ] |
| IT-04 | 集成测试 | 执行用例数 | CAT 候选聚合值 | CAT | 当前手册未提供 | 整体执行用例数不能由功能数量或通过率反推。 | 上游缺失 | [ ] |
| IT-05 | 集成测试 | 通过用例数 | CAT 候选聚合值 | CAT | 当前手册未提供 | 整体通过用例数不能由功能数量或通过率反推。 | 上游缺失 | [ ] |
| IT-06 | 集成测试 | 未通过用例数 | BI 展示派生值 | BI 数据适配/计算层 | IT-04、IT-05 | 仅用于页面展示，CAT 不需要返回该数量；当前因 IT-04、IT-05 缺失而不可计算。 | 上游缺失 | [ ] |
| IT-07 | 集成测试 | 通过率 | CAT 聚合值 | CAT | 模块统计响应 `data.passRate` | 百分比范围校验后直接使用；BI 不反推分子分母。 | 等价映射 | [ ] |
| IT-08 | 集成测试 | 通过率目标 | 质量目标 | BI 规则 | `BiCatTestSourceAdapter.TARGET_RATE` | 固定为 `95.00%`。 | BI计算 | [ ] |
| IT-09 | 集成测试 | 整体达标状态 | 计算值 | CAT + BI | IT-07、IT-08 | `通过率 ≥ 95.00%` 为达标。 | BI计算 | [ ] |
| IT-10 | 集成测试 | 模块执行用例数 | CAT 候选聚合值 | CAT | 当前手册未提供 | `passFeatureCount + notPassFeatureCount` 是功能数量，不是模块执行用例数。 | 上游缺失 | [ ] |
| IT-11 | 集成测试 | 模块通过用例数 | CAT 候选聚合值 | CAT | 当前手册未提供 | `passFeatureCount` 是达到阈值的功能数量，不是模块通过用例数。 | 上游缺失 | [ ] |
| IT-12 | 集成测试 | 模块未通过用例数 | BI 展示派生值 | BI 数据适配/计算层 | IT-10、IT-11 | 仅用于页面展示，CAT 不需要返回该字段；当前因 IT-10、IT-11 缺失而不可计算。 | 上游缺失 | [ ] |
| IT-13 | 集成测试 | 模块通过率 | CAT 聚合值 | CAT | `data.result[].id/testPassRate` | 百分比范围校验后直接使用。 | 等价映射 | [ ] |
| IT-14 | 集成测试 | 模块达标状态 | 模块计算值 | CAT + BI | IT-13、IT-08 | `模块通过率 ≥ 95.00%` 为达标。 | BI计算 | [ ] |
| IT-15 | 集成测试 | 功能执行用例数 | CAT 候选聚合值 | CAT | 当前手册未提供 | 功能级响应只提供通过率，不能反推执行用例数。 | 上游缺失 | [ ] |
| IT-16 | 集成测试 | 功能通过用例数 | CAT 候选聚合值 | CAT | 当前手册未提供 | 功能级响应只提供通过率，不能反推通过用例数。 | 上游缺失 | [ ] |
| IT-17 | 集成测试 | 功能未通过用例数 | BI 展示派生值 | BI 数据适配/计算层 | IT-15、IT-16 | 仅用于页面展示，CAT 不需要返回该字段；当前因 IT-15、IT-16 缺失而不可计算。 | 上游缺失 | [ ] |
| IT-18 | 集成测试 | 功能通过率 | CAT 聚合值 | CAT | `statisticsInfoList[].featureUniqueId/testPassRate` | 百分比范围校验后直接使用。 | 等价映射 | [ ] |
| IT-19 | 集成测试 | 功能达标状态 | 功能计算值 | CAT + BI | IT-18、IT-08 | `功能通过率 ≥ 95.00%` 为达标。 | BI计算 | [ ] |

## 系统测试

| 编号 | 阶段 | 字段 | 类型 | 来源 | 原始字段 | 公式或规则 | 实现状态 | 人工核对 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| ST-01 | 系统测试 | 缺陷 ID | 明细维度 | 数据采集平台 | `issue_fact.issue_id` | 产品整体去重使用稳定缺陷 ID。 | 等价映射 | [ ] |
| ST-02 | 系统测试 | 缺陷严重级别 | 明细维度 | 数据采集平台 | `issue_fact.severity_level` | `LEVEL1/LEVEL2/LEVEL3` 对应一级、二级、三级，不能与 P1/P2 优先级混用。 | 等价映射 | [ ] |
| ST-03 | 系统测试 | 缺陷优先级 | 明细维度 | 数据采集平台 | `issue_fact.priority_level` | P1、P2、P3 是优先级，不能与一级/二级/三级严重级别混用。 | 等价映射 | [ ] |
| ST-04 | 系统测试 | 当前修复状态 | 明细维度 | 数据采集平台 | `issue_fact.is_fixed` | `true` 为已修复，`false` 为当前未修复。 | 等价映射 | [ ] |
| ST-05 | 系统测试 | 有效缺陷总数 | 计算输入 | BI | ST-01；查询已排除 `deleted/is_excluded`、建议项 | 对有效缺陷按产品缺陷 ID 去重计数。 | BI计算 | [ ] |
| ST-06 | 系统测试 | 已修复有效缺陷数 | 计算输入 | BI | ST-01、ST-04、排除标识 | 有效缺陷中 `isFixed=true` 的去重缺陷数。 | BI计算 | [ ] |
| ST-07 | 系统测试 | 当前未修复有效缺陷数 | 计算输入 | BI | ST-05、ST-06 | `有效缺陷总数 - 已修复有效缺陷数`。 | BI计算 | [ ] |
| ST-08 | 系统测试 | 整体修复率 | 计算值 | BI | ST-05、ST-06 | `已修复有效缺陷数 ÷ 有效缺陷总数`。 | BI计算 | [ ] |
| ST-09 | 系统测试 | 一级缺陷数量 | 计算输入 | BI | ST-01、ST-02、排除标识 | 有效缺陷中 `severityLevel=一级` 的去重缺陷数。 | BI计算 | [ ] |
| ST-10 | 系统测试 | 一级缺陷修复数 | 计算输入 | BI | ST-01、ST-02、ST-04、排除标识 | 有效一级缺陷中 `isFixed=true` 的去重缺陷数。 | BI计算 | [ ] |
| ST-11 | 系统测试 | 一级缺陷未修复数 | 计算输入 | BI | ST-09、ST-10 | `一级缺陷数量 - 一级缺陷修复数`。 | BI计算 | [ ] |
| ST-12 | 系统测试 | 一级缺陷修复率 | 计算值 | BI | ST-09、ST-10 | `一级缺陷修复数 ÷ 一级缺陷数量`。 | BI计算 | [ ] |
| ST-13 | 系统测试 | 一级缺陷修复率目标 | 质量目标 | BI 规则 | `BiSystemTestCalculator.LEVEL_ONE_TARGET` | 固定为 `100.00%`。 | BI计算 | [ ] |
| ST-14 | 系统测试 | 一级缺陷达标状态 | 计算值 | BI | ST-12、ST-13 | `一级缺陷修复率 = 100.00%` 为达标。 | BI计算 | [ ] |
| ST-15 | 系统测试 | 二级缺陷数量 | 计算输入 | BI | ST-01、ST-02、排除标识 | 有效缺陷中 `severityLevel=二级` 的去重缺陷数。 | BI计算 | [ ] |
| ST-16 | 系统测试 | 二级缺陷修复数 | 计算输入 | BI | ST-01、ST-02、ST-04、排除标识 | 有效二级缺陷中 `isFixed=true` 的去重缺陷数。 | BI计算 | [ ] |
| ST-17 | 系统测试 | 二级缺陷未修复数 | 计算输入 | BI | ST-15、ST-16 | `二级缺陷数量 - 二级缺陷修复数`。 | BI计算 | [ ] |
| ST-18 | 系统测试 | 三级缺陷数量 | 计算输入 | BI | ST-01、ST-02、排除标识 | 有效缺陷中 `severityLevel=三级` 的去重缺陷数。 | BI计算 | [ ] |
| ST-19 | 系统测试 | 三级缺陷修复数 | 计算输入 | BI | ST-01、ST-02、ST-04、排除标识 | 有效三级缺陷中 `isFixed=true` 的去重缺陷数。 | BI计算 | [ ] |
| ST-20 | 系统测试 | 三级缺陷未修复数 | 计算输入 | BI | ST-18、ST-19 | `三级缺陷数量 - 三级缺陷修复数`。 | BI计算 | [ ] |
| ST-21 | 系统测试 | P1 缺陷数量 | 计算输入 | BI | ST-01、ST-03、排除标识 | 有效缺陷中 `priority=P1` 的去重缺陷数。 | BI计算 | [ ] |
| ST-22 | 系统测试 | P1 缺陷修复数 | 计算输入 | BI | ST-01、ST-03、ST-04、排除标识 | 有效 P1 缺陷中 `isFixed=true` 的去重缺陷数。 | BI计算 | [ ] |
| ST-23 | 系统测试 | P1 缺陷未修复数 | 计算输入 | BI | ST-21、ST-22 | `P1 缺陷数量 - P1 缺陷修复数`。 | BI计算 | [ ] |
| ST-24 | 系统测试 | P1 缺陷修复率 | 计算值 | BI | ST-21、ST-22 | `P1 缺陷修复数 ÷ P1 缺陷数量`。 | BI计算 | [ ] |
| ST-25 | 系统测试 | P1 缺陷修复率目标 | 质量目标 | BI 规则 | `BiSystemTestCalculator.P1_TARGET` | 固定为不低于 `90.00%`。 | BI计算 | [ ] |
| ST-26 | 系统测试 | P1 缺陷达标状态 | 计算值 | BI | ST-24、ST-25 | `P1 缺陷修复率 ≥ 90.00%` 为达标。 | BI计算 | [ ] |
| ST-27 | 系统测试 | P2 缺陷数量 | 计算输入 | BI | ST-01、ST-03、排除标识 | 有效缺陷中 `priority=P2` 的去重缺陷数。 | BI计算 | [ ] |
| ST-28 | 系统测试 | P2 缺陷修复数 | 计算输入 | BI | ST-01、ST-03、ST-04、排除标识 | 有效 P2 缺陷中 `isFixed=true` 的去重缺陷数。 | BI计算 | [ ] |
| ST-29 | 系统测试 | P2 缺陷未修复数 | 计算输入 | BI | ST-27、ST-28 | `P2 缺陷数量 - P2 缺陷修复数`。 | BI计算 | [ ] |
| ST-30 | 系统测试 | P2 缺陷修复率 | 计算值 | BI | ST-27、ST-28 | `P2 缺陷修复数 ÷ P2 缺陷数量`。 | BI计算 | [ ] |
| ST-31 | 系统测试 | P2 缺陷修复率目标 | 质量目标 | BI 规则 | `BiSystemTestCalculator.P2_TARGET` | 固定为不低于 `80.00%`。 | BI计算 | [ ] |
| ST-32 | 系统测试 | P2 缺陷达标状态 | 计算值 | BI | ST-30、ST-31 | `P2 缺陷修复率 ≥ 80.00%` 为达标。 | BI计算 | [ ] |
| ST-33 | 系统测试 | 测试轮次 ID | 明细维度 | 数据采集平台 | `issue_scope_members.id` | 逐轮趋势和排序使用稳定 ID。 | 等价映射 | [ ] |
| ST-34 | 系统测试 | 测试轮次名称 | 显示维度 | 数据采集平台 | `issue_scope_members.display_name` | 仅作显示；事实匹配使用同成员的 `source_value` 对应 `issue_fact.testing_phase`。 | 等价映射 | [ ] |
| ST-35 | 系统测试 | 测试轮次顺序 | 排序维度 | 数据采集平台 | `issue_scope_members.sort_order` | 不按轮次名称字符串排序。 | 等价映射 | [ ] |
| ST-36 | 系统测试 | 轮次一级缺陷数量 | 轮次聚合值 | BI | ST-33、ST-02、ST-01 | 按轮次和一级严重级别聚合。 | BI计算 | [ ] |
| ST-37 | 系统测试 | 轮次二级缺陷数量 | 轮次聚合值 | BI | ST-33、ST-02、ST-01 | 按轮次和二级严重级别聚合。 | BI计算 | [ ] |
| ST-38 | 系统测试 | 轮次三级缺陷数量 | 轮次聚合值 | BI | ST-33、ST-02、ST-01 | 按轮次和三级严重级别聚合。 | BI计算 | [ ] |
| ST-39 | 系统测试 | 轮次提交总数 | 轮次聚合值 | BI | ST-33、ST-01 | 当前轮次有效缺陷 ID 去重数；必须同时等于 ST-36+ST-37+ST-38 和 ST-40+ST-41，否则该轮次数据不完整。 | BI计算 | [ ] |
| ST-40 | 系统测试 | 轮次已关闭数 | 轮次聚合值 | BI | ST-33、ST-04、ST-01 | 当前轮次 `is_fixed=true` 的去重缺陷数。 | BI计算 | [ ] |
| ST-41 | 系统测试 | 轮次未关闭数 | 轮次聚合值 | BI | ST-33、ST-04、ST-01 | 当前轮次 `is_fixed=false` 的去重缺陷数。 | BI计算 | [ ] |
| ST-42 | 系统测试 | 轮次关闭率 | 计算值 | BI | ST-40、ST-41 | `轮次已关闭数 ÷ (轮次已关闭数 + 轮次未关闭数)`；分母必须等于 ST-39。 | BI计算 | [ ] |
| ST-43 | 系统测试 | 模块有效缺陷总数 | 模块计算输入 | BI | `issue_fact.module_names`、ST-01 | 按来源快照内模块名称成员计数；逗号分隔的多模块分别归属，同一缺陷在同一模块只计一次，缺失值进入“未标注模块”。 | BI计算 | [ ] |
| ST-44 | 系统测试 | 模块已修复缺陷数 | 模块计算输入 | BI | `issue_fact.module_names`、ST-01、ST-04 | 模块有效缺陷中 `isFixed=true` 的数量。 | BI计算 | [ ] |
| ST-45 | 系统测试 | 模块当前未修复缺陷数 | 模块计算输入 | BI | ST-43、ST-44 | `模块有效缺陷总数 - 模块已修复缺陷数`。 | BI计算 | [ ] |
| ST-46 | 系统测试 | 模块修复率 | 模块计算值 | BI | ST-43、ST-44 | `模块已修复缺陷数 ÷ 模块有效缺陷总数`。 | BI计算 | [ ] |
| ST-47 | 系统测试 | 模块一级缺陷数量 | 模块聚合值 | BI | `issue_fact.module_names`、ST-01、ST-02 | 模块有效缺陷中 `severityLevel=一级` 的数量。 | BI计算 | [ ] |
| ST-48 | 系统测试 | 模块二级缺陷数量 | 模块聚合值 | BI | `issue_fact.module_names`、ST-01、ST-02 | 模块有效缺陷中 `severityLevel=二级` 的数量。 | BI计算 | [ ] |
| ST-49 | 系统测试 | 模块三级缺陷数量 | 模块聚合值 | BI | `issue_fact.module_names`、ST-01、ST-02 | 模块有效缺陷中 `severityLevel=三级` 的数量。 | BI计算 | [ ] |
| ST-49A | 系统测试 | 模块一级缺陷修复率 | 模块计算值 | BI | `issue_fact.module_names/severity_level/is_fixed` | `模块已修复一级缺陷数 ÷ 模块一级缺陷总数`；分母为 0 时不适用。 | BI计算 | [ ] |
| ST-49B | 系统测试 | 模块 P1 缺陷修复率 | 模块计算值 | BI | `issue_fact.module_names/priority_level/is_fixed` | `模块已修复 P1 缺陷数 ÷ 模块 P1 缺陷总数`；分母为 0 时不适用。 | BI计算 | [ ] |
| ST-49C | 系统测试 | 模块 P2 缺陷修复率 | 模块计算值 | BI | `issue_fact.module_names/priority_level/is_fixed` | `模块已修复 P2 缺陷数 ÷ 模块 P2 缺陷总数`；分母为 0 时不适用。 | BI计算 | [ ] |
| ST-50 | 系统测试 | 当前指派人快照维度 | 维度 | 数据采集平台 | `issue_fact.assignee_name` | 表示 GitLab 当前缺陷责任人，用于同一人员下展示总数和待修复数；缺失值进入“未指派”。 | 等价映射 | [ ] |
| ST-50A | 系统测试 | 实际修复人快照维度 | 维度 | 数据采集平台 | `issue_fact.fix_user` | 从合法 `### 1、修复状态` 评论作者提取，只表示实际修复历史归属；不用于当前待修复负荷，当前人员负荷图不消费。 | 当前未使用 | [ ] |
| ST-51 | 系统测试 | 指派人缺陷总数 | 人员聚合值 | BI | ST-50、ST-01 | 按当前指派人快照维度聚合有效缺陷数。 | BI计算 | [ ] |
| ST-52 | 系统测试 | 指派人待修复缺陷数 | 人员聚合值 | BI | ST-50、ST-01、ST-04 | 按当前指派人聚合 `is_fixed=false` 的有效缺陷数；为重点显示值。 | BI计算 | [ ] |
| ST-53 | 系统测试 | 指派人已修复缺陷数 | 人员聚合值 | BI | ST-51、ST-52 | `指派人缺陷总数 - 指派人待修复缺陷数`，与 ST-51、ST-52 使用同一指派人范围。 | BI计算 | [ ] |
| ST-54 | 系统测试 | 缺陷原因大类名称 | 维度 | BI | `issue_fact.reason_category/label_names` + `BiSystemTestCauseClassifier` | 按 `bi-system-test-cause-v1` 词典映射为需求问题、设计问题、编码规范、打包问题、依赖问题、精度问题；无命中保留“未归类”。 | BI计算 | [ ] |
| ST-55 | 系统测试 | 缺陷原因大类缺陷数 | 类别计数 | BI | ST-54、ST-01 | 每个缺陷在同一原因大类最多计一次。 | BI计算 | [ ] |
| ST-56 | 系统测试 | 缺陷原因大类占比 | 计算值 | BI | ST-55、ST-05 | `原因大类缺陷数 ÷ 有效缺陷总数`。 | BI计算 | [ ] |
| ST-57 | 系统测试 | 缺陷原因子类名称 | 维度 | BI | `issue_fact.reason_category/label_names` + `BiSystemTestCauseClassifier` | 使用同一版本化词典产出子类；同一缺陷可命中不同子类，无命中保留“未归类”。 | BI计算 | [ ] |
| ST-58 | 系统测试 | 缺陷原因子类缺陷数 | 类别计数 | BI | ST-57、ST-01 | 每个缺陷在同一大类/子类组合最多计一次。 | BI计算 | [ ] |
| ST-59 | 系统测试 | 缺陷原因子类占比 | 计算值 | BI | ST-58、ST-05 | `原因子类缺陷数 ÷ 有效缺陷总数`；子类使用柱状图。 | BI计算 | [ ] |
| ST-60 | 系统测试 | 未归类原因缺陷数 | 类别计数 | BI | ST-54、ST-57、ST-01 | 未命中版本化原因词典的缺陷独立保留为“未归类”，不丢弃也不并入其它类别。 | BI计算 | [ ] |
| ST-61 | 系统测试 | 是否申请延期 | 明细维度 | 数据采集平台 | `issue_fact.delay_issue` | 只有 `delay_issue=true` 的有效缺陷进入申请延期缺陷情况；不再要求不存在的 `delayApplicationStatus`。 | 等价映射 | [ ] |
| ST-62 | 系统测试 | 延期原因 | 明细维度 | 数据采集平台 / BI | `issue_fact.delay_cause/delay_reason/label_names` + `BiSystemTestDelayCauseClassifier` | 按 `bi-system-test-delay-cause-v1` 提取技术卡点、方案卡点、资源卡点、数据异常、算法问题、机制问题、计算效率；无命中保留“未归类延期原因”。 | BI计算 | [ ] |
| ST-63 | 系统测试 | 延期缺陷数量 | 计算值 | BI | ST-01、ST-02、ST-61、ST-62 | 对 `delay_issue=true` 的有效缺陷按“延期原因 × 严重级别”聚合；同一缺陷在同一原因只计一次。 | BI计算 | [ ] |
跨阶段单元/集成/系统测试用例对比已确认不适用：系统测试没有执行用例数、通过用例数或通过率，不能用缺陷议题数或缺陷修复率补齐。因此不登记跨阶段对比字段、不实现三类测试对比图；历史编号 `ST-70`～`ST-72` 仅用于追溯，不属于当前字段或接口契约。

## 待联调与待补充事项

| 事项 | 当前状态 |
| --- | --- |
| 数据采集平台面向 BI 的进程内查询端口、字段映射、样例和来源版本 | 需求、设计、编码和系统测试已接入并按本表记录真实字段；剩余缺口见各行“上游缺失/待确认” |
| 系统测试修复人及原因评论完整性 | 本地外网镜像只适合验证计算逻辑；待打包进入内网并完成 GitLab 评论同步后，核对合法修复状态评论、`fix_user`、`reason_category` 和原因词典命中率 |
| CAT 集成测试接口、认证、映射、公式和来源一致性 | 四个查询接口已取得；待补基地址/认证、三层用例计数或基础记录、通过率精确语义、产品版本/阶段映射、原子来源身份和稳定分页规则 |
| CAT 单元测试接口、字段和来源一致性 | 当前手册未覆盖，待 CAT 提供独立契约；禁止复用集成测试接口或平台数据补齐 |
| 编码三类事实的内网覆盖率 | 代码规模类继续读取 `code_review_match_mode_records`，提交类读取 `merge_request_commit_fact`，人工走查类复用 `code_review_match_mode_records`；外网已完成来源隔离、去重和计算回归，待内网兼容模式常开环境核对 GitLab 提交与 `spider_crowncad_data` 的真实覆盖率 |
