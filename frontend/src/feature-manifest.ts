import { pageByKey, type ModuleKey, type PageKey } from './navigation';

export interface PageRouteContract {
  allowedQueryKeys?: string[];
  allowedQueryPrefixes?: string[];
  persistedQueryKeys?: string[];
  boardKey?: string;
  standalone?: boolean;
}

const statisticBoardQueryKeys = [
  'sortBy',
  'sortOrder',
  'tablePage',
  'tablePageSize',
  'detailPage',
  'detailPageSize',
  'detailSortBy',
  'detailSortOrder',
  'detailVisible',
  'detailRowKey',
  'detailColumnKey',
  'filterGroup',
  'filterLogic',
  'projectId',
];

const customerIssueRecordQueryKeys = [
  'page',
  'pageSize',
  'sortBy',
  'sortOrder',
  'projectId',
  'keyword',
  'issueIid',
  'title',
  'projectName',
  'moduleName',
  'reasonCategory',
  'severityLevel',
  'priorityLevel',
  'issueState',
  'bugStatus',
  'category',
  'milestoneTitle',
  'createdAtStart',
  'createdAtEnd',
  'updatedAtStart',
  'updatedAtEnd',
];

const pageRouteContractByKey: Partial<Record<PageKey, PageRouteContract>> = {
  'quality-board-home': {
    allowedQueryKeys: statisticBoardQueryKeys,
    allowedQueryPrefixes: ['filters.'],
    persistedQueryKeys: ['projectId'],
    boardKey: 'mirror-table-overview',
  },
  'review-data-home': {
    allowedQueryKeys: [
      'page',
      'pageSize',
      'sortBy',
      'sortOrder',
      'keyword',
      'filterGroup',
      'filterLogic',
      'title',
      'projectName',
      'moduleName',
      'reviewOwner',
      'reviewType',
      'problemStatus',
      'reviewExpert',
    ],
    allowedQueryPrefixes: ['filters.'],
    persistedQueryKeys: ['projectId'],
  },
  'code-review-illegal-records': {
    allowedQueryKeys: [
      'page',
      'pageSize',
      'sortBy',
      'sortOrder',
      'projectId',
      'filterGroup',
      'filterLogic',
      'repositoryName',
      'mergedAtStart',
      'mergedAtEnd',
      'keyword',
      'projectName',
      'requestType',
      'targetBranch',
      'mergedBy',
      'moduleName',
      'illegalType',
      'mergeRequestIid',
      'owner',
    ],
    allowedQueryPrefixes: ['filters.'],
    persistedQueryKeys: ['projectId'],
  },
  'question-metrics-home': {
    allowedQueryKeys: statisticBoardQueryKeys,
    allowedQueryPrefixes: ['filters.'],
    persistedQueryKeys: ['projectId'],
    boardKey: 'system-test-defect-summary',
  },
  'question-metrics-delay-analysis': {
    allowedQueryKeys: statisticBoardQueryKeys,
    allowedQueryPrefixes: ['filters.'],
    persistedQueryKeys: ['projectId'],
    boardKey: 'system-test-delay-analysis',
  },
  'question-metrics-defect-cause': {
    allowedQueryKeys: statisticBoardQueryKeys,
    allowedQueryPrefixes: ['filters.'],
    persistedQueryKeys: ['projectId'],
    boardKey: 'system-test-defect-cause',
  },
  'question-metrics-phase-statistics': {
    allowedQueryKeys: statisticBoardQueryKeys,
    allowedQueryPrefixes: ['filters.'],
    persistedQueryKeys: ['projectId'],
    boardKey: 'system-test-phase-statistics',
  },
  'question-metrics-issue-search': {
    allowedQueryKeys: [
      'page',
      'pageSize',
      'sortBy',
      'sortOrder',
      'projectId',
      'keyword',
      'issueIid',
      'title',
      'projectName',
      'moduleName',
      'testingPhase',
      'authorName',
      'assigneeName',
      'issueState',
      'severityLevel',
      'bugStatus',
      'category',
      'milestoneTitle',
      'createdAtStart',
      'createdAtEnd',
      'updatedAtStart',
      'updatedAtEnd',
    ],
    persistedQueryKeys: ['projectId'],
  },
  'customer-issues-home': {
    allowedQueryKeys: statisticBoardQueryKeys,
    allowedQueryPrefixes: ['filters.'],
    persistedQueryKeys: ['projectId'],
    boardKey: 'customer-issue-defect-summary',
  },
  'customer-issues-illegal-records': {
    allowedQueryKeys: [
      'page',
      'pageSize',
      'sortBy',
      'sortOrder',
      'projectId',
      'keyword',
      'issueIid',
      'title',
      'projectName',
      'moduleName',
      'illegalReason',
      'severityLevel',
      'priorityLevel',
      'issueState',
      'bugStatus',
      'category',
      'milestoneTitle',
      'createdAtStart',
      'createdAtEnd',
      'updatedAtStart',
      'updatedAtEnd',
    ],
    persistedQueryKeys: ['projectId'],
  },
  'customer-issues-defect-cause': {
    allowedQueryKeys: statisticBoardQueryKeys,
    allowedQueryPrefixes: ['filters.'],
    persistedQueryKeys: ['projectId'],
    boardKey: 'customer-issue-defect-cause',
  },
  'customer-issues-cc-product-issues': {
    allowedQueryKeys: customerIssueRecordQueryKeys,
    persistedQueryKeys: ['projectId'],
  },
  'customer-issues-delay-issues': {
    allowedQueryKeys: customerIssueRecordQueryKeys,
    persistedQueryKeys: ['projectId'],
  },
  'customer-issues-response-efficiency': {
    allowedQueryKeys: statisticBoardQueryKeys,
    allowedQueryPrefixes: ['filters.'],
    persistedQueryKeys: ['projectId'],
    boardKey: 'customer-issue-response-efficiency',
  },
  'customer-issues-issue-by-function': {
    allowedQueryKeys: statisticBoardQueryKeys,
    allowedQueryPrefixes: ['filters.'],
    persistedQueryKeys: ['projectId'],
    boardKey: 'customer-issue-by-function',
  },
  'mirror-settings': {
    persistedQueryKeys: ['projectId'],
  },
  'database-browser': {
    allowedQueryKeys: ['table', 'keyword', 'page', 'pageSize', 'sortBy', 'sortOrder', 'projectId'],
    persistedQueryKeys: ['projectId'],
  },
  'testing-phase-definition': {
    allowedQueryKeys: ['projectId', 'keyword', 'enabled'],
    persistedQueryKeys: ['projectId'],
  },
};

export function getPageRouteContract(pageKey: PageKey) {
  return pageRouteContractByKey[pageKey] ?? {};
}

export function getStatisticBoardKey(pageKey?: PageKey | null) {
  if (!pageKey) {
    return null;
  }
  return pageRouteContractByKey[pageKey]?.boardKey ?? null;
}

export function buildPageRouteMeta(
  moduleKey: ModuleKey,
  pageKey: PageKey,
  overrides: Partial<PageRouteContract & { title: string; description: string }> = {},
) {
  const page = pageByKey.get(pageKey);
  if (!page) {
    throw new Error(`Unknown page key: ${pageKey}`);
  }
  const contract = getPageRouteContract(pageKey);
  return {
    moduleKey,
    pageKey,
    title: overrides.title ?? page.label,
    description: overrides.description ?? page.description,
    allowedQueryKeys: overrides.allowedQueryKeys ?? contract.allowedQueryKeys,
    allowedQueryPrefixes: overrides.allowedQueryPrefixes ?? contract.allowedQueryPrefixes,
    persistedQueryKeys: overrides.persistedQueryKeys ?? contract.persistedQueryKeys,
    standalone: overrides.standalone ?? contract.standalone,
  };
}
