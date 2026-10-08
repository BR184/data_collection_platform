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
export type SyncRunTableTaskStage = 'SCAN' | 'RECONCILE';
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
  webBaseUrl?: string | null;
  apiToken?: string;
  delayLabelWritebackEnabled?: boolean;
  matchModeEnabled?: boolean;
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
  mergeRequestFactCount: number;
  issueFactCount: number;
  missingRequiredMirrorTables: string[];
}

export interface TableWhitelistOption {
  tableName: string;
  label: string;
  primaryKey: string;
  updatedAtColumn?: string | null;
  recommended: boolean;
}

export type SyncFreshnessStatus =
  | 'NOT_APPLICABLE'
  | 'VERIFYING'
  | 'CAUGHT_UP'
  | 'NOT_CAUGHT_UP';

export type DeleteReconciliationStatus =
  | 'NOT_APPLICABLE'
  | 'RUNNING'
  | 'COMPLETED'
  | 'INCOMPLETE';

/** 故障定位项的四类来源；同表同关系的不同范围由 `scopeSignature`/`scopeKey` 区分。 */
export type SyncRunDiagnosticKind = 'TABLE_TASK' | 'AUTHORITATIVE_SCOPE' | 'FACT_BUILD' | 'PROJECTION';

/** 运行明细的可分页区段：定位项与相关事件。 */
export type SyncRunLogDetailSection = 'DIAGNOSTICS' | 'EVENTS';

/**
 * 单个可定位的故障/待处理项。
 *
 * <p>字段按 kind 取用，未取得的项服务端保持空值，界面不得用相关状态推断补齐。
 * `rawError` 是任务自身的错误原文，优先于运行级概括与事件文案展示。
 */
export interface SyncRunDiagnosticItem {
  kind: SyncRunDiagnosticKind | string;
  kindRank?: number | null;
  taskId?: number | string | null;
  status?: string | null;
  manualDisposition?: string | null;
  sourceInstance?: string | null;
  originalRunId?: number | string | null;
  currentRunId?: number | string | null;
  expectedRunId?: number | string | null;
  newRunId?: number | string | null;
  retryCount?: number | null;
  maxRetryCount?: number | null;
  rawError?: string | null;
  dispositionReason?: string | null;
  startedAt?: string | null;
  finishedAt?: string | null;
  errorObservedAt?: string | null;
  elapsedMs?: number | null;
  scope?: string | null;
  scopeType?: string | null;
  scopeKey?: string | null;
  targetGeneration?: number | string | null;
  factType?: string | null;
  fullBuild?: boolean | null;
  heartbeatAt?: string | null;
  leaseUntil?: string | null;
  recordUpdatedAt?: string | null;
  runKey?: string | null;
  message?: string | null;
  details?: unknown;
}

/** 与一次运行相关的生命周期/失败/人工处置事件（不含高频进度事件）。 */
export interface SyncRunEventTrailItem {
  eventId?: number | string | null;
  eventType?: string | null;
  message?: string | null;
  createdAt?: string | null;
}

/** 运行明细的分页区块；`total` 是完整总数，`items` 只含当前页。 */
export interface SyncRunDetailBlock<TItem> {
  runId?: number | null;
  section?: SyncRunLogDetailSection | string;
  offset: number;
  limit: number;
  total: number;
  items: TItem[];
  hasMore: boolean;
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
  freshnessStatus: SyncFreshnessStatus;
  deleteReconciliationStatus: DeleteReconciliationStatus;
  message: string;
  tableCount: number;
  completedTableCount?: number | null;
  recordCount: number;
  queuedAt?: string | null;
  startedAt: string;
  finishedAt?: string | null;
  errorSummary?: string | null;
  /** 可查明细的异常总数（去重后）。 */
  diagnosticCount?: number | null;
  /** 失败项计数；与人工待处理可重叠，不相加当作任务总数。 */
  failureCount?: number | null;
  /** 需人工处置的项计数；与失败项可重叠。 */
  manualAttentionCount?: number | null;
  /** 摘要区展示的定位项（每类最多 5 条）。 */
  diagnostics?: SyncRunDiagnosticItem[] | null;
  /** 相关事件总数（不含高频进度事件）。 */
  eventCount?: number | null;
  /** 最近 5 条相关事件，正序展示。 */
  eventTrail?: SyncRunEventTrailItem[] | null;
  latestProgressMessage?: string | null;
  latestProgressAt?: string | null;
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
  /** 仅在显式传入 `detailsRunId` 时返回：指定运行的分页定位项或相关事件。 */
  details?: SyncRunDetailBlock<SyncRunDiagnosticItem | SyncRunEventTrailItem> | null;
  /** 仅在显式传入 `pendingOffset`/`pendingLimit` 时返回：按配置与来源分页的人工待处理列表。 */
  pending?: SyncRunDetailBlock<SyncRunDiagnosticItem> | null;
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
  currentTaskId?: number | null;
  currentTaskType?: SyncRunTablePhase | null;
  currentTaskStage?: SyncRunTableTaskStage | null;
  currentTaskStatus?: GitlabSyncStatus | null;
  currentTaskRunAfter?: string | null;
  currentTaskHeartbeatAt?: string | null;
  currentTaskLeaseUntil?: string | null;
  currentTaskRetryCount?: number | null;
  currentTaskCursorUpdatedAt?: string | null;
  currentTaskCursorPk?: string | null;
  currentTaskRowsScanned?: number | null;
  currentTaskRowsApplied?: number | null;
  currentTaskError?: string | null;
}

export interface DirectConnectionPoolMetrics {
  maximumConnections: number;
  totalConnections: number;
  activeConnections: number;
  idleConnections: number;
  waitingThreads: number;
}

export interface SyncRunDiagnosticsResponse {
  configId?: number | null;
  sourceInstance: string;
  generatedAt: string;
  status?: string;
  message?: string;
  currentRunDbId?: number | null;
  currentRunId?: string | null;
  resolvedWorkerCount?: number | null;
  directPoolMetrics?: DirectConnectionPoolMetrics | null;
  tableCount: number;
  dirtyTableCount: number;
  pendingTaskCount: number;
  runningTaskCount: number;
  retryingTaskCount: number;
  failedTaskCount: number;
  timedOutTaskCount: number;
  historicalFailedTaskCount: number;
  historicalTimedOutTaskCount: number;
  tables: SyncRunTableDiagnostics[];
}

export type MirrorRunMonitorOperatorAction = 'refresh' | 'cancel' | 'retry' | 'open_table_tasks';
