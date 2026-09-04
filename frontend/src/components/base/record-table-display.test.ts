import { describe, expect, it } from 'vitest';
import {
  buildQuickFilterSummaryChips,
  formatQuickFilterSummaryValue,
  readableSortDirection,
  readableSortFieldLabel,
} from './record-table-display';
import type { RecordTableFilterField } from '../../types/record-table';

describe('record-table-display', () => {
  const statusFilter: RecordTableFilterField = {
    key: 'status',
    label: '状态',
    type: 'select',
    options: [
      { value: 'open', label: '进行中' },
      { value: 'closed', label: '已关闭' },
    ],
  };

  it('formats filter values by their control type', () => {
    expect(formatQuickFilterSummaryValue(
      { key: 'date', label: '日期', type: 'daterange' },
      ['2026-08-01', '2026-08-24'],
    )).toBe('2026-08-01 ~ 2026-08-24');
    expect(formatQuickFilterSummaryValue(
      { key: 'count', label: '数量', type: 'numberrange' },
      [3, null],
    )).toBe('>= 3');
    expect(formatQuickFilterSummaryValue(statusFilter, ['open', 'closed'])).toBe('进行中、已关闭');
    expect(formatQuickFilterSummaryValue(statusFilter, null)).toBe('');
  });

  it('builds keyword and primary-filter summary chips without mutating inputs', () => {
    const filters: RecordTableFilterField[] = [
      statusFilter,
      { key: 'date', label: '日期', type: 'daterange' },
      { key: 'empty', label: '空值', type: 'input' },
    ];
    const filterValues = {
      status: ['open'],
      date: ['2026-08-01', '2026-08-24'],
      empty: '',
    };

    expect(buildQuickFilterSummaryChips({
      hasStandaloneSearch: true,
      searchPlaceholder: '搜索',
      keyword: '客户问题',
      primaryFilters: filters,
      filterValues,
    })).toEqual([
      { id: 'quick:keyword', label: '搜索 客户问题' },
      { id: 'quick:status', label: '状态 进行中' },
      { id: 'quick:date', label: '日期 2026-08-01 ~ 2026-08-24' },
    ]);
    expect(filterValues).toEqual({
      status: ['open'],
      date: ['2026-08-01', '2026-08-24'],
      empty: '',
    });
  });

  it('maps known and unknown sort labels and directions', () => {
    expect(readableSortFieldLabel('mergeRequestIid')).toBe('合并请求编号');
    expect(readableSortFieldLabel('unknown')).toBe('当前字段');
    expect(readableSortDirection('ascending')).toBe('升序');
    expect(readableSortDirection('desc')).toBe('降序');
    expect(readableSortDirection('')).toBe('默认顺序');
  });
});
