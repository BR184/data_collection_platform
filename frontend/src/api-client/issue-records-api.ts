import type {
  CustomerIssueIllegalRecordFilterOptionsResponse,
  CustomerIssueIllegalRecordListResponse,
  CustomerIssueRecordFilterOptionsResponse,
  CustomerIssueRecordListResponse,
  CustomerIssueRecordTopic,
  RealtimeWorkspaceStatusResponse,
  StatisticFilterGroup,
  StatisticBoardRuleExplanationResponse,
  SystemTestIllegalRecordFilterOptionsResponse,
  SystemTestIllegalRecordListResponse,
  SystemTestIssueSearchFilterOptionsResponse,
  SystemTestIssueSearchListResponse,
} from '../types/api';
import { EXPORT_REQUEST_TIMEOUT_MS, request, requestText } from './request';
import { stringifyStatisticFilterGroup } from '../utils/statistic-filter-group';

type SystemTestIssueSearchQueryParams = {
  projectId?: string | number | null;
  keyword?: string;
  searchType?: string;
  issueIid?: string;
  sourceInstance?: string | null;
  title?: string;
  projectName?: string;
  moduleName?: string;
  functionName?: string;
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
  filterGroup?: StatisticFilterGroup | null;
  page?: number;
  size?: number;
  sortBy?: string;
  sortOrder?: 'asc' | 'desc';
};

type SystemTestIllegalRecordQueryParams = SystemTestIssueSearchQueryParams & {
  illegalReason?: string;
  priorityLevel?: string;
};

function buildSystemTestIssueSearchQuery(params: SystemTestIssueSearchQueryParams, includePagination = true) {
  return new URLSearchParams({
    ...(includePagination ? { page: String(params.page ?? 1), size: String(params.size ?? 20) } : {}),
    ...(params.projectId != null && params.projectId !== '' ? { projectId: String(params.projectId) } : {}),
    ...(params.keyword ? { keyword: params.keyword } : {}),
    ...(params.searchType ? { searchType: params.searchType } : {}),
    ...(params.issueIid ? { issueIid: params.issueIid } : {}),
    ...(params.sourceInstance ? { sourceInstance: params.sourceInstance } : {}),
    ...(params.title ? { title: params.title } : {}),
    ...(params.projectName ? { projectName: params.projectName } : {}),
    ...(params.moduleName ? { moduleName: params.moduleName } : {}),
    ...(params.functionName ? { functionName: params.functionName } : {}),
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
    ...(params.filterGroup ? { filterGroup: stringifyStatisticFilterGroup(params.filterGroup) } : {}),
    ...(params.sortBy ? { sortBy: params.sortBy } : {}),
    ...(params.sortOrder ? { sortOrder: params.sortOrder } : {}),
  });
}

function buildSystemTestIllegalRecordQuery(params: SystemTestIllegalRecordQueryParams, includePagination = true) {
  return new URLSearchParams({
    ...(includePagination ? { page: String(params.page ?? 1), size: String(params.size ?? 20) } : {}),
    ...(params.projectId != null && params.projectId !== '' ? { projectId: String(params.projectId) } : {}),
    ...(params.keyword ? { keyword: params.keyword } : {}),
    ...(params.issueIid ? { issueIid: params.issueIid } : {}),
    ...(params.title ? { title: params.title } : {}),
    ...(params.projectName ? { projectName: params.projectName } : {}),
    ...(params.moduleName ? { moduleName: params.moduleName } : {}),
    ...(params.testingPhase ? { testingPhase: params.testingPhase } : {}),
    ...(params.illegalReason ? { illegalReason: params.illegalReason } : {}),
    ...(params.authorName ? { authorName: params.authorName } : {}),
    ...(params.assigneeName ? { assigneeName: params.assigneeName } : {}),
    ...(params.issueState ? { issueState: params.issueState } : {}),
    ...(params.severityLevel ? { severityLevel: params.severityLevel } : {}),
    ...(params.priorityLevel ? { priorityLevel: params.priorityLevel } : {}),
    ...(params.bugStatus ? { bugStatus: params.bugStatus } : {}),
    ...(params.category ? { category: params.category } : {}),
    ...(params.milestoneTitle ? { milestoneTitle: params.milestoneTitle } : {}),
    ...(params.createdAtStart ? { createdAtStart: params.createdAtStart } : {}),
    ...(params.createdAtEnd ? { createdAtEnd: params.createdAtEnd } : {}),
    ...(params.updatedAtStart ? { updatedAtStart: params.updatedAtStart } : {}),
    ...(params.updatedAtEnd ? { updatedAtEnd: params.updatedAtEnd } : {}),
    ...(params.filterGroup ? { filterGroup: stringifyStatisticFilterGroup(params.filterGroup) } : {}),
    ...(params.sortBy ? { sortBy: params.sortBy } : {}),
    ...(params.sortOrder ? { sortOrder: params.sortOrder } : {}),
  });
}

