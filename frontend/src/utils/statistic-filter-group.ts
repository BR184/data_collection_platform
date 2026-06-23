import type { StatisticFilterGroup, StatisticFilterOperator } from '../types/api';

type LabelGroupSetOperator = 'intersects' | 'notIntersects' | 'containsAll' | 'notContainsAll' | 'partialContainsAny';
type NormalizableFilterCondition = Omit<StatisticFilterGroup['conditions'][number], 'operator'> & {
  operator: StatisticFilterOperator | '';
};
type NormalizableFilterGroup = Omit<StatisticFilterGroup, 'conditions'> & {
  conditions: NormalizableFilterCondition[];
};

export function defaultLabelGroupOperator(): StatisticFilterOperator {
  return 'intersects';
}

export function isLabelGroupOperator(
  operator: StatisticFilterOperator | '',
): operator is LabelGroupSetOperator {
  return ['intersects', 'notIntersects', 'containsAll', 'notContainsAll', 'partialContainsAny'].includes(operator);
}

export function normalizeLabelGroupOperator(operator: StatisticFilterOperator | ''): StatisticFilterOperator {
  if (operator === 'eq') {
    return 'intersects';
  }
  if (operator === 'ne') {
    return 'notIntersects';
  }
  return isLabelGroupOperator(operator) ? operator : defaultLabelGroupOperator();
}

export function normalizeStatisticFilterGroupOperators(
  filterGroup: NormalizableFilterGroup | null | undefined,
): NormalizableFilterGroup | null {
  if (!filterGroup?.conditions?.length) {
    return filterGroup ?? null;
  }
  return {
    logic: filterGroup.logic === 'OR' ? 'OR' : 'AND',
    conditions: filterGroup.conditions.map((condition) => {
      if (condition.valueType !== 'LABEL_GROUP') {
        return condition;
      }
      return {
        ...condition,
        operator: normalizeLabelGroupOperator(condition.operator ?? ''),
      };
    }),
  };
}

export function stringifyStatisticFilterGroup(filterGroup: StatisticFilterGroup) {
  return JSON.stringify(normalizeStatisticFilterGroupOperators(filterGroup));
}
