import type {
  CustomSeriesRenderItemAPI,
  CustomSeriesRenderItemParams,
  CustomSeriesRenderItemReturn,
  EChartsOption,
} from 'echarts';
import { BiChart, type BiChartRenderContext, type BiChartSize } from '../BiChart';
import type { DeveloperWorkloadRow } from '../chart-data';
import { BI_PALETTE } from '../palette';

export class DeveloperWorkloadChart extends BiChart<DeveloperWorkloadRow[]> {
  readonly templateId = 'developer-workload' as const;

  private static readonly VIEW_WINDOW_SIZE = 18;

  hasData(data: DeveloperWorkloadRow[]): boolean {
    return data.length > 0;
  }

  exportSize(data: DeveloperWorkloadRow[]): BiChartSize {
    return this.verticalExportSize(data.length);
  }

  build(data: DeveloperWorkloadRow[], context: BiChartRenderContext): EChartsOption {
    const hasViewZoom = context.mode === 'view' && data.length > DeveloperWorkloadChart.VIEW_WINDOW_SIZE;
    const maxTotal = data.reduce((maximum, item) => Math.max(maximum, item.total), 0);
    return {
      ...this.baseOption(`指派人缺陷负荷，共 ${data.length} 人。`),
      legend: { top: 2, left: 'center', data: ['缺陷总数', '已修复', '待修复'] },
      graphic: [{
        type: 'text',
        left: 'center',
        top: 31,
        silent: true,
        style: { text: '左侧：缺陷总数；右侧：修复状态（已修复 / 待修复）', fill: '#667085', fontSize: 11, fontWeight: 500 },
      }],
      grid: { top: 82, right: hasViewZoom ? 44 : 24, bottom: hasViewZoom ? 104 : 92, left: 54, containLabel: true },
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
        formatter: (params: unknown) => {
          const list = Array.isArray(params) ? params : [];
          const dataIndex = Number((list[0] as { dataIndex?: number } | undefined)?.dataIndex ?? 0);
          const item = data[dataIndex];
          return item ? `${item.name}<br/>缺陷总数：${item.total}<br/>已修复：${item.fixed}<br/>待修复：${item.open}` : '';
        },
      },
        xAxis: {
        type: 'category',
        data: data.map((item) => item.name),
        axisLabel: { interval: 0, rotate: data.length > 10 ? 25 : 0, hideOverlap: false },
      },
      yAxis: { type: 'value', name: '缺陷数', min: 0, max: Math.max(1, Math.ceil(maxTotal * 1.24)), minInterval: 1 },
      dataZoom: hasViewZoom
        ? [{ type: 'slider', xAxisIndex: 0, startValue: 0, endValue: DeveloperWorkloadChart.VIEW_WINDOW_SIZE - 1, height: 14, bottom: 6, showDataShadow: false }]
        : [],
      series: [
        {
          name: '缺陷总数',
          type: 'bar',
          barMaxWidth: 42,
          barGap: '34%',
          barCategoryGap: '42%',
          itemStyle: { color: BI_PALETTE.slate, opacity: 0.72, borderRadius: [4, 4, 0, 0] },
          label: { show: false },
          data: data.map((item) => item.total),
        },
        {
          name: '已修复',
          type: 'bar',
          stack: 'status',
          barMaxWidth: 42,
          barGap: '34%',
          barCategoryGap: '42%',
          itemStyle: { color: BI_PALETTE.green, borderRadius: [0, 0, 3, 3] },
          label: { show: false },
          data: data.map((item) => item.fixed),
        },
        {
          name: '待修复',
          type: 'bar',
          stack: 'status',
          barMaxWidth: 42,
          barGap: '34%',
          barCategoryGap: '42%',
          itemStyle: { color: BI_PALETTE.red, borderRadius: [3, 3, 0, 0] },
          label: { show: false },
          data: data.map((item) => item.open),
        },
        {
          name: '指派人摘要',
          type: 'custom',
          coordinateSystem: 'cartesian2d',
          encode: { x: 0, y: 1 },
          data: data.map((item, index) => [index, item.total, item.open]),
          renderItem: (params: CustomSeriesRenderItemParams, api: CustomSeriesRenderItemAPI): CustomSeriesRenderItemReturn => {
            const customApi = api as unknown as { value(dimension: number): unknown; coord(value: unknown[]): [number, number] };
            const total = numericValue(customApi.value(1));
            const open = numericValue(customApi.value(2));
            if (total == null || open == null) return { type: 'group', children: [] } as unknown as CustomSeriesRenderItemReturn;
            const point = customApi.coord([customApi.value(0), total]);
            const summaryWidth = 58;
            const summaryLineHeight = 13;
            const summaryHeight = summaryLineHeight * 2 + 8;
            const summaryOffset = 8;
            const summaryX = point[0] - summaryWidth / 2;
            const summaryY = point[1] - summaryHeight - summaryOffset;
            return {
              type: 'group',
              x: summaryX,
              y: summaryY,
              silent: true,
              z2: 20,
              children: [
                {
                  type: 'rect',
                  shape: { x: 0, y: 0, width: summaryWidth, height: summaryHeight, r: 3 },
                  style: { fill: 'rgba(255,255,255,.96)', stroke: 'rgba(154,159,176,.42)', lineWidth: 1 },
                },
                {
                  type: 'text',
                  style: { x: summaryWidth / 2, y: 4 + summaryLineHeight / 2, text: `总数 ${total}`, fill: '#667085', font: '700 10px sans-serif', textAlign: 'center', textVerticalAlign: 'middle' },
                },
                {
                  type: 'text',
                  style: { x: summaryWidth / 2, y: 4 + summaryLineHeight + summaryLineHeight / 2, text: `待修复 ${open}`, fill: '#B5474F', font: '750 10px sans-serif', textAlign: 'center', textVerticalAlign: 'middle' },
                },
              ],
            } as unknown as CustomSeriesRenderItemReturn;
          },
          tooltip: { show: false },
          silent: true,
          z: 10,
        },
      ],
    };
  }
}

function numericValue(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}
