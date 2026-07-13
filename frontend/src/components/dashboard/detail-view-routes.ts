import type { LocationQueryRaw, RouteLocationRaw } from 'vue-router';
import { getPagePath, getPageRouteContract, type PageKey } from '../../feature-manifest';
import type { AnalyticsDashboardDetailAction } from '../../types/api';

interface AnalyticsDetailViewRouteDefinition {
  pageKey?: PageKey;
  internalOwnerPageKey?: PageKey;
  internalDashboardKey?: string;
  allowedQueryKeys: readonly string[];
}

const statisticBoardDetailQueryKeys = [
  'projectId',
  'projectName',
  'testingPhase',
  'detailVisible',
  'detailRowKey',
  'detailColumnKey',
  'detailPage',
  'detailPageSize',
  'detailSortBy',
  'detailSortOrder',
  'filterGroup',
  'filterLogic',
] as const;

const detailViewRouteByKey: Readonly<Record<string, AnalyticsDetailViewRouteDefinition>> = {
  'review-data-records': {
    pageKey: 'review-data-home',
    allowedQueryKeys: ['projectName', 'moduleName', 'reviewOwner', 'reviewType', 'problemStatus', 'reviewExpert'],
  },
  'quality-code-review-records': {
    internalOwnerPageKey: 'quality-board-rd-quality-board',
    internalDashboardKey: 'quality-rd',
    allowedQueryKeys: [
      'projectName',
      'source',
      'topic',
      'reviewerName',
      'authorName',
      'page',
      'size',
      'sortField',
      'sortOrder',
    ],
  },
  'system-test-issue-records': {
    pageKey: 'question-metrics-issue-search',
    allowedQueryKeys: [
      'projectId',
      'projectName',
      'testingPhase',
      'moduleName',
      'assigneeName',
      'authorName',
      'severityLevel',
      'issueState',
      'bugStatus',
      'category',
      'filterGroup',
      'filterLogic',
    ],
  },
  'system-test-defect-summary': {
    pageKey: 'question-metrics-home',
    allowedQueryKeys: statisticBoardDetailQueryKeys,
  },
  'system-test-phase-statistics': {
    pageKey: 'question-metrics-phase-statistics',
    allowedQueryKeys: statisticBoardDetailQueryKeys,
  },
  'system-test-defect-cause': {
    pageKey: 'question-metrics-defect-cause',
    allowedQueryKeys: statisticBoardDetailQueryKeys,
  },
  'system-test-delay-analysis': {
    pageKey: 'question-metrics-delay-analysis',
    allowedQueryKeys: statisticBoardDetailQueryKeys,
  },
  'customer-issue-records': {
    pageKey: 'customer-issues-cc-product-issues',
    allowedQueryKeys: [
      'projectName',
      'milestoneTitle',
      'moduleName',
      'functionName',
      'assigneeName',
      'authorName',
      'severityLevel',
      'priorityLevel',
      'issueState',
      'bugStatus',
      'category',
    ],
  },
  'assignee-remaining-defects': {
    internalOwnerPageKey: 'quality-board-rd-quality-board',
    internalDashboardKey: 'quality-rd',
    allowedQueryKeys: [
      'projectId',
      'projectName',
      'assigneeName',
      'page',
      'size',
      'sortField',
      'sortOrder',
    ],
  },
  'integration-test-results': {
    internalOwnerPageKey: 'quality-board-rd-quality-board',
    internalDashboardKey: 'quality-rd',
    allowedQueryKeys: ['projectName', 'page', 'size', 'sortField', 'sortOrder'],
  },
  'release-leakage-defects': {
    internalOwnerPageKey: 'quality-board-rd-quality-board',
    internalDashboardKey: 'quality-rd',
    allowedQueryKeys: ['projectName', 'page', 'size', 'sortField', 'sortOrder'],
  },
  'development-leakage-defects': {
    internalOwnerPageKey: 'quality-board-rd-quality-board',
    internalDashboardKey: 'quality-rd',
    allowedQueryKeys: ['projectName', 'page', 'size', 'sortField', 'sortOrder'],
  },
  'fix-user-defects': {
    internalOwnerPageKey: 'quality-board-rd-quality-board',
    internalDashboardKey: 'quality-rd',
    allowedQueryKeys: [
      'projectName',
      'fixUser',
      'severityLevel',
      'page',
      'size',
      'sortField',
      'sortOrder',
    ],
  },
  'other-function-defect-count': {
    internalOwnerPageKey: 'quality-board-other-board',
    internalDashboardKey: 'quality-board-other',
    allowedQueryKeys: ['functionCountProjectName', 'pointKey', 'page', 'size', 'sortField', 'sortOrder'],
  },
  'other-function-defect-density': {
    internalOwnerPageKey: 'quality-board-other-board',
    internalDashboardKey: 'quality-board-other',
    allowedQueryKeys: ['functionDensityProjectName', 'pointKey', 'page', 'size', 'sortField', 'sortOrder'],
  },
  'other-quality-ranking': {
    internalOwnerPageKey: 'quality-board-other-board',
    internalDashboardKey: 'quality-board-other',
    allowedQueryKeys: ['qualityRankingProjectName', 'pointKey', 'page', 'size', 'sortField', 'sortOrder'],
  },
  'other-member-unresolved-rate': {
    internalOwnerPageKey: 'quality-board-other-board',
    internalDashboardKey: 'quality-board-other',
    allowedQueryKeys: ['memberUnresolvedProjectName', 'pointKey', 'page', 'size', 'sortField', 'sortOrder'],
  },
  'other-release-leakage-rate': {
    internalOwnerPageKey: 'quality-board-other-board',
    internalDashboardKey: 'quality-board-other',
    allowedQueryKeys: ['pointKey', 'page', 'size', 'sortField', 'sortOrder'],
  },
  'other-development-leakage-rate': {
    internalOwnerPageKey: 'quality-board-other-board',
    internalDashboardKey: 'quality-board-other',
    allowedQueryKeys: ['pointKey', 'page', 'size', 'sortField', 'sortOrder'],
  },
  'code-review-statistics': {
    internalOwnerPageKey: 'code-review-multi-board',
    internalDashboardKey: 'code-review-multi',
    allowedQueryKeys: ['source', 'projectName', 'topic', 'pointKey', 'page', 'size', 'sortField', 'sortOrder'],
  },
};

