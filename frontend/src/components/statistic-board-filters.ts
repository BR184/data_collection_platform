import type { StatisticFilterField, StatisticFilterGroup, StatisticFilterOperator } from '../types/api';

export interface StatisticFilterConditionDraft {
  id: string;
  fieldKey: string;
  operator: StatisticFilterOperator | '';
  value: string | number | null;
  secondaryValue: string | number | null;
}

export interface StatisticFilterDraftGroup {
  logic: 'AND' | 'OR';
  conditions: StatisticFilterConditionDraft[];
}

let filterConditionSeed = 0;

export function createEmptyFilterGroup(): StatisticFilterDraftGroup {
  return {
    logic: 'AND',
    conditions: [],
  };
}

export function replaceFilterDraftGroup(
  target: StatisticFilterDraftGroup,
  nextGroup: Pick<StatisticFilterDraftGroup, 'logic' | 'conditions'>,
) {
  target.logic = nextGroup.logic === 'OR' ? 'OR' : 'AND';
  target.conditions.splice(0, target.conditions.length, ...nextGroup.conditions);
}

export function resetFilterDraftGroup(target: StatisticFilterDraftGroup) {
  replaceFilterDraftGroup(target, createEmptyFilterGroup());
}

export function createFilterConditionDraft(field?: StatisticFilterField): StatisticFilterConditionDraft {
  filterConditionSeed += 1;
  return {
    id: `condition-${filterConditionSeed}`,
    fieldKey: field?.key ?? '',
    operator: field?.operators?.[0] ?? '',
    value: '',
    secondaryValue: '',
  };
}

export function normalizeFilterDraftGroup(
  source: StatisticFilterGroup | null | undefined,
  fields: StatisticFilterField[],
): StatisticFilterDraftGroup {
  const fieldMap = new Map(fields.map((field) => [field.key, field]));
  if (!source || !Array.isArray(source.conditions) || source.conditions.length === 0) {
    return createEmptyFilterGroup();
  }
  return {
    logic: source.logic === 'OR' ? 'OR' : 'AND',
    conditions: source.conditions
      .filter((condition) => fieldMap.has(condition.fieldKey))
      .map((condition) => ({
        id: createFilterConditionDraft(fieldMap.get(condition.fieldKey)).id,
        fieldKey: condition.fieldKey,
        operator: condition.operator,
        value: condition.value ?? '',
        secondaryValue: condition.secondaryValue ?? '',
      })),
  };
}

export function sanitizeFilterDraftGroup(draft: StatisticFilterDraftGroup): StatisticFilterGroup | null {
  const conditions = draft.conditions
    .map((condition) => ({
      fieldKey: condition.fieldKey,
      operator: condition.operator,
      value: normalizeScalar(condition.value),
      secondaryValue: normalizeScalar(condition.secondaryValue),
    }))
    .filter((condition) => condition.fieldKey && condition.operator && (!requiresPrimaryValue(condition.operator) || condition.value))
    .filter((condition) => (condition.operator === 'between' ? !!condition.secondaryValue : true));

  if (!conditions.length) {
    return null;
  }

  return {
    logic: draft.logic,
    conditions,
  };
}

export function operatorLabel(operator: StatisticFilterOperator | '') {
  return (
    {
      eq: '等于',
      ne: '不等于',
      contains: '包含',
      notContains: '不包含',
      gt: '大于',
      gte: '大于等于',
      lt: '小于',
      lte: '小于等于',
      between: '区间',
      year: '某年',
      month: '某月',
      day: '某日',
      at: '某时刻',
      before: '早于',
      after: '晚于',
      isEmpty: '为空',
      isNotEmpty: '不为空',
    } as Record<string, string>
  )[operator] ?? '条件';
}

export function usesSecondaryValue(operator: StatisticFilterOperator | '') {
  return operator === 'between';
}

function normalizeScalar(value: string | number | null) {
  if (value == null) {
    return '';
  }
  return typeof value === 'number' ? String(value) : value.trim();
}

function requiresPrimaryValue(operator: StatisticFilterOperator | '') {
  return operator !== 'isEmpty' && operator !== 'isNotEmpty';
}
