import { flattenStatisticColumnLeavesFromGroup, type StatisticBoardDefinition } from '../types/api';

const STORAGE_PREFIX = 'stat-board-view:';

interface StoredStatisticBoardViewPrefs {
  visibleColumnKeys: string[];
  groupOrder: string[];
  childGroupOrderByParent: Record<string, string[]>;
  columnOrderByGroup: Record<string, string[]>;
  sortColumnKey: string;
  sortDirection: 'default' | 'asc' | 'desc';
  widthStrategy: 'compact' | 'header' | 'content';
}

export interface StatisticBoardViewPrefs {
  visibleColumnKeys: string[];
  groupOrder: string[];
  childGroupOrderByParent: Record<string, string[]>;
  columnOrderByGroup: Record<string, string[]>;
  sortColumnKey: string;
  sortDirection: 'default' | 'asc' | 'desc';
  widthStrategy: 'compact' | 'header' | 'content';
}

function storageKey(boardKey: string) {
  return `${STORAGE_PREFIX}${boardKey}`;
}

function defaultGroupOrder(definition: StatisticBoardDefinition): string[] {
  return definition.columnGroups.map((group) => group.key);
}

function defaultColumnOrderByGroup(definition: StatisticBoardDefinition): Record<string, string[]> {
  return Object.fromEntries(
    definition.columnGroups.map((group) => [group.key, flattenStatisticColumnLeavesFromGroup(group).map((column) => column.key)]),
  );
}

function defaultChildGroupOrderByParent(definition: StatisticBoardDefinition): Record<string, string[]> {
  const result: Record<string, string[]> = {};

  function walk(groups: StatisticBoardDefinition['columnGroups']) {
    for (const group of groups) {
      const children = group.children ?? [];
      if (children.length > 0) {
        result[group.key] = children.map((child) => child.key);
        walk(children);
      }
    }
  }

  walk(definition.columnGroups);
  return result;
}

function mergeOrderedKeys(persisted: string[], fallback: string[]) {
  const persistedValid = persisted.filter((key) => fallback.includes(key));
  const missing = fallback.filter((key) => !persistedValid.includes(key));
  return [...persistedValid, ...missing];
}

export function defaultVisibleColumnKeys(definition: StatisticBoardDefinition): string[] {
  return definition.columnGroups.flatMap((group) => flattenStatisticColumnLeavesFromGroup(group).map((column) => column.key));
}

export function createDefaultStatisticBoardViewPrefs(definition: StatisticBoardDefinition): StatisticBoardViewPrefs {
  return {
    visibleColumnKeys: defaultVisibleColumnKeys(definition),
    groupOrder: defaultGroupOrder(definition),
    childGroupOrderByParent: defaultChildGroupOrderByParent(definition),
    columnOrderByGroup: defaultColumnOrderByGroup(definition),
    sortColumnKey: '',
    sortDirection: 'default',
    widthStrategy: 'compact',
  };
}

export function loadStatisticBoardViewPrefs(
  boardKey: string,
  definition: StatisticBoardDefinition,
): StatisticBoardViewPrefs {
  const fallbackPrefs = createDefaultStatisticBoardViewPrefs(definition);
  const fallbackVisible = fallbackPrefs.visibleColumnKeys;
  const fallbackGroupOrder = fallbackPrefs.groupOrder;
  const fallbackChildGroupOrderByParent = fallbackPrefs.childGroupOrderByParent;
  const fallbackColumnOrderByGroup = fallbackPrefs.columnOrderByGroup;
  const raw = window.localStorage.getItem(storageKey(boardKey));

  if (!raw) {
    return fallbackPrefs;
  }

  try {
    const parsed = JSON.parse(raw) as Partial<StoredStatisticBoardViewPrefs>;
    const allowedColumnKeys = new Set(fallbackVisible);
    const allowedGroupKeys = new Set(fallbackGroupOrder);

    const visibleColumnKeys = (parsed.visibleColumnKeys ?? []).filter((key) => allowedColumnKeys.has(key));
    const groupOrder = (parsed.groupOrder ?? []).filter((key) => allowedGroupKeys.has(key));
    const resolvedGroupOrder = groupOrder.length ? mergeOrderedKeys(groupOrder, fallbackGroupOrder) : fallbackGroupOrder;
    const childGroupOrderByParent = Object.fromEntries(
      Object.entries(fallbackChildGroupOrderByParent).map(([parentGroupKey, fallbackGroupKeys]) => {
        const persisted = (parsed.childGroupOrderByParent?.[parentGroupKey] ?? []).filter((key) =>
          fallbackGroupKeys.includes(key),
        );
        return [parentGroupKey, persisted.length ? mergeOrderedKeys(persisted, fallbackGroupKeys) : fallbackGroupKeys];
      }),
    );
    const columnOrderByGroup = Object.fromEntries(
      resolvedGroupOrder.map((groupKey) => {
        const fallbackColumns = fallbackColumnOrderByGroup[groupKey] ?? [];
        const persisted = (parsed.columnOrderByGroup?.[groupKey] ?? []).filter((key) => allowedColumnKeys.has(key));
        return [groupKey, persisted.length ? mergeOrderedKeys(persisted, fallbackColumns) : fallbackColumns];
      }),
    );

    return {
      visibleColumnKeys: visibleColumnKeys.length ? visibleColumnKeys : fallbackVisible,
      groupOrder: resolvedGroupOrder,
      childGroupOrderByParent,
      columnOrderByGroup,
      sortColumnKey:
        parsed.sortColumnKey && allowedColumnKeys.has(parsed.sortColumnKey) ? parsed.sortColumnKey : '',
      sortDirection:
        parsed.sortDirection === 'asc' || parsed.sortDirection === 'desc' ? parsed.sortDirection : 'default',
      widthStrategy:
        parsed.widthStrategy === 'header' || parsed.widthStrategy === 'content' ? parsed.widthStrategy : 'compact',
    };
  } catch {
    return fallbackPrefs;
  }
}

export function saveStatisticBoardViewPrefs(boardKey: string, prefs: StatisticBoardViewPrefs) {
  window.localStorage.setItem(storageKey(boardKey), JSON.stringify(prefs));
}

export function resetStatisticBoardViewPrefs(boardKey: string) {
  window.localStorage.removeItem(storageKey(boardKey));
}
