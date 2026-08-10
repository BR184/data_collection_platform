import type { EChartsOption } from 'echarts';
import { BiChart, type BiChartRenderContext, type BiChartSize } from '../BiChart';
import type { CodingTrendData } from '../chart-data';
import { BI_PALETTE } from '../palette';

export class CodingTrendComboChart extends BiChart<CodingTrendData> {
  readonly templateId = 'coding-trend-combo' as const;

  hasData(data: CodingTrendData): boolean {
    return data.periods.length > 0;
  }

  exportSize(data: CodingTrendData): BiChartSize {
    return this.verticalExportSize(data.periods.length);
  }

  build(data: CodingTrendData, context: BiChartRenderContext): EChartsOption {
    return {
      ...this.baseOption(`代码增加趋势，共 ${data.periods.length} 个周期。`),
      legend: { top: 0 },
      grid: { top: 48, right: 24, bottom: context.mode === 'view' && data.periods.length > 12 ? 72 : 44, left: 56, containLabel: true },
      tooltip: { trigger: 'axis' },
      xAxis: { type: 'category', data: data.periods, axisLabel: { rotate: data.periods.length > 8 ? 25 : 0 } },
      yAxis: { type: 'value', name: '代码行' },
      dataZoom: this.categoryZoom(data.periods.length, context.mode),
      series: [
        { name: '新增代码', type: 'bar', data: data.addedLines, barMaxWidth: 36, itemStyle: { color: BI_PALETTE.blue, borderRadius: [3, 3, 0, 0] } },
        { name: '累计新增', type: 'line', data: data.cumulativeLines, smooth: true, symbolSize: 7, lineStyle: { width: 3, color: BI_PALETTE.teal }, itemStyle: { color: BI_PALETTE.teal } },
      ],
    };
  }
}
