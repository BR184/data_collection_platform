import type { CodeReviewRuleConfig } from './code-review-rule-config';

export * from './integration-test';

export type WhitelistMode = 'RECOMMENDED' | 'ALL' | 'CUSTOM';
export type SourceMode = 'DIRECT' | 'DOCKER';
export type GitlabSyncType = 'FULL' | 'INCREMENTAL' | 'COMPENSATION' | 'WEBHOOK' | 'PURGE';
export type GitlabSyncStatus =
  | 'PENDING'
  | 'QUEUED'
  | 'RUNNING'
  | 'SUCCESS'
  | 'FAILED'
  | 'CANCELLED'
  | 'TIMEOUT'
  | 'CANCELLING';

export interface GitlabSyncConfig {
  id?: number;
  name: string;
  enabled: boolean;
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
  webhookSecret?: string;
  webhookProjectId?: number | null;
  compensationIntervalMinutes: number;
  lastFullSyncAt?: string | null;
  lastIncrementalSyncAt?: string | null;
}

export interface TableWhitelistOption {
  tableName: string;
  label: string;
  primaryKey: string;
  updatedAtColumn?: string | null;
  recommended: boolean;
}

export interface GitlabSyncLog {
  id: number;
  syncType: GitlabSyncType;
  status: GitlabSyncStatus;
  message: string;
  tableCount: number;
  recordCount: number;
  startedAt: string;
  finishedAt?: string | null;
}

export interface SyncProgress {
  phase: string;
  totalTables: number;
  completedTables: number;
  syncedRecords: number;
  currentTable?: string | null;
  startedAt?: string | null;
}

export interface GitlabSyncTask {
  id: number;
  runId: string;
  taskType: GitlabSyncType;
  triggerType: string;
  sourceMode: SourceMode;
  scopeKey: string;
  dedupeKey: string;
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
  taskId: number;
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
  currentTask?: GitlabSyncTask | null;
  currentStatus: GitlabSyncStatus | 'IDLE';
  currentMessage: string;
  currentStartedAt?: string | null;
  progress?: SyncProgress | null;
  logs: GitlabSyncLog[];
  webhookUrl: string;
  webhookRegistration?: GitlabWebhookRegistrationStatus | null;
}

