import { computed, onBeforeUnmount, onMounted, reactive } from 'vue';
import {
  EXPORT_PROGRESS_BEGIN_EVENT,
  EXPORT_PROGRESS_FAIL_EVENT,
  EXPORT_PROGRESS_FINISH_EVENT,
  type ExportProgressEventDetail,
  type ExportProgressOptions,
  type ExportProgressProfile,
} from '../api-client/export-progress-events';

type ExportTaskStatus = 'running' | 'success' | 'exception';

interface ExportProgressTask {
  id: string;
  label: string;
  url: string;
  endpointKey: string;
  profile: ExportProgressProfile;
  startedAt: number;
  estimatedMs: number;
  percentage: number;
  status: ExportTaskStatus;
  errorMessage?: string;
  timer?: ReturnType<typeof window.setInterval>;
  hideTimer?: ReturnType<typeof window.setTimeout>;
}

const PROFILE_BASE_MS: Record<ExportProgressProfile, number> = {
  template: 900,
  record: 3_000,
  statistic: 3_200,
  review: 4_500,
  illegal: 5_000,
  codeReview: 6_500,
  heavyStatistic: 9_000,
};

const PROFILE_ROW_COST_MS: Record<ExportProgressProfile, number> = {
  template: 0.2,
  record: 1.2,
  statistic: 0.8,
  review: 1.5,
  illegal: 1.8,
  codeReview: 2.2,
  heavyStatistic: 2.5,
};

const MIN_ESTIMATED_MS = 1_200;
const MAX_ESTIMATED_MS = 18_000;
const EXPORT_PROGRESS_LEARNED_KEY = 'platform-export-progress-duration-v1';

const state = reactive({
  tasks: [] as ExportProgressTask[],
});

let listenerReferenceCount = 0;

const currentTask = computed(() =>
  state.tasks
    .slice()
    .sort((left, right) => {
      if (left.status === 'running' && right.status !== 'running') {
        return -1;
      }
      if (right.status === 'running' && left.status !== 'running') {
        return 1;
      }
      return right.startedAt - left.startedAt;
    })[0],
);

const runningCount = computed(() => state.tasks.filter((task) => task.status === 'running').length);
const hasVisibleTask = computed(() => Boolean(currentTask.value));

export function useExportProgress() {
  onMounted(registerExportProgressListeners);
  onBeforeUnmount(unregisterExportProgressListeners);

  return {
    tasks: computed(() => state.tasks),
    currentTask,
    runningCount,
    hasVisibleTask,
  };
}

function registerExportProgressListeners() {
  listenerReferenceCount += 1;
  if (listenerReferenceCount !== 1 || typeof window === 'undefined') {
    return;
  }
  window.addEventListener(EXPORT_PROGRESS_BEGIN_EVENT, handleBegin as EventListener);
  window.addEventListener(EXPORT_PROGRESS_FINISH_EVENT, handleFinish as EventListener);
  window.addEventListener(EXPORT_PROGRESS_FAIL_EVENT, handleFail as EventListener);
}

function unregisterExportProgressListeners() {
  listenerReferenceCount = Math.max(0, listenerReferenceCount - 1);
  if (listenerReferenceCount !== 0 || typeof window === 'undefined') {
    return;
  }
  window.removeEventListener(EXPORT_PROGRESS_BEGIN_EVENT, handleBegin as EventListener);
  window.removeEventListener(EXPORT_PROGRESS_FINISH_EVENT, handleFinish as EventListener);
  window.removeEventListener(EXPORT_PROGRESS_FAIL_EVENT, handleFail as EventListener);
}

function handleBegin(event: CustomEvent<ExportProgressEventDetail>) {
  const detail = event.detail;
  const options = detail.options ?? {};
  const task: ExportProgressTask = {
    id: detail.id,
    label: options.label || '正在生成导出文件',
    url: detail.url,
    endpointKey: options.endpointKey || normalizeEndpointKey(detail.url),
    profile: options.profile ?? 'record',
    startedAt: Date.now(),
    estimatedMs: estimateDurationMs(options),
    percentage: 8,
    status: 'running',
  };
  clearTaskTimers(task);
  removeTask(detail.id);
  state.tasks.push(task);
  const reactiveTask = findTask(detail.id);
  if (!reactiveTask) {
    return;
  }
  reactiveTask.timer = window.setInterval(() => updateRunningTask(reactiveTask), 120);
  updateRunningTask(reactiveTask);
}

function handleFinish(event: CustomEvent<ExportProgressEventDetail>) {
  const task = findTask(event.detail.id);
  if (!task) {
    return;
  }
  rememberActualDuration(task, Date.now() - task.startedAt);
  finishTask(task, 'success');
}

