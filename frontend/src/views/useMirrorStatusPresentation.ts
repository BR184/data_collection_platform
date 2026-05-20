import { computed, type Ref } from 'vue';
import type { GitlabSyncStatus, SyncRunSummary, MirrorStatusResponse, SyncProgress } from '../types/api';
import {
  formatDateTime,
  syncStatusTagType,
  syncStatusText,
  translateSyncMessage,
} from './mirror-settings-helpers';

const ACTIVE_POLLING_STATUSES: GitlabSyncStatus[] = ['PENDING', 'QUEUED', 'RUNNING', 'CANCELLING'];
const TERMINAL_STATUSES: Array<GitlabSyncStatus | 'IDLE'> = [
  'SUCCESS',
  'PARTIAL_SUCCESS',
  'FAILED',
  'CANCELLED',
  'TIMEOUT',
  'IDLE',
];

function activeProgressPercent(completedTables: number, totalTables: number): number {
  if (totalTables <= 0) {
    return 5;
  }
  const rawPercent = Math.round((completedTables / totalTables) * 100);
  return Math.min(95, Math.max(5, rawPercent));
}

function fallbackPhaseText(task: SyncRunSummary | null): string {
  if (!task || !ACTIVE_POLLING_STATUSES.includes(task.status)) {
    return '空闲';
  }
  if (task.status === 'QUEUED') {
    return '排队中';
  }
  switch (task.taskType) {
    case 'FULL':
      return '全量同步';
    case 'INCREMENTAL':
    case 'SYSTEM_HOOK':
      return '增量同步';
    case 'COMPENSATION':
      return '补偿扫描';
    case 'PURGE':
      return '删除镜像数据';
    default:
      return '准备同步';
  }
}

function terminalProgressHint(statusValue: GitlabSyncStatus | 'IDLE', progress: SyncProgress | null) {
  const records = progress?.syncedRecords ?? 0;
  switch (statusValue) {
    case 'SUCCESS':
      return records > 0 ? `同步已完成，本次写入 ${records} 条记录。` : '同步已完成，本次未发现需要写入的新记录。';
    case 'PARTIAL_SUCCESS':
      return records > 0 ? `同步部分成功，本次已写入 ${records} 条记录，请查看表级诊断。` : '同步部分成功，请查看表级诊断。';
    case 'FAILED':
      return '同步失败，请查看最近同步日志和表级诊断。';
    case 'TIMEOUT':
      return '同步超时，请稍后重试或查看表级诊断。';
    case 'CANCELLED':
      return '同步已取消。';
    default:
      return '当前没有正在执行的同步任务。';
  }
}

export function useMirrorStatusPresentation(status: Ref<MirrorStatusResponse | null>) {
  const progress = computed<SyncProgress | null>(() => status.value?.progress ?? null);
  const currentTask = computed<SyncRunSummary | null>(() => status.value?.currentTask ?? null);
  const recentLogs = computed(() => status.value?.logs ?? []);
  const latestLog = computed(() => recentLogs.value[0] ?? null);
  const canCancel = computed(
    () => currentTask.value != null && ACTIVE_POLLING_STATUSES.includes(currentTask.value.status),
  );

  const lastSyncDisplay = computed(() => {
    const lastFinishedAt = latestLog.value?.finishedAt || latestLog.value?.startedAt;
    if (!lastFinishedAt) {
      return '最近同步：暂无';
    }
    return `最近同步：${formatDateTime(lastFinishedAt)}（${syncStatusText(latestLog.value?.status ?? 'IDLE')}）`;
  });

  const progressPercent = computed(() => {
    const current = progress.value;
    const currentStatus = status.value?.currentStatus ?? currentTask.value?.status ?? 'IDLE';
    if (TERMINAL_STATUSES.includes(currentStatus)) {
      return currentStatus === 'SUCCESS' || currentStatus === 'PARTIAL_SUCCESS' ? 100 : 0;
    }
    if (!current) {
      return currentTask.value && ACTIVE_POLLING_STATUSES.includes(currentTask.value.status) ? 5 : 0;
    }
    if (current.totalTables <= 0) {
      return currentTask.value && ACTIVE_POLLING_STATUSES.includes(currentTask.value.status) ? 5 : 0;
    }
    return activeProgressPercent(current.completedTables, current.totalTables);
  });

  const displayStatus = computed(() => {
    const raw = status.value?.currentStatus ?? 'IDLE';
    if (raw !== 'IDLE') {
      return { text: syncStatusText(raw), type: syncStatusTagType(raw) };
    }
    const log = latestLog.value;
    if (log != null) {
      return { text: `最近同步${syncStatusText(log.status)}`, type: syncStatusTagType(log.status) };
    }
    return { text: syncStatusText('IDLE'), type: syncStatusTagType('IDLE') };
  });

  const statusMessageClass = computed(() => [
    'status-message',
    `status-message--${displayStatus.value.type}`,
  ]);

  const phaseText = computed(() => {
    const phase = progress.value?.phase;
    switch (phase) {
      case 'FULL_SYNC':
        return '全量同步';
      case 'INCREMENTAL_SYNC':
        return '增量同步';
      case 'COMPENSATION_SYNC':
        return '补偿扫描';
      case 'FACT_REFRESH':
        return '事实刷新';
      default:
        return fallbackPhaseText(currentTask.value);
    }
  });

  const progressHint = computed(() => {
    const current = progress.value;
    const currentStatus = status.value?.currentStatus ?? currentTask.value?.status ?? 'IDLE';
    if (TERMINAL_STATUSES.includes(currentStatus)) {
      return terminalProgressHint(currentStatus, current);
    }
    if (!current) {
      if (currentTask.value?.status === 'PENDING') {
        return '同步任务已提交，正在等待执行。';
      }
      if (currentTask.value?.status === 'RUNNING') {
        return '同步任务已开始，正在准备扫描和进度信息。';
      }
      if (currentTask.value?.status === 'QUEUED') {
        return '已有同步任务执行中，本次请求正在排队。';
      }
      if (currentTask.value?.status === 'CANCELLING') {
        return '已请求取消，当前批次正在安全停止。';
      }
      return '当前没有正在执行的同步任务。';
    }
    if (current.phase === 'FACT_REFRESH') {
      return current.factRefreshStatus
        ? `事实刷新状态：${syncStatusText(current.factRefreshStatus as GitlabSyncStatus)}`
        : '镜像同步已完成，正在刷新事实表。';
    }
    if (current.activeTableTasks?.length) {
      return `正在处理 ${current.activeTableTasks.length} 张表，已写入 ${current.appliedRows ?? current.syncedRecords} 条记录。`;
    }
    if (current.currentTable) {
      return `正在处理表 ${current.currentTable}，已同步 ${current.syncedRecords} 条记录。`;
    }
    return '同步任务已开始，正在准备表扫描。';
  });

  const currentMessageText = computed(() => {
    const rawMessage = status.value?.currentMessage?.trim() ?? '';
    if (!rawMessage) {
      if (currentTask.value && ACTIVE_POLLING_STATUSES.includes(currentTask.value.status)) {
        return '同步任务已开始，正在收集最新状态。';
      }
      return '当前没有正在执行的同步任务。';
    }
    return translateSyncMessage(rawMessage, currentTask.value?.taskType);
  });

  return {
    progress,
    currentTask,
    recentLogs,
    latestLog,
    canCancel,
    lastSyncDisplay,
    progressPercent,
    displayStatus,
    statusMessageClass,
    phaseText,
    progressHint,
    currentMessageText,
  };
}
