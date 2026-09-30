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

function detail(overrides: Partial<StatisticDetailResponse> = {}): StatisticDetailResponse {
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
    ...overrides,
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

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

function openDetailState() {
  const deps = setup();
  const state = useStatisticBoardDetail(deps);
  state.activeRow.value = row();
  state.activeCell.value = cell({ columnKey: 'defects' });
  return { deps, state };
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
      detailCollection: '',
    });
  });

  it('opens cells declared as drillable even when the value is zero', async () => {
    const deps = setup();
    const state = useStatisticBoardDetail(deps);
    const zeroRow = row();
    zeroRow.cells[0] = cell({ columnKey: 'defects', drilldown: true, numericValue: 0 });

    await state.openDetail(zeroRow, zeroRow.cells[0], 10);

    expect(state.activeCell.value?.columnKey).toBe('defects');
    expect(deps.replaceRouteQuery).toHaveBeenCalledTimes(1);
  });

  it('keeps closed cells closed even when the value is positive', async () => {
    const deps = setup();
    const state = useStatisticBoardDetail(deps);
    const closedRow = row();
    closedRow.cells[0] = cell({ columnKey: 'defects', drilldown: false, numericValue: 9 });

    await state.openDetail(closedRow, closedRow.cells[0], 10);

    expect(state.activeRow.value).toBeNull();
    expect(deps.replaceRouteQuery).not.toHaveBeenCalled();
  });

  it('carries declared capability without consulting the numeric value', async () => {
    const deps = setup();
    const state = useStatisticBoardDetail(deps);
    const mixedRow = row();
    mixedRow.cells[0] = cell({ columnKey: 'defects', drilldown: true, numericValue: null });

    await state.openDetail(mixedRow, mixedRow.cells[0], 10);

    // 空比率的数值为 null，但能力显式为 true，仍可打开并给出 0 分母解释。
    expect(state.activeCell.value?.columnKey).toBe('defects');
  });

  it('sends the selected collection with the cell source version and resets paging', async () => {
    const deps = setup();
    const state = useStatisticBoardDetail(deps);
    state.activeRow.value = row();
    state.activeCell.value = cell({
      columnKey: 'defects',
      detailParams: { sourceVersion: 'fact-projection:v1:abc', rowKey: 'module-a' },
    });
    state.detailPagination.page = 4;

    await state.selectDetailCollection('NUMERATOR');

    expect(state.detailCollection.value).toBe('NUMERATOR');
    expect(state.detailPagination.page).toBe(1);
    expect(deps.replaceRouteQuery).toHaveBeenCalledWith({ detailCollection: 'NUMERATOR', detailPage: 1 });
    // 明细重取由路由监听触发（与分页/排序同一路径），集合切换不自行发第二次请求。
    expect(deps.loadDetails).not.toHaveBeenCalled();

    await state.loadDetail();

    expect(deps.loadDetails).toHaveBeenCalledWith('system-test-defect-summary', {
      rowKey: 'module-a',
      columnKey: 'defects',
      page: 1,
      size: 10,
      sortField: undefined,
      sortOrder: 'descending',
      filters: { sourceVersion: 'fact-projection:v1:abc', rowKey: 'module-a', population: 'NUMERATOR' },
      filterGroup: { logic: 'AND', conditions: [] },
    });
  });

  it('restores the collection from the route query when reopening a deep link', async () => {
    const deps = setup();
    const state = useStatisticBoardDetail(deps);

    await state.syncFromRoute(
      {
        detailVisible: '1',
        detailRowKey: 'module-a',
        detailColumnKey: 'defects',
        detailCollection: 'DENOMINATOR',
      },
      [row()],
      10,
    );

    expect(state.detailCollection.value).toBe('DENOMINATOR');
    expect(deps.loadDetails).toHaveBeenCalledWith(
      'system-test-defect-summary',
      expect.objectContaining({ filters: expect.objectContaining({ population: 'DENOMINATOR' }) }),
    );
  });

  it('surfaces the collection list returned by the backend', async () => {
    const deps = setup();
    deps.loadDetails = vi.fn(() =>
      Promise.resolve(
        detail({
          collections: [
            { key: 'COUNTED', label: '计数集合', description: '贡献议题' },
          ],
          collection: 'COUNTED',
        }),
      ),
    );
    const state = useStatisticBoardDetail(deps);
    state.activeRow.value = row();
    state.activeCell.value = state.activeRow.value.cells[0];

    await state.loadDetail();

    expect(state.detail.value?.collections?.[0]?.key).toBe('COUNTED');
    expect(state.detail.value?.collection).toBe('COUNTED');
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

  it('drops a late success so an older page cannot overwrite the newest one', async () => {
    const { deps, state } = openDetailState();
    const older = deferred<StatisticDetailResponse>();
    const newer = deferred<StatisticDetailResponse>();
    deps.loadDetails = vi
      .fn()
      .mockReturnValueOnce(older.promise)
      .mockReturnValueOnce(newer.promise);

    // 第一页请求发出后立刻切到第二页：两个请求并发，旧请求后返回。
    state.detailPagination.page = 1;
    const firstLoad = state.loadDetail();
    state.detailPagination.page = 2;
    const secondLoad = state.loadDetail();

    newer.resolve(detail({ page: 2, records: [{ title: 'Issue 2' }] }));
    await secondLoad;
    expect(state.detail.value?.records).toEqual([{ title: 'Issue 2' }]);

    older.resolve(detail({ page: 1, records: [{ title: 'Issue 1' }] }));
    await firstLoad;

    expect(state.detail.value?.records).toEqual([{ title: 'Issue 2' }]);
    expect(state.detailLoading.value).toBe(false);
  });

  it('drops a late failure so an older page cannot raise an error over newer data', async () => {
    const { deps, state } = openDetailState();
    const older = deferred<StatisticDetailResponse>();
    const newer = deferred<StatisticDetailResponse>();
    deps.loadDetails = vi
      .fn()
      .mockReturnValueOnce(older.promise)
      .mockReturnValueOnce(newer.promise);

    state.detailPagination.page = 1;
    const firstLoad = state.loadDetail();
    state.detailPagination.page = 2;
    const secondLoad = state.loadDetail();

    newer.resolve(detail({ page: 2, records: [{ title: 'Issue 2' }] }));
    await secondLoad;

    older.reject(new Error('第一页请求失败'));
    await firstLoad;

    expect(state.detail.value?.records).toEqual([{ title: 'Issue 2' }]);
    expect(deps.notifyError).not.toHaveBeenCalled();
  });

  it('keeps loading until the newest detail request finishes', async () => {
    const { deps, state } = openDetailState();
    const first = deferred<StatisticDetailResponse>();
    const second = deferred<StatisticDetailResponse>();
    deps.loadDetails = vi
      .fn()
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise);

    const firstLoad = state.loadDetail();
    const secondLoad = state.loadDetail();

    first.resolve(detail());
    await firstLoad;
    expect(state.detailLoading.value).toBe(true);

    second.resolve(detail());
    await secondLoad;
    expect(state.detailLoading.value).toBe(false);
  });

  it('drops responses that arrive after the detail was closed', async () => {
    const { deps, state } = openDetailState();
    const pending = deferred<StatisticDetailResponse>();
    deps.loadDetails = vi.fn(() => pending.promise);

    const load = state.loadDetail();
    state.handleDetailVisibleChange(false);
    pending.resolve(detail({ records: [{ title: 'Issue 1' }] }));
    await load;

    expect(state.detail.value).toBeNull();
    expect(state.detailLoading.value).toBe(false);
    expect(deps.notifyError).not.toHaveBeenCalled();
  });

  it('drops a failure that arrives after the detail was closed', async () => {
    const { deps, state } = openDetailState();
    const pending = deferred<StatisticDetailResponse>();
    deps.loadDetails = vi.fn(() => pending.promise);

    const load = state.loadDetail();
    state.handleDetailVisibleChange(false);
    pending.reject(new Error('关闭后失败'));
    await load;

    expect(deps.notifyError).not.toHaveBeenCalled();
  });

  it('issues exactly one request per quick filter change', async () => {
    const { deps, state } = openDetailState();
    state.detailPagination.page = 1;

    // 已在第一页时路由不会变化，必须由自己发起请求，且只发一次。
    state.handleDetailQuickFilterChange('bugStatus', '处理中');
    expect(deps.loadDetails).toHaveBeenCalledTimes(1);
    expect(deps.replaceRouteQuery).not.toHaveBeenCalled();

    // 不在第一页时只改路由、由路由监听统一重取，不得在此再发一次请求。
    state.detailPagination.page = 3;
    state.handleDetailQuickFilterChange('bugStatus', '已修复');

    expect(deps.loadDetails).toHaveBeenCalledTimes(1);
    expect(deps.replaceRouteQuery).toHaveBeenCalledWith({ detailPage: 1 });
  });

  it('clears quick filters through the same single-request path', async () => {
    const { deps, state } = openDetailState();
    state.detailPagination.page = 1;
    state.handleDetailQuickFilterChange('bugStatus', '处理中');
    expect(deps.loadDetails).toHaveBeenCalledTimes(1);

    await state.resetDetailQuickFilters();

    expect(state.detailQuickFilterValues).toEqual({});
    expect(deps.loadDetails).toHaveBeenCalledTimes(2);
  });

  it('sends the quick filter value with the detail request', async () => {
    const { deps, state } = openDetailState();
    state.detailPagination.page = 1;

    state.handleDetailQuickFilterChange('bugStatus', '处理中');

    expect(deps.loadDetails).toHaveBeenLastCalledWith(
      'system-test-defect-summary',
      expect.objectContaining({ filters: expect.objectContaining({ bugStatus: '处理中' }) }),
    );
  });
});
