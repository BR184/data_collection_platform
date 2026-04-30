import type { Ref } from 'vue';
import type {
  StatisticBoardResponse,
  StatisticCellData,
  StatisticColumnLeaf,
  StatisticRowData,
} from '../types/api';
import {
  columnMinWidth as resolveColumnMinWidth,
  columnResizable as resolveColumnResizable,
} from '../components/statistic-board-column-layout';
import type { StatisticBoardViewPrefs } from '../components/statistic-board-view-prefs';

interface StatisticBoardTableAdaptersDependencies {
  board: Ref<StatisticBoardResponse | null>;
  boardViewPrefs: Ref<StatisticBoardViewPrefs>;
  openStatisticDetail: (
    row: StatisticRowData,
    cell: StatisticCellData,
    defaultPageSize: number,
  ) => Promise<void>;
}

export function useStatisticBoardTableAdapters(deps: StatisticBoardTableAdaptersDependencies) {
  async function openDetail(row: StatisticRowData, cell: StatisticCellData) {
    await deps.openStatisticDetail(
      row,
      cell,
      deps.board.value?.definition.defaultPageSize ?? 10,
    );
  }

  function cellForColumn(row: StatisticRowData, columnKey: string) {
    return row.cells.find((item) => item.columnKey === columnKey);
  }

  function columnMinWidth(column: StatisticColumnLeaf) {
    return resolveColumnMinWidth(
      column,
      deps.boardViewPrefs.value.widthStrategy,
      deps.board.value?.rows ?? [],
    );
  }

  function columnResizable(column: StatisticColumnLeaf) {
    return resolveColumnResizable(column);
  }

  return {
    openDetail,
    cellForColumn,
    columnMinWidth,
    columnResizable,
  };
}
