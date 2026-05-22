import { ref } from 'vue';
import type { LocationQuery } from 'vue-router';
import type { StatisticFilterGroup } from '../../types/api';
import { mergeRouteQuery } from '../../components/statistic-board-route-query';
import type { ReviewDataRecordQueryParams } from './useReviewDataRecords';

type QueryValue = string | number | null | undefined;
type QueryPatch = Record<string, QueryValue>;

export interface ReviewDataRouteControllerDependencies {
  getRouteQuery: () => LocationQuery;
  getKeyword: () => string;
  getPage: () => number;
  getPageSize: () => number;
  getSortBy: () => string;
  getSortOrder: () => 'asc' | 'desc' | '';
  patchQuery: (patch: QueryPatch) => Promise<void>;
  initializeFromQuery: (query: LocationQuery) => void;
  buildFilterPayload: () => StatisticFilterGroup | null;
  resetDraft: () => void;
  buildApplyQueryPatch: (query: LocationQuery) => QueryPatch;
  buildResetQueryPatch: (query: LocationQuery) => QueryPatch;
  loadRows: () => Promise<void>;
}

export function useReviewDataRouteController(deps: ReviewDataRouteControllerDependencies) {
  const appliedFilterGroup = ref<StatisticFilterGroup | null>(null);

  function buildRecordQueryParams(overrides: { page?: number; size?: number } = {}): ReviewDataRecordQueryParams {
    return {
      keyword: deps.getKeyword().trim(),
      filterGroup: appliedFilterGroup.value,
      page: overrides.page ?? deps.getPage(),
      size: overrides.size ?? deps.getPageSize(),
      sortBy: deps.getSortBy(),
      sortOrder: deps.getSortOrder() || 'desc',
    };
  }

  function syncFilterDraftFromRoute() {
    deps.initializeFromQuery(deps.getRouteQuery());
    appliedFilterGroup.value = deps.buildFilterPayload();
  }

  async function handleReset() {
    deps.resetDraft();
    appliedFilterGroup.value = null;
    await deps.patchQuery({
      keyword: '',
      ...deps.buildResetQueryPatch(deps.getRouteQuery()),
      title: '',
      projectName: '',
      moduleName: '',
      reviewOwner: '',
      reviewType: '',
      problemStatus: '',
      reviewExpert: '',
      sortBy: 'updatedAt',
      sortOrder: 'desc',
      page: 1,
    });
  }

  async function handleQuery(nextKeyword = deps.getKeyword()) {
    appliedFilterGroup.value = deps.buildFilterPayload();
    const patch = {
      keyword: nextKeyword.trim(),
      ...deps.buildApplyQueryPatch(deps.getRouteQuery()),
      page: 1,
    };
    const routeQuery = deps.getRouteQuery();
    const nextQuery = mergeRouteQuery(routeQuery, patch);
    const currentQuery = mergeRouteQuery(routeQuery, {});
    const queryChanged = JSON.stringify(nextQuery) !== JSON.stringify(currentQuery);
    await deps.patchQuery(patch);
    if (!queryChanged) {
      await deps.loadRows();
    }
  }

  async function handleKeywordSearch(nextKeyword: string) {
    await deps.patchQuery({
      keyword: nextKeyword.trim(),
      page: 1,
    });
  }

  async function handleSortChange(payload: { prop: string; order: 'ascending' | 'descending' | null }) {
    await deps.patchQuery({
      sortBy: payload.prop || 'updatedAt',
      sortOrder: payload.order === 'ascending' ? 'asc' : payload.order === 'descending' ? 'desc' : 'desc',
      page: 1,
    });
  }

  async function handlePageChange(nextPage: number) {
    await deps.patchQuery({ page: nextPage });
  }

  async function handleSizeChange(nextSize: number) {
    await deps.patchQuery({ pageSize: nextSize, page: 1 });
  }

  return {
    appliedFilterGroup,
    buildRecordQueryParams,
    syncFilterDraftFromRoute,
    handleReset,
    handleQuery,
    handleKeywordSearch,
    handleSortChange,
    handlePageChange,
    handleSizeChange,
  };
}
