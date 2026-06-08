import { computed, ref } from 'vue';
import type { TagGroupsResponse } from '../types/api';
import type { RecordTableActiveFilterTag } from '../types/record-table';
import {
  buildTagGroupActiveFilterTags,
  hasRestorableTagGroupSnapshot,
  parseTagSelectionsQuery,
} from '../components/tag-group-filter';

type Getter<T> = () => T;

export interface UseTagGroupFilterAdapterOptions {
  domain: string;
  storageKey: string | Getter<string>;
  tagSelectionsQuery: Getter<unknown>;
  loadTagGroups: (domain: string) => Promise<TagGroupsResponse>;
  shouldAutoRestore?: Getter<boolean>;
}

export function useTagGroupFilterAdapter(options: UseTagGroupFilterAdapterOptions) {
  const tagGroups = ref<TagGroupsResponse | null>(null);
  const storageKey = computed(() =>
    typeof options.storageKey === 'function' ? options.storageKey() : options.storageKey,
  );
  const tagSelections = computed(() => parseTagSelectionsQuery(options.tagSelectionsQuery()));
  const shouldAutoRestoreTagSnapshot = computed(() =>
    options.shouldAutoRestore ? options.shouldAutoRestore() : options.tagSelectionsQuery() == null,
  );
  const activeFilterTags = computed<RecordTableActiveFilterTag[]>(() =>
    buildTagGroupActiveFilterTags(tagSelections.value, tagGroups.value?.groups ?? []),
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

  return {
    tagGroups,
    tagSelections,
    tagGroupStorageKey: storageKey,
    shouldAutoRestoreTagSnapshot,
    tagGroupActiveFilterTags: activeFilterTags,
    loadTagGroups,
    shouldDeferRowsUntilTagSnapshotRestore,
  };
}
