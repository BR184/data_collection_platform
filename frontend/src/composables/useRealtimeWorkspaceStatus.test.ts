import { describe, expect, it, vi } from 'vitest';
import { useRealtimeWorkspaceStatus } from './useRealtimeWorkspaceStatus';
import type { RealtimeWorkspaceStatusResponse } from '../types/api';

function createStatus(lastSyncedAt: string | null): RealtimeWorkspaceStatusResponse {
  return {
    workspaceKey: 'stat-board',
    supported: true,
    status: 'IDLE',
    message: '',
    refreshing: false,
    lastSyncedAt,
    lastRefreshStartedAt: null,
    lastRefreshFinishedAt: null,
  };
}

describe('useRealtimeWorkspaceStatus', () => {
  it('loads status, formats last synced time, and clears status on failure', async () => {
    const loadStatus = vi
      .fn<() => Promise<RealtimeWorkspaceStatusResponse>>()
      .mockResolvedValueOnce(createStatus('2026-04-28T09:10:11.123Z'))
      .mockRejectedValueOnce(new Error('network'));

    const status = useRealtimeWorkspaceStatus({
      loadStatus,
      emptyText: '暂无同步记录',
    });

    expect(status.lastSyncedText.value).toBe('暂无同步记录');

    await status.loadRealtimeStatus();

    expect(loadStatus).toHaveBeenCalledOnce();
    expect(status.syncStatus.value?.workspaceKey).toBe('stat-board');
    expect(status.lastSyncedText.value).toBe('2026-04-28 17:10:11');

    await status.loadRealtimeStatus();

    expect(status.syncStatus.value).toBeNull();
    expect(status.lastSyncedText.value).toBe('暂无同步记录');
  });

  it('keeps legacy offset-free timestamps stable', () => {
    const status = useRealtimeWorkspaceStatus({
      loadStatus: vi.fn<() => Promise<RealtimeWorkspaceStatusResponse>>(),
    });

    status.syncStatus.value = createStatus('2026-04-28T09:10:11');

    expect(status.lastSyncedText.value).toBe('2026-04-28 09:10:11');
  });

  it('invalidates an in-flight status read without overwriting the cleared state', async () => {
    let resolveStatus!: (value: RealtimeWorkspaceStatusResponse) => void;
    const loadStatus = vi.fn(
      () => new Promise<RealtimeWorkspaceStatusResponse>((resolve) => {
        resolveStatus = resolve;
      }),
    );
    const status = useRealtimeWorkspaceStatus({ loadStatus });

    const pending = status.loadRealtimeStatus();
    status.invalidateRealtimeStatusRequest();
    resolveStatus(createStatus('2026-04-28T09:10:11.123Z'));
    await pending;

    expect(status.syncStatus.value).toBeNull();
  });
});
