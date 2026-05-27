import type {
  CodeReviewIllegalRecordFilterOptionsResponse,
  CodeReviewIllegalRecordRowResponse,
  StatisticBoardRuleExplanationResponse,
  StatisticFilterField,
} from '../types/api';
import type { RecordTableColumn } from '../types/record-table';
import { buildGitlabResourceLinkCell } from '../utils/issue-record-links';

const DEFAULT_SELECT_WIDTH = 180;
const DEFAULT_TEXT_WIDTH = 180;
const DEFAULT_NUMBER_WIDTH = 160;
const DEFAULT_DATETIME_WIDTH = 220;

export interface CodeReviewRuleExplanationOverview {
  firstInputCount: number;
  finalOutputCount: number;
  finalRetainedRate: string;
  summary: string;
}

export const CODE_REVIEW_QUERY_CLEAR_KEYS = [
  'keyword',
  'repositoryName',
  'mergedAtStart',
  'mergedAtEnd',
  'projectName',
  'requestType',
  'targetBranch',
  'mergedBy',
  'moduleName',
  'illegalType',
  'mergeRequestIid',
  'owner',
];

export const CODE_REVIEW_RANGE_KEYS = {
  mergedAtRange: { startKey: 'mergedAtStart', endKey: 'mergedAtEnd' },
};

export const CODE_REVIEW_ILLEGAL_RECORD_COLUMNS: RecordTableColumn[] = [
  { key: 'mergeRequestIid', label: '合并请求编号', type: 'link', sortable: true, width: 128, fixed: 'left' },
  { key: 'mergeRequestContent', label: '合并请求内容', sortable: true, minWidth: 260 },
  { key: 'owner', label: '标注责任人', sortable: true, minWidth: 140 },
  { key: 'projectName', label: '所属项目', sortable: true, minWidth: 160 },
  { key: 'mergedAt', label: '合并时间', type: 'datetime', sortable: true, minWidth: 180 },
  { key: 'mergedBy', label: '合并人', sortable: true, minWidth: 140 },
  { key: 'moduleName', label: '模块名', sortable: true, minWidth: 140 },
  { key: 'targetBranch', label: '合并目标分支', sortable: true, minWidth: 180 },
  { key: 'illegalTypes', label: '非法类型', type: 'tags', minWidth: 220 },
  { key: 'commentRate', label: '代码注释比例(%)', sortable: true, width: 160, align: 'right' },
  { key: 'defectCount', label: '缺陷数量', type: 'number', sortable: true, width: 120, align: 'right' },
  { key: 'addedLines', label: '新增代码行数(行)', type: 'number', sortable: true, width: 150, align: 'right' },
];

export function createDefaultCodeReviewFilterOptions(): CodeReviewIllegalRecordFilterOptionsResponse {
  return {
    requestTypes: [{ label: '合并请求', value: 'merge_request' }],
    repositoryNames: [],
    illegalTypes: [],
    targetBranches: [],
    mergedBys: [],
    moduleNames: [],
    projectNames: [],
  };
}

export function createCodeReviewConditionFields(
  filterOptions: CodeReviewIllegalRecordFilterOptionsResponse,
): StatisticFilterField[] {
  return [
    selectField('repositoryName', '代码库', filterOptions.repositoryNames),
    datetimeField('mergedAt', '合并时间'),
    selectField('illegalType', '非法类型', filterOptions.illegalTypes),
    textField('keyword', '关键字', 240),
    selectField('requestType', '请求类型', filterOptions.requestTypes),
    numberField('mergeRequestIid', '合并请求编号'),
    textField('owner', '标注责任人'),
    selectField('targetBranch', '目标分支', filterOptions.targetBranches),
    selectField('mergedBy', '合并人', filterOptions.mergedBys),
    selectField('moduleName', '模块名称', filterOptions.moduleNames),
    selectField('projectName', '项目名称', filterOptions.projectNames),
    numberField('commentRate', '代码注释比例'),
    numberField('defectCount', '缺陷数量'),
    numberField('addedLines', '新增代码行数'),
  ];
}

