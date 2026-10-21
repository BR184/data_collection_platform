import type { EChartsOption } from 'echarts';
import {
  BiChart,
  type BiChartRenderContext,
  type BiChartRenderMode,
  type BiChartSize,
  type BiExcelTableData,
} from '../BiChart';
import type { CategorySeriesData } from '../chart-data';
import { BI_SERIES_COLORS } from '../palette';

export interface StackedCategoryBarChartConfig {
  /** 数值轴名称；两种朝向下都表示数量轴，不表示分类轴。 */
  yAxisName?: string;
  /**
   * 条形朝向。默认 `vertical-bars`（分类沿横轴）保持系统测试等既有消费者不变；
   * 客户问题页用 `horizontal-bars`：分类沿纵轴、最大值在上、视图纵向浏览，窄窗口不切掉类别文字。
   */
  orientation?: 'vertical-bars' | 'horizontal-bars';
  /** `horizontal-bars` 下页面视图可见的分类数量；导出始终完整，不受此限制。 */
  visibleCategories?: number;
}

export class StackedCategoryBarChart extends BiChart<CategorySeriesData> {
  readonly templateId = 'stacked-category-bar' as const;

  private static readonly DEFAULT_VISIBLE_CATEGORIES = 12;

  constructor(private readonly config: StackedCategoryBarChartConfig = {}) {
    super();
  }

  hasData(data: CategorySeriesData): boolean {
    return data.categories.length > 0 && data.series.some((series) => series.values.some((value) => value > 0));
  }

  exportSize(data: CategorySeriesData): BiChartSize {
    return this.horizontalOrientation()
      ? this.horizontalExportSize(data.categories.length)
      : this.verticalExportSize(data.categories.length);
  }

  excelTable(data: CategorySeriesData): BiExcelTableData {
    return {
      headers: ['分类/模块', ...data.series.map((series) => series.name)],
      rows: data.categories.map((category, index) => [
        category,
        ...data.series.map((series) => series.values[index] ?? 0),
      ]),
    };
  }

  build(data: CategorySeriesData, context: BiChartRenderContext): EChartsOption {
    const horizontal = this.horizontalOrientation();
    const browsable = horizontal
      ? this.browsableCategories(data.categories.length, context.mode)
      : this.categoryZoom(data.categories.length, context.mode);
    return {
      ...this.baseOption(`堆叠分类柱状图，共 ${data.categories.length} 个类别。`),
      legend: { top: 0, left: 'center' },
      grid: horizontal
        ? { top: 48, right: 40, bottom: 40, left: 12, containLabel: true }
        : {
          top: 48,
          right: 20,
          bottom: context.mode === 'view' && data.categories.length > 12 ? 72 : 64,
          left: 52,
          containLabel: true,
        },
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
      // 朝向下只交换两条轴的角色：分类始终承载同一份顺序，数量轴名称不变。
      xAxis: horizontal
        ? { type: 'value', name: this.config.yAxisName, minInterval: 1 }
        : {
          type: 'category',
          data: data.categories,
          axisLabel: { interval: 0, rotate: data.categories.length > 6 ? 25 : 0, hideOverlap: false },
        },
      yAxis: horizontal
        ? {
          type: 'category',
          // inverse 让排序后的第一个（最大）类别出现在顶部。
          inverse: true,
          data: data.categories,
          axisLabel: { interval: 0, hideOverlap: false },
        }
        : { type: 'value', name: this.config.yAxisName, minInterval: 1 },
      dataZoom: browsable,
      series: data.series.map((item, index) => {
        const color = item.color ?? BI_SERIES_COLORS[index % BI_SERIES_COLORS.length]!;
        return {
          name: item.name,
          type: 'bar' as const,
          stack: 'total',
          barMaxWidth: 54,
          itemStyle: { color },
          label: index === data.series.length - 1
            ? {
              show: true,
              position: horizontal ? ('right' as const) : ('top' as const),
              color: '#344054',
              formatter: (params: unknown) => {
                const itemIndex = Number((params as { dataIndex?: number }).dataIndex ?? 0);
                return String(data.series.reduce((sum, series) => sum + (series.values[itemIndex] ?? 0), 0));
              },
            }
            : { show: false },
          data: item.values,
        };
      }),
    };
  }

  private horizontalOrientation(): boolean {
    return this.config.orientation === 'horizontal-bars';
  }

  /** 纵向浏览：滑块沿分类轴（纵轴）移动，不保留横向滑块；导出模式返回完整类别。 */
  private browsableCategories(itemCount: number, mode: BiChartRenderMode) {
    const visible = this.config.visibleCategories ?? StackedCategoryBarChart.DEFAULT_VISIBLE_CATEGORIES;
    if (mode === 'export' || itemCount <= visible) {
      return [];
    }
    return [{ type: 'slider' as const, yAxisIndex: 0, startValue: 0, endValue: visible - 1, width: 12, right: 4, showDataShadow: false }];
  }
}
