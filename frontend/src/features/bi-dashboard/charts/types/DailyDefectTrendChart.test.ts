import { describe, expect, it } from 'vitest';
import { DailyDefectTrendChart, buildCustomerIssueDailyTrend } from './DailyDefectTrendChart';

describe('DailyDefectTrendChart', () => {
  const chart = new DailyDefectTrendChart();

  it('keeps the complete natural-date series and exports unknown contributions as blank cells', () => {
    const data = {
      dates: ['2028-02-29', '2028-03-01', '2028-03-02'],
      cumulativeCreatedCounts: [1, 3, null],
      cumulativeFixedCounts: [0, 1, null],
    };

    expect(chart.templateId).toBe('daily-defect-trend');
    expect(chart.hasData(data)).toBe(true);
    expect(chart.excelTable(data)).toEqual({
      headers: ['日期', '缺陷累计新增（个）', '缺陷累计修复（个）'],
      rows: [
        ['2028-02-29', 1, 0],
        ['2028-03-01', 3, 1],
        ['2028-03-02', null, null],
      ],
    });
    const option = chart.build(data, { mode: 'export' });
    expect(option.dataZoom).toEqual([]);
    expect(option.xAxis).toMatchObject({ data: data.dates });
    expect(option.yAxis).toMatchObject({ name: '累计缺陷数（个）', minInterval: 1 });
    expect(option.series).toHaveLength(2);
    // 未知贡献保持空洞：不得用 0 或平滑插值冒充累计结果。
    expect(option.series).toEqual(expect.arrayContaining([
      expect.objectContaining({
        name: '缺陷累计新增',
        type: 'line',
        smooth: false,
        connectNulls: false,
        data: data.cumulativeCreatedCounts,
      }),
      expect.objectContaining({
        name: '缺陷累计修复',
        type: 'line',
        smooth: false,
        connectNulls: false,
        data: data.cumulativeFixedCounts,
      }),
    ]));
  });

  it('uses a real empty state for an empty, unknown or all-zero cumulative axis', () => {
    expect(chart.hasData({ dates: [], cumulativeCreatedCounts: [], cumulativeFixedCounts: [] })).toBe(false);
    expect(chart.hasData({ dates: ['2026-01-01'], cumulativeCreatedCounts: [0], cumulativeFixedCounts: [0] })).toBe(false);
    expect(chart.hasData({
      dates: ['2026-01-01', '2026-01-02'],
      cumulativeCreatedCounts: [null, null],
      cumulativeFixedCounts: [null, null],
    })).toBe(false);
  });

  it('keeps an in-page window but exports the complete date axis', () => {
    const dates = Array.from({ length: 240 }, (_, index) => `day-${index}`);
    const data = {
      dates,
      cumulativeCreatedCounts: dates.map((_, index) => (index === 239 ? 1 : 0)),
      cumulativeFixedCounts: dates.map(() => 0),
    };
    const view = chart.build(data, { mode: 'view' });
    const exported = chart.build(data, { mode: 'export' });

    expect(view.dataZoom).toHaveLength(1);
    expect(exported.dataZoom).toEqual([]);
    expect((exported.xAxis as { data: string[] }).data).toHaveLength(240);
    expect(chart.exportSize(data).width).toBeGreaterThan(1440);
  });

  describe('buildCustomerIssueDailyTrend', () => {
    it('slices leading zero days and starts from the first day with actual data', () => {
      const rows = [
        { date: '2026-01-01', cumulativeCreatedCount: 0, cumulativeFixedCount: 0 },
        { date: '2026-01-02', cumulativeCreatedCount: 0, cumulativeFixedCount: 0 },
        { date: '2026-04-02', cumulativeCreatedCount: 1, cumulativeFixedCount: 0 },
        { date: '2026-04-03', cumulativeCreatedCount: 2, cumulativeFixedCount: 1 },
      ];
      const result = buildCustomerIssueDailyTrend(rows);
      expect(result.dates).toEqual(['2026-04-02', '2026-04-03']);
      expect(result.cumulativeCreatedCounts).toEqual([1, 2]);
      expect(result.cumulativeFixedCounts).toEqual([0, 1]);
      expect(chart.hasData(result)).toBe(true);
    });

    it('retains all rows when data starts on the very first date', () => {
      const rows = [
        { date: '2026-01-01', cumulativeCreatedCount: 1, cumulativeFixedCount: 0 },
        { date: '2026-01-02', cumulativeCreatedCount: 1, cumulativeFixedCount: 1 },
      ];
      const result = buildCustomerIssueDailyTrend(rows);
      expect(result.dates).toEqual(['2026-01-01', '2026-01-02']);
      expect(result.cumulativeCreatedCounts).toEqual([1, 1]);
    });

    it('falls back to all rows when no row has data', () => {
      const rows = [
        { date: '2026-01-01', cumulativeCreatedCount: 0, cumulativeFixedCount: 0 },
        { date: '2026-01-02', cumulativeCreatedCount: 0, cumulativeFixedCount: 0 },
      ];
      const result = buildCustomerIssueDailyTrend(rows);
      expect(result.dates).toEqual(['2026-01-01', '2026-01-02']);
      expect(chart.hasData(result)).toBe(false);
    });

    it('handles empty input gracefully', () => {
      expect(buildCustomerIssueDailyTrend([])).toEqual({
        dates: [],
        cumulativeCreatedCounts: [],
        cumulativeFixedCounts: [],
      });
    });
  });
});
