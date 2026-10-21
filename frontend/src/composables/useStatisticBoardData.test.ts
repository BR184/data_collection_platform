import { afterEach, describe, expect, it, vi } from 'vitest';
import { useStatisticBoardData, type PreparedStatisticDetail } from './useStatisticBoardData';
import type { StatisticBoardControlOptions, StatisticBoardResponse, ReadViewMetadata, ReadViewTransport } from '../types/api';

function createBoard(): StatisticBoardResponse {
  return {
    definition: {
      boardKey: 'code-review',
      title: '代码评审统计',
      description: '',
      queryTitle: '',
      queryDescription: '',
      rowHeaderLabel: '项目',
      filters: [],
      columnGroups: [],
      detailColumns: [],
      defaultPageSize: 20,
    },
    appliedFilters: {},
    rows: [],
    meta: {
      generatedAt: '2026-04-29T09:00:00Z',
      queryDurationMs: 3,
      rowCount: 0,
      columnCount: 0,
      drilldownColumnCount: 0,
    },
  };
}

const activeStates: ReturnType<typeof useStatisticBoardData>[] = [];
afterEach(() => { for (const state of activeStates.splice(0)) { state.dispose(); } vi.useRealTimers(); });
function metadata(id: string): ReadViewMetadata {
  return { readContextId: id, pageKey: 'code-review', scopeKey: 'scope', sourceVersion: 'publication:fixture',
    businessDate: '2026-10-13', dataAsOf: '2026-10-13T12:00:00Z', leaseExpiresAt: '2099-10-13T12:30:00Z',
    pendingUpdates: 0, sourceState: 'CURRENT', escalated: false };
}

