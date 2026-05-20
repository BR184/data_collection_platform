import { describe, expect, it, vi } from 'vitest';
import { refreshStatisticBoardRouteState } from './useStatisticBoardRouteRefresh';

describe('refreshStatisticBoardRouteState', () => {
  it('runs the route refresh chain in order with silent board loading', async () => {
    const calls: string[] = [];
    const deps = {
      setLoading: vi.fn((next: boolean) => calls.push(`loading:${next}`)),
      syncTablePaginationFromRoute: vi.fn(() => calls.push('sync-table-pagination')),
      loadBoard: vi.fn(async (showError?: boolean) => {
        calls.push(`load-board:${showError}`);
      }),
      loadRealtimeStatus: vi.fn(async () => {
        calls.push('load-realtime-status');
      }),
      syncDetailFromRoute: vi.fn(async () => {
        calls.push('sync-detail');
      }),
    };

    await refreshStatisticBoardRouteState(deps);

    expect(calls).toEqual([
      'loading:true',
      'sync-table-pagination',
      'load-board:false',
      'load-realtime-status',
      'sync-detail',
      'loading:false',
    ]);
  });

  it('clears loading when one route refresh step fails', async () => {
    const deps = {
      setLoading: vi.fn(),
      syncTablePaginationFromRoute: vi.fn(),
      loadBoard: vi.fn(async () => {
        throw new Error('load failed');
      }),
      loadRealtimeStatus: vi.fn(),
      syncDetailFromRoute: vi.fn(),
    };

    await expect(refreshStatisticBoardRouteState(deps)).rejects.toThrow('load failed');

    expect(deps.setLoading).toHaveBeenNthCalledWith(1, true);
    expect(deps.setLoading).toHaveBeenLastCalledWith(false);
    expect(deps.loadRealtimeStatus).not.toHaveBeenCalled();
  });
});
