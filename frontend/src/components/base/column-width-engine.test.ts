import { describe, expect, it } from 'vitest';
import {
  columnFlexWeight,
  effectiveColumnMinWidth,
  effectiveColumnWidth,
  estimateTextWidthPx,
} from './column-width-engine';

describe('column-width-engine', () => {
  it('keeps person columns independent from row content', () => {
    const column = {
      key: 'owner',
      label: '负责人',
      sortable: true,
      headerTooltip: '负责人',
    } as const;

    expect(effectiveColumnWidth(column, [{ owner: '这是一个很长的姓名' }])).toBe(
      effectiveColumnMinWidth(column),
    );
  });

  it('applies type floors and preserves the larger tags minimum width', () => {
    const numberColumn = { key: 'count', label: '数量', type: 'number' as const };
    const tagsColumn = {
      key: 'labels',
      label: '标签',
      type: 'tags' as const,
      minWidth: 240,
    };

    expect(effectiveColumnMinWidth(numberColumn)).toBeGreaterThanOrEqual(58);
    expect(effectiveColumnMinWidth(tagsColumn)).toBe(240);
    expect(effectiveColumnWidth(tagsColumn, [{ labels: ['alpha', 'beta'] }])).toBeGreaterThanOrEqual(240);
  });

  it('caps long narrative content at the configured narrative upper bound', () => {
    const column = { key: 'description', label: '描述', type: 'text' as const };
    const longText = 'long text '.repeat(100);

    expect(effectiveColumnWidth(column, [{ description: longText }])).toBe(460);
  });

  it('assigns elastic space only to content columns with the intended weights', () => {
    expect(columnFlexWeight({ key: 'owner', label: '负责人' })).toBe(0);
    expect(columnFlexWeight({ key: 'projectName', label: '所属项目' })).toBe(0);
    expect(columnFlexWeight({ key: 'title', label: '标题' })).toBe(3.2);
    expect(columnFlexWeight({ key: 'labels', label: '标签', type: 'tags' })).toBe(2);
  });

  it('estimates empty, CJK, and ASCII text deterministically', () => {
    expect(estimateTextWidthPx('-')).toBe(8);
    expect(estimateTextWidthPx('中文')).toBe(26);
    expect(estimateTextWidthPx('ABC')).toBe(21);
    expect(estimateTextWidthPx('中文')).toBeGreaterThan(estimateTextWidthPx('abc'));
  });
});
