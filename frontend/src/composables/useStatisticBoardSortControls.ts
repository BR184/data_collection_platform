import { computed, type Ref } from 'vue';
import { flattenStatisticColumnLeavesFromGroup, type StatisticBoardResponse } from '../types/api';
import type { StatisticBoardViewPrefs } from '../components/statistic-board-view-prefs';
import {
  nextColumnSortState,
  ROW_LABEL_SORT_KEY,
  type SortDirection,
  sortDirectionForColumn as resolveSortDirectionForColumn,
} from '../components/statistic-board-sorting';

interface UseStatisticBoardSortControlsOptions {
  board: Ref<StatisticBoardResponse | null>;
  boardViewPrefs: Ref<StatisticBoardViewPrefs>;
  persistViewPrefs: () => void;
  replaceRouteQuery: (patch: Record<string, string | number | null | undefined>) => void | Promise<void>;
}

export function useStatisticBoardSortControls(options: UseStatisticBoardSortControlsOptions) {
  const currentSortColumn = computed(() => {
    const board = options.board.value;
    const prefs = options.boardViewPrefs.value;
    if (!board || !prefs.sortColumnKey || prefs.sortDirection === 'default') {
      return null;
    }
    if (prefs.sortColumnKey === ROW_LABEL_SORT_KEY) {
      return {
        key: ROW_LABEL_SORT_KEY,
        label: '统计对象',
      };
    }
    return (
      board.definition.columnGroups
        .flatMap((group) => flattenStatisticColumnLeavesFromGroup(group))
        .find((column) => column.key === prefs.sortColumnKey) ?? null
    );
  });

  const currentSortSummary = computed(() => {
    if (!currentSortColumn.value) {
      return '';
    }
    return `${currentSortColumn.value.label} / ${options.boardViewPrefs.value.sortDirection === 'asc' ? '升序' : '降序'}`;
  });

  function sortDirectionForColumn(columnKey: string) {
    return resolveSortDirectionForColumn(options.boardViewPrefs.value, columnKey);
  }

  function toggleColumnSort(columnKey: string) {
    const nextSortState = nextColumnSortState(options.boardViewPrefs.value, columnKey);
    options.boardViewPrefs.value = {
      ...options.boardViewPrefs.value,
      ...nextSortState,
    };
    options.persistViewPrefs();
    void options.replaceRouteQuery({
      sortBy: nextSortState.sortColumnKey,
      sortOrder: nextSortState.sortDirection,
    });
  }

  function sortStateLabel(direction: SortDirection) {
    if (direction === 'asc') {
      return '当前为升序，点击切换为降序';
    }
    if (direction === 'desc') {
      return '当前为降序，点击切换为升序';
    }
    return '当前为默认顺序，点击开始排序';
  }

  return {
    currentSortColumn,
    currentSortSummary,
    sortDirectionForColumn,
    toggleColumnSort,
    sortStateLabel,
  };
}
