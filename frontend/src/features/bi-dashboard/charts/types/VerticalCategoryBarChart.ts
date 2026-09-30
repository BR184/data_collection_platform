import type { EChartsOption } from 'echarts';
import { BiChart, type BiChartRenderContext, type BiChartSize, type BiExcelTableData } from '../BiChart';
import type { NamedValue } from '../chart-data';
import { BI_PALETTE, BI_SERIES_COLORS, readableTextColor } from '../palette';

/** Excel 表格可见字段；复用同一柱图模板的页面按自身数据语义指定列名。 */
export interface VerticalCategoryBarChartExcelHeaders {
  nameHeader: string;
  valueHeader: string;
}

export class VerticalCategoryBarChart extends BiChart<NamedValue[]> {
  readonly templateId = 'vertical-category-bar' as const;

  private readonly excelHeaders: VerticalCategoryBarChartExcelHeaders;

  /**
   * 创建分类柱图并固定 Excel 列名。
   * @param excelHeaders 当前数据语义对应的名称列与数值列标题；缺省保持编码页既有标题。
   */
  constructor(excelHeaders: VerticalCategoryBarChartExcelHeaders) {
    super();
    this.excelHeaders = excelHeaders;
  }

  hasData(data: NamedValue[]): boolean {
    return data.length > 0;
  }

  exportSize(data: NamedValue[]): BiChartSize {
    return this.verticalExportSize(data.length);
  }

  excelTable(data: NamedValue[]): BiExcelTableData {
    return {
      headers: [this.excelHeaders.nameHeader, this.excelHeaders.valueHeader],
      rows: data.map((item) => [item.name, item.value]),
    };
  }

  build(data: NamedValue[], context: BiChartRenderContext): EChartsOption {
    const categoryNames = [...new Set(data.map((item) => item.category).filter((item): item is string => Boolean(item)))];
    const categoryColor = new Map(categoryNames.map((name, index) => [name, BI_SERIES_COLORS[index % BI_SERIES_COLORS.length]! ]));
    const color = BI_PALETTE.blue;
    return {
      ...this.baseOption(`分类柱状图，共 ${data.length} 项。`),
      legend: { show: false },
      grid: { top: 24, right: 20, bottom: context.mode === 'view' && data.length > 12 ? 72 : 64, left: 52, containLabel: true },
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
      xAxis: {
        type: 'category',
        data: data.map((item) => item.name),
        axisLabel: { interval: 0, rotate: data.length > 6 ? 25 : 0, hideOverlap: false },
      },
      yAxis: { type: 'value', name: this.excelHeaders.valueHeader, minInterval: 1 },
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
