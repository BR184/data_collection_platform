export interface RealtimeWorkspaceStatusResponse {
  workspaceKey: string;
  supported: boolean;
  status: 'IDLE' | 'READY' | 'REFRESHING' | 'FAILED' | 'UNSUPPORTED' | string;
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
  mirrorStatus?: string | null;
  factStatus?: string | null;
}
