import type { EChartsOption } from 'echarts';
import { BiChart, type BiChartRenderContext, type BiChartSize } from '../BiChart';
import type { SubmissionTrendData } from '../chart-data';
import { BI_PALETTE } from '../palette';

export class SubmissionTrendComboChart extends BiChart<SubmissionTrendData> {
  readonly templateId = 'submission-trend-combo' as const;

  hasData(data: SubmissionTrendData): boolean {
    return data.periods.length > 0;
  }

  exportSize(data: SubmissionTrendData): BiChartSize {
    return this.verticalExportSize(data.periods.length);
  }

  build(data: SubmissionTrendData, context: BiChartRenderContext): EChartsOption {
    return {
      ...this.baseOption(`提交趋势，共 ${data.periods.length} 个周期。`),
      legend: { top: 0 },
      grid: { top: 48, right: 24, bottom: context.mode === 'view' && data.periods.length > 12 ? 72 : 44, left: 52, containLabel: true },
      tooltip: { trigger: 'axis' },
      xAxis: { type: 'category', data: data.periods, axisLabel: { rotate: data.periods.length > 8 ? 25 : 0 } },
      yAxis: { type: 'value', name: '次数', minInterval: 1 },
      dataZoom: this.categoryZoom(data.periods.length, context.mode),
      series: [
        { name: '提交数', type: 'bar', data: data.commits, barMaxWidth: 36, itemStyle: { color: BI_PALETTE.blue, borderRadius: [3, 3, 0, 0] } },
        { name: '合并请求数', type: 'line', data: data.mergeRequests, smooth: true, symbolSize: 7, lineStyle: { width: 3, color: BI_PALETTE.orange }, itemStyle: { color: BI_PALETTE.orange } },
      ],
    };
  }
}
