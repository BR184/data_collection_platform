import type { GitlabSyncLog, GitlabSyncStatus, GitlabSyncType, MirrorPurgeResult } from '../types/api';

const ACTIVE_POLLING_STATUSES: GitlabSyncStatus[] = ['PENDING', 'QUEUED', 'RUNNING', 'RETRYING', 'CANCELLING'];

const SYNC_TYPE_LABELS: Record<GitlabSyncType, string> = {
  FULL: '全量同步',
  INCREMENTAL: '增量同步',
  COMPENSATION: '补偿同步',
  WEBHOOK: 'Webhook同步',
  PURGE: '删除镜像数据',
};

const SYNC_TYPE_TAG_TYPES: Record<GitlabSyncType, '' | 'danger' | 'info' | 'success' | 'warning'> = {
  FULL: 'warning',
  INCREMENTAL: 'info',
  COMPENSATION: 'success',
  WEBHOOK: '',
  PURGE: 'danger',
};

const SYNC_STATUS_LABELS: Record<GitlabSyncStatus | 'IDLE', string> = {
  PENDING: '等待中',
  QUEUED: '排队中',
  RUNNING: '执行中',
  RETRYING: '等待重试',
  SUCCESS: '成功',
  PARTIAL_SUCCESS: '部分成功',
  FAILED: '失败',
  CANCELLED: '已中止',
  TIMEOUT: '已超时',
  CANCELLING: '中止中',
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
  if (!value) {
    return '-';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleString('zh-CN', { hour12: false });
}

export function formatDuration(log: GitlabSyncLog) {
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
    return `同步已完成，补偿窗口扫描时跳过了 ${skippedTablesMatch[1]} 张缺少时间列的表`;
  }

  if (/^Sync completed successfully$/i.test(normalized)) {
    return '同步已完成';
  }
  if (/^Sync cancelled by user$/i.test(normalized)) {
    return '同步已被用户中止';
  }
  if (/^Task heartbeat timed out$/i.test(normalized)) {
    return '任务心跳超时';
  }
  if (/^Cancellation requested by user$/i.test(normalized)) {
    return '已收到用户中止请求';
  }
  if (/^已收到用户中止请求$/i.test(normalized)) {
    return '已收到用户中止请求';
  }
  if (/^同步已完成$/i.test(normalized)) {
    return '同步已完成';
  }

  if (syncType === 'FULL' && /^Manual full sync$/i.test(normalized)) {
    return '手工触发的全量同步';
  }
  if (syncType === 'INCREMENTAL' && /^Manual recovery incremental sync(?: requested)?$/i.test(normalized)) {
    return '手工恢复增量同步';
  }
  if (syncType === 'COMPENSATION' && /^Scheduled compensation sync$/i.test(normalized)) {
    return '定时补偿同步';
  }
  if (syncType === 'WEBHOOK') {
    const webhookMatch = normalized.match(/^Triggered by webhook:\s*(.+)$/i);
    if (webhookMatch) {
      return `Webhook 精确更新：${webhookMatch[1]}`;
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
      return 'Webhook 触发的业务对象精确更新。';
    case 'INCREMENTAL':
      return '人工触发的恢复型时间窗口增量同步。';
    case 'COMPENSATION':
      return '定时补偿窗口兜底同步。';
    case 'FULL':
      return '全量重建或初始化同步。';
    case 'PURGE':
      return '删除镜像数据。';
    default:
      return '-';
  }
}

export function buildPurgeSummaryHtml(result: MirrorPurgeResult) {
  const scopeText =
    result.scope === 'MIRROR_DATA_EXCLUDING_CURRENT_WHITELIST'
      ? '删除白名单外镜像数据'
      : '删除全部镜像数据';
  const syncTimeResetText = result.syncTimestampsReset ? '已重置' : '未重置';
  return [
    `<strong>${scopeText}已完成</strong>`,
    `删除镜像表：${result.droppedMirrorTables} 张`,
    `清理数据表：${result.truncatedTables} 张`,
    `同步时间：${syncTimeResetText}`,
  ].join('<br />');
}
