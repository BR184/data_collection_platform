import { computed, type Ref } from 'vue';
import { flattenStatisticColumnLeaves, type StatisticBoardResponse } from '../types/api';
import type { StatisticBoardViewPrefs } from '../components/statistic-board-view-prefs';
import { sortRowsFromSource } from '../components/statistic-board-sorting';
import {
  computeFirstColumnMinWidth,
  computeFirstColumnWidth,
  resolveOrderedColumnGroups,
} from '../components/statistic-board-column-layout';

export interface StatisticBoardTableStateOptions {
  board: Ref<StatisticBoardResponse | null>;
  boardViewPrefs: Ref<StatisticBoardViewPrefs>;
  tableCurrentPage: Ref<number>;
  tablePageSize: Ref<number>;
  boardKey: () => string;
}

export function useStatisticBoardTableState(options: StatisticBoardTableStateOptions) {
  const activeFilterFields = computed(() => options.board.value?.definition.filters ?? []);

  const orderedColumnGroups = computed(() => {
    if (!options.board.value) {
      return [];
    }
    return resolveOrderedColumnGroups(options.board.value.definition.columnGroups, options.boardViewPrefs.value);
  });

  const sortedRows = computed(() => {
    const rows = options.board.value?.rows ?? [];
    const columns = options.board.value?.definition.columnGroups
      ? flattenStatisticColumnLeaves(options.board.value.definition.columnGroups)
      : [];
    return sortRowsFromSource(rows, columns, options.boardViewPrefs.value);
  });

  const totalTableRows = computed(() => sortedRows.value.length);

  const paginatedRows = computed(() => {
    const start = (options.tableCurrentPage.value - 1) * options.tablePageSize.value;
    return sortedRows.value.slice(start, start + options.tablePageSize.value);
  });

  const tableRenderKey = computed(() =>
    [
      options.boardKey(),
      options.boardViewPrefs.value.widthStrategy,
      options.boardViewPrefs.value.groupOrder.join('|'),
      Object.entries(options.boardViewPrefs.value.childGroupOrderByParent)
        .map(([groupKey, childKeys]) => `${groupKey}:${childKeys.join(',')}`)
        .join('|'),
      Object.entries(options.boardViewPrefs.value.columnOrderByGroup)
        .map(([groupKey, columnKeys]) => `${groupKey}:${columnKeys.join(',')}`)
        .join('|'),
      options.boardViewPrefs.value.visibleColumnKeys.join('|'),
    ].join('::'),
  );

  const rowHeaderLabel = computed(() => options.board.value?.definition.rowHeaderLabel || '统计对象');

  const firstColumnWidth = computed(() =>
    computeFirstColumnWidth(options.board.value?.rows ?? [], options.boardViewPrefs.value.widthStrategy),
  );

  const firstColumnMinWidth = computed(() =>
    computeFirstColumnMinWidth(rowHeaderLabel.value, options.boardViewPrefs.value.widthStrategy),
  );

  return {
    activeFilterFields,
    orderedColumnGroups,
    sortedRows,
    totalTableRows,
    paginatedRows,
    tableRenderKey,
    rowHeaderLabel,
    firstColumnWidth,
    firstColumnMinWidth,
  };
}
