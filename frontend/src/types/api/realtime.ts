import type { GitlabSyncStatus } from './sync';

/** 刷新提交结论：区分本次已接受、同一刷新已在跟踪与冷却未接受。 */
export type RealtimeRefreshSubmissionOutcome =
  | 'ACCEPTED'
  | 'ALREADY_REFRESHING'
  | 'COOLDOWN'
  | 'UNSUPPORTED';

export interface RealtimeWorkspaceStatusResponse {
  workspaceKey: string;
  supported: boolean;
  status: string;
  message: string;
  refreshing: boolean;
  lastSyncedAt?: string | null;
  lastRefreshStartedAt?: string | null;
  lastRefreshFinishedAt?: string | null;
  jobId?: number | null;
  sourceTables?: string[];
  plannedTasks?: number | null;
  unsupportedTables?: string[];
  factRefreshPlanned?: boolean | null;
  mirrorStatus?: GitlabSyncStatus | string | null;
  factStatus?: GitlabSyncStatus | string | null;
  /** 仅提交刷新（POST /refresh）时返回；读取状态时为空。 */
  submissionOutcome?: RealtimeRefreshSubmissionOutcome | null;
  /** 本次刷新的跟踪身份，用于复用同一条后台跟踪；读取状态时为空。 */
  trackingId?: string | null;
}
