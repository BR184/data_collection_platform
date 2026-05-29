import { createRouter, createWebHashHistory, type RouteLocationNormalized, type RouteRecordRaw } from 'vue-router';
import {
  buildPageRouteMeta,
  buildSpecialRouteMeta,
  canAccessPageKey,
  getFirstAccessiblePagePath,
  getPagePath,
  type AccessUser,
  type ModuleKey,
  type PageKey,
} from './feature-manifest';
import { authState, loadCurrentUser } from './composables/auth-state';
import { beginRouteLoading, clearRouteError, endRouteLoading, setRouteError } from './router-state';

const StatisticBoardPage = () => import('./views/StatisticBoardPage.vue');
const MirrorSettingsView = () => import('./views/MirrorSettingsView.vue');
const DatabaseBrowserView = () => import('./components/DatabaseBrowserView.vue');
const ModulePlaceholderView = () => import('./views/ModulePlaceholderView.vue');
const NotFoundView = () => import('./views/NotFoundView.vue');
const CollectFormView = () => import('./views/CollectFormView.vue');
const CodeReviewIllegalRecordsView = () => import('./views/CodeReviewIllegalRecordsView.vue');
const CodeReviewIllegalRuleConfigView = () => import('./views/CodeReviewIllegalRuleConfigView.vue');
const ReviewDataManagementView = () => import('./views/ReviewDataManagementView.vue');
const SystemTestIssueSearchView = () => import('./views/SystemTestIssueSearchView.vue');
const CustomerIssueIllegalRecordsView = () => import('./views/CustomerIssueIllegalRecordsView.vue');
const CustomerIssueRecordsView = () => import('./views/CustomerIssueRecordsView.vue');
const SystemTestIllegalRecordsView = () => import('./views/SystemTestIllegalRecordsView.vue');
const CodeReviewMultiBoardView = () => import('./views/CodeReviewMultiBoardView.vue');
const TestingPhaseDefinitionView = () => import('./views/TestingPhaseDefinitionView.vue');
const IntegrationTestAnalysisView = () => import('./views/IntegrationTestAnalysisView.vue');
const QualityBoardRdView = () => import('./views/QualityBoardRdView.vue');
const QualityBoardOtherView = () => import('./views/QualityBoardOtherView.vue');
const SystemTestMultiBoardView = () => import('./views/SystemTestMultiBoardView.vue');

type RouteComponent = NonNullable<RouteRecordRaw['component']>;
type QueryNormalizableRoute = Pick<RouteLocationNormalized, 'hash' | 'matched' | 'meta' | 'path' | 'query'>;
type AccessCheckRoute = Pick<RouteLocationNormalized, 'meta' | 'path'>;

declare module 'vue-router' {
  interface RouteMeta {
    moduleKey?: ModuleKey;
    pageKey?: PageKey;
    title?: string;
    description?: string;
    standalone?: boolean;
    allowedQueryKeys?: string[];
    allowedQueryPrefixes?: string[];
    persistedQueryKeys?: string[];
  }
}

function buildShellRoute(pageKey: PageKey, component: RouteComponent): RouteRecordRaw {
  const path = getPagePath(pageKey);
  if (!path) {
    throw new Error(`Missing path for page key: ${pageKey}`);
  }
  return {
    path,
    component,
    meta: buildPageRouteMeta(pageKey),
  };
}

function buildPlaceholderRoute(pageKey: PageKey): RouteRecordRaw {
  return buildShellRoute(pageKey, ModulePlaceholderView);
}

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: '/quality-board/rd-quality-board',
  },
  {
    path: '/quality-board/home',
    redirect: '/quality-board/rd-quality-board',
  },
  {
    path: '/external/code-review-form',
    component: CollectFormView,
    meta: buildSpecialRouteMeta('external-code-review-form'),
  },
  {
    ...buildShellRoute('quality-board-rd-quality-board', QualityBoardRdView),
  },
  {
    ...buildShellRoute('quality-board-other-board', QualityBoardOtherView),
  },
  {
    ...buildShellRoute('review-data-home', ReviewDataManagementView),
  },
  {
    path: '/code-review/home',
    redirect: '/code-review/illegal-records',
  },
  {
    ...buildShellRoute('code-review-illegal-records', CodeReviewIllegalRecordsView),
  },
  {
    path: '/code-review/illegal-records/rule-config',
    component: CodeReviewIllegalRuleConfigView,
    meta: buildSpecialRouteMeta('code-review-illegal-rule-config'),
  },
  {
    ...buildShellRoute('code-review-multi-board', CodeReviewMultiBoardView),
  },
  {
    ...buildShellRoute('integration-test-home', IntegrationTestAnalysisView),
  },
  {
    ...buildShellRoute('question-metrics-home', StatisticBoardPage),
  },
  {
    ...buildShellRoute('question-metrics-multi-board', SystemTestMultiBoardView),
  },
  {
    ...buildShellRoute('question-metrics-delay-analysis', StatisticBoardPage),
  },
  {
    ...buildShellRoute('question-metrics-illegal-records', SystemTestIllegalRecordsView),
  },
  {
    ...buildShellRoute('question-metrics-defect-cause', StatisticBoardPage),
  },
  {
    ...buildShellRoute('question-metrics-phase-statistics', StatisticBoardPage),
  },
  {
    ...buildShellRoute('question-metrics-issue-search', SystemTestIssueSearchView),
  },
  {
    ...buildShellRoute('customer-issues-home', StatisticBoardPage),
  },
  {
    ...buildShellRoute('customer-issues-illegal-records', CustomerIssueIllegalRecordsView),
  },
  {
    ...buildShellRoute('customer-issues-defect-cause', StatisticBoardPage),
  },
  {
    ...buildShellRoute('customer-issues-cc-product-issues', CustomerIssueRecordsView),
  },
  {
    ...buildShellRoute('customer-issues-delay-issues', CustomerIssueRecordsView),
  },
  {
    ...buildShellRoute('customer-issues-response-efficiency', StatisticBoardPage),
  },
  {
    ...buildShellRoute('customer-issues-issue-by-function', StatisticBoardPage),
  },
  {
    ...buildShellRoute('mirror-settings', MirrorSettingsView),
  },
  {
    ...buildShellRoute('database-browser', DatabaseBrowserView),
  },
  {
    ...buildShellRoute('testing-phase-definition', TestingPhaseDefinitionView),
  },
  {
    path: '/:pathMatch(.*)*',
    component: NotFoundView,
    meta: buildSpecialRouteMeta('not-found'),
  },
];

