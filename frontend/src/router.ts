import { createRouter, createWebHashHistory, type RouteLocationNormalized, type RouteRecordRaw } from 'vue-router';
import { type ModuleKey, type PageKey } from './navigation';
import { buildPageRouteMeta } from './feature-manifest';
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
const TestingPhaseDefinitionView = () => import('./views/TestingPhaseDefinitionView.vue');

declare module 'vue-router' {
  interface RouteMeta {
    moduleKey: ModuleKey;
    pageKey: PageKey;
    title: string;
    description: string;
    standalone?: boolean;
    allowedQueryKeys?: string[];
    allowedQueryPrefixes?: string[];
    persistedQueryKeys?: string[];
  }
}

function buildPlaceholderRoute(path: string, moduleKey: ModuleKey, pageKey: PageKey): RouteRecordRaw {
  return {
    path,
    component: ModulePlaceholderView,
    meta: buildPageRouteMeta(moduleKey, pageKey, {
      persistedQueryKeys: ['projectId'],
    }),
  };
}

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: '/quality-board/home',
  },
  {
    path: '/external/code-review-form',
    component: CollectFormView,
    meta: buildPageRouteMeta('code-review', 'code-review-illegal-records', {
      title: '代码走查表单',
      description: '通过独立链接打开的代码走查表单页面。',
      standalone: true,
      allowedQueryKeys: ['gitlabBaseUrl', 'projectId', 'mrIid'],
      persistedQueryKeys: [],
    }),
  },
  {
    path: '/quality-board/home',
    component: StatisticBoardPage,
    meta: buildPageRouteMeta('quality-board', 'quality-board-home'),
  },
  buildPlaceholderRoute('/quality-board/rd-quality-board', 'quality-board', 'quality-board-rd-quality-board'),
  buildPlaceholderRoute('/quality-board/other-board', 'quality-board', 'quality-board-other-board'),
  {
    path: '/review-data/home',
    component: ReviewDataManagementView,
    meta: buildPageRouteMeta('review-data', 'review-data-home'),
  },
  {
    path: '/code-review/home',
    redirect: '/code-review/illegal-records',
  },
  {
    path: '/code-review/illegal-records',
    component: CodeReviewIllegalRecordsView,
    meta: buildPageRouteMeta('code-review', 'code-review-illegal-records'),
  },
  {
    path: '/code-review/illegal-records/rule-config',
    component: CodeReviewIllegalRuleConfigView,
    meta: buildPageRouteMeta('code-review', 'code-review-illegal-records', {
      title: '代码走查规则配置',
      description: '配置当前用户自己的代码走查判定规则，并即时查看结果预览。',
    }),
  },
  buildPlaceholderRoute('/code-review/multi-board', 'code-review', 'code-review-multi-board'),
  buildPlaceholderRoute('/integration-test/home', 'integration-test', 'integration-test-home'),
  {
    path: '/question-metrics/home',
    component: StatisticBoardPage,
    meta: buildPageRouteMeta('question-metrics', 'question-metrics-home'),
  },
  buildPlaceholderRoute('/question-metrics/multi-board', 'question-metrics', 'question-metrics-multi-board'),
  {
    path: '/question-metrics/delay-analysis',
    component: StatisticBoardPage,
    meta: buildPageRouteMeta('question-metrics', 'question-metrics-delay-analysis'),
  },
  buildPlaceholderRoute('/question-metrics/illegal-records', 'question-metrics', 'question-metrics-illegal-records'),
  {
    path: '/question-metrics/defect-cause',
    component: StatisticBoardPage,
    meta: buildPageRouteMeta('question-metrics', 'question-metrics-defect-cause'),
  },
  {
    path: '/question-metrics/phase-statistics',
    component: StatisticBoardPage,
    meta: buildPageRouteMeta('question-metrics', 'question-metrics-phase-statistics'),
  },
  {
    path: '/question-metrics/issue-search',
    component: SystemTestIssueSearchView,
    meta: buildPageRouteMeta('question-metrics', 'question-metrics-issue-search'),
  },
  {
    path: '/customer-issues/home',
    component: StatisticBoardPage,
    meta: buildPageRouteMeta('customer-issues', 'customer-issues-home'),
  },
  {
    path: '/customer-issues/illegal-records',
    component: CustomerIssueIllegalRecordsView,
    meta: buildPageRouteMeta('customer-issues', 'customer-issues-illegal-records'),
  },
  {
    path: '/customer-issues/defect-cause',
    component: StatisticBoardPage,
    meta: buildPageRouteMeta('customer-issues', 'customer-issues-defect-cause'),
  },
  {
    path: '/customer-issues/cc-product-issues',
    component: CustomerIssueRecordsView,
    meta: buildPageRouteMeta('customer-issues', 'customer-issues-cc-product-issues'),
  },
  {
    path: '/customer-issues/delay-issues',
    component: CustomerIssueRecordsView,
    meta: buildPageRouteMeta('customer-issues', 'customer-issues-delay-issues'),
  },
  {
    path: '/customer-issues/response-efficiency',
    component: StatisticBoardPage,
    meta: buildPageRouteMeta('customer-issues', 'customer-issues-response-efficiency'),
  },
  {
    path: '/customer-issues/issue-by-function',
    component: StatisticBoardPage,
    meta: buildPageRouteMeta('customer-issues', 'customer-issues-issue-by-function'),
  },
  {
    path: '/system-settings/mirror-settings',
    component: MirrorSettingsView,
    meta: buildPageRouteMeta('system-settings', 'mirror-settings'),
  },
  {
    path: '/system-settings/database-browser',
    component: DatabaseBrowserView,
    meta: buildPageRouteMeta('system-settings', 'database-browser'),
  },
  {
    path: '/system-settings/testing-phase-definition',
    component: TestingPhaseDefinitionView,
    meta: buildPageRouteMeta('system-settings', 'testing-phase-definition'),
  },
  {
    path: '/:pathMatch(.*)*',
    component: NotFoundView,
    meta: {
      moduleKey: 'quality-board',
      pageKey: 'quality-board-home',
      title: '页面不存在',
      description: '访问的地址无效，已为你保留平台基础壳子。',
    },
  },
];

function sameStringArray(left: string[], right: string[]) {
  return left.length === right.length && left.every((value, index) => value === right[index]);
}

export function normalizeQuery(to: RouteLocationNormalized, from?: RouteLocationNormalized) {
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

router.beforeEach((to, from) => {
  clearRouteError();
  if (shouldShowGlobalRouteLoading(to, from)) {
    beginRouteLoading();
  } else {
    endRouteLoading();
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
