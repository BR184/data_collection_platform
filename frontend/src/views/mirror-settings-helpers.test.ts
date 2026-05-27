import { describe, expect, it } from 'vitest';
import type { SyncRunLog, MirrorPurgeResult } from '../types/api';
import {
  buildPurgeSummaryHtml,
  formatDateTime,
  formatDuration,
  formatLogTime,
  syncLogMessage,
  syncStatusTagType,
  syncStatusText,
  tableTaskStatusText,
  tableDiagnosticNote,
  tableRowStrategyText,
  syncTypeTagType,
  syncTypeText,
  translateSyncMessage,
} from './mirror-settings-helpers';

function createLog(overrides: Partial<SyncRunLog> = {}): SyncRunLog {
  return {
    id: 1,
    syncType: 'FULL',
    status: 'SUCCESS',
    message: 'Sync completed successfully',
    tableCount: 2,
    recordCount: 10,
    startedAt: '2026-04-27T10:00:00',
    finishedAt: '2026-04-27T10:01:05',
    ...overrides,
  };
}

describe('mirror settings helpers', () => {
  it('formats sync status and type labels for display', () => {
    expect(syncStatusText('RUNNING')).toBe('处理中');
    expect(syncStatusText('RETRYING')).toBe('重试中');
    expect(syncStatusText('PARTIAL_SUCCESS')).toBe('已完成，需查看明细');
    expect(syncStatusText('MERGED_INTO_ACTIVE_RUN')).toBe('未知状态');
    expect(syncStatusTagType('FAILED')).toBe('danger');
    expect(syncStatusTagType('PARTIAL_SUCCESS')).toBe('warning');
    expect(syncStatusText('IDLE')).toBe('空闲');
    expect(syncTypeText('PURGE')).toBe('删除镜像数据');
    expect(syncTypeText('SYSTEM_HOOK')).toBe('System Hook 唤醒');
    expect(syncTypeTagType('FULL')).toBe('warning');
    expect(tableTaskStatusText('QUEUED')).toBe('等待处理');
    expect(tableTaskStatusText('RUNNING')).toBe('处理中');
    expect(tableTaskStatusText('MERGED_INTO_ACTIVE_RUN')).toBe('未知状态');
    expect(tableRowStrategyText('INCREMENTAL')).toBe('按更新时间补齐');
    expect(tableRowStrategyText('FULL_RECONCILE')).toBe('全量补偿对账');
    expect(tableDiagnosticNote({
      sourceTable: 'issues',
      mirrorTable: 'ods_gitlab_issues',
      primaryKeyColumns: 'id',
      rowStrategy: 'INCREMENTAL',
      syncEnabled: true,
      dirty: true,
      dirtyReason: 'row_count_drift',
      blockingRunId: 'run-1',
    })).toBe('当前同步正在处理相关表');
  });

  it('uses stable fallback copy for unknown backend enum values', () => {
    expect(syncTypeText('UNKNOWN_SYNC_TYPE' as never)).toBe('其他同步任务');
    expect(tableRowStrategyText('UNKNOWN_ROW_STRATEGY')).toBe('其他处理策略');
    expect(tableDiagnosticNote({
      sourceTable: 'issues',
      mirrorTable: 'ods_gitlab_issues',
      primaryKeyColumns: 'id',
      rowStrategy: 'UNKNOWN_ROW_STRATEGY' as never,
      syncEnabled: true,
      dirty: true,
      dirtyReason: 'unknown_dirty_reason',
    })).toBe('表状态需要查看明细');
  });

  it('translates known sync messages and falls back to default log copy', () => {
    expect(translateSyncMessage('Sync completed successfully')).toBe('同步已完成');
    expect(translateSyncMessage('Cancellation requested')).toBe('已请求取消同步任务');
    expect(translateSyncMessage('Queued sync run cancelled')).toBe('已取消尚未开始的同步任务');
    expect(translateSyncMessage('Sync run cancelled')).toBe('同步运行已取消');
    expect(translateSyncMessage('Cancelled before worker start')).toBe('任务启动前已取消');
    expect(translateSyncMessage('Triggered by system hook: issue#1', 'SYSTEM_HOOK')).toBe('System Hook 已唤醒同步：issue#1');
    expect(
      translateSyncMessage(
        'Sync completed successfully, skipped 3 tables without time columns during compensation window scan',
      ),
    ).toBe('同步已完成，补偿扫描跳过 3 张缺少时间列的表。');
    expect(translateSyncMessage('Daily full compensation scan', 'COMPENSATION')).toBe('定时全量补偿');
    expect(translateSyncMessage('手动全量补偿对账', 'COMPENSATION')).toBe('手动全量补偿对账');
    expect(syncLogMessage(createLog({ syncType: 'COMPENSATION', message: '' }))).toBe('自动补偿扫描。');
    expect(syncLogMessage(createLog({ syncType: 'COMPENSATION', runType: 'FULL_COMPENSATION_SCAN', message: '' }))).toBe(
      '全量补偿对账。',
    );
    expect(syncLogMessage(createLog({ syncType: 'INCREMENTAL', runType: 'TABLE_REFRESH', message: '' }))).toBe('单表刷新。');
  });

  it('formats log time and duration safely', () => {
    expect(formatDuration(createLog())).toBe('1 分 5 秒');
    expect(formatDuration(createLog({ status: 'RUNNING', finishedAt: null }))).toBe('进行中');
    expect(formatDuration(createLog({ startedAt: 'invalid', finishedAt: 'also-invalid' }))).toBe('-');
    expect(formatLogTime(createLog({ finishedAt: 'invalid-date' }))).toBe('invalid-date');
    expect(formatDateTime('2026-04-27T02:00:00Z')).toBe('2026-04-27 10:00:00');
    expect(formatDateTime('2026-04-27T10:00:00')).toBe('2026-04-27 10:00:00');
  });

  it('builds the purge completion summary html', () => {
    const result: MirrorPurgeResult = {
      scope: 'MIRROR_DATA_EXCLUDING_CURRENT_WHITELIST',
      droppedMirrorTables: 3,
      droppedTableNames: ['ods_gitlab_issues'],
      truncatedTables: 2,
      truncatedTableNames: ['sys_table_registry'],
      syncTimestampsReset: true,
    };

    expect(buildPurgeSummaryHtml(result)).toContain('已删除白名单外镜像数据');
    expect(buildPurgeSummaryHtml(result)).toContain('删除镜像表：3');
    expect(buildPurgeSummaryHtml(result)).toContain('同步时间：已重置');
  });
});
