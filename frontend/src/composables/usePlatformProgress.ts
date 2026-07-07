import { computed, onBeforeUnmount, onMounted, reactive } from 'vue';
import {
  PLATFORM_PROGRESS_BEGIN_EVENT,
  PLATFORM_PROGRESS_FAIL_EVENT,
  PLATFORM_PROGRESS_FINISH_EVENT,
  type PlatformProgressEventDetail,
  type PlatformProgressOptions,
  type PlatformProgressProfile,
} from '../api-client/platform-progress-events';

type PlatformTaskStatus = 'running' | 'success' | 'exception';

interface PlatformProgressTask {
  id: string;
  label: string;
  source: string;
  endpointKey: string;
  profile: PlatformProgressProfile;
  startedAt: number;
  estimatedMs: number;
  percentage: number;
  visible: boolean;
  status: PlatformTaskStatus;
  learnDuration: boolean;
  errorMessage?: string;
  timer?: ReturnType<typeof window.setInterval>;
  showTimer?: ReturnType<typeof window.setTimeout>;
  settleTimer?: ReturnType<typeof window.setTimeout>;
  hideTimer?: ReturnType<typeof window.setTimeout>;
}

const PROFILE_BASE_MS: Record<PlatformProgressProfile, number> = {
  route: 1_200,
  background: 1_500,
  filter: 1_800,
  table: 2_400,
  statistic: 3_600,
  refresh: 5_500,
  template: 900,
  export: 5_500,
  heavyExport: 10_000,
};

const PROFILE_ROW_COST_MS: Record<PlatformProgressProfile, number> = {
  route: 0,
  background: 0,
  filter: 0.4,
  table: 0.8,
  statistic: 1.1,
  refresh: 1.4,
  template: 0.2,
  export: 1.6,
  heavyExport: 2.5,
};

const MIN_ESTIMATED_MS = 900;
const MAX_ESTIMATED_MS = 20_000;
const DEFAULT_SHOW_DELAY_MS = 260;
const IDLE_SUCCESS_DELAY_MS = 700;
const SUCCESS_HIDE_DELAY_MS = 650;
const FAILURE_HIDE_DELAY_MS = 2_600;
const PLATFORM_PROGRESS_LEARNED_KEY = 'platform-progress-duration-v1';

const state = reactive({
  tasks: [] as PlatformProgressTask[],
});

let listenerReferenceCount = 0;

const visibleTasks = computed(() => state.tasks.filter((task) => task.visible));
const currentTask = computed(() =>
  visibleTasks.value
    .slice()
    .sort((left, right) => {
      if (left.status === 'running' && right.status !== 'running') {
        return -1;
      }
      if (right.status === 'running' && left.status !== 'running') {
        return 1;
      }
      return priorityOf(right.profile) - priorityOf(left.profile) || right.startedAt - left.startedAt;
    })[0],
);
const runningCount = computed(() => visibleTasks.value.filter((task) => task.status === 'running').length);
const hasVisibleTask = computed(() => Boolean(currentTask.value));

export function usePlatformProgress() {
  onMounted(registerPlatformProgressListeners);
  onBeforeUnmount(unregisterPlatformProgressListeners);

  return {
    tasks: computed(() => state.tasks),
    currentTask,
    runningCount,
    hasVisibleTask,
  };
}

function registerPlatformProgressListeners() {
  listenerReferenceCount += 1;
  if (listenerReferenceCount !== 1 || typeof window === 'undefined') {
    return;
  }
  window.addEventListener(PLATFORM_PROGRESS_BEGIN_EVENT, handleBegin as EventListener);
  window.addEventListener(PLATFORM_PROGRESS_FINISH_EVENT, handleFinish as EventListener);
  window.addEventListener(PLATFORM_PROGRESS_FAIL_EVENT, handleFail as EventListener);
}

function unregisterPlatformProgressListeners() {
  listenerReferenceCount = Math.max(0, listenerReferenceCount - 1);
  if (listenerReferenceCount !== 0 || typeof window === 'undefined') {
    return;
  }
  window.removeEventListener(PLATFORM_PROGRESS_BEGIN_EVENT, handleBegin as EventListener);
  window.removeEventListener(PLATFORM_PROGRESS_FINISH_EVENT, handleFinish as EventListener);
  window.removeEventListener(PLATFORM_PROGRESS_FAIL_EVENT, handleFail as EventListener);
}

