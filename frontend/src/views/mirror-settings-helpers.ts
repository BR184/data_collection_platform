import type { GitlabSyncLog, GitlabSyncStatus, GitlabSyncType, MirrorPurgeResult } from '../types/api';
import { formatBeijingDateTime } from '../utils/beijing-time';

const ACTIVE_POLLING_STATUSES: GitlabSyncStatus[] = ['PENDING', 'QUEUED', 'RUNNING', 'RETRYING', 'CANCELLING'];

const SYNC_TYPE_LABELS: Record<GitlabSyncType, string> = {
  FULL: 'Full sync',
  INCREMENTAL: 'Incremental sync',
  COMPENSATION: 'Compensation sync',
  WEBHOOK: 'System Hook sync',
  PURGE: 'Delete mirror data',
};

const SYNC_TYPE_TAG_TYPES: Record<GitlabSyncType, '' | 'danger' | 'info' | 'success' | 'warning'> = {
  FULL: 'warning',
  INCREMENTAL: 'info',
  COMPENSATION: 'success',
  WEBHOOK: '',
  PURGE: 'danger',
};

const SYNC_STATUS_LABELS: Record<GitlabSyncStatus | 'IDLE', string> = {
  PENDING: 'Pending',
  QUEUED: 'Queued',
  RUNNING: 'Running',
  RETRYING: 'Retrying',
  SUCCESS: 'Success',
  PARTIAL_SUCCESS: 'Partial success',
  FAILED: 'Failed',
  CANCELLED: 'Cancelled',
  TIMEOUT: 'Timed out',
  CANCELLING: 'Cancelling',
  IDLE: 'Idle',
};

const SYNC_STATUS_TAG_TYPES: Record<GitlabSyncStatus | 'IDLE', 'danger' | 'info' | 'success' | 'warning'> = {
  PENDING: 'warning',
  QUEUED: 'warning',
  RUNNING: 'warning',
  RETRYING: 'warning',
  SUCCESS: 'success',
  PARTIAL_SUCCESS: 'warning',
  FAILED: 'danger',
  CANCELLED: 'info',
  TIMEOUT: 'danger',
  CANCELLING: 'warning',
  IDLE: 'info',
};

export function formatDateTime(value?: string | null) {
  return formatBeijingDateTime(value);
}

export function formatDuration(log: GitlabSyncLog) {
  if (!log.finishedAt || !log.startedAt) {
    return ACTIVE_POLLING_STATUSES.includes(log.status) ? 'In progress' : '-';
  }
  const start = new Date(log.startedAt).getTime();
  const end = new Date(log.finishedAt).getTime();
  if (Number.isNaN(start) || Number.isNaN(end)) {
    return '-';
  }
  const seconds = Math.max(0, Math.round((end - start) / 1000));
  if (seconds < 60) {
    return `${seconds}s`;
  }
  const minutes = Math.floor(seconds / 60);
  const remain = seconds % 60;
  return `${minutes}m ${remain}s`;
}

export function formatLogTime(log: GitlabSyncLog) {
  return formatDateTime(log.finishedAt || log.startedAt);
}

export function syncStatusText(statusValue: GitlabSyncStatus | 'IDLE') {
  return SYNC_STATUS_LABELS[statusValue] ?? statusValue;
}

export function syncStatusTagType(statusValue: GitlabSyncStatus | 'IDLE') {
  return SYNC_STATUS_TAG_TYPES[statusValue] ?? 'info';
}

export function syncTypeText(syncType: GitlabSyncType) {
  return SYNC_TYPE_LABELS[syncType] ?? syncType;
}

export function syncTypeTagType(syncType: GitlabSyncType) {
  return SYNC_TYPE_TAG_TYPES[syncType] ?? 'info';
}

export function logStatusType(statusValue: GitlabSyncStatus) {
  return syncStatusTagType(statusValue);
}

export function logStatusText(statusValue: GitlabSyncStatus) {
  return syncStatusText(statusValue);
}

export function translateSyncMessage(message?: string | null, syncType?: GitlabSyncType | null) {
  const normalized = message?.trim() ?? '';
  if (!normalized) {
    return '';
  }

  const skippedTablesMatch = normalized.match(
    /^Sync completed successfully, skipped (\d+) tables without time columns during compensation window scan$/i,
  );
  if (skippedTablesMatch) {
    return `Sync completed. Skipped ${skippedTablesMatch[1]} tables without time columns during the compensation scan.`;
  }

  if (/^Sync completed successfully$/i.test(normalized)) {
    return 'Sync completed';
  }
  if (/^Sync cancelled by user$/i.test(normalized)) {
    return 'Sync cancelled by user';
  }
  if (/^Task heartbeat timed out$/i.test(normalized)) {
    return 'Task heartbeat timed out';
  }
  if (/^Cancellation requested by user$/i.test(normalized)) {
    return 'Cancellation requested by user';
  }
  if (/^Manual full sync$/i.test(normalized) && syncType === 'FULL') {
    return 'Manual full sync';
  }
  if (/^Manual recovery incremental sync(?: requested)?$/i.test(normalized) && syncType === 'INCREMENTAL') {
    return 'Manual recovery incremental sync';
  }
  if (/^Scheduled compensation sync$/i.test(normalized) && syncType === 'COMPENSATION') {
    return 'Scheduled compensation sync';
  }
  if (syncType === 'WEBHOOK') {
    const webhookMatch = normalized.match(/^Triggered by webhook:\s*(.+)$/i);
    if (webhookMatch) {
      return `System Hook triggered update: ${webhookMatch[1]}`;
    }
  }

  return normalized;
}

export function syncLogMessage(log: GitlabSyncLog) {
  const message = translateSyncMessage(log.message, log.syncType);
  if (message) {
    return message;
  }
  switch (log.syncType) {
    case 'WEBHOOK':
      return 'System Hook triggered targeted update.';
    case 'INCREMENTAL':
      return 'Manual recovery incremental sync.';
    case 'COMPENSATION':
      return 'Scheduled compensation sync.';
    case 'FULL':
      return 'Full rebuild or initialization sync.';
    case 'PURGE':
      return 'Delete mirror data.';
    default:
      return '-';
  }
}

export function buildPurgeSummaryHtml(result: MirrorPurgeResult) {
  const scopeText =
    result.scope === 'MIRROR_DATA_EXCLUDING_CURRENT_WHITELIST'
      ? 'Deleted non-whitelist mirror data'
      : 'Deleted all mirror data';
  const syncTimeResetText = result.syncTimestampsReset ? 'Reset' : 'Not reset';
  return [
    `<strong>${scopeText}</strong>`,
    `Dropped mirror tables: ${result.droppedMirrorTables}`,
    `Truncated tables: ${result.truncatedTables}`,
    `Sync timestamps: ${syncTimeResetText}`,
  ].join('<br />');
}
