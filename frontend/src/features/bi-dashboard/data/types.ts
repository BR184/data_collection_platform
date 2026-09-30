export type BiDataStatus = 'READY' | 'EMPTY' | 'NOT_APPLICABLE' | 'INCOMPLETE' | 'ERROR';

export type BiPageKey =
  | 'requirements'
  | 'design'
  | 'coding'
  | 'unit-test'
  | 'integration-test'
  | 'system-test'
  | 'customer-issues';

export type BiChartTemplateId =
  | 'distribution-donut'
  | 'vertical-category-bar'
  | 'stacked-category-bar'
  | 'coding-trend-combo'
  | 'submission-trend-combo'
  | 'overlay-category-bar'
  | 'developer-workload'
  | 'quality-round-track'
  | 'module-repair-matrix'
  | 'test-quality-attainment'
  | 'review-quality-dual-panel'
  | 'review-quality-scatter'
  | 'quality-trend-small-multiples'
  | 'delay-heatmap'
  | 'daily-defect-trend';

export type BiMemberSelection =
  | { kind: 'ALL' }
  | { kind: 'MISSING' }
  | { kind: 'VALUE'; value: string };

/** 每张图在授权时冻结的完整范围身份。 */
export type BiDownloadScope =
  | { rangeType: 'PRODUCT_VERSION'; productVersionId: number }
  | {
    rangeType: 'CUSTOMER_ISSUE';
    milestoneBusinessKey: string;
    businessDate: string;
    customerKind: BiMemberSelection['kind'];
    customer?: string;
    moduleKind: BiMemberSelection['kind'];
    module?: string;
    functionKind: BiMemberSelection['kind'];
    function?: string;
  };

export interface BiChartDownloadContext {
  pageKey: BiPageKey;
  chartInstanceId: string;
  sourceVersion: string;
  scope: BiDownloadScope;
}

export interface BiProductVersionOption {
  id: number;
  businessKey: string;
  displayName: string;
  sortOrder: number;
}

export interface BiProductVersionCatalog {
  defaultVersionId: number | null;
  versions: BiProductVersionOption[];
}

export interface BiPageSection {
  key: string;
  label: string;
  status: BiDataStatus;
  message: string;
}

export interface BiMetricTrace {
  metricIds: string[];
  sourcePlatform: string;
  sourceFields: Array<{ chineseName: string; englishName: string }>;
  formula: string;
  calculationOwner: string;
}

/** 单一来源快照内的分组维度，不代表跨平台业务实体 ID。 */
export interface BiSourceDimension {
  sourceValue: string | null;
  displayName: string;
  identified: boolean;
}

export interface BiPageResponse<T> {
  pageKey: BiPageKey;
  status: BiDataStatus;
  sourceVersion: string;
  snapshotId: string;
  ruleVersion: string;
  generatedAt: string;
  sections: BiPageSection[];
  traces: BiMetricTrace[];
  data: T | null;
}

export interface BiReviewPageData {
  summary: {
    reviewCount: number;
    reviewedPages: number | null;
    workloadHours: number | null;
    effectiveProblemCount: number | null;
    defectDensity: number | null;
    reviewRate: number | null;
    achieved: boolean | null;
  };
  categories: Array<{ category: string; count: number; sharePercent: number | null }>;
  modules: Array<{
    module: BiSourceDimension;
    reviewedPages: number;
    workloadHours: number | null;
    effectiveProblemCount: number;
    defectDensity: number | null;
    reviewRate: number | null;
    achieved: boolean | null;
  }>;
  reviewPoints: Array<{
    reviewId: number;
    reviewDate: string;
    module: BiSourceDimension;
    reviewRate: number | null;
    defectDensity: number | null;
    achieved: boolean | null;
  }>;
  moduleCoverage: BiMetricCoverage;
  reviewPointCoverage: BiMetricCoverage;
}

export interface BiMetricCoverage {
  totalObservations: number;
  validObservations: number;
  coveragePercent: number | null;
}

export interface BiCodingPageData {
  summary: {
    addedLines: number | null;
    addedKloc: number | null;
    mergeRequestCount: number | null;
    contributorCount: number | null;
    reviewDefectDensity: number | null;
    reviewSpeedLocPerHour: number | null;
    reviewDensityAchieved: boolean | null;
  };
  codeTrend: Array<{ period: string; addedLines: number; cumulativeLines: number }>;
  submissionTrend: Array<{ period: string; commitCount: number | null; mergeRequestCount: number }>;
  reviewCategories: Array<{ category: string; count: number; sharePercent: number | null }>;
  contributors: Array<{ contributor: BiSourceDimension; addedLines: number }>;
  moduleIncrements: Array<{ module: BiSourceDimension; addedLines: number }>;
  moduleReviewQuality: Array<{
    module: BiSourceDimension;
    defectDensity: number | null;
    reviewSpeedLocPerHour: number | null;
    achieved: boolean | null;
  }>;
  reviewPoints: Array<{
    codeReviewId: number;
    reviewDate: string;
    module: BiSourceDimension;
    reviewSpeedKlocPerHour: number | null;
    defectDensity: number | null;
    achieved: boolean | null;
  }>;
  commentRateTrend: Array<{ period: string; averageCommentRate: number | null }>;
  reviewDensityTrend: Array<{ period: string; reviewDefectDensity: number | null }>;
  commentRateCoverage: BiMetricCoverage;
  reviewDensityCoverage: BiMetricCoverage;
}

