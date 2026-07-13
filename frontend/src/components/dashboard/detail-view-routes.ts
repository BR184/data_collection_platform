import type { LocationQueryRaw, RouteLocationRaw } from 'vue-router';
import { getPagePath, getPageRouteContract, type PageKey } from '../../feature-manifest';
import type { AnalyticsDashboardDetailAction } from '../../types/api';

interface AnalyticsDetailViewRouteDefinition {
  pageKey?: PageKey;
  allowedQueryKeys: readonly string[];
}

const detailViewRouteByKey: Readonly<Record<string, AnalyticsDetailViewRouteDefinition>> = {
  'review-data-records': {
    pageKey: 'review-data-home',
    allowedQueryKeys: ['projectName', 'moduleName', 'reviewOwner', 'reviewType', 'problemStatus', 'reviewExpert'],
  },
  'code-review-records': {
    pageKey: 'code-review-illegal-records',
    allowedQueryKeys: [
      'repositoryName',
      'projectName',
      'moduleName',
      'mergeRequestIid',
      'owner',
      'source',
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
    ],
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
    allowedQueryKeys: [
      'projectId',
      'projectName',
      'testingPhase',
      'assigneeName',
      'page',
      'size',
      'sortField',
      'sortOrder',
    ],
  },
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

  return {
    name: 'analytics-dashboard-detail',
    params: { dashboardKey, detailViewKey: action.viewKey },
    query: sanitizeQuery(values, definition.allowedQueryKeys),
  };
}
