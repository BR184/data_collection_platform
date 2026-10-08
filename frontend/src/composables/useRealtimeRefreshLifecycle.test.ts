import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  REFRESH_BUTTON_WINDOW_MS,
  useRealtimeRefreshLifecycle,
  type RealtimeRefreshLifecycleOptions,
} from './useRealtimeRefreshLifecycle';
import type { RealtimeWorkspaceStatusResponse } from '../types/api';

const POLL_INTERVAL_MS = 1_000;

function status(
  overrides: Partial<RealtimeWorkspaceStatusResponse> = {},
): RealtimeWorkspaceStatusResponse {
  return {
    workspaceKey: 'system-test-defect-summary',
    supported: true,
    status: 'REFRESHING',
    message: '镜像同步中',
    refreshing: true,
    ...overrides,
  };
}

function accepted(overrides: Partial<RealtimeWorkspaceStatusResponse> = {}) {
  return status({
    submissionOutcome: 'ACCEPTED',
    trackingId: 'system-test-defect-summary#1',
    ...overrides,
  });
}

function ready(overrides: Partial<RealtimeWorkspaceStatusResponse> = {}) {
  return status({ status: 'READY', message: '已展示最新事实数据', refreshing: false, ...overrides });
}

function createLifecycle(overrides: Partial<RealtimeRefreshLifecycleOptions> = {}) {
  const notifySuccess = vi.fn();
  const notifyWarning = vi.fn();
  const notifyError = vi.fn();
  const loadData = vi.fn(() => Promise.resolve());
  const submitRefresh = vi.fn(() => Promise.resolve(accepted()));
  const loadStatus = vi.fn(() => Promise.resolve<RealtimeWorkspaceStatusResponse | null>(status()));
  const lifecycle = useRealtimeRefreshLifecycle({
    submitRefresh,
    loadStatus,
    loadData,
    notifySuccess,
    notifyWarning,
    notifyError,
    pollIntervalMs: POLL_INTERVAL_MS,
    buttonWindowMs: REFRESH_BUTTON_WINDOW_MS,
    ...overrides,
  });
  return { lifecycle, submitRefresh, loadStatus, loadData, notifySuccess, notifyWarning, notifyError };
}

