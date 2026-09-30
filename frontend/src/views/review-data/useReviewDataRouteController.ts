import { ref } from 'vue';
import type { LocationQuery } from 'vue-router';
import type { StatisticFilterGroup } from '../../types/api';
import { mergeRouteQuery } from '../../components/statistic-board-route-query';
import type { ReviewDataRecordQueryParams } from './useReviewDataRecords';

type QueryValue = string | number | null | undefined;
type QueryPatch = Record<string, QueryValue>;
type QueryMode = 'push' | 'replace';

export interface ReviewDataRouteControllerDependencies {
  getRouteQuery: () => LocationQuery;
  getKeyword: () => string;
  getPage: () => number;
  getPageSize: () => number;
  getSortBy: () => string;
  getSortOrder: () => 'asc' | 'desc' | '';
  getSourceInstance?: () => string;
  patchQuery: (patch: QueryPatch, mode?: QueryMode) => Promise<void>;
  initializeFromQuery: (query: LocationQuery) => void;
  buildFilterPayload: () => StatisticFilterGroup | null;
  resetDraft: () => void;
  buildApplyQueryPatch: (query: LocationQuery) => QueryPatch;
  buildResetQueryPatch: (query: LocationQuery) => QueryPatch;
  loadRows: () => Promise<void>;
}

export function useReviewDataRouteController(deps: ReviewDataRouteControllerDependencies) {
  const appliedFilterGroup = ref<StatisticFilterGroup | null>(null);

  function buildRecordQueryParams(
    overrides: { page?: number; size?: number } = {},
    query?: LocationQuery,
  ): ReviewDataRecordQueryParams {
    const routeQuery = query ?? deps.getRouteQuery();
    return {
      keyword: (query ? routeString(routeQuery.keyword) : deps.getKeyword()).trim(),
      title: routeString(routeQuery.title),
      projectName: routeString(routeQuery.projectName),
      moduleName: routeString(routeQuery.moduleName),
      reviewOwner: routeString(routeQuery.reviewOwner),
      reviewType: routeString(routeQuery.reviewType),
      problemStatus: routeString(routeQuery.problemStatus),
      reviewExpert: routeString(routeQuery.reviewExpert),
      filterGroup: appliedFilterGroup.value,
      sourceInstance: deps.getSourceInstance?.() || undefined,
      page: overrides.page ?? (query ? parsePositiveInteger(routeQuery.page, 1) : deps.getPage()),
      size: overrides.size ?? (query ? parsePositiveInteger(routeQuery.pageSize, 20) : deps.getPageSize()),
      sortBy: query ? routeString(routeQuery.sortBy) || 'updatedAt' : deps.getSortBy(),
      sortOrder: ((query ? routeString(routeQuery.sortOrder) : deps.getSortOrder()) || 'desc') as 'asc' | 'desc',
    };
  }

  function syncFilterDraftFromRoute(query?: LocationQuery) {
    deps.initializeFromQuery(query ?? deps.getRouteQuery());
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

function routeString(rawValue: LocationQuery[string]) {
  const value = Array.isArray(rawValue) ? rawValue[0] : rawValue;
  return value == null ? '' : String(value);
}

function parsePositiveInteger(rawValue: LocationQuery[string], fallback: number) {
  const parsed = Number.parseInt(routeString(rawValue), 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}
