import type { EChartsOption } from 'echarts';
import { BiChart, type BiChartRenderContext, type BiExcelTableData } from '../BiChart';
import type { ReviewScatterPoint } from '../chart-data';
import { BI_PALETTE } from '../palette';
import { buildAdaptiveValueAxis } from '../adaptive-value-axis';
import { excelAchieved } from '../excel-format';

export interface ReviewScatterChartConfig {
  densityRange: readonly [number, number];
  densityUnit: string;
  rateUnit: string;
}

export class ReviewQualityScatterChart extends BiChart<ReviewScatterPoint[]> {
  readonly templateId = 'review-quality-scatter' as const;

  constructor(private readonly config: ReviewScatterChartConfig) {
    super();
  }

  hasData(data: ReviewScatterPoint[]): boolean {
    return data.some((item) => item.rate != null && item.density != null);
  }

  excelTable(data: ReviewScatterPoint[]): BiExcelTableData {
    return {
      headers: ['模块名称', '评审日期', `评审速率 (${this.config.rateUnit})`, `缺陷密度 (${this.config.densityUnit})`, '达标状态'],
      rows: data.map((item) => [
        item.name,
        item.date ?? '--',
        item.rate ?? '--',
        item.density ?? '--',
        excelAchieved(item.achieved),
      ]),
    };
  }

  build(data: ReviewScatterPoint[], _context: BiChartRenderContext): EChartsOption {
    const points = data.filter((item) => item.rate != null && item.density != null);
    const rateAxis = buildAdaptiveValueAxis(points.map((item) => item.rate), { minimumMax: 24 });
    const densityAxis = buildAdaptiveValueAxis(points.map((item) => item.density), {
      minimumMax: Math.max(0.8, this.config.densityRange[1] + 0.1),
    });
    return {
      ...this.baseOption(`单次评审速率与缺陷密度散点图，共 ${points.length} 次评审。`),
      legend: { data: ['达标', '未达标'], top: 0, right: 8, selectedMode: false },
      graphic: [{ type: 'text', left: 8, bottom: 8, silent: true, style: { text: `目标：缺陷密度 ${this.config.densityRange[0].toFixed(2)}–${this.config.densityRange[1].toFixed(2)} ${this.config.densityUnit}；评审速率不设目标`, fill: '#7B8797', fontSize: 11 } }],
      grid: { top: 32, right: 28, bottom: 48, left: 62, containLabel: true },
      tooltip: {
        trigger: 'item',
        formatter: (params: unknown) => {
          const value = (params as { value?: unknown[] }).value;
          return value ? `${String(value[2] ?? '')}<br/>日期：${String(value[3] ?? '')}<br/>速率：${String(value[0] ?? '--')} ${this.config.rateUnit}<br/>缺陷密度：${String(value[1] ?? '--')} ${this.config.densityUnit}` : '';
        },
      },
      xAxis: { ...rateAxis, type: 'value', name: this.config.rateUnit, nameLocation: 'middle', nameGap: 32 },
      yAxis: { ...densityAxis, type: 'value', name: this.config.densityUnit, nameLocation: 'middle', nameGap: 42 },
      series: [
        scatterSeries('达标', points.filter((item) => item.achieved === true), this.config, true),
        scatterSeries('未达标', points.filter((item) => item.achieved === false), this.config, false),
        scatterSeries('不可计算', points.filter((item) => item.achieved == null), this.config, false),
      ].filter((series) => Array.isArray(series.data) && series.data.length > 0),
    };
  }
}

function scatterSeries(name: string, points: ReviewScatterPoint[], config: ReviewScatterChartConfig, includeTargetBand: boolean) {
  const targetBandData: [[{ yAxis: number }, { yAxis: number }]] = [[
    { yAxis: config.densityRange[0] },
    { yAxis: config.densityRange[1] },
  ]];
  return {
    name,
    type: 'scatter' as const,
    symbolSize: 13,
    data: points.map((item) => ({
      value: [item.rate, item.density, item.name, item.date],
      itemStyle: { color: statusColor(item.achieved), borderColor: '#FFFFFF', borderWidth: 2 },
    })),
    markArea: includeTargetBand ? { silent: true, itemStyle: { color: 'rgba(145, 204, 117, 0.09)' }, data: targetBandData } : undefined,
  };
}

function statusColor(achieved: boolean | null): string {
  return achieved == null ? BI_PALETTE.slate : achieved ? BI_PALETTE.green : BI_PALETTE.orange;
}
