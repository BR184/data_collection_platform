import { computed, ref, watch, type ComputedRef } from 'vue';
import type { LocationQuery } from 'vue-router';
import type { StatisticFilterCondition, StatisticFilterField, StatisticFilterOperator } from '../types/api';
import type { RecordTableFilterField } from '../types/record-table';
import {
  sanitizeFilterDraftGroup,
  type StatisticFilterConditionDraft,
  type StatisticFilterDraftGroup,
} from './statistic-board-filters';
import { ElNotification } from '../element-plus-services';

export type FilterPriorityLevel = 'standalone' | 'condition' | 'quick';

export interface FilterConflictField {
  key: string;
  label: string;
  dimension: string;
}

export interface FilterPriorityResolution {
  fields: FilterConflictField[];
  clearQuickKeys: string[];
}

export interface FilterRangeQueryKeys {
  startKey: string;
  endKey: string;
}

export type FilterClearQueryPatch = Record<string, string | number | null>;

const VALUELESS_OPERATORS = new Set<StatisticFilterOperator>(['isEmpty', 'isNotEmpty']);

const FIELD_DIMENSION_ALIASES: Record<string, string> = {
  module: 'moduleName',
  moduleName: 'moduleName',
  moduleNames: 'moduleName',
  project: 'projectName',
  projectName: 'projectName',
  repositoryName: 'projectName',
  projectId: 'projectName',
  testingPhase: 'testingPhase',
  testPhase: 'testingPhase',
  milestone: 'milestoneTitle',
  milestoneTitle: 'milestoneTitle',
  issueIid: 'issueIid',
  iid: 'issueIid',
  title: 'title',
  keyword: 'keyword',
  detailKeyword: 'keyword',
  author: 'authorName',
  authorName: 'authorName',
  owner: 'ownerName',
  ownerName: 'ownerName',
  reviewOwner: 'ownerName',
  reviewerName: 'reviewerName',
  reviewExpert: 'reviewerName',
  assigneeName: 'assigneeName',
  mergedBy: 'mergedBy',
  createdBy: 'authorName',
  updatedBy: 'updatedBy',
  charger: 'ownerName',
  functionName: 'functionName',
  reasonCategory: 'reasonCategory',
  illegalReason: 'illegalReason',
  illegalType: 'illegalReason',
  severity: 'severityLevel',
  severityLevel: 'severityLevel',
  priorityLevel: 'priorityLevel',
  bugStatus: 'bugStatus',
  category: 'category',
  issueState: 'issueState',
  state: 'issueState',
  targetBranch: 'targetBranch',
  mergedAt: 'mergedAt',
  mergedAtRange: 'mergedAt',
  createdAt: 'createdAt',
  createdAtRange: 'createdAt',
  updatedAt: 'updatedAt',
  updatedAtRange: 'updatedAt',
  source: 'source',
  sourceInstance: 'source',
};

export function filterDimensionKey(key: string | null | undefined) {
  const raw = String(key ?? '').trim();
  if (!raw) {
    return '';
  }
  const detailMatch = /^detailFilter\.(.+)$/.exec(raw);
  const normalized = (detailMatch?.[1] ?? raw).replace(/[-_]/g, '');
  return FIELD_DIMENSION_ALIASES[raw]
    ?? FIELD_DIMENSION_ALIASES[detailMatch?.[1] ?? '']
    ?? FIELD_DIMENSION_ALIASES[normalized]
    ?? normalized;
}

export function filterFieldsByBlockedDimensions<T extends { key: string }>(
  fields: T[],
  blockedKeys: Iterable<string | null | undefined>,
) {
  const blocked = new Set(Array.from(blockedKeys, filterDimensionKey).filter(Boolean));
  return fields.filter((field) => !blocked.has(filterDimensionKey(field.key)));
}

export function filterBlockedKeys<T extends { key: string }>(
  fields: T[],
  blockedKeys: Iterable<string | null | undefined>,
) {
  const blocked = new Set(Array.from(blockedKeys, filterDimensionKey).filter(Boolean));
  return fields.filter((field) => blocked.has(filterDimensionKey(field.key))).map((field) => field.key);
}

