import type { RecordTableFilterField } from '../../types/record-table';

const SORT_FIELD_LABELS: Record<string, string> = {
  id: '编号',
  iid: '编号',
  issueIid: '议题编号',
  mergeRequestIid: '合并请求编号',
  title: '标题',
  name: '名称',
  moduleName: '模块名',
  moduleNames: '模块名',
  projectName: '所属项目',
  repositoryName: '代码库',
  owner: '负责人',
  author: '作者',
  assignee: '处理人',
  mergedBy: '合并人',
  createdAt: '创建时间',
  updatedAt: '更新时间',
  submittedAt: '提交时间',
  mergedAt: '合并时间',
  status: '状态',
  issueStatus: '议题状态',
  severity: '严重程度',
  illegalType: '非法类型',
  illegalTypes: '非法类型',
};

export interface RecordTableQuickFilterSummaryChip {
  id: string;
  label: string;
}

export interface RecordTableQuickFilterSummaryOptions {
  hasStandaloneSearch: boolean;
  searchPlaceholder?: string;
  keyword?: string;
  primaryFilters: ReadonlyArray<RecordTableFilterField>;
  filterValues: Readonly<Record<string, unknown>>;
}

/**
 * 构造快速筛选摘要标签；输入是页面快照，函数不会修改筛选值或列配置。
 */
export function buildQuickFilterSummaryChips(
  options: RecordTableQuickFilterSummaryOptions,
): RecordTableQuickFilterSummaryChip[] {
  const chips: RecordTableQuickFilterSummaryChip[] = [];
  if (options.hasStandaloneSearch && String(options.keyword ?? '').trim()) {
    chips.push({
      id: 'quick:keyword',
      label: `${options.searchPlaceholder || '关键字'} ${String(options.keyword).trim()}`,
    });
  }
  for (const filter of options.primaryFilters) {
    const label = formatQuickFilterSummaryValue(filter, options.filterValues[filter.key]);
    if (!label) {
      continue;
    }
    chips.push({
      id: `quick:${filter.key}`,
      label: `${filter.label} ${label}`,
    });
  }
  return chips;
}

/**
 * 将筛选值转换为快速筛选摘要中的展示文本；空值返回空字符串。
 */
export function formatQuickFilterSummaryValue(
  filter: RecordTableFilterField,
  value: unknown,
): string {
  if (filter.type === 'daterange') {
    if (!Array.isArray(value) || value.length !== 2 || !value[0] || !value[1]) {
      return '';
    }
    return `${value[0]} ~ ${value[1]}`;
  }
  if (filter.type === 'numberrange') {
    if (!Array.isArray(value)) {
      return '';
    }
    const minimum = value[0];
    const maximum = value[1];
    if (minimum != null && maximum != null) {
      return `${minimum} ~ ${maximum}`;
    }
    if (minimum != null) {
      return `>= ${minimum}`;
    }
    return maximum != null ? `<= ${maximum}` : '';
  }
  if (filter.type === 'select') {
    const values = Array.isArray(value)
      ? value.map((item) => String(item)).filter(Boolean)
      : [String(value ?? '').trim()].filter(Boolean);
    if (!values.length) {
      return '';
    }
    return values
      .map((item) => filter.options?.find((option) => option.value === item)?.label || item)
      .join('、');
  }
  return String(value ?? '').trim();
}

/**
 * 返回排序字段的中文展示名，未登记字段使用统一兜底文案。
 */
export function readableSortFieldLabel(fieldKey: string): string {
  return SORT_FIELD_LABELS[fieldKey] ?? '当前字段';
}

/**
 * 返回排序方向的中文展示名，未识别方向使用默认顺序。
 */
export function readableSortDirection(direction: string): string {
  if (direction === 'asc' || direction === 'ascending') {
    return '升序';
  }
  if (direction === 'desc' || direction === 'descending') {
    return '降序';
  }
  return '默认顺序';
}
