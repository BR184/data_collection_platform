import type { Router, RouteLocationRaw } from 'vue-router';
import type { AnalyticsDashboardDetailAction } from '../../types/api';
import type { useAcceptedPageView } from '../../composables/useAcceptedPageView';
import { readViewsApi } from '../../api-client/read-views-api';
import { buildAnalyticsDetailRoute } from './detail-view-routes';
import { getStatisticBoardKey, type PageKey } from '../../feature-manifest';
import { createUuid } from '../../utils/uuid';

/** 与服务器读取目录一致的来源范围；成员过滤和分页不扩大来源闭包。 */
const sourceScopeKeys = new Set(['sourceInstance', 'source', 'projectId', 'testingPhase', 'milestoneTitle',
  'milestoneBusinessKey', 'productVersionId', 'tableName', 'topic', 'repositoryName']);

/** 从实际目标查询提取范围，来源身份不能从可编辑草稿或新版本猜测。 */
export function analyticsReadScope(query: Readonly<Record<string, unknown>>): Record<string, string> {
  return Object.fromEntries(Object.entries(query).filter(([key, value]) => sourceScopeKeys.has(key)
    && value != null && String(value).trim()).map(([key, value]) => [key, String(value)]));
}

/**
 * 内部下钻先复制父完整版本的租约；导航未成功或父画面已换版时释放未使用的子租约。
 * 普通业务页面同样复制目标页租约，服务器验证目标权限及完整来源闭包。
 */
export async function navigateAnalyticsDetail<T>(page: ReturnType<typeof useAcceptedPageView<T>>, router: Router,
  dashboardKey: string, action: AnalyticsDashboardDetailAction,
  parameters: (data: T) => Record<string, string>): Promise<void> {
  let childId: string | undefined;
  let navigationStarted = false;
  try {
    await page.readAccepted(async (data, read) => {
      const destination = buildAnalyticsDetailRoute(dashboardKey, action, parameters(data));
      if (typeof destination === 'object') {
        const query = destination.query ?? {};
        const scope = analyticsReadScope(query);
        const resolved = router.resolve(destination);
        const internal = 'name' in destination && String(destination.name).endsWith('-analytics-detail');
        const pageKey = resolved.meta.pageKey as PageKey | undefined;
        const businessPages: Record<string, string> = {
          'review-data-home': 'review-data-records', 'question-metrics-issue-search': 'system-test-issues',
        };
        const targetPage = internal ? dashboardKey : getStatisticBoardKey(pageKey)
          ?? businessPages[pageKey ?? ''] ?? String(pageKey ?? '');
        if (!targetPage) throw new Error('下钻目标页面未登记读取身份');
        const metadata = await readViewsApi.fork(read.readContextId, scope, createUuid(), targetPage);
        childId = metadata.readContextId;
        return { ...destination, state: { analyticsReadContextId: childId, analyticsReadScope: scope, readViewPageKey: targetPage } } as RouteLocationRaw;
      }
      return destination;
    }, destination => {
      navigationStarted = true;
      void router.push(destination).then(failure => {
        if (failure && childId) void readViewsApi.release(childId).catch(() => undefined);
      }).catch(() => { if (childId) void readViewsApi.release(childId).catch(() => undefined); });
    });
  } finally {
    if (childId && !navigationStarted) await readViewsApi.release(childId).catch(() => undefined);
  }
}