export function hasFilterValue(value: unknown) {
  if (Array.isArray(value)) {
    return value.some((item) => String(item ?? '').trim().length > 0);
  }
  return String(value ?? '').trim().length > 0;
}

export function isCompleteFilterCondition(condition: Pick<StatisticFilterConditionDraft, 'fieldKey' | 'operator' | 'value' | 'secondaryValue' | 'labelGroupId' | 'valueType'>) {
  if (!condition.fieldKey || !condition.operator) {
    return false;
  }
  if (VALUELESS_OPERATORS.has(condition.operator)) {
    return true;
  }
  if (condition.valueType === 'LABEL_GROUP') {
    return Boolean(condition.labelGroupId);
  }
  if (!hasFilterValue(condition.value)) {
    return false;
  }
  return condition.operator !== 'between' || hasFilterValue(condition.secondaryValue);
}

export function isCompleteSanitizedCondition(condition: StatisticFilterCondition) {
  if (!condition.fieldKey || !condition.operator) {
    return false;
  }
  if (VALUELESS_OPERATORS.has(condition.operator)) {
    return true;
  }
  if (condition.valueType === 'LABEL_GROUP') {
    return Boolean(condition.labelGroupId);
  }
  if (!hasFilterValue(condition.value)) {
    return false;
  }
  return condition.operator !== 'between' || hasFilterValue(condition.secondaryValue);
}

export function completedConditionDimensions(filterDraft: StatisticFilterDraftGroup) {
  return new Set(
    filterDraft.conditions
      .filter((condition) => condition.source !== 'QUICK')
      .filter(isCompleteFilterCondition)
      .map((condition) => filterDimensionKey(condition.fieldKey))
      .filter(Boolean),
  );
}

export function conditionDuplicateFields(
  quickFilters: RecordTableFilterField[],
  quickValues: Record<string, unknown>,
  filterDraft: StatisticFilterDraftGroup,
) {
  return conditionCoveredQuickFields(quickFilters, filterDraft)
    .filter((field) => hasFilterValue(quickValues[field.key]));
}

export function conditionCoveredQuickFields(
  quickFilters: RecordTableFilterField[],
  filterDraft: StatisticFilterDraftGroup,
) {
  const conditionDimensions = completedConditionDimensions(filterDraft);
  return quickFilters
    .filter((field) => conditionDimensions.has(filterDimensionKey(field.key)))
    .map(toConflictField);
}

export function routeQuickDuplicateFields(
  quickFilters: RecordTableFilterField[],
  routeQuery: LocationQuery,
  filterDraft: StatisticFilterDraftGroup,
) {
  const conditionDimensions = completedConditionDimensions(filterDraft);
  return quickFilters
    .filter((field) => conditionDimensions.has(filterDimensionKey(field.key)) && hasFilterValue(routeQuery[field.key]))
    .map(toConflictField);
}

export function stripLowerPriorityQuickConditions(filterDraft: StatisticFilterDraftGroup) {
  const conditionDimensions = completedConditionDimensions(filterDraft);
  const kept: StatisticFilterConditionDraft[] = [];
  const removed: FilterConflictField[] = [];
  for (const condition of filterDraft.conditions) {
    const dimension = filterDimensionKey(condition.fieldKey);
    if (!dimension || !isCompleteFilterCondition(condition)) {
      kept.push(condition);
      continue;
    }
    if (condition.source === 'QUICK' && conditionDimensions.has(dimension)) {
      removed.push({ key: condition.fieldKey, label: condition.fieldKey, dimension });
      continue;
    }
    kept.push(condition);
  }
  if (removed.length) {
    filterDraft.conditions.splice(0, filterDraft.conditions.length, ...kept);
  }
  return removed;
}

