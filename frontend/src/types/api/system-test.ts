import type { OptionItemResponse } from './common';

export interface SystemTestIssueSearchRowResponse {
  issueId: number;
  issueIid: number;
  issueLink?: string | null;
  sourceInstance?: string | null;
  projectId: number;
  projectName: string;
  title: string;
  issueState: string;
  testingPhase: string;
  severityLevel: string;
  priorityLevel: string;
  bugStatus: string;
  category: string;
  milestoneTitle: string;
  delayCause: string;
  authorName: string;
  assigneeName: string;
  moduleNames: string;
  functionName: string;
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
  functionNames: OptionItemResponse[];
  testingPhases: OptionItemResponse[];
  authorNames: OptionItemResponse[];
  assigneeNames: OptionItemResponse[];
  issueStates: OptionItemResponse[];
  severityLevels: OptionItemResponse[];
  bugStatuses: OptionItemResponse[];
  categories: OptionItemResponse[];
  milestoneTitles: OptionItemResponse[];
}

export interface SystemTestIssueMultiBoardScopeResponse {
  projectId: number;
  projectName: string;
  testingPhase?: string | null;
  expandedTestingPhases: string[];
  scopeLabel: string;
}

export interface SystemTestIssueMultiBoardSummaryCardResponse {
  key: string;
  label: string;
  value: string;
  tone?: 'default' | 'success' | 'warning' | 'danger' | string;
}

export interface SystemTestIssueMultiBoardSeriesResponse {
  name: string;
  data: number[];
}

export interface SystemTestIssueMultiBoardPointResponse {
  name: string;
  value: number;
}

export interface SystemTestIssueMultiBoardChartResponse {
  key: string;
  title: string;
  description: string;
  chartType: 'pie' | 'bar' | 'stackedBar' | string;
  detailPath?: string | null;
  exportName: string;
  categories: string[];
  series: SystemTestIssueMultiBoardSeriesResponse[];
  points: SystemTestIssueMultiBoardPointResponse[];
  metadata: Record<string, string>;
}

export interface SystemTestIssueMultiBoardResponse {
  scope: SystemTestIssueMultiBoardScopeResponse;
  projectOptions: OptionItemResponse[];
  testingPhaseOptions: OptionItemResponse[];
  summaryCards: SystemTestIssueMultiBoardSummaryCardResponse[];
  charts: SystemTestIssueMultiBoardChartResponse[];
}

export interface SystemTestIllegalRecordRowResponse {
  issueId: number;
  issueIid: number;
  issueLink?: string | null;
  sourceInstance?: string | null;
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
  functionName: string;
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
