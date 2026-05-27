import type { GitlabTableRowStrategy, SyncRunTableDiagnostics } from '../types/api';

const TABLE_ROW_STRATEGY_LABELS: Record<string, string> = {
  INCREMENTAL: '按更新时间补齐',
  FULL_RECONCILE: '全量补偿对账',
  FULL_SMALL_TABLE: '小表整表校验',
  VERIFY_ONLY: '只校验',
  UNSUPPORTED: '暂不支持自动写入',
};

const DIRTY_REASON_LABELS: Record<string, string> = {
  row_count_drift: '源表与镜像表行数不一致',
  schema_changed: '源表结构发生变化',
  task_failed: '最近一次表任务未完成',
};

export function tableRowStrategyText(strategy: GitlabTableRowStrategy | string | null | undefined) {
  if (!strategy) {
    return '-';
  }
  return TABLE_ROW_STRATEGY_LABELS[strategy] ?? '其他处理策略';
}

export function tableDiagnosticNote(row: SyncRunTableDiagnostics) {
  if (row.latestTaskError || row.lastError) {
    return row.latestTaskError || row.lastError || '表任务需要查看明细';
  }
  if (row.blockingRunId) {
    return '当前同步正在处理相关表';
  }
  if (row.dirtyReason) {
    return DIRTY_REASON_LABELS[row.dirtyReason] ?? '表状态需要查看明细';
  }
  if (row.driftSummary) {
    return row.driftSummary;
  }
  return tableRowStrategyText(row.rowStrategy);
}