describe('useRealtimeRefreshLifecycle', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-10-08T10:00:00Z'));
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('occupies the refresh button only for the button window, then keeps a single background tracker', async () => {
    const { lifecycle, loadStatus, loadData, notifySuccess } = createLifecycle();

    await lifecycle.submitRefresh();

    expect(notifySuccess).toHaveBeenCalledWith('镜像同步中');
    expect(lifecycle.refreshPending.value).toBe(true);
    expect(lifecycle.refreshButtonBusy.value).toBe(true);

    await vi.advanceTimersByTimeAsync(REFRESH_BUTTON_WINDOW_MS + 500);

    // 按钮窗口结束后释放按钮，但后台跟踪继续，不以“窗口结束”冒充刷新成功。
    expect(lifecycle.refreshButtonBusy.value).toBe(false);
    expect(lifecycle.refreshPending.value).toBe(true);
    expect(loadStatus).toHaveBeenCalled();
    expect(loadData).not.toHaveBeenCalled();
  });

  it('loads page data once the refresh converges', async () => {
    const { lifecycle, loadStatus, loadData } = createLifecycle();
    loadStatus.mockResolvedValue(ready());

    await lifecycle.submitRefresh();
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS + 500);

    expect(loadData).toHaveBeenCalledTimes(1);
    expect(lifecycle.refreshPending.value).toBe(false);
    expect(lifecycle.fetchLoading.value).toBe(false);
  });

  it('reuses the active tracker on repeat clicks and never resets the tracking origin', async () => {
    const { lifecycle, submitRefresh, loadStatus, notifyWarning } = createLifecycle({
      trackingLimitMs: 5_000,
    });

    await lifecycle.submitRefresh();
    await vi.advanceTimersByTimeAsync(3_000);

    expect(await lifecycle.submitRefresh()).toBe('ALREADY_REFRESHING');
    expect(submitRefresh).toHaveBeenCalledTimes(1);
    expect(loadStatus).toHaveBeenCalled();

    // 再推进到首次接受后 5 秒：到期停止跟踪并提示查看同步日志；重复点击未把期限推到 8 秒。
    await vi.advanceTimersByTimeAsync(2_500);

    expect(lifecycle.refreshPending.value).toBe(false);
    expect(notifyWarning).toHaveBeenCalledWith('刷新仍未完成，可查看同步日志确认进度');
  });

  it('does not report a started refresh when the submit is cooling down', async () => {
    const { lifecycle, notifySuccess, notifyWarning, loadData } = createLifecycle({
      submitRefresh: vi.fn(() => Promise.resolve(status({
        submissionOutcome: 'COOLDOWN',
        message: '刷新请求过于频繁，请稍后再试',
        refreshing: false,
        status: 'READY',
      }))),
    });

    expect(await lifecycle.submitRefresh()).toBe('COOLDOWN');

    expect(notifySuccess).not.toHaveBeenCalled();
    expect(notifyWarning).toHaveBeenCalledWith('刷新请求过于频繁，请稍后再试');
    expect(lifecycle.refreshPending.value).toBe(false);
    expect(loadData).not.toHaveBeenCalled();
  });

  it('adopts an equivalent refresh that the backend already tracks', async () => {
    const { lifecycle, notifyWarning, loadStatus } = createLifecycle({
      submitRefresh: vi.fn(() => Promise.resolve(status({
        submissionOutcome: 'ALREADY_REFRESHING',
        message: '刷新仍在进行中，继续跟踪本次刷新',
      }))),
    });
    loadStatus.mockResolvedValue(ready());

    expect(await lifecycle.submitRefresh()).toBe('ALREADY_REFRESHING');

    expect(notifyWarning).toHaveBeenCalledWith('刷新仍在进行中，继续跟踪本次刷新');
    expect(lifecycle.refreshPending.value).toBe(true);
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS + 500);
    expect(lifecycle.refreshPending.value).toBe(false);
  });

  it('keeps current data and reports the reason when the refresh fails terminally', async () => {
    const { lifecycle, loadStatus, loadData, notifyError } = createLifecycle();
    loadStatus.mockResolvedValue(status({
      status: 'FAILED',
      message: '镜像同步未完成，已展示当前可用数据',
      refreshing: false,
    }));

    await lifecycle.submitRefresh();
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS + 500);

    expect(notifyError).toHaveBeenCalledWith('镜像同步未完成，已展示当前可用数据');
    expect(loadData).not.toHaveBeenCalled();
    expect(lifecycle.refreshPending.value).toBe(false);
  });

  it('keeps polling after a status read failure instead of ending the refresh', async () => {
    const { lifecycle, loadStatus, loadData, notifyWarning } = createLifecycle();
    loadStatus
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValue(ready());

    await lifecycle.submitRefresh();
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 2 + 500);

    expect(loadData).toHaveBeenCalledTimes(1);
    expect(notifyWarning).not.toHaveBeenCalled();
  });

  it('invalidates the running tracker on route, source or filter changes and on unmount', async () => {
    const { lifecycle, loadStatus } = createLifecycle();

    await lifecycle.submitRefresh();
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS + 500);
    const callsBeforeInvalidate = loadStatus.mock.calls.length;

    lifecycle.invalidateRefreshTracking();
    await vi.advanceTimersByTimeAsync(10 * 60_000);

    expect(loadStatus.mock.calls.length).toBe(callsBeforeInvalidate);
    expect(lifecycle.refreshPending.value).toBe(false);
    expect(lifecycle.refreshButtonBusy.value).toBe(false);
  });

  it('runs the automatic refresh through the same lifecycle without bothering the user', async () => {
    const { lifecycle, notifySuccess, notifyWarning, loadStatus, loadData } = createLifecycle();
    loadStatus.mockResolvedValue(ready());

    expect(await lifecycle.autoRefresh()).toBe('ACCEPTED');

    expect(notifySuccess).not.toHaveBeenCalled();
    expect(notifyWarning).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS + 500);
    expect(loadData).toHaveBeenCalledTimes(1);
  });

  it('surfaces submit transport failures without starting a tracker', async () => {
    const { lifecycle, notifyError, notifySuccess } = createLifecycle({
      submitRefresh: vi.fn(() => Promise.reject(new Error('提交失败'))),
    });

    expect(await lifecycle.submitRefresh()).toBe('FAILED');

    expect(notifyError).toHaveBeenCalledWith('提交失败');
    expect(notifySuccess).not.toHaveBeenCalled();
    expect(lifecycle.refreshPending.value).toBe(false);
  });

  it('treats an unsupported workspace as an explicit non-submission', async () => {
    const { lifecycle, notifyWarning, notifySuccess } = createLifecycle({
      submitRefresh: vi.fn(() => Promise.resolve(status({
        supported: false,
        status: 'UNSUPPORTED',
        message: '当前统计表暂不支持实时刷新',
        refreshing: false,
      }))),
    });

    expect(await lifecycle.submitRefresh()).toBe('UNSUPPORTED');

    expect(notifySuccess).not.toHaveBeenCalled();
    expect(notifyWarning).toHaveBeenCalledWith('当前统计表暂不支持实时刷新');
    expect(lifecycle.refreshPending.value).toBe(false);
  });
});
