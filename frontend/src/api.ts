import type { CodeReviewRuleConfig, CodeReviewRulePreviewResponse } from './types/code-review-rule-config';

export type WhitelistMode = 'RECOMMENDED' | 'ALL' | 'CUSTOM';
export type SourceMode = 'DIRECT' | 'DOCKER';

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
  syncType: string;
  status: string;
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
  taskType: string;
  triggerType: string;
  sourceMode: SourceMode;
  scopeKey: string;
  dedupeKey: string;
  status: string;
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
  currentStatus: string;
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
  return [...(group.columns ?? []), ...((group.children ?? []).flatMap((child) => flattenStatisticColumnLeavesFromGroup(child)))];
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

export interface CustomerIssueIllegalRecordRowResponse {
  issueId: number;
  issueIid: number;
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


async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  });
  const rawText = await response.text();
  let payload: any = null;
  try {
    payload = rawText ? JSON.parse(rawText) : null;
  } catch {
    payload = null;
  }

  if (!response.ok) {
    throw new Error(payload?.message || rawText || `Request failed: ${response.status}`);
  }

  if (payload && typeof payload === 'object' && 'success' in payload) {
    if (!payload.success) {
      throw new Error(payload.message || 'Request failed');
    }
    return payload.data as T;
  }

  return payload as T;
}

