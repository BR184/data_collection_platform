import type { StatisticColumnLeaf, StatisticRowData } from '../types/api';

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
  const multiplier = direction === 'asc' ? 1 : -1;

  if (column.metricType.includes('count') || column.metricType.includes('ratio') || column.metricType.includes('number')) {
    return ((leftCell?.numericValue ?? 0) - (rightCell?.numericValue ?? 0)) * multiplier;
  }

  if (column.metricType.includes('time') || column.metricType.includes('date')) {
    const leftValue = Date.parse(leftCell?.displayValue ?? '') || 0;
    const rightValue = Date.parse(rightCell?.displayValue ?? '') || 0;
    return (leftValue - rightValue) * multiplier;
  }

  return String(leftCell?.displayValue ?? '').localeCompare(String(rightCell?.displayValue ?? '')) * multiplier;
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
