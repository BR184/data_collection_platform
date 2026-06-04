export type WhitelistMode = 'RECOMMENDED' | 'ALL' | 'CUSTOM';
export type SourceMode = 'DIRECT' | 'DOCKER';
export type SyncThreadMode = 'FIXED' | 'CPU_RATIO';
export type CompensationScheduleMode = 'INTERVAL' | 'DAILY_TIME' | 'WINDOWED_INTERVAL';
export type CompensationMissedWindowPolicy = 'SKIP' | 'RUN_NEXT_WINDOW';
export type GitlabSyncType = 'FULL' | 'INCREMENTAL' | 'COMPENSATION' | 'SYSTEM_HOOK' | 'PURGE';
export type SyncRunTablePhase =
  | 'COMPENSATION_INCREMENTAL'
  | 'DAILY_VERIFY'
  | 'MANUAL_REFRESH'
  | 'FULL_REPAIR'
  | 'SHARD_REPAIR'
  | 'DELETE_RECONCILE';
export type GitlabTableRowStrategy = 'INCREMENTAL' | 'FULL_SMALL_TABLE' | 'VERIFY_ONLY' | 'UNSUPPORTED' | string;
export type GitlabSyncStatus =
  | 'PENDING'
  | 'QUEUED'
  | 'RUNNING'
  | 'RETRYING'
  | 'SUCCESS'
  | 'PARTIAL_SUCCESS'
  | 'FAILED'
  | 'CANCELLED'
  | 'TIMEOUT'
  | 'CANCELLING';

export interface GitlabSyncConfig {
  id?: number;
  name: string;
  enabled: boolean;
  sourceEnabled?: boolean;
  sourceInstance: string;
  autoSyncEnabled: boolean;
  sourceMode: SourceMode;
  whitelistMode: WhitelistMode;
  whitelistTables: string[];
  dbHost: string;
  dbPort: number;
  dbName: string;
  dbUsername: string;
  dbPassword: string;
  dockerContainerName?: string;
  systemHookSecret?: string;
  systemHookEnabled?: boolean;
  systemHookProjectId?: number | null;
  compensationIntervalMinutes: number;
  compensationScheduleMode?: CompensationScheduleMode;
  compensationTime?: string;
  compensationWindowStart?: string | null;
  compensationWindowEnd?: string | null;
  compensationMissedWindowPolicy?: CompensationMissedWindowPolicy;
  fullCompensationEnabled?: boolean;
  fullCompensationTime?: string;
  syncThreadMode: SyncThreadMode;
  syncThreadValue: number;
  maxSyncThreads?: number | null;
  lastFullSyncAt?: string | null;
  lastIncrementalSyncAt?: string | null;
}

export interface GitlabSourceHealthResponse {
  configId: number;
  name: string;
  sourceInstance: string;
  enabled: boolean;
  healthStatus?: 'OK' | 'DEGRADED' | 'BLOCKED' | 'DISABLED' | string;
  healthMessage?: string | null;
  currentStatus: GitlabSyncStatus | 'IDLE';
  currentMessage?: string | null;
  currentStartedAt?: string | null;
  latestLogStatus?: GitlabSyncStatus | null;
  latestLogMessage?: string | null;
  latestLogFinishedAt?: string | null;
  registeredMirrorTables: number;
  existingMirrorTables: number;
  factLayerLagging: boolean;
  factLayerMessage?: string | null;
  latestFactUpdatedAt?: string | null;
  mergeRequestFactLagging: boolean;
  issueFactLagging: boolean;
  integrationTestFactLagging: boolean;
  mergeRequestFactCount: number;
  issueFactCount: number;
  integrationTestFactCount: number;
  missingRequiredMirrorTables: string[];
}

export interface TableWhitelistOption {
  tableName: string;
  label: string;
  primaryKey: string;
  updatedAtColumn?: string | null;
  recommended: boolean;
}

export interface SyncRunLog {
  id: number;
  runId?: string | null;
  syncType: GitlabSyncType;
  runType?: string | null;
  runStatus?: string | null;
  triggerType?: string | null;
  requestReason?: string | null;
  sourcePageKey?: string | null;
  triggerSurface?: string | null;
  sourceTables?: string[];
  primaryTableName?: string | null;
  parentRunId?: number | string | null;
  parentRunRunId?: string | null;
  fullBuild?: boolean | null;
  status: GitlabSyncStatus;
  message: string;
  tableCount: number;
  completedTableCount?: number | null;
  recordCount: number;
  queuedAt?: string | null;
  startedAt: string;
  finishedAt?: string | null;
  errorSummary?: string | null;
}

export interface SyncProgress {
  phase: string;
  runId?: string | null;
  runType?: string | null;
  status?: GitlabSyncStatus | string | null;
  queuedRunsAhead?: number | null;
  totalTables: number;
  runningTables?: number | null;
  completedTables: number;
  failedTables?: number | null;
  dirtyTables?: number | null;
  syncedRecords: number;
  scannedRows?: number | null;
  appliedRows?: number | null;
  recordsPerSecond?: number | null;
  estimatedRemainingSeconds?: number | null;
  factRefreshStatus?: string | null;
  activeTableTasks?: string[];
  currentTable?: string | null;
  startedAt?: string | null;
}

