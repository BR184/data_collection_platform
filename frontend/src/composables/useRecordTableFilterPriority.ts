import { computed, type ComputedRef } from 'vue';
import type { RecordTableFilterField } from '../types/record-table';
import type { StatisticFilterDraftGroup } from '../components/statistic-board-filters';
import {
  buildQuickFilterClearPatch,
  stripLowerPriorityQuickConditions,
  useQuickFilterConflictState,
  type FilterRangeQueryKeys,
} from '../components/filter-priority';

type QueryPatch = Record<string, string | number | null>;

interface UseRecordTableFilterPriorityOptions {
  quickFilters: ComputedRef<RecordTableFilterField[]>;
  quickValues: ComputedRef<Record<string, unknown>>;
  filterDraft: StatisticFilterDraftGroup;
  standaloneFilterKeys?: ComputedRef<string[]> | string[];
  rangeKeys?: Record<string, FilterRangeQueryKeys>;
}

function toComputedKeys(source: ComputedRef<string[]> | string[] | undefined) {
  return computed(() => Array.isArray(source) ? source : source?.value ?? []);
}

export function useRecordTableFilterPriority(options: UseRecordTableFilterPriorityOptions) {
  const standaloneFilterKeys = toComputedKeys(options.standaloneFilterKeys);
  const quickFilterConflict = useQuickFilterConflictState({
    quickFilters: options.quickFilters,
    quickValues: options.quickValues,
    filterDraft: options.filterDraft,
  });

  const hiddenFilterKeys = computed(() => [
    ...standaloneFilterKeys.value,
    ...quickFilterConflict.hiddenQuickFilterKeys.value,
  ]);
  const disabledFilterKeys = computed(() => quickFilterConflict.disabledQuickFilterKeys.value);
  const highlightedFilterKeys = computed(() => quickFilterConflict.highlightedQuickFilterKeys.value);

  function buildPriorityApplyPatch(extraPatch: QueryPatch = {}) {
    const resolution = quickFilterConflict.applyConflictResolution();
    stripLowerPriorityQuickConditions(options.filterDraft);
    return {
      ...extraPatch,
      ...buildQuickFilterClearPatch(resolution.clearQuickKeys, options.rangeKeys),
    };
  }

  function resetPriorityState() {
    quickFilterConflict.resetConflictResolution();
  }

  return {
    standaloneFilterKeys,
    hiddenFilterKeys,
    disabledFilterKeys,
    highlightedFilterKeys,
    notifyDetectedConflicts: quickFilterConflict.notifyDetectedConflicts,
    buildPriorityApplyPatch,
    resetPriorityState,
  };
}
