import { describe, expect, it } from 'vitest';
import type { StatisticFilterField } from '../types/api';
import {
  createFilterConditionDraft,
  createEmptyFilterGroup,
  replaceFilterDraftGroup,
  resetFilterDraftGroup,
  normalizeFilterDraftGroup,
  sanitizeFilterDraftGroup,
  usesSecondaryValue,
} from './statistic-board-filters';

const FIELDS: StatisticFilterField[] = [
  {
    key: 'tableName',
    label: '统计对象',
    type: 'select',
    placeholder: '',
    defaultValue: '',
    width: 220,
    operators: ['eq', 'ne'],
    options: [{ label: 'issues', value: 'issues' }],
  },
  {
    key: 'totalRecords',
    label: '总记录数',
    type: 'number',
    placeholder: '',
    defaultValue: '',
    width: 160,
    operators: ['eq', 'gt', 'between'],
    options: [],
  },
];

describe('statistic-board-filters', () => {
  it('creates default operator from field metadata', () => {
    const draft = createFilterConditionDraft(FIELDS[0]);
    expect(draft.fieldKey).toBe('tableName');
    expect(draft.operator).toBe('eq');
  });

  it('normalizes and sanitizes condition groups', () => {
    const normalized = normalizeFilterDraftGroup(
      {
        logic: 'OR',
        conditions: [
          { fieldKey: 'tableName', operator: 'eq', value: 'issues' },
          { fieldKey: 'totalRecords', operator: 'between', value: '1', secondaryValue: '9' },
        ],
      },
      FIELDS,
    );

    expect(normalized.logic).toBe('OR');
    expect(normalized.conditions).toHaveLength(2);

    const payload = sanitizeFilterDraftGroup(normalized);
    expect(payload).toEqual({
      logic: 'OR',
      conditions: [
        { fieldKey: 'tableName', operator: 'eq', value: 'issues', secondaryValue: '' },
        { fieldKey: 'totalRecords', operator: 'between', value: '1', secondaryValue: '9' },
      ],
    });
  });

  it('drops invalid empty conditions', () => {
    const group = createEmptyFilterGroup();
    group.conditions.push({
      id: 'condition-x',
      fieldKey: 'tableName',
      operator: 'eq',
      value: '',
      secondaryValue: '',
    });

    expect(sanitizeFilterDraftGroup(group)).toBeNull();
    expect(usesSecondaryValue('between')).toBe(true);
    expect(usesSecondaryValue('eq')).toBe(false);
  });

  it('keeps empty checks without a primary value', () => {
    const group = createEmptyFilterGroup();
    group.conditions.push({
      id: 'condition-empty',
      fieldKey: 'tableName',
      operator: 'isEmpty',
      value: '',
      secondaryValue: '',
    });

    expect(sanitizeFilterDraftGroup(group)).toEqual({
      logic: 'AND',
      conditions: [{ fieldKey: 'tableName', operator: 'isEmpty', value: '', secondaryValue: '' }],
    });
  });

  it('replaces and resets draft group in place', () => {
    const group = createEmptyFilterGroup();
    group.conditions.push(createFilterConditionDraft(FIELDS[0]));

    replaceFilterDraftGroup(group, {
      logic: 'OR',
      conditions: [
        {
          id: 'condition-y',
          fieldKey: 'totalRecords',
          operator: 'gt',
          value: '3',
          secondaryValue: '',
        },
      ],
    });

    expect(group.logic).toBe('OR');
    expect(group.conditions).toHaveLength(1);
    expect(group.conditions[0].fieldKey).toBe('totalRecords');

    resetFilterDraftGroup(group);
    expect(group.logic).toBe('AND');
    expect(group.conditions).toHaveLength(0);
  });
});
