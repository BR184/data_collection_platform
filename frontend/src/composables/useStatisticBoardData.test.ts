import { describe, expect, it, vi } from 'vitest';
import { useStatisticBoardData } from './useStatisticBoardData';
import type { StatisticBoardResponse } from '../types/api';

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

function setup() {
  const board = createBoard();
  return {
    board,
    deps: {
      boardKey: vi.fn(() => 'code-review'),
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
  it('loads board data with the current filter group and runs the loaded callback', async () => {
    const { board, deps } = setup();
    const state = useStatisticBoardData(deps);

    await state.loadBoard();

    expect(deps.loadBoardData).toHaveBeenCalledWith('code-review', {
      filterGroup: {
        logic: 'AND',
        conditions: [{ fieldKey: 'moduleName', operator: 'eq', value: 'module-a' }],
      },
    });
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

    await state.exportBoard();

    expect(deps.exportBoardFile).toHaveBeenCalledWith('code-review', {
      filterGroup: {
        logic: 'AND',
        conditions: [{ fieldKey: 'moduleName', operator: 'eq', value: 'module-a' }],
      },
    });
    expect(deps.downloadFile).toHaveBeenCalledWith(expect.any(Blob), '当前里程碑-客户问题缺陷原因统计表.xlsx');
    expect(deps.notifySuccess).toHaveBeenCalledWith('导出成功');
  });
});
