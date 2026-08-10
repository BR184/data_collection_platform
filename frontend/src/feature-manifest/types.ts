export type ModuleKey =
  | 'quality-board'
  | 'bi-dashboard'
  | 'review-data'
  | 'code-review'
  | 'question-metrics'
  | 'customer-issues'
  | 'system-settings';

export type PageKey =
  | 'quality-board-rd-quality-board'
  | 'quality-board-other-board'
  | 'bi-dashboard-requirements'
  | 'bi-dashboard-design'
  | 'bi-dashboard-coding'
  | 'bi-dashboard-unit-test'
  | 'bi-dashboard-integration-test'
  | 'bi-dashboard-system-test'
  | 'review-data-home'
  | 'code-review-home'
  | 'code-review-illegal-records'
  | 'code-review-multi-board'
  | 'question-metrics-home'
  | 'question-metrics-multi-board'
  | 'question-metrics-delay-analysis'
  | 'question-metrics-illegal-records'
  | 'question-metrics-defect-cause'
  | 'question-metrics-phase-statistics'
  | 'question-metrics-issue-search'
  | 'customer-issues-home'
  | 'customer-issues-illegal-records'
  | 'customer-issues-defect-cause'
  | 'customer-issues-cc-product-issues'
  | 'customer-issues-delay-issues'
  | 'customer-issues-response-efficiency'
  | 'customer-issues-issue-by-function'
  | 'label-group-settings'
  | 'testing-phase-definition'
  | 'mirror-settings'
  | 'database-settings'
  | 'database-browser'
  | 'permission-settings';

export interface ShellPage {
  key: PageKey;
  label: string;
  icon: unknown;
  description: string;
  path: string;
  requiresLogin?: boolean;
  hiddenForApproval?: boolean;
  permission?: string;
}

export interface ShellModule {
  key: ModuleKey;
  label: string;
  icon: unknown;
  title: string;
  description: string;
  pages: ShellPage[];
}

export interface AccessUser {
  roleCodes?: string[];
  roleNames?: string[];
  permissions?: string[];
  authenticated: boolean;
}

export interface PageRouteContract {
  allowedQueryKeys?: string[];
  allowedQueryPrefixes?: string[];
  persistedQueryKeys?: string[];
  inheritedQueryKeys?: string[];
  boardKey?: string;
  standalone?: boolean;
}

export type SpecialRouteKey =
  | 'external-code-review-form'
  | 'code-review-illegal-rule-config'
  | 'not-found';

export interface RouteMetaOverrides extends PageRouteContract {
  title: string;
  description: string;
}
