import type {
  RecordTableActiveFilterTag,
} from '../types/record-table';
import type {
  TagGroupResponse,
  TagGroupsResponse,
  TagGroupValueResponse,
  TagSelectionRequest,
} from '../types/api';

export interface TagGroupFilterSnapshot {
  schemaVersion: 1;
  schemaHash: string;
  tagSelections: TagSelectionRequest[];
  fixedFilters?: Record<string, unknown>;
  savedAt?: string;
}

export interface RestoredTagGroupSnapshot {
  tagSelections: TagSelectionRequest[];
  fixedFilters: Record<string, unknown>;
  ignoredCount: number;
  schemaMismatch: boolean;
}

export const TAG_GROUP_SNAPSHOT_SCHEMA_VERSION = 1;

export function normalizeTagSelections(
  selections: TagSelectionRequest[],
  groups: TagGroupResponse[],
): TagSelectionRequest[] {
  const groupByKey = new Map(groups.map((group) => [group.groupKey, group]));
  const merged = new Map<string, string[]>();

  for (const selection of selections) {
    const group = groupByKey.get(selection.groupKey);
    if (!group) {
      continue;
    }
    const validValueKeys = new Set(group.values.map((value) => value.valueKey));
    const current = merged.get(selection.groupKey) ?? [];
    for (const valueKey of selection.valueKeys) {
      if (validValueKeys.has(valueKey) && !current.includes(valueKey)) {
        current.push(valueKey);
      }
    }
    if (current.length > 0) {
      merged.set(selection.groupKey, group.selectionMode === 'single' ? current.slice(0, 1) : current);
    }
  }

  return groups
    .map((group) => {
      const valueKeys = merged.get(group.groupKey) ?? [];
      return {
        groupKey: group.groupKey,
        valueKeys,
      };
    })
    .filter((selection) => selection.valueKeys.length > 0);
}

export function toggleTagSelectionValue(
  selections: TagSelectionRequest[],
  groups: TagGroupResponse[],
  groupKey: string,
  valueKey: string,
): TagSelectionRequest[] {
  const group = groups.find((item) => item.groupKey === groupKey);
  const value = group?.values.find((item) => item.valueKey === valueKey);
  if (!group || !value || value.disabled) {
    return normalizeTagSelections(selections, groups);
  }

  const currentSelection = selections.find((selection) => selection.groupKey === groupKey);
  const currentValueKeys = currentSelection?.valueKeys ?? [];
  const isSelected = currentValueKeys.includes(valueKey);
  const nextValueKeys = group.selectionMode === 'single'
    ? isSelected ? [] : [valueKey]
    : isSelected
      ? currentValueKeys.filter((item) => item !== valueKey)
      : [...currentValueKeys, valueKey];

  return normalizeTagSelections(
    [
      ...selections.filter((selection) => selection.groupKey !== groupKey),
      { groupKey, valueKeys: nextValueKeys },
    ],
    groups,
  );
}

export function buildTagGroupActiveFilterTags(
  selections: TagSelectionRequest[],
  groups: TagGroupResponse[],
): RecordTableActiveFilterTag[] {
  const normalized = normalizeTagSelections(selections, groups);
  return normalized.map((selection) => {
    const group = groups.find((item) => item.groupKey === selection.groupKey);
    const valueLabels = selection.valueKeys
      .map((valueKey) => group?.values.find((value) => value.valueKey === valueKey)?.label ?? valueKey);
    return {
      key: `tagSelection:${selection.groupKey}`,
      label: group?.label ?? selection.groupKey,
      value: valueLabels.join(', '),
    };
  });
}

export function restoreTagGroupSnapshot(
  snapshot: TagGroupFilterSnapshot,
  response: TagGroupsResponse,
): RestoredTagGroupSnapshot {
  const normalized = normalizeTagSelections(snapshot.tagSelections ?? [], response.groups);
  const restoredCount = normalized.reduce((sum, selection) => sum + selection.valueKeys.length, 0);
  const requestedCount = (snapshot.tagSelections ?? [])
    .reduce((sum, selection) => sum + Math.max(1, selection.valueKeys.length), 0);

  return {
    tagSelections: normalized,
    fixedFilters: snapshot.fixedFilters ?? {},
    ignoredCount: Math.max(0, requestedCount - restoredCount),
    schemaMismatch: snapshot.schemaHash !== response.schemaHash,
  };
}

export function createTagGroupSnapshot(
  response: TagGroupsResponse,
  tagSelections: TagSelectionRequest[],
  fixedFilters: Record<string, unknown> = {},
): TagGroupFilterSnapshot {
  return {
    schemaVersion: TAG_GROUP_SNAPSHOT_SCHEMA_VERSION,
    schemaHash: response.schemaHash,
    tagSelections: normalizeTagSelections(tagSelections, response.groups),
    fixedFilters,
    savedAt: new Date().toISOString(),
  };
}

export function isDisabledTagValue(value: TagGroupValueResponse) {
  return value.disabled || value.valueType === 'disabled';
}

export function parseTagSelectionsQuery(rawValue: unknown): TagSelectionRequest[] {
  const text = Array.isArray(rawValue) ? rawValue[0] : rawValue;
  if (!text) {
    return [];
  }
  try {
    const parsed = JSON.parse(String(text)) as TagSelectionRequest[];
    if (!Array.isArray(parsed)) {
      return [];
    }
    return parsed
      .map((selection) => ({
        groupKey: String(selection?.groupKey ?? ''),
        valueKeys: Array.isArray(selection?.valueKeys)
          ? selection.valueKeys.map((valueKey) => String(valueKey ?? '')).filter(Boolean)
          : [],
      }))
      .filter((selection) => selection.groupKey && selection.valueKeys.length > 0);
  } catch {
    return [];
  }
}

export function stringifyTagSelectionsQuery(selections: TagSelectionRequest[]) {
  return selections.length > 0 ? JSON.stringify(selections) : null;
}
