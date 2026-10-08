import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

type QueryValue = string | number | undefined | null;
type QueryPatch = Record<string, QueryValue>;
type QueryMode = 'push' | 'replace';

export interface RouteTableLoadContext {
  /** 单调递增的查询请求身份。 */
  requestId: number;
  /** 发起请求时冻结的完整路由查询快照。 */
  query: Readonly<Record<string, unknown>>;
  /** 查询意图仍为当前请求且页面尚未卸载时返回 true。 */
  isCurrent(): boolean;
}

/**
 * 一次加载的最终归属。
 *
 * - `committed`：结果已提交到页面状态，请求身份在提交时仍是当前请求。
 * - `superseded`：结果未提交，因为查询意图已被更新的请求或路由取代。
 * - `failed`：加载确实失败，且失败时该请求仍是当前请求，loader 的错误通道已同时收到通知。
 */
export type RouteTableLoadOutcome =
  | { status: 'committed' }
  | { status: 'superseded' }
  | { status: 'failed'; error: unknown };

function parsePositiveInteger(rawValue: unknown, fallback: number) {
  const parsed = Number.parseInt(String(rawValue ?? ''), 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

function parseSortOrder(rawValue: unknown) {
  return rawValue === 'asc' || rawValue === 'desc' ? rawValue : '';
}

export interface RouteTableStateOptions {
  defaults?: {
    page?: number;
    pageSize?: number;
    sortBy?: string;
    sortOrder?: 'asc' | 'desc' | '';
    keyword?: string;
  };
  watchedQueryKeys?: string[];
  debounceMs?: number;
  minLoadingMs?: number;
  /** 是否在绑定 loader 时立即执行；默认保持既有的立即加载行为。 */
  immediate?: boolean;
  autoRefreshOnEnter?: () => boolean;
}

/** 尚未落到路由、但已表示用户最新查询意图的延迟补丁。 */
interface QueryIntent {
  patch: QueryPatch;
  mode: QueryMode;
}

type BoundLoader = () => Promise<RouteTableLoadOutcome>;
type Loader<T> = (context: RouteTableLoadContext) => Promise<T>;
type Commit<T> = (result: T, context: RouteTableLoadContext) => void;
type LoadFailure = (error: unknown, context: RouteTableLoadContext) => void;

const DEFAULT_WATCHED_QUERY_KEYS = ['page', 'pageSize', 'sortBy', 'sortOrder', 'keyword'];

export function useRouteTableState(options: RouteTableStateOptions = {}) {
  const route = useRoute();
  const router = useRouter();
  const isTableLoading = ref(false);
  let debounceTimer: number | null = null;
  let pendingIntent: QueryIntent | null = null;
  let loaderRunId = 0;
  let disposed = false;
  let boundLoader: BoundLoader | null = null;

  const page = computed(() => parsePositiveInteger(route.query.page, options.defaults?.page ?? 1));
  const pageSize = computed(() => parsePositiveInteger(route.query.pageSize, options.defaults?.pageSize ?? 20));
  const sortBy = computed(() => String(route.query.sortBy ?? options.defaults?.sortBy ?? ''));
  const sortOrder = computed(() => parseSortOrder(route.query.sortOrder ?? options.defaults?.sortOrder ?? ''));
  const keyword = computed(() => String(route.query.keyword ?? options.defaults?.keyword ?? ''));
  /** 参与加载判定的查询组合签名：筛选、分页与排序任一变化都会改变它。 */
  const watchedQuerySignatureValue = computed(() =>
    watchedQuerySignature(route.query, options.watchedQueryKeys),
  );

  function invalidatePendingQuery() {
    loaderRunId += 1;
    isTableLoading.value = false;
  }

  function buildNextQuery(patch: QueryPatch) {
    const nextQuery = { ...route.query } as Record<string, string>;
    for (const [key, rawValue] of Object.entries(patch)) {
      if (rawValue == null || rawValue === '') {
        delete nextQuery[key];
      } else {
        nextQuery[key] = String(rawValue);
      }
    }
    return nextQuery;
  }

  function intentChangesWatchedQuery(patch: QueryPatch) {
    return watchedQuerySignature(route.query, options.watchedQueryKeys)
      !== watchedQuerySignature(buildNextQuery(patch), options.watchedQueryKeys);
  }

  async function patchQuery(patch: QueryPatch, mode: QueryMode = 'replace') {
    cancelDebouncedQuery();
    const nextQuery = buildNextQuery(patch);
    if (intentChangesWatchedQuery(patch)) {
      invalidatePendingQuery();
    }
    await router[mode]({
      path: route.path,
      query: nextQuery,
      hash: route.hash,
    });
  }

  function debouncedPatchQuery(patch: QueryPatch, mode: QueryMode = 'replace') {
    const mergedPatch = { ...pendingIntent?.patch, ...patch };
    pendingIntent = { patch: mergedPatch, mode };
    if (intentChangesWatchedQuery(mergedPatch)) {
      invalidatePendingQuery();
    }
    if (debounceTimer != null) {
      window.clearTimeout(debounceTimer);
    }
    debounceTimer = window.setTimeout(() => {
      debounceTimer = null;
      const intent = pendingIntent;
      if (intent) {
        void patchQuery(intent.patch, intent.mode);
      }
    }, options.debounceMs ?? 300);
  }

  /** 取消尚未落地的延迟查询意图：定时器与意图一并丢弃，之后由调用者自行决定查询。 */
  function cancelDebouncedQuery() {
    if (debounceTimer != null) {
      window.clearTimeout(debounceTimer);
      debounceTimer = null;
    }
    pendingIntent = null;
  }

  function bindLoader<T>(loader: Loader<T>, commit: Commit<T>, onError: LoadFailure = () => undefined) {
    boundLoader = async () => {
      const requestId = ++loaderRunId;
      const startedAt = Date.now();
      const query = snapshotQuery(route.query);
      const context: RouteTableLoadContext = {
        requestId,
        query,
        isCurrent: () => !disposed && requestId === loaderRunId,
      };
      isTableLoading.value = true;
      let outcome: RouteTableLoadOutcome = { status: 'superseded' };
      try {
        const result = await loader(context);
        if (context.isCurrent()) {
          commit(result, context);
          outcome = { status: 'committed' };
        }
      } catch (error) {
        if (context.isCurrent()) {
          onError(error, context);
          outcome = { status: 'failed', error };
        }
      } finally {
        const minLoadingMs = options.minLoadingMs ?? 220;
        const remainingMs = minLoadingMs - (Date.now() - startedAt);
        if (remainingMs > 0) {
          await new Promise((resolve) => window.setTimeout(resolve, remainingMs));
        }
        if (context.isCurrent()) isTableLoading.value = false;
      }
      return outcome;
    };
    watch(watchedQuerySignatureValue, () => void reload(), { immediate: options.immediate ?? true });
  }

  /**
   * 按当前查询意图执行一次加载，并把真实归属报告给调用者。
   *
   * 存在尚未落到路由的查询意图时，先提交该意图再加载，绝不按已被取代的旧查询加载并授予提交资格；
   * 因此返回 `committed` 时，页面数据一定来自调用时刻最新的查询，而不是更早的查询或被丢弃的结果。
   * 加载失败会通过 loader 的错误通道上报，同时以 `failed` 返回，调用者不得把非 `committed` 当作刷新成功。
   */
  async function reload(): Promise<RouteTableLoadOutcome> {
    if (!boundLoader || disposed) return { status: 'superseded' };
    const intent = pendingIntent;
    if (intent) {
      await patchQuery(intent.patch, intent.mode);
      if (!boundLoader || disposed) return { status: 'superseded' };
    }
    return boundLoader();
  }

  onBeforeUnmount(() => {
    disposed = true;
    cancelDebouncedQuery();
    invalidatePendingQuery();
    boundLoader = null;
  });

  return {
    route,
    router,
    page,
    pageSize,
    sortBy,
    sortOrder,
    keyword,
    isTableLoading,
    watchedQuerySignature: watchedQuerySignatureValue,
    patchQuery,
    debouncedPatchQuery,
    cancelDebouncedQuery,
    invalidatePendingQuery,
    bindLoader,
    reload,
  };
}

function snapshotQuery(query: Record<string, unknown>) {
  const snapshot: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(query)) {
    snapshot[key] = Array.isArray(value) ? Object.freeze([...value]) : value;
  }
  return Object.freeze(snapshot);
}

function watchedQuerySignature(query: Record<string, unknown>, additionalKeys: string[] = []) {
  const keys = [...new Set([...DEFAULT_WATCHED_QUERY_KEYS, ...additionalKeys])].sort();
  return keys
    .map((key) => `${key}=${normalizeQueryValue(query[key])}`)
    .join('&');
}

function normalizeQueryValue(value: unknown) {
  return Array.isArray(value)
    ? value.map((item) => String(item ?? '')).join(',')
    : String(value ?? '');
}
