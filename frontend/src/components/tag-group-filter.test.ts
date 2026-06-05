import { describe, expect, it } from 'vitest';
import type { TagGroupsResponse, TagSelectionRequest } from '../types/api';
import {
  buildTagGroupActiveFilterTags,
  normalizeTagSelections,
  restoreTagGroupSnapshot,
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
});
