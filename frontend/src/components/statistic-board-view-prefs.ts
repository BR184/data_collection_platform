import { flattenStatisticColumnLeavesFromGroup, type StatisticBoardDefinition } from '../types/api';

const STORAGE_PREFIX = 'stat-board-view:';

export interface StatisticBoardViewPrefs {
  /** 只记录主动隐藏意图；暂未出现在当前行维度的列也保留，新增列默认可见。 */
  hiddenColumnKeys: string[];
  groupOrder: string[];
  childGroupOrderByParent: Record<string, string[]>;
  columnOrderByGroup: Record<string, string[]>;
  sortColumnKey: string;
  sortDirection: 'default' | 'asc' | 'desc';
  widthStrategy: 'compact' | 'header' | 'content';
  stickyHeaderEnabled?: boolean;
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
  const persistedValid = [...new Set(persisted.filter((key) => fallback.includes(key)))];
  const missing = fallback.filter((key) => !persistedValid.includes(key));
  return [...persistedValid, ...missing];
}

/** 返回当前服务端定义的全部叶列，保持定义顺序。 */
export function statisticColumnKeys(definition: StatisticBoardDefinition): string[] {
  return definition.columnGroups.flatMap((group) => flattenStatisticColumnLeavesFromGroup(group).map((column) => column.key));
}

/** 从当前定义建立默认视图，不隐藏任何列。 */
export function createDefaultStatisticBoardViewPrefs(definition: StatisticBoardDefinition): StatisticBoardViewPrefs {
  return {
    hiddenColumnKeys: [],
    groupOrder: defaultGroupOrder(definition),
    childGroupOrderByParent: defaultChildGroupOrderByParent(definition),
    columnOrderByGroup: defaultColumnOrderByGroup(definition),
    sortColumnKey: '',
    sortDirection: 'default',
    widthStrategy: 'compact',
    stickyHeaderEnabled: true,
  };
}

function stringKeys(value: unknown): string[] {
  return Array.isArray(value) ? [...new Set(value.filter((key): key is string => typeof key === 'string'))] : [];
}

function objectValue(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

/**
 * 将浏览器配置或收藏存档解析为唯一运行模型，并与当前定义协调布局。
 *
 * 旧可见列存档仅在此边界迁入：旧列顺序记录了当时已知全集，其与旧可见集合的差才是主动隐藏。
 * 当前定义中新出现的列不能据此判为隐藏；无法从旧存档证明的隐藏意图不猜测。
 * 隐藏集合保留当前维度之外的键，以便维度往返后仍恢复用户选择。
 */
export function resolveStatisticBoardViewPrefs(
  value: unknown, definition: StatisticBoardDefinition,
): StatisticBoardViewPrefs {
  const parsed = objectValue(value);
  const fallbackPrefs = createDefaultStatisticBoardViewPrefs(definition);
  const allColumnKeys = statisticColumnKeys(definition);
  const fallbackGroupOrder = fallbackPrefs.groupOrder;
  const fallbackChildGroupOrderByParent = fallbackPrefs.childGroupOrderByParent;
  const fallbackColumnOrderByGroup = fallbackPrefs.columnOrderByGroup;
  const columnOrders = objectValue(parsed.columnOrderByGroup);
  const childOrders = objectValue(parsed.childGroupOrderByParent);
  const legacyVisible = new Set(stringKeys(parsed.visibleColumnKeys));
  const hiddenColumnKeys = Array.isArray(parsed.hiddenColumnKeys)
    ? stringKeys(parsed.hiddenColumnKeys)
    : [...new Set(Object.values(columnOrders).flatMap(stringKeys))].filter(key => !legacyVisible.has(key));
  const hidden = new Set(hiddenColumnKeys);
  // 损坏或不适用于当前定义的配置不能令整表没有指标列；其它维度的隐藏意图仍保留。
  const resolvedHidden = allColumnKeys.length && allColumnKeys.every(key => hidden.has(key))
    ? hiddenColumnKeys.filter(key => !allColumnKeys.includes(key)) : hiddenColumnKeys;
  const allowedColumnKeys = new Set(allColumnKeys);
  const allowedGroupKeys = new Set(fallbackGroupOrder);
  const groupOrder = stringKeys(parsed.groupOrder).filter((key) => allowedGroupKeys.has(key));
  const resolvedGroupOrder = groupOrder.length ? mergeOrderedKeys(groupOrder, fallbackGroupOrder) : fallbackGroupOrder;
  const childGroupOrderByParent = Object.fromEntries(
    Object.entries(fallbackChildGroupOrderByParent).map(([parentGroupKey, fallbackGroupKeys]) => {
      const persisted = stringKeys(childOrders[parentGroupKey]).filter((key) => fallbackGroupKeys.includes(key));
      return [parentGroupKey, persisted.length ? mergeOrderedKeys(persisted, fallbackGroupKeys) : fallbackGroupKeys];
    }),
  );
  const columnOrderByGroup = Object.fromEntries(
    resolvedGroupOrder.map((groupKey) => {
      const fallbackColumns = fallbackColumnOrderByGroup[groupKey] ?? [];
      const persisted = stringKeys(columnOrders[groupKey]).filter((key) => fallbackColumns.includes(key));
      return [groupKey, persisted.length ? mergeOrderedKeys(persisted, fallbackColumns) : fallbackColumns];
    }),
  );

  return {
    hiddenColumnKeys: resolvedHidden,
    groupOrder: resolvedGroupOrder,
    childGroupOrderByParent,
    columnOrderByGroup,
    sortColumnKey:
      typeof parsed.sortColumnKey === 'string' && allowedColumnKeys.has(parsed.sortColumnKey) ? parsed.sortColumnKey : '',
    sortDirection:
      parsed.sortDirection === 'asc' || parsed.sortDirection === 'desc' ? parsed.sortDirection : 'default',
    widthStrategy:
      parsed.widthStrategy === 'header' || parsed.widthStrategy === 'content' ? parsed.widthStrategy : 'compact',
    stickyHeaderEnabled: parsed.stickyHeaderEnabled !== false,
  };
}

/** 加载浏览器存档；已发布的旧格式读取后一次性迁入隐藏意图格式。存储不可用时仍返回可用视图。 */
export function loadStatisticBoardViewPrefs(boardKey: string, definition: StatisticBoardDefinition): StatisticBoardViewPrefs {
  let parsed: unknown;
  try {
    parsed = JSON.parse(window.localStorage.getItem(storageKey(boardKey)) ?? 'null');
  } catch {
    return createDefaultStatisticBoardViewPrefs(definition);
  }
  const prefs = resolveStatisticBoardViewPrefs(parsed, definition);
  const stored = objectValue(parsed);
  if (Array.isArray(stored.visibleColumnKeys) && !Array.isArray(stored.hiddenColumnKeys)) {
    try {
      saveStatisticBoardViewPrefs(boardKey, prefs);
    } catch {
      // 只读/配额限制不妨碍本次使用已迁入的视图；下次可写时再持久化。
    }
  }
  return prefs;
}

/** 保存唯一隐藏意图模型，不再写入旧的可见列快照。 */
export function saveStatisticBoardViewPrefs(boardKey: string, prefs: StatisticBoardViewPrefs) {
  window.localStorage.setItem(storageKey(boardKey), JSON.stringify(prefs));
}

/** 删除该看板的浏览器偏好，不影响其它看板。 */
export function resetStatisticBoardViewPrefs(boardKey: string) {
  window.localStorage.removeItem(storageKey(boardKey));
}
