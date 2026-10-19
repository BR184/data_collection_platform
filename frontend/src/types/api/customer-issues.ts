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
  /** 与项目/议题共同构成稳定业务身份，不能按事实修订自增 ID 或链接猜测来源。 */
  sourceInstance: string;
  issueId: number;
  issueIid: number;
  issueLink?: string | null;
  projectId: number;
  projectName: string;
  title: string;
  customerNames: string;
  issueState: string;
  severityLevel: string;
  priorityLevel: string;
  bugStatus: string;
  category: string;
  reasonCategory: string;
  milestoneTitle: string;
  authorName: string;
  handlerName: string;
  assigneeName: string;
  testingPhase: string;
  fixUser: string;
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
  retentionHours?: number | null;
  plannedResolutionAt?: string | null;
  plannedResolutionText: string;
  plannedMergeVersionBranch: string;
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
  customerNames: OptionItemResponse[];
  reasonCategories: OptionItemResponse[];
  severityLevels: OptionItemResponse[];
  priorityLevels: OptionItemResponse[];
  issueStates: OptionItemResponse[];
  bugStatuses: OptionItemResponse[];
  categories: OptionItemResponse[];
  authorNames: OptionItemResponse[];
  handlerNames: OptionItemResponse[];
  assigneeNames: OptionItemResponse[];
  testingPhases: OptionItemResponse[];
  fixUsers: OptionItemResponse[];
  delayCauses: OptionItemResponse[];
  plannedMergeVersionBranches: OptionItemResponse[];
  milestoneTitles: OptionItemResponse[];
}
