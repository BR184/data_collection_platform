import type { GitlabSyncStatus, GitlabSyncType, SyncRunLog } from '../types/api';

export const ACTIVE_POLLING_STATUSES: GitlabSyncStatus[] = ['PENDING', 'QUEUED', 'RUNNING', 'RETRYING', 'CANCELLING'];

const SYNC_TYPE_LABELS: Record<GitlabSyncType, string> = {
  FULL: '全量同步',
  INCREMENTAL: '刷新最新数据',
  COMPENSATION: '自动补偿扫描',
  SYSTEM_HOOK: 'System Hook 唤醒',
  PURGE: '删除镜像数据',
};

const SYNC_RUN_TYPE_LABELS: Record<string, string> = {
  FULL_SYNC: '全量同步',
  INCREMENTAL_SYNC: '刷新最新数据',
  TABLE_REFRESH: '单表刷新',
  SYSTEM_HOOK: 'System Hook 唤醒',
  COMPENSATION_SCAN: '自动补偿扫描',
  FULL_COMPENSATION_SCAN: '全量补偿对账',
  FACT_REFRESH: '事实数据刷新',
};

const SYNC_TYPE_TAG_TYPES: Record<GitlabSyncType, '' | 'danger' | 'info' | 'success' | 'warning'> = {
  FULL: 'warning',
  INCREMENTAL: 'info',
  COMPENSATION: 'success',
  SYSTEM_HOOK: '',
  PURGE: 'danger',
};

const SYNC_STATUS_LABELS: Record<GitlabSyncStatus | 'IDLE', string> = {
  PENDING: '等待执行',
  QUEUED: '等待当前同步完成',
  RUNNING: '处理中',
  RETRYING: '重试中',
  SUCCESS: '已完成',
  PARTIAL_SUCCESS: '已完成，需查看明细',
  FAILED: '需要处理',
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

const TABLE_TASK_STATUS_LABELS: Record<GitlabSyncStatus, string> = {
  PENDING: '等待处理',
  QUEUED: '等待处理',
  RUNNING: '处理中',
  RETRYING: '重试中',
  SUCCESS: '已完成',
  PARTIAL_SUCCESS: '已完成，需查看明细',
  FAILED: '需要处理',
  CANCELLED: '已取消',
  TIMEOUT: '已超时',
  CANCELLING: '取消中',
};

const TRIGGER_TYPE_LABELS: Record<string, string> = {
  MANUAL: '手动触发',
  SCHEDULED: '定时触发',
  SYSTEM_HOOK: 'System Hook 触发',
  AUTO: '自动触发',
  API: '接口触发',
};

export function syncStatusText(statusValue: GitlabSyncStatus | 'IDLE' | string) {
  return SYNC_STATUS_LABELS[statusValue as GitlabSyncStatus | 'IDLE'] ?? '未知状态';
}

export function syncStatusTagType(statusValue: GitlabSyncStatus | 'IDLE') {
  return SYNC_STATUS_TAG_TYPES[statusValue] ?? 'info';
}

export function syncTypeText(syncType?: GitlabSyncType | string | null) {
  if (!syncType) {
    return '其他同步任务';
  }
  return SYNC_TYPE_LABELS[syncType as GitlabSyncType] ?? '其他同步任务';
}

export function syncLogTypeText(log: Pick<SyncRunLog, 'syncType' | 'runType'>) {
  const runType = log.runType?.trim();
  if (runType && SYNC_RUN_TYPE_LABELS[runType]) {
    return SYNC_RUN_TYPE_LABELS[runType];
  }
  return syncTypeText(log.syncType);
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

export function tableTaskStatusText(statusValue: GitlabSyncStatus | string) {
  return TABLE_TASK_STATUS_LABELS[statusValue as GitlabSyncStatus] ?? '未知状态';
}

export function syncTriggerTypeText(triggerType?: string | null) {
  const normalized = triggerType?.trim();
  if (!normalized) {
    return '-';
  }
  return TRIGGER_TYPE_LABELS[normalized] ?? '其他触发';
}
