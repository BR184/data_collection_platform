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
  reviewEfficiency?: number | null;
  reviewRate?: number | null;
  reviewCategorySummary?: string | null;
  docSpecificationCount?: number | null;
  integrityCount?: number | null;
  functionalityCount?: number | null;
  feasibilityCount?: number | null;
  independentReviewWorkload?: number | null;
  independentReviewProblemCount?: number | null;
  meetingReviewWorkload?: number | null;
  meetingReviewProblemCount?: number | null;
  notReachStandardReason?: string | null;
  reachStandard?: boolean | null;
  sourceFileName?: string | null;
  weightedDefectDensity?: number | null;
  createdAt?: string | null;
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
  reviewVersions: OptionItemResponse[];
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

export interface ReviewDataDescriptionResponse {
  id: number;
  reviewRecordId: number;
  reviewProduct: string;
  reviewVersion: string;
  authorName: string;
  reviewScalePages: number;
  unit: string;
  sortOrder: number;
  updatedAt?: string | null;
}

export interface ReviewDataContentResponse {
  id: number;
  reviewRecordId: number;
  reviewerName: string;
  assignmentContent: string;
  independentWorkloadHours: number;
  independentProblemCount: number;
  meetingWorkloadHours: number;
  meetingProblemCount: number;
  sortOrder: number;
  updatedAt?: string | null;
}

export interface ReviewDataRecordDetailResponse {
  record: ReviewDataRecordRowResponse;
  reviewExperts: string[];
  problemItems: ReviewDataProblemItemResponse[];
  descriptions: ReviewDataDescriptionResponse[];
  contents: ReviewDataContentResponse[];
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
  notReachStandardReason?: string | null;
  sourceFileName?: string | null;
  weightedDefectDensity?: number | null;
  descriptions?: ReviewDataDescriptionSaveRequest[];
  contents?: ReviewDataContentSaveRequest[];
  createPendingProblemItems?: boolean | null;
}

export interface ReviewDataDescriptionSaveRequest {
  reviewProduct: string;
  reviewVersion: string;
  authorName: string;
  reviewScalePages: number;
  unit?: string | null;
  sortOrder?: number | null;
}

export interface ReviewDataContentSaveRequest {
  reviewerName: string;
  assignmentContent?: string | null;
  independentWorkloadHours?: number | null;
  independentProblemCount?: number | null;
  meetingWorkloadHours?: number | null;
  meetingProblemCount?: number | null;
  sortOrder?: number | null;
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