function sameStringArray(left: string[], right: string[]) {
  return left.length === right.length && left.every((value, index) => value === right[index]);
}

export function normalizeQuery(to: QueryNormalizableRoute, from?: QueryNormalizableRoute) {
  const allowedKeys = to.meta.allowedQueryKeys ?? [];
  const allowedPrefixes = to.meta.allowedQueryPrefixes ?? [];
  const persistedKeys = to.meta.persistedQueryKeys ?? [];
  const moduleChanged = from != null && from.meta.moduleKey !== to.meta.moduleKey;
  const currentEntries = Object.entries(to.query);
  const normalizedEntries: Array<[string, string | string[]]> = [];

  for (const [key, rawValue] of currentEntries) {
    if (key.startsWith('temp_')) {
      continue;
    }
    const keyAllowed = allowedKeys.includes(key) || allowedPrefixes.some((prefix) => key.startsWith(prefix));
    if (moduleChanged && !persistedKeys.includes(key) && !keyAllowed) {
      continue;
    }
    if (!keyAllowed && !persistedKeys.includes(key)) {
      continue;
    }
    if (rawValue == null) {
      continue;
    }
    if (Array.isArray(rawValue)) {
      const nextValue = rawValue.map((item) => String(item)).filter(Boolean);
      if (nextValue.length) {
        normalizedEntries.push([key, nextValue]);
      }
    } else {
      const nextValue = String(rawValue).trim();
      if (nextValue) {
        normalizedEntries.push([key, nextValue]);
      }
    }
  }

  const nextQuery = Object.fromEntries(normalizedEntries);

  for (const key of persistedKeys) {
    const currentValue = nextQuery[key];
    if (typeof currentValue === 'string' && currentValue) {
      sessionStorage.setItem(`route-query:${key}`, currentValue);
      continue;
    }
    const storedValue = sessionStorage.getItem(`route-query:${key}`);
    if (storedValue) {
      nextQuery[key] = storedValue;
    }
  }

  const currentKeys = Object.keys(to.query).sort();
  const nextKeys = Object.keys(nextQuery).sort();
  if (!sameStringArray(currentKeys, nextKeys)) {
    return nextQuery;
  }

  for (const key of nextKeys) {
    const currentValue = to.query[key];
    const normalizedValue = nextQuery[key];
    if (Array.isArray(currentValue) && Array.isArray(normalizedValue)) {
      if (!sameStringArray(currentValue.map(String), normalizedValue.map(String))) {
        return nextQuery;
      }
      continue;
    }
    if (String(currentValue) !== String(normalizedValue)) {
      return nextQuery;
    }
  }

  return null;
}

export function routeAccessRedirect(to: AccessCheckRoute, user: AccessUser) {
  if (to.meta.standalone) {
    return null;
  }
  const pageKey = to.meta.pageKey as PageKey | undefined;
  if (!pageKey) {
    return null;
  }
  if (canAccessPageKey(pageKey, user)) {
    return null;
  }
  const fallbackPath = getFirstAccessiblePagePath(user);
  return fallbackPath === to.path ? null : fallbackPath;
}

const router = createRouter({
  history: createWebHashHistory(),
  routes,
  scrollBehavior() {
    return { top: 0 };
  },
});

function shouldShowGlobalRouteLoading(to: RouteLocationNormalized, from: RouteLocationNormalized) {
  return from.matched.length > 0 && to.path !== from.path;
}

router.beforeEach(async (to, from) => {
  clearRouteError();
  if (shouldShowGlobalRouteLoading(to, from)) {
    beginRouteLoading();
  } else {
    endRouteLoading();
  }
  if (!authState.initialized) {
    await loadCurrentUser();
  }
  const accessRedirect = routeAccessRedirect(to, authState.currentUser);
  if (accessRedirect) {
    return {
      path: accessRedirect,
      replace: true,
    };
  }
  const normalizedQuery = normalizeQuery(to, from);
  if (normalizedQuery) {
    return {
      path: to.path,
      query: normalizedQuery,
      hash: to.hash,
      replace: true,
    };
  }
  return true;
});

router.afterEach(() => {
  endRouteLoading();
});

router.onError((error) => {
  endRouteLoading();
  setRouteError(error instanceof Error ? error.message : '页面加载失败，请稍后重试。');
});

export default router;
