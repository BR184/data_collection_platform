import type { OptionItemResponse } from './common';

export interface CustomerIssueIllegalRecordRowResponse {
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
  priorityLevel: string;
  bugStatus: string;
  category: string;
  milestoneTitle: string;
  authorName: string;
  assigneeName: string;
  moduleNames: string;
  functionName: string;
  delayReason: string;
  delayCause: string;
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
  functionNames: OptionItemResponse[];
  illegalReasons: OptionItemResponse[];
  severityLevels: OptionItemResponse[];
  priorityLevels: OptionItemResponse[];
  issueStates: OptionItemResponse[];
  bugStatuses: OptionItemResponse[];
  categories: OptionItemResponse[];
  authorNames: OptionItemResponse[];
  assigneeNames: OptionItemResponse[];
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
  functionName: string;
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
  functionNames: OptionItemResponse[];
  reasonCategories: OptionItemResponse[];
  severityLevels: OptionItemResponse[];
  priorityLevels: OptionItemResponse[];
  issueStates: OptionItemResponse[];
  bugStatuses: OptionItemResponse[];
  categories: OptionItemResponse[];
  authorNames: OptionItemResponse[];
  assigneeNames: OptionItemResponse[];
  milestoneTitles: OptionItemResponse[];
}
