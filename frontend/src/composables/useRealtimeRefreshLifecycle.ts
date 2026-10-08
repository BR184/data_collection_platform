import { computed, ref, type Ref } from 'vue';
import type { RealtimeRefreshSubmissionOutcome, RealtimeWorkspaceStatusResponse } from '../types/api';
import { getErrorMessage, toUserMessage } from '../utils/user-message';

/** 提交后刷新按钮保持占用的交互窗口；到期即释放按钮并由后台跟踪接管。 */
export const REFRESH_BUTTON_WINDOW_MS = 10_000;
/** 同一刷新自首次接受/开始跟踪起的最长后台跟踪时长。 */
export const REFRESH_TRACKING_LIMIT_MS = 15 * 60_000;
/** 后台轮询刷新状态的固定间隔。 */
export const REFRESH_STATUS_POLL_INTERVAL_MS = 1_000;

/** 一次刷新提交的明确结论；调用方按它决定提示与是否继续跟踪。 */
export type RealtimeRefreshSubmitOutcome = RealtimeRefreshSubmissionOutcome | 'FAILED';

export interface RealtimeRefreshLifecycleOptions {
  /** 提交刷新请求，返回带提交结论与跟踪身份的状态响应。 */
  submitRefresh: () => Promise<RealtimeWorkspaceStatusResponse>;
  /** 读取当前刷新与工作区状态；失败返回空值时不终止跟踪。 */
  loadStatus: () => Promise<RealtimeWorkspaceStatusResponse | null | undefined>;
  /** 刷新收敛后加载页面主表与当前已打开明细。 */
  loadData: () => Promise<void> | void;
  notifySuccess?: (message: string) => void;
  notifyWarning?: (message: string) => void;
  notifyError?: (message: string) => void;
  pollIntervalMs?: number;
  buttonWindowMs?: number;
  trackingLimitMs?: number;
}

export interface RealtimeRefreshLifecycle {
  /** 提交请求在途。 */
  submitBusy: Ref<boolean>;
  /** 收敛后重新取数在途。 */
  fetchLoading: Ref<boolean>;
  /** 后台跟踪仍在进行。 */
  refreshPending: Ref<boolean>;
  /** 刷新按钮占用状态：提交在途或处于提交后的交互窗口。 */
  refreshButtonBusy: Ref<boolean>;
  /** 用户明确触发的刷新，带成功/警告提示。 */
  submitRefresh: () => Promise<RealtimeRefreshSubmitOutcome>;
  /** 进入页面自动触发的刷新，复用同一生命周期但不打扰用户。 */
  autoRefresh: () => Promise<RealtimeRefreshSubmitOutcome>;
  /** 使当前跟踪失效：清定时器、释放按钮窗口并丢弃跟踪身份。 */
  invalidateRefreshTracking: () => void;
}

/**
 * 统一实时刷新生命周期：拆开提交、取数与后台跟踪，按跟踪身份只保留一条轮询链。
 *
 * 提交被接受或复用后，按钮仅占用 `buttonWindowMs`（默认 10 秒）；未收敛时释放按钮，由唯一跟踪者
 * 继续轮询到收敛或达到 `trackingLimitMs`（默认 15 分钟）。重复提交不叠加请求，也不重置同一刷新的
 * 跟踪起算时点。收敛后加载最新数据；终态失败保留当前数据并说明原因；到期只停止跟踪，不伪装 READY。
 */
