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
  title: '评审数据管理帮助指南',
  version: 'v1.1',
  summary: '这份指南主要回答三个问题：关键字段怎么填、页面上的数字怎么算、遇到常见显示结果时该怎么理解。',
  scopeDescription:
    '当前列表、顶部汇总卡片和详情页，统计的都是当前筛选结果中的有效评审记录，以及这些记录下的有效问题项。',
  fieldDefinitions: [
    {
      key: 'reviewScalePages',
      label: '评审规模(页)',
      description: '填写本次实际纳入评审范围的页数，它会直接影响评审缺陷密度。',
      guidance: '建议填本次真正评审到的页数，不要直接照搬整份文档总页数。',
      note: '当评审规模为空、为 0 或小于等于 0 时，评审缺陷密度按 0 处理。',
    },
    {
      key: 'reviewType',
      label: '评审类型',
      description: '用于区分这条记录属于哪类评审场景，方便后续筛选和横向比较。',
      guidance: '优先选择系统已有类型，保持同类评审使用同一名称。',
    },
    {
      key: 'reviewExperts',
      label: '评审专家',
      description: '填写实际参与本次评审并承担评审工作的人员，可多选。',
      guidance: '只填写实际参与评审的人，不建议把抄送或知会人员一起写入。',
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
      formula: '评审缺陷密度 = 当前记录的问题总数 / 当前记录的评审规模(页)',
      note: '对应表格里的“评审缺陷密度”和详情里的“缺陷密度”。',
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
      note: '它反映的是每条评审平均有多少问题，不是平均缺陷密度。',
    },
  ],
  commonQuestions: [
    {
      key: 'density-zero',
      title: '为什么我的缺陷密度显示为 0？',
      description: '请先检查“评审规模(页)”是否为空、为 0，或没有填写本次实际评审页数。系统不会对无效分母继续计算。',
    },
    {
      key: 'status-filter',
      title: '按问题状态筛选后，顶部“评审问题”是不是只统计该状态的问题？',
      description: '不是。当前口径是先筛出“包含该状态问题的评审记录”，再统计这些记录本身的问题总数。',
    },
    {
      key: 'average-density',
      title: '平均问题数能直接理解成评审缺陷密度吗？',
      description: '不能。平均问题数的分母是评审记录数，评审缺陷密度的分母是页数，两者含义不同。',
    },
  ],
  recordDialogTip: '请重点确认评审类型、评审专家和评审规模(页)，其中评审规模会直接影响评审缺陷密度。',
  problemDialogTip: '请重点确认文档中的位置、问题类别和问题状态；问题状态按单条问题项维护，不代表整条评审记录状态。',
};
