import type {
  CodeReviewIllegalRecordFilterOptionsResponse,
  CodeReviewIllegalRecordRowResponse,
  StatisticBoardRuleExplanationResponse,
  StatisticFilterField,
} from '../types/api';
import type { RecordTableActiveFilterTag, RecordTableColumn, RecordTableFilterField } from '../types/record-table';
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
  { key: 'mergeRequestIid', label: '合并请求编号', type: 'link', sortable: true, width: 108, fixed: 'left' },
  { key: 'mergeRequestContent', label: '合并请求内容', sortable: true, minWidth: 360 },
  { key: 'author', label: '被走查人', sortable: true, width: 112 },
  { key: 'projectName', label: '所属项目', sortable: true, width: 112 },
  { key: 'mergedAt', label: '合并时间', type: 'datetime', sortable: true, width: 156 },
  { key: 'mergedBy', label: '合并人', sortable: true, width: 104 },
  { key: 'moduleName', label: '模块名', sortable: true, width: 112 },
  { key: 'targetBranch', label: '合并目标分支', sortable: true, width: 122 },
  { key: 'illegalTypes', label: '非法类型', type: 'tags', minWidth: 320 },
  { key: 'commentRate', label: '代码注释比例（%）', sortable: true, width: 132 },
  { key: 'defectCount', label: '缺陷数量', type: 'number', sortable: true, width: 104 },
  { key: 'addedLines', label: '新增代码行数（行）', type: 'number', sortable: true, width: 132 },
];

export function buildCodeReviewIllegalRecordColumns(
  legacyMode = false,
  dgmLegacyMode = false,
): RecordTableColumn[] {
  if (!legacyMode) {
    return CODE_REVIEW_ILLEGAL_RECORD_COLUMNS;
  }
  const columns = CODE_REVIEW_ILLEGAL_RECORD_COLUMNS.map((column) =>
    column.key === 'projectName' ? { ...column, key: 'repositoryName' } : column,
  );
  //兼容模式-MatchMode：老平台 DGM 页签固定 name=DGM，不展示 CC 页签的“所属项目/nameSearch”列。
  return dgmLegacyMode ? columns.filter((column) => column.key !== 'repositoryName') : columns;
}

export function createDefaultCodeReviewFilterOptions(): CodeReviewIllegalRecordFilterOptionsResponse {
  return {
    requestTypes: [{ label: '合并请求', value: 'merge_request' }],
    projects: [],
    repositoryNames: [],
    illegalTypes: [],
    targetBranches: [],
    owners: [],
    mergedBys: [],
    moduleNames: [],
    projectNames: [],
  };
}

export function createCodeReviewConditionFields(
  filterOptions: CodeReviewIllegalRecordFilterOptionsResponse,
  legacyMode = false,
): StatisticFilterField[] {
  return [
    selectField('repositoryName', legacyMode ? '所属项目' : '代码库', filterOptions.repositoryNames),
    datetimeField('mergedAt', '合并时间'),
    selectField('illegalType', '非法类型', filterOptions.illegalTypes, DEFAULT_SELECT_WIDTH, ['contains', 'notContains']),
    textField('keyword', '合并请求内容', 240),
    selectField('requestType', '请求类型', filterOptions.requestTypes),
    numberField('mergeRequestIid', '合并请求编号'),
    legacyMode
      ? selectField('owner', '被走查人', filterOptions.owners)
      : textField('owner', '被走查人'),
    selectField('targetBranch', '目标分支', filterOptions.targetBranches),
    selectField('mergedBy', '合并人', filterOptions.mergedBys),
    selectField('moduleName', '模块名称', filterOptions.moduleNames),
    selectField('projectName', '项目名称', filterOptions.projectNames),
    numberField('commentRate', '代码注释比例'),
    numberField('defectCount', '缺陷数量'),
    numberField('addedLines', '新增代码行数'),
  ];
}

