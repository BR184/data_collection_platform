import type {
  CustomerIssueIllegalRecordFilterOptionsResponse,
  CustomerIssueIllegalRecordListResponse,
  CustomerIssueRecordFilterOptionsResponse,
  CustomerIssueRecordListResponse,
  CustomerIssueRecordTopic,
  StatisticFilterGroup,
  StatisticBoardRuleExplanationResponse,
  SystemTestIllegalRecordFilterOptionsResponse,
  SystemTestIllegalRecordListResponse,
  SystemTestIssueSearchFilterOptionsResponse,
  SystemTestIssueSearchListResponse,
} from '../types/api';
import { request, requestText } from './request';

type SystemTestIssueSearchQueryParams = {
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
    ...(params.filterGroup ? { filterGroup: JSON.stringify(params.filterGroup) } : {}),
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
    ...(params.filterGroup ? { filterGroup: JSON.stringify(params.filterGroup) } : {}),
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
    ...(params.filterGroup ? { filterGroup: JSON.stringify(params.filterGroup) } : {}),
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
    ...(params.filterGroup ? { filterGroup: JSON.stringify(params.filterGroup) } : {}),
    ...(params.sortBy ? { sortBy: params.sortBy } : {}),
    ...(params.sortOrder ? { sortOrder: params.sortOrder } : {}),
  });
}

async function requestCsv(url: string) {
  return requestText(url, { timeoutMs: 60_000 });
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
  getSystemTestIssueSearchFilterOptions(projectId?: string | number | null) {
    const query = new URLSearchParams(
      projectId != null && projectId !== '' ? { projectId: String(projectId) } : {},
    );
    return request<SystemTestIssueSearchFilterOptionsResponse>(
      `/api/question-metrics/issues/filter-options${query.toString() ? `?${query.toString()}` : ''}`,
    );
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
      ...(params.filterGroup ? { filterGroup: JSON.stringify(params.filterGroup) } : {}),
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
};