function setup() {
  const board = createBoard();
  let sequence = 0;
  return {
    board,
    deps: {
      boardKey: vi.fn(() => 'code-review'),
      getControlParams: () => ({}), getRouteQuery: () => ({}), getReadScope: () => ({}),
      views: { acquire: vi.fn(async () => metadata(`view-${++sequence}`)), renew: vi.fn(async (id: string) => metadata(id)), release: vi.fn(async () => {}) },
      loadControlOptions: vi.fn(async (_key: string, _request: unknown, read: ReadViewTransport) => {
        read.onReadView(metadata(read.readContextId));
        return { scopeReadable: true, sourceVersion: 'publication:fixture', reason: '', scopeKey: '', groups: [] } as StatisticBoardControlOptions;
      }),
      loadOpenedDetail: vi.fn<(...args: unknown[]) => Promise<PreparedStatisticDetail | null>>(async () => null),
      loadOpenedRule: vi.fn(async () => null),
      getFilterGroup: vi.fn(() => ({
        logic: 'AND' as const,
        conditions: [{ fieldKey: 'moduleName', operator: 'eq' as const, value: 'module-a' }],
      })),
      loadBoardData: vi.fn(async () => board),
      exportBoardFile: vi.fn(async () => ({
        blob: new Blob(['workbook'], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }),
        filename: '当前里程碑-客户问题缺陷原因统计表.xlsx',
      })),
      onBoardLoaded: vi.fn<(response: StatisticBoardResponse) => void>(),
      downloadFile: vi.fn<(blob: Blob, filename: string) => void>(),
      notifySuccess: vi.fn<(message: string) => void>(),
      notifyError: vi.fn<(message: string) => void>(),
    },
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

describe('useStatisticBoardData', () => {
  it('automatic renewal includes the currently opened detail without adopting unapplied main filters', async () => {
    vi.useFakeTimers();
    const { deps } = setup();
    const query: Record<string, string> = { keyword: 'accepted-main' };
    deps.getRouteQuery = () => ({ ...query });
    const state = useStatisticBoardData(deps);
    activeStates.push(state);
    await state.loadBoard();
    query.keyword = 'unapplied-draft';
    query.detailVisible = '1'; query.detailRowKey = 'customer-a'; query.detailColumnKey = 'defect_total';
    deps.views.renew.mockResolvedValueOnce({ ...metadata('view-1'), sourceState: 'NEWER_AVAILABLE' });
    await vi.advanceTimersByTimeAsync(60_000);
    expect(state.acceptedFrame.value?.query).toMatchObject({ keyword: 'accepted-main', detailVisible: '1',
      detailRowKey: 'customer-a', detailColumnKey: 'defect_total' });
    expect(deps.loadOpenedDetail).toHaveBeenLastCalledWith(expect.any(Object), expect.objectContaining({ detailVisible: '1' }),
      expect.any(Object), expect.objectContaining({ readContextId: 'view-2' }));
  });
  it('keeps old main and candidates until the opened detail is also ready', async () => {
    const { board, deps } = setup();
    const state = useStatisticBoardData(deps);
    activeStates.push(state);
    await state.loadBoard();
    const next = createBoard();
    next.definition.title = '新版本';
    deps.loadBoardData.mockResolvedValueOnce(next);
    const pendingDetail = deferred<PreparedStatisticDetail | null>();
    deps.loadOpenedDetail.mockReturnValueOnce(pendingDetail.promise);
    const updating = state.loadBoard();
    await vi.waitFor(() => expect(deps.loadOpenedDetail).toHaveBeenCalledTimes(2));
    expect(state.board.value).toBe(board);
    expect(state.readMetadata.value?.readContextId).toBe('view-1');
    pendingDetail.resolve(null);
    await updating;
    expect(state.board.value).toBe(next);
    expect(state.readMetadata.value?.readContextId).toBe('view-2');
  });

  it('keeps the accepted frame and its export identity when detail preparation fails', async () => {
    const { board, deps } = setup();
    const state = useStatisticBoardData(deps);
    activeStates.push(state);
    await state.loadBoard();
    deps.loadOpenedDetail.mockRejectedValueOnce(new Error('明细准备失败'));
    await state.loadBoard(false);
    expect(state.board.value).toBe(board);
    expect(state.errorMessage.value).toBe('明细准备失败');
    await state.exportBoard();
    expect(deps.exportBoardFile).toHaveBeenCalledWith('code-review', expect.anything(),
      expect.objectContaining({ readContextId: 'view-1' }));
    expect(deps.views.release).toHaveBeenCalledWith('view-2');
  });

  it('canceling a pending scope preserves the accepted page and discards its late result', async () => {
    const { board, deps } = setup();
    const state = useStatisticBoardData(deps);
    activeStates.push(state);
    await state.loadBoard();
    const next = deferred<StatisticBoardResponse>();
    deps.loadBoardData.mockReturnValueOnce(next.promise);
    const updating = state.loadBoard();
    await vi.waitFor(() => expect(deps.loadBoardData).toHaveBeenCalledTimes(2));
    state.invalidateBoardRequest();
    expect(state.loading.value).toBe(false);
    next.resolve(createBoard());
    await updating;
    expect(state.board.value).toBe(board);
    expect(state.readMetadata.value?.readContextId).toBe('view-1');
  });
  it('loads board data with the current filter group and runs the loaded callback', async () => {
    const { board, deps } = setup();
    const state = useStatisticBoardData(deps);
    activeStates.push(state);

    await state.loadBoard();

    expect(deps.loadBoardData).toHaveBeenCalledWith('code-review', { ...{
      filterGroup: {
        logic: 'AND',
        conditions: [{ fieldKey: 'moduleName', operator: 'eq', value: 'module-a' }],
      },
    }, filters: {} }, expect.objectContaining({ readContextId: 'view-1' }));
    expect(state.board.value).toBe(board);
    expect(state.loading.value).toBe(false);
    expect(state.errorMessage.value).toBe('');
    expect(deps.onBoardLoaded).toHaveBeenCalledWith(board);
  });

  it('drops a late success so it cannot overwrite the newest board', async () => {
    const { deps } = setup();
    const older = createBoard();
    const newer = createBoard();
    const first = deferred<StatisticBoardResponse>();
    const second = deferred<StatisticBoardResponse>();
    deps.loadBoardData = vi
      .fn()
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise);
    const state = useStatisticBoardData(deps);
    activeStates.push(state);

    const firstLoad = state.loadBoard();
    const secondLoad = state.loadBoard();

    // 后发的请求先返回，新数据生效。
    second.resolve(newer);
    await secondLoad;
    expect(state.board.value).toBe(newer);

    // 先发的请求晚到：既不得覆盖新数据，也不得再次触发加载完成回调。
    first.resolve(older);
    await firstLoad;

    expect(state.board.value).toBe(newer);
    expect(deps.onBoardLoaded).toHaveBeenCalledTimes(1);
    expect(deps.onBoardLoaded).toHaveBeenCalledWith(newer);
  });

  it('drops a late failure so it cannot replace newer data with an error', async () => {
    const { deps } = setup();
    const newer = createBoard();
    const first = deferred<StatisticBoardResponse>();
    const second = deferred<StatisticBoardResponse>();
    deps.loadBoardData = vi
      .fn()
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise);
    const state = useStatisticBoardData(deps);
    activeStates.push(state);

    const firstLoad = state.loadBoard();
    const secondLoad = state.loadBoard();
    second.resolve(newer);
    await secondLoad;

    first.reject(new Error('旧请求失败'));
    await firstLoad;

    expect(state.board.value).toBe(newer);
    expect(state.errorMessage.value).toBe('');
    expect(deps.notifyError).not.toHaveBeenCalled();
  });

  it('keeps loading until the newest request finishes', async () => {
    const { deps } = setup();
    const first = deferred<StatisticBoardResponse>();
    const second = deferred<StatisticBoardResponse>();
    deps.loadBoardData = vi
      .fn()
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise);
    const state = useStatisticBoardData(deps);
    activeStates.push(state);

    const firstLoad = state.loadBoard();
    const secondLoad = state.loadBoard();

    first.resolve(createBoard());
    await firstLoad;
    expect(state.loading.value).toBe(true);

    second.resolve(createBoard());
    await secondLoad;
    expect(state.loading.value).toBe(false);
  });

  it('records load errors and only notifies when showError is enabled', async () => {
    const { deps } = setup();
    deps.loadBoardData.mockRejectedValueOnce(new Error('加载失败'));
    const state = useStatisticBoardData(deps);
    activeStates.push(state);

    await state.loadBoard(false);

    expect(state.errorMessage.value).toBe('加载失败');
    expect(state.loading.value).toBe(false);
    expect(deps.notifyError).not.toHaveBeenCalled();

    deps.loadBoardData.mockRejectedValueOnce(new Error('再次失败'));
    await state.loadBoard();

    expect(state.errorMessage.value).toBe('再次失败');
    expect(deps.notifyError).toHaveBeenCalledWith('再次失败');
  });

  it('exports the current board as a file', async () => {
    const { deps } = setup();
    const state = useStatisticBoardData(deps);
    activeStates.push(state);

    await state.loadBoard();
    await state.exportBoard();

    expect(deps.exportBoardFile).toHaveBeenCalledWith('code-review', { ...{
      filterGroup: {
        logic: 'AND',
        conditions: [{ fieldKey: 'moduleName', operator: 'eq', value: 'module-a' }],
      },
    }, filters: {} }, expect.objectContaining({ readContextId: 'view-1' }));
    expect(deps.downloadFile).toHaveBeenCalledWith(expect.any(Blob), '当前里程碑-客户问题缺陷原因统计表.xlsx');
    expect(deps.notifySuccess).toHaveBeenCalledWith('导出成功');
  });
});