export interface GitlabRegisteredWebhook {
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

export interface GitlabWebhookRegistrationStatus {
  supported: boolean;
  configured: boolean;
  registered: boolean;
  projectId?: number | null;
  webhookUrl: string;
  message: string;
  hooks: GitlabRegisteredWebhook[];
}

export interface StatisticFilterOption {
  label: string;
  value: string;
}

export type StatisticFilterFieldType = 'text' | 'select' | 'number' | 'datetime';
export type StatisticFilterOperator =
  | 'eq'
  | 'ne'
  | 'contains'
  | 'notContains'
  | 'isEmpty'
  | 'isNotEmpty'
  | 'gt'
  | 'gte'
  | 'lt'
  | 'lte'
  | 'between'
  | 'year'
  | 'month'
  | 'day'
  | 'at'
  | 'before'
  | 'after';

export interface StatisticFilterField {
  key: string;
  label: string;
  type: StatisticFilterFieldType;
  placeholder?: string | null;
  defaultValue?: string | null;
  width?: number | null;
  operators: StatisticFilterOperator[];
  options: StatisticFilterOption[];
}

export interface StatisticFilterCondition {
  fieldKey: string;
  operator: StatisticFilterOperator;
  value: string;
  secondaryValue?: string | null;
}

export interface StatisticFilterGroup {
  logic: 'AND' | 'OR';
  conditions: StatisticFilterCondition[];
}

export interface StatisticColumnLeaf {
  key: string;
  label: string;
  drilldown: boolean;
  metricType: string;
}

export interface StatisticColumnGroup {
  key: string;
  label: string;
  children?: StatisticColumnGroup[];
  columns?: StatisticColumnLeaf[];
}

export function flattenStatisticColumnLeaves(groups: StatisticColumnGroup[]): StatisticColumnLeaf[] {
  return groups.flatMap((group) => flattenStatisticColumnLeavesFromGroup(group));
}

export function flattenStatisticColumnLeavesFromGroup(group: StatisticColumnGroup): StatisticColumnLeaf[] {
  return [
    ...(group.columns ?? []),
    ...((group.children ?? []).flatMap((child) => flattenStatisticColumnLeavesFromGroup(child))),
  ];
}

export interface StatisticDetailColumn {
  key: string;
  label: string;
  width?: number | null;
  minWidth?: number | null;
  sortable: boolean;
}

export interface StatisticBoardDefinition {
  boardKey: string;
  title: string;
  description: string;
  queryTitle: string;
  queryDescription: string;
  rowHeaderLabel: string;
  filters: StatisticFilterField[];
  columnGroups: StatisticColumnGroup[];
  detailColumns: StatisticDetailColumn[];
  defaultPageSize?: number | null;
  emptyText?: string | null;
}

export interface StatisticBoardMeta {
  generatedAt: string;
  queryDurationMs: number;
  rowCount: number;
  columnCount: number;
  drilldownColumnCount: number;
}

export interface StatisticRuleFlowStepSample {
  label: string;
  detail: string;
}

export interface StatisticRuleFlowStep {
  key: string;
  title: string;
  description: string;
  inputCount: number;
  outputCount: number;
  samples: StatisticRuleFlowStepSample[];
}

export interface StatisticRuleMetricDefinition {
  key: string;
  label: string;
  definition: string;
  formula: string;
  note?: string | null;
}

export interface StatisticBoardRuleExplanationResponse {
  boardKey: string;
  supported: boolean;
  title?: string | null;
  version?: string | null;
  scopeDescription?: string | null;
  summary?: string | null;
  flowSteps: StatisticRuleFlowStep[];
  metricDefinitions: StatisticRuleMetricDefinition[];
  unsupportedReason?: string | null;
}

export interface StatisticCellData {
  columnKey: string;
  numericValue: number;
  displayValue: string;
  drilldown: boolean;
  detailViewKey?: string | null;
  detailParams: Record<string, string>;
}

export interface StatisticRowData {
  rowKey: string;
  rowLabel: string;
  cells: StatisticCellData[];
}

export interface StatisticBoardResponse {
  definition: StatisticBoardDefinition;
  appliedFilters: Record<string, string>;
  appliedFilterGroup?: StatisticFilterGroup | null;
  rows: StatisticRowData[];
  meta: StatisticBoardMeta;
}

export interface StatisticDetailResponse {
  title: string;
  description: string;
  columns: StatisticDetailColumn[];
  records: Record<string, unknown>[];
  total: number;
  page: number;
  size: number;
  sortField?: string | null;
  sortOrder?: string | null;
}

export interface DatabaseTableOption {
  tableName: string;
  label: string;
  syncStatus: string;
  lastSyncTime?: string | null;
}

export interface DatabaseTableColumn {
  key: string;
  label: string;
  sortable: boolean;
}

export interface DatabaseTableRowsResponse {
  tableName: string;
  label: string;
  columns: DatabaseTableColumn[];
  rows: Record<string, unknown>[];
  total: number;
  page: number;
  size: number;
  sortField?: string | null;
  sortOrder?: string | null;
  keyword?: string | null;
  syncStatus: string;
  lastSyncTime?: string | null;
  statusMessage?: string | null;
}

export interface TestingPhaseDefinitionResponse {
  id: number;
  projectId: number;
  projectName: string;
  testingPhase: string;
  phaseStartAt: string;
  phaseEndAt?: string | null;
  enabled: boolean;
  remark: string;
  issueCount: number;
  createdAt?: string | null;
  updatedAt?: string | null;
}

export interface TestingPhaseDefinitionSaveRequest {
  projectId: number;
  testingPhase: string;
  phaseStartAt: string;
  phaseEndAt?: string | null;
  enabled: boolean;
  remark?: string | null;
}

export interface TestingPhaseProjectOptionResponse {
  projectId: number;
  projectName: string;
}

export interface OptionItemResponse {
  label: string;
  value: string;
}

export interface CodeReviewIllegalRecordRowResponse {
  requestType: string;
  mergeRequestId: number;
  mergeRequestIid: number;
  projectId: number;
  mergeRequestContent: string;
  mergeRequestLink?: string | null;
  owner: string;
  projectName: string;
  repositoryName: string;
  mergedAt?: string | null;
  mergedBy: string;
  moduleName: string;
  targetBranch: string;
  illegalTypes: string[];
  commentRate?: number | null;
  defectCount?: number | null;
  addedLines?: number | null;
}

export interface CodeReviewIllegalRecordListResponse {
  records: CodeReviewIllegalRecordRowResponse[];
  total: number;
  page: number;
  size: number;
  sortField: string;
  sortOrder: 'asc' | 'desc';
}

export interface CodeReviewIllegalRecordFilterOptionsResponse {
  requestTypes: OptionItemResponse[];
  repositoryNames: OptionItemResponse[];
  illegalTypes: OptionItemResponse[];
  targetBranches: OptionItemResponse[];
  mergedBys: OptionItemResponse[];
  moduleNames: OptionItemResponse[];
  projectNames: OptionItemResponse[];
}

export interface CodeReviewMultiBoardBreakdownRowResponse {
  rowKey: string;
  rowLabel: string;
  mergeRequestCount: number;
  completedCount: number;
  averageCommentRate?: number | null;
  totalDefectCount: number;
  totalAddedLines: number;
  defectDensityPerKloc?: number | null;
  averageReviewDurationMinutes?: number | null;
  averageAddedLines?: number | null;
}

export interface CodeReviewMultiBoardOverviewResponse {
  source: string;
  sourceLabel: string;
  mergeRequestCount: number;
  completedCount: number;
  pendingCount: number;
  averageCommentRate?: number | null;
  totalDefectCount: number;
  totalAddedLines: number;
  defectDensityPerKloc?: number | null;
  averageReviewDurationMinutes?: number | null;
  averageAddedLines?: number | null;
  moduleRows: CodeReviewMultiBoardBreakdownRowResponse[];
  ownerRows: CodeReviewMultiBoardBreakdownRowResponse[];
}

export interface CodeReviewRulePreviewRequest {
  projectId?: number | null;
  repositoryName?: string;
  mergedAtStart?: string;
  mergedAtEnd?: string;
  keyword?: string;
  projectName?: string;
  requestType?: string;
  targetBranch?: string;
  mergedBy?: string;
  moduleName?: string;
  illegalType?: string;
  mergeRequestIid?: string;
  owner?: string;
  ruleConfig: CodeReviewRuleConfig;
}

export interface ReviewDataSummaryResponse {
  totalRecords: number;
  totalProblemItems: number;
  averageReviewScalePages: number;
  averageProblemCount: number;
}

export interface ReviewDataRecordRowResponse {
  id: number;
  projectName: string;
  title: string;
  moduleName: string;
  reviewType: string;
  reviewDate?: string | null;
  reviewOwner: string;
  reviewExpertsSummary: string;
  reviewScalePages: number;
  reviewProduct: string;
  authorName: string;
  reviewVersion: string;
  problemCount: number;
  problemDensity: number;
  updatedAt?: string | null;
  deleted: boolean;
}

export interface ReviewDataRecordListResponse {
  records: ReviewDataRecordRowResponse[];
  total: number;
  page: number;
  size: number;
  sortField: string;
  sortOrder: 'asc' | 'desc';
  summary: ReviewDataSummaryResponse;
}

export interface ReviewDataFilterOptionsResponse {
  projectNames: OptionItemResponse[];
  moduleNames: OptionItemResponse[];
  reviewOwners: OptionItemResponse[];
  reviewTypes: OptionItemResponse[];
  reviewExperts: OptionItemResponse[];
  problemStatuses: OptionItemResponse[];
  reviewCategories: OptionItemResponse[];
  problemCategories: OptionItemResponse[];
}

export interface ReviewDataProblemItemResponse {
  id: number;
  reviewRecordId: number;
  reviewerName: string;
  workloadHours: number;
  reviewCategory: string;
  documentPosition: string;
  problemCategory: string;
  problemDescription: string;
  suggestedSolution: string;
  ownerName: string;
  rejectionReason: string;
  problemStatus: string;
  updatedAt?: string | null;
}

export interface ReviewDataRecordDetailResponse {
  record: ReviewDataRecordRowResponse;
  reviewExperts: string[];
  problemItems: ReviewDataProblemItemResponse[];
}

export interface ReviewDataRecordSaveRequest {
  projectName: string;
  title: string;
  moduleName: string;
  reviewType: string;
  reviewDate: string;
  reviewOwner: string;
  reviewExperts: string[];
  reviewScalePages: number;
  reviewProduct: string;
  authorName: string;
  reviewVersion: string;
}

export interface ReviewDataProblemItemSaveRequest {
  reviewerName: string;
  workloadHours: number;
  reviewCategory: string;
  documentPosition: string;
  problemCategory: string;
  problemDescription: string;
  suggestedSolution: string;
  ownerName: string;
  rejectionReason: string;
  problemStatus: string;
}

export interface CollectFormDetailResponse {
  id: number;
  gitlabBaseUrl: string;
  projectId: number;
  requestIid?: number | null;
  resourceType: string;
  resourceId: string;
  templateCode: string;
  formTitle: string;
  reviewer: string;
  reviewDurationMinutes: number;
  specificationScore: number;
  logicScore: number;
  performanceScore: number;
  designScore: number;
  otherScore: number;
  remark?: string | null;
  deleted: boolean;
  createdAt?: string | null;
  updatedAt?: string | null;
}

export interface CollectFormNotificationPayloadResponse {
  sourceAddress: string;
  projectId: number;
  requestIid: number;
  resourceType: string;
}

export interface RealtimeWorkspaceStatusResponse {
  workspaceKey: string;
  supported: boolean;
  status: string;
  message: string;
  refreshing: boolean;
  lastSyncedAt?: string | null;
  lastRefreshStartedAt?: string | null;
  lastRefreshFinishedAt?: string | null;
}

export interface SystemTestIssueSearchRowResponse {
  issueId: number;
  issueIid: number;
  issueLink?: string | null;
  projectId: number;
  projectName: string;
  title: string;
  issueState: string;
  testingPhase: string;
  severityLevel: string;
  bugStatus: string;
  category: string;
  milestoneTitle: string;
  authorName: string;
  assigneeName: string;
  moduleNames: string;
  createdAt?: string | null;
  updatedAt?: string | null;
  closedAt?: string | null;
  labels: string[];
}

export interface SystemTestIssueSearchListResponse {
  records: SystemTestIssueSearchRowResponse[];
  total: number;
  page: number;
  size: number;
  sortField: string;
  sortOrder: 'asc' | 'desc';
}

export interface SystemTestIssueSearchFilterOptionsResponse {
  projectNames: OptionItemResponse[];
  moduleNames: OptionItemResponse[];
  testingPhases: OptionItemResponse[];
  authorNames: OptionItemResponse[];
  assigneeNames: OptionItemResponse[];
  issueStates: OptionItemResponse[];
  severityLevels: OptionItemResponse[];
  bugStatuses: OptionItemResponse[];
  categories: OptionItemResponse[];
  milestoneTitles: OptionItemResponse[];
}

export interface SystemTestIllegalRecordRowResponse {
  issueId: number;
  issueIid: number;
  issueLink?: string | null;
  projectId: number;
  projectName: string;
  title: string;
  issueState: string;
  testingPhase: string;
  illegalReason: string;
  severityLevel: string;
  bugStatus: string;
  category: string;
  milestoneTitle: string;
  authorName: string;
  assigneeName: string;
  moduleNames: string;
  createdAt?: string | null;
  updatedAt?: string | null;
  closedAt?: string | null;
  labels: string[];
}

export interface SystemTestIllegalRecordListResponse {
  records: SystemTestIllegalRecordRowResponse[];
  total: number;
  page: number;
  size: number;
  sortField: string;
  sortOrder: 'asc' | 'desc';
}

export interface SystemTestIllegalRecordFilterOptionsResponse {
  projectNames: OptionItemResponse[];
  moduleNames: OptionItemResponse[];
  testingPhases: OptionItemResponse[];
  illegalReasons: OptionItemResponse[];
  authorNames: OptionItemResponse[];
  assigneeNames: OptionItemResponse[];
  issueStates: OptionItemResponse[];
  severityLevels: OptionItemResponse[];
  bugStatuses: OptionItemResponse[];
  categories: OptionItemResponse[];
  milestoneTitles: OptionItemResponse[];
}

export interface CustomerIssueIllegalRecordRowResponse {
  issueId: number;
  issueIid: number;
  issueLink?: string | null;
  projectId: number;
  projectName: string;
  title: string;
  issueState: string;
  illegalReason: string;
  severityLevel: string;
  priorityLevel: string;
  bugStatus: string;
  category: string;
  milestoneTitle: string;
  authorName: string;
  assigneeName: string;
  moduleNames: string;
  createdAt?: string | null;
  updatedAt?: string | null;
  closedAt?: string | null;
  labels: string[];
}

export interface CustomerIssueIllegalRecordListResponse {
  records: CustomerIssueIllegalRecordRowResponse[];
  total: number;
  page: number;
  size: number;
  sortField: string;
  sortOrder: 'asc' | 'desc';
}

export interface CustomerIssueIllegalRecordFilterOptionsResponse {
  projectNames: OptionItemResponse[];
  moduleNames: OptionItemResponse[];
  illegalReasons: OptionItemResponse[];
  severityLevels: OptionItemResponse[];
  priorityLevels: OptionItemResponse[];
  issueStates: OptionItemResponse[];
  bugStatuses: OptionItemResponse[];
  categories: OptionItemResponse[];
  milestoneTitles: OptionItemResponse[];
}

export type CustomerIssueRecordTopic = 'cc-product' | 'delay';

export interface CustomerIssueRecordRowResponse {
  issueId: number;
  issueIid: number;
  issueLink?: string | null;
  projectId: number;
  projectName: string;
  title: string;
  issueState: string;
  severityLevel: string;
  priorityLevel: string;
  bugStatus: string;
  category: string;
  reasonCategory: string;
  milestoneTitle: string;
  authorName: string;
  assigneeName: string;
  moduleNames: string;
  delayIssue: boolean;
  delayReason: string;
  delayCause: string;
  responseDelayed: boolean;
  resolveDelayed: boolean;
  illegal: boolean;
  illegalReason: string;
  createdAt?: string | null;
  updatedAt?: string | null;
  closedAt?: string | null;
  labels: string[];
}

export interface CustomerIssueRecordListResponse {
  records: CustomerIssueRecordRowResponse[];
  total: number;
  page: number;
  size: number;
  sortField: string;
  sortOrder: 'asc' | 'desc';
}

export interface CustomerIssueRecordFilterOptionsResponse {
  projectNames: OptionItemResponse[];
  moduleNames: OptionItemResponse[];
  reasonCategories: OptionItemResponse[];
  severityLevels: OptionItemResponse[];
  priorityLevels: OptionItemResponse[];
  issueStates: OptionItemResponse[];
  bugStatuses: OptionItemResponse[];
  categories: OptionItemResponse[];
  milestoneTitles: OptionItemResponse[];
}