const internalDetailRouteNameByPageKey: Partial<Record<PageKey, string>> = {
  'quality-board-rd-quality-board': 'quality-rd-analytics-detail',
  'quality-board-other-board': 'quality-other-analytics-detail',
  'code-review-multi-board': 'code-review-analytics-detail',
};

function sanitizeQuery(
  values: Record<string, string>,
  allowedKeys: readonly string[],
): LocationQueryRaw {
  return Object.fromEntries(
    Object.entries(values).filter(([key, value]) => Boolean(value) && allowedKeys.includes(key)),
  );
}

/** Every detail target declares an exact query allowlist; unregistered views never fall back. */
export function buildAnalyticsDetailRoute(
  dashboardKey: string,
  action: AnalyticsDashboardDetailAction,
  inheritedParameters: Record<string, string> = {},
): RouteLocationRaw {
  const definition = detailViewRouteByKey[action.viewKey];
  if (!definition) {
    throw new Error(`未注册的看板详情视图: ${action.viewKey}`);
  }
  const values = { ...inheritedParameters, ...action.params };
  if (definition.pageKey) {
    const path = getPagePath(definition.pageKey);
    if (!path) {
      throw new Error(`未注册的详情页面: ${definition.pageKey}`);
    }
    const routeContract = getPageRouteContract(definition.pageKey);
    const destinationKeys = routeContract.allowedQueryKeys ?? [];
    const allowedKeys = definition.allowedQueryKeys.filter((key) => destinationKeys.includes(key));
    return { path, query: sanitizeQuery(values, allowedKeys) };
  }

  if (!definition.internalOwnerPageKey || !definition.internalDashboardKey) {
    throw new Error(`详情视图缺少页面归属: ${action.viewKey}`);
  }
  if (definition.internalDashboardKey !== dashboardKey) {
    throw new Error(`详情视图不属于当前看板: ${action.viewKey}`);
  }
  const routeName = internalDetailRouteNameByPageKey[definition.internalOwnerPageKey];
  if (!routeName) {
    throw new Error(`详情页面归属未注册: ${definition.internalOwnerPageKey}`);
  }
  return {
    name: routeName,
    params: { detailViewKey: action.viewKey },
    query: sanitizeQuery(values, definition.allowedQueryKeys),
  };
}