function handleBegin(event: CustomEvent<PlatformProgressEventDetail>) {
  const detail = event.detail;
  const options = detail.options ?? {};
  const joinedTask = findJoinableVisibleTask(options.profile ?? 'background');
  const task: PlatformProgressTask = {
    id: detail.id,
    label: options.label || defaultLabel(options.profile),
    source: detail.source,
    endpointKey: options.endpointKey || normalizeEndpointKey(detail.source),
    profile: options.profile ?? 'background',
    startedAt: Date.now(),
    estimatedMs: estimateDurationMs(options),
    percentage: joinedTask ? inheritPercentage(joinedTask) : 8,
    visible: Boolean(joinedTask),
    status: 'running',
    learnDuration: Boolean(options.learnDuration),
  };
  clearTaskTimers(task);
  removeTask(detail.id);
  if (joinedTask) {
    removeTask(joinedTask.id);
  }
  state.tasks.push(task);
  const reactiveTask = findTask(detail.id);
  if (!reactiveTask) {
    return;
  }
  if (!reactiveTask.visible) {
    reactiveTask.showTimer = window.setTimeout(() => {
      reactiveTask.visible = true;
      reactiveTask.showTimer = undefined;
    }, options.showDelayMs ?? DEFAULT_SHOW_DELAY_MS);
  }
  reactiveTask.timer = window.setInterval(() => updateRunningTask(reactiveTask), 120);
  updateRunningTask(reactiveTask);
}

function handleFinish(event: CustomEvent<PlatformProgressEventDetail>) {
  const task = findTask(event.detail.id);
  if (!task) {
    return;
  }
  if (task.learnDuration) {
    rememberActualDuration(task, Date.now() - task.startedAt);
  }
  if (!task.visible) {
    removeTask(task.id);
    return;
  }
  finishTask(task, 'success');
}

function handleFail(event: CustomEvent<PlatformProgressEventDetail>) {
  const task = findTask(event.detail.id);
  if (!task) {
    return;
  }
  task.errorMessage = event.detail.errorMessage || '任务失败';
  if (!task.visible) {
    removeTask(task.id);
    return;
  }
  finishTask(task, 'exception');
}

function updateRunningTask(task: PlatformProgressTask) {
  if (task.status !== 'running') {
    return;
  }
  const elapsed = Date.now() - task.startedAt;
  const ratio = elapsed / task.estimatedMs;
  const nextPercentage = resolveRunningPercentage(ratio, elapsed, task.estimatedMs);
  task.percentage = Math.max(task.percentage, nextPercentage);
}

function resolveRunningPercentage(ratio: number, elapsed: number, estimatedMs: number) {
  if (ratio <= 0.22) {
    return 8 + easeOutCubic(ratio / 0.22) * 32;
  }
  if (ratio <= 0.72) {
    return 40 + easeOutCubic((ratio - 0.22) / 0.5) * 42;
  }
  if (ratio <= 1) {
    return 82 + easeOutCubic((ratio - 0.72) / 0.28) * 10;
  }
  const crawlRatio = Math.min(1, (elapsed - estimatedMs) / Math.max(estimatedMs * 0.8, 1_500));
  return 92 + crawlRatio * 4;
}

function finishTask(task: PlatformProgressTask, status: Exclude<PlatformTaskStatus, 'running'>) {
  if (task.timer !== undefined) {
    window.clearInterval(task.timer);
    task.timer = undefined;
  }
  if (task.showTimer !== undefined) {
    window.clearTimeout(task.showTimer);
    task.showTimer = undefined;
  }
  if (status === 'success') {
    task.visible = true;
    task.percentage = Math.max(task.percentage, 92);
    task.settleTimer = window.setTimeout(() => {
      task.settleTimer = undefined;
      task.status = 'success';
      task.percentage = 100;
      task.hideTimer = window.setTimeout(() => {
        removeTask(task.id);
      }, SUCCESS_HIDE_DELAY_MS);
    }, IDLE_SUCCESS_DELAY_MS);
    return;
  }

  if (task.settleTimer !== undefined) {
    window.clearTimeout(task.settleTimer);
    task.settleTimer = undefined;
  }
  task.status = 'exception';
  task.visible = true;
  task.percentage = Math.max(task.percentage, 92);
  task.hideTimer = window.setTimeout(() => {
    removeTask(task.id);
  }, FAILURE_HIDE_DELAY_MS);
}

