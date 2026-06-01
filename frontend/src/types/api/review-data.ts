import type { OptionItemResponse } from './common';

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
  gitlabProjectId?: number | null;
  gitlabResourceIid?: number | null;
  gitlabResourceType?: string | null;
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

export interface ReviewDataGitlabContextRefreshRequest {
  recordIds?: number[];
  resourceType?: string | null;
}

export interface ReviewDataGitlabContextRefreshResponse {
  accepted: boolean;
  jobId?: number | null;
  status: string;
  resourceTypes: string[];
  sourceTables: string[];
  plannedTasks: number;
  manualFieldsTouched: boolean;
  message: string;
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

export type ReviewDataLegacyExcelIssueLevel = 'ERROR' | 'WARNING';

export interface ReviewDataLegacyExcelImportIssue {
  rowNumber: number;
  field: string;
  level: ReviewDataLegacyExcelIssueLevel;
  message: string;
}

export interface ReviewDataLegacyExcelImportRequest {
  defaultReviewDate?: string | null;
  defaultReviewOwner?: string | null;
  defaultReviewExperts?: string[];
  defaultAuthorName?: string | null;
  defaultReviewVersion?: string | null;
  defaultProblemStatus?: string | null;
  duplicateStrategy?: string | null;
}

export interface ReviewDataLegacyExcelPreviewRowResponse {
  rowNumber: number;
  importable: boolean;
  record: ReviewDataRecordSaveRequest;
  problemItems: ReviewDataProblemItemSaveRequest[];
  issues: ReviewDataLegacyExcelImportIssue[];
}

export interface ReviewDataLegacyExcelPreviewResponse {
  previewToken: string;
  sheetName: string;
  totalRows: number;
  importableRows: number;
  warningRows: number;
  errorRows: number;
  estimatedRecordCount: number;
  estimatedProblemItemCount: number;
  rows: ReviewDataLegacyExcelPreviewRowResponse[];
  issues: ReviewDataLegacyExcelImportIssue[];
}

export interface ReviewDataLegacyExcelConfirmResponse {
  importedRecords: number;
  skippedRecords: number;
  importedProblemItems: number;
  issues: ReviewDataLegacyExcelImportIssue[];
}

export type ReviewDataLegacyExcelConfirmRequest =
  ReviewDataLegacyExcelImportRequest & {
    previewToken: string;
  };
