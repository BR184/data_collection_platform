import type { EChartsOption } from 'echarts';
import { BiChart, type BiChartRenderContext, type BiChartSize } from '../BiChart';
import type { QualityTrendData } from '../chart-data';
import { BI_PALETTE } from '../palette';
import { buildAdaptiveValueAxis } from '../adaptive-value-axis';

export class QualityTrendSmallMultiplesChart extends BiChart<QualityTrendData> {
  readonly templateId = 'quality-trend-small-multiples' as const;

  hasData(data: QualityTrendData): boolean {
    return data.periods.length > 0 && (data.commentRates.some((item) => item != null) || data.defectDensities.some((item) => item != null));
  }

  exportSize(data: QualityTrendData): BiChartSize {
    return this.verticalExportSize(data.periods.length);
  }

  build(data: QualityTrendData, context: BiChartRenderContext): EChartsOption {
    const bottom = context.mode === 'view' && data.periods.length > 12 ? 70 : 42;
    const commentRateAxis = buildAdaptiveValueAxis(data.commentRates, { minimumMax: 100 });
    const densityAxis = buildAdaptiveValueAxis(data.defectDensities, { minimumMax: 0.8 });
    return {
      ...this.baseOption(`代码质量趋势，共 ${data.periods.length} 个周期。`),
      legend: { top: 0 },
      grid: [
        { left: 58, right: 24, top: 48, height: '30%', containLabel: true },
        { left: 58, right: 24, top: '57%', bottom, containLabel: true },
      ],
      tooltip: { trigger: 'axis' },
      xAxis: [
        { type: 'category', data: data.periods, gridIndex: 0, axisLabel: { show: false } },
        { type: 'category', data: data.periods, gridIndex: 1, axisLabel: { rotate: data.periods.length > 8 ? 25 : 0 } },
      ],
      yAxis: [
        { ...commentRateAxis, type: 'value', name: '注释率', gridIndex: 0, axisLabel: { formatter: '{value}%' } },
        { ...densityAxis, type: 'value', name: '个/KLOC', gridIndex: 1 },
      ],
      dataZoom: context.mode === 'view' && data.periods.length > 12
        ? [{ type: 'slider', xAxisIndex: [0, 1], startValue: 0, endValue: 11, height: 16, bottom: 4 }]
        : [],
      series: [
        { name: '代码注释率', type: 'line', xAxisIndex: 0, yAxisIndex: 0, data: data.commentRates, connectNulls: false, smooth: true, lineStyle: { width: 3, color: BI_PALETTE.teal }, itemStyle: { color: BI_PALETTE.teal } },
        { name: '代码走查缺陷密度', type: 'line', xAxisIndex: 1, yAxisIndex: 1, data: data.defectDensities, connectNulls: false, smooth: true, lineStyle: { width: 3, color: BI_PALETTE.orange }, itemStyle: { color: BI_PALETTE.orange } },
      ],
    };
  }
}
