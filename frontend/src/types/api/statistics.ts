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
  /** 降级产出的数据时刻；来源已收敛到最新变化版本时缺失。 */
  dataAsOf?: string | null;
  /** 尚未发布到最新变化版本的稳定根数量；来源已收敛时缺失。 */
  pendingUpdates?: number | null;
}

export interface StatisticDetailCollection {
  key: string;
  label: string;
  description: string;
}

/** 成员选择语义：ALL 不限、MISSING 成员缺失、VALUE 精确成员名。 */
export type StatisticBoardMemberSelectionKind = 'ALL' | 'MISSING' | 'VALUE';

/**
 * 统计板成员候选项。
 *
 * <p>类型与取值分开：MISSING 候选项的 value 恒为空串，VALUE 候选项的 value 就是成员名本身。
 * 成员名因此可以是任意文本（含 `__missing__` 或与缺失文案同形的名称），不需要哨兵也不会互相顶替。
 */
export interface StatisticBoardControlOption {
  kind: StatisticBoardMemberSelectionKind;
  value: string;
  label: string;
}

/** 一个控制参数的候选组。 */
export interface StatisticBoardControlOptionGroup {
  key: string;
  options: StatisticBoardControlOption[];
}

/** 统计板成员候选：由后端在同一窄事实的完整基础范围上求得。 */
export interface StatisticBoardControlOptions {
  scopeKey: string;
  sourceVersion: string;
  /** false 表示来源或范围不可判定，必须展示 reason 而不是空候选。 */
  scopeReadable: boolean;
  reason: string;
  groups: StatisticBoardControlOptionGroup[];
}

export interface StatisticDetailResponse {
  title: string;
  description: string;
  /** 当前指标允许的全部下钻集合；长度不大于 1 时前端不渲染切换控件。 */
  collections?: StatisticDetailCollection[] | null;
  /** 本次实际返回的集合键。 */
  collection?: string | null;
  columns: StatisticDetailColumn[];
  records: Record<string, unknown>[];
  total: number;
  page: number;
  size: number;
  sortField?: string | null;
  sortOrder?: string | null;
  quickFilterOptions?: Record<string, string[]> | null;
}
