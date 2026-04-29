import type { LocationQuery } from 'vue-router';
import type { StatisticFilterDraftGroup } from '../components/statistic-board-filters';
import {
  buildFilterQueryPatch,
  buildResetFilterQueryPatch,
  mergeRouteQuery,
} from '../components/statistic-board-route-query';

type QueryValue = string | number | null | undefined;
type QueryPatch = Record<string, QueryValue>;

interface ReplaceRouteLocation {
  path: string;
  query: Record<string, string>;
  hash: string;
}

export interface StatisticBoardRouteControllerDependencies {
  getRouteQuery: () => LocationQuery;
  getRoutePath: () => string;
  getRouteHash: () => string;
  replaceRoute: (location: ReplaceRouteLocation) => Promise<void>;
  resetFilterDraft: () => void;
}

const DETAIL_ROUTE_CLEAR_PATCH: QueryPatch = {
  detailVisible: '',
  detailRowKey: '',
  detailColumnKey: '',
  detailPage: '',
  detailPageSize: '',
  detailSortBy: '',
  detailSortOrder: '',
};

export function useStatisticBoardRouteController(deps: StatisticBoardRouteControllerDependencies) {
  async function replaceRouteQuery(patch: QueryPatch) {
    const nextQuery = mergeRouteQuery(deps.getRouteQuery(), patch);
    await deps.replaceRoute({
      path: deps.getRoutePath(),
      query: nextQuery,
      hash: deps.getRouteHash(),
    });
  }

  async function applyFiltersToRoute(filterDraft: StatisticFilterDraftGroup) {
    await replaceRouteQuery({
      ...buildFilterQueryPatch(deps.getRouteQuery(), filterDraft),
      ...DETAIL_ROUTE_CLEAR_PATCH,
    });
  }

  async function resetFilters() {
    deps.resetFilterDraft();
    await replaceRouteQuery(buildResetFilterQueryPatch(deps.getRouteQuery()));
  }

  return {
    replaceRouteQuery,
    applyFiltersToRoute,
    resetFilters,
  };
}
