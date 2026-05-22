import { ref } from 'vue';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useStatisticBoardRefreshController } from './useStatisticBoardRefreshController';

describe('useStatisticBoardRefreshController', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('loads board without preloading rule explanation when refreshing', async () => {
    const loading = ref(false);
    const detailVisible = ref(false);
    const loadBoard = vi.fn(() => Promise.resolve());
    const loadDetail = vi.fn(() => Promise.resolve());
    const controller = useStatisticBoardRefreshController({
      loading,
      detailVisible,
      loadBoard,
      loadDetail,
    });

    await controller.refreshBoard();

    expect(loadBoard).toHaveBeenCalledTimes(1);
    expect(loadDetail).not.toHaveBeenCalled();
    expect(loading.value).toBe(false);
  });

  it('reloads detail when detail dialog is visible', async () => {
    const loading = ref(false);
    const detailVisible = ref(true);
    const loadBoard = vi.fn(() => Promise.resolve());
    const loadDetail = vi.fn(() => Promise.resolve());
    const controller = useStatisticBoardRefreshController({
      loading,
      detailVisible,
      loadBoard,
      loadDetail,
    });

    await controller.refreshBoard();

    expect(loadDetail).toHaveBeenCalledTimes(1);
  });

  it('clears loading when refresh fails', async () => {
    const loading = ref(false);
    const detailVisible = ref(true);
    const error = new Error('load failed');
    const controller = useStatisticBoardRefreshController({
      loading,
      detailVisible,
      loadBoard: vi.fn(() => Promise.reject(error)),
      loadDetail: vi.fn(() => Promise.resolve()),
    });

    await expect(controller.refreshBoard()).rejects.toThrow('load failed');

    expect(loading.value).toBe(false);
  });

  it('calls page-level realtime refresh and waits for mirror/fact status before reloading board data', async () => {
    vi.useFakeTimers();
    const loading = ref(false);
    const detailVisible = ref(false);
    const loadBoard = vi.fn(() => Promise.resolve());
    const loadDetail = vi.fn(() => Promise.resolve());
    const notifySuccess = vi.fn();
    const requestRealtimeRefresh = vi.fn(() => Promise.resolve({
      workspaceKey: 'system-test-defect-summary',
      supported: true,
      status: 'REFRESHING',
      message: '镜像同步中',
      refreshing: true,
      mirrorStatus: 'RUNNING',
      factStatus: null,
    }));
    const settledStatus = {
      workspaceKey: 'system-test-defect-summary',
      supported: true,
      status: 'READY',
      message: '已是最新',
      refreshing: false,
      mirrorStatus: 'SUCCESS',
      factStatus: 'SUCCESS',
    };
    const loadRealtimeStatus = vi.fn(() => Promise.resolve(settledStatus));
    const controller = useStatisticBoardRefreshController({
      loading,
      detailVisible,
      loadBoard,
      loadDetail,
      requestRealtimeRefresh,
      loadRealtimeStatus,
      notifySuccess,
    });

    const refreshPromise = controller.refreshBoard();
    await Promise.resolve();
    await vi.advanceTimersByTimeAsync(1000);
    await refreshPromise;

    expect(requestRealtimeRefresh).toHaveBeenCalledOnce();
    expect(loadRealtimeStatus).toHaveBeenCalledTimes(2);
    expect(notifySuccess).toHaveBeenCalledWith('镜像同步中');
    expect(loadBoard).toHaveBeenCalledOnce();
    expect(loading.value).toBe(false);
  });

  it('converts backend refresh default messages to Chinese before notifying users', async () => {
    const loading = ref(false);
    const detailVisible = ref(false);
    const notifySuccess = vi.fn();
    const controller = useStatisticBoardRefreshController({
      loading,
      detailVisible,
      loadBoard: vi.fn(() => Promise.resolve()),
      loadDetail: vi.fn(() => Promise.resolve()),
      requestRealtimeRefresh: vi.fn(() => Promise.resolve({
        workspaceKey: 'system-test-defect-summary',
        supported: true,
        status: 'REFRESHING',
        message: 'Refresh requested',
        refreshing: false,
        mirrorStatus: 'RUNNING',
        factStatus: null,
      })),
      notifySuccess,
    });

    await controller.refreshBoard();

    expect(notifySuccess).toHaveBeenCalledWith('已开始刷新最新数据');
  });
});
