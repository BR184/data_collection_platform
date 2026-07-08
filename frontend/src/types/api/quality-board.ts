import type { OptionItemResponse } from './common';

export interface QualityBoardMetricResponse {
  key: string;
  label: string;
  value: number;
  unit: string;
  targetDescription: string;
}

export interface QualityBoardProjectOptionsResponse {
  defaultProjectName: string;
  options: OptionItemResponse[];
}

export interface QualityBoardRdOverviewResponse {
  projectName: string;
  demandReviewReportDensity: number;
  designReviewReportDensity: number;
  codeWalkThroughDefectDensityCc: number;
  codeWalkThroughDefectDensityDgm: number;
  integrationPassRate: number;
  defectLeakageRate: number;
  defectEliminationRate: number;
  newIssueFixRate: number;
  metrics: QualityBoardMetricResponse[];
}

export interface QualityBoardChartRowResponse {
  name: string;
  value: number;
}

export interface QualityBoardFixUserSeverityRowResponse {
  name: string;
  level1: number;
  level2: number;
  level3: number;
  suggestion: number;
  total: number;
}

export interface QualityBoardOtherOverviewResponse {
  summary: QualityBoardRdOverviewResponse;
  assigneeDefectDensityRows: QualityBoardChartRowResponse[];
  authorDefectDensityRows: QualityBoardChartRowResponse[];
  fixUserSeverityRows: QualityBoardFixUserSeverityRowResponse[];
  frequencyCodeSubmissionRows: QualityBoardChartRowResponse[];
  defectRepairUserRows: QualityBoardChartRowResponse[];
}
