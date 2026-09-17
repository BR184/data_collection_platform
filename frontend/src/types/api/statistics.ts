export interface StatisticFilterOption {
  label: string;
  value: string;
}

export type StatisticFilterFieldType = 'text' | 'select' | 'number' | 'datetime';
export type StatisticFilterOperator =
  | 'eq'
  | 'ne'
  | 'contains'
  | 'notContains'
  | 'intersects'
  | 'notIntersects'
  | 'containsAll'
  | 'notContainsAll'
  | 'partialContainsAny'
  | 'isEmpty'
  | 'isNotEmpty'
  | 'gt'
  | 'gte'
  | 'lt'
  | 'lte'
  | 'between'
  | 'year'
  | 'month'
  | 'day'
  | 'at'
  | 'before'
  | 'after';

export interface StatisticFilterField {
  key: string;
  label: string;
  type: StatisticFilterFieldType;
  placeholder?: string | null;
  defaultValue?: string | null;
  width?: number | null;
  labelDimensionKey?: string | null;
  labelGroupEnabled?: boolean | null;
  labelGroupValueType?: string | null;
  operators: StatisticFilterOperator[];
  options: StatisticFilterOption[];
}

export interface StatisticFilterCondition {
  fieldKey: string;
  operator: StatisticFilterOperator;
  value?: string | null;
  secondaryValue?: string | null;
  valueType?: 'LITERAL' | 'LABEL_GROUP' | string | null;
  labelGroupId?: number | null;
  labelGroupName?: string | null;
  values?: string[];
}

export interface StatisticFilterGroup {
  logic: 'AND' | 'OR';
  conditions: StatisticFilterCondition[];
}

export interface StatisticColumnLeaf {
  key: string;
  label: string;
  drilldown: boolean;
  metricType: string;
  headerTooltip?: string | null;
}

export interface StatisticColumnGroup {
  key: string;
  label: string;
  children?: StatisticColumnGroup[];
  columns?: StatisticColumnLeaf[];
}

export function flattenStatisticColumnLeaves(groups: StatisticColumnGroup[]): StatisticColumnLeaf[] {
  return groups.flatMap((group) => flattenStatisticColumnLeavesFromGroup(group));
}

export function flattenStatisticColumnLeavesFromGroup(group: StatisticColumnGroup): StatisticColumnLeaf[] {
  return [
    ...(group.columns ?? []),
    ...((group.children ?? []).flatMap((child) => flattenStatisticColumnLeavesFromGroup(child))),
  ];
}

export interface StatisticDetailColumn {
  key: string;
  label: string;
  width?: number | null;
  minWidth?: number | null;
  sortable: boolean;
  type?: 'tag' | 'tags' | string | null;
  expandOnly?: boolean | null;
}

export interface StatisticDetailLinkValue {
  label: string;
  href?: string | null;
}

export type StatisticDetailCellValue = string | StatisticDetailLinkValue;

export interface StatisticBoardDefinition {
  boardKey: string;
  title: string;
  description: string;
  queryTitle: string;
  queryDescription: string;
  rowHeaderLabel: string;
  filters: StatisticFilterField[];
  columnGroups: StatisticColumnGroup[];
  detailColumns: StatisticDetailColumn[];
  defaultPageSize?: number | null;
  emptyText?: string | null;
}

export interface StatisticBoardMeta {
  generatedAt: string;
  queryDurationMs: number;
  rowCount: number;
  columnCount: number;
  drilldownColumnCount: number;
}

export interface StatisticRuleFlowStepSample {
  label: string;
  detail: string;
}

export interface StatisticRuleFlowStep {
  key: string;
  title: string;
  description: string;
  inputCount: number;
  outputCount: number;
  samples: StatisticRuleFlowStepSample[];
}

export interface StatisticRuleMetricDefinition {
  key: string;
  label: string;
  definition: string;
  formula: string;
  note?: string | null;
}

export interface StatisticBoardRuleExplanationResponse {
  boardKey: string;
  supported: boolean;
  title?: string | null;
  version?: string | null;
  scopeDescription?: string | null;
  summary?: string | null;
  flowSteps: StatisticRuleFlowStep[];
  metricDefinitions: StatisticRuleMetricDefinition[];
  unsupportedReason?: string | null;
}

export interface StatisticCellData {
  columnKey: string;
  // null 表示无数据（如比率分母为 0），与真实 0 区分；排序时无数据恒置底。
  numericValue: number | null;
  displayValue: string;
  drilldown: boolean;
  detailViewKey?: string | null;
  detailParams: Record<string, string>;
}

export interface StatisticRowData {
  rowKey: string;
  rowLabel: string;
  cells: StatisticCellData[];
}

export interface StatisticBoardResponse {
  definition: StatisticBoardDefinition;
  appliedFilters: Record<string, string>;
  appliedFilterGroup?: StatisticFilterGroup | null;
  rows: StatisticRowData[];
  meta: StatisticBoardMeta;
}

export interface StatisticDetailResponse {
  title: string;
  description: string;
  columns: StatisticDetailColumn[];
  records: Record<string, unknown>[];
  total: number;
  page: number;
  size: number;
  sortField?: string | null;
  sortOrder?: string | null;
  quickFilterOptions?: Record<string, string[]> | null;
}
