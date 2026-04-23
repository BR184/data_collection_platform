import type {
  CustomerIssueIllegalRecordFilterOptionsResponse,
  CustomerIssueIllegalRecordListResponse,
  CustomerIssueRecordFilterOptionsResponse,
  CustomerIssueRecordListResponse,
  CustomerIssueRecordTopic,
  StatisticBoardRuleExplanationResponse,
  SystemTestIssueSearchFilterOptionsResponse,
  SystemTestIssueSearchListResponse,
} from '../types/api';
import { request } from './request';

export const issueRecordsApi = {
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
};