export function useRealtimeRefreshLifecycle(
  options: RealtimeRefreshLifecycleOptions,
): RealtimeRefreshLifecycle {
  const submitBusy = ref(false);
  const fetchLoading = ref(false);
  const refreshPending = ref(false);
  const buttonWindowActive = ref(false);

  const pollIntervalMs = options.pollIntervalMs ?? REFRESH_STATUS_POLL_INTERVAL_MS;
  const buttonWindowMs = options.buttonWindowMs ?? REFRESH_BUTTON_WINDOW_MS;
  const trackingLimitMs = options.trackingLimitMs ?? REFRESH_TRACKING_LIMIT_MS;

  let generation = 0;
  let pollTimer: number | null = null;
  let buttonTimer: number | null = null;
  let trackingActive = false;
  let trackingId: string | null = null;
  let trackingStartedAt: number | null = null;

  const refreshButtonBusy = computed(() => submitBusy.value || buttonWindowActive.value);

  function clearPollTimer() {
    if (pollTimer != null) {
      window.clearTimeout(pollTimer);
      pollTimer = null;
    }
  }

  function clearButtonTimer() {
    if (buttonTimer != null) {
      window.clearTimeout(buttonTimer);
      buttonTimer = null;
    }
  }

  function releaseButtonWindow() {
    clearButtonTimer();
    buttonWindowActive.value = false;
  }

  function openButtonWindow() {
    clearButtonTimer();
    buttonWindowActive.value = true;
    buttonTimer = window.setTimeout(() => {
      buttonTimer = null;
      buttonWindowActive.value = false;
    }, buttonWindowMs);
  }

  function stopTracking() {
    clearPollTimer();
    trackingActive = false;
    refreshPending.value = false;
  }

  function isActiveRun(runGeneration: number) {
    return runGeneration === generation && trackingActive;
  }

  function trackingElapsed() {
    return trackingStartedAt == null ? 0 : Date.now() - trackingStartedAt;
  }

  function invalidateRefreshTracking() {
    generation += 1;
    stopTracking();
    releaseButtonWindow();
    trackingId = null;
    trackingStartedAt = null;
  }

  function resolveSubmitOutcome(
    response: RealtimeWorkspaceStatusResponse,
  ): RealtimeRefreshSubmissionOutcome {
    if (response.supported === false) {
      return 'UNSUPPORTED';
    }
    if (response.submissionOutcome) {
      return response.submissionOutcome;
    }
    return response.refreshing ? 'ALREADY_REFRESHING' : 'ACCEPTED';
  }

  function adoptTrackingIdentity(nextTrackingId: string | null) {
    // 同一刷新的跟踪起算时点只由首次接受决定；重复提交沿用原时点。
    if (nextTrackingId != null && nextTrackingId === trackingId) {
      return;
    }
    trackingId = nextTrackingId;
    trackingStartedAt = Date.now();
  }

  async function submitWithFeedback(
    notify: boolean,
  ): Promise<RealtimeWorkspaceStatusResponse | null> {
    submitBusy.value = true;
    try {
      return await options.submitRefresh();
    } catch (error) {
      if (notify) {
        options.notifyError?.(getErrorMessage(error, '刷新最新数据失败'));
      }
      return null;
    } finally {
      submitBusy.value = false;
    }
  }

  async function runSubmit(notify: boolean): Promise<RealtimeRefreshSubmitOutcome> {
    if (refreshPending.value || submitBusy.value) {
      return 'ALREADY_REFRESHING';
    }
    const response = await submitWithFeedback(notify);
    if (response == null) {
      return 'FAILED';
    }
    const outcome = resolveSubmitOutcome(response);
    if (outcome === 'COOLDOWN') {
      if (notify) {
        options.notifyWarning?.(toUserMessage(response.message, '刷新请求过于频繁，请稍后再试'));
      }
      return outcome;
    }
    if (outcome === 'UNSUPPORTED') {
      if (notify) {
        options.notifyWarning?.(toUserMessage(response.message, '当前页面暂不支持刷新最新数据'));
      }
      return outcome;
    }
    if (outcome === 'ACCEPTED') {
      if (notify) {
        options.notifySuccess?.(toUserMessage(response.message, '已开始刷新最新数据'));
      }
    } else if (notify) {
      options.notifyWarning?.(toUserMessage(response.message, '刷新仍在进行中，继续跟踪本次刷新'));
    }
    adoptTrackingIdentity(response.trackingId ?? null);
    openButtonWindow();
    startTracking(response);
    return outcome;
  }

  function startTracking(status: RealtimeWorkspaceStatusResponse) {
    if (trackingStartedAt == null) {
      trackingStartedAt = Date.now();
    }
    clearPollTimer();
    trackingActive = true;
    refreshPending.value = true;
    void advanceTracking(status, generation);
  }

  async function advanceTracking(
    status: RealtimeWorkspaceStatusResponse,
    runGeneration: number,
  ) {
    if (!isActiveRun(runGeneration)) {
      return;
    }
    if (!status.refreshing) {
      const terminal = status.status === 'FAILED' || status.status === 'UNSUPPORTED';
      stopTracking();
      if (terminal) {
        options.notifyError?.(toUserMessage(status.message, '刷新未完成，已展示当前可用数据'));
        return;
      }
      await reloadSettledData(runGeneration);
      return;
    }
    if (trackingElapsed() >= trackingLimitMs) {
      stopTracking();
      options.notifyWarning?.('刷新仍未完成，可查看同步日志确认进度');
      return;
    }
    clearPollTimer();
    pollTimer = window.setTimeout(() => void pollTracking(runGeneration), pollIntervalMs);
  }

  async function pollTracking(runGeneration: number) {
    pollTimer = null;
    if (!isActiveRun(runGeneration)) {
      return;
    }
    let latest: RealtimeWorkspaceStatusResponse | null | undefined;
    try {
      latest = await options.loadStatus();
    } catch {
      latest = null;
    }
    if (!isActiveRun(runGeneration)) {
      return;
    }
    if (!latest) {
      if (trackingElapsed() >= trackingLimitMs) {
        stopTracking();
        options.notifyWarning?.('刷新状态读取失败，请稍后查看同步日志');
        return;
      }
      clearPollTimer();
      pollTimer = window.setTimeout(() => void pollTracking(runGeneration), pollIntervalMs);
      return;
    }
    await advanceTracking(latest, runGeneration);
  }

  async function reloadSettledData(runGeneration: number) {
    // 收敛后加载取决于请求代次：跟踪已正常结束，但同代次的取数必须完成。
    if (runGeneration !== generation) {
      return;
    }
    fetchLoading.value = true;
    try {
      await options.loadData();
    } catch (error) {
      options.notifyError?.(getErrorMessage(error, '刷新后加载最新数据失败'));
    } finally {
      fetchLoading.value = false;
    }
  }

  return {
    submitBusy,
    fetchLoading,
    refreshPending,
    refreshButtonBusy,
    submitRefresh: () => runSubmit(true),
    autoRefresh: () => runSubmit(false),
    invalidateRefreshTracking,
  };
}
