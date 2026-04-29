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
      exportBoardCsv: vi.fn(async () => '项目,阻塞数\nmodule-a,1'),
      onBoardLoaded: vi.fn<(response: StatisticBoardResponse) => void>(),
      downloadCsv: vi.fn<(csv: string, filename: string) => void>(),
      notifySuccess: vi.fn<(message: string) => void>(),
      notifyError: vi.fn<(message: string) => void>(),
    },
  };
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

  it('exports the current board as a csv file', async () => {
    const { deps } = setup();
    const state = useStatisticBoardData(deps);

    await state.exportBoard();

    expect(deps.exportBoardCsv).toHaveBeenCalledWith('code-review', {
      filterGroup: {
        logic: 'AND',
        conditions: [{ fieldKey: 'moduleName', operator: 'eq', value: 'module-a' }],
      },
    });
    expect(deps.downloadCsv).toHaveBeenCalledWith('项目,阻塞数\nmodule-a,1', 'code-review.csv');
    expect(deps.notifySuccess).toHaveBeenCalledWith('导出成功');
  });
});
