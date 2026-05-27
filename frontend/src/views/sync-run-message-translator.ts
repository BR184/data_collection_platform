import type { GitlabSyncType, SyncRunLog } from '../types/api';
import { syncStatusText } from './mirror-sync-status-labels';

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
    return `${syncRunNameText(completedStatusMatch[1])}已完成，状态：${syncStatusText(completedStatusMatch[2])}`;
  }

  if (/^Sync completed successfully$/i.test(normalized)) {
    return '同步已完成';
  }
  if (/^Sync cancelled by user$/i.test(normalized)) {
    return '同步已由用户取消';
  }
  if (/^Sync run cancelled$/i.test(normalized)) {
    return '同步运行已取消';
  }
  if (
    /^Queued sync run cancelled$/i.test(normalized) ||
    normalized === '已取消排队中的同步任务' ||
    normalized === '已取消等待中的同步任务'
  ) {
    return '已取消尚未开始的同步任务';
  }
  if (/^Cancelled before worker start$/i.test(normalized)) {
    return '任务启动前已取消';
  }
  if (/^Cancellation requested$/i.test(normalized)) {
    return '已请求取消同步任务';
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
    return '手动刷新最新数据';
  }
  if (/^Scheduled compensation sync$/i.test(normalized) && syncType === 'COMPENSATION') {
    return '自动补偿扫描';
  }
  if (/^Daily full compensation scan$/i.test(normalized) && syncType === 'COMPENSATION') {
    return '定时全量补偿';
  }
  if (normalized === '手动全量补偿对账' && syncType === 'COMPENSATION') {
    return '手动全量补偿对账';
  }
  if (/^Daily verification scan$/i.test(normalized) && syncType === 'COMPENSATION') {
    return '定时全量补偿';
  }
  if (/^Merged into a full sync submitted for the same source$/i.test(normalized)) {
    return '已合并到当前全量同步，完成后以全量结果为准。';
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

function syncRunNameText(value: string) {
  const normalized = value.trim();
  if (/^Sync run\b/i.test(normalized)) {
    return '同步运行 ';
  }
  if (/^Fact refresh run\b/i.test(normalized)) {
    return '事实刷新运行 ';
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
      return log.runType === 'TABLE_REFRESH' ? '单表刷新。' : '刷新最新数据。';
    case 'COMPENSATION':
      if (log.runType === 'FACT_REFRESH') {
        return '事实数据刷新。';
      }
      if (log.runType === 'FULL_COMPENSATION_SCAN') {
        return '全量补偿对账。';
      }
      return '自动补偿扫描。';
    case 'FULL':
      return '全量校验或初始化同步。';
    case 'PURGE':
      return '删除镜像数据。';
    default:
      return '-';
  }
}
