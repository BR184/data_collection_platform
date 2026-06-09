import { computed, ref } from 'vue';
import type { TagGroupsResponse } from '../types/api';
import type { RecordTableActiveFilterTag } from '../types/record-table';
import {
  buildTagGroupActiveFilterTags,
  hasRestorableTagGroupSnapshot,
  normalizeTagSelections,
  parseTagSelectionsQuery,
} from '../components/tag-group-filter';
import type { TagSelectionRequest } from '../types/api';

type Getter<T> = () => T;

export interface UseTagGroupFilterAdapterOptions {
  domain: string;
  storageKey: string | Getter<string>;
  tagSelectionsQuery: Getter<unknown>;
  loadTagGroups: (domain: string) => Promise<TagGroupsResponse>;
  shouldAutoRestore?: Getter<boolean>;
  fixedFilterValues?: Getter<Record<string, unknown>>;
  dimensionMappings?: Record<string, string>;
}

export function useTagGroupFilterAdapter(options: UseTagGroupFilterAdapterOptions) {
  const tagGroups = ref<TagGroupsResponse | null>(null);
  const storageKey = computed(() =>
    typeof options.storageKey === 'function' ? options.storageKey() : options.storageKey,
  );
  const routeTagSelections = computed(() => parseTagSelectionsQuery(options.tagSelectionsQuery()));
  const tagSelections = computed(() =>
    applyFixedFilterMappings(routeTagSelections.value, options.fixedFilterValues?.() ?? {}),
  );
  const shouldAutoRestoreTagSnapshot = computed(() =>
    options.shouldAutoRestore ? options.shouldAutoRestore() : options.tagSelectionsQuery() == null,
  );
  const mappedGroupKeys = computed(() => new Set(Object.keys(options.dimensionMappings ?? {})));
  const activeFilterTags = computed<RecordTableActiveFilterTag[]>(() =>
    buildTagGroupActiveFilterTags(
      tagSelections.value.filter((selection) => !mappedGroupKeys.value.has(selection.groupKey)),
      tagGroups.value?.groups ?? [],
    ),
  );

  async function loadTagGroups() {
    tagGroups.value = await options.loadTagGroups(options.domain);
  }

  function shouldDeferRowsUntilTagSnapshotRestore() {
    if (!shouldAutoRestoreTagSnapshot.value || typeof window === 'undefined') {
      return false;
    }
    return hasRestorableTagGroupSnapshot(
      window.localStorage.getItem(storageKey.value),
      tagGroups.value,
    );
  }

  function syncFixedFilterToTagGroup(queryKey: string, value: unknown) {
    const groupKey = groupKeyForQueryKey(queryKey);
    if (!groupKey) {
      return null;
    }
    return setMappedTagSelection(tagSelections.value, groupKey, value);
  }

  function syncFixedFiltersToTagGroups() {
    const fixedFilters = options.fixedFilterValues?.() ?? {};
    const mappedQueryKeys = Object.values(options.dimensionMappings ?? {});
    if (!mappedQueryKeys.some((queryKey) => normalizeMappedValue(fixedFilters[queryKey]))) {
      return null;
    }
    return applyFixedFilterMappings(routeTagSelections.value, fixedFilters);
  }

  function syncTagGroupToFixedFilter(nextSelections: TagSelectionRequest[]) {
    const patch: Record<string, string | null> = {};
    for (const [groupKey, queryKey] of Object.entries(options.dimensionMappings ?? {})) {
      const value = nextSelections.find((selection) => selection.groupKey === groupKey)?.valueKeys[0] ?? '';
      patch[queryKey] = value || null;
    }
    return patch;
  }

  function groupKeyForQueryKey(queryKey: string) {
    return Object.entries(options.dimensionMappings ?? {})
      .find(([, mappedQueryKey]) => mappedQueryKey === queryKey)?.[0] ?? '';
  }

  function applyFixedFilterMappings(
    selections: TagSelectionRequest[],
    fixedFilters: Record<string, unknown>,
  ) {
    let nextSelections = [...selections];
    for (const [groupKey, queryKey] of Object.entries(options.dimensionMappings ?? {})) {
      const value = normalizeMappedValue(fixedFilters[queryKey]);
      if (value) {
        nextSelections = setMappedTagSelection(nextSelections, groupKey, value);
      }
    }
    return normalizeWithLoadedGroups(nextSelections);
  }

  function setMappedTagSelection(
    selections: TagSelectionRequest[],
    groupKey: string,
    value: unknown,
  ) {
    const normalizedValue = normalizeMappedValue(value);
    const nextSelections = selections.filter((selection) => selection.groupKey !== groupKey);
    if (normalizedValue) {
      nextSelections.push({ groupKey, valueKeys: [normalizedValue] });
    }
    return normalizeWithLoadedGroups(nextSelections);
  }

  function normalizeWithLoadedGroups(selections: TagSelectionRequest[]) {
    return Array.isArray(tagGroups.value?.groups)
      ? normalizeTagSelections(selections, tagGroups.value.groups)
      : selections.filter((selection) => selection.groupKey && selection.valueKeys.length > 0);
  }

  return {
    tagGroups,
    tagSelections,
    tagGroupStorageKey: storageKey,
    shouldAutoRestoreTagSnapshot,
    tagGroupActiveFilterTags: activeFilterTags,
    loadTagGroups,
    shouldDeferRowsUntilTagSnapshotRestore,
    syncFixedFilterToTagGroup,
    syncFixedFiltersToTagGroups,
    syncTagGroupToFixedFilter,
  };
}

function normalizeMappedValue(value: unknown) {
  return Array.isArray(value) ? String(value[0] ?? '') : String(value ?? '');
}
