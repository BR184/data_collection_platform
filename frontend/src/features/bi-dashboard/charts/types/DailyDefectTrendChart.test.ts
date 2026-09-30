import { describe, expect, it } from 'vitest';
import { DailyDefectTrendChart } from './DailyDefectTrendChart';

describe('DailyDefectTrendChart', () => {
  const chart = new DailyDefectTrendChart();

  it('keeps the complete natural-date series and exports unknown counts as blank cells', () => {
    const data = {
      dates: ['2028-02-29', '2028-03-01', '2028-03-02'],
      createdCounts: [1, 0, null],
      fixedCounts: [0, 1, null],
    };

    expect(chart.templateId).toBe('daily-defect-trend');
    expect(chart.hasData(data)).toBe(true);
    expect(chart.excelTable(data)).toEqual({
      headers: ['日期', '缺陷日增（个）', '缺陷日修复（个）'],
      rows: [
        ['2028-02-29', 1, 0],
        ['2028-03-01', 0, 1],
        ['2028-03-02', null, null],
      ],
    });
    const option = chart.build(data, { mode: 'export' });
    expect(option.dataZoom).toEqual([]);
    expect(option.xAxis).toMatchObject({ data: data.dates });
    expect(option.yAxis).toMatchObject({ name: '缺陷数（个）', minInterval: 1 });
    expect(option.series).toHaveLength(2);
    expect(option.series).toEqual(expect.arrayContaining([
      expect.objectContaining({ name: '缺陷日增', type: 'line', smooth: false, connectNulls: false, data: data.createdCounts }),
      expect.objectContaining({ name: '缺陷日修复', type: 'line', smooth: false, connectNulls: false, data: data.fixedCounts }),
    ]));
  });

  it('uses a real empty state for an empty or all-zero daily axis', () => {
    expect(chart.hasData({ dates: [], createdCounts: [], fixedCounts: [] })).toBe(false);
    expect(chart.hasData({ dates: ['2026-01-01'], createdCounts: [0], fixedCounts: [0] })).toBe(false);
  });

  it('keeps an in-page window but exports the complete date axis', () => {
    const dates = Array.from({ length: 240 }, (_, index) => `day-${index}`);
    const data = {
      dates,
      createdCounts: dates.map((_, index) => index === 239 ? 1 : 0),
      fixedCounts: dates.map(() => 0),
    };
    const view = chart.build(data, { mode: 'view' });
    const exported = chart.build(data, { mode: 'export' });

    expect(view.dataZoom).toHaveLength(1);
    expect(exported.dataZoom).toEqual([]);
    expect((exported.xAxis as { data: string[] }).data).toHaveLength(240);
    expect(chart.exportSize(data).width).toBeGreaterThan(1440);
  });
});
