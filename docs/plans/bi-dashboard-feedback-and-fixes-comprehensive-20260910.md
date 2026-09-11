# BI 看板实测问题修复与全看板业务说明综合方案

> **文档版本**：v2.1（2026-09-10 执行完成并回写实测结果；v2.0 为审查修订，v1.0 为初版）
>
> **文档性质**：全新整合独立专项方案（涵盖近期测试反馈的所有前端修复、新增需求与待决策调研项）
>
> **当前状态**：P0-1〜P0-5、P1-1〜P1-3 已全部执行并验证全绿；浏览器真实界面四项验收已于 2026-09-11 完成（见第六部分第 5 条、8.7），提交待并行单元先入库（见 8.8）
>
> **编制依据**：测试实测反馈、领导现场沟通要点、《BI看板数据来源与计算口径核对表.md》、代码库最新真实状态
> **审查对象**：研发主管、测试负责人、项目经理

---

## 目录

1. [方案背景与设计原则](#一-方案背景与设计原则)
2. [全部问题与处置状态总览矩阵](#二-全部问题与处置状态总览矩阵)
3. [第一部分：已落地的前端修复方案（共 10 项）](#三-第一部分已落地的前端修复方案)
   - 3.1 [【BUG-01】图表下载按钮无法使用与提示优化](#31-bug-01-图表下载按钮无法使用与提示优化)
   - 3.2 [【BUG-02】编码阶段代码增量趋势切换粒度跳顶修复](#32-bug-02-编码阶段代码增量趋势切换粒度跳顶修复)
   - 3.3 [【BUG-03】系统测试顶部 7 个指标分散重构为紧凑单行](#33-bug-03-系统测试顶部-7-个指标分散重构为紧凑单行)
   - 3.4 [【BUG-04】系统测试模块修复率矩阵显式体现遗留缺陷数](#34-bug-04-系统测试模块修复率矩阵显式体现遗留缺陷数)
   - 3.5 [【BUG-05】需求与设计阶段评审问题分布饼图高度严格对齐](#35-bug-05-需求与设计阶段评审问题分布饼图高度严格对齐)
   - 3.6 [【BUG-06】编码阶段三大趋势图表按日/按周粒度解耦](#36-bug-06-编码阶段三大趋势图表按日按周粒度解耦)
   - 3.7 [【ISSUE-01】人工代码走查质量 Tooltip 增加新增代码行](#37-issue-01-人工代码走查质量-tooltip-增加新增代码行)
   - 3.8 [【ISSUE-02】下线删除编码阶段“代码提交频次时间分布”图表](#38-issue-02-下线删除编码阶段代码提交频次时间分布图表)
   - 3.9 [【ISSUE-03】系统测试各轮次缺陷修复情况拼音乱序修复为自然升序](#39-issue-03-系统测试各轮次缺陷修复情况拼音乱序修复为自然升序)
   - 3.10 [【ISSUE-04】系统测试各模块修复率达成情况排序修正](#310-issue-04-系统测试各模块修复率达成情况排序修正)
4. [第二部分：全看板图表业务说明与问号 Tooltip 落地（新增需求）](#四-第二部分全看板图表业务说明与问号-tooltip-落地新增需求)
   - 4.1 [交互规范与视觉设计（对标“是否达标”列头）](#41-交互规范与视觉设计)
   - 4.2 [全看板图表业务说明与口径词条权威字典（26 条词条 / 23 个卡片实例）](#42-全看板图表业务说明与口径词条权威字典26-条词条--23-个卡片实例)
5. [第三部分：待决策调研项分析与改造建议（暂不改代码）](#五-第三部分待决策调研项分析与改造建议暂不改代码)
   - 5.1 [编码阶段“静态代码扫描结果”图表业务价值评估与建议](#51-编码阶段静态代码扫描结果图表业务价值评估与建议)
   - 5.2 [系统测试“按指派人统计缺陷数”支持按模块/组织架构查看调研](#52-系统测试按指派人统计缺陷数支持按模块组织架构查看调研)
6. [第四部分：自动化测试与质量验收结果（v2.0 实测）](#六-第四部分自动化测试与质量验收结果v20-实测)
7. [第五部分：审查要点与建议行动](#七-第五部分审查要点与建议行动)
8. [第六部分：v2.0 审查结论与完善执行计划（已执行，见 8.7；待提交清单见 8.8）](#八-第六部分v20-审查结论与完善执行计划)

---

## 一、 方案背景与设计原则

### 1.1 背景说明
在近期 BI 看板的集成测试与用户试用过程中，陆续发现了若干前端体验问题、交互瑕疵及排序逻辑缺陷。同时，领导提出希望在每个图表卡片增加业务说明问号图标，以便业务人员快速理解图表的达标门槛与计算口径。

为了保护后端稳定性与基线环境，本方案以**“前端优先闭环”**为原则：对于前端能通过现有 DTO 数据解决的缺陷立即完成修复与测试，对于涉及跨系统边界或业务方向不确定的事项展开深度调查并形成建议，汇总成本综合方案供各方统一审查；仅保留 1.2 第 2 条列出的三项经审定的后端例外。

### 1.2 核心实施准则
1. **前端优先闭环原则**：凡能由前端用现有响应字段与计算能力解决的缺陷，一律在前端闭环，不扩张后端协议；
2. **已确认的三项后端例外**（经用户 2026-09-10 审定，见 8.3 D1/D3/D4，不再视为违反准则）：
   - **P0-1**：解除后端编译阻塞（统计看板并行工作流的 2 处 `int`→`Long`，属本方案范围外的最小必要改动）；
   - **P0-5**：清理频次图表在下线时漏删的后端下载模板白名单 `BiDownloadAuthorizationService.PAGE_TEMPLATES`（死代码）；
   - **P1-1**：Excel 导出增补业务口径说明行（新增可空请求字段 `explanation`，不改任何查询与 DTO 语义）；
3. **单一事实源原则**：所有业务解释与指标口径严格依据已由用户人工确认的《BI看板数据来源与计算口径核对表.md》，杜绝随意发挥；
4. **零回归与自动化保障**：每项修改均配备对应的自动化单元测试与类型定义，确保快速套件 100% 绿色通过。

---

## 二、 全部问题与处置状态总览矩阵

| 序号 | 问题/需求编号 | 问题概述 | 分类 | 处置状态 | 审查重点 |
|:---:|---|---|:---:|:---:|---|
| **1** | **BUG-01** | 图表的下载按钮点击无法弹出菜单 | 纯前端缺陷 | **已修复并验证** | Dropdown 与 Tooltip 事件嵌套解耦 |
| **2** | **BUG-02** | 编码代码增量趋势点击按周/按日跳转至页面顶部 | 纯前端缺陷 | **已修复并验证** | 路由滚动行为与记忆还原 |
| **3** | **BUG-03** | 系统测试顶部 7 个指标分散为两行，需单行呈现 | 布局与视觉 | **已修复并验证** | 目标与概览整合为紧凑横向条 |
| **4** | **BUG-04** | 系统测试模块修复率表格未体现遗留缺陷数 | 图表与展示 | **已修复并验证** | 矩阵表格、Tooltip 与导出显式呈现 |
| **5** | **BUG-05** | 需求与设计阶段评审问题类别分布饼图未与右侧图表对齐高度 | 布局与视觉 | **已修复并验证** | 统一为 430px，继承 Grid 弹性拉伸 |
| **6** | **BUG-06** | 编码阶段代码增量趋势按日/按周按钮联动影响整页其他图表 | 状态与架构 | **已修复并验证** | 浏览器内存周聚合，各图表独立状态 |
| **7** | **ISSUE-01** | 人工代码走查质量柱状图 Tooltip 需体现新增代码行 | 图表与展示 | **已修复并验证** | 内存关联 `moduleIncrements`，无侵入后端 |
| **8** | **ISSUE-02** | 编码阶段代码提交频次时间分布与提交趋势重叠，需删除 | 功能收敛 | **v2.0 已彻底清理** | 页面卡片＋图表类＋联合类型＋周聚合函数＋后端模板白名单＋两处测试计数全部删除，全库零残留 |
| **9** | **ISSUE-03** | 系统测试各轮次缺陷修复情况排序乱序（二、六、三、四...） | 算法缺陷 | **已修复并验证** | 解析后端整数 `roundOrder`，自然轮次升序 |
| **10** | **ISSUE-04** | 系统测试各模块修复率达成情况整体修复率 0% 异常置顶 | 算法缺陷 | **v2.0 已按证据修正** | 阈值 `>= 95` 纠正保留；“0% 沉底”业务前提已推翻（0% ＝有缺陷且全部未修复，属最高风险），仅 `null` 在默认异常优先视图置底；页面内联副本已删除，收敛为单一权威实现 |
| **11** | **ISSUE-05** | 给全看板每个图表增加业务说明问号图标 Tooltip（对标“是否达标”） | 新增需求 | **v2.0 已补齐** | **26 条词条 / 23 个卡片实例**（非 21）；popper 样式改由非 scoped 样式块承载，320px 与折行已生效 |
| **12** | **SURVEY-01** | 编码阶段静态代码扫描结果图表是否有业务意义调研 | 专项调研 | **已完成调研评估** | 评估量纲混淆硬伤，给出下线/重构建议 |
| **13** | **SURVEY-02** | 系统测试按指派人统计缺陷数按模块/组织架构查看调研 | 架构调研 | **结论已更正（v2.0）** | 第一阶段“纯前端模块筛选”经实证**数据上不可行**：DTO 无“人×模块”交叉明细，必须后端供数；两阶段合并为单一后端任务，待领导裁定优先级 |

---

## 三、 第一部分：已落地的前端修复方案

### 3.1 【BUG-01】图表下载按钮无法使用与提示优化
* **问题现象**：在图表卡片右上角点击下载按钮时，下拉菜单无法弹出，导致 PNG 图片与 Excel 数据表均无法导出。
* **技术根因**：在 `BiChartPanel.vue` 中，外层 `<el-tooltip>` 直接包裹了 `<el-dropdown>` 的触发插槽。Element Plus 的 Tooltip popper 包装层拦截了 DOM 点击事件，阻断事件向内传播。
* **修复方案**：
  1. 将 `<el-tooltip>` 与 `<el-dropdown>` 的嵌套关系翻转，将 Tooltip 置于最外层，内部触发器保持为标准原生 `<button>`；
  2. 针对账号无下载权限的情况，Tooltip 明确提示“当前账号暂无下载权限”；对于无数据状态提示“暂无可下载数据”，提升交互友好性。
* **改动文件**：`frontend/src/features/bi-dashboard/components/BiChartPanel.vue`

### 3.2 【BUG-02】编码阶段代码增量趋势切换粒度跳顶修复
* **问题现象**：在编码阶段向下滚动后，点击代码增量趋势的“按日/按周”，页面瞬间滚回最顶部。
* **技术根因**：
  1. `frontend/src/router.ts` 的 `scrollBehavior` 硬编码了 `return { top: 0 };`，同路由仅变更 query 时强制复位；
  2. 控件绑定了路由 query，触发了整页向后端重拉数据。
* **修复方案**：
  1. 在 `router.ts` 中判断 `to.path === from.path` 时返回 `false`，同路径 query 变更严格保持视口原位；
  2. 解绑全局路由参数，将颗粒度收敛为前端本地即时聚合状态。
* **改动文件**：`frontend/src/router.ts`、`frontend/src/features/bi-dashboard/BiDashboardView.vue`

### 3.3 【BUG-03】系统测试顶部 7 个指标分散重构为紧凑单行
* **问题现象**：原界面顶部“质量目标”与“测试概览”纵向分为两行渲染，占用较多竖向空间。
* **修复方案**：
  1. 废除上下堆叠的双组件模式，在 `SystemTestStageContent.vue` 中重构为统一样式的紧凑横向指标条（`bi-system-test-metric-bar`）；
  2. **左侧（质量目标）**：并列排布一级缺陷（0个）、P1 修复率（≥90%）、P2 修复率（≥80%）3 项指标，保留微章标记；
  3. **中间**：设置 1px 细分割线（Divider）；
  4. **右侧（缺陷概览）**：并列排布累计发现数、已修复数、当前未修复数（未修复 > 0 橙红警示）、整体修复率 4 项指标；
  5. 整体高度控制在 82px 左右，主流分辨率保持单行不换行。
* **改动文件**：`frontend/src/features/bi-dashboard/stages/SystemTestStageContent.vue`

### 3.4 【BUG-04】系统测试模块修复率矩阵显式体现遗留缺陷数
* **问题现象**：各模块修复率矩阵只显示了修复率百分比进度条，业务管理人员无法直观获知各模块还有多少个具体未修复缺陷。
* **修复方案**：
  1. 在 `ModuleRepairRow` 类型中声明 `openCount?: number` 与 `totalCount?: number`；
  2. 从 `SystemTestStageContent.vue` 的 `modules` 数据中提取对应数值透传至图表数据；
  3. 在 `ModuleRepairMatrixChart.ts` 顶部绘制“遗留缺陷（未修复数）”表头列，并在每行显著渲染胶囊标签（未修复 > 0 显示橙红色如 `5 遗留`，未修复 = 0 显示绿色 `0 遗留`）；
  4. Tooltip 同步展示“遗留缺陷数：X 个 / 累计缺陷数：Y 个”，Excel 导出追加相应列。
* **改动文件**：`ModuleRepairMatrixChart.ts`、`chart-data.ts`、`SystemTestStageContent.vue`

### 3.5 【BUG-05】需求与设计阶段评审问题分布饼图高度严格对齐
* **问题现象**：需求和设计评审页面第一行左侧饼图卡片比右侧质量卡片矮近 100px，导致底部露出一大片空白。
* **技术根因**：`BiChartPanel.vue` 中样式 `.bi-chart-panel--pie { align-self: start; height: auto; }` 破坏了 Grid 网格行拉伸。
* **修复方案**：
  1. 移除 `.bi-chart-panel--pie` 上的 `align-self: start`，恢复继承 Grid item 的自然纵向拉伸（`height: 100%`）；
  2. 将两卡片高度显式统一设置为 `:height="430"`；空数据占位增加 `flex: 1` 保证垂直居中。
* **改动文件**：`BiChartPanel.vue`、`ReviewStageContent.vue`

### 3.6 【BUG-06】编码阶段三大趋势图表按日/按周粒度解耦
* **问题现象**：在代码增量趋势卡片切换“按日/按周”，提交趋势和提交频次图表也跟着一起联动切换。
* **修复方案**：
  1. 在 `frontend/src/features/bi-dashboard/data/aggregation.ts` 中实现周一时间桶聚合算法 `aggregateCodeTrendByWeek` 与 `aggregateSubmissionTrendByWeek`；
  2. 在 `CodingStageContent.vue` 中为各图表建立独立的响应式状态（`codeTrendGranularity`、`submissionTrendGranularity`）；
  3. 分别在各自卡片的 Actions 插槽配置独立分段单选控件，内存即时计算，互不干扰且毫秒级响应。
* **改动文件**：`aggregation.ts`、`CodingStageContent.vue`

### 3.7 【ISSUE-01】人工代码走查质量 Tooltip 增加新增代码行
* **需求与现象**：鼠标悬停在“各模块人工代码走查质量”柱状图时，需要了解该模块当前周期的实际新增代码规模。
* **实现方案（零修改后端）**：
  1. 充分挖掘前端数据源，页面 DTO 中已具备 `moduleIncrements: Array<{ module: BiSourceDimension; addedLines: number }>`；
  2. 在 `CodingStageContent.vue` 中构建 `moduleIncrementsMap`，计算 `moduleReviews` 时映射对应模块的 `addedLines`；
  3. `ReviewQualityDualPanelChart.ts` 的 `tooltip.formatter` 中增加展示 `新增代码：${item.addedLines.toLocaleString()} 行`；
  4. 当存在该字段时，Excel 导出自动追加“新增代码量 (行)”列。
* **改动文件**：`chart-data.ts`、`ReviewQualityDualPanelChart.ts`、`CodingStageContent.vue`

### 3.8 【ISSUE-02】下线删除编码阶段“代码提交频次时间分布”图表
* **背景与决策**：用户明确指示该图表的数据与“提交趋势”中的合并请求数/提交数展示重叠，建议删除。
* **实现方案（v2.0 按 D3 彻底清理，不留死代码）**：
  1. 从 `CodingStageContent.vue` 模板中移除该 `<BiChartPanel>` 卡片与相关状态；
  2. 删除图表类 `charts/types/SubmissionFrequencyBarChart.ts`、`types/index.ts` 导出、`data/types.ts` 的 `'submission-frequency-bar'` 联合类型成员；
  3. 删除仅服务于该图表的周聚合函数 `aggregateFrequenciesByWeek` 及其单测用例；
  4. 删除后端 `BiDownloadAuthorizationService.PAGE_TEMPLATES.coding` 中的模板白名单条目；
  5. 同步调整两处图表清单测试计数（`excel-table.test.ts`、`chart-contract.test.ts`）。
* **改动文件**：`CodingStageContent.vue`、`CodingStageContent.test.ts`、`charts/types/SubmissionFrequencyBarChart.ts`（删）、`types/index.ts`、`data/types.ts`、`aggregation.ts`、`aggregation.test.ts`、`chart-contract.test.ts`、`excel-table.test.ts`、`BiDownloadAuthorizationService.java`
* **验收**：全库 `SubmissionFrequency`/`submission-frequency-bar` 零引用残留。

### 3.9 【ISSUE-03】系统测试各轮次缺陷修复情况拼音乱序修复为自然升序
* **问题现象**：轮次排序呈现为：二、六、三、四、五、一、回归测试，完全错乱。
* **技术根因**：原排序逻辑使用了中文字符串 `localeCompare('zh-CN')`，按照汉语拼音字典序比较（二er < 六liu < 三san < 四si < 五wu < 一yi < 回hui）。
* **修复方案**：
  1. 后端数据源 `BiSystemTestCalculator` 在每项数据中实际已包含正确的整数 `roundOrder`（1, 2, 3...，回归测试固定末位）；
  2. 前端在 `RoundQualityRow` 中映射 `order: item.roundOrder`；
  3. 在 `sorting.ts` 中实现自然时序解析器 `parseRoundOrder`，优先比对整数 `order`，兜底采用中文数字（一、二、三...）与正则识别，并将“回归测试”赋予极大权值（`99000`）确保置底；
  4. 轮次顺序在“升序”下严格从上到下展现为“第一轮 -> 第二轮 -> 第三轮 -> 第四轮... -> 回归测试”。
* **改动文件**：`sorting.ts`、`SystemTestStageContent.vue`、`sorting.test.ts`

### 3.10 【ISSUE-04】系统测试各模块修复率达成情况排序修正
* **问题现象**：整体修复率为 0% 的模块被强行排在了最上面。
* **技术根因**：达标判断误将百分数写为 `left.fixRate >= 0.95`。由于系统修复率是 0~100 标量，70% 或 100% 均大于 0.95，只有 0.00% 小于 0.95 被判定为“唯一未达标异常”，在默认“异常优先”下被顶到了最顶端。
* **v1.0 错误前提的推翻（重要）**：v1.0 补充的“0% 修复率通常代表该模块无缺陷或尚未开展测试，所以应沉底”不成立——`BiSystemTestCalculator.modules()` 由 issues 分组构建，**只有存在缺陷的模块才会进入列表**；`percent()` 仅在 `total <= 0` 时返回 `null`，而此处 `total` 恒大于 0，故 `fixRate === 0` 严格等价于“**有缺陷且一个都未修复**”＝最高风险模块，绝不得置底。
* **v2.0 修复方案（按 D2）**：
  1. 纠正达标判定阈值为目标线 `>= 95`（保留 v1.0 的正确部分）；
  2. 置底规则收敛为**仅对真不可计算的 `null` 生效**，且只在默认 `status`（异常优先）维度下生效；用户显式选择 `open`/`total`/`rate`/`name` 时一律不干预排序；
  3. 删除 `SystemTestStageContent.vue` 中与 `sorting.ts` 已漂移的内联比较器副本，页面改为直接调用单一权威实现 `sortModuleRepairRows`，使 `sorting.test.ts` 的用例真正保护生产路径；
  4. 修正词条文案：0% ＝有缺陷且全部未修复，属最高风险；仅无可计算数值时置底。
* **改动文件**：`sorting.ts`、`SystemTestStageContent.vue`、`sorting.test.ts`、`chart-explanations.ts`

---

## 四、 第二部分：全看板图表业务说明与问号 Tooltip 落地（新增需求）

### 4.1 交互规范与视觉设计
针对用户指示：“给BI看板的每个图表都加一个类似于评审数据模块的‘是否达标’字段上的小问号（解释标准、业务含义）”，设计规范如下：
1. **位置**：放置在卡片标题行（`<h3>{{ title }}</h3>`）正右侧，距离标题文字 6px；
2. **图标**：采用 Element Plus 标准 `<QuestionFilled />` 图标（实现值：静置 `#94a3b8`，鼠标悬浮 `#475467`，与 BI 卡片既有配色体系一致）；
3. **Tooltip 呈现**：
   - 触发方式：鼠标 Hover（悬停即现，移开即隐）；
   - 主题：`effect="dark"` 深色背景、白色文字；
   - 展现效果：文字折行清晰、最大宽度 320px，层级高于图表 Canvas，无布局抖动；
   - **样式陷阱（v2.0 已修）**：Element Plus popper 被 teleport 到 `body`，组件内 `<style scoped>` 对其无效；样式必须放在 `BiChartPanel.vue` 的**非 scoped `<style>` 块**中，以 `.bi-chart-panel__tooltip.el-popper { max-width: 320px; line-height: 1.6; word-break: break-word; }` 实现（与 SmartSelect 既有惯例一致）；v1.0 仅写了 `popper-class` 而全库无定义，宽度与折行实际未生效。

### 4.2 全看板图表业务说明与口径词条权威字典（26 条词条 / 23 个卡片实例）

字典统一定义在 `frontend/src/features/bi-dashboard/data/chart-explanations.ts` 中，严格忠实于《BI看板数据来源与计算口径核对表.md》：

> **计数口径更正**：v1.0 称“21 个图表”失实。字典实际共 **26 条词条**，对应 **23 个卡片实例**（需求与设计评审共用同一组件模板与同一组图表位，故各 3 条词条只渲染为一组卡片）；26 条已全部接线，无死条目、无未定义引用。

| 阶段页面 | 图表卡片标题 | 权威业务解释说明文字（Tooltip 显示内容） |
|---|---|---|
| **需求评审** | 需求评审问题类别分布 | 统计需求评审发现的问题类别分布及占比。 |
| **需求评审** | 各模块需求评审质量 | 统计各模块需求评审缺陷密度与评审速率。达标标准：需求评审缺陷密度介于[0.2~0.6]个/页；评审速率无达标要求。 |
| **需求评审** | 每次需求评审质量分布 | 展示每次独立需求评审活动的速率与缺陷密度分布，横轴为评审速率（页/小时），纵轴为缺陷密度（个/页），带色散点标识单次是否达标。 |
| **设计评审** | 设计评审问题类别分布 | 统计设计评审发现的问题类别分布及占比。 |
| **设计评审** | 各模块设计评审质量 | 统计各模块设计评审缺陷密度与评审速率。达标标准：设计评审缺陷密度介于[0.3~0.8]个/页；评审速率无达标要求。 |
| **设计评审** | 每次设计评审质量分布 | 展示每次独立设计评审活动的速率与缺陷密度分布，横轴为评审速率（页/小时），纵轴为缺陷密度（个/页），带色散点标识单次是否达标。 |
| **编码阶段** | 各模块人工代码走查质量 | 统计各模块人工代码走查缺陷密度与走查速率。达标标准：代码走查缺陷密度介于[2.0~10.0]个/KLOC；走查速率无达标要求。 |
| **编码阶段** | 代码走查问题分布 | 统计人工代码走查过程中发现的问题类别分布及占比。 |
| **编码阶段** | 静态代码扫描结果 | 统计代码静态分析扫描记录数与发现的问题总数。 |
| **编码阶段** | 开发人员代码贡献 | 统计各开发人员在当前版本周期内合并请求所累计新增的代码行数（行）。 |
| **编码阶段** | 各模块代码增量 | 统计各业务模块在当前版本周期内合并请求所累计新增的代码行数（行）。 |
| **编码阶段** | 代码注释率与走查密度趋势 | 展示按时间周期的代码注释率(%)与人工走查缺陷密度(个/KLOC)双轨演进趋势。 |
| **编码阶段** | 代码增量趋势 | 展示按日或按周的新增代码量（柱状）与版本累计代码量（折线），单位：行（代码行）。 |
| **编码阶段** | 提交趋势 | 展示按日或按周的代码提交次数（Commit）与合并请求数（MR）演进趋势。 |
| **编码阶段** | 单次人工走查质量分布 | 展示每次代码走查的速率与缺陷密度分布，横轴为走查速率（KLOC/小时），纵轴为缺陷密度（个/KLOC）。 |
| **测试质量** | 各模块测试达标情况 | 统计各功能模块的测试用例通过率(%)。达标标准：测试用例通过率 >= 95.00%。 |
| **测试质量** | 模块下功能达标情况 | 统计所选模块下各具体功能点的测试通过率与达标情况。达标标准：通过率 >= 95.00%。 |
| **系统测试** | 系统测试各轮次缺陷修复情况 | 展示从首轮至回归测试各轮次的缺陷提交数、关闭数与缺陷关闭率(%)演进情况。 |
| **系统测试** | 缺陷严重度分布 | 统计系统测试期间一级缺陷（致命）、二级缺陷（严重）、三级缺陷（一般）的数量分布及占比。 |
| **系统测试** | 各模块系统测试修复率达成情况 | 统计各模块整体修复率、一级修复率、P1/P2修复率。达标标准：整体修复率 >= 95.00%。修复率 0% 表示该模块已发现缺陷但尚未修复，属最高风险，异常优先视图下排在最前；仅无可计算数值时置于列表末尾。 |
| **系统测试** | 系统测试缺陷延期情况 | 交叉分析缺陷原因与严重级别在解决时限上的延期分布，深色代表延期集中的高风险区域。 |
| **系统测试** | 各模块缺陷级别 | 展示各模块所包含的一级、二级、三级缺陷数量构成，直观反映各模块质量脆弱点。 |
| **系统测试** | 各模块累计发现与当前未修复缺陷对比 | 展示各模块累计发现的缺陷总数，并高亮叠加当前尚未关闭的待修复缺陷数。 |
| **系统测试** | 系统测试缺陷原因大类分布 | 按缺陷归因大类（如需求遗漏、设计缺陷、编码逻辑、环境问题等）统计缺陷数量与占比。 |
| **系统测试** | 系统测试缺陷原因子类分布 | 下钻展示各缺陷归因大类下的具体二级子类原因缺陷分布。 |
| **系统测试** | 按指派人统计缺陷数 | 统计各处理人员被指派的缺陷总数，单柱垂直堆叠展示已修复数（绿）与待修复数（橙），柱顶标识累计总数。 |

---

## 五、 第三部分：待决策调研项分析与改造建议（暂不改代码）

针对用户指出的两项调研事项，当前保持生产代码不动，调研评估结论如下：

### 5.1 编码阶段“静态代码扫描结果”图表业务价值评估与建议
* **用户问题**：“静态代码扫描结果图表意义不明，调查一下是否有意义，是我们图表展示的有问题，还是图表本身就没意义？”
* **现状硬伤剖析**：
  1. **量纲逻辑硬伤**：当前图表将“扫描记录数（次）”和“发现Bug总数（个）”垂直堆叠在同一根柱子上。“扫描次数”与“Bug个数”物理量纲不同，堆叠相加无数学与管理意义；
  2. **缺少严重级别分类**：静态代码扫描的核心价值在于区分**阻断（Blocker）、严重（Critical）、主要（Major）**违规。当前数据源只提供了问题总数，无法识别哪些问题真正阻碍发布；
  3. **X 轴维度偏差**：当前 X 轴是构建状态（如 SUCCESS、FAILED），更偏向 CI/CD 运维流水线，不能直观反映业务代码库本身的健康状态。
* **处置建议（供领导裁定）**：
  * **建议方案 A（推荐，短期）**：直接下线删除该图表。理由是当前信息无法提供有效的质量决策，下线后编码阶段可留出更多版面给核心的“各模块人工代码走查质量”与“代码增量趋势”；
  * **建议方案 B（中远期演进）**：若后续平台对接到统一的静态代码分析系统（如 SonarQube），按项目或模块维度，呈现阻断、严重、主要缺陷级别分布及修复趋势。

### 5.2 系统测试“按指派人统计缺陷数”支持按模块/组织架构查看调研
* **用户需求**：“系统测试的按指派人统计缺陷数图表，领导想要实现按照模块或者组织架构查看，方便管理”
* **现状与技术边界**：
  1. **当前数据源**：当前接口 `GET /api/bi/system-test` 下发的 `developers` 列表是单纯按当前指派人 `assignee_name` 进行单维度聚合汇总（总数、已修复数、未修复数）；
  2. **缺失维度关系**：接口未下发人员所属组织架构（如部门、组别），缺陷数据模型中虽然缺陷归属于模块，但单个开发人员往往跨多个模块解决缺陷（属于多对多关系），无法在纯前端进行简单的层级分组。
* **架构演进路线建议（v2.0 按实证更正）**：
  1. **原“第一阶段：纯前端模块筛选”不可行**：`DeveloperWorkload(assignee, totalCount, fixedCount, openCount)` 与 `ModuleQuality(module, …)` 均为单维聚合，`GET /api/bi/system-test` 的 DTO 中**不存在“人 × 模块”交叉明细**，前端无法在选定模块后重建该模块内的指派人数值（强行实现只会出现与图表自身口径不一致的假数字）；
  2. **合并为单一后端供数任务**（原两阶段不再拆开）：后端在 `system-test` 响应中新增按模块 × 指派人（或按组织维度）的交叉明细，或在服务端直接聚合后下发；人员侧可结合平台已有的 LDAP / 组织管理表为 `BiSystemTestDeveloperItem` 补充 `departmentId`、`departmentName`；
  3. **前端控件后置**：待真实交叉数据就绪后，再实现 `[ 按人员排行 | 按部门汇总 | 按模块透视 ]` 切换器；在此之前不得先建空壳控件；
  4. **优先级**：需领导裁定后排期，本轮不动生产代码。

---

## 六、 第四部分：自动化测试与质量验收结果（v2.0 实测）

> v1.0 本章曾声称“`/actuator/health` 保持 UP、纯前端全绿”，已被 8.2 实证明实为陈旧实例且后端当时不可编译；下列为本单元收尾时（含 P0/P1 全部代码落地后）的真实运行结果。

1. **后端编译与全量默认套件**：
   - `mvn -o -DskipTests test-compile` → **EXIT=0**（P0-1 解除编译阻塞的首要证据）；
   - `mvn -o test` → **EXIT=0，287 个测试类 / 1342 项：0 失败、0 错误、1 跳过**（含已将 `bi.api` 纳入扫描的 `GoldenBaselineCoverageGuardTest`）；
   - `mvn -o test -Dtest=com.data.collection.platform.bi.**.*Test` → **109 项全绿**（含 `BiExcelExportServiceTest` 6 项、`BiDashboardControllerExcelExportTest` 2 项）；
   - `mvn -o spotbugs:check` → **EXIT=0，0 发现**；`mvn -o checkstyle:check` 仅剩 `backup` 模块 **7 处既有未用 import**（与本方案无关的存量债务，且本方案不越界修改）。
   - 注：`npm run build` 首次因 vite 开发者服务与 `src/components.d.ts` 写入争用报 `UNKNOWN: unknown error`，重试即成功（15.67s），属 Windows 文件锁定闪烁，非代码问题。
2. **前端工程验证**：
   - `npx vitest run src/features/bi-dashboard` → **14 个测试文件、74 项全绿**（含新增“异常优先分组不随降序翻转”语义钉住用例）；
   - `npm run test`（全量）→ **133 个测试文件、519 项全绿**；
   - `npm run typecheck` → **0 错误**；`npm run lint` → **0 错误**；`npm run build` → **成功**（24.76s）；
   - 关键防护网：[`sorting.test.ts`](file:///d:/projects/data_collection_platform/frontend/src/features/bi-dashboard/data/sorting.test.ts) 现在直接覆盖页面使用的单一权威实现 `sortModuleRepairRows`（含“0% 且遗留缺陷最多的模块在按遗留数排序时置顶”“仅 `null` 在默认异常优先视图置底”）；[`CodingStageContent.test.ts`](file:///d:/projects/data_collection_platform/frontend/src/features/bi-dashboard/stages/CodingStageContent.test.ts) 断言频次图表卡片不存在；[`excel-table.test.ts`](file:///d:/projects/data_collection_platform/frontend/src/features/bi-dashboard/charts/excel-table.test.ts) 计数收敛为 **15 个图表**；[`export-chart.test.ts`](file:///d:/projects/data_collection_platform/frontend/src/features/bi-dashboard/charts/export-chart.test.ts) 断言 `description` 透传为 `explanation`、无词条时传空串。
3. **仓库门禁**：`check_api_contract_drift.py` 0 missing（backend_paths=175、frontend_paths=87）、`check_frontend_api_boundary.py`、`check_worktree_artifacts.py`、`check_runtime_artifact_locations.py`、`check_text_whitespace.py` 与 `git diff --check` 全部通过。
4. **本地开发服务**：后端已**重启至当前代码**（原运行实例为陈旧版本，不含 Excel 口径行与统计看板修复），`http://127.0.0.1:18080/actuator/health` → `{"status":"UP"}`；`http://127.0.0.1:18181` → 200。
5. **浏览器真实界面验收（2026-09-11 已执行，使用用户提供的具备 `bi.dashboard.view` 权限账号登录 18181/18080 真实实例）**：
   - **频次图表确认消失**：✅ 编码页实际渲染 9 张卡片（各模块人工代码走查质量、代码走查问题分布、静态代码扫描结果、开发人员代码贡献、各模块代码增量、代码注释率与走查密度趋势、代码增量趋势、提交趋势、单次人工走查质量分布），页面文本不再包含“代码提交频次时间分布”；
   - **问号与深色气泡**：✅ 9/9 卡片均有 `.bi-chart-panel__help-trigger`（`aria-label="卡片名说明"`），popper class 为 `el-popper is-dark el-tooltip bi-chart-panel__tooltip`，背景 `rgb(48,49,51)`/白字；
   - **宽度 ≤320px 与折行**：✅ 对最长词条之一“各模块系统测试修复率达成情况”悬停后实测 `getBoundingClientRect()` 返回 **width=320、height=89**，按 `Range.getClientRects()` 确认视觉折行 4 行（line-height 1.6 生效）；非 scoped `.el-popper` 样式已在真实 teleport popper 上命中（证明 v1.0 只写 `popper-class` 时的失效已修正）；
   - **0% 模块位置**：✅ CC2026R3 无 0% 模块（最低 16.88%），改在含 3 个 0% 模块的 CC2026R2 验收：默认“异常优先 + 升序”下 0% 模块（管路/焊件/平台）均位列前 3，100%+0 遗留的模块在末尾；切到“未修复数 降序”时完全由该维度决定（前 3：工程图 324、装配 241、实体算法 192），不再被 0% 规则干预；验收后版本与排序已复原；
   - **Excel 含口径说明行且可正常打开**：✅ 真实下载 `各模块系统测试修复率达成情况_20260911092657.xlsx`（5,538 字节），解压检查 OOXML 内部结构确认：行 1 标题、行 2“产品版本：CC2026R3 | 导出时间：2026-09-11 09:26:…”、**行 3“口径说明：统计各模块整体修复率、一级修复率、P1/P2修复率。达标标准：整体修复…”**、行 5 表头（模块名称 / 遗留缺陷数 (个) / 累计缺陷数 (个) / 整体修复率 (%) / 一级缺陷修复率 (%) / P1修复率 (%)）、行 6 起数据，`<pane ySplit="5.0" state="frozen" topLeftCell="A6">` 与“有说明时冻结 5 行”一致；数值单元格为真数字且保持百分量纲（16.9 而非 1690，不再二次乘 100）。
   - **本轮未能取证的部分（环境而非产品）**：执行时浏览器视图处于隐藏/最小化状态，`take_screenshot` 全部失败（无可提供截图），且 640px 视口命中 ≤760px 媒体查询导致栅格退化单列；1920×1080 下的多列布局与气泡肉眼观感仍建议用户亲自看一眼。隐藏视图还使 `requestAnimationFrame` 不触发，而 `api-client/request.ts` 的 `waitForProgressFirstPaint` 在发请求前 `await rAF`，导致 BI 页面卡在“加载中”（探针为取功能结论临时在内存注入 rAF 兼容，未改仓库文件）——该耦合对任何 headless/隐藏窗自动验收都是地雷，已作为独立发现记录到 `docs/progress.md`，不属本单元修复范围。
   - **新发现（需产品确认，已在 8.6 登记）**：“异常优先”维度切到**降序**时，未达标组仍在前但组内按修复率从高到低重排，0% 模块会落在未达标组末尾（看起来像“中间”）。该行为已用单测固定为可预期语义，但它是否符合领导预期需裁定。

---

## 七、 第五部分：审查要点与建议行动

1. **已实施完成项确认**：请审查上述“第一部分”中的 10 项修复细节以及“第二部分”中 **26 条问号 Tooltip 词条**的文案是否完全符合领导预期；
2. **静态代码扫描图表决议**：请裁定是否同意“短期直接下线删除静态代码扫描图表”的建议；
3. **按指派人统计缺陷数演进决议**：请确认是否在下一迭代开启“按模块筛选指派人”或“按组织架构聚合”的后端扩展任务。
4. **“异常优先”维度的降序语义**：浏览器实测发现降序时 0%（最危险）模块会从顶部移到未达标组末尾。当前实现把“异常优先”定义为**分组**（未达标组永远在前，方向只重排组内），已用单测固定；若领导期望降序就是“单纯按修复率反向”（0% 到底），需另行裁定并同步修正词条文案。

---

## 八、 第六部分：v2.0 审查结论与完善执行计划

> 本章为 2026-09-10 对 v1.0 全部声称的**逐条代码实证复核**结果，以及经用户审定的 v2.0 执行计划。
> 复核方法：不采信文档表述，每项均回到当前工作区源码与后端计算器取证，并以实际运行测试复现。

### 8.1 复核结论摘要

- v1.0 共 13 项声称：**8 项完全属实**（BUG-01~BUG-06、ISSUE-01、ISSUE-03）、**5 项失实或半完成**（ISSUE-02、ISSUE-04、ISSUE-05、SURVEY-02、第四部分服务健康声明）。
- 另发现 **1 个阻塞项**：后端源码当前**不可编译**（属统计看板并行工作流，与本方案无关），导致一切后端验证与重启阻塞，且当前运行中的后端为陈旧实例。
- 已复现属实的质量数据：`npx vitest run src/features/bi-dashboard` → **14 个测试文件、73 项全绿**（与 v1.0 第四部分完全一致）；全仓 **132 文件 / 508 项全绿**；`typecheck` 0 错误。

### 8.2 声称与实证的差异更正记录

| 项 | v1.0 表述 | 代码实证 | v2.0 更正 |
|---|---|---|---|
| ISSUE-02 | “彻底移除频次图表模板与冗余状态” | 仅删除页面卡片（编码页 10→9 个）；`SubmissionFrequencyBarChart.ts`、`types/index.ts` 导出、`data/types.ts` 联合类型、`aggregateFrequenciesByWeek`（仅自身测试引用）、**后端 `BiDownloadAuthorizationService.PAGE_TEMPLATES.coding` 白名单**全部残留 | 改为“页面卡片已下线，模板与后端白名单待清理” |
| ISSUE-04 | “0% 修复率通常代表该模块无缺陷或尚未开展测试” | `BiSystemTestCalculator.modules()` 由 issues 分组构建，**只有存在缺陷的模块才会进入列表**；`percent()` 在 `total <= 0` 返回 `null`，故整体 `fixRate = percent(fixed, issues.size())` **永不为 null**。因此 `fixRate === 0` 严格等价于“**有缺陷且一个都未修复**”＝最高风险 | 业务前提错误，必须修正（详 8.4 P0-2） |
| ISSUE-04 | “改动文件：sorting.ts、SystemTestStageContent.vue” | `sortModuleRepairRows` **仅被 `sorting.test.ts` 引用**，页面 `SystemTestStageContent.vue:140-173` 是另一份内联实现；两者已漂移（页面用 `compareNumericNullsLast`，函数用 `(fixRate ?? 0)` 相减；默认 order 一个 `asc` 一个 `desc`） | 双实现＋**虚假防护网**，必须收敛（详 8.4 P0-3） |
| ISSUE-05 | “21 个图表权威字典”、“最大宽度 320px、文字折行清晰” | 字典实为 **26 键**（需求/设计共用 3 个卡片模板，故各 3 条词条），**26 键全部已接线、无死条目、无未定义引用**；但 `popper-class="bi-chart-panel__tooltip"` 全库**仅此一处引用、无任何定义**，组件只有一个 `<style scoped>` | 计数更正为 26 词条 / 23 卡片；宽度与折行**未实现**（详 8.4 P0-4） |
| ISSUE-05 | 图标色 `#909399`，hover `#409EFF` | 实现为 `#94a3b8`，hover `#475467` | 实现更贴合 BI 既有配色体系，**改文档不改代码** |
| SURVEY-02 | “第一阶段（纯前端轻量筛选）：选模块后仅统计该模块范围内各指派人负荷” | `DeveloperWorkload(assignee, totalCount, fixedCount, openCount)` 与 `ModuleQuality(module, …)` 均为**单维聚合**，DTO 中**不存在“人×模块”交叉明细** | 第一阶段数据上不可行，必须后端供数；两阶段应合并 |
| 第四部分 | “后端 `/actuator/health` 保持 UP” | 后端源码当前**不可编译**（2 处 `int`→`Long`）；运行中为 10:45 启动的陈旧实例，不含此后任何改动 | 声明失效，待 P0-1 修复后重新验证 |
| 1.2 准则 | “纯前端闭环、零侵入后端” | P0-1（解除编译阻塞）、P0-5（后端白名单清理）、P1-1（Excel 口径说明）**均需改后端** | 保留原则但显式声明三项例外及其理由 |

### 8.3 已审定的四项决策（用户 2026-09-10 确认）

| 编号 | 决策事项 | 审定结果 |
|---|---|---|
| **D1** | 后端 2 处编译错误是否授权修复 | **授权按 `null` 语义修复**（两处均为纯文本单元格） |
| **D2** | 0% 置底规则如何处置 | **按证据修正**：仅对真不可计算的 `null` 沉底，不再覆盖用户显式选择的排序维度；同步修正词条与文档表述 |
| **D3** | 频次图表死代码是否彻底清理 | **彻底清理含后端白名单**，并同步调整相关测试计数 |
| **D4** | Excel 是否增补业务口径说明 | **增补一行口径说明**，复用 `BiExcelExportService`，前端透传 `description` |

### 8.4 v2.0 执行计划（已审定，执行结果见 8.7）

#### P0-1　解除后端编译阻塞（D1，本方案范围外的最小必要改动）— ✅ 已解除
- 原计划：`service/statistics/CustomerIssueByFunctionBoardService.java:619` 与 `CustomerIssueResponseEfficiencyBoardService.java:795` 的文本单元格 `0` → `null`
- 实际结果：该文件的统计看板并行工作流作者先一步以 `0L` + 注释修正了同一处编译阻塞，本人随即**撤回 `null` 建议**以免越界改动他人在途代码；改动仍严格限于这 2 处
- 证据：`mvn -o -DskipTests test-compile` → EXIT=0；`mvn -o test` 全量默认套件全绿
- 保留的依据事实：`StatisticCellData.numericValue` 已由 `long` 改为 `Long`，`null` 表示无可计算数值、与真实 0 严格区分；两处均为纯文本单元格，不参与数值排序

#### P0-2　修正 0% 置底的业务语义（D2）— ✅ 已完成
- `data/sorting.ts` 与 `SystemTestStageContent.vue` 中的沉底判定由 `fixRate == null || fixRate === 0` 改为**仅 `fixRate == null`**
- 沉底规则**仅在默认 `status`（异常优先）维度生效**；用户显式选择 `open`/`total`/`rate`/`name` 时不得干预排序
- 修正 `data/chart-explanations.ts` 的 `systemTestModuleRepair` 文案：删除“修复率为0%通常代表该模块无缺陷或尚未开展测试，统一置底展示”，改为准确表述（0% ＝有缺陷且全部未修复，属最高风险；仅无可计算数值时置底）
- 同步更新 `sorting.test.ts` 相关断言，新增“0% 且遗留缺陷最多的模块在按遗留数降序时置顶”回归用例

#### P0-3　排序收敛为单一权威实现（消除虚假防护网）— ✅ 已完成
- 前置确认：`SystemTestStageContent.vue` 的 `repair` 映射已含 `openCount`/`totalCount`（否则 `open`/`total` 排序失效）
- 删除 `visibleModules` 的内联比较器（已核实 `visibleModules` **仅被 `repair` 消费**，收敛零风险），改为“先 map 为 `ModuleRepairRow[]`，再调 `sortModuleRepairRows(rows, repairSort, repairSortOrder)`”
- 统一默认 `order` 语义（页面显式传参为准），消除两份实现的默认值分歧
- 成果：`sorting.test.ts` 的 8 项用例真正保护生产路径

#### P0-4　补齐问号 Tooltip 的宽度与折行样式— ✅ 已完成
- 关键陷阱：Element Plus popper 被 teleport 到 `body`，**`<style scoped>` 对其无效**；必须新增**非 scoped** 样式块（或全局样式）定义 `.bi-chart-panel__tooltip { max-width: 320px; line-height: 1.6; }`
- 验收：最长词条（约 110 字）必须折行且不超出视口
- 同步把 4.1 的颜色表述改为实现值 `#94a3b8` / hover `#475467`

#### P0-5　频次图表死代码彻底清理（D3，含后端）— ✅ 已完成（另含两处权威文档条目清理）
- 前端：删除 `charts/types/SubmissionFrequencyBarChart.ts`；删 `types/index.ts` 导出；删 `data/types.ts` 的 `'submission-frequency-bar'`；删 `aggregation.ts` 的 `aggregateFrequenciesByWeek` 及 `aggregation.test.ts` 对应用例
- 后端：`BiDownloadAuthorizationService.PAGE_TEMPLATES` 的 `coding` 集合删除 `"submission-frequency-bar"`（同步核查 `BiDownloadAuthorizationServiceTest` 是否断言该模板）
- 连带调整本轮 Excel 改造的两处测试计数：`excel-table.test.ts` 16→15 图表、`chart-contract.test.ts` 15→14 个 templateId

#### P1-1　Excel 导出增补业务口径说明（D4）— ✅ 已完成
- 前端：`BiChartExportRequest` 增 `description?: string`；`BiChartPanel.downloadExcel()` 传 `props.description`；`bi-dashboard-api.exportExcel` 参数增 `description`
- 后端：`BiExcelExportRequest` 增可空 `description`；`BiExcelExportService` 在元信息行下新增一行“口径说明：…”
- **行索引风险**：新增一行会使 spacer/header/data 索引整体下移，冻结行数由 4 变 5；必须同步更新 `BiExcelExportServiceTest` 与 `BiDashboardControllerExcelExportTest` 中所有行号断言（现有断言硬编码 `getRow(4)`）
- 无 `description` 时不写该行，保持行索引稳定（避免空行漂移）

#### P1-2　文档与进度记录更正— ✅ 已完成
- 本方案：4.2 标题与矩阵的“21 个图表”→“26 条词条 / 23 个卡片实例”；第四部分服务健康声明待 P0-1 后重写；1.2 增补三项后端例外声明
- `docs/bi-dashboard/progress.md`：更正本会话早先写入的“BI 端点天然豁免黄金基线、无需登记 `endpoint-catalog.yml`”——并行工作流已将 `GoldenBaselineCoverageGuardTest` 扩展为同时扫描 `controller` 与 `bi.api`，且 `POST /api/bi/download/excel` **已登记为 EXCLUDED**（理由 chain-dependency）

#### P1-3　SURVEY-02 结论更正— ✅ 已完成
- 5.2 第一阶段改为：因 DTO 无“人×模块”交叉明细，**纯前端筛选不可行**；需后端在 `system-test` 响应中新增按模块×指派人的明细（或直接后端聚合），前端再做 `[按人员 | 按部门 | 按模块]` 切换器；两阶段合并为一个后端供数任务，待领导裁定优先级

### 8.5 执行后的验证口径（必须全绿）

1. `mvn -q -DskipTests test-compile` → EXIT=0（P0-1 解除阻塞的首要证据）
2. 后端定向测试：`GoldenBaselineCoverageGuardTest`（现已覆盖 `bi.api`）、`BiExcelExportServiceTest`、`BiDashboardControllerExcelExportTest`、`BiDashboardSecurityContractTest`、`BiSpringBoundaryContractTest`、`BiDownloadAuthorizationServiceTest`，以及因 P0-1 触及的统计看板相关测试
3. `mvn -q spotbugs:check` → EXIT=0；checkstyle 仅剩 `backup` 模块 7 处既有未用 import（与本方案无关，已备案）
4. 前端 `npm run lint`、`npm run typecheck`、`npm run test`（全量）、`npm run build` 全绿
5. `python scripts/check_api_contract_drift.py` → 0 missing；`check_frontend_api_boundary.py` 通过
6. 浏览器实测清单：问号气泡宽度 ≤320px 且折行；0% 模块在默认视图不再被误置底、按遗留缺陷数降序时高危模块置顶；频次图表确认消失；Excel 含口径说明行且可正常打开

### 8.6 本轮不做（需另行裁定）

- **SURVEY-01**：静态代码扫描图表是否下线——待领导裁定；若裁定下线，必须按 P0-5 同样标准一次性清理干净（卡片＋图表类＋templateId＋词条＋后端白名单＋测试），不得再造死代码
- **SURVEY-02**：按模块/组织架构查看指派人的后端扩展——待优先级裁定
- “异常优先”维度降序时 0% 模块落在未达标组末尾的语义是否符合领导预期——待裁定（第七部分第 4 条）
- 前端 `waitForProgressFirstPaint` 先 `await requestAnimationFrame` 再发请求，使隐藏/最小化窗口下的页面永远不拉数——属平台请求层通用耦合（非 BI 专属），本次只记录不改
- 统计看板并行工作流的其余在途改动（本轮仅修 P0-1 的 2 行以解除阻塞）
- `backup` 模块 7 处既有 checkstyle 违规（属已提交存量债务，且该模块有并行未提交改动，不越界修改）

### 8.7 v2.0 执行记录（2026-09-10 收尾）

| 项 | 落地文件与要点 | 状态 |
|---|---|---|
| P0-1 | `CustomerIssueByFunctionBoardService.java`、`CustomerIssueResponseEfficiencyBoardService.java`（并行作者以 `0L`+注释落地，本人验证） | ✅ 编译 EXIT=0 |
| P0-2 | `data/sorting.ts`、`data/chart-explanations.ts`、`sorting.test.ts`（新增 0% 高危及 `null` 置底用例）；同时修正本单元发现的 `codingCodeTrend` 词条单位错（KLOC → 行） | ✅ |
| P0-3 | `stages/SystemTestStageContent.vue`（删除内联副本，改调 `sortModuleRepairRows`） | ✅ |
| P0-4 | `components/BiChartPanel.vue` 新增非 scoped `<style>` 块 `.bi-chart-panel__tooltip.el-popper`（`max-width: 320px`/`line-height: 1.6`/`word-break`） | ✅ 浏览器实测 width=320、视觉折行 4 行 |
| P0-5 | 前端 5 处删除 + 后端 `BiDownloadAuthorizationService` 白名单 + `excel-table.test.ts`/`chart-contract.test.ts` 计数 + `docs/bi-dashboard/product.md`与`architecture.md` 频次图表条目 | ✅ 全库零引用 |
| P1-1 | 前端 `charts/export-chart.ts`、`components/BiChartPanel.vue`、`api-client/bi-dashboard-api.ts`；后端 `BiExcelExportRequest`、`BiExcelExportService`、`BiDashboardController`；测试 `BiExcelExportServiceTest`（+2 项）、`BiDashboardControllerExcelExportTest`（行索引 2/5 与透传断言）、`export-chart.test.ts`（透传与空串断言）；口径入 `docs/bi-dashboard/data-contracts.md` | ✅ 以真实 POI 读取字节断言 |
| P1-2/P1-3 | 本文 1.1/1.2、第 2 章矩阵、`3.8`/`3.10`/`4.1`/`4.2`/`5.2`/第六部分重写；`docs/bi-dashboard/progress.md` 两条 09-10 条目更正（含黄金基线不豁免 `bi.api`、EXCLUDED 登记、26/23 计数、0% 语义）；v1.0  predecessor 计划文件标注“已被取代” | ✅ |

**验证口径完成情况**：8.5 第 1~5 项已全部执行并全绿（结果记于第六部分）；第 6 项浏览器实测已于 2026-09-11 使用用户提供的账号在真实实例完成四项取证（第六部分第 5 条），仅 1920×1080 肉眼观感受本机隐藏视图限制未能截图。执行后另新增“异常优先降序语义”单测 1 项（BI 套件 14 文件 / 74 项全绿）并产出两项新发现待裁定。

### 8.8 待提交清单与提交顺序（用户 2026-09-10 决定：并行单元先入库，再提本单元）

本单元**硬依赖**统计看板单元尚未提交的 `frontend/src/utils/missing-value-sorting.ts`（`bi-dashboard/data/sorting.ts` 直接 `import { compareNumericNullsLast }`），工作树同时混有至少三个并行在途单元（统计看板、备份与 deploy、LDAP/评审人调研），因而不存在职责单一且可独立回退的提交切口。待统计看板单元入库后，按下列两个提交推进，提交前重新跑 BI 定向测试 + 前端全量 vitest/typecheck + 五项仓库门禁，提交后确认 `git status` 只剩其他单元在途文件：

1. `feat(bi-dashboard): 图表 Excel 导出改为后端标准 OOXML 并携带口径说明行`
   - 后端主源：`bi/api/BiExcelExportRequest.java`（新）、`bi/application/BiExcelExportService.java`（新）、`bi/api/BiDashboardController.java`、`bi/application/BiDashboardRuntime.java`、`bi/infrastructure/BiDashboardRuntimeFactory.java`；
   - 前端：`charts/BiChart.ts`、`charts/excel-format.ts`（新）、`charts/excel-table.test.ts`（新）、`charts/export-chart.ts`、`charts/export-chart.test.ts`、`api-client/bi-dashboard-api.ts`、各 `charts/types/*.ts` 的 `excelTable()`；
   - 测试与基线：`bi/api/BiDashboardControllerExcelExportTest.java`（新）、`bi/application/BiExcelExportServiceTest.java`（新）、`bi/api/BiDashboardSecurityContractTest.java`、`bi/infrastructure/BiSpringBoundaryContractTest.java`、`golden/GoldenBaselineCoverageGuardTest.java`、`golden/GoldenBaselineChainTest.java`、`golden-baseline/endpoint-catalog.yml`、`golden-baseline/snapshots/bi/`（新）；
   - 文档：`docs/bi-dashboard/data-contracts.md`。
2. `fix(bi-dashboard): v2.0 复核修正排序语义与单一实现、Tooltip popper 样式、彻底下线频次图表`
   - 前端：`data/sorting.ts`、`data/sorting.test.ts`、`data/chart-explanations.ts`（新）、`data/types.ts`、`data/aggregation.ts` 与 `.test.ts`、`charts/types/index.ts`、删除 `charts/types/SubmissionFrequencyBarChart.ts`、`components/BiChartPanel.vue`、`stages/*.vue` 与对应 `.test.ts`、`BiDashboardView.vue`、`charts/chart-data.ts`、`charts/chart-contract.test.ts`；
   - 后端：`bi/application/BiDownloadAuthorizationService.java`（coding 模板白名单去条目）；`service/statistics/` 那 2 行编译修正归统计单元，若其已入库则不重复提交；
   - 文档：`docs/bi-dashboard/product.md`、`docs/bi-dashboard/architecture.md`、`docs/bi-dashboard/progress.md`、`docs/progress.md`、本计划与 v1.0 predecessor 计划。

---
*本方案 v1.0 初版曾保存于桌面独立文件：`C:\Users\admin\Desktop\BI看板实测问题修复与全看板业务说明综合方案.md`（该副本未含 v2.0 复核与 v2.1 执行结论，以仓库存档为准）*

*仓库实施计划留痕存档：`docs/plans/bi-dashboard-feedback-and-fixes-comprehensive-20260910.md`（v2.1）*
