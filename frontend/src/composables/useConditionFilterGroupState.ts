import { computed, reactive, type ComputedRef } from 'vue';
import type { LocationQuery } from 'vue-router';
import type { StatisticFilterField } from '../types/api';
import {
  buildFilterGroupFromRouteQuery,
  buildFilterQueryPatch,
  buildResetFilterQueryPatch,
} from '../components/statistic-board-route-query';
import {
  createEmptyFilterGroup,
  normalizeFilterDraftGroup,
  replaceFilterDraftGroup,
  resetFilterDraftGroup,
  sanitizeFilterDraftGroup,
  type StatisticFilterDraftGroup,
} from '../components/statistic-board-filters';

export function useConditionFilterGroupState(fields: ComputedRef<StatisticFilterField[]>) {
  const filterDraft = reactive<StatisticFilterDraftGroup>(createEmptyFilterGroup());
  const activeFilterTags = computed(() =>
    filterDraft.conditions.length
      ? [{ key: 'filterGroup', label: '条件筛选', value: `${filterDraft.conditions.length} 条` }]
      : [],
  );

  function initializeFromQuery(query: LocationQuery) {
    const routeFilterGroup = buildFilterGroupFromRouteQuery(query);
    const nextDraft = normalizeFilterDraftGroup(routeFilterGroup, fields.value);
    replaceFilterDraftGroup(filterDraft, nextDraft);
  }

  function buildFilterPayload() {
    return sanitizeFilterDraftGroup(filterDraft);
  }

  function resetDraft() {
    resetFilterDraftGroup(filterDraft);
  }

  function buildApplyQueryPatch(query: LocationQuery) {
    return buildFilterQueryPatch(query, filterDraft);
  }

  function buildResetQueryPatch(query: LocationQuery) {
    return buildResetFilterQueryPatch(query);
  }

  return {
    filterDraft,
    activeFilterTags,
    initializeFromQuery,
    buildFilterPayload,
    resetDraft,
    buildApplyQueryPatch,
    buildResetQueryPatch,
  };
}
