import { pageByKey, pageModuleKeyByPageKey } from './lookups';
import type { PageKey, PageRouteContract, RouteMetaOverrides, SpecialRouteKey } from './types';
import { CUSTOMER_ISSUE_RECORD_QUERY_KEYS } from './customer-issue-record-query-contract';

interface SpecialRouteContract {
  basePageKey: PageKey;
  overrides: RouteMetaOverrides;
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
  'projectName',
  'testingPhase',
];

const customerIssueStatisticBoardQueryKeys = statisticBoardQueryKeys
  .filter((key) => !['projectId', 'projectName', 'testingPhase'].includes(key))
  .concat('milestoneTitle');

export const analyticsDashboardDetailQueryKeys = [
  'projectId',
  'projectName',
  'testingPhase',
  'milestoneTitle',
  'codeReviewSource',
  'source',
  'personName',
  'reviewerName',
  'authorName',
  'assigneeName',
  'fixUser',
  'moduleName',
  'severityLevel',
  'priorityLevel',
  'pointKey',
  'topic',
  'functionCountProjectName',
  'functionDensityProjectName',
  'qualityRankingProjectName',
  'memberUnresolvedProjectName',
  'page',
  'size',
  'sortField',
  'sortOrder',
];

const pageRouteContractByKey: Partial<Record<PageKey, PageRouteContract>> = {
  'bi-dashboard-requirements': {
    allowedQueryKeys: ['productVersionId'],
    persistedQueryKeys: [],
    inheritedQueryKeys: ['productVersionId'],
  },
  'bi-dashboard-design': {
    allowedQueryKeys: ['productVersionId'],
    persistedQueryKeys: [],
    inheritedQueryKeys: ['productVersionId'],
  },
  'bi-dashboard-coding': {
    allowedQueryKeys: ['productVersionId', 'granularity', 'source', 'repositoryId'],
    persistedQueryKeys: [],
    inheritedQueryKeys: ['productVersionId'],
  },
  'bi-dashboard-unit-test': {
    allowedQueryKeys: ['productVersionId'],
    persistedQueryKeys: [],
    inheritedQueryKeys: ['productVersionId'],
  },
  'bi-dashboard-integration-test': {
    allowedQueryKeys: ['productVersionId'],
    persistedQueryKeys: [],
    inheritedQueryKeys: ['productVersionId'],
  },
  'bi-dashboard-system-test': {
    allowedQueryKeys: ['productVersionId'],
    persistedQueryKeys: [],
    inheritedQueryKeys: ['productVersionId'],
  },
  'review-data-home': {
    allowedQueryKeys: [
      'page',
      'pageSize',
      'sortBy',
      'sortOrder',
      'sourceInstance',
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
      'source',
    ],
    allowedQueryPrefixes: ['filters.'],
    persistedQueryKeys: [],
  },
  'code-review-multi-board': {
    allowedQueryKeys: ['source', 'projectName'],
    persistedQueryKeys: [],
  },
  'question-metrics-multi-board': {
    allowedQueryKeys: ['projectId', 'projectName', 'testingPhase'],
    persistedQueryKeys: [],
  },
  'question-metrics-home': {
    allowedQueryKeys: statisticBoardQueryKeys,
    allowedQueryPrefixes: ['filters.'],
    persistedQueryKeys: [],
    boardKey: 'system-test-defect-summary',
  },
  'question-metrics-delay-analysis': {
    allowedQueryKeys: statisticBoardQueryKeys,
    allowedQueryPrefixes: ['filters.'],
    persistedQueryKeys: [],
    boardKey: 'system-test-delay-analysis',
  },
  'question-metrics-defect-cause': {
    allowedQueryKeys: statisticBoardQueryKeys,
    allowedQueryPrefixes: ['filters.'],
    persistedQueryKeys: [],
    boardKey: 'system-test-defect-cause',
  },
  'question-metrics-phase-statistics': {
    allowedQueryKeys: statisticBoardQueryKeys,
    allowedQueryPrefixes: ['filters.'],
    persistedQueryKeys: [],
    boardKey: 'system-test-phase-statistics',
  },
  'question-metrics-issue-search': {
    allowedQueryKeys: [
      'projectId',
      'page',
      'pageSize',
      'sortBy',
      'sortOrder',
      'sourceInstance',
      'issueIid',
      'title',
      'projectName',
      'moduleName',
      'functionName',
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
      'filterGroup',
      'filterLogic',
    ],
    allowedQueryPrefixes: ['filters.'],
    persistedQueryKeys: [],
  },
  'question-metrics-illegal-records': {
    allowedQueryKeys: [
      'page',
      'pageSize',
      'sortBy',
      'sortOrder',
      'keyword',
      'issueIid',
      'title',
      'projectName',
      'moduleName',
      'testingPhase',
      'illegalReason',
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
      'filterGroup',
      'filterLogic',
    ],
    allowedQueryPrefixes: ['filters.'],
    persistedQueryKeys: [],
  },
  'customer-issues-home': {
    allowedQueryKeys: customerIssueStatisticBoardQueryKeys,
    allowedQueryPrefixes: ['filters.'],
    persistedQueryKeys: [],
    boardKey: 'customer-issue-defect-summary',
  },
  'customer-issues-illegal-records': {
    allowedQueryKeys: [
      'page',
      'pageSize',
      'sortBy',
      'sortOrder',
      'keyword',
      'issueIid',
      'title',
      'projectName',
      'moduleName',
      'illegalReason',
      'authorName',
      'assigneeName',
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
      'filterGroup',
      'filterLogic',
    ],
    allowedQueryPrefixes: ['filters.'],
    persistedQueryKeys: [],
  },
  'customer-issues-defect-cause': {
    allowedQueryKeys: customerIssueStatisticBoardQueryKeys,
    allowedQueryPrefixes: ['filters.'],
    persistedQueryKeys: [],
    boardKey: 'customer-issue-defect-cause',
  },
  'customer-issues-cc-product-issues': {
    allowedQueryKeys: CUSTOMER_ISSUE_RECORD_QUERY_KEYS,
    allowedQueryPrefixes: ['filters.'],
    persistedQueryKeys: [],
  },
  'customer-issues-delay-issues': {
    allowedQueryKeys: customerIssueStatisticBoardQueryKeys,
    allowedQueryPrefixes: ['filters.'],
    persistedQueryKeys: [],
    boardKey: 'customer-issue-delay-issues',
  },
  'customer-issues-response-efficiency': {
    allowedQueryKeys: customerIssueStatisticBoardQueryKeys,
    allowedQueryPrefixes: ['filters.'],
    persistedQueryKeys: [],
    boardKey: 'customer-issue-response-efficiency',
  },
  'customer-issues-issue-by-function': {
    allowedQueryKeys: customerIssueStatisticBoardQueryKeys,
    allowedQueryPrefixes: ['filters.'],
    persistedQueryKeys: [],
    boardKey: 'customer-issue-by-function',
  },
  'mirror-settings': {
    persistedQueryKeys: ['projectId'],
  },
  'database-settings': {
    persistedQueryKeys: ['projectId'],
  },
  'label-group-settings': {
    allowedQueryKeys: ['valueType', 'keyword', 'projectId'],
    persistedQueryKeys: ['projectId'],
  },
  'testing-phase-definition': {
    allowedQueryKeys: ['projectId', 'keyword', 'enabled'],
    persistedQueryKeys: ['projectId'],
  },
  'database-browser': {
    allowedQueryKeys: ['table', 'keyword', 'page', 'pageSize', 'sortBy', 'sortOrder', 'projectId'],
    persistedQueryKeys: ['projectId'],
  },
  'permission-settings': {
    allowedQueryKeys: ['role'],
    persistedQueryKeys: [],
  },
  'dropdown-option-settings': {
    allowedQueryKeys: ['field'],
    persistedQueryKeys: [],
  },
};

