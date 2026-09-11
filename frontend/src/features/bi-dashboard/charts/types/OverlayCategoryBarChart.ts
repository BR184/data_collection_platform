import type { EChartsOption } from 'echarts';
import { BiChart, type BiChartRenderContext, type BiChartSize, type BiExcelTableData } from '../BiChart';
import type { OverlayBarRow } from '../chart-data';
import { BI_PALETTE } from '../palette';

export class OverlayCategoryBarChart extends BiChart<OverlayBarRow[]> {
  readonly templateId = 'overlay-category-bar' as const;

  private static readonly VIEW_WINDOW_SIZE = 10;
  private static readonly BAR_WIDTH = '72%';
  private static readonly BAR_MAX_WIDTH = 46;

  hasData(data: OverlayBarRow[]): boolean {
    return data.length > 0;
  }

  exportSize(data: OverlayBarRow[]): BiChartSize {
    return this.verticalExportSize(data.length);
  }

  excelTable(data: OverlayBarRow[]): BiExcelTableData {
    return {
      headers: ['模块名称', '累计发现缺陷数', '当前未修复缺陷数', '已修复缺陷数', '修复率 (%)'],
      rows: data.map((item) => {
        const fixed = item.total - item.overlay;
        const fixRate = item.total > 0 ? Number(((fixed / item.total) * 100).toFixed(1)) : 100;
        return [item.name, item.total, item.overlay, fixed, fixRate];
      }),
    };
  }

  build(data: OverlayBarRow[], context: BiChartRenderContext): EChartsOption {
    const totalColor = BI_PALETTE.slate;
    const openColor = BI_PALETTE.red;
    return {
      ...this.baseOption(`累计发现与当前未修复覆盖对比，共 ${data.length} 个模块。`),
      legend: { top: 0, selectedMode: true },
      grid: {
        top: 52,
        right: 20,
        bottom: context.mode === 'view' && data.length > OverlayCategoryBarChart.VIEW_WINDOW_SIZE ? 72 : 64,
        left: 52,
        containLabel: true,
      },
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
      xAxis: { type: 'category', data: data.map((item) => item.name), axisLabel: { interval: 0, rotate: data.length > 6 ? 25 : 0 } },
      yAxis: { type: 'value', minInterval: 1 },
      dataZoom: this.categoryZoom(data.length, context.mode, OverlayCategoryBarChart.VIEW_WINDOW_SIZE),
      series: [
        {
          name: '累计发现',
          type: 'bar',
          barWidth: OverlayCategoryBarChart.BAR_WIDTH,
          barMaxWidth: OverlayCategoryBarChart.BAR_MAX_WIDTH,
          z: 1,
          itemStyle: { color: totalColor, opacity: 0.42, borderRadius: [4, 4, 0, 0] },
          label: { show: true, position: 'top', color: BI_PALETTE.text, formatter: '{c}' },
          labelLayout: { moveOverlap: 'shiftY' },
          data: data.map((item) => item.total),
        },
        {
          name: '当前未修复',
          type: 'bar',
          barWidth: OverlayCategoryBarChart.BAR_WIDTH,
          barMaxWidth: OverlayCategoryBarChart.BAR_MAX_WIDTH,
          barGap: '-100%',
          z: 2,
          itemStyle: { color: openColor, borderRadius: [3, 3, 0, 0] },
          label: { show: true, position: 'top', color: BI_PALETTE.text, formatter: '{c}' },
          labelLayout: { moveOverlap: 'shiftY' },
          data: data.map((item) => item.overlay),
        },
      ],
    };
  }
}
