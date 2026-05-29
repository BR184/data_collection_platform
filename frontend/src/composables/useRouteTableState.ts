import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

type QueryValue = string | number | undefined | null;
type QueryPatch = Record<string, QueryValue>;
type QueryMode = 'push' | 'replace';

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
}

const DEFAULT_WATCHED_QUERY_KEYS = ['page', 'pageSize', 'sortBy', 'sortOrder', 'keyword'];

export function useRouteTableState(options: RouteTableStateOptions = {}) {
  const route = useRoute();
  const router = useRouter();
  const isTableLoading = ref(false);
  let debounceTimer: number | null = null;
  let loaderRunId = 0;

  const page = computed(() => parsePositiveInteger(route.query.page, options.defaults?.page ?? 1));
  const pageSize = computed(() => parsePositiveInteger(route.query.pageSize, options.defaults?.pageSize ?? 20));
  const sortBy = computed(() => String(route.query.sortBy ?? options.defaults?.sortBy ?? ''));
  const sortOrder = computed(() => parseSortOrder(route.query.sortOrder ?? options.defaults?.sortOrder ?? ''));
  const keyword = computed(() => String(route.query.keyword ?? options.defaults?.keyword ?? ''));

  async function patchQuery(patch: QueryPatch, mode: QueryMode = 'replace') {
    if (debounceTimer != null) {
      window.clearTimeout(debounceTimer);
      debounceTimer = null;
    }
    const nextQuery = { ...route.query } as Record<string, string>;
    for (const [key, rawValue] of Object.entries(patch)) {
      if (rawValue == null || rawValue === '') {
        delete nextQuery[key];
      } else {
        nextQuery[key] = String(rawValue);
      }
    }
    await router[mode]({
      path: route.path,
      query: nextQuery,
      hash: route.hash,
    });
  }

  function debouncedPatchQuery(patch: QueryPatch, mode: QueryMode = 'replace') {
    if (debounceTimer != null) {
      window.clearTimeout(debounceTimer);
    }
    debounceTimer = window.setTimeout(() => {
      debounceTimer = null;
      void patchQuery(patch, mode);
    }, options.debounceMs ?? 300);
  }

  function cancelDebouncedQuery() {
    if (debounceTimer != null) {
      window.clearTimeout(debounceTimer);
      debounceTimer = null;
    }
  }

  function bindLoader(loader: () => Promise<void>) {
    watch(
      () => watchedQuerySignature(route.query, options.watchedQueryKeys),
      async () => {
        const runId = ++loaderRunId;
        const startedAt = Date.now();
        isTableLoading.value = true;
        try {
          await loader();
        } finally {
          const minLoadingMs = options.minLoadingMs ?? 220;
          const remainingMs = minLoadingMs - (Date.now() - startedAt);
          if (remainingMs > 0) {
            await new Promise((resolve) => window.setTimeout(resolve, remainingMs));
          }
          if (runId === loaderRunId) {
            isTableLoading.value = false;
          }
        }
      },
      { immediate: true },
    );
  }

  onBeforeUnmount(() => {
    cancelDebouncedQuery();
    loaderRunId += 1;
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
    patchQuery,
    debouncedPatchQuery,
    cancelDebouncedQuery,
    bindLoader,
  };
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
