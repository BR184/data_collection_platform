import type { GitlabSyncStatus } from './sync';

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
}
