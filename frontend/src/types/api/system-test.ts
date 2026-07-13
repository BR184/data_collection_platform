import type { OptionItemResponse } from './common';
import type { AnalyticsDashboardRule } from './analytics-dashboard';

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
  ruleKey: string;
  label: string;
  value: string;
  tone?: 'default' | 'success' | 'warning' | 'danger' | string;
}

export interface SystemTestIssueMultiBoardSeriesResponse {
  name: string;
  data: SystemTestIssueMultiBoardPointResponse[];
}

export interface SystemTestIssueMultiBoardPointResponse {
  name: string;
  value: number;
  pointKey: string;
  detailViewKey: string;
  detailParams: Record<string, string>;
}

export interface SystemTestIssueMultiBoardChartResponse {
  key: string;
  ruleKey: string;
  title: string;
  description: string;
  chartType: 'pie' | 'bar' | 'stackedBar' | string;
  detailViewKey: string;
  detailParams: Record<string, string>;
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
  rules: AnalyticsDashboardRule[];
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
  priorityLevel?: string | null;
  delayCause?: string | null;
  authorName: string;
  assigneeName: string;
  moduleNames: string;
  functionName: string;
  fixUser?: string | null;
  fixStatus?: string | null;
  majorCause?: string | null;
  secondCause?: string | null;
  specificReason?: string | null;
  modification?: string | null;
  causedByOther?: string | null;
  effectFunction?: string | null;
  hasTested?: string | null;
  potentialImpact?: string | null;
  relationTableUpdated?: string | null;
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
