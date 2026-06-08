import { describe, expect, it, vi, beforeEach } from 'vitest';
import { ref } from 'vue';
import { useTagGroupFilterAdapter } from './useTagGroupFilterAdapter';
import type { TagGroupsResponse } from '../types/api';

const tagGroups: TagGroupsResponse = {
  domain: 'issue',
  schemaHash: 'hash-a',
  groups: [
    {
      groupKey: 'module',
      label: '模块',
      selectionMode: 'multiple',
      sortOrder: 10,
      matchStrategyName: 'split_exact_comma',
      values: [
        { valueKey: 'sketch', label: '草图', valueType: 'standard', sortOrder: 10, disabled: false },
        { valueKey: 'surface', label: '曲面', valueType: 'standard', sortOrder: 20, disabled: false },
      ],
    },
  ],
};

describe('useTagGroupFilterAdapter', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it('loads domain tag groups and exposes route-driven selections and active tags', async () => {
    const queryValue = ref(JSON.stringify([{ groupKey: 'module', valueKeys: ['sketch'] }]));
    const loadTagGroups = vi.fn<() => Promise<TagGroupsResponse>>().mockResolvedValue(tagGroups);
    const adapter = useTagGroupFilterAdapter({
      domain: 'issue',
      storageKey: () => 'tag-groups:issue:default',
      tagSelectionsQuery: () => queryValue.value,
      loadTagGroups,
    });

    await adapter.loadTagGroups();

    expect(loadTagGroups).toHaveBeenCalledWith('issue');
    expect(adapter.tagSelections.value).toEqual([{ groupKey: 'module', valueKeys: ['sketch'] }]);
    expect(adapter.tagGroupStorageKey.value).toBe('tag-groups:issue:default');
    expect(adapter.tagGroupActiveFilterTags.value).toEqual([
      { key: 'tagSelection:module', label: '模块', value: '草图' },
    ]);
  });

  it('detects restorable snapshots only when auto restore is active', async () => {
    const queryValue = ref<unknown>(undefined);
    const shouldAutoRestore = ref(true);
    const adapter = useTagGroupFilterAdapter({
      domain: 'issue',
      storageKey: 'tag-groups:issue:default',
      tagSelectionsQuery: () => queryValue.value,
      loadTagGroups: vi.fn<() => Promise<TagGroupsResponse>>().mockResolvedValue(tagGroups),
      shouldAutoRestore: () => shouldAutoRestore.value,
    });
    window.localStorage.setItem(
      'tag-groups:issue:default',
      JSON.stringify({
        schemaVersion: 1,
        snapshots: [
          {
            schemaVersion: 1,
            id: 'snapshot-a',
            schemaHash: 'hash-a',
            tagSelections: [{ groupKey: 'module', valueKeys: ['surface'] }],
            savedAt: '2026-06-08T00:00:00.000Z',
            expiresAt: '2026-07-08T00:00:00.000Z',
            pinned: true,
          },
        ],
        activeSnapshotId: 'snapshot-a',
      }),
    );

    expect(adapter.shouldDeferRowsUntilTagSnapshotRestore()).toBe(false);

    await adapter.loadTagGroups();
    expect(adapter.shouldDeferRowsUntilTagSnapshotRestore()).toBe(true);

    shouldAutoRestore.value = false;
    expect(adapter.shouldDeferRowsUntilTagSnapshotRestore()).toBe(false);
  });
});