export function buildCodeReviewPrimaryFilters(
  filterOptions: CodeReviewIllegalRecordFilterOptionsResponse,
  legacyMode = false,
): RecordTableFilterField[] {
  const filters: RecordTableFilterField[] = [
    { key: 'keyword', label: '任意关键字', type: 'input', placeholder: '输入任意关键字搜索', width: 260 },
    { key: 'mergeRequestIid', label: '合并请求编号', type: 'input', placeholder: '输入合并请求ID' },
    legacyMode
      ? {
          key: 'owner',
          label: '被走查人',
          type: 'select',
          options: [{ label: '全部被走查人', value: '' }, ...filterOptions.owners],
        }
      : { key: 'owner', label: '被走查人', type: 'input', placeholder: '输入被走查人' },
    {
      key: 'mergedBy',
      label: '合并人',
      type: 'select',
      options: [{ label: '全部合并人', value: '' }, ...filterOptions.mergedBys],
    },
    {
      key: 'moduleName',
      label: '模块名',
      type: 'select',
      options: [{ label: '全部模块', value: '' }, ...filterOptions.moduleNames],
    },
    {
      key: 'targetBranch',
      label: '合并目标分支',
      type: 'select',
      width: 180,
      options: [{ label: '全部目标分支', value: '' }, ...filterOptions.targetBranches],
    },
    {
      key: 'illegalType',
      label: '非法类型',
      type: 'select',
      width: 220,
      options: [{ label: '全部非法类型', value: '' }, ...filterOptions.illegalTypes],
    },
  ];
  if (legacyMode) {
    filters.push({
      key: 'projectName',
      label: '项目名称',
      type: 'select',
      width: 180,
      options: [{ label: '全部项目名称', value: '' }, ...filterOptions.projectNames],
    });
  } else {
    filters.push({
      key: 'repositoryName',
      label: '代码库',
      type: 'select',
      width: 180,
      options: [{ label: '全部代码库', value: '' }, ...filterOptions.repositoryNames],
    });
  }
  filters.push(
    {
      key: 'mergedAtRange',
      label: '合并时间',
      type: 'daterange',
      width: 280,
      startPlaceholder: '开始日期',
      endPlaceholder: '结束日期',
    },
  );
  return filters;
}

export function buildCodeReviewQuickFilterTags(
  values: Record<string, unknown>,
  legacyMode = false,
): RecordTableActiveFilterTag[] {
  const tags: RecordTableActiveFilterTag[] = [];
  pushTag(tags, 'mergeRequestIid', '合并请求编号', values.mergeRequestIid);
  pushTag(tags, 'keyword', '合并请求内容', values.keyword);
  pushTag(tags, 'owner', '被走查人', values.owner);
  pushTag(tags, 'mergedBy', '合并人', values.mergedBy);
  pushTag(tags, 'moduleName', '模块名', values.moduleName);
  pushTag(tags, 'targetBranch', '合并目标分支', values.targetBranch);
  pushTag(tags, 'illegalType', '非法类型', values.illegalType);
  pushTag(tags, 'projectName', '项目名称', values.projectName);
  pushTag(tags, 'repositoryName', legacyMode ? '所属项目' : '代码库', values.repositoryName);
  if (Array.isArray(values.mergedAtRange) && values.mergedAtRange.length === 2) {
    tags.push({
      key: 'mergedAtRange',
      label: '合并时间',
      value: `${values.mergedAtRange[0]} ~ ${values.mergedAtRange[1]}`,
    });
  }
  return tags;
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
  return rows.map((row, index) => ({
    id: [
      row.sourceInstance || 'default',
      row.repositoryName || '-',
      row.mergeRequestIid ?? '-',
      row.author || '-',
      row.reviewerNames || '-',
      row.codeWalkthroughDate || '-',
      row.mergedAt || '-',
      index,
    ].join('|'),
    __raw: row,
    mergeRequestIid: buildGitlabResourceLinkCell(row.mergeRequestIid, row.mergeRequestLink),
    mergeRequestContent: row.mergeRequestContent,
    author: row.author || '-',
    projectName: row.projectName || '-',
    repositoryName: row.repositoryName || '-',
    mergedAt: formatCodeReviewDateTime(row.mergedAt),
    mergedBy: row.mergedBy || '-',
    moduleName: row.moduleName || '-',
    targetBranch: row.targetBranch || '-',
    illegalTypes: normalizeIllegalTypeLabels(row.illegalTypes).map((label) => ({ label, type: 'warning' as const })),
    commentRate: formatCodeReviewPercent(row.commentRate),
    defectCount: row.defectCount,
    addedLines: row.addedLines,
  }));
}

export function formatCodeReviewDateTime(value?: string | null) {
  return value ? value.replace('T', ' ').slice(0, 19) : '-';
}

export function formatCodeReviewDate(value?: string | null) {
  return value ? value.replace('T', ' ').slice(0, 10) : '-';
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

function normalizeIllegalTypeLabels(values: string[] = []) {
  return [
    ...new Set(
      values
        .flatMap((value) => String(value ?? '').split(/[、,，;；]/))
        .map((value) => value.trim())
        .filter(Boolean),
    ),
  ];
}

function selectField(
  key: string,
  label: string,
  options: { label: string; value: string }[],
  width = DEFAULT_SELECT_WIDTH,
  operators: StatisticFilterField['operators'] = ['eq', 'ne'],
): StatisticFilterField {
  return {
    key,
    label,
    type: 'select',
    width,
    operators,
    options,
  };
}

function pushTag(tags: RecordTableActiveFilterTag[], key: string, label: string, value: unknown) {
  const text = String(value ?? '').trim();
  if (text) {
    tags.push({ key, label, value: text });
  }
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