function buildCustomerIssueIllegalRecordQuery(params: {
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
  filterGroup?: StatisticFilterGroup | null;
  page?: number;
  size?: number;
  sortBy?: string;
  sortOrder?: 'asc' | 'desc';
}, includePagination = true) {
  return new URLSearchParams({
    ...(includePagination ? { page: String(params.page ?? 1), size: String(params.size ?? 20) } : {}),
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
    ...(params.filterGroup ? { filterGroup: stringifyStatisticFilterGroup(params.filterGroup) } : {}),
    ...(params.sortBy ? { sortBy: params.sortBy } : {}),
    ...(params.sortOrder ? { sortOrder: params.sortOrder } : {}),
  });
}

function buildCustomerIssueRecordQuery(params: {
  topic: CustomerIssueRecordTopic;
  projectId?: string | number | null;
  keyword?: string;
  issueIid?: string;
  title?: string;
  projectName?: string;
  moduleName?: string;
  functionName?: string;
  reasonCategory?: string;
  authorName?: string;
  assigneeName?: string;
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
  filterGroup?: StatisticFilterGroup | null;
  page?: number;
  size?: number;
  sortBy?: string;
  sortOrder?: 'asc' | 'desc';
}, includePagination = true) {
  return new URLSearchParams({
    topic: params.topic,
    ...(includePagination ? { page: String(params.page ?? 1), size: String(params.size ?? 20) } : {}),
    ...(params.projectId != null && params.projectId !== '' ? { projectId: String(params.projectId) } : {}),
    ...(params.keyword ? { keyword: params.keyword } : {}),
    ...(params.issueIid ? { issueIid: params.issueIid } : {}),
    ...(params.title ? { title: params.title } : {}),
    ...(params.projectName ? { projectName: params.projectName } : {}),
    ...(params.moduleName ? { moduleName: params.moduleName } : {}),
    ...(params.functionName ? { functionName: params.functionName } : {}),
    ...(params.reasonCategory ? { reasonCategory: params.reasonCategory } : {}),
    ...(params.authorName ? { authorName: params.authorName } : {}),
    ...(params.assigneeName ? { assigneeName: params.assigneeName } : {}),
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
    ...(params.filterGroup ? { filterGroup: stringifyStatisticFilterGroup(params.filterGroup) } : {}),
    ...(params.sortBy ? { sortBy: params.sortBy } : {}),
    ...(params.sortOrder ? { sortOrder: params.sortOrder } : {}),
  });
}

async function requestCsv(url: string) {
  return requestText(url, {
    errorPrefix: '导出失败',
    timeoutMs: EXPORT_REQUEST_TIMEOUT_MS,
  });
}

