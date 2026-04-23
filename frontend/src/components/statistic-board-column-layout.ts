import type {
  StatisticColumnGroup,
  StatisticColumnLeaf,
  StatisticRowData,
} from '../types/api';
import { flattenStatisticColumnLeavesFromGroup } from '../types/api';
import type { StatisticBoardViewPrefs } from './statistic-board-view-prefs';

export function applyOrderedColumnsToGroup(group: StatisticColumnGroup, orderedLeafColumns: StatisticColumnLeaf[]): StatisticColumnGroup | null {
  return applyOrderedColumnsToGroupWithPrefs(group, orderedLeafColumns, {});
}

function applyOrderedColumnsToGroupWithPrefs(
  group: StatisticColumnGroup,
  orderedLeafColumns: StatisticColumnLeaf[],
  childGroupOrderByParent: Record<string, string[]>,
): StatisticColumnGroup | null {
  const directColumnKeys = new Set((group.columns ?? []).map((leaf) => leaf.key));
  const directColumns = orderedLeafColumns.filter((column) => directColumnKeys.has(column.key));
  const childrenByKey = new Map((group.children ?? []).map((child) => [child.key, child]));
  const defaultChildOrder = (group.children ?? []).map((child) => child.key);
  const orderedChildKeys = childGroupOrderByParent[group.key] ?? defaultChildOrder;
  const children = orderedChildKeys
    .map((childKey) => childrenByKey.get(childKey))
    .filter((child): child is StatisticColumnGroup => Boolean(child))
    .map((child) => {
      const childLeafKeys = new Set(flattenStatisticColumnLeavesFromGroup(child).map((column) => column.key));
      const childColumns = orderedLeafColumns.filter((column) => childLeafKeys.has(column.key));
      return applyOrderedColumnsToGroupWithPrefs(child, childColumns, childGroupOrderByParent);
    })
    .filter((child): child is StatisticColumnGroup => Boolean(child));
  if (!directColumns.length && !children.length) {
    return null;
  }
  return {
    ...group,
    children,
    columns: directColumns,
  };
}

export function resolveOrderedColumnGroups(columnGroups: StatisticColumnGroup[], prefs: StatisticBoardViewPrefs): StatisticColumnGroup[] {
  const visibleColumnKeySet = new Set(prefs.visibleColumnKeys);
  const groupMap = new Map(columnGroups.map((group) => [group.key, group]));
  return prefs.groupOrder
    .map((groupKey) => groupMap.get(groupKey))
    .filter((group): group is StatisticColumnGroup => Boolean(group))
    .map((group) => {
      const leafColumns = flattenStatisticColumnLeavesFromGroup(group);
      const columnOrder = prefs.columnOrderByGroup[group.key] ?? leafColumns.map((column) => column.key);
      const columnMap = new Map(leafColumns.map((column) => [column.key, column]));
      const orderedLeafColumns = columnOrder
        .map((columnKey) => columnMap.get(columnKey))
        .filter((column): column is StatisticColumnLeaf => Boolean(column))
        .filter((column) => visibleColumnKeySet.has(column.key));
      return applyOrderedColumnsToGroupWithPrefs(group, orderedLeafColumns, prefs.childGroupOrderByParent);
    })
    .filter((group): group is StatisticColumnGroup => Boolean(group));
}

export function visualTextUnits(text: string) {
  return Array.from(text).reduce((total, character) => total + (/[\u0000-\u00ff]/.test(character) ? 1 : 2), 0);
}

export function headerLabelMinimumWidth(label: string, reservePx: number) {
  return Math.max(84, visualTextUnits(label) * 6 + reservePx);
}

export function computeFirstColumnWidth(rows: StatisticRowData[], widthStrategy: StatisticBoardViewPrefs['widthStrategy']) {
  const longestLabelLength = rows.reduce((max, row) => Math.max(max, row.rowLabel.length), 4);
  if (widthStrategy === 'compact') {
    return Math.min(148, Math.max(112, longestLabelLength * 14 + 24));
  }
  if (widthStrategy === 'header') {
    return Math.min(176, Math.max(120, longestLabelLength * 16 + 30));
  }
  return Math.min(220, Math.max(128, longestLabelLength * 18 + 42));
}

export function computeFirstColumnMinWidth(rowHeaderLabel: string, widthStrategy: StatisticBoardViewPrefs['widthStrategy']) {
  const baseWidth =
    widthStrategy === 'compact'
      ? 100
      : widthStrategy === 'header'
        ? 114
        : 126;
  return Math.max(baseWidth, headerLabelMinimumWidth(rowHeaderLabel, 72));
}

export function columnWidth(
  column: StatisticColumnLeaf,
  widthStrategy: StatisticBoardViewPrefs['widthStrategy'],
  rows: StatisticRowData[],
) {
  if (column.metricType.includes('count') || column.metricType.includes('ratio') || column.metricType.includes('number')) {
    return widthStrategy === 'compact' ? 88 : widthStrategy === 'header' ? 116 : 148;
  }
  if (widthStrategy === 'compact') {
    return compactColumnWidth(column);
  }
  if (widthStrategy === 'header') {
    return headerBasedWidth(column);
  }
  return contentBasedWidth(column, rows);
}

export function columnMinWidth(
  column: StatisticColumnLeaf,
  widthStrategy: StatisticBoardViewPrefs['widthStrategy'],
  rows: StatisticRowData[],
) {
  return Math.max(columnWidth(column, widthStrategy, rows), headerLabelMinimumWidth(column.label, 74));
}

export function columnResizable(column: StatisticColumnLeaf) {
  return !(column.metricType.includes('count') || column.metricType.includes('ratio') || column.metricType.includes('number'));
}

function compactColumnWidth(column: StatisticColumnLeaf) {
  if (column.metricType.includes('time') || column.metricType.includes('date')) {
    return 124;
  }
  return Math.min(124, Math.max(80, column.label.length * 10 + 18));
}

function headerBasedWidth(column: StatisticColumnLeaf) {
  return Math.min(176, Math.max(104, column.label.length * 14 + 28));
}

function contentBasedWidth(column: StatisticColumnLeaf, rows: StatisticRowData[]) {
  const maxLength = rows.reduce((current, row) => {
    const valueLength = (row.cells.find((item) => item.columnKey === column.key)?.displayValue ?? '').length;
    return Math.max(current, valueLength);
  }, column.label.length);
  if (column.metricType.includes('time') || column.metricType.includes('date')) {
    return Math.min(240, Math.max(156, maxLength * 9 + 34));
  }
  return Math.min(280, Math.max(132, maxLength * 13 + 26));
}
