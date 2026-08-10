import type { EChartsOption } from 'echarts';
import { BiChart, type BiChartRenderContext, type BiChartSize } from '../BiChart';
import type { NamedValue } from '../chart-data';
import { BI_PALETTE } from '../palette';

export class SubmissionFrequencyBarChart extends BiChart<NamedValue[]> {
  readonly templateId = 'submission-frequency-bar' as const;

  hasData(data: NamedValue[]): boolean {
    return data.length > 0;
  }

  exportSize(data: NamedValue[]): BiChartSize {
    return this.verticalExportSize(data.length);
  }

  build(data: NamedValue[], context: BiChartRenderContext): EChartsOption {
    return {
      ...this.baseOption(`提交频次，共 ${data.length} 个日期。`),
      grid: { top: 24, right: 20, bottom: context.mode === 'view' && data.length > 12 ? 72 : 48, left: 52, containLabel: true },
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
      xAxis: { type: 'category', data: data.map((item) => item.name), axisLabel: { rotate: data.length > 8 ? 25 : 0 } },
      yAxis: { type: 'value', name: '次数', minInterval: 1 },
      dataZoom: this.categoryZoom(data.length, context.mode),
      series: [{ name: '提交数', type: 'bar', data: data.map((item) => item.value), barMaxWidth: 34, itemStyle: { color: BI_PALETTE.teal, borderRadius: [3, 3, 0, 0] }, label: { show: true, position: 'top', color: '#334155' } }],
    };
  }
}
