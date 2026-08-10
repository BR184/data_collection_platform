import type { EChartsOption } from 'echarts';
import { BiChart, type BiChartRenderContext, type BiChartSize } from '../BiChart';
import type { TestAttainmentRow } from '../chart-data';
import { BI_PALETTE, readableTextColor } from '../palette';
import { callbackDataIndex } from '../formatters';

export class TestQualityAttainmentChart extends BiChart<TestAttainmentRow[]> {
  readonly templateId = 'test-quality-attainment' as const;

  hasData(data: TestAttainmentRow[]): boolean {
    return data.length > 0;
  }

  exportSize(data: TestAttainmentRow[]): BiChartSize {
    return this.horizontalExportSize(data.length);
  }

  build(data: TestAttainmentRow[], context: BiChartRenderContext): EChartsOption {
    const labels = data.map((item) => attainmentLabel(item));
    const hasViewZoom = context.mode === 'view' && data.length > 12;
    return {
      ...this.baseOption(`测试达标矩阵，共 ${data.length} 项。`),
      legend: { top: 0, data: ['通过率', '目标值'] },
      grid: { top: 48, right: hasViewZoom ? 48 : 24, bottom: hasViewZoom ? 48 : 24, left: 20, containLabel: true },
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
        formatter: (params: unknown) => {
          const row = data[callbackDataIndex(params)];
          if (!row) return '';
          const countLine = row.counts
            ? `<br/>${row.counts.label}：${row.counts.attained} / ${row.counts.total}`
            : '';
          return `${row.name}<br/>通过率：${formatRate(row.passRate)}<br/>目标值：${formatRate(row.targetRate)}${countLine}`;
        },
      },
      xAxis: { type: 'value', min: 0, max: 100, axisLabel: { formatter: '{value}%' } },
      yAxis: { type: 'category', inverse: true, data: data.map((item) => item.name), axisLabel: { width: 160, overflow: 'truncate' } },
      dataZoom: hasViewZoom
        ? [{ type: 'slider', yAxisIndex: 0, startValue: 0, endValue: 11, width: 14, right: 4 }]
        : [],
      series: [
        {
          name: '通过率',
          type: 'bar',
          barWidth: 24,
          showBackground: true,
          backgroundStyle: { color: BI_PALETTE.track, borderRadius: 3 },
          data: data.map((item, index) => {
            const color = statusColor(item.achieved);
            const placeInside = item.passRate != null && item.passRate >= 35;
            return {
              value: item.passRate,
              itemStyle: { color, borderRadius: [0, 3, 3, 0] },
              label: {
                show: true,
                position: placeInside ? 'insideRight' : 'right',
                color: placeInside ? readableTextColor(color) : BI_PALETTE.text,
                formatter: labels[index],
              },
            };
          }),
        },
        {
          name: '目标值',
          type: 'scatter',
          symbol: 'diamond',
          symbolSize: 8,
          symbolOffset: [0, -12],
          itemStyle: { color: BI_PALETTE.text },
          data: data.map((item, index) => [item.targetRate, index]),
        },
      ],
    };
  }
}

function statusColor(achieved: boolean | null): string {
  return achieved == null ? BI_PALETTE.slate : achieved ? BI_PALETTE.green : BI_PALETTE.red;
}

function formatRate(value: number | null): string {
  return value == null ? '不可计算' : `${value.toFixed(2)}%`;
}

function attainmentLabel(item: TestAttainmentRow): string {
  return item.counts
    ? `${formatRate(item.passRate)}  ${item.counts.attained} / ${item.counts.total}`
    : formatRate(item.passRate);
}
