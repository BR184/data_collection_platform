import { onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from '../element-plus-services';
import {
  normalizeRouteQueryForSnapshot,
  toRouteQueryFromSnapshot,
  useSavedTableViews,
  type SavedTableViewSnapshot,
} from './useSavedTableViews';

export interface UsePageSavedViewsOptions {
  scopeKey: () => string;
  captureViewPrefs?: () => unknown;
  applyViewPrefs?: (viewPrefs: unknown) => void | Promise<void>;
  afterApply?: () => void;
}

export function usePageSavedViews(options: UsePageSavedViewsOptions) {
  const route = useRoute();
  const router = useRouter();

  const views = useSavedTableViews({
    scopeKey: options.scopeKey,
    getCurrentSnapshot,
    applySnapshot,
    notifySuccess: (message) => ElMessage.success(message),
    notifyWarning: (message) => ElMessage.warning(message),
    confirmDelete: (viewName) =>
      ElMessageBox.confirm(`确定删除固定视图「${viewName}」吗？删除后无法恢复。`, '删除固定视图', {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
      }),
  });

  onMounted(views.loadSavedViews);

  function getCurrentSnapshot(): SavedTableViewSnapshot {
    return {
      routeQuery: normalizeRouteQueryForSnapshot(route.query),
      viewPrefs: options.captureViewPrefs?.(),
    };
  }

  async function applySnapshot(snapshot: SavedTableViewSnapshot) {
    if (options.applyViewPrefs && snapshot.viewPrefs !== undefined) {
      await options.applyViewPrefs(snapshot.viewPrefs);
    }
    await router.replace({
      path: route.path,
      query: toRouteQueryFromSnapshot(snapshot),
      hash: route.hash,
    });
    options.afterApply?.();
  }

  return views;
}
