import type {
  CustomSeriesRenderItemAPI,
  CustomSeriesRenderItemParams,
  CustomSeriesRenderItemReturn,
  EChartsOption,
} from 'echarts';
import { BiChart, type BiChartRenderContext, type BiChartSize, type BiExcelTableData } from '../BiChart';
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

  excelTable(data: DeveloperWorkloadRow[]): BiExcelTableData {
    return {
      headers: ['指派责任人', '缺陷总数', '已修复缺陷数', '待修复缺陷数', '修复率 (%)'],
      rows: data.map((item) => {
        const fixRate = item.total > 0 ? Number(((item.fixed / item.total) * 100).toFixed(1)) : 100;
        return [item.name, item.total, item.fixed, item.open, fixRate];
      }),
    };
  }

  build(data: DeveloperWorkloadRow[], context: BiChartRenderContext): EChartsOption {
    const hasViewZoom = context.mode === 'view' && data.length > DeveloperWorkloadChart.VIEW_WINDOW_SIZE;
    const maxTotal = data.reduce((maximum, item) => Math.max(maximum, item.total), 0);
    return {
      ...this.baseOption(`按指派人统计缺陷数，共 ${data.length} 人。`),
      legend: { top: 2, left: 'center', data: ['已修复', '待修复'] },
      grid: { top: 48, right: hasViewZoom ? 44 : 24, bottom: hasViewZoom ? 104 : 92, left: 54, containLabel: true },
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
        formatter: (params: unknown) => {
          const list = Array.isArray(params) ? params : [];
          const dataIndex = Number((list[0] as { dataIndex?: number } | undefined)?.dataIndex ?? 0);
          const item = data[dataIndex];
          if (!item) return '';
          const repairRate = item.total > 0 ? ((item.fixed / item.total) * 100).toFixed(1) : '100.0';
          return `<strong>${item.name}</strong><br/>`
            + `● 缺陷总数：${item.total}<br/>`
            + `● 已修复：${item.fixed}<br/>`
            + `● 待修复：${item.open}<br/>`
            + `● 修复率：${repairRate}%`;
        },
      },
      xAxis: {
        type: 'category',
        data: data.map((item) => item.name),
        axisLabel: { interval: 0, rotate: data.length > 10 ? 25 : 0, hideOverlap: false },
      },
      yAxis: { type: 'value', name: '缺陷数 (个)', min: 0, max: Math.max(1, Math.ceil(maxTotal * 1.18)), minInterval: 1 },
      dataZoom: hasViewZoom
        ? [{ type: 'slider', xAxisIndex: 0, startValue: 0, endValue: DeveloperWorkloadChart.VIEW_WINDOW_SIZE - 1, height: 14, bottom: 6, showDataShadow: false }]
        : [],
      series: [
        {
          name: '已修复',
          type: 'bar',
          stack: 'workload',
          barMaxWidth: 38,
          barCategoryGap: '38%',
          itemStyle: { color: BI_PALETTE.green },
          label: {
            show: true,
            position: 'inside',
            formatter: (params: unknown) => {
              const val = Number((params as { value?: number }).value ?? 0);
              return val > 0 ? String(val) : '';
            },
            color: '#FFFFFF',
            fontSize: 11,
            fontWeight: 600,
          },
          data: data.map((item) => item.fixed),
        },
        {
          name: '待修复',
          type: 'bar',
          stack: 'workload',
          barMaxWidth: 38,
          barCategoryGap: '38%',
          itemStyle: { color: BI_PALETTE.orange, borderRadius: [4, 4, 0, 0] },
          label: {
            show: true,
            position: 'inside',
            formatter: (params: unknown) => {
              const val = Number((params as { value?: number }).value ?? 0);
              return val > 0 ? String(val) : '';
            },
            color: '#FFFFFF',
            fontSize: 11,
            fontWeight: 600,
          },
          data: data.map((item) => item.open),
        },
        {
          name: '总数标顶',
          type: 'custom',
          coordinateSystem: 'cartesian2d',
          data: data.map((item, index) => [index, item.total]),
          renderItem: (params: CustomSeriesRenderItemParams, api: CustomSeriesRenderItemAPI): CustomSeriesRenderItemReturn => {
            const customApi = api as unknown as { value(dimension: number): unknown; coord(value: unknown[]): [number, number] };
            const point = customApi.coord([customApi.value(0), customApi.value(1)]);
            const total = Number(customApi.value(1) ?? 0);
            if (total <= 0) return { type: 'group', children: [] } as unknown as CustomSeriesRenderItemReturn;
            return {
              type: 'text',
              style: {
                x: point[0],
                y: point[1] - 6,
                text: String(total),
                fill: '#475467',
                font: '600 11px sans-serif',
                textAlign: 'center',
                textVerticalAlign: 'bottom',
              },
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
