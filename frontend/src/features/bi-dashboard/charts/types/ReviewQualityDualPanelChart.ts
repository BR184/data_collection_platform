import type { EChartsOption } from 'echarts';
import { BiChart, type BiChartRenderContext, type BiChartSize, type BiExcelTableData } from '../BiChart';
import type { ReviewQualityRow } from '../chart-data';
import { BI_PALETTE } from '../palette';
import { buildAdaptiveValueAxis } from '../adaptive-value-axis';
import { excelAchieved } from '../excel-format';

export interface ReviewQualityChartConfig {
  densityRange: readonly [number, number];
  densityUnit: string;
  rateUnit: string;
}

export class ReviewQualityDualPanelChart extends BiChart<ReviewQualityRow[]> {
  readonly templateId = 'review-quality-dual-panel' as const;

  constructor(private readonly config: ReviewQualityChartConfig) {
    super();
  }

  hasData(data: ReviewQualityRow[]): boolean {
    return data.length > 0;
  }

  exportSize(data: ReviewQualityRow[]): BiChartSize {
    return this.horizontalExportSize(data.length);
  }

  excelTable(data: ReviewQualityRow[]): BiExcelTableData {
    const hasAddedLines = data.some((item) => item.addedLines != null);
    const headers = ['模块名称', `缺陷密度 (${this.config.densityUnit})`, `评审速率 (${this.config.rateUnit})`];
    if (hasAddedLines) {
      headers.push('新增代码量 (行)');
    }
    headers.push('达标状态');
    return {
      headers,
      rows: data.map((item) => {
        const row: (string | number)[] = [
          item.name,
          item.density ?? '--',
          item.rate ?? '--',
        ];
        if (hasAddedLines) {
          row.push(item.addedLines ?? '--');
        }
        row.push(excelAchieved(item.achieved));
        return row;
      }),
    };
  }

  build(data: ReviewQualityRow[], context: BiChartRenderContext): EChartsOption {
    const categories = data.map((item) => item.name);
    const yAxis = { type: 'category' as const, inverse: true, data: categories, axisTick: { show: false } };
    const hasViewZoom = context.mode === 'view' && data.length > 10;
    const gridBottom = hasViewZoom ? 64 : 44;
    const densityAxis = buildAdaptiveValueAxis(data.map((item) => item.density), {
      minimumMax: Math.max(0.8, this.config.densityRange[1] + 0.1),
    });
    const rateAxis = buildAdaptiveValueAxis(data.map((item) => item.rate), { minimumMax: 24 });

    return {
      ...this.baseOption(`模块评审质量双面板，共 ${data.length} 个模块。`),
      legend: { show: false },
      graphic: [
        { type: 'text', left: 20, top: 8, silent: true, style: { text: `缺陷密度（${this.config.densityUnit}） · 目标 ${this.config.densityRange[0].toFixed(2)}–${this.config.densityRange[1].toFixed(2)}`, fill: '#475467', fontSize: 12, fontWeight: 600 } },
        { type: 'text', left: '56%', top: 8, silent: true, style: { text: `${this.config.rateUnit} · 无目标`, fill: '#667085', fontSize: 12, fontWeight: 600 } },
      ],
      grid: [
        { left: 118, right: '54%', top: 48, bottom: gridBottom, containLabel: true },
        { left: '56%', right: 34, top: 48, bottom: gridBottom, containLabel: false },
      ],
      tooltip: {
        trigger: 'item',
        formatter: (params: unknown) => {
          const item = data[Number((params as { dataIndex?: number }).dataIndex ?? 0)];
          if (!item) return '';
          const addedLinesText = item.addedLines != null ? `<br/>新增代码：${item.addedLines.toLocaleString()} 行` : '';
          return `${item.name}<br/>缺陷密度：${formatValue(item.density)} ${this.config.densityUnit}<br/>评审速率：${formatValue(item.rate)} ${this.config.rateUnit}${addedLinesText}`;
        },
      },
      xAxis: [
        { ...densityAxis, type: 'value', name: this.config.densityUnit, nameLocation: 'middle', nameGap: 28, gridIndex: 0, axisLabel: { formatter: (value: number) => value.toFixed(2) } },
        { ...rateAxis, type: 'value', name: this.config.rateUnit, nameLocation: 'middle', nameGap: 28, gridIndex: 1, axisLabel: { formatter: (value: number) => value.toFixed(0) } },
      ],
      yAxis: [yAxis, { ...yAxis, gridIndex: 1, axisLabel: { show: false }, axisLine: { show: false } }],
      dataZoom: hasViewZoom
        ? [{ type: 'slider', yAxisIndex: [0, 1], startValue: 0, endValue: 9, width: 14, right: 4 }]
        : [],
      series: [
        {
          name: '缺陷密度', type: 'bar', xAxisIndex: 0, yAxisIndex: 0, barWidth: 20,
          data: data.map((item) => ({ value: displayNumber(item.density), itemStyle: { color: statusColor(item.achieved), borderRadius: [0, 4, 4, 0] } })),
          label: { show: true, position: 'right', distance: 8, color: '#344054', formatter: (params: unknown) => formatValue((params as { value?: number }).value ?? null) },
          markArea: { silent: true, itemStyle: { color: 'rgba(145, 204, 117, 0.10)' }, data: [[{ xAxis: this.config.densityRange[0] }, { xAxis: this.config.densityRange[1] }]] },
          markLine: { silent: true, symbol: 'none', lineStyle: { type: 'dashed', color: 'rgba(145, 204, 117, .72)', width: 1 }, label: { show: false }, data: [{ xAxis: this.config.densityRange[0] }, { xAxis: this.config.densityRange[1] }] },
        },
        {
          name: '评审速率', type: 'scatter', xAxisIndex: 1, yAxisIndex: 1, symbol: 'circle', symbolSize: 13,
          data: data.map((item) => ({ value: [displayNumber(item.rate), item.name], itemStyle: { color: statusColor(item.achieved), borderColor: '#FFFFFF', borderWidth: 1.5, opacity: 0.95 } })),
          label: { show: false },
          emphasis: { focus: 'series', scale: true, label: { show: true, formatter: (params: unknown) => String((params as { value?: unknown[] }).value?.[1] ?? ''), position: 'right', color: '#344054', fontSize: 12, fontWeight: 600 } },
        },
      ],
    };
  }
}

function statusColor(achieved: boolean | null): string {
  return achieved == null ? BI_PALETTE.slate : achieved ? BI_PALETTE.green : BI_PALETTE.orange;
}

function displayNumber(value: number | null): number | null {
  return value == null ? null : Number(value.toFixed(2));
}

function formatValue(value: number | null): string {
  return value == null ? '--' : value.toFixed(2);
}