export interface BiTestAttainment {
  counts: {
    unit: 'TEST_CASE' | 'FUNCTION';
    attainedCount: number;
    totalCount: number;
  } | null;
  passRate: number | null;
  targetRate: number | null;
  achieved: boolean | null;
}

export interface BiTestQualityPageData {
  overall: BiTestAttainment | null;
  modules: Array<{ moduleId: string; moduleName: string; attainment: BiTestAttainment }>;
  functions: Array<{
    moduleId: string;
    functionId: string;
    functionName: string;
    attainment: BiTestAttainment;
  }>;
}

export interface BiSystemTestPageData {
  overview: { totalCount: number; fixedCount: number; openCount: number; fixRate: number | null };
  qualityTargets: Array<{
    key: string;
    label: string;
    totalCount: number | null;
    fixedCount: number | null;
    fixRate: number | null;
    targetRate: number | null;
    status: BiDataStatus;
    achieved: boolean | null;
  }>;
  rounds: Array<{
    roundId: string;
    roundName: string;
    roundOrder: number;
    levelOneCount: number;
    levelTwoCount: number;
    levelThreeCount: number;
    submittedCount: number;
    closedCount: number;
    openCount: number;
    closeRate: number | null;
  }>;
  severity: { levelOneCount: number; levelTwoCount: number; levelThreeCount: number } | null;
  modules: Array<{
    module: BiSourceDimension;
    totalCount: number;
    fixedCount: number;
    openCount: number;
    fixRate: number | null;
    levelOneCount: number;
    levelTwoCount: number;
    levelThreeCount: number;
    levelOneFixRate: number | null;
    p1FixRate: number | null;
    p2FixRate: number | null;
  }>;
  causeCategories: Array<{
    categoryId: string;
    categoryName: string;
    count: number;
    sharePercent: number | null;
  }>;
  causeSubcategories: Array<{
    categoryId: string;
    categoryName: string;
    subcategoryId: string;
    subcategoryName: string;
    count: number;
    sharePercent: number | null;
  }>;
  delays: Array<{ reason: string; severity: string; count: number }>;
  developers: Array<{
    assignee: BiSourceDimension;
    totalCount: number;
    fixedCount: number;
    openCount: number;
  }>;
}

export interface BiCustomerIssueMetric {
  key: string;
  label: string;
  value: number | null;
  numerator: number | null;
  denominator: number | null;
  percentage: number | null;
  unit: string;
}

export interface BiCustomerIssuePageData {
  milestones: Array<{ businessKey: string; displayName: string }>;
  selectedMilestoneBusinessKey: string;
  selectedMilestoneDisplayName: string;
  businessDate: string;
  customers: Array<{ kind: 'VALUE' | 'MISSING'; value: string | null; displayName: string }>;
  modules: Array<{ kind: 'VALUE' | 'MISSING'; value: string | null; displayName: string }>;
  functions: Array<{ kind: 'VALUE' | 'MISSING'; value: string | null; displayName: string }>;
  defectMetrics: BiCustomerIssueMetric[];
  requirementMetrics: BiCustomerIssueMetric[];
  moduleDefects: Array<{ module: string; fixedCount: number; unfixedCount: number; totalCount: number }>;
  severityDistribution: Array<{ severity: string; count: number }>;
  moduleSeverity: Array<{ module: string; severity: string; count: number }>;
  causeDistribution: Array<{ groupId: string; groupName: string; causeId: string; causeName: string; count: number }>;
  unclassifiedCauseCount: number;
  delayAnalysis: Array<{ reason: string; severity: string; count: number }>;
  assigneeWorkload: Array<{ assignee: string; fixedCount: number; unfixedCount: number; totalCount: number }>;
  moduleDemand: Array<{ module: string; resolvedCount: number; unresolvedCount: number; totalCount: number }>;
  dailyTrend: Array<{ date: string; createdCount: number | null; fixedCount: number | null }>;
  filteredFactCount: number;
}

export interface BiDownloadAuthorization {
  authorized: boolean;
  scope: BiDownloadScope;
  pageKey: BiPageKey;
  chartInstanceId: string;
  chartTemplateId: BiChartTemplateId;
  sourceVersion: string;
  rangeDescription: string;
}

export type BiStageData = BiReviewPageData | BiCodingPageData | BiTestQualityPageData | BiSystemTestPageData | BiCustomerIssuePageData;