export interface SyncRunSummary {
  id: number;
  runId: string;
  taskType: GitlabSyncType;
  triggerType: string;
  sourceMode: SourceMode;
  scopeKey: string;
  dedupeKey: string;
  parentRunId?: number | null;
  status: GitlabSyncStatus;
  cancelRequested: boolean;
  pendingResync: boolean;
  retryCount: number;
  cooldownUntil?: string | null;
  heartbeatAt?: string | null;
  queuedAt?: string | null;
  runAfter?: string | null;
  startedAt?: string | null;
  finishedAt?: string | null;
  finishedReason?: string | null;
  lockOwner?: string | null;
  payloadJson?: string | null;
}

export type SyncSubmissionAction =
  | 'CREATED'
  | 'QUEUED'
  | 'REUSED_ACTIVE'
  | 'REUSED_QUEUED'
  | 'DEDUPED';

export interface SyncSubmissionResponse {
  accepted: boolean;
  runId?: number | string | null;
  status: string;
  action: SyncSubmissionAction;
  message: string;
}

export type MirrorPurgeScope = 'MIRROR_DATA_ONLY' | 'MIRROR_DATA_EXCLUDING_CURRENT_WHITELIST';

export interface MirrorPurgeResult {
  scope: MirrorPurgeScope;
  droppedMirrorTables: number;
  droppedTableNames: string[];
  truncatedTables: number;
  truncatedTableNames: string[];
  syncTimestampsReset: boolean;
}

export interface MirrorStatusResponse {
  config: GitlabSyncConfig;
  currentTask?: SyncRunSummary | null;
  currentStatus: GitlabSyncStatus | 'IDLE';
  currentMessage: string;
  currentStartedAt?: string | null;
  progress?: SyncProgress | null;
  logs: SyncRunLog[];
  systemHookUrl?: string;
  systemHookRegistration?: GitlabSystemHookRegistrationStatus | null;
  availableProcessors?: number | null;
  resolvedSyncThreads?: number | null;
}

export interface GitlabRegisteredSystemHook {
  id: number;
  url: string;
  issuesEvents: boolean;
  mergeRequestsEvents: boolean;
  noteEvents: boolean;
  pipelineEvents: boolean;
  jobEvents: boolean;
  releasesEvents: boolean;
  enableSslVerification: boolean;
}

export interface GitlabSystemHookRegistrationStatus {
  supported: boolean;
  configured: boolean;
  registered: boolean;
  projectId?: number | null;
  systemHookUrl?: string;
  message: string;
  hooks: GitlabRegisteredSystemHook[];
}

export interface GitlabSourceTableDiagnosticsResponse {
  tableName: string;
  primaryKey?: string | null;
  updatedAtColumn?: string | null;
  rowStrategy: string;
  schemaFingerprint: string;
  recommended: boolean;
}

export interface GitlabSyncDiagnosticsResponse {
  configId?: number | null;
  sourceInstance: string;
  sourceMode: SourceMode;
  connectionOk: boolean;
  connectionMessage: string;
  whitelistOk: boolean;
  whitelistMessage: string;
  whitelistOptionCount: number;
  metadataOk: boolean;
  metadataMessage: string;
  sourceTableCount: number;
  primaryKeyTableCount: number;
  missingPrimaryKeyTableCount: number;
  missingUpdatedAtTableCount: number;
  sourceTables: GitlabSourceTableDiagnosticsResponse[];
  systemHookReceiverUrl?: string;
  systemHookEnabled?: boolean;
  systemHookSecretConfigured?: boolean;
  systemHookSecretUnique?: boolean;
  systemHookConfigMessage?: string;
  systemHookAutoRegistrationSupported?: boolean;
  systemHookAutoRegistered?: boolean;
  systemHookMessage?: string;
  runtimeWarnings?: string[];
}

export interface SyncRunTableDiagnostics {
  sourceTable: string;
  mirrorTable: string;
  primaryKeyColumns: string;
  updatedAtColumn?: string | null;
  rowStrategy: GitlabTableRowStrategy;
  syncEnabled: boolean;
  dirty: boolean;
  dirtyReason?: string | null;
  blockingRunId?: string | null;
  lastVerifiedAt?: string | null;
  lastAppliedAt?: string | null;
  lastSuccessAt?: string | null;
  lastFullVerifiedAt?: string | null;
  lastWatermarkAt?: string | null;
  lastCursorPk?: string | null;
  sourceRows?: number | null;
  mirrorRows?: number | null;
  sourceRowCount?: number | null;
  mirrorRowCount?: number | null;
  driftSummary?: string | null;
  schemaFingerprint?: string | null;
  lastError?: string | null;
  retryCount?: number | null;
  latestTaskType?: SyncRunTablePhase | null;
  latestTaskStatus?: GitlabSyncStatus | null;
  latestTaskRunAfter?: string | null;
  latestTaskHeartbeatAt?: string | null;
  latestTaskLeaseUntil?: string | null;
  latestTaskRowsScanned?: number | null;
  latestTaskRowsApplied?: number | null;
  latestTaskError?: string | null;
}

export interface SyncRunDiagnosticsResponse {
  configId?: number | null;
  sourceInstance: string;
  generatedAt: string;
  status?: string;
  message?: string;
  tableCount: number;
  dirtyTableCount: number;
  pendingTaskCount: number;
  runningTaskCount: number;
  retryingTaskCount: number;
  failedTaskCount: number;
  timedOutTaskCount: number;
  tables: SyncRunTableDiagnostics[];
}

export type MirrorRunMonitorOperatorAction = 'refresh' | 'cancel' | 'retry' | 'open_table_tasks';
