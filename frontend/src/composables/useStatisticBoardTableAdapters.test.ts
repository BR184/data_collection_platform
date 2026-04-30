import { ref } from 'vue';
import { describe, expect, it, vi } from 'vitest';
import { useStatisticBoardTableAdapters } from './useStatisticBoardTableAdapters';
import type {
  StatisticBoardResponse,
  StatisticCellData,
  StatisticColumnLeaf,
  StatisticRowData,
} from '../types/api';
import type { StatisticBoardViewPrefs } from '../components/statistic-board-view-prefs';

function createRow(): StatisticRowData {
  return {
    rowKey: 'module-a',
    rowLabel: 'Module A',
    cells: [
      { columnKey: 'count', displayValue: '12', numericValue: 12, drilldown: true, detailParams: {} },
      { columnKey: 'owner', displayValue: 'Long Owner Name', numericValue: 0, drilldown: true, detailParams: {} },
    ],
  };
}

function createBoard(defaultPageSize: number | null = 25): StatisticBoardResponse {
  return {
    definition: {
      boardKey: 'test-board',
      title: 'Test board',
      description: '',
      queryTitle: '',
      queryDescription: '',
      rowHeaderLabel: 'Row',
      filters: [],
      columnGroups: [],
      detailColumns: [],
      defaultPageSize,
      emptyText: '',
    },
    appliedFilters: {},
    appliedFilterGroup: null,
    rows: [createRow()],
    meta: {
      generatedAt: '2026-04-30T10:00:00',
      queryDurationMs: 1,
      rowCount: 1,
      columnCount: 2,
      drilldownColumnCount: 2,
    },
  };
}

function createPrefs(): StatisticBoardViewPrefs {
  return {
    visibleColumnKeys: [],
    groupOrder: [],
    childGroupOrderByParent: {},
    columnOrderByGroup: {},
    sortColumnKey: '',
    sortDirection: 'default',
    widthStrategy: 'content',
  };
}

const countColumn: StatisticColumnLeaf = {
  key: 'count',
  label: 'Count',
  drilldown: true,
  metricType: 'count',
};

const ownerColumn: StatisticColumnLeaf = {
  key: 'owner',
  label: 'Owner',
  drilldown: true,
  metricType: 'text',
};

describe('useStatisticBoardTableAdapters', () => {
  it('opens detail with the board default page size', async () => {
    const openStatisticDetail = vi.fn(() => Promise.resolve());
    const board = ref(createBoard());
    const adapters = useStatisticBoardTableAdapters({
      board,
      boardViewPrefs: ref(createPrefs()),
      openStatisticDetail,
    });
    const row = createRow();
    const cell = row.cells[0] as StatisticCellData;

    await adapters.openDetail(row, cell);

    expect(openStatisticDetail).toHaveBeenCalledWith(row, cell, 25);
  });

  it('falls back to page size 10 when board is not loaded', async () => {
    const openStatisticDetail = vi.fn(() => Promise.resolve());
    const adapters = useStatisticBoardTableAdapters({
      board: ref(null),
      boardViewPrefs: ref(createPrefs()),
      openStatisticDetail,
    });
    const row = createRow();
    const cell = row.cells[0] as StatisticCellData;

    await adapters.openDetail(row, cell);

    expect(openStatisticDetail).toHaveBeenCalledWith(row, cell, 10);
  });

  it('resolves cells and column layout helpers from current board state', () => {
    const adapters = useStatisticBoardTableAdapters({
      board: ref(createBoard()),
      boardViewPrefs: ref(createPrefs()),
      openStatisticDetail: vi.fn(() => Promise.resolve()),
    });
    const row = createRow();

    expect(adapters.cellForColumn(row, 'owner')?.displayValue).toBe('Long Owner Name');
    expect(adapters.cellForColumn(row, 'missing')).toBeUndefined();
    expect(adapters.columnMinWidth(ownerColumn)).toBeGreaterThan(120);
    expect(adapters.columnResizable(countColumn)).toBe(false);
  });
});
