import type { StatisticRuleMetricDefinition } from '../../types/api';

export interface ReviewDataRuleFieldDefinition {
  key: string;
  label: string;
  description: string;
  guidance: string;
  note?: string;
}

export interface ReviewDataRuleCommonQuestion {
  key: string;
  title: string;
  description: string;
}

export interface ReviewDataRuleExplanationContent {
  title: string;
  version: string;
  summary: string;
  scopeDescription: string;
  fieldDefinitions: ReviewDataRuleFieldDefinition[];
  metricDefinitions: StatisticRuleMetricDefinition[];
  commonQuestions: ReviewDataRuleCommonQuestion[];
  recordDialogTip: string;
  problemDialogTip: string;
}

export const reviewDataRuleExplanationContent: ReviewDataRuleExplanationContent = {
  title: '评审数据管理规则说明',
  version: 'v1.1',
  summary: '说明评审数据管理页的关键字段、统计指标和筛选口径。',
  scopeDescription:
    '当前列表、顶部汇总卡片和详情页，统计的都是当前筛选结果中的有效评审记录，以及这些记录下的有效问题项。',
  fieldDefinitions: [
    {
      key: 'reviewScalePages',
      label: '评审规模(页)',
      description: '填写本次实际纳入评审范围的页数，它会直接影响评审缺陷密度。',
      guidance: '填写本次实际完成评审的页数。',
      note: '当评审规模为空、为 0 或小于等于 0 时，评审缺陷密度按 0 处理。',
    },
    {
      key: 'reviewType',
      label: '评审类型',
      description: '用于区分评审记录所属的评审场景。',
      guidance: '同类评审应使用统一评审类型名称。',
    },
    {
      key: 'reviewExperts',
      label: '评审专家',
      description: '填写实际参与本次评审并承担评审工作的人员，可多选。',
      guidance: '仅填写实际参与评审并承担评审工作的人员。',
    },
    {
      key: 'problemStatus',
      label: '问题状态',
      description: '问题状态是按单条问题项维护的，不是整条评审记录的统一状态。',
      guidance: '如果一条评审下有多个问题，不同问题可以处于不同状态。',
      note: '按问题状态筛选时，筛出来的是“包含该状态问题的评审记录”。',
    },
  ],
  metricDefinitions: [
    {
      key: 'problemCount',
      label: '问题总计',
      definition: '单条评审记录下的问题项数量。',
      formula: '问题总计 = 当前评审记录下的问题项数量',
      note: '对应表格里的“问题总计”和详情里的“问题总计”。',
    },
    {
      key: 'problemDensity',
      label: '评审缺陷密度(个/页)',
      definition: '表示单位页数上的问题密集程度。',
      formula: '评审缺陷密度 = 当前记录的有效问题数 / 当前记录的评审规模(页)，四舍五入保留两位小数',
      note: '有效问题数会排除已拒绝、未评审、无问题的问题项；对应表格里的“评审缺陷密度”和详情里的“缺陷密度”。',
    },
    {
      key: 'reviewEfficiency',
      label: '评审效率(个/小时)',
      definition: '表示单位评审工作量发现的有效问题数。',
      formula: '评审效率 = 当前记录的有效问题数 / 清单评审工作量合计，四舍五入保留两位小数',
      note: '清单评审工作量合计为空、为 0 或小于 0 时按 0 处理。',
    },
    {
      key: 'reviewRate',
      label: '评审速率(页/小时)',
      definition: '表示单位评审工作量覆盖的评审页数。',
      formula: '评审速率 = 当前记录的评审规模(页) / 清单评审工作量合计，四舍五入保留两位小数',
      note: '清单评审工作量合计为空、为 0 或小于 0 时按 0 处理。',
    },
    {
      key: 'reachStandard',
      label: '是否达标',
      definition: '达标判断：评审缺陷密度介于[0.2~0.6]',
      formula: '达标判断：评审缺陷密度介于[0.2~0.6]',
    },
    {
      key: 'totalRecords',
      label: '顶部卡片：评审记录',
      definition: '当前筛选条件下的评审记录总条数。',
      formula: '评审记录 = 当前筛选结果中的记录条数',
      note: '对应顶部卡片“评审记录”。',
    },
    {
      key: 'totalProblemItems',
      label: '顶部卡片：评审问题',
      definition: '当前筛选条件下全部评审记录的问题总数。',
      formula: '评审问题 = 当前筛选结果中每条记录的问题总计之和',
      note: '对应顶部卡片“评审问题”。',
    },
    {
      key: 'averageReviewScalePages',
      label: '顶部卡片：平均页数',
      definition: '当前筛选条件下 reviewScalePages 的平均值。',
      formula: '平均页数 = 当前筛选结果中的评审规模总和 / 评审记录总条数',
      note: '对应顶部卡片“平均页数”。',
    },
    {
      key: 'averageProblemCount',
      label: '顶部卡片：平均问题数',
      definition: '当前筛选条件下 problemCount 的平均值。',
      formula: '平均问题数 = 当前筛选结果中的问题总数 / 评审记录总条数',
      note: '平均问题数按评审记录数作为分母，不等同于评审缺陷密度。',
    },
  ],
  commonQuestions: [
    {
      key: 'density-zero',
      title: '缺陷密度为 0 的处理口径',
      description: '当“评审规模(页)”为空、为 0 或小于 0 时，评审缺陷密度按 0 展示。',
    },
    {
      key: 'status-filter',
      title: '问题状态筛选后的统计口径',
      description: '按问题状态筛选时，先筛出包含该状态问题的评审记录，再统计这些记录的问题总数。',
    },
    {
      key: 'average-density',
      title: '平均问题数与评审缺陷密度',
      description: '平均问题数按评审记录数作为分母；评审缺陷密度按评审规模页数作为分母。',
    },
  ],
  recordDialogTip: '评审类型、评审专家和评审规模(页)会影响列表筛选与评审缺陷密度计算。',
  problemDialogTip: '文档中的位置、问题类别和问题状态按单条问题项维护。',
};
