import { describe, expect, it, vi } from 'vitest';
import { useStatisticBoardDetail } from './useStatisticBoardDetail';
import type { StatisticCellData, StatisticDetailResponse, StatisticFilterGroup, StatisticRowData } from '../types/api';

function row(): StatisticRowData {
  return {
    rowKey: 'module-a',
    rowLabel: 'Module A',
    cells: [
      cell({ columnKey: 'defects', drilldown: true }),
      cell({ columnKey: 'closed', drilldown: false }),
    ],
  };
}

function cell(overrides: Partial<StatisticCellData> = {}): StatisticCellData {
  return {
    columnKey: 'defects',
    numericValue: 3,
    displayValue: '3',
    drilldown: true,
    detailParams: {},
    ...overrides,
  };
}

function detail(): StatisticDetailResponse {
  return {
    title: 'Detail',
    description: 'Detail rows',
    columns: [{ key: 'title', label: 'Title', sortable: true }],
    records: [{ title: 'Issue 1' }],
    total: 1,
    page: 1,
    size: 10,
    sortField: 'syncedAt',
    sortOrder: 'descending',
  };
}

function setup() {
  const filterGroup: StatisticFilterGroup = { logic: 'AND', conditions: [] };
  return {
    boardKey: vi.fn(() => 'system-test-defect-summary'),
    getFilterGroup: vi.fn(() => filterGroup),
    loadDetails: vi.fn(() => Promise.resolve(detail())),
    notifyError: vi.fn(),
    replaceRouteQuery: vi.fn(() => Promise.resolve()),
  };
}

describe('useStatisticBoardDetail', () => {
  it('opens drilldown cells through route query', async () => {
    const deps = setup();
    const state = useStatisticBoardDetail(deps);
    const currentRow = row();

    await state.openDetail(currentRow, currentRow.cells[0], 25);

    expect(state.activeRow.value?.rowKey).toBe('module-a');
    expect(state.activeCell.value?.columnKey).toBe('defects');
    expect(deps.replaceRouteQuery).toHaveBeenCalledWith({
      detailVisible: '1',
      detailRowKey: 'module-a',
      detailColumnKey: 'defects',
      detailPage: 1,
      detailPageSize: 25,
      detailSortBy: 'syncedAt',
      detailSortOrder: 'descending',
    });
  });

  it('ignores cells without drilldown', async () => {
    const deps = setup();
    const state = useStatisticBoardDetail(deps);
    const currentRow = row();

    await state.openDetail(currentRow, currentRow.cells[1], 10);

    expect(state.activeRow.value).toBeNull();
    expect(state.activeCell.value).toBeNull();
    expect(deps.replaceRouteQuery).not.toHaveBeenCalled();
  });

  it('loads detail rows with current pagination, sorting and filter group', async () => {
    const deps = setup();
    const state = useStatisticBoardDetail(deps);
    state.activeRow.value = row();
    state.activeCell.value = state.activeRow.value.cells[0];
    state.detailPagination.page = 3;
    state.detailPagination.size = 50;
    state.detailPagination.sortField = 'updatedAt';
    state.detailPagination.sortOrder = 'ascending';

    await state.loadDetail();

    expect(deps.loadDetails).toHaveBeenCalledWith('system-test-defect-summary', {
      rowKey: 'module-a',
      columnKey: 'defects',
      page: 3,
      size: 50,
      sortField: 'updatedAt',
      sortOrder: 'ascending',
      filterGroup: { logic: 'AND', conditions: [] },
    });
    expect(state.detail.value?.records).toEqual([{ title: 'Issue 1' }]);
    expect(state.detailLoading.value).toBe(false);
  });

  it('preserves structured detail cell link values', () => {
    const deps = setup();
    const state = useStatisticBoardDetail(deps);

    expect(
      state.detailCellValue(
        {
          iid: {
            label: '301',
            href: 'http://gitlab.example.com/-/issues/301',
          },
        },
        { key: 'iid', label: 'IID', sortable: true },
      ),
    ).toEqual({
      label: '301',
      href: 'http://gitlab.example.com/-/issues/301',
    });
  });

  it('syncs and clears detail state from route query', async () => {
    const deps = setup();
    const state = useStatisticBoardDetail(deps);
    const currentRow = row();

    await state.syncFromRoute(
      {
        detailVisible: '1',
        detailRowKey: 'module-a',
        detailColumnKey: 'defects',
        detailPage: '2',
        detailPageSize: '20',
        detailSortBy: 'createdAt',
        detailSortOrder: 'ascending',
      },
      [currentRow],
      10,
    );

    expect(state.detailVisible.value).toBe(true);
    expect(state.activeRow.value?.rowKey).toBe('module-a');
    expect(state.activeCell.value?.columnKey).toBe('defects');
    expect(state.detailPagination.page).toBe(2);
    expect(state.detailPagination.size).toBe(20);
    expect(deps.loadDetails).toHaveBeenCalledTimes(1);

    await state.syncFromRoute({}, [currentRow], 10);

    expect(state.detailVisible.value).toBe(false);
    expect(state.activeRow.value).toBeNull();
    expect(state.activeCell.value).toBeNull();
    expect(state.detail.value).toBeNull();
  });
});
