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
  const conditionDimensions = completedConditionDimensions(filterDraft);
  return quickFilters
    .filter((field) => conditionDimensions.has(filterDimensionKey(field.key)) && hasFilterValue(quickValues[field.key]))
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

export function notifyFilterConflict(fields: FilterConflictField[], phase: 'detected' | 'applied') {
  if (!fields.length) {
    return;
  }
  const names = uniqueConflictLabels(fields).join('、');
  ElNotification.warning({
    title: phase === 'detected' ? '筛选条件存在重复' : '已按筛选优先级处理',
    message: phase === 'detected'
      ? `检测到「${names}」已经在条件筛选中设置，快速筛选中的对应项将被禁用。`
      : `「${names}」已由条件筛选接管，快速筛选中的对应项已隐藏并不再参与查询。`,
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
  const lastDetectedSignature = ref('');
  const conflictFields = computed(() =>
    conditionDuplicateFields(options.quickFilters.value, options.quickValues.value, options.filterDraft)
      .filter((field) => !hiddenDimensions.value.has(field.dimension)),
  );
  const disabledQuickFilterKeys = computed(() => conflictFields.value.map((field) => field.key));
  const highlightedQuickFilterKeys = computed(() => conflictFields.value.map((field) => field.key));
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
  );

  function notifyDetectedConflicts() {
    const signature = conflictFields.value.map((field) => field.dimension).sort().join('|');
    if (!signature || signature === lastDetectedSignature.value) {
      return;
    }
    lastDetectedSignature.value = signature;
    notifyFilterConflict(conflictFields.value, 'detected');
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
      clearQuickKeys: fields.map((field) => field.key),
    };
  }

  function resetConflictResolution() {
    hiddenDimensions.value = new Set();
    lastDetectedSignature.value = '';
  }

  return {
    conflictFields,
    disabledQuickFilterKeys,
    highlightedQuickFilterKeys,
    hiddenQuickFilterKeys,
    visibleQuickFilters,
    notifyDetectedConflicts,
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
