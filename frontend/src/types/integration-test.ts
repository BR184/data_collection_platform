export interface FactBuildResponse {
  scope: string;
  full: boolean;
  affectedRows: number;
  message: string;
}

export interface IntegrationTestProjectOptionResponse {
  projectId: number;
  projectName: string;
}

export interface IntegrationTestPhaseOptionResponse {
  projectId: number;
  projectName: string;
  testingPhase: string;
  recordCount: number;
}

export interface IntegrationTestSummaryRowResponse {
  moduleName: string;
  issueCount: number;
  executeCase: number;
  passCase: number;
  notPassCase: number;
  notPassCaseNow: number;
  problemCase: number;
  exceptionCount: number;
  passRate: string | number;
  illegalCount: number;
}

export interface IntegrationTestSummaryResponse {
  projectId?: number | null;
  testingPhase?: string | null;
  moduleCount: number;
  totalIssueCount: number;
  factRefreshedAt?: string | null;
  rows: IntegrationTestSummaryRowResponse[];
}

export interface IntegrationTestDetailRowResponse {
  issueId: number;
  issueIid: number;
  issuableReference: string;
  projectId: number;
  projectName: string;
  title: string;
  moduleName: string;
  functionName?: string | null;
  functionLabels?: string | null;
  executor?: string | null;
  executeCase?: number | null;
  passCase?: number | null;
  notPassCase?: number | null;
  notPassCaseNow?: number | null;
  problemCase?: number | null;
  exceptionCount?: number | null;
  passRate?: string | number | null;
  legal?: boolean | null;
  parseStatus?: string | null;
  validationReason?: string | null;
  issueState?: string | null;
  authorName?: string | null;
  assigneeName?: string | null;
  noteUpdatedAt?: string | null;
  updatedAtSource?: string | null;
}

export interface IntegrationTestDetailResponse {
  records: IntegrationTestDetailRowResponse[];
  total: number;
  page: number;
  size: number;
  sortField: string;
  sortOrder: 'asc' | 'desc';
}