export const issueRecordsApi = {
  getSystemTestIssueSearchRecords(params: SystemTestIssueSearchQueryParams) {
    const query = buildSystemTestIssueSearchQuery(params);
    return request<SystemTestIssueSearchListResponse>(`/api/question-metrics/issues?${query.toString()}`);
  },
  exportSystemTestIssueSearchRecords(params: SystemTestIssueSearchQueryParams) {
    const query = buildSystemTestIssueSearchQuery(params, false);
    return requestCsv(`/api/question-metrics/issues/export${query.toString() ? `?${query.toString()}` : ''}`);
  },
  getSystemTestIssueSearchFilterOptions(
    projectId?: string | number | null,
    sourceInstance?: string | null,
  ) {
    const query = new URLSearchParams(
      {
        ...(projectId != null && projectId !== '' ? { projectId: String(projectId) } : {}),
        ...(sourceInstance ? { sourceInstance } : {}),
      },
    );
    return request<SystemTestIssueSearchFilterOptionsResponse>(
      `/api/question-metrics/issues/filter-options${query.toString() ? `?${query.toString()}` : ''}`,
    );
  },
  getSystemTestIssueSearchRealtimeStatus() {
    return request<RealtimeWorkspaceStatusResponse>('/api/question-metrics/issues/status');
  },
  refreshSystemTestIssueSearchRealtime() {
    return request<RealtimeWorkspaceStatusResponse>('/api/question-metrics/issues/refresh', {
      method: 'POST',
    });
  },
  getSystemTestIllegalRecords(params: SystemTestIllegalRecordQueryParams) {
    const query = buildSystemTestIllegalRecordQuery(params);
    return request<SystemTestIllegalRecordListResponse>(`/api/question-metrics/illegal-records?${query.toString()}`);
  },
  exportSystemTestIllegalRecords(params: SystemTestIllegalRecordQueryParams) {
    const query = buildSystemTestIllegalRecordQuery(params, false);
    return requestCsv(`/api/question-metrics/illegal-records/export${query.toString() ? `?${query.toString()}` : ''}`);
  },
  getSystemTestIllegalRecordFilterOptions(projectId?: string | number | null) {
    const query = new URLSearchParams(
      projectId != null && projectId !== '' ? { projectId: String(projectId) } : {},
    );
    return request<SystemTestIllegalRecordFilterOptionsResponse>(
      `/api/question-metrics/illegal-records/filter-options${query.toString() ? `?${query.toString()}` : ''}`,
    );
  },
  getSystemTestIllegalRecordRuleExplanation(projectId?: string | number | null) {
    const query = new URLSearchParams(
      projectId != null && projectId !== '' ? { projectId: String(projectId) } : {},
    );
    return request<StatisticBoardRuleExplanationResponse>(
      `/api/question-metrics/illegal-records/rule-explanation${query.toString() ? `?${query.toString()}` : ''}`,
    );
  },
  getSystemTestIllegalRecordRealtimeStatus() {
    return request<RealtimeWorkspaceStatusResponse>('/api/question-metrics/illegal-records/status');
  },
  refreshSystemTestIllegalRecordRealtime() {
    return request<RealtimeWorkspaceStatusResponse>('/api/question-metrics/illegal-records/refresh', {
      method: 'POST',
    });
  },
  refreshSystemTestIllegalRecord(payload: {
    source?: string | null;
    projectId?: string | number | null;
    issueIid?: string | number | null;
  }) {
    return request<SystemTestIllegalRecordListResponse['records'][number] | null>(
      '/api/question-metrics/illegal-records/refresh-one',
      {
        method: 'POST',
        body: JSON.stringify(payload),
      },
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
    filterGroup?: StatisticFilterGroup | null;
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
      ...(params.filterGroup ? { filterGroup: stringifyStatisticFilterGroup(params.filterGroup) } : {}),
      ...(params.sortBy ? { sortBy: params.sortBy } : {}),
      ...(params.sortOrder ? { sortOrder: params.sortOrder } : {}),
    });
    return request<CustomerIssueIllegalRecordListResponse>(`/api/customer-issues/illegal-records?${query.toString()}`);
  },
  exportCustomerIssueIllegalRecords(params: Parameters<typeof buildCustomerIssueIllegalRecordQuery>[0]) {
    const query = buildCustomerIssueIllegalRecordQuery(params, false);
    return requestCsv(`/api/customer-issues/illegal-records/export${query.toString() ? `?${query.toString()}` : ''}`);
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
  getCustomerIssueIllegalRecordRealtimeStatus() {
    return request<RealtimeWorkspaceStatusResponse>('/api/customer-issues/illegal-records/status');
  },
  refreshCustomerIssueIllegalRecordRealtime() {
    return request<RealtimeWorkspaceStatusResponse>('/api/customer-issues/illegal-records/refresh', {
      method: 'POST',
    });
  },
  refreshCustomerIssueIllegalRecord(payload: {
    source?: string | null;
    projectId?: string | number | null;
    issueIid?: string | number | null;
  }) {
    return request<CustomerIssueIllegalRecordListResponse['records'][number] | null>(
      '/api/customer-issues/illegal-records/refresh-one',
      {
        method: 'POST',
        body: JSON.stringify(payload),
      },
    );
  },
  getCustomerIssueRecords(params: Parameters<typeof buildCustomerIssueRecordQuery>[0]) {
    const query = buildCustomerIssueRecordQuery(params);
    return request<CustomerIssueRecordListResponse>(`/api/customer-issues/records?${query.toString()}`);
  },
  exportCustomerIssueRecords(params: Parameters<typeof buildCustomerIssueRecordQuery>[0]) {
    const query = buildCustomerIssueRecordQuery(params, false);
    return requestCsv(`/api/customer-issues/records/export${query.toString() ? `?${query.toString()}` : ''}`);
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
  getCustomerIssueRecordRealtimeStatus(topic: CustomerIssueRecordTopic) {
    const query = new URLSearchParams({ topic });
    return request<RealtimeWorkspaceStatusResponse>(`/api/customer-issues/records/status?${query.toString()}`);
  },
  refreshCustomerIssueRecordRealtime(topic: CustomerIssueRecordTopic) {
    const query = new URLSearchParams({ topic });
    return request<RealtimeWorkspaceStatusResponse>(`/api/customer-issues/records/refresh?${query.toString()}`, {
      method: 'POST',
    });
  },
};