function estimateDurationMs(options: PlatformProgressOptions) {
  const profile = options.profile ?? 'background';
  const baseMs = PROFILE_BASE_MS[profile];
  const rowCount = normalizePositiveNumber(options.rowCount);
  const workload = normalizePositiveNumber(options.workload);
  const dataCostMs = rowCount * PROFILE_ROW_COST_MS[profile] + workload * 350;
  const learnedMs = options.learnDuration ? readLearnedDuration(options.endpointKey) : 0;
  const blendedMs = learnedMs > 0 ? Math.max(baseMs, learnedMs * 0.8) : baseMs;
  return clamp(blendedMs + dataCostMs, MIN_ESTIMATED_MS, MAX_ESTIMATED_MS);
}

function rememberActualDuration(task: PlatformProgressTask, durationMs: number) {
  if (!task.endpointKey || durationMs <= 0 || typeof window === 'undefined') {
    return;
  }
  try {
    const durations = readLearnedDurations();
    const previous = durations[task.endpointKey];
    durations[task.endpointKey] = previous
      ? Math.round(Math.max(previous * 0.85, durationMs * 0.45))
      : durationMs;
    window.localStorage.setItem(PLATFORM_PROGRESS_LEARNED_KEY, JSON.stringify(durations));
  } catch {
    // Progress learning is only a perception aid; blocked localStorage should not affect real work.
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
    const raw = window.localStorage.getItem(PLATFORM_PROGRESS_LEARNED_KEY);
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

function findJoinableVisibleTask(nextProfile: PlatformProgressProfile) {
  const visibleTask = currentTask.value;
  if (!visibleTask || !canJoinProgressSession(visibleTask.profile, nextProfile)) {
    return null;
  }
  if (visibleTask.status === 'exception') {
    return null;
  }
  return visibleTask.settleTimer !== undefined || visibleTask.status === 'success' ? visibleTask : null;
}

function canJoinProgressSession(previousProfile: PlatformProgressProfile, nextProfile: PlatformProgressProfile) {
  if (isExportProfile(previousProfile) !== isExportProfile(nextProfile)) {
    return false;
  }
  return true;
}

function isExportProfile(profile: PlatformProgressProfile) {
  return profile === 'export' || profile === 'heavyExport' || profile === 'template';
}

function inheritPercentage(task: PlatformProgressTask) {
  return clamp(task.percentage, 18, 96);
}

function clearTaskTimers(task: PlatformProgressTask) {
  if (task.timer !== undefined) {
    window.clearInterval(task.timer);
    task.timer = undefined;
  }
  if (task.showTimer !== undefined) {
    window.clearTimeout(task.showTimer);
    task.showTimer = undefined;
  }
  if (task.settleTimer !== undefined) {
    window.clearTimeout(task.settleTimer);
    task.settleTimer = undefined;
  }
  if (task.hideTimer !== undefined) {
    window.clearTimeout(task.hideTimer);
    task.hideTimer = undefined;
  }
}

function defaultLabel(profile?: PlatformProgressProfile) {
  switch (profile) {
    case 'route':
      return '正在打开页面';
    case 'table':
      return '正在加载数据';
    case 'statistic':
      return '正在计算统计';
    case 'filter':
      return '正在筛选数据';
    case 'refresh':
      return '正在刷新数据';
    case 'export':
    case 'heavyExport':
    case 'template':
      return '正在生成导出文件';
    default:
      return '正在处理请求';
  }
}

function normalizeEndpointKey(source: string) {
  return source.split('?')[0]?.replace(/^\/api\//, '') || 'task';
}

function priorityOf(profile: PlatformProgressProfile) {
  switch (profile) {
    case 'heavyExport':
    case 'export':
    case 'template':
      return 80;
    case 'route':
      return 70;
    case 'statistic':
      return 60;
    case 'table':
    case 'filter':
      return 50;
    case 'refresh':
      return 40;
    default:
      return 10;
  }
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
