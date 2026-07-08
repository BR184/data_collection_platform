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
