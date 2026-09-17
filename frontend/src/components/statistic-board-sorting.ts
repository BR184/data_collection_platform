import type { StatisticColumnLeaf, StatisticRowData } from '../types/api';
import { hasNumericSortKey } from './statistic-board-metric';
import { compareNumericNullsLast, compareTextNullsLast } from '../utils/missing-value-sorting';

export type SortDirection = 'default' | 'asc' | 'desc';

export const ROW_LABEL_SORT_KEY = '__row_label__';

const SUMMARY_ROW_KEYS = new Set(['__total__', '__ratio__']);
const SUMMARY_ROW_LABELS = new Set(['总计', '共计', '总数', '合计', '汇总', '比例']);

export interface StatisticBoardSortState {
  sortColumnKey: string;
  sortDirection: SortDirection;
}

function cellForColumn(row: StatisticRowData, columnKey: string) {
  return row.cells.find((item) => item.columnKey === columnKey);
}

export function isStatisticSummaryRow(row: StatisticRowData) {
  return SUMMARY_ROW_KEYS.has(row.rowKey) || SUMMARY_ROW_LABELS.has(row.rowLabel.trim());
}

function compareColumnValues(
  left: StatisticRowData,
  right: StatisticRowData,
  column: StatisticColumnLeaf,
  direction: Exclude<SortDirection, 'default'>,
) {
  const leftCell = cellForColumn(left, column.key);
  const rightCell = cellForColumn(right, column.key);

  if (hasNumericSortKey(column.metricType)) {
    // 比率的“无数据”单元格 numericValue 为 null（显示 `/`），与真实 0 严格区分并恒排最后，
    // 避免升降序下两者比较恒等、只能按后端原始行序交错。
    return compareNumericNullsLast(leftCell?.numericValue, rightCell?.numericValue, direction);
  }

  // 文本（含时间文本，看板时间列以 ISO 文本下发，字典序即时序）：空值与 `/` 视为无数据恒置底。
  return compareTextNullsLast(leftCell?.displayValue, rightCell?.displayValue, direction);
}

function compareRowLabelValues(
  left: StatisticRowData,
  right: StatisticRowData,
  direction: Exclude<SortDirection, 'default'>,
) {
  const multiplier = direction === 'asc' ? 1 : -1;
  return left.rowLabel.localeCompare(right.rowLabel) * multiplier;
}

function sortNonSummaryRows(
  rows: StatisticRowData[],
  compare: (left: { row: StatisticRowData; index: number }, right: { row: StatisticRowData; index: number }) => number,
) {
  const indexedRows = rows.map((row, index) => ({ row, index }));
  const sortedNormalRows = indexedRows
    .filter((item) => !isStatisticSummaryRow(item.row))
    .sort(compare);

  let normalIndex = 0;
  return indexedRows.map((item) => {
    if (isStatisticSummaryRow(item.row)) {
      return item.row;
    }
    const sortedItem = sortedNormalRows[normalIndex];
    normalIndex += 1;
    return sortedItem.row;
  });
}

export function sortDirectionForColumn(sortState: StatisticBoardSortState, columnKey: string): SortDirection {
  if (sortState.sortColumnKey !== columnKey) {
    return 'default';
  }
  return sortState.sortDirection;
}

export function nextSortDirection(direction: SortDirection): Exclude<SortDirection, 'default'> {
  return direction === 'desc' ? 'asc' : 'desc';
}

export function nextColumnSortState(
  sortState: StatisticBoardSortState,
  columnKey: string,
): StatisticBoardSortState {
  const currentDirection = sortDirectionForColumn(sortState, columnKey);
  return {
    sortColumnKey: columnKey,
    sortDirection: currentDirection === 'default' ? 'desc' : nextSortDirection(currentDirection),
  };
}

export function clearSortState(): StatisticBoardSortState {
  return {
    sortColumnKey: '',
    sortDirection: 'default',
  };
}

/**
 * 从看板原始行重算排序，供翻页、切换排序列和切换方向共用，避免在已排序结果上叠加排序。
 *
 * 排序规则：总计/比例等汇总行保持原位不参与排序；数值列按 `numericValue` 比较，无数据
 * （比率为 `/` 时下发 null）恒排最后且与真实 0 区分；其余列按显示文本比较，空值与 `/` 同样置底。
 * 方向只作用于有值元素，同值（含同为无数据）按后端原始行序稳定兜底。
 *
 * @param rows 后端返回的完整看板行。
 * @param columns 展平后的叶子列定义，用于按 key 找到列类型。
 * @param sortState 当前排序列与方向；`default` 或未指定列时原样返回。
 * @returns 新的行数组；不修改入参。
 */
export function sortRowsFromSource(
  rows: StatisticRowData[],
  columns: StatisticColumnLeaf[],
  sortState: StatisticBoardSortState,
): StatisticRowData[] {
  if (!sortState.sortColumnKey || sortState.sortDirection === 'default') {
    return rows;
  }

  const effectiveDirection = sortState.sortDirection as Exclude<SortDirection, 'default'>;
  if (sortState.sortColumnKey === ROW_LABEL_SORT_KEY) {
    return sortNonSummaryRows(
      rows,
      (left, right) => compareRowLabelValues(left.row, right.row, effectiveDirection) || left.index - right.index,
    );
  }

  const column = columns.find((item) => item.key === sortState.sortColumnKey);
  if (!column) {
    return rows;
  }

  return sortNonSummaryRows(
    rows,
    (left, right) => compareColumnValues(left.row, right.row, column, effectiveDirection) || left.index - right.index,
  );
}
