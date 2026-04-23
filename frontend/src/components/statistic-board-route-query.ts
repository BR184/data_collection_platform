import type { LocationQuery } from 'vue-router';
import type { StatisticFilterGroup, StatisticFilterOperator } from '../types/api';
import type { SortDirection } from './statistic-board-sorting';
import {
  createEmptyFilterGroup,
  sanitizeFilterDraftGroup,
  type StatisticFilterConditionDraft,
  type StatisticFilterDraftGroup,
} from './statistic-board-filters';

function nextConditionId() {
  if (typeof globalThis.crypto?.randomUUID === 'function') {
    return globalThis.crypto.randomUUID();
  }
  return `condition-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

const FILTER_GROUP_QUERY_KEY = 'filterGroup';

export function parsePositiveInteger(rawValue: unknown, fallback: number) {
  const parsed = Number.parseInt(String(rawValue ?? ''), 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

export function parseSortDirection(rawValue: unknown, fallback: SortDirection) {
  return rawValue === 'asc' || rawValue === 'desc' || rawValue === 'default' ? rawValue : fallback;
}

export function routeBoardSortColumn(query: LocationQuery) {
  return String(query.sortBy ?? '');
}

export function routeBoardSortDirection(query: LocationQuery) {
  return parseSortDirection(query.sortOrder, 'default');
}

export function routeDetailPage(query: LocationQuery) {
  return parsePositiveInteger(query.detailPage, 1);
}

export function routeDetailPageSize(query: LocationQuery, fallback: number) {
  return parsePositiveInteger(query.detailPageSize, fallback);
}

export function routeDetailSortBy(query: LocationQuery) {
  return String(query.detailSortBy ?? 'syncedAt');
}

export function routeDetailSortOrder(query: LocationQuery) {
  const value = String(query.detailSortOrder ?? 'descending');
  return value === 'ascending' || value === 'descending' ? value : 'descending';
}

export function routeDetailVisible(query: LocationQuery) {
  return String(query.detailVisible ?? '') === '1';
}

export function buildFilterGroupFromRouteQuery(query: LocationQuery) {
  const serializedFilterGroup = firstQueryValue(query[FILTER_GROUP_QUERY_KEY]);
  if (serializedFilterGroup) {
    const parsed = parseRouteFilterGroup(serializedFilterGroup);
    if (parsed) {
      return toDraftFilterGroup(parsed);
    }
  }

  const draftGroup = createEmptyFilterGroup();
  draftGroup.logic = query.filterLogic === 'OR' ? 'OR' : 'AND';
  const keyedConditions = new Map<number, StatisticFilterConditionDraft>();

  for (const [key, rawValue] of Object.entries(query)) {
    const match = /^filters\.(\d+)\.(field|operator|value|value2)$/.exec(key);
    if (!match) {
      continue;
    }
    const index = Number.parseInt(match[1], 10);
    const nextValue = Array.isArray(rawValue) ? String(rawValue[0] ?? '') : String(rawValue ?? '');
    const currentCondition =
      keyedConditions.get(index) ?? {
        id: nextConditionId(),
        fieldKey: '',
        operator: '' as StatisticFilterOperator | '',
        value: '',
        secondaryValue: '',
      };
    if (match[2] === 'field') {
      currentCondition.fieldKey = nextValue;
    } else if (match[2] === 'operator') {
      currentCondition.operator = nextValue as StatisticFilterOperator;
    } else if (match[2] === 'value') {
      currentCondition.value = nextValue;
    } else {
      currentCondition.secondaryValue = nextValue;
    }
    keyedConditions.set(index, currentCondition);
  }

  draftGroup.conditions.push(
    ...[...keyedConditions.entries()].sort((left, right) => left[0] - right[0]).map(([, condition]) => condition),
  );
  return draftGroup;
}

export function buildFilterQueryPatch(query: LocationQuery, filterDraft: Pick<StatisticFilterDraftGroup, 'logic' | 'conditions'>) {
  const sanitizedFilterGroup = sanitizeFilterDraftGroup({
    logic: filterDraft.logic,
    conditions: [...filterDraft.conditions],
  });
  const patch: Record<string, string | number | null> = {
    filterLogic: null,
    [FILTER_GROUP_QUERY_KEY]: sanitizedFilterGroup ? stringifyRouteFilterGroup(sanitizedFilterGroup) : null,
  };
  for (const key of Object.keys(query)) {
    if (key.startsWith('filters.')) {
      patch[key] = null;
    }
  }
  return patch;
}

export function buildResetFilterQueryPatch(query: LocationQuery) {
  return buildFilterQueryPatch(query, createEmptyFilterGroup());
}

export function mergeRouteQuery(query: LocationQuery, patch: Record<string, string | number | null | undefined>) {
  const nextQuery = { ...query } as Record<string, string>;
  for (const [key, value] of Object.entries(patch)) {
    if (value == null || value === '') {
      delete nextQuery[key];
    } else {
      nextQuery[key] = String(value);
    }
  }
  return nextQuery;
}

function parseRouteFilterGroup(value: string): StatisticFilterGroup | null {
  try {
    const parsed = JSON.parse(value) as Partial<StatisticFilterGroup> | null;
    if (!parsed || !Array.isArray(parsed.conditions)) {
      return null;
    }
    return {
      logic: parsed.logic === 'OR' ? 'OR' : 'AND',
      conditions: parsed.conditions.map((condition) => ({
        fieldKey: String(condition?.fieldKey ?? ''),
        operator: String(condition?.operator ?? '') as StatisticFilterOperator,
        value: condition?.value == null ? '' : String(condition.value),
        secondaryValue: condition?.secondaryValue == null ? '' : String(condition.secondaryValue),
      })),
    };
  } catch {
    return null;
  }
}

function stringifyRouteFilterGroup(
  filterDraft: Pick<StatisticFilterDraftGroup, 'logic' | 'conditions'> | StatisticFilterGroup,
) {
  return JSON.stringify({
    logic: filterDraft.logic === 'OR' ? 'OR' : 'AND',
    conditions: filterDraft.conditions.map((condition) => ({
      fieldKey: condition.fieldKey,
      operator: condition.operator,
      value: normalizeRouteScalar(condition.value),
      secondaryValue: normalizeRouteScalar(condition.secondaryValue),
    })),
  });
}

function toDraftFilterGroup(source: StatisticFilterGroup): StatisticFilterDraftGroup {
  const draftGroup = createEmptyFilterGroup();
  draftGroup.logic = source.logic === 'OR' ? 'OR' : 'AND';
  draftGroup.conditions.push(
    ...source.conditions.map((condition) => ({
      id: nextConditionId(),
      fieldKey: condition.fieldKey ?? '',
      operator: (condition.operator ?? '') as StatisticFilterOperator | '',
      value: condition.value ?? '',
      secondaryValue: condition.secondaryValue ?? '',
    })),
  );
  return draftGroup;
}

function normalizeRouteScalar(value: string | number | null) {
  if (value == null) {
    return '';
  }
  return typeof value === 'number' ? String(value) : value;
}

function firstQueryValue(rawValue: LocationQuery[string]) {
  if (Array.isArray(rawValue)) {
    return rawValue[0] ? String(rawValue[0]) : '';
  }
  return rawValue == null ? '' : String(rawValue);
}
