import type { EChartsOption } from 'echarts';
import { BiChart, type BiChartRenderContext, type BiChartSize } from '../BiChart';
import type { NamedValue } from '../chart-data';
import { BI_PALETTE, BI_SERIES_COLORS, readableTextColor } from '../palette';

export class VerticalCategoryBarChart extends BiChart<NamedValue[]> {
  readonly templateId = 'vertical-category-bar' as const;

  hasData(data: NamedValue[]): boolean {
    return data.length > 0;
  }

  exportSize(data: NamedValue[]): BiChartSize {
    return this.verticalExportSize(data.length);
  }

  build(data: NamedValue[], context: BiChartRenderContext): EChartsOption {
    const categoryNames = [...new Set(data.map((item) => item.category).filter((item): item is string => Boolean(item)))];
    const categoryColor = new Map(categoryNames.map((name, index) => [name, BI_SERIES_COLORS[index % BI_SERIES_COLORS.length]! ]));
    const hasLegend = categoryNames.length > 0;
    const color = BI_PALETTE.blue;
    return {
      ...this.baseOption(`分类柱状图，共 ${data.length} 项。`),
      legend: hasLegend ? { data: categoryNames, top: 0 } : { show: false },
      grid: { top: hasLegend ? 48 : 24, right: 20, bottom: context.mode === 'view' && data.length > 12 ? 72 : 64, left: 52, containLabel: true },
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
      xAxis: {
        type: 'category',
        data: data.map((item) => item.name),
        axisLabel: { interval: 0, rotate: data.length > 6 ? 25 : 0, hideOverlap: false },
      },
      yAxis: { type: 'value', minInterval: 1 },
      dataZoom: this.categoryZoom(data.length, context.mode),
      series: [{
        name: '数量',
        type: 'bar',
        barMaxWidth: 46,
        data: data.map((item) => ({
          value: item.value,
          itemStyle: { color: item.category ? categoryColor.get(item.category) : color, borderRadius: [4, 4, 0, 0] },
          label: { color: readableTextColor(item.category ? categoryColor.get(item.category) ?? color : color) },
        })),
        label: { show: true, position: 'top', distance: 6, fontWeight: 600, color: '#344054' },
      }],
    };
  }
}
