/**
 * BI 看板各图表权威业务解释与指标口径说明字典
 * 严格基于《BI看板数据来源与计算口径核对表.md》与各阶段质量规范定义
 */
export const BI_CHART_EXPLANATIONS = {
  // === 阶段 1：需求评审与设计评审 ===
  requirementReviewQuality: '统计各模块需求评审缺陷密度与评审速率。达标标准：需求评审缺陷密度介于[0.2~0.6]个/页；评审速率无达标要求。',
  requirementReviewCategories: '统计需求评审发现的问题类别分布及占比。',
  requirementReviewScatter: '展示每次独立需求评审活动的速率与缺陷密度分布，横轴为评审速率（页/小时），纵轴为缺陷密度（个/页），带色散点标识单次是否达标。',

  designReviewQuality: '统计各模块设计评审缺陷密度与评审速率。达标标准：设计评审缺陷密度介于[0.3~0.8]个/页；评审速率无达标要求。',
  designReviewCategories: '统计设计评审发现的问题类别分布及占比。',
  designReviewScatter: '展示每次独立设计评审活动的速率与缺陷密度分布，横轴为评审速率（页/小时），纵轴为缺陷密度（个/页），带色散点标识单次是否达标。',

  // === 阶段 2：编码阶段 ===
  codingReviewQuality: '统计各模块人工代码走查缺陷密度与走查速率。达标标准：代码走查缺陷密度介于[3.0~12.0]个/KLOC；走查速率无达标要求。',
  codingReviewCategories: '统计人工代码走查过程中发现的问题类别分布及占比。',
  codingScanResult: '统计代码静态分析扫描记录数与发现的问题总数。',
  codingContributors: '统计各开发人员在当前版本周期内合并请求所累计新增的代码行数（行）。',
  codingModuleIncrements: '统计各业务模块在当前版本周期内合并请求所累计新增的代码行数（行）。',
  codingQualityTrend: '展示按时间周期的代码注释率(%)与人工走查缺陷密度(个/KLOC)双轨演进趋势。',
  codingCodeTrend: '展示按日或按周的新增代码量（柱状）与版本累计代码量（折线），单位：行（代码行）。',
  codingSubmissionTrend: '展示按日或按周的代码提交次数（Commit）与合并请求数（MR）演进趋势。',
  codingReviewScatter: '展示每次代码走查的速率与缺陷密度分布，横轴为走查速率（KLOC/小时），纵轴为缺陷密度（个/KLOC）。',

  // === 阶段 3：测试质量阶段 ===
  testQualityAttainment: '统计各功能模块的测试用例通过率(%)。达标标准：测试用例通过率 >= 95.00%。',
  testQualityExecution: '统计所选模块下各具体功能点的测试通过率与达标情况。达标标准：通过率 >= 95.00%。',

  // === 阶段 4：系统测试阶段 ===
  systemTestRounds: '展示从首轮至回归测试各轮次的缺陷提交数、关闭数与缺陷关闭率(%)演进情况。',
  systemTestSeverityDistribution: '统计系统测试期间一级缺陷（致命）、二级缺陷（严重）、三级缺陷（一般）的数量分布及占比。',
  systemTestModuleRepair: '统计各模块整体修复率、一级修复率、P1/P2修复率。达标标准：整体修复率 >= 95.00%。修复率 0% 表示该模块已发现缺陷但尚未修复，属最高风险；「异常优先」始终把未达标模块排在前面（默认升序下 0% 即列表首位），切换升降序只调整未达标组内部顺序；仅无可计算数值时置于列表末尾。',
  systemTestDelayAnalysis: '交叉分析缺陷原因与严重级别在解决时限上的延期分布，深色代表延期集中的高风险区域。',
  systemTestModuleSeverity: '展示各模块所包含的一级、二级、三级缺陷数量构成，直观反映各模块质量脆弱点。',
  systemTestModuleOverlay: '展示各模块累计发现的缺陷总数，并高亮叠加当前尚未关闭的待修复缺陷数。',
  systemTestCauseDistribution: '按缺陷归因大类（如需求遗漏、设计缺陷、编码逻辑、环境问题等）统计缺陷数量与占比。',
  systemTestCauseSubcategories: '下钻展示各缺陷归因大类下的具体二级子类原因缺陷分布。',
  systemTestDeveloperWorkload: '统计各处理人员被指派的缺陷总数，单柱垂直堆叠展示已修复数（绿）与待修复数（橙），柱顶标识累计总数。',
} as const;

