import type {
  CustomSeriesRenderItemAPI,
  CustomSeriesRenderItemParams,
  CustomSeriesRenderItemReturn,
  EChartsOption,
} from 'echarts';
import {
  BiChart,
  type BiChartRenderContext,
  type BiChartRenderMode,
  type BiChartSize,
  type BiExcelTableData,
} from '../BiChart';
import type { DeveloperWorkloadRow } from '../chart-data';
import { BI_PALETTE } from '../palette';

export interface DeveloperWorkloadChartConfig {
  /**
   * 条形朝向。默认 `vertical-bars`（指派人沿横轴）保持系统测试等既有消费者不变；
   * 客户问题页用 `horizontal-bars`：指派人沿纵轴、最大值在上、视图纵向浏览。
   */
  orientation?: 'vertical-bars' | 'horizontal-bars';
  /** `horizontal-bars` 下页面视图可见的指派人数量；导出始终完整，不受此限制。 */
  visibleCategories?: number;
}

export class DeveloperWorkloadChart extends BiChart<DeveloperWorkloadRow[]> {
  readonly templateId = 'developer-workload' as const;

  private static readonly VIEW_WINDOW_SIZE = 18;

  private static readonly DEFAULT_VISIBLE_CATEGORIES = 10;

  constructor(private readonly config: DeveloperWorkloadChartConfig = {}) {
    super();
  }

  hasData(data: DeveloperWorkloadRow[]): boolean {
    return data.length > 0;
  }

  exportSize(data: DeveloperWorkloadRow[]): BiChartSize {
    return this.horizontalOrientation()
      ? this.horizontalExportSize(data.length)
      : this.verticalExportSize(data.length);
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
    const horizontal = this.horizontalOrientation();
    const names = data.map((item) => item.name);
    const maxTotal = data.reduce((maximum, item) => Math.max(maximum, item.total), 0);
    const valueMax = Math.max(1, Math.ceil(maxTotal * 1.18));
    const dataZoom = horizontal
      ? this.browsableRows(data.length, context.mode)
      : this.viewableColumns(data.length, context.mode);
    return {
      ...this.baseOption(`按指派人统计缺陷数，共 ${data.length} 人。`),
      legend: { top: 2, left: 'center', data: ['已修复', '待修复'] },
      grid: horizontal
        ? { top: 48, right: 44, bottom: 40, left: 12, containLabel: true }
        : { top: 48, right: dataZoom.length > 0 ? 44 : 24, bottom: dataZoom.length > 0 ? 104 : 92, left: 54, containLabel: true },
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
      // 朝向下只交换两条轴的角色：指派人轴承载同一份顺序，数量轴名称不变。
      xAxis: horizontal
        ? { type: 'value', name: '缺陷数 (个)', min: 0, max: valueMax, minInterval: 1 }
        : { type: 'category', data: names, axisLabel: { interval: 0, rotate: data.length > 10 ? 25 : 0, hideOverlap: false } },
      yAxis: horizontal
        ? {
          type: 'category',
          // inverse 让排序后的第一个（最大）指派人出现在顶部。
          inverse: true,
          data: names,
          axisLabel: { interval: 0, hideOverlap: false },
        }
        : { type: 'value', name: '缺陷数 (个)', min: 0, max: valueMax, minInterval: 1 },
      dataZoom,
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
          itemStyle: { color: BI_PALETTE.orange, borderRadius: horizontal ? [0, 4, 4, 0] : [4, 4, 0, 0] },
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
          // 维度顺序跟随朝向：数值维度对应数量轴，分类维度使用数组下标，避免同名指派人被合并。
          data: horizontal
            ? data.map((item, index) => [item.total, index])
            : data.map((item, index) => [index, item.total]),
          renderItem: (params: CustomSeriesRenderItemParams, api: CustomSeriesRenderItemAPI): CustomSeriesRenderItemReturn => {
            const customApi = api as unknown as { value(dimension: number): unknown; coord(value: unknown[]): [number, number] };
            const point = customApi.coord([customApi.value(0), customApi.value(1)]);
            const total = Number(horizontal ? customApi.value(0) : customApi.value(1));
            if (total <= 0) return { type: 'group', children: [] } as unknown as CustomSeriesRenderItemReturn;
            return {
              type: 'text',
              style: horizontal
                ? {
                  x: point[0] + 6,
                  y: point[1],
                  text: String(total),
                  fill: '#475467',
                  font: '600 11px sans-serif',
                  textAlign: 'left',
                  textVerticalAlign: 'middle',
                }
                : {
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

  private horizontalOrientation(): boolean {
    return this.config.orientation === 'horizontal-bars';
  }

  private viewableColumns(itemCount: number, mode: BiChartRenderMode) {
    if (mode === 'export' || itemCount <= DeveloperWorkloadChart.VIEW_WINDOW_SIZE) {
      return [];
    }
    return [{ type: 'slider' as const, xAxisIndex: 0, startValue: 0, endValue: DeveloperWorkloadChart.VIEW_WINDOW_SIZE - 1, height: 14, bottom: 6, showDataShadow: false }];
  }

  /** 纵向浏览：滑块沿指派人轴（纵轴）移动，不保留横向滑块；导出模式返回完整名单。 */
  private browsableRows(itemCount: number, mode: BiChartRenderMode) {
    const visible = this.config.visibleCategories ?? DeveloperWorkloadChart.DEFAULT_VISIBLE_CATEGORIES;
    if (mode === 'export' || itemCount <= visible) {
      return [];
    }
    return [{ type: 'slider' as const, yAxisIndex: 0, startValue: 0, endValue: visible - 1, width: 12, right: 4, showDataShadow: false }];
  }
}
