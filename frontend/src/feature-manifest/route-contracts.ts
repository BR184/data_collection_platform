import { pageByKey, pageModuleKeyByPageKey } from './lookups';
import type { PageKey, PageRouteContract, RouteMetaOverrides, SpecialRouteKey } from './types';

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
  'functionName',
  'reasonCategory',
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
];

const pageRouteContractByKey: Partial<Record<PageKey, PageRouteContract>> = {
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
      'source',
    ],
    allowedQueryPrefixes: ['filters.'],
    persistedQueryKeys: ['projectId'],
  },
  'code-review-multi-board': {
    allowedQueryKeys: ['source'],
    persistedQueryKeys: [],
  },
  'question-metrics-multi-board': {
    allowedQueryKeys: ['projectName'],
    persistedQueryKeys: [],
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
      'sourceInstance',
      'searchType',
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
      'filterGroup',
      'filterLogic',
    ],
    allowedQueryPrefixes: ['filters.'],
    persistedQueryKeys: ['projectId'],
  },
  'question-metrics-illegal-records': {
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
      'filterGroup',
      'filterLogic',
    ],
    allowedQueryPrefixes: ['filters.'],
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
    allowedQueryPrefixes: ['filters.'],
    persistedQueryKeys: ['projectId'],
  },
  'customer-issues-delay-issues': {
    allowedQueryKeys: customerIssueRecordQueryKeys,
    allowedQueryPrefixes: ['filters.'],
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
  'label-group-settings': {
    allowedQueryKeys: ['valueType', 'keyword', 'projectId'],
    persistedQueryKeys: ['projectId'],
  },
  'database-browser': {
    allowedQueryKeys: ['table', 'keyword', 'page', 'pageSize', 'sortBy', 'sortOrder', 'projectId'],
    persistedQueryKeys: ['projectId'],
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
    standalone: overrides.standalone ?? contract.standalone,
  };
}

export function buildSpecialRouteMeta(routeKey: SpecialRouteKey) {
  const routeContract = getSpecialRouteContract(routeKey);
  return buildPageRouteMeta(routeContract.basePageKey, routeContract.overrides);
}