export function createCodeReviewRuleExplanationFallback(
  reason: string,
): StatisticBoardRuleExplanationResponse {
  return {
    boardKey: 'code-review-illegal-records',
    supported: false,
    title: '代码走查非法记录规则说明',
    version: null,
    scopeDescription: null,
    summary: null,
    flowSteps: [],
    metricDefinitions: [],
    unsupportedReason: reason,
  };
}

export function buildCodeReviewRuleExplanationOverview(
  explanation: Pick<StatisticBoardRuleExplanationResponse, 'supported' | 'summary' | 'flowSteps'> | null | undefined,
): CodeReviewRuleExplanationOverview {
  const flowSteps = explanation?.flowSteps ?? [];
  const firstInputCount = flowSteps[0]?.inputCount || 0;
  const illegalTotalStep = flowSteps.find((step) => step.key === 'illegal-total') || null;
  const finalOutputCount = illegalTotalStep?.outputCount ?? (flowSteps.length ? flowSteps[flowSteps.length - 1].outputCount : 0);
  const finalRetainedRate = firstInputCount ? `${((finalOutputCount / firstInputCount) * 100).toFixed(1)}%` : '0%';

  if (!explanation?.supported) {
    return {
      firstInputCount,
      finalOutputCount,
      finalRetainedRate,
      summary: '',
    };
  }

  if (!flowSteps.length) {
    return {
      firstInputCount,
      finalOutputCount,
      finalRetainedRate,
      summary: explanation.summary || '当前页面已经启用规则说明，但暂时没有可展示的统计过程。',
    };
  }

  return {
    firstInputCount,
    finalOutputCount,
    finalRetainedRate,
    summary: `当前结果一共基于 ${firstInputCount} 条合并请求逐步检查，最终筛出 ${finalOutputCount} 条需要关注的记录，占原始数据的 ${finalRetainedRate}。`,
  };
}

export function mapCodeReviewIllegalTableRows(
  rows: CodeReviewIllegalRecordRowResponse[],
): Record<string, unknown>[] {
  return rows.map((row) => ({
    __raw: row,
    mergeRequestIid: buildGitlabResourceLinkCell(row.mergeRequestIid, row.mergeRequestLink),
    mergeRequestContent: row.mergeRequestContent,
    owner: row.owner || '-',
    projectName: row.projectName || '-',
    mergedAt: formatCodeReviewDateTime(row.mergedAt),
    mergedBy: row.mergedBy || '-',
    moduleName: row.moduleName || '-',
    targetBranch: row.targetBranch || '-',
    illegalTypes: row.illegalTypes.map((label) => ({ label, type: 'warning' as const })),
    commentRate: formatCodeReviewPercent(row.commentRate),
    defectCount: row.defectCount,
    addedLines: row.addedLines,
  }));
}

export function formatCodeReviewDateTime(value?: string | null) {
  return value ? value.replace('T', ' ').slice(0, 19) : '-';
}

export function formatCodeReviewMetric(value?: number | null, suffix = '') {
  if (value == null) {
    return '-';
  }
  return `${value}${suffix}`;
}

export function formatCodeReviewPercent(value?: number | null) {
  if (value == null) {
    return '-';
  }
  return `${value.toFixed(2)}%`;
}

function selectField(
  key: string,
  label: string,
  options: { label: string; value: string }[],
  width = DEFAULT_SELECT_WIDTH,
): StatisticFilterField {
  return {
    key,
    label,
    type: 'select',
    width,
    operators: ['eq', 'ne'],
    options,
  };
}

function textField(key: string, label: string, width = DEFAULT_TEXT_WIDTH): StatisticFilterField {
  return {
    key,
    label,
    type: 'text',
    width,
    operators: ['contains', 'eq', 'ne', 'isEmpty', 'isNotEmpty'],
    options: [],
  };
}

function numberField(key: string, label: string, width = DEFAULT_NUMBER_WIDTH): StatisticFilterField {
  return {
    key,
    label,
    type: 'number',
    width,
    operators: ['eq', 'gt', 'gte', 'lt', 'lte', 'between'],
    options: [],
  };
}

function datetimeField(key: string, label: string, width = DEFAULT_DATETIME_WIDTH): StatisticFilterField {
  return {
    key,
    label,
    type: 'datetime',
    width,
    operators: ['year', 'month', 'day', 'at', 'before', 'after', 'between'],
    options: [],
  };
}