const specialRouteContractByKey: Record<SpecialRouteKey, SpecialRouteContract> = {
  'external-code-review-form': {
    basePageKey: 'code-review-illegal-records',
    overrides: {
      title: '代码走查表单',
      description: '通过独立链接打开的代码走查表单页面。',
      standalone: true,
      allowedQueryKeys: ['gitlabBaseUrl', 'projectId', 'mrIid'],
      persistedQueryKeys: [],
    },
  },
  'code-review-illegal-rule-config': {
    basePageKey: 'code-review-illegal-records',
    overrides: {
      title: '代码走查规则配置',
      description: '配置当前用户自己的代码走查判定规则，并即时查看结果预览。',
    },
  },
  'not-found': {
    basePageKey: 'quality-board-rd-quality-board',
    overrides: {
      title: '页面不存在',
      description: '访问的地址无效，已为你保留平台基础壳子。',
      allowedQueryKeys: [],
      allowedQueryPrefixes: [],
      persistedQueryKeys: [],
    },
  },
};

export function getPageRouteContract(pageKey: PageKey) {
  return pageRouteContractByKey[pageKey] ?? {};
}

export function getSpecialRouteContract(routeKey: SpecialRouteKey) {
  return specialRouteContractByKey[routeKey];
}

export function getPagePath(pageKey: PageKey) {
  return pageByKey.get(pageKey)?.path ?? null;
}

export function getPageModuleKey(pageKey: PageKey) {
  return pageModuleKeyByPageKey.get(pageKey) ?? null;
}

export function getStatisticBoardKey(pageKey?: PageKey | null) {
  if (!pageKey) {
    return null;
  }
  return pageRouteContractByKey[pageKey]?.boardKey ?? null;
}

export function buildPageRouteMeta(
  pageKey: PageKey,
  overrides: Partial<RouteMetaOverrides> = {},
) {
  const page = pageByKey.get(pageKey);
  const moduleKey = getPageModuleKey(pageKey);
  if (!page) {
    throw new Error(`Unknown page key: ${pageKey}`);
  }
  if (!moduleKey) {
    throw new Error(`Unknown module key for page: ${pageKey}`);
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
    inheritedQueryKeys: overrides.inheritedQueryKeys ?? contract.inheritedQueryKeys,
    standalone: overrides.standalone ?? contract.standalone,
  };
}

export function buildSpecialRouteMeta(routeKey: SpecialRouteKey) {
  const routeContract = getSpecialRouteContract(routeKey);
  return buildPageRouteMeta(routeContract.basePageKey, routeContract.overrides);
}
