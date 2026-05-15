import type { SyncRunLog, GitlabSyncStatus, GitlabSyncType, MirrorPurgeResult } from '../types/api';
import { formatBeijingDateTime } from '../utils/beijing-time';

const ACTIVE_POLLING_STATUSES: GitlabSyncStatus[] = ['PENDING', 'QUEUED', 'RUNNING', 'RETRYING', 'CANCELLING'];

const SYNC_TYPE_LABELS: Record<GitlabSyncType, string> = {
  FULL: '全量同步',
  INCREMENTAL: '增量同步',
  COMPENSATION: '补偿扫描',
  SYSTEM_HOOK: 'System Hook 唤醒',
  PURGE: '删除镜像数据',
};

const SYNC_TYPE_TAG_TYPES: Record<GitlabSyncType, '' | 'danger' | 'info' | 'success' | 'warning'> = {
  FULL: 'warning',
  INCREMENTAL: 'info',
  COMPENSATION: 'success',
  SYSTEM_HOOK: '',
  PURGE: 'danger',
};

const SYNC_STATUS_LABELS: Record<GitlabSyncStatus | 'IDLE', string> = {
  PENDING: '待执行',
  QUEUED: '排队中',
  RUNNING: '执行中',
  RETRYING: '重试中',
  SUCCESS: '成功',
  PARTIAL_SUCCESS: '部分成功',
  FAILED: '失败',
  CANCELLED: '已取消',
  TIMEOUT: '已超时',
  CANCELLING: '取消中',
  IDLE: '空闲',
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

export function syncStatusText(statusValue: GitlabSyncStatus | 'IDLE' | string) {
  return SYNC_STATUS_LABELS[statusValue as GitlabSyncStatus | 'IDLE'] ?? statusValue;
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
    return `同步已完成，补偿扫描跳过 ${skippedTablesMatch[1]} 张缺少时间列的表。`;
  }

  const fullVerificationMatch = normalized.match(/^Full table verification completed with status\s+(.+)$/i);
  if (fullVerificationMatch) {
    return `全量表校验已完成，状态：${syncStatusText(fullVerificationMatch[1])}`;
  }
  const completedStatusMatch = normalized.match(/^(.+) completed with status\s+(.+)$/i);
  if (completedStatusMatch) {
    return `${completedStatusMatch[1]} 已完成，状态：${syncStatusText(completedStatusMatch[2])}`;
  }

  if (/^Sync completed successfully$/i.test(normalized)) {
    return '同步已完成';
  }
  if (/^Sync cancelled by user$/i.test(normalized)) {
    return '同步已由用户取消';
  }
  if (/^Task heartbeat timed out$/i.test(normalized)) {
    return '任务心跳超时';
  }
  if (/^Cancellation requested by user$/i.test(normalized)) {
    return '用户已请求取消';
  }
  if (/^Manual full sync$/i.test(normalized) && syncType === 'FULL') {
    return '手动全量同步';
  }
  if (/^Manual recovery incremental sync(?: requested)?$/i.test(normalized) && syncType === 'INCREMENTAL') {
    return '手动增量同步';
  }
  if (/^Scheduled compensation sync$/i.test(normalized) && syncType === 'COMPENSATION') {
    return '定时补偿扫描';
  }
  if (/^Delete mirror data$/i.test(normalized) && syncType === 'PURGE') {
    return '删除镜像数据';
  }
  if (/^Delete non-whitelist mirror data$/i.test(normalized) && syncType === 'PURGE') {
    return '删除白名单外镜像数据';
  }
  if (syncType === 'SYSTEM_HOOK') {
    const systemHookMatch = normalized.match(/^Triggered by system hook:\s*(.+)$/i);
    if (systemHookMatch) {
      return `System Hook 已唤醒同步：${systemHookMatch[1]}`;
    }
  }

  return normalized;
}

export function syncLogMessage(log: SyncRunLog) {
  const message = translateSyncMessage(log.message, log.syncType);
  if (message) {
    return message;
  }
  switch (log.syncType) {
    case 'SYSTEM_HOOK':
      return 'System Hook 触发了目标表更新。';
    case 'INCREMENTAL':
      return '手动增量同步。';
    case 'COMPENSATION':
      return '定时补偿扫描。';
    case 'FULL':
      return '全量校验或初始化同步。';
    case 'PURGE':
      return '删除镜像数据。';
    default:
      return '-';
  }
}

export function buildPurgeSummaryHtml(result: MirrorPurgeResult) {
  const scopeText =
    result.scope === 'MIRROR_DATA_EXCLUDING_CURRENT_WHITELIST'
      ? '已删除白名单外镜像数据'
      : '已删除全部镜像数据';
  const syncTimeResetText = result.syncTimestampsReset ? '已重置' : '未重置';
  return [
    `<strong>${scopeText}</strong>`,
    `删除镜像表：${result.droppedMirrorTables}`,
    `清空数据表：${result.truncatedTables}`,
    `同步时间：${syncTimeResetText}`,
  ].join('<br />');
}