export const api = {
  getStatus() {
    return request<MirrorStatusResponse>('/api/gitlab-sync/status');
  },
  getWebhookRegistrationStatus() {
    return request<GitlabWebhookRegistrationStatus>('/api/gitlab-sync/webhook-registration-status');
  },
  getWhitelistOptions() {
    return request<TableWhitelistOption[]>('/api/gitlab-sync/whitelist-options');
  },
  saveConfig(config: GitlabSyncConfig) {
    return request<GitlabSyncConfig>('/api/gitlab-sync/config', {
      method: 'PUT',
      body: JSON.stringify(config),
    });
  },
  testConnection() {
    return request<{ success: boolean; message: string }>('/api/gitlab-sync/test-connection', {
      method: 'POST',
    });
  },
  startFullSync() {
    return request<SyncSubmissionResponse>('/api/gitlab-sync/full-sync', {
      method: 'POST',
    });
  },
  startIncrementalSync() {
    return request<SyncSubmissionResponse>('/api/gitlab-sync/incremental-sync', {
      method: 'POST',
    });
  },
  registerWebhook() {
    return request<GitlabWebhookRegistrationStatus>('/api/gitlab-sync/register-webhook', {
      method: 'POST',
    });
  },
  cancelSync() {
    return request<{ accepted: boolean; taskId?: number; status?: string }>('/api/gitlab-sync/cancel', {
      method: 'POST',
    });
  },
  purgeMirrorData(scope: MirrorPurgeScope) {
    return request<MirrorPurgeResult>('/api/gitlab-sync/purge', {
      method: 'POST',
      body: JSON.stringify({ scope }),
    });
  },
  getStatisticBoard(boardKey: string, params?: { filters?: Record<string, string>; filterGroup?: StatisticFilterGroup | null }) {
    const searchParams = new URLSearchParams(params?.filters ?? {});
    if (params?.filterGroup && params.filterGroup.conditions.length) {
      searchParams.set('filterGroup', JSON.stringify(params.filterGroup));
    }
    const queryString = searchParams.toString();
    return request<StatisticBoardResponse>(
      `/api/statistic-boards/${boardKey}${queryString ? `?${queryString}` : ''}`,
    );
  },
  getStatisticBoardDetails(
    boardKey: string,
    params: {
      rowKey: string;
      columnKey: string;
      page?: number;
      size?: number;
      sortField?: string;
      sortOrder?: string;
      filters?: Record<string, string>;
      filterGroup?: StatisticFilterGroup | null;
    },
  ) {
    const query = new URLSearchParams({
      rowKey: params.rowKey,
      columnKey: params.columnKey,
      page: String(params.page ?? 1),
      size: String(params.size ?? 10),
      ...(params.sortField ? { sortField: params.sortField } : {}),
      ...(params.sortOrder ? { sortOrder: params.sortOrder } : {}),
      ...(params.filters ?? {}),
    });
    if (params.filterGroup && params.filterGroup.conditions.length) {
      query.set('filterGroup', JSON.stringify(params.filterGroup));
    }
    return request<StatisticDetailResponse>(`/api/statistic-boards/${boardKey}/details?${query.toString()}`);
  },
  getStatisticBoardRuleExplanation(
    boardKey: string,
    params?: { filters?: Record<string, string>; filterGroup?: StatisticFilterGroup | null },
  ) {
    const searchParams = new URLSearchParams(params?.filters ?? {});
    if (params?.filterGroup && params.filterGroup.conditions.length) {
      searchParams.set('filterGroup', JSON.stringify(params.filterGroup));
    }
    const queryString = searchParams.toString();
    return request<StatisticBoardRuleExplanationResponse>(
      `/api/statistic-boards/${boardKey}/rule-explanation${queryString ? `?${queryString}` : ''}`,
    );
  },
  async exportStatisticBoard(boardKey: string, params?: { filters?: Record<string, string>; filterGroup?: StatisticFilterGroup | null }) {
    const searchParams = new URLSearchParams(params?.filters ?? {});
    if (params?.filterGroup && params.filterGroup.conditions.length) {
      searchParams.set('filterGroup', JSON.stringify(params.filterGroup));
    }
    const queryString = searchParams.toString();
    const response = await fetch(`/api/statistic-boards/${boardKey}/export${queryString ? `?${queryString}` : ''}`);
    if (!response.ok) {
      const text = await response.text();
      throw new Error(text || `Export failed: ${response.status}`);
    }
    return response.text();
  },
  getDatabaseTables() {
    return request<DatabaseTableOption[]>('/api/database-browser/tables');
  },
    getDatabaseTableRows(params: {
    tableName: string;
    page?: number;
    size?: number;
    keyword?: string;
    sortField?: string;
    sortOrder?: 'asc' | 'desc';
    }) {
      const query = new URLSearchParams({
      tableName: params.tableName,
      page: String(params.page ?? 1),
      size: String(params.size ?? 20),
      ...(params.keyword ? { keyword: params.keyword } : {}),
      ...(params.sortField ? { sortField: params.sortField } : {}),
      ...(params.sortOrder ? { sortOrder: params.sortOrder } : {}),
    });
      return request<DatabaseTableRowsResponse>(`/api/database-browser/rows?${query.toString()}`);
    },
    getCodeReviewIllegalRecords(params: {
      projectId?: string | number | null;
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
      filterGroup?: StatisticFilterGroup | null;
      page?: number;
      size?: number;
      sortBy?: string;
      sortOrder?: 'asc' | 'desc';
      ruleConfig?: CodeReviewRuleConfig | null;
    }) {
      const query = new URLSearchParams({
        page: String(params.page ?? 1),
        size: String(params.size ?? 20),
        ...(params.projectId != null && params.projectId !== '' ? { projectId: String(params.projectId) } : {}),
        ...(params.repositoryName ? { repositoryName: params.repositoryName } : {}),
        ...(params.mergedAtStart ? { mergedAtStart: params.mergedAtStart } : {}),
        ...(params.mergedAtEnd ? { mergedAtEnd: params.mergedAtEnd } : {}),
        ...(params.keyword ? { keyword: params.keyword } : {}),
        ...(params.projectName ? { projectName: params.projectName } : {}),
        ...(params.requestType ? { requestType: params.requestType } : {}),
        ...(params.targetBranch ? { targetBranch: params.targetBranch } : {}),
        ...(params.mergedBy ? { mergedBy: params.mergedBy } : {}),
        ...(params.moduleName ? { moduleName: params.moduleName } : {}),
        ...(params.illegalType ? { illegalType: params.illegalType } : {}),
        ...(params.mergeRequestIid ? { mergeRequestIid: params.mergeRequestIid } : {}),
        ...(params.owner ? { owner: params.owner } : {}),
        ...(params.filterGroup ? { filterGroup: JSON.stringify(params.filterGroup) } : {}),
        ...(params.sortBy ? { sortBy: params.sortBy } : {}),
        ...(params.sortOrder ? { sortOrder: params.sortOrder } : {}),
        ...(params.ruleConfig ? { ruleConfig: JSON.stringify(params.ruleConfig) } : {}),
      });
      return request<CodeReviewIllegalRecordListResponse>(`/api/code-review/illegal-records?${query.toString()}`);
    },
    getCodeReviewIllegalRecordFilterOptions(projectId?: string | number | null) {
      const query = new URLSearchParams(
        projectId != null && projectId !== '' ? { projectId: String(projectId) } : {},
      );
      return request<CodeReviewIllegalRecordFilterOptionsResponse>(
        `/api/code-review/illegal-records/filter-options${query.toString() ? `?${query.toString()}` : ''}`,
      );
    },
    getCodeReviewIllegalRecordRuleExplanation() {
      return request<StatisticBoardRuleExplanationResponse>('/api/code-review/illegal-records/rule-explanation');
    },
    previewCodeReviewIllegalRecordRuleConfig(payload: CodeReviewRulePreviewRequest) {
      return request<CodeReviewRulePreviewResponse>('/api/code-review/illegal-records/rule-config/preview', {
        method: 'POST',
        body: JSON.stringify(payload),
      });
    },
    getCodeReviewIllegalRecordRealtimeStatus() {
      return request<RealtimeWorkspaceStatusResponse>('/api/code-review/illegal-records/status');
    },
    refreshCodeReviewIllegalRecords() {
      return request<RealtimeWorkspaceStatusResponse>('/api/code-review/illegal-records/refresh', {
        method: 'POST',
      });
    },
    getReviewDataRecords(params: {
      keyword?: string;
      title?: string;
      projectName?: string;
      moduleName?: string;
      reviewOwner?: string;
      reviewType?: string;
      problemStatus?: string;
      reviewExpert?: string;
      filterGroup?: StatisticFilterGroup | null;
      page?: number;
      size?: number;
      sortBy?: string;
      sortOrder?: 'asc' | 'desc';
    }) {
      const query = new URLSearchParams({
        page: String(params.page ?? 1),
        size: String(params.size ?? 20),
        ...(params.keyword ? { keyword: params.keyword } : {}),
        ...(params.title ? { title: params.title } : {}),
        ...(params.projectName ? { projectName: params.projectName } : {}),
        ...(params.moduleName ? { moduleName: params.moduleName } : {}),
        ...(params.reviewOwner ? { reviewOwner: params.reviewOwner } : {}),
        ...(params.reviewType ? { reviewType: params.reviewType } : {}),
        ...(params.problemStatus ? { problemStatus: params.problemStatus } : {}),
        ...(params.reviewExpert ? { reviewExpert: params.reviewExpert } : {}),
        ...(params.filterGroup ? { filterGroup: JSON.stringify(params.filterGroup) } : {}),
        ...(params.sortBy ? { sortBy: params.sortBy } : {}),
        ...(params.sortOrder ? { sortOrder: params.sortOrder } : {}),
      });
      return request<ReviewDataRecordListResponse>(`/api/review-data/records?${query.toString()}`);
    },
    getReviewDataFilterOptions() {
      return request<ReviewDataFilterOptionsResponse>('/api/review-data/records/filter-options');
    },
    getReviewDataRecordDetail(recordId: string | number) {
      return request<ReviewDataRecordDetailResponse>(`/api/review-data/records/${recordId}`);
    },
    createReviewDataRecord(payload: ReviewDataRecordSaveRequest) {
      return request<ReviewDataRecordDetailResponse>('/api/review-data/records', {
        method: 'POST',
        body: JSON.stringify(payload),
      });
    },
    updateReviewDataRecord(recordId: string | number, payload: ReviewDataRecordSaveRequest) {
      return request<ReviewDataRecordDetailResponse>(`/api/review-data/records/${recordId}`, {
        method: 'PUT',
        body: JSON.stringify(payload),
      });
    },
    deleteReviewDataRecord(recordId: string | number) {
      return request<void>(`/api/review-data/records/${recordId}`, {
        method: 'DELETE',
      });
    },
    getReviewDataProblemItems(recordId: string | number) {
      return request<ReviewDataProblemItemResponse[]>(`/api/review-data/records/${recordId}/problem-items`);
    },
    createReviewDataProblemItem(recordId: string | number, payload: ReviewDataProblemItemSaveRequest) {
      return request<ReviewDataProblemItemResponse>(`/api/review-data/records/${recordId}/problem-items`, {
        method: 'POST',
        body: JSON.stringify(payload),
      });
    },
    updateReviewDataProblemItem(
      recordId: string | number,
      itemId: string | number,
      payload: ReviewDataProblemItemSaveRequest,
    ) {
      return request<ReviewDataProblemItemResponse>(`/api/review-data/records/${recordId}/problem-items/${itemId}`, {
        method: 'PUT',
        body: JSON.stringify(payload),
      });
    },
    deleteReviewDataProblemItem(recordId: string | number, itemId: string | number) {
      return request<void>(`/api/review-data/records/${recordId}/problem-items/${itemId}`, {
        method: 'DELETE',
      });
    },
    getSystemTestIssueSearchRecords(params: {
      projectId?: string | number | null;
      keyword?: string;
      issueIid?: string;
      title?: string;
      projectName?: string;
      moduleName?: string;
      testingPhase?: string;
      authorName?: string;
      assigneeName?: string;
      issueState?: string;
      severityLevel?: string;
      bugStatus?: string;
      category?: string;
      milestoneTitle?: string;
      createdAtStart?: string;
      createdAtEnd?: string;
      updatedAtStart?: string;
      updatedAtEnd?: string;
      page?: number;
      size?: number;
      sortBy?: string;
      sortOrder?: 'asc' | 'desc';
    }) {
      const query = new URLSearchParams({
        page: String(params.page ?? 1),
        size: String(params.size ?? 20),
        ...(params.projectId != null && params.projectId !== '' ? { projectId: String(params.projectId) } : {}),
        ...(params.keyword ? { keyword: params.keyword } : {}),
        ...(params.issueIid ? { issueIid: params.issueIid } : {}),
        ...(params.title ? { title: params.title } : {}),
        ...(params.projectName ? { projectName: params.projectName } : {}),
        ...(params.moduleName ? { moduleName: params.moduleName } : {}),
        ...(params.testingPhase ? { testingPhase: params.testingPhase } : {}),
        ...(params.authorName ? { authorName: params.authorName } : {}),
        ...(params.assigneeName ? { assigneeName: params.assigneeName } : {}),
        ...(params.issueState ? { issueState: params.issueState } : {}),
        ...(params.severityLevel ? { severityLevel: params.severityLevel } : {}),
        ...(params.bugStatus ? { bugStatus: params.bugStatus } : {}),
        ...(params.category ? { category: params.category } : {}),
        ...(params.milestoneTitle ? { milestoneTitle: params.milestoneTitle } : {}),
        ...(params.createdAtStart ? { createdAtStart: params.createdAtStart } : {}),
        ...(params.createdAtEnd ? { createdAtEnd: params.createdAtEnd } : {}),
        ...(params.updatedAtStart ? { updatedAtStart: params.updatedAtStart } : {}),
        ...(params.updatedAtEnd ? { updatedAtEnd: params.updatedAtEnd } : {}),
        ...(params.sortBy ? { sortBy: params.sortBy } : {}),
        ...(params.sortOrder ? { sortOrder: params.sortOrder } : {}),
      });
      return request<SystemTestIssueSearchListResponse>(`/api/question-metrics/issues?${query.toString()}`);
    },
    getSystemTestIssueSearchFilterOptions(projectId?: string | number | null) {
      const query = new URLSearchParams(
        projectId != null && projectId !== '' ? { projectId: String(projectId) } : {},
      );
      return request<SystemTestIssueSearchFilterOptionsResponse>(
        `/api/question-metrics/issues/filter-options${query.toString() ? `?${query.toString()}` : ''}`,
      );
    },
    getCustomerIssueIllegalRecords(params: {
      projectId?: string | number | null;
      keyword?: string;
      issueIid?: string;
      title?: string;
      projectName?: string;
      moduleName?: string;
      illegalReason?: string;
      severityLevel?: string;
      priorityLevel?: string;
      issueState?: string;
      bugStatus?: string;
      category?: string;
      milestoneTitle?: string;
      createdAtStart?: string;
      createdAtEnd?: string;
      updatedAtStart?: string;
      updatedAtEnd?: string;
      page?: number;
      size?: number;
      sortBy?: string;
      sortOrder?: 'asc' | 'desc';
    }) {
      const query = new URLSearchParams({
        page: String(params.page ?? 1),
        size: String(params.size ?? 20),
        ...(params.projectId != null && params.projectId !== '' ? { projectId: String(params.projectId) } : {}),
        ...(params.keyword ? { keyword: params.keyword } : {}),
        ...(params.issueIid ? { issueIid: params.issueIid } : {}),
        ...(params.title ? { title: params.title } : {}),
        ...(params.projectName ? { projectName: params.projectName } : {}),
        ...(params.moduleName ? { moduleName: params.moduleName } : {}),
        ...(params.illegalReason ? { illegalReason: params.illegalReason } : {}),
        ...(params.severityLevel ? { severityLevel: params.severityLevel } : {}),
        ...(params.priorityLevel ? { priorityLevel: params.priorityLevel } : {}),
        ...(params.issueState ? { issueState: params.issueState } : {}),
        ...(params.bugStatus ? { bugStatus: params.bugStatus } : {}),
        ...(params.category ? { category: params.category } : {}),
        ...(params.milestoneTitle ? { milestoneTitle: params.milestoneTitle } : {}),
        ...(params.createdAtStart ? { createdAtStart: params.createdAtStart } : {}),
        ...(params.createdAtEnd ? { createdAtEnd: params.createdAtEnd } : {}),
        ...(params.updatedAtStart ? { updatedAtStart: params.updatedAtStart } : {}),
        ...(params.updatedAtEnd ? { updatedAtEnd: params.updatedAtEnd } : {}),
        ...(params.sortBy ? { sortBy: params.sortBy } : {}),
        ...(params.sortOrder ? { sortOrder: params.sortOrder } : {}),
      });
      return request<CustomerIssueIllegalRecordListResponse>(`/api/customer-issues/illegal-records?${query.toString()}`);
    },
    getCustomerIssueIllegalRecordFilterOptions(projectId?: string | number | null) {
      const query = new URLSearchParams(
        projectId != null && projectId !== '' ? { projectId: String(projectId) } : {},
      );
      return request<CustomerIssueIllegalRecordFilterOptionsResponse>(
        `/api/customer-issues/illegal-records/filter-options${query.toString() ? `?${query.toString()}` : ''}`,
      );
    },
    getCustomerIssueIllegalRecordRuleExplanation(projectId?: string | number | null) {
      const query = new URLSearchParams(
        projectId != null && projectId !== '' ? { projectId: String(projectId) } : {},
      );
      return request<StatisticBoardRuleExplanationResponse>(
        `/api/customer-issues/illegal-records/rule-explanation${query.toString() ? `?${query.toString()}` : ''}`,
      );
    },
    getCustomerIssueRecords(params: {
      topic: CustomerIssueRecordTopic;
      projectId?: string | number | null;
      keyword?: string;
      issueIid?: string;
      title?: string;
      projectName?: string;
      moduleName?: string;
      reasonCategory?: string;
      severityLevel?: string;
      priorityLevel?: string;
      issueState?: string;
      bugStatus?: string;
      category?: string;
      milestoneTitle?: string;
      createdAtStart?: string;
      createdAtEnd?: string;
      updatedAtStart?: string;
      updatedAtEnd?: string;
      page?: number;
      size?: number;
      sortBy?: string;
      sortOrder?: 'asc' | 'desc';
    }) {
      const query = new URLSearchParams({
        topic: params.topic,
        page: String(params.page ?? 1),
        size: String(params.size ?? 20),
        ...(params.projectId != null && params.projectId !== '' ? { projectId: String(params.projectId) } : {}),
        ...(params.keyword ? { keyword: params.keyword } : {}),
        ...(params.issueIid ? { issueIid: params.issueIid } : {}),
        ...(params.title ? { title: params.title } : {}),
        ...(params.projectName ? { projectName: params.projectName } : {}),
        ...(params.moduleName ? { moduleName: params.moduleName } : {}),
        ...(params.reasonCategory ? { reasonCategory: params.reasonCategory } : {}),
        ...(params.severityLevel ? { severityLevel: params.severityLevel } : {}),
        ...(params.priorityLevel ? { priorityLevel: params.priorityLevel } : {}),
        ...(params.issueState ? { issueState: params.issueState } : {}),
        ...(params.bugStatus ? { bugStatus: params.bugStatus } : {}),
        ...(params.category ? { category: params.category } : {}),
        ...(params.milestoneTitle ? { milestoneTitle: params.milestoneTitle } : {}),
        ...(params.createdAtStart ? { createdAtStart: params.createdAtStart } : {}),
        ...(params.createdAtEnd ? { createdAtEnd: params.createdAtEnd } : {}),
        ...(params.updatedAtStart ? { updatedAtStart: params.updatedAtStart } : {}),
        ...(params.updatedAtEnd ? { updatedAtEnd: params.updatedAtEnd } : {}),
        ...(params.sortBy ? { sortBy: params.sortBy } : {}),
        ...(params.sortOrder ? { sortOrder: params.sortOrder } : {}),
      });
      return request<CustomerIssueRecordListResponse>(`/api/customer-issues/records?${query.toString()}`);
    },
    getCustomerIssueRecordFilterOptions(topic: CustomerIssueRecordTopic, projectId?: string | number | null) {
      const query = new URLSearchParams({
        topic,
        ...(projectId != null && projectId !== '' ? { projectId: String(projectId) } : {}),
      });
      return request<CustomerIssueRecordFilterOptionsResponse>(
        `/api/customer-issues/records/filter-options?${query.toString()}`,
      );
    },
    getCustomerIssueRecordRuleExplanation(topic: CustomerIssueRecordTopic, projectId?: string | number | null) {
      const query = new URLSearchParams({
        topic,
        ...(projectId != null && projectId !== '' ? { projectId: String(projectId) } : {}),
      });
      return request<StatisticBoardRuleExplanationResponse>(
        `/api/customer-issues/records/rule-explanation?${query.toString()}`,
      );
    },
    getStatisticBoardRealtimeStatus(boardKey: string) {
      return request<RealtimeWorkspaceStatusResponse>(`/api/statistic-boards/${boardKey}/status`);
    },
    refreshStatisticBoardRealtime(boardKey: string) {
      return request<RealtimeWorkspaceStatusResponse>(`/api/statistic-boards/${boardKey}/refresh`, {
        method: 'POST',
      });
    },
    getCollectFormDetail(params: {
      gitlabBaseUrl: string;
      projectId: number;
    resourceType: string;
    resourceId: string;
    templateCode: string;
  }) {
    const query = new URLSearchParams({
      gitlabBaseUrl: params.gitlabBaseUrl,
      projectId: String(params.projectId),
      resourceType: params.resourceType,
      resourceId: params.resourceId,
      templateCode: params.templateCode,
    });
    return request<CollectFormDetailResponse | null>(`/api/collect-forms/detail?${query.toString()}`);
  },
  saveCollectForm(payload: {
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
    remark: string;
  }) {
    return request<CollectFormDetailResponse>('/api/collect-forms/save', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },
  updateCollectFormRecord(payload: {
    id: number;
    formTitle: string;
    reviewer: string;
    reviewDurationMinutes: number;
    specificationScore: number;
    logicScore: number;
    performanceScore: number;
    designScore: number;
    otherScore: number;
    remark: string;
    deleted: boolean;
  }) {
    return request<CollectFormDetailResponse>('/api/collect-forms/update-record', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },
  deleteCollectForm(payload: {
    gitlabBaseUrl: string;
    projectId: number;
    resourceType: string;
    resourceId: string;
    templateCode: string;
  }) {
    return request<boolean>('/api/collect-forms/delete', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },
  getCollectFormNotificationPayload(params: {
    gitlabBaseUrl: string;
    projectId: number;
    requestIid: number;
    resourceType: string;
  }) {
    const query = new URLSearchParams({
      gitlabBaseUrl: params.gitlabBaseUrl,
      projectId: String(params.projectId),
      requestIid: String(params.requestIid),
      resourceType: params.resourceType,
    });
    return request<CollectFormNotificationPayloadResponse>(
      `/api/collect-forms/notification-payload?${query.toString()}`,
    );
  },
};
