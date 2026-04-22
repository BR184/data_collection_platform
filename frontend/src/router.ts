import { createRouter, createWebHashHistory, type RouteLocationNormalized, type RouteRecordRaw } from 'vue-router';
import { pageByKey, type ModuleKey, type PageKey } from './navigation';
import { routerState } from './router-state';

import StatisticBoardPage from './views/StatisticBoardPage.vue';
import MirrorSettingsView from './views/MirrorSettingsView.vue';
import DatabaseBrowserView from './components/DatabaseBrowserView.vue';
import ModulePlaceholderView from './views/ModulePlaceholderView.vue';
import NotFoundView from './views/NotFoundView.vue';
import CollectFormView from './views/CollectFormView.vue';
import CodeReviewIllegalRecordsView from './views/CodeReviewIllegalRecordsView.vue';
import CodeReviewIllegalRuleConfigView from './views/CodeReviewIllegalRuleConfigView.vue';
import ReviewDataManagementView from './views/ReviewDataManagementView.vue';
import SystemTestIssueSearchView from './views/SystemTestIssueSearchView.vue';
import CustomerIssueIllegalRecordsView from './views/CustomerIssueIllegalRecordsView.vue';
import CustomerIssueRecordsView from './views/CustomerIssueRecordsView.vue';

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
    meta: {
      moduleKey,
      pageKey,
      title: pageByKey.get(pageKey)!.label,
      description: pageByKey.get(pageKey)!.description,
      persistedQueryKeys: ['projectId'],
    },
  };
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

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: '/quality-board/home',
  },
  {
    path: '/external/code-review-form',
    component: CollectFormView,
    meta: {
      moduleKey: 'code-review',
      pageKey: 'code-review-illegal-records',
      title: '代码走查表单',
      description: '通过独立链接打开的代码走查表单页面。',
      standalone: true,
      allowedQueryKeys: ['gitlabBaseUrl', 'projectId', 'mrIid'],
      persistedQueryKeys: [],
    },
  },
  {
    path: '/quality-board/home',
    component: StatisticBoardPage,
    meta: {
      moduleKey: 'quality-board',
      pageKey: 'quality-board-home',
      title: pageByKey.get('quality-board-home')!.label,
      description: pageByKey.get('quality-board-home')!.description,
      allowedQueryKeys: statisticBoardQueryKeys,
      allowedQueryPrefixes: ['filters.'],
      persistedQueryKeys: ['projectId'],
    },
  },
  buildPlaceholderRoute('/quality-board/rd-quality-board', 'quality-board', 'quality-board-rd-quality-board'),
  buildPlaceholderRoute('/quality-board/other-board', 'quality-board', 'quality-board-other-board'),
  {
    path: '/review-data/home',
    component: ReviewDataManagementView,
    meta: {
      moduleKey: 'review-data',
      pageKey: 'review-data-home',
      title: pageByKey.get('review-data-home')!.label,
      description: pageByKey.get('review-data-home')!.description,
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
  },
  {
    path: '/code-review/home',
    redirect: '/code-review/illegal-records',
  },
  {
    path: '/code-review/illegal-records',
    component: CodeReviewIllegalRecordsView,
    meta: {
      moduleKey: 'code-review',
      pageKey: 'code-review-illegal-records',
      title: pageByKey.get('code-review-illegal-records')!.label,
      description: pageByKey.get('code-review-illegal-records')!.description,
      allowedQueryKeys: [
        'page',
        'pageSize',
        'sortBy',
        'sortOrder',
        'projectId',
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
      persistedQueryKeys: ['projectId'],
    },
  },
  {
    path: '/code-review/illegal-records/rule-config',
    component: CodeReviewIllegalRuleConfigView,
    meta: {
      moduleKey: 'code-review',
      pageKey: 'code-review-illegal-records',
      title: '代码走查规则配置',
      description: '配置当前用户自己的代码走查判定规则，并即时查看结果预览。',
      allowedQueryKeys: [
        'page',
        'pageSize',
        'sortBy',
        'sortOrder',
        'projectId',
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
      persistedQueryKeys: ['projectId'],
    },
  },
  buildPlaceholderRoute('/code-review/multi-board', 'code-review', 'code-review-multi-board'),
  buildPlaceholderRoute('/integration-test/home', 'integration-test', 'integration-test-home'),
  {
    path: '/question-metrics/home',
    component: StatisticBoardPage,
    meta: {
      moduleKey: 'question-metrics',
      pageKey: 'question-metrics-home',
      title: pageByKey.get('question-metrics-home')!.label,
      description: pageByKey.get('question-metrics-home')!.description,
      allowedQueryKeys: statisticBoardQueryKeys,
      allowedQueryPrefixes: ['filters.'],
      persistedQueryKeys: ['projectId'],
    },
  },
  buildPlaceholderRoute('/question-metrics/multi-board', 'question-metrics', 'question-metrics-multi-board'),
  {
    path: '/question-metrics/delay-analysis',
    component: StatisticBoardPage,
    meta: {
      moduleKey: 'question-metrics',
      pageKey: 'question-metrics-delay-analysis',
      title: pageByKey.get('question-metrics-delay-analysis')!.label,
      description: pageByKey.get('question-metrics-delay-analysis')!.description,
      allowedQueryKeys: statisticBoardQueryKeys,
      allowedQueryPrefixes: ['filters.'],
      persistedQueryKeys: ['projectId'],
    },
  },
  buildPlaceholderRoute('/question-metrics/illegal-records', 'question-metrics', 'question-metrics-illegal-records'),
  {
    path: '/question-metrics/defect-cause',
    component: StatisticBoardPage,
    meta: {
      moduleKey: 'question-metrics',
      pageKey: 'question-metrics-defect-cause',
      title: pageByKey.get('question-metrics-defect-cause')!.label,
      description: pageByKey.get('question-metrics-defect-cause')!.description,
      allowedQueryKeys: statisticBoardQueryKeys,
      allowedQueryPrefixes: ['filters.'],
      persistedQueryKeys: ['projectId'],
    },
  },
  {
    path: '/question-metrics/phase-statistics',
    component: StatisticBoardPage,
    meta: {
      moduleKey: 'question-metrics',
      pageKey: 'question-metrics-phase-statistics',
      title: pageByKey.get('question-metrics-phase-statistics')!.label,
      description: pageByKey.get('question-metrics-phase-statistics')!.description,
      allowedQueryKeys: statisticBoardQueryKeys,
      allowedQueryPrefixes: ['filters.'],
      persistedQueryKeys: ['projectId'],
    },
  },
  {
    path: '/question-metrics/issue-search',
    component: SystemTestIssueSearchView,
    meta: {
      moduleKey: 'question-metrics',
      pageKey: 'question-metrics-issue-search',
      title: pageByKey.get('question-metrics-issue-search')!.label,
      description: pageByKey.get('question-metrics-issue-search')!.description,
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
  },
  {
    path: '/customer-issues/home',
    component: StatisticBoardPage,
    meta: {
      moduleKey: 'customer-issues',
      pageKey: 'customer-issues-home',
      title: pageByKey.get('customer-issues-home')!.label,
      description: pageByKey.get('customer-issues-home')!.description,
      allowedQueryKeys: statisticBoardQueryKeys,
      allowedQueryPrefixes: ['filters.'],
      persistedQueryKeys: ['projectId'],
    },
  },
  {
    path: '/customer-issues/illegal-records',
    component: CustomerIssueIllegalRecordsView,
    meta: {
      moduleKey: 'customer-issues',
      pageKey: 'customer-issues-illegal-records',
      title: pageByKey.get('customer-issues-illegal-records')!.label,
      description: pageByKey.get('customer-issues-illegal-records')!.description,
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
  },
  {
    path: '/customer-issues/defect-cause',
    component: StatisticBoardPage,
    meta: {
      moduleKey: 'customer-issues',
      pageKey: 'customer-issues-defect-cause',
      title: pageByKey.get('customer-issues-defect-cause')!.label,
      description: pageByKey.get('customer-issues-defect-cause')!.description,
      allowedQueryKeys: statisticBoardQueryKeys,
      allowedQueryPrefixes: ['filters.'],
      persistedQueryKeys: ['projectId'],
    },
  },
  {
    path: '/customer-issues/cc-product-issues',
    component: CustomerIssueRecordsView,
    meta: {
      moduleKey: 'customer-issues',
      pageKey: 'customer-issues-cc-product-issues',
      title: pageByKey.get('customer-issues-cc-product-issues')!.label,
      description: pageByKey.get('customer-issues-cc-product-issues')!.description,
      allowedQueryKeys: customerIssueRecordQueryKeys,
      persistedQueryKeys: ['projectId'],
    },
  },
  {
    path: '/customer-issues/delay-issues',
    component: CustomerIssueRecordsView,
    meta: {
      moduleKey: 'customer-issues',
      pageKey: 'customer-issues-delay-issues',
      title: pageByKey.get('customer-issues-delay-issues')!.label,
      description: pageByKey.get('customer-issues-delay-issues')!.description,
      allowedQueryKeys: customerIssueRecordQueryKeys,
      persistedQueryKeys: ['projectId'],
    },
  },
  {
    path: '/customer-issues/response-efficiency',
    component: StatisticBoardPage,
    meta: {
      moduleKey: 'customer-issues',
      pageKey: 'customer-issues-response-efficiency',
      title: pageByKey.get('customer-issues-response-efficiency')!.label,
      description: pageByKey.get('customer-issues-response-efficiency')!.description,
      allowedQueryKeys: statisticBoardQueryKeys,
      allowedQueryPrefixes: ['filters.'],
      persistedQueryKeys: ['projectId'],
    },
  },
  buildPlaceholderRoute('/customer-issues/issue-by-function', 'customer-issues', 'customer-issues-issue-by-function'),
  {
    path: '/system-settings/mirror-settings',
    component: MirrorSettingsView,
    meta: {
      moduleKey: 'system-settings',
      pageKey: 'mirror-settings',
      title: pageByKey.get('mirror-settings')!.label,
      description: pageByKey.get('mirror-settings')!.description,
      persistedQueryKeys: ['projectId'],
    },
  },
  {
    path: '/system-settings/database-browser',
    component: DatabaseBrowserView,
    meta: {
      moduleKey: 'system-settings',
      pageKey: 'database-browser',
      title: pageByKey.get('database-browser')!.label,
      description: pageByKey.get('database-browser')!.description,
      allowedQueryKeys: ['table', 'keyword', 'page', 'pageSize', 'sortBy', 'sortOrder', 'projectId'],
      persistedQueryKeys: ['projectId'],
    },
  },
  buildPlaceholderRoute('/system-settings/module-management', 'system-settings', 'module-management'),
  buildPlaceholderRoute('/system-settings/testing-phase-definition', 'system-settings', 'testing-phase-definition'),
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

router.beforeEach((to, from) => {
  routerState.routeLoading = true;
  routerState.routeError = '';
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
  routerState.routeLoading = false;
});

router.onError((error) => {
  routerState.routeLoading = false;
  routerState.routeError = error instanceof Error ? error.message : '页面加载失败，请稍后重试。';
});

export default router;
