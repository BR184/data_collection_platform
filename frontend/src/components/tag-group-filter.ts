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
  id?: string;
  name?: string;
  schemaHash: string;
  tagSelections: TagSelectionRequest[];
  fixedFilters?: Record<string, unknown>;
  savedAt?: string;
  expiresAt?: string;
  pinned?: boolean;
}

export interface TagGroupFilterSnapshotStore {
  schemaVersion: 1;
  snapshots: TagGroupFilterSnapshot[];
  activeSnapshotId?: string;
  updatedAt?: string;
}

export interface RestoredTagGroupSnapshot {
  tagSelections: TagSelectionRequest[];
  fixedFilters: Record<string, unknown>;
  ignoredCount: number;
  schemaMismatch: boolean;
}

export const TAG_GROUP_SNAPSHOT_SCHEMA_VERSION = 1;
export const TAG_GROUP_SNAPSHOT_MAX_PINNED = 3;
export const TAG_GROUP_SNAPSHOT_TTL_DAYS = 30;

export interface SaveTagGroupSnapshotOptions {
  now?: Date;
  ttlDays?: number;
  maxPinned?: number;
  name?: string;
}

let snapshotIdSequence = 0;

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
  options: SaveTagGroupSnapshotOptions = {},
): TagGroupFilterSnapshot {
  const now = options.now ?? new Date();
  const ttlDays = options.ttlDays ?? TAG_GROUP_SNAPSHOT_TTL_DAYS;
  const savedAt = now.toISOString();
  return {
    schemaVersion: TAG_GROUP_SNAPSHOT_SCHEMA_VERSION,
    id: createSnapshotId(now),
    name: options.name,
    schemaHash: response.schemaHash,
    tagSelections: normalizeTagSelections(tagSelections, response.groups),
    fixedFilters,
    savedAt,
    expiresAt: new Date(now.getTime() + ttlDays * 24 * 60 * 60 * 1000).toISOString(),
    pinned: true,
  };
}

export function parseTagGroupSnapshotStore(
  rawValue: string | null | undefined,
  now = new Date(),
): TagGroupFilterSnapshotStore {
  if (!rawValue) {
    return emptySnapshotStore();
  }
  try {
    const parsed = JSON.parse(rawValue) as unknown;
    const snapshots = isSnapshotStore(parsed)
      ? parsed.snapshots
      : isSnapshot(parsed)
        ? [parsed]
        : [];
    return normalizeSnapshotStore({
      schemaVersion: TAG_GROUP_SNAPSHOT_SCHEMA_VERSION,
      snapshots,
      activeSnapshotId: isSnapshotStore(parsed) ? parsed.activeSnapshotId : snapshots[0]?.id,
      updatedAt: isSnapshotStore(parsed) ? parsed.updatedAt : snapshots[0]?.savedAt,
    }, now);
  } catch {
    return emptySnapshotStore();
  }
}

export function savePinnedTagGroupSnapshot(
  rawValue: string | null | undefined,
  response: TagGroupsResponse,
  tagSelections: TagSelectionRequest[],
  fixedFilters: Record<string, unknown> = {},
  options: SaveTagGroupSnapshotOptions = {},
): TagGroupFilterSnapshotStore {
  const now = options.now ?? new Date();
  const maxPinned = Math.max(1, options.maxPinned ?? TAG_GROUP_SNAPSHOT_MAX_PINNED);
  const snapshot = createTagGroupSnapshot(response, tagSelections, fixedFilters, {
    ...options,
    now,
  });
  const current = parseTagGroupSnapshotStore(rawValue, now);
  const snapshots = [
    snapshot,
    ...current.snapshots.filter((item) => item.id !== snapshot.id),
  ].slice(0, maxPinned);
  return {
    schemaVersion: TAG_GROUP_SNAPSHOT_SCHEMA_VERSION,
    snapshots,
    activeSnapshotId: snapshot.id,
    updatedAt: now.toISOString(),
  };
}

export function getActiveTagGroupSnapshot(store: TagGroupFilterSnapshotStore): TagGroupFilterSnapshot | null {
  return store.snapshots.find((snapshot) => snapshot.id === store.activeSnapshotId) ?? store.snapshots[0] ?? null;
}

export function hasRestorableTagGroupSnapshot(
  rawValue: string | null | undefined,
  response: TagGroupsResponse | null | undefined,
): boolean {
  if (!response) {
    return false;
  }
  const snapshot = getActiveTagGroupSnapshot(parseTagGroupSnapshotStore(rawValue));
  if (!snapshot) {
    return false;
  }
  const restored = restoreTagGroupSnapshot(snapshot, response);
  return restored.tagSelections.length > 0 || Object.keys(restored.fixedFilters).length > 0;
}

function emptySnapshotStore(): TagGroupFilterSnapshotStore {
  return {
    schemaVersion: TAG_GROUP_SNAPSHOT_SCHEMA_VERSION,
    snapshots: [],
  };
}

function normalizeSnapshotStore(
  store: TagGroupFilterSnapshotStore,
  now: Date,
): TagGroupFilterSnapshotStore {
  const snapshots = store.snapshots
    .filter(isSnapshot)
    .map((snapshot, index) => ({
      ...snapshot,
      id: snapshot.id || createSnapshotId(new Date(snapshot.savedAt ?? now), index),
      pinned: snapshot.pinned ?? true,
    }))
    .filter((snapshot) => !isExpiredSnapshot(snapshot, now))
    .sort((left, right) => getSnapshotTime(right) - getSnapshotTime(left));
  const activeSnapshotId = snapshots.some((snapshot) => snapshot.id === store.activeSnapshotId)
    ? store.activeSnapshotId
    : snapshots[0]?.id;
  return {
    schemaVersion: TAG_GROUP_SNAPSHOT_SCHEMA_VERSION,
    snapshots,
    activeSnapshotId,
    updatedAt: store.updatedAt,
  };
}

function isSnapshotStore(value: unknown): value is TagGroupFilterSnapshotStore {
  return Boolean(
    value
    && typeof value === 'object'
    && Array.isArray((value as TagGroupFilterSnapshotStore).snapshots),
  );
}

function isSnapshot(value: unknown): value is TagGroupFilterSnapshot {
  return Boolean(
    value
    && typeof value === 'object'
    && (value as TagGroupFilterSnapshot).schemaVersion === TAG_GROUP_SNAPSHOT_SCHEMA_VERSION
    && Array.isArray((value as TagGroupFilterSnapshot).tagSelections),
  );
}

function isExpiredSnapshot(snapshot: TagGroupFilterSnapshot, now: Date) {
  return Boolean(snapshot.expiresAt && Date.parse(snapshot.expiresAt) <= now.getTime());
}

function getSnapshotTime(snapshot: TagGroupFilterSnapshot) {
  const time = Date.parse(snapshot.savedAt ?? '');
  return Number.isFinite(time) ? time : 0;
}

function createSnapshotId(now: Date, offset = 0) {
  const timestamp = now.getTime() + offset;
  const sequence = snapshotIdSequence;
  snapshotIdSequence = (snapshotIdSequence + 1) % 1_000_000;
  return `snapshot-${timestamp.toString(36)}-${sequence.toString(36)}`;
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
