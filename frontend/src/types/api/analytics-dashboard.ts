import type { EChartsOption } from 'echarts';

export interface AnalyticsDashboardDetailAction {
  viewKey: string;
  params: Record<string, string>;
}

export interface AnalyticsDashboardExportAction {
  exportKey: string;
  label: string;
}

export interface AnalyticsDashboardMetric {
  key: string;
  title: string;
  value: number | null;
  displayValue: string;
  unit?: string | null;
  ruleKey?: string | null;
  detail?: AnalyticsDashboardDetailAction | null;
  export?: AnalyticsDashboardExportAction | null;
}

export interface AnalyticsDashboardChart {
  key: string;
  title: string;
  subtitle?: string | null;
  option: EChartsOption;
  ruleKey?: string | null;
  detail?: AnalyticsDashboardDetailAction | null;
  export?: AnalyticsDashboardExportAction | null;
}

export interface AnalyticsDashboardResponse {
  dashboardKey: string;
  title: string;
  subtitle?: string | null;
  metrics: AnalyticsDashboardMetric[];
  charts: AnalyticsDashboardChart[];
}

export interface AnalyticsDashboardRule {
  key: string;
  title: string;
  formula?: string | null;
  scope?: string | null;
  target?: string | null;
  description?: string | null;
}

export interface AnalyticsDashboardRulesResponse {
  dashboardKey: string;
  rules: AnalyticsDashboardRule[];
}

export interface AnalyticsDashboardDetailColumn {
  key: string;
  label: string;
  format?: string | null;
  width?: number | null;
}

export interface AnalyticsDashboardDetailFilterOption {
  label: string;
  value: string;
}

export interface AnalyticsDashboardDetailFilter {
  key: string;
  label: string;
  value?: string | null;
  options: AnalyticsDashboardDetailFilterOption[];
}

export interface AnalyticsDashboardDetailChart {
  key: string;
  title: string;
  subtitle?: string | null;
  option: EChartsOption;
  height?: number | null;
}

export interface AnalyticsDashboardDetailResponse {
  dashboardKey: string;
  viewKey: string;
  title: string;
  description?: string | null;
  columns: AnalyticsDashboardDetailColumn[];
  records: Array<Record<string, unknown>>;
  total: number;
  page: number;
  size: number;
  exports: AnalyticsDashboardExportAction[];
  filters: AnalyticsDashboardDetailFilter[];
  chart?: AnalyticsDashboardDetailChart | null;
}

export type AnalyticsDashboardQueryValue = string | number | boolean | null | undefined;
export interface AnalyticsDashboardQuery {
  projectId?: AnalyticsDashboardQueryValue;
  projectName?: AnalyticsDashboardQueryValue;
  testingPhase?: AnalyticsDashboardQueryValue;
  milestoneTitle?: AnalyticsDashboardQueryValue;
  codeReviewSource?: AnalyticsDashboardQueryValue;
  source?: AnalyticsDashboardQueryValue;
  reviewerName?: AnalyticsDashboardQueryValue;
  personName?: AnalyticsDashboardQueryValue;
  authorName?: AnalyticsDashboardQueryValue;
  assigneeName?: AnalyticsDashboardQueryValue;
  fixUser?: AnalyticsDashboardQueryValue;
  moduleName?: AnalyticsDashboardQueryValue;
  severityLevel?: AnalyticsDashboardQueryValue;
  priorityLevel?: AnalyticsDashboardQueryValue;
  issueState?: AnalyticsDashboardQueryValue;
  bugStatus?: AnalyticsDashboardQueryValue;
  category?: AnalyticsDashboardQueryValue;
  pointKey?: AnalyticsDashboardQueryValue;
  topic?: AnalyticsDashboardQueryValue;
  functionCountProjectName?: AnalyticsDashboardQueryValue;
  functionDensityProjectName?: AnalyticsDashboardQueryValue;
  qualityRankingProjectName?: AnalyticsDashboardQueryValue;
  memberUnresolvedProjectName?: AnalyticsDashboardQueryValue;
  page?: AnalyticsDashboardQueryValue;
  size?: AnalyticsDashboardQueryValue;
  sortField?: AnalyticsDashboardQueryValue;
  sortOrder?: AnalyticsDashboardQueryValue;
}
