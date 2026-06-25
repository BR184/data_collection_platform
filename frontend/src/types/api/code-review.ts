import type { CodeReviewRuleConfig } from '../code-review-rule-config';
import type { OptionItemResponse } from './common';

export interface CodeReviewIllegalRecordRowResponse {
  requestType: string;
  sourceInstance: string;
  mergeRequestId: number;
  mergeRequestIid: number;
  projectId: number;
  mergeRequestContent: string;
  mergeRequestLink?: string | null;
  owner: string;
  projectName: string;
  repositoryName: string;
  mergedAt?: string | null;
  author: string;
  mergedBy: string;
  moduleName: string;
  targetBranch: string;
  illegalTypes: string[];
  reviewerNames: string;
  assigneeNames: string;
  reviewStatus: string;
  reviewDurationMinutes?: number | null;
  reviewExceptionReason?: string | null;
  codeWalkthroughDate?: string | null;
  scanStatus: string;
  scanBugCount?: number | null;
  annotationRateResult: string;
  bugCountResult: string;
  commentRate?: number | null;
  defectCount?: number | null;
  addedLines?: number | null;
  deletedLines?: number | null;
  codeSpecificationCount?: number | null;
  codeLogicSpecificationCount?: number | null;
  performanceSpecificationCount?: number | null;
  designSpecificationCount?: number | null;
  otherSpecificationCount?: number | null;
  reviewSpeedLocPerHour?: number | null;
  reviewSpeedKlocPerHour?: number | null;
  defectDensityPerKloc?: number | null;
  reviewEfficiencyPerHour?: number | null;
  commitCount?: number | null;
  commitRate?: number | null;
  functionName: string;
  clangAddedLineCount?: number | null;
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
  projects: OptionItemResponse[];
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
  source?: string;
  ruleConfig: CodeReviewRuleConfig;
}
