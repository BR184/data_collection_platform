import { describe, expect, it } from 'vitest';
import type { GitlabSyncLog, MirrorPurgeResult } from '../types/api';
import {
  buildPurgeSummaryHtml,
  formatDuration,
  formatLogTime,
  syncLogMessage,
  syncStatusTagType,
  syncStatusText,
  syncTypeTagType,
  syncTypeText,
  translateSyncMessage,
} from './mirror-settings-helpers';

function createLog(overrides: Partial<GitlabSyncLog> = {}): GitlabSyncLog {
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
    expect(syncStatusText('RUNNING')).toBe('执行中');
    expect(syncStatusText('RETRYING')).toBe('等待重试');
    expect(syncStatusText('PARTIAL_SUCCESS')).toBe('部分成功');
    expect(syncStatusTagType('FAILED')).toBe('danger');
    expect(syncStatusTagType('PARTIAL_SUCCESS')).toBe('warning');
    expect(syncStatusText('IDLE')).toBe('空闲');
    expect(syncTypeText('PURGE')).toBe('删除镜像数据');
    expect(syncTypeTagType('FULL')).toBe('warning');
  });

  it('translates known sync messages and falls back to default log copy', () => {
    expect(translateSyncMessage('Sync completed successfully')).toBe('同步已完成');
    expect(translateSyncMessage('Triggered by webhook: issue#1', 'WEBHOOK')).toBe('Webhook 精确更新：issue#1');
    expect(
      translateSyncMessage(
        'Sync completed successfully, skipped 3 tables without time columns during compensation window scan',
      ),
    ).toBe('同步已完成，补偿窗口扫描时跳过了 3 张缺少时间列的表');
    expect(syncLogMessage(createLog({ syncType: 'COMPENSATION', message: '' }))).toBe('定时补偿窗口兜底同步。');
  });

  it('formats log time and duration safely', () => {
    expect(formatDuration(createLog())).toBe('1 分 5 秒');
    expect(formatDuration(createLog({ status: 'RUNNING', finishedAt: null }))).toBe('进行中');
    expect(formatDuration(createLog({ startedAt: 'invalid', finishedAt: 'also-invalid' }))).toBe('-');
    expect(formatLogTime(createLog({ finishedAt: 'invalid-date' }))).toBe('invalid-date');
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

    expect(buildPurgeSummaryHtml(result)).toContain('删除白名单外镜像数据已完成');
    expect(buildPurgeSummaryHtml(result)).toContain('删除镜像表：3 张');
    expect(buildPurgeSummaryHtml(result)).toContain('同步时间：已重置');
  });
});
