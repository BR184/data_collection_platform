import { describe, expect, it } from 'vitest';
import type { TagGroupsResponse, TagSelectionRequest } from '../types/api';
import {
  buildTagGroupActiveFilterTags,
  deleteTagGroupSnapshot,
  hasRestorableTagGroupSnapshot,
  normalizeTagSelections,
  parseTagGroupSnapshotStore,
  restoreTagGroupSnapshot,
  savePinnedTagGroupSnapshot,
} from './tag-group-filter';

const groupsResponse: TagGroupsResponse = {
  domain: 'issue',
  schemaHash: 'hash-a',
  groups: [
    {
      groupKey: 'module',
      label: 'Module',
      selectionMode: 'multiple',
      sortOrder: 1,
      matchStrategyName: 'split_exact_comma',
      values: [
        { valueKey: 'sketch', label: 'Sketch', valueType: 'standard', sortOrder: 1, disabled: false },
        { valueKey: 'surface', label: 'Surface', valueType: 'standard', sortOrder: 2, disabled: false },
      ],
    },
    {
      groupKey: 'severity',
      label: 'Severity',
      selectionMode: 'single',
      sortOrder: 2,
      matchStrategyName: 'eq',
      values: [
        { valueKey: 'major', label: 'Major', valueType: 'standard', sortOrder: 1, disabled: false },
        { valueKey: 'minor', label: 'Minor', valueType: 'standard', sortOrder: 2, disabled: false },
      ],
    },
  ],
};

describe('tag group filter helpers', () => {
  it('keeps multiple selections and enforces single-selection groups', () => {
    const selections: TagSelectionRequest[] = [
      { groupKey: 'module', valueKeys: ['sketch', 'surface'] },
      { groupKey: 'severity', valueKeys: ['major', 'minor'] },
    ];

    expect(normalizeTagSelections(selections, groupsResponse.groups)).toEqual([
      { groupKey: 'module', valueKeys: ['sketch', 'surface'] },
      { groupKey: 'severity', valueKeys: ['major'] },
    ]);
  });

  it('builds active filter tags from tag selections', () => {
    const tags = buildTagGroupActiveFilterTags(
      [{ groupKey: 'module', valueKeys: ['sketch', 'surface'] }],
      groupsResponse.groups,
    );

    expect(tags).toEqual([
      {
        key: 'tagSelection:module',
        label: 'Module',
        value: 'Sketch, Surface',
      },
    ]);
  });

  it('restores valid snapshot selections while reporting unknown and stale entries', () => {
    const restored = restoreTagGroupSnapshot(
      {
        schemaVersion: 1,
        schemaHash: 'old-hash',
        tagSelections: [
          { groupKey: 'module', valueKeys: ['sketch', 'missing'] },
          { groupKey: 'unknown', valueKeys: ['ghost'] },
        ],
      },
      groupsResponse,
    );

    expect(restored.tagSelections).toEqual([{ groupKey: 'module', valueKeys: ['sketch'] }]);
    expect(restored.ignoredCount).toBe(2);
    expect(restored.schemaMismatch).toBe(true);
  });

  it('stores pinned snapshots as a collection while keeping the newest three', () => {
    const now = new Date('2026-06-05T00:00:00.000Z');
    let rawValue = '';

    for (const valueKey of ['sketch', 'surface', 'major', 'minor']) {
      const groupKey = valueKey === 'major' || valueKey === 'minor' ? 'severity' : 'module';
      rawValue = JSON.stringify(savePinnedTagGroupSnapshot(
        rawValue,
        groupsResponse,
        [{ groupKey, valueKeys: [valueKey] }],
        {},
        { now },
      ));
    }

    const store = parseTagGroupSnapshotStore(rawValue, now);
    expect(store.snapshots).toHaveLength(3);
    expect(store.snapshots.map((snapshot) => snapshot.tagSelections[0]?.valueKeys[0])).toEqual([
      'minor',
      'major',
      'surface',
    ]);
    expect(store.activeSnapshotId).toBe(store.snapshots[0]?.id);
  });

  it('auto-migrates old single snapshot storage and drops expired snapshots', () => {
    const store = parseTagGroupSnapshotStore(
      JSON.stringify({
        schemaVersion: 1,
        schemaHash: 'hash-a',
        tagSelections: [{ groupKey: 'module', valueKeys: ['sketch'] }],
        savedAt: '2026-05-01T00:00:00.000Z',
        expiresAt: '2026-05-31T00:00:00.000Z',
      }),
      new Date('2026-06-05T00:00:00.000Z'),
    );

    expect(store.snapshots).toEqual([]);
    expect(store.activeSnapshotId).toBeUndefined();
  });

  it('detects whether a snapshot can restore tag selections or fixed filters', () => {
    const rawValue = JSON.stringify({
      schemaVersion: 1,
      snapshots: [
        {
          schemaVersion: 1,
          id: 'snapshot-a',
          schemaHash: 'hash-a',
          tagSelections: [{ groupKey: 'module', valueKeys: ['sketch'] }],
          fixedFilters: { keyword: 'crash' },
          savedAt: '2026-06-05T00:00:00.000Z',
          expiresAt: '2026-07-05T00:00:00.000Z',
          pinned: true,
        },
      ],
      activeSnapshotId: 'snapshot-a',
    });

    expect(hasRestorableTagGroupSnapshot(rawValue, groupsResponse)).toBe(true);
    expect(hasRestorableTagGroupSnapshot('', groupsResponse)).toBe(false);
    expect(hasRestorableTagGroupSnapshot(rawValue, null)).toBe(false);
  });

  it('deletes a snapshot and promotes the next available snapshot', () => {
    const rawValue = JSON.stringify({
      schemaVersion: 1,
      snapshots: [
        {
          schemaVersion: 1,
          id: 'snapshot-a',
          schemaHash: 'hash-a',
          tagSelections: [{ groupKey: 'module', valueKeys: ['sketch'] }],
          savedAt: '2026-06-05T00:00:00.000Z',
          expiresAt: '2026-07-05T00:00:00.000Z',
          pinned: true,
        },
        {
          schemaVersion: 1,
          id: 'snapshot-b',
          schemaHash: 'hash-a',
          tagSelections: [{ groupKey: 'module', valueKeys: ['surface'] }],
          savedAt: '2026-06-04T00:00:00.000Z',
          expiresAt: '2026-07-04T00:00:00.000Z',
          pinned: true,
        },
      ],
      activeSnapshotId: 'snapshot-a',
    });

    const store = deleteTagGroupSnapshot(rawValue, 'snapshot-a', new Date('2026-06-08T00:00:00.000Z'));

    expect(store.snapshots.map((snapshot) => snapshot.id)).toEqual(['snapshot-b']);
    expect(store.activeSnapshotId).toBe('snapshot-b');
  });
});