function handleFail(event: CustomEvent<ExportProgressEventDetail>) {
  const task = findTask(event.detail.id);
  if (!task) {
    return;
  }
  task.errorMessage = event.detail.errorMessage || '导出失败';
  finishTask(task, 'exception');
}

function updateRunningTask(task: ExportProgressTask) {
  if (task.status !== 'running') {
    return;
  }
  const elapsed = Date.now() - task.startedAt;
  const ratio = elapsed / task.estimatedMs;
  const nextPercentage = resolveRunningPercentage(ratio, elapsed, task.estimatedMs);
  task.percentage = Math.max(task.percentage, nextPercentage);
}

function resolveRunningPercentage(ratio: number, elapsed: number, estimatedMs: number) {
  if (ratio <= 0.6) {
    return 8 + easeOutCubic(ratio / 0.6) * 57;
  }
  if (ratio <= 0.95) {
    return 65 + easeOutCubic((ratio - 0.6) / 0.35) * 25;
  }
  const crawlRatio = Math.min(1, (elapsed - estimatedMs * 0.95) / Math.max(estimatedMs * 0.7, 1_200));
  return 90 + crawlRatio * 5;
}

function finishTask(task: ExportProgressTask, status: Exclude<ExportTaskStatus, 'running'>) {
  clearTaskTimers(task);
  task.status = status;
  task.percentage = status === 'success' ? 100 : Math.max(task.percentage, 92);
  task.hideTimer = window.setTimeout(() => {
    removeTask(task.id);
  }, status === 'success' ? 850 : 2_600);
}

function estimateDurationMs(options: ExportProgressOptions) {
  const profile = options.profile ?? 'record';
  const baseMs = PROFILE_BASE_MS[profile];
  const rowCount = normalizePositiveNumber(options.rowCount);
  const workload = normalizePositiveNumber(options.workload);
  const dataCostMs = rowCount * PROFILE_ROW_COST_MS[profile] + workload * 350;
  const learnedMs = readLearnedDuration(options.endpointKey);
  const blendedMs = learnedMs > 0
    ? baseMs * 0.35 + learnedMs * 0.65
    : baseMs;
  return clamp(blendedMs + dataCostMs, MIN_ESTIMATED_MS, MAX_ESTIMATED_MS);
}

function rememberActualDuration(task: ExportProgressTask, durationMs: number) {
  if (!task.endpointKey || durationMs <= 0 || typeof window === 'undefined') {
    return;
  }
  try {
    const durations = readLearnedDurations();
    const previous = durations[task.endpointKey];
    durations[task.endpointKey] = previous
      ? Math.round(previous * 0.65 + durationMs * 0.35)
      : durationMs;
    window.localStorage.setItem(EXPORT_PROGRESS_LEARNED_KEY, JSON.stringify(durations));
  } catch {
    // Export progress learning is only a perception aid; blocked localStorage should not affect downloads.
  }
}

function readLearnedDuration(endpointKey?: string) {
  if (!endpointKey || typeof window === 'undefined') {
    return 0;
  }
  const duration = readLearnedDurations()[endpointKey];
  return Number.isFinite(duration) ? clamp(duration, MIN_ESTIMATED_MS, MAX_ESTIMATED_MS) : 0;
}

function readLearnedDurations(): Record<string, number> {
  if (typeof window === 'undefined') {
    return {};
  }
  try {
    const raw = window.localStorage.getItem(EXPORT_PROGRESS_LEARNED_KEY);
    return raw ? JSON.parse(raw) as Record<string, number> : {};
  } catch {
    return {};
  }
}

function findTask(id: string) {
  return state.tasks.find((task) => task.id === id);
}

function removeTask(id: string) {
  const index = state.tasks.findIndex((task) => task.id === id);
  if (index < 0) {
    return;
  }
  clearTaskTimers(state.tasks[index]);
  state.tasks.splice(index, 1);
}

function clearTaskTimers(task: ExportProgressTask) {
  if (task.timer !== undefined) {
    window.clearInterval(task.timer);
    task.timer = undefined;
  }
  if (task.hideTimer !== undefined) {
    window.clearTimeout(task.hideTimer);
    task.hideTimer = undefined;
  }
}

function normalizeEndpointKey(url: string) {
  return url.split('?')[0]?.replace(/^\/api\//, '') || 'export';
}

function normalizePositiveNumber(value: unknown) {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : 0;
}

function clamp(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, value));
}

function easeOutCubic(value: number) {
  const normalized = clamp(value, 0, 1);
  return 1 - (1 - normalized) ** 3;
}
