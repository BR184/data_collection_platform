import type {
  StatisticFilterCondition,
  StatisticFilterField,
  StatisticFilterGroup,
  StatisticFilterOperator,
} from '../types/api';
import {
  defaultLabelGroupOperator,
  isLabelGroupOperator,
  normalizeLabelGroupOperator,
} from '../utils/statistic-filter-group';

export {
  defaultLabelGroupOperator,
  isLabelGroupOperator,
  normalizeLabelGroupOperator,
};

export interface StatisticFilterConditionDraft {
  id: string;
  fieldKey: string;
  operator: StatisticFilterOperator | '';
  value: string | number | null;
  secondaryValue: string | number | null;
  valueType?: 'LITERAL' | 'LABEL_GROUP';
  labelGroupId?: number | null;
  labelGroupName?: string | null;
  source?: 'CONDITION' | 'QUICK';
}

export interface StatisticFilterDraftGroup {
  logic: 'AND' | 'OR';
  conditions: StatisticFilterConditionDraft[];
}

type NormalizableFilterCondition = StatisticFilterCondition | StatisticFilterConditionDraft;

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
    valueType: 'LITERAL',
    labelGroupId: null,
    labelGroupName: null,
    source: 'CONDITION',
  };
}

export function normalizeFilterDraftGroup(
  source: Pick<StatisticFilterDraftGroup, 'logic' | 'conditions'> | StatisticFilterGroup | null | undefined,
  fields: StatisticFilterField[],
): StatisticFilterDraftGroup {
  const fieldMap = new Map(fields.map((field) => [field.key, field]));
  if (!source || !Array.isArray(source.conditions) || source.conditions.length === 0) {
    return createEmptyFilterGroup();
  }
  const sourceConditions = source.conditions as NormalizableFilterCondition[];
  const conditions: StatisticFilterConditionDraft[] = sourceConditions
    .filter((condition) => fieldMap.has(condition.fieldKey))
    .map((condition): StatisticFilterConditionDraft => ({
      id: createFilterConditionDraft(fieldMap.get(condition.fieldKey)).id,
      fieldKey: condition.fieldKey,
      operator: normalizeConditionOperator(condition.operator ?? '', condition.valueType),
      value: condition.valueType === 'LABEL_GROUP' && condition.labelGroupId
        ? labelGroupSelectValue(condition.labelGroupId)
        : condition.value ?? '',
      secondaryValue: condition.secondaryValue ?? '',
      valueType: condition.valueType === 'LABEL_GROUP' ? 'LABEL_GROUP' : 'LITERAL',
      labelGroupId: condition.labelGroupId ?? null,
      labelGroupName: condition.labelGroupName ?? null,
      source: 'CONDITION',
    }));
  return {
    logic: source.logic === 'OR' ? 'OR' : 'AND',
    conditions,
  };
}

function normalizeConditionOperator(operator: StatisticFilterOperator | '', valueType?: string | null): StatisticFilterOperator | '' {
  if (valueType !== 'LABEL_GROUP') {
    return operator;
  }
  return normalizeLabelGroupOperator(operator);
}

export function sanitizeFilterDraftGroup(draft: StatisticFilterDraftGroup): StatisticFilterGroup | null {
  const conditions: StatisticFilterCondition[] = [];

  for (const condition of draft.conditions) {
    if (!condition.fieldKey || !condition.operator) {
      continue;
    }

    if (condition.valueType === 'LABEL_GROUP') {
      if (!condition.labelGroupId || !isLabelGroupOperator(condition.operator)) {
        continue;
      }
      conditions.push({
        fieldKey: condition.fieldKey,
        operator: condition.operator,
        value: null,
        secondaryValue: null,
        valueType: 'LABEL_GROUP',
        labelGroupId: condition.labelGroupId,
        labelGroupName: condition.labelGroupName ?? '',
      });
      continue;
    }

    const value = normalizeScalar(condition.value);
    const secondaryValue = normalizeScalar(condition.secondaryValue);
    if (requiresPrimaryValue(condition.operator) && !value) {
      continue;
    }
    if (condition.operator === 'between' && !secondaryValue) {
      continue;
    }

    conditions.push({
      fieldKey: condition.fieldKey,
      operator: condition.operator,
      value,
      secondaryValue,
    });
  }

  if (!conditions.length) {
    return null;
  }

  return {
    logic: draft.logic,
    conditions,
  };
}

export function labelGroupSelectValue(groupId: number) {
  return `__label_group__:${groupId}`;
}

export function parseLabelGroupSelectValue(value: string) {
  const match = /^__label_group__:(\d+)$/.exec(value);
  return match ? Number(match[1]) : null;
}

export function operatorLabel(operator: StatisticFilterOperator | '') {
  return (
    {
      eq: '等于',
      ne: '不等于',
      contains: '包含',
      notContains: '不包含',
      intersects: '包含任意一个',
      notIntersects: '不包含任意一个',
      containsAll: '包含全部',
      notContainsAll: '不包含全部',
      partialContainsAny: '局部包含任意',
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
