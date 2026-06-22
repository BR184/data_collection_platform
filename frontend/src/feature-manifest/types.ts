export type ModuleKey =
  | 'quality-board'
  | 'review-data'
  | 'code-review'
  | 'question-metrics'
  | 'customer-issues'
  | 'system-settings';

export type PageKey =
  | 'quality-board-rd-quality-board'
  | 'quality-board-other-board'
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
  | 'question-metrics-testing-phase-definition'
  | 'customer-issues-home'
  | 'customer-issues-illegal-records'
  | 'customer-issues-defect-cause'
  | 'customer-issues-cc-product-issues'
  | 'customer-issues-delay-issues'
  | 'customer-issues-response-efficiency'
  | 'customer-issues-issue-by-function'
  | 'label-group-settings'
  | 'mirror-settings'
  | 'database-browser';

export interface ShellPage {
  key: PageKey;
  label: string;
  description: string;
  path: string;
  requiresLogin?: boolean;
  hiddenForApproval?: boolean;
}

export interface ShellModule {
  key: ModuleKey;
  label: string;
  icon: unknown;
  title: string;
  description: string;
  pages: ShellPage[];
}

export type UserRole = 'GUEST' | 'ADMIN' | 'APPROVAL';

export interface AccessUser {
  role: UserRole;
  authenticated: boolean;
}

export interface PageRouteContract {
  allowedQueryKeys?: string[];
  allowedQueryPrefixes?: string[];
  persistedQueryKeys?: string[];
  boardKey?: string;
  standalone?: boolean;
}

export type SpecialRouteKey = 'external-code-review-form' | 'code-review-illegal-rule-config' | 'not-found';

export interface RouteMetaOverrides extends PageRouteContract {
  title: string;
  description: string;
}
