import { describe, expect, it, vi } from 'vitest';
import { refreshStatisticBoardRouteState } from './useStatisticBoardRouteRefresh';

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise;
  });
  return { promise, resolve };
}

describe('refreshStatisticBoardRouteState', () => {
  it('runs route synchronization in order without owning board loading', async () => {
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

    await refreshStatisticBoardRouteState(deps, () => true);

    expect(calls).toEqual([
      'sync-table-pagination',
      'load-board:false',
      'load-realtime-status',
      'sync-detail',
    ]);
    expect(deps.setLoading).not.toHaveBeenCalled();
  });

  it('does not continue a route task after a newer route replaces it during board loading', async () => {
    const boardResponse = deferred<void>();
    let current = true;
    const deps = {
      syncTablePaginationFromRoute: vi.fn(),
      loadBoard: vi.fn(() => boardResponse.promise),
      loadRealtimeStatus: vi.fn(),
      syncDetailFromRoute: vi.fn(),
    };

    const oldRouteTask = refreshStatisticBoardRouteState(deps, () => current);
    current = false;
    boardResponse.resolve();
    await oldRouteTask;

    expect(deps.loadRealtimeStatus).not.toHaveBeenCalled();
    expect(deps.syncDetailFromRoute).not.toHaveBeenCalled();
  });

  it('does not synchronize detail after the route becomes stale during status loading', async () => {
    const statusResponse = deferred<void>();
    let current = true;
    const deps = {
      syncTablePaginationFromRoute: vi.fn(),
      loadBoard: vi.fn(async () => undefined),
      loadRealtimeStatus: vi.fn(() => statusResponse.promise),
      syncDetailFromRoute: vi.fn(),
    };

    const routeTask = refreshStatisticBoardRouteState(deps, () => current);
    await Promise.resolve();
    current = false;
    statusResponse.resolve();
    await routeTask;

    expect(deps.syncDetailFromRoute).not.toHaveBeenCalled();
  });

  it('keeps failures visible to the caller without taking ownership of loading', async () => {
    const deps = {
      setLoading: vi.fn(),
      syncTablePaginationFromRoute: vi.fn(),
      loadBoard: vi.fn(async () => {
        throw new Error('load failed');
      }),
      loadRealtimeStatus: vi.fn(),
      syncDetailFromRoute: vi.fn(),
    };

    await expect(refreshStatisticBoardRouteState(deps, () => true)).rejects.toThrow('load failed');

    expect(deps.setLoading).not.toHaveBeenCalled();
    expect(deps.loadRealtimeStatus).not.toHaveBeenCalled();
  });
});
