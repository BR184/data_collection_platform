import type { SyncRunLog } from '../types/api';
import { formatBeijingDateTime } from '../utils/beijing-time';
import { ACTIVE_POLLING_STATUSES } from './mirror-sync-status-labels';

export function formatDateTime(value?: string | null) {
  return formatBeijingDateTime(value);
}

export function formatDuration(log: SyncRunLog) {
  if (!log.finishedAt || !log.startedAt) {
    return ACTIVE_POLLING_STATUSES.includes(log.status) ? '进行中' : '-';
  }
  const start = new Date(log.startedAt).getTime();
  const end = new Date(log.finishedAt).getTime();
  if (Number.isNaN(start) || Number.isNaN(end)) {
    return '-';
  }
  const seconds = Math.max(0, Math.round((end - start) / 1000));
  if (seconds < 60) {
    return `${seconds} 秒`;
  }
  const minutes = Math.floor(seconds / 60);
  const remain = seconds % 60;
  return `${minutes} 分 ${remain} 秒`;
}

export function formatLogTime(log: SyncRunLog) {
  return formatDateTime(log.finishedAt || log.startedAt);
}
