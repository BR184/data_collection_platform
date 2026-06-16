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