export function filterGroupWithoutBlockedDimensions(
  filterDraft: StatisticFilterDraftGroup,
  blockedKeys: Iterable<string | null | undefined>,
) {
  const blocked = new Set(Array.from(blockedKeys, filterDimensionKey).filter(Boolean));
  const sanitized = sanitizeFilterDraftGroup(filterDraft);
  if (!sanitized) {
    return null;
  }
  const conditions = sanitized.conditions.filter((condition) => !blocked.has(filterDimensionKey(condition.fieldKey)));
  return conditions.length ? { ...sanitized, conditions } : null;
}

export function notifyFilterConflict(fields: FilterConflictField[], phase: 'detected' | 'applied' | 'blocked') {
  if (!fields.length) {
    return;
  }
  const names = uniqueConflictLabels(fields).join('、');
  ElNotification.warning({
    title: phase === 'applied' ? '已按筛选优先级处理' : '筛选条件存在重复',
    message: phase === 'detected'
      ? `检测到「${names}」已经在条件筛选中设置，快速筛选中的对应项将被临时禁用并高亮提示。`
      : phase === 'blocked'
        ? `「${names}」已在条件筛选中设置，本次快速筛选不会生效。请调整条件筛选或清空该条件后再使用快速筛选。`
        : `「${names}」已由条件筛选接管，快速筛选中的对应项已隐藏并不再参与查询。`,
    duration: 5200,
    showClose: true,
  });
}

export function uniqueConflictLabels(fields: FilterConflictField[]) {
  const seen = new Set<string>();
  const labels: string[] = [];
  for (const field of fields) {
    const label = field.label || field.key;
    if (seen.has(label)) {
      continue;
    }
    seen.add(label);
    labels.push(label);
  }
  return labels;
}

export function toConflictField(field: Pick<RecordTableFilterField | StatisticFilterField, 'key' | 'label'>): FilterConflictField {
  return {
    key: field.key,
    label: field.label,
    dimension: filterDimensionKey(field.key),
  };
}

