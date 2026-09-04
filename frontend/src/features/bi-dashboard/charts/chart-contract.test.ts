import { describe, expect, it } from 'vitest';
import { readableTextColor } from './palette';
import {
  CodingTrendComboChart,
  DefectCauseBreakdownChart,
  DelayHeatmapChart,
  DeveloperWorkloadChart,
  DistributionDonutChart,
  ModuleRepairMatrixChart,
  OverlayCategoryBarChart,
  QualityRoundTrackChart,
  QualityTrendSmallMultiplesChart,
  ReviewQualityDualPanelChart,
  ReviewQualityScatterChart,
  StackedCategoryBarChart,
  SubmissionFrequencyBarChart,
  SubmissionTrendComboChart,
  TestQualityAttainmentChart,
  VerticalCategoryBarChart,
} from './types';
import { prepareDistributionDonutData } from './types/DistributionDonutChart';

describe('BI chart type contract', () => {
  it('provides one concrete class for every registered template id', () => {
    const charts = [
      new DistributionDonutChart(),
      new VerticalCategoryBarChart(),
      new StackedCategoryBarChart(),
      new CodingTrendComboChart(),
      new SubmissionTrendComboChart(),
      new SubmissionFrequencyBarChart(),
      new OverlayCategoryBarChart(),
      new DeveloperWorkloadChart(),
      new QualityRoundTrackChart(),
      new ModuleRepairMatrixChart(),
      new TestQualityAttainmentChart(),
      new ReviewQualityDualPanelChart({ densityRange: [0.2, 0.6], densityUnit: '问题/页', rateUnit: '页/小时' }),
      new ReviewQualityScatterChart({ densityRange: [0.2, 0.6], densityUnit: '问题/页', rateUnit: '页/小时' }),
      new QualityTrendSmallMultiplesChart(),
      new DelayHeatmapChart(),
    ];

    expect(new Set(charts.map((chart) => chart.templateId)).size).toBe(15);
  });

  it('chooses contrasting label colors for dark and light fills', () => {
    expect(readableTextColor('#5470C6')).toBe('#FFFFFF');
    expect(readableTextColor('#F0F2F5')).toBe('#111827');
  });

  it('keeps the module total bar stable underneath the unresolved overlay', () => {
    const option = new OverlayCategoryBarChart().build(
      [{ name: '草图', total: 12, overlay: 3 }],
      { mode: 'view' },
    );
    const series = option.series as Array<{
      barWidth?: string;
      barMaxWidth?: number;
      barGap?: string;
      z?: number;
      label?: { formatter?: string; position?: string };
      labelLayout?: { moveOverlap?: string };
    }>;

    expect(series[0]).toMatchObject({ barWidth: '72%', barMaxWidth: 46, z: 1 });
    expect(series[1]).toMatchObject({ barWidth: '72%', barMaxWidth: 46, barGap: '-100%', z: 2 });
    expect(series[0].label).toMatchObject({ formatter: '{c}', position: 'top' });
    expect(series[1].label).toMatchObject({ formatter: '{c}', position: 'top' });
    expect(series[0].labelLayout).toMatchObject({ moveOverlap: 'shiftY' });
    expect(series[1].labelLayout).toMatchObject({ moveOverlap: 'shiftY' });
  });

  it('uses a ten-module view window while keeping complete export data', () => {
    const data = Array.from({ length: 14 }, (_, index) => ({
      name: `模块 ${index}`,
      total: index + 10,
      overlay: index + 1,
    }));
    const chart = new OverlayCategoryBarChart();

    const viewOption = chart.build(data, { mode: 'view' });
    const exportOption = chart.build(data, { mode: 'export' });
    const viewZoom = viewOption.dataZoom as Array<{ startValue?: number; endValue?: number }>;

    expect(viewZoom).toHaveLength(1);
    expect(viewZoom[0]).toMatchObject({ startValue: 0, endValue: 9 });
    expect(exportOption.dataZoom).toEqual([]);
  });

  it('uses the static-page vertical workload layout with isolated summary labels', () => {
    const option = new DeveloperWorkloadChart().build(
      [{ name: '张三', total: 12, fixed: 8, open: 4 }],
      { mode: 'view' },
    );
    const series = option.series as Array<{
      type?: string;
      name?: string;
      stack?: string;
      label?: { show?: boolean };
      data?: unknown[];
    }>;

    expect(option.xAxis).toMatchObject({ type: 'category', data: ['张三'] });
    expect(option.yAxis).toMatchObject({ type: 'value', min: 0, max: 15 });
    expect(series.slice(0, 2)).toEqual([
      expect.objectContaining({ name: '已修复', type: 'bar', stack: 'workload', data: [8] }),
      expect.objectContaining({ name: '待修复', type: 'bar', stack: 'workload', data: [4] }),
    ]);
    expect(series[2]).toMatchObject({ name: '总数标顶', type: 'custom', data: [[0, 12]], tooltip: { show: false } });
  });

  it('centers review quality axis units so paired panels do not clip them', () => {
    const option = new ReviewQualityDualPanelChart({ densityRange: [0.2, 0.6], densityUnit: '问题/页', rateUnit: '页/小时' }).build(
      [{ name: '草图', density: 0.4, rate: 5.6, achieved: true }],
      { mode: 'view' },
    );
    const axes = option.xAxis as Array<{ name?: string; nameLocation?: string; nameGap?: number }>;
    const grids = option.grid as Array<{ bottom?: number }>;
    const series = option.series as Array<{ data?: Array<number | { value?: number }> }>;

    expect(axes).toEqual([
      expect.objectContaining({ name: '问题/页', nameLocation: 'middle', nameGap: 28 }),
      expect.objectContaining({ name: '页/小时', nameLocation: 'middle', nameGap: 28 }),
    ]);
    expect(grids.every((grid) => grid.bottom === 44)).toBe(true);
    expect(series[0].data?.[0]).toMatchObject({ value: 0.4 });
    expect(series[1].data?.[0]).toMatchObject({ value: [5.6, '草图'] });
  });

  it('uses coding review units and preserves outliers behind adaptive axis breaks', () => {
    const option = new ReviewQualityDualPanelChart({
      densityRange: [2, 10],
      densityUnit: '个/KLOC',
      rateUnit: '行/小时',
    }).build([
      { name: '草图', density: 3.32, rate: 480, achieved: true },
      { name: '装配', density: 7.31, rate: 720, achieved: true },
      { name: '隐藏异常模块', density: 166.7, rate: 37_600, achieved: false },
    ], { mode: 'view' });
    const axes = option.xAxis as Array<{ name?: string; max?: number; breaks?: unknown[] }>;
    const series = option.series as Array<{ data?: Array<{ value?: number | unknown[] }> }>;

    expect(axes[0]).toMatchObject({ name: '个/KLOC' });
    expect(axes[1]).toMatchObject({ name: '行/小时' });
    expect(axes[0].max).toBeGreaterThanOrEqual(166.7);
    expect(axes[1].max).toBeGreaterThanOrEqual(37_600);
    expect(axes[0].breaks?.length).toBeGreaterThan(0);
    expect(axes[1].breaks?.length).toBeGreaterThan(0);
    expect(series[0].data?.at(-1)).toMatchObject({ value: 166.7 });
    expect(series[1].data?.at(-1)).toMatchObject({ value: [37_600, '隐藏异常模块'] });
  });

  it('keeps every review scatter point while compressing sparse extreme ranges', () => {
    const option = new ReviewQualityScatterChart({
      densityRange: [2, 10],
      densityUnit: '个/KLOC',
      rateUnit: 'KLOC/小时',
    }).build([
      { name: '草图', date: '2026-08-01', rate: 0.8, density: 3, achieved: true },
      { name: '装配', date: '2026-08-02', rate: 1.2, density: 7, achieved: true },
      { name: '异常记录', date: '2026-08-03', rate: 180, density: 1_000, achieved: false },
    ], { mode: 'view' });
    const xAxis = option.xAxis as { max?: number; breaks?: unknown[] };
    const yAxis = option.yAxis as { max?: number; breaks?: unknown[]; name?: string };
    const series = option.series as Array<{ data?: unknown[] }>;

    expect(xAxis.max).toBeGreaterThanOrEqual(180);
    expect(yAxis).toMatchObject({ name: '个/KLOC' });
    expect(yAxis.max).toBeGreaterThanOrEqual(1_000);
    expect(xAxis.breaks?.length).toBeGreaterThan(0);
    expect(yAxis.breaks?.length).toBeGreaterThan(0);
    expect(series.flatMap((item) => item.data ?? [])).toHaveLength(3);
  });

  it('keeps code quality trend readable when one period has an extreme density', () => {
    const option = new QualityTrendSmallMultiplesChart().build({
      periods: ['2026-08-01', '2026-08-02', '2026-08-03', '2026-08-04'],
      commentRates: [20, 22, 24, 21],
      defectDensities: [3.2, 4.1, 5.3, 166.7],
    }, { mode: 'view' });
    const axes = option.yAxis as Array<{ max?: number; splitNumber?: number; breaks?: unknown[] }>;

    expect(axes[0]).toMatchObject({ max: 100, splitNumber: 4 });
    expect(axes[1].max).toBeGreaterThanOrEqual(166.7);
    expect(axes[1].splitNumber).toBe(4);
    expect(axes[1].breaks?.length).toBeGreaterThan(0);
  });

  it('uses a responsive labeled donut without a bottom legend', () => {
    const option = new DistributionDonutChart().build(
      [{ name: '设计规范', value: 12 }, { name: '逻辑规范', value: 8 }],
      { mode: 'view' },
    );
    const legend = option.legend as { show?: boolean };
    const series = option.series as Array<{
      center?: string[];
      radius?: number[];
      label?: { width?: number; overflow?: string };
    }>;
    const graphic = option.graphic as Array<{
      x?: number;
      y?: number;
      style?: {
        align?: string;
        verticalAlign?: string;
        textAlign?: string;
        textVerticalAlign?: string;
      };
    }>;

    expect(legend).toMatchObject({ show: false });
    expect(series[0]?.center).toEqual([260, 178]);
    expect(series[0]?.radius).toEqual([78.4, 112]);
    expect(series[0]).toMatchObject({ label: { width: 104, overflow: 'break' } });
    expect(graphic[0]?.x).toBe(260);
    expect(graphic[0]?.y).toBe(178);
    expect(graphic[0]?.style).toMatchObject({
      textAlign: 'center',
      textVerticalAlign: 'middle',
    });
  });

  it('keeps the system summary donut centered in its 272px drawing area', () => {
    const option = new DistributionDonutChart().build(
      [{ name: '一级缺陷', value: 32 }, { name: '二级缺陷', value: 135 }, { name: '三级缺陷', value: 269 }],
      { mode: 'view', width: 500, height: 272 },
    );
    const series = option.series as Array<{ center?: number[]; radius?: number[] }>;
    const graphic = option.graphic as Array<{ x?: number; y?: number }>;

    expect(series[0]).toMatchObject({ center: [250, 136], radius: [78.4, 112] });
    expect(graphic[0]).toMatchObject({ x: 250, y: 136 });
  });

  it('merges categories below one percent into the other-problem note', () => {
    const prepared = prepareDistributionDonutData([
      { name: '主类', value: 990 },
      { name: '边界类', value: 5 },
      { name: '零值类', value: 0 },
    ]);

    expect(prepared.displayData.map((item) => item.name)).toEqual(['主类', '其他问题']);
    expect(prepared.displayData[1]?.value).toBe(5);
    expect(prepared.noteText).toContain('其他问题包含：边界类 0.5%');
  });

  it('keeps the minor-category note inside the donut drawing area', () => {
    const option = new DistributionDonutChart().build(
      [{ name: '主类', value: 990 }, { name: '边界类', value: 5 }],
      { mode: 'view', width: 520, height: 356 },
    );
    const graphic = option.graphic as Array<{
      id?: string;
      y?: number;
      style?: { text?: string; textAlign?: string; textVerticalAlign?: string };
    }>;

    expect(graphic).toHaveLength(2);
    expect(graphic[1]).toMatchObject({
      id: 'donut-minor-detail',
      y: 328,
      style: {
        text: '其他问题包含：边界类 0.5%',
        textAlign: 'left',
        textVerticalAlign: 'top',
      },
    });
  });

  it('keeps test totals visible and separates the target marker from their text', () => {
    const option = new TestQualityAttainmentChart().build(
      [{
        id: 'module-permission',
        name: '权限管理',
        counts: { label: '达标功能 / 统计功能', attained: 18, total: 20 },
        passRate: 96.95,
        targetRate: 95,
        achieved: true,
      }],
      { mode: 'view' },
    );
    const grid = option.grid as { right?: number };
    const series = option.series as Array<{
      data?: Array<{ label?: { formatter?: string; position?: string; color?: string } }>;
      symbolOffset?: number[];
    }>;

    expect(grid.right).toBe(24);
    expect(series[0].data?.[0]?.label).toMatchObject({
      formatter: '96.95%  18 / 20',
      position: 'insideRight',
      color: '#111827',
    });
    expect(series[1].symbolOffset).toEqual([0, -12]);
  });

  it('omits unavailable counts from test quality labels and tooltips', () => {
    const option = new TestQualityAttainmentChart().build(
      [{ id: 'feature-extrude', name: '拉伸凸台/基体', counts: null, passRate: 94, targetRate: 95, achieved: false }],
      { mode: 'view' },
    );
    const series = option.series as Array<{
      data?: Array<{ label?: { formatter?: string } }>;
    }>;
    const tooltip = option.tooltip as { formatter?: (params: unknown) => string };

    expect(series[0].data?.[0]?.label?.formatter).toBe('94.00%');
    expect(tooltip.formatter?.([{ dataIndex: 0 }])).toBe(
      '拉伸凸台/基体<br/>通过率：94.00%<br/>目标值：95.00%',
    );
  });

  it('centers both round quality axis names within their own tracks', () => {
    const option = new QualityRoundTrackChart().build(
      [{ name: '第一轮', levelOne: 1, levelTwo: 2, levelThree: 3, submitted: 6, closed: 5, open: 1, closeRate: 83.33 }],
      { mode: 'view' },
    );
    const axes = option.xAxis as Array<{ name?: string; nameLocation?: string; nameGap?: number }>;
    const grids = option.grid as Array<{ bottom?: number }>;

    expect(axes).toEqual([
      expect.objectContaining({ name: '缺陷数', nameLocation: 'middle', nameGap: 28 }),
      expect.objectContaining({ name: '关闭状态', nameLocation: 'middle', nameGap: 28 }),
    ]);
    expect(grids.every((grid) => grid.bottom === 44)).toBe(true);
  });

  it('keeps dense category labels within the confirmed 25 degree limit', () => {
    const data = Array.from({ length: 14 }, (_, index) => ({ name: `模块 ${index}`, value: index }));
    const option = new VerticalCategoryBarChart().build(data, { mode: 'view' });
    const xAxis = option.xAxis as { axisLabel?: { rotate?: number } };

    expect(xAxis.axisLabel?.rotate).toBe(25);
  });

  it('uses grouped percentage bars while keeping unclassified defects outside the scale', () => {
    const chart = new DefectCauseBreakdownChart();
    const option = chart.build({
      items: [
        { id: 'demand', name: '需求遗漏', groupId: 'requirement', groupName: '需求问题', count: 5, sharePercent: 1.54, color: '#5470C6' },
        { id: 'design', name: '设计方案不合理', groupId: 'design', groupName: '设计问题', count: 14, sharePercent: 24.21, color: '#F2A66F' },
      ],
      unclassifiedCount: 3_175,
      unclassifiedSharePercent: 99.28,
    }, { mode: 'view', width: 1100, height: 432 });
    const axes = option.xAxis as Array<{ axisLabel?: { formatter?: (value: string, index: number) => string } }>;
    const grid = option.grid as { left?: number; containLabel?: boolean };
    const yAxis = option.yAxis as { max?: number; axisLabel?: { formatter?: (value: number) => string } };
    const series = option.series as Array<{ data?: Array<{ value?: number; count?: number }> }>;
    const graphic = option.graphic as Array<{ style?: { text?: string } }>;

    expect(chart.templateId).toBe('vertical-category-bar');
    expect(grid).toMatchObject({ left: 58, containLabel: false });
    expect(yAxis.max).toBe(30);
    expect(yAxis.axisLabel?.formatter?.(10)).toBe('10%');
    expect(series[0]?.data).toEqual([
      expect.objectContaining({ value: 1.54, count: 5 }),
      expect.objectContaining({ value: 24.21, count: 14 }),
    ]);
    expect(axes[1]?.axisLabel?.formatter?.('', 0)).toBe('需求问题');
    expect(graphic[0]?.style?.text).toBe('未归类占比 99.28%  ·  3,175 条');
  });

  it('uses the orange heat scale and gives zero cells a neutral fill', () => {
    const option = new DelayHeatmapChart().build({
      reasons: ['数据异常'],
      severities: ['一级缺陷', '二级缺陷', '三级缺陷'],
      values: [[0, 0, 12], [1, 0, 39], [2, 0, 0]],
    }, { mode: 'view' });
    const visualMap = option.visualMap as { inRange?: { color?: string[] } };
    const series = option.series as Array<{ data?: Array<{ itemStyle?: { color?: string } }> }>;

    expect(visualMap.inRange?.color).toEqual(['#FAC858', '#EE6666']);
    expect(series[0]?.data?.[0]?.itemStyle).toBeUndefined();
    expect(series[0]?.data?.[2]?.itemStyle?.color).toBe('#F3F4F6');
  });
});
