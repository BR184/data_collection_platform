import type { LocationQuery } from 'vue-router';

type QueryValue = string | number | null | undefined;
type QueryPatch = Record<string, QueryValue>;
type SortOrder = 'asc' | 'desc';

interface RangeQueryKeys {
  startKey: string;
  endKey: string;
}

interface UseRecordPageControllerOptions {
  getRouteQuery: () => LocationQuery;
  patchQuery: (patch: QueryPatch) => Promise<void>;
  loadTableData?: () => Promise<void>;
  resetDraft?: () => void;
  buildApplyQueryPatch?: (query: LocationQuery) => QueryPatch;
  buildResetQueryPatch?: (query: LocationQuery) => QueryPatch;
  defaultSortBy: string;
  defaultSortOrder?: SortOrder;
  resetClearKeys?: string[];
  queryClearKeys?: string[];
  rangeKeys?: Record<string, RangeQueryKeys>;
  keywordKey?: string;
}

function buildNullPatch(keys: string[] = []) {
  return keys.reduce<QueryPatch>((patch, key) => {
    patch[key] = null;
    return patch;
  }, {});
}

export function useRecordPageController(options: UseRecordPageControllerOptions) {
  const defaultSortOrder = options.defaultSortOrder ?? 'desc';
  const keywordKey = options.keywordKey ?? 'keyword';

  async function handleReset() {
    options.resetDraft?.();
    await options.patchQuery({
      ...(options.buildResetQueryPatch?.(options.getRouteQuery()) ?? {}),
      page: 1,
      sortBy: options.defaultSortBy,
      sortOrder: defaultSortOrder,
      ...buildNullPatch(options.resetClearKeys),
    });
  }

  async function handleQuery() {
    await options.patchQuery({
      ...(options.buildApplyQueryPatch?.(options.getRouteQuery()) ?? {}),
      page: 1,
      ...buildNullPatch(options.queryClearKeys),
    });
  }

  async function handleKeywordSearch(nextKeyword: string) {
    await options.patchQuery({
      page: 1,
      [keywordKey]: nextKeyword || null,
    });
  }

  async function handleRefresh() {
    await options.loadTableData?.();
  }

  async function handleSizeChange(nextSize: number) {
    await options.patchQuery({ pageSize: nextSize, page: 1 });
  }

  async function handleCurrentChange(nextPage: number) {
    await options.patchQuery({ page: nextPage });
  }

  async function handleSortChange(payload: { prop: string; order: 'ascending' | 'descending' | null }) {
    await options.patchQuery({
      sortBy: payload.prop || options.defaultSortBy,
      sortOrder: payload.order === 'ascending' ? 'asc' : defaultSortOrder,
      page: 1,
    });
  }

  async function handleClearFilter(key: string) {
    if (key === 'filterGroup') {
      options.resetDraft?.();
      await options.patchQuery({
        ...(options.buildResetQueryPatch?.(options.getRouteQuery()) ?? {}),
        page: 1,
      });
      return;
    }

    const rangeKeys = options.rangeKeys?.[key];
    if (rangeKeys) {
      await options.patchQuery({
        page: 1,
        [rangeKeys.startKey]: null,
        [rangeKeys.endKey]: null,
      });
      return;
    }

    await options.patchQuery({ page: 1, [key]: null });
  }

  return {
    handleReset,
    handleQuery,
    handleKeywordSearch,
    handleRefresh,
    handleSizeChange,
    handleCurrentChange,
    handleSortChange,
    handleClearFilter,
  };
}