export function useQuickFilterConflictState(options: {
  quickFilters: ComputedRef<RecordTableFilterField[]>;
  quickValues: ComputedRef<Record<string, unknown>>;
  filterDraft: StatisticFilterDraftGroup;
}) {
  const hiddenDimensions = ref<Set<string>>(new Set());
  const interceptedQuickFilterKeys = ref<Set<string>>(new Set());
  const lastDetectedSignature = ref('');
  const lastDetectedNotification = ref({ signature: '', timestamp: 0 });
  const interceptedHighlightTimers = new Map<string, ReturnType<typeof window.setTimeout>>();
  const conflictFields = computed(() =>
    conditionDuplicateFields(options.quickFilters.value, options.quickValues.value, options.filterDraft)
      .filter((field) => !hiddenDimensions.value.has(field.dimension)),
  );
  const quickValueConflictFields = computed(() =>
    conflictFields.value.filter((field) => hasFilterValue(options.quickValues.value[field.key])),
  );
  const disabledQuickFilterKeys = computed(() => conflictFields.value.map((field) => field.key));
  const highlightedQuickFilterKeys = computed(() => [
    ...conflictFields.value.map((field) => field.key),
    ...interceptedQuickFilterKeys.value,
  ]);
  const hiddenQuickFilterKeys = computed(() =>
    options.quickFilters.value
      .filter((field) => hiddenDimensions.value.has(filterDimensionKey(field.key)))
      .map((field) => field.key),
  );
  const visibleQuickFilters = computed(() =>
    options.quickFilters.value.filter((field) => !hiddenDimensions.value.has(filterDimensionKey(field.key))),
  );

  watch(
    () => conflictFields.value.map((field) => `${field.key}:${field.dimension}`).sort().join('|'),
    () => notifyDetectedConflicts(),
    { flush: 'post', immediate: true },
  );

  watch(
    () => Array.from(completedConditionDimensions(options.filterDraft)).sort().join('|'),
    () => pruneStaleHiddenDimensions(),
    { flush: 'post' },
  );

  function notifyDetectedConflicts(force = false) {
    const signature = conflictFields.value.map((field) => field.dimension).sort().join('|');
    if (!signature) {
      lastDetectedSignature.value = '';
      return;
    }
    if (!force && signature === lastDetectedSignature.value) {
      return;
    }
    const now = Date.now();
    lastDetectedSignature.value = signature;
    lastDetectedNotification.value = { signature, timestamp: now };
    notifyFilterConflict(conflictFields.value, 'detected');
  }

  function guardQuickFilterChange(key: string, value: unknown) {
    if (!hasFilterValue(value)) {
      return true;
    }
    const field = quickFilterConflictField(key);
    if (!field) {
      return true;
    }
    notifyFilterConflict([field], 'blocked');
    flashQuickFilter(field.key);
    return false;
  }

  function quickFilterConflictField(key: string) {
    const dimension = filterDimensionKey(key);
    if (!dimension || hiddenDimensions.value.has(dimension)) {
      return null;
    }
    if (!completedConditionDimensions(options.filterDraft).has(dimension)) {
      return null;
    }
    return options.quickFilters.value
      .filter((field) => filterDimensionKey(field.key) === dimension)
      .map(toConflictField)[0] ?? null;
  }

  function flashQuickFilter(key: string) {
    const nextKeys = new Set(interceptedQuickFilterKeys.value);
    nextKeys.add(key);
    interceptedQuickFilterKeys.value = nextKeys;
    const existingTimer = interceptedHighlightTimers.get(key);
    if (existingTimer) {
      window.clearTimeout(existingTimer);
    }
    interceptedHighlightTimers.set(key, window.setTimeout(() => {
      const remainingKeys = new Set(interceptedQuickFilterKeys.value);
      remainingKeys.delete(key);
      interceptedQuickFilterKeys.value = remainingKeys;
      interceptedHighlightTimers.delete(key);
    }, 1800));
  }

  function applyConflictResolution(): FilterPriorityResolution {
    const fields = conflictFields.value;
    if (!fields.length) {
      return { fields: [], clearQuickKeys: [] };
    }
    const nextHidden = new Set(hiddenDimensions.value);
    for (const field of fields) {
      nextHidden.add(field.dimension);
    }
    hiddenDimensions.value = nextHidden;
    notifyFilterConflict(fields, 'applied');
    return {
      fields,
      clearQuickKeys: quickValueConflictFields.value.map((field) => field.key),
    };
  }

  function resetConflictResolution() {
    hiddenDimensions.value = new Set();
    interceptedQuickFilterKeys.value = new Set();
    for (const timer of interceptedHighlightTimers.values()) {
      window.clearTimeout(timer);
    }
    interceptedHighlightTimers.clear();
    lastDetectedSignature.value = '';
    lastDetectedNotification.value = { signature: '', timestamp: 0 };
  }

  function pruneStaleHiddenDimensions() {
    if (!hiddenDimensions.value.size) {
      return;
    }
    const conditionDimensions = completedConditionDimensions(options.filterDraft);
    const nextHidden = new Set(
      Array.from(hiddenDimensions.value).filter((dimension) => conditionDimensions.has(dimension)),
    );
    if (nextHidden.size !== hiddenDimensions.value.size) {
      hiddenDimensions.value = nextHidden;
    }
  }

  return {
    conflictFields,
    disabledQuickFilterKeys,
    highlightedQuickFilterKeys,
    hiddenQuickFilterKeys,
    visibleQuickFilters,
    notifyDetectedConflicts,
    guardQuickFilterChange,
    applyConflictResolution,
    resetConflictResolution,
  };
}

export function buildQuickFilterClearPatch(
  keys: string[],
  rangeKeys: Record<string, FilterRangeQueryKeys> = {},
): FilterClearQueryPatch {
  const patch: FilterClearQueryPatch = {};
  for (const key of keys) {
    const range = rangeKeys[key];
    if (range) {
      patch[range.startKey] = null;
      patch[range.endKey] = null;
      continue;
    }
    patch[key] = null;
  }
  return patch;
}
