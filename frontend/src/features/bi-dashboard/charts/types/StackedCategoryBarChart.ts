import type { EChartsOption } from 'echarts';
import { BiChart, type BiChartRenderContext, type BiChartSize } from '../BiChart';
import type { CategorySeriesData } from '../chart-data';
import { BI_SERIES_COLORS } from '../palette';

export class StackedCategoryBarChart extends BiChart<CategorySeriesData> {
  readonly templateId = 'stacked-category-bar' as const;

  hasData(data: CategorySeriesData): boolean {
    return data.categories.length > 0 && data.series.some((series) => series.values.some((value) => value > 0));
  }

  exportSize(data: CategorySeriesData): BiChartSize {
    return this.verticalExportSize(data.categories.length);
  }

  build(data: CategorySeriesData, context: BiChartRenderContext): EChartsOption {
    return {
      ...this.baseOption(`堆叠分类柱状图，共 ${data.categories.length} 个类别。`),
      legend: { top: 0, left: 'center' },
      grid: { top: 48, right: 20, bottom: context.mode === 'view' && data.categories.length > 12 ? 72 : 64, left: 52, containLabel: true },
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
      xAxis: {
        type: 'category',
        data: data.categories,
        axisLabel: { interval: 0, rotate: data.categories.length > 6 ? 25 : 0, hideOverlap: false },
      },
      yAxis: { type: 'value', minInterval: 1 },
      dataZoom: this.categoryZoom(data.categories.length, context.mode),
      series: data.series.map((item, index) => {
        const color = item.color ?? BI_SERIES_COLORS[index % BI_SERIES_COLORS.length]!;
        return {
          name: item.name,
          type: 'bar' as const,
          stack: 'total',
          barMaxWidth: 54,
          itemStyle: { color },
          label: index === data.series.length - 1
            ? { show: true, position: 'top' as const, color: '#344054', formatter: (params: unknown) => {
              const itemIndex = Number((params as { dataIndex?: number }).dataIndex ?? 0);
              return String(data.series.reduce((sum, series) => sum + (series.values[itemIndex] ?? 0), 0));
            } }
            : { show: false },
          data: item.values,
        };
      }),
    };
  }
}
