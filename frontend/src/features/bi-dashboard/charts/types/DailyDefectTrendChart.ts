import type { EChartsOption } from 'echarts';
import { BiChart, type BiChartRenderContext, type BiChartSize, type BiExcelTableData } from '../BiChart';
import type { DailyDefectTrendData } from '../chart-data';
import { BI_PALETTE } from '../palette';

/** 当前事实的客户缺陷日增与日修复回看趋势，不代表历史事件流水。 */
export class DailyDefectTrendChart extends BiChart<DailyDefectTrendData> {
  readonly templateId = 'daily-defect-trend' as const;

  hasData(data: DailyDefectTrendData): boolean {
    return data.dates.length > 0
      && [...data.createdCounts, ...data.fixedCounts].some((value) => value != null && value > 0);
  }

  exportSize(data: DailyDefectTrendData): BiChartSize {
    return { width: Math.max(1440, Math.min(32000, data.dates.length * 36 + 260)), height: 760 };
  }

  excelTable(data: DailyDefectTrendData): BiExcelTableData {
    return {
      headers: ['日期', '缺陷日增（个）', '缺陷日修复（个）'],
      rows: data.dates.map((date, index) => [
        date,
        data.createdCounts[index] ?? null,
        data.fixedCounts[index] ?? null,
      ]),
    };
  }

  build(data: DailyDefectTrendData, context: BiChartRenderContext): EChartsOption {
    return {
      ...this.baseOption('客户缺陷按自然日统计日增和日修复；按当前事实回看，撤销或重新修复可能改变历史日期结果。'),
      legend: { top: 0 },
      grid: { top: 48, right: 24, bottom: context.mode === 'view' && data.dates.length > 30 ? 76 : 48, left: 66, containLabel: true },
      tooltip: {
        trigger: 'axis',
        formatter: (params: unknown) => formatTooltip(params),
      },
      xAxis: {
        type: 'category',
        data: data.dates,
        axisLabel: { rotate: data.dates.length > 20 ? 35 : 0, hideOverlap: true },
      },
      yAxis: { type: 'value', name: '缺陷数（个）', min: 0, minInterval: 1 },
      dataZoom: this.categoryZoom(data.dates.length, context.mode, 30),
      series: [
        {
          name: '缺陷日增',
          type: 'line',
          data: data.createdCounts,
          smooth: false,
          connectNulls: false,
          showSymbol: false,
          lineStyle: { width: 2, color: BI_PALETTE.blue },
          itemStyle: { color: BI_PALETTE.blue },
        },
        {
          name: '缺陷日修复',
          type: 'line',
          data: data.fixedCounts,
          smooth: false,
          connectNulls: false,
          showSymbol: false,
          lineStyle: { width: 2, color: BI_PALETTE.teal },
          itemStyle: { color: BI_PALETTE.teal },
        },
      ],
    };
  }
}

function formatTooltip(params: unknown): string {
  if (!Array.isArray(params) || params.length === 0) return '';
  const first = params[0] as { axisValue?: unknown };
  const date = typeof first.axisValue === 'string' ? first.axisValue : '';
  const lines = params.map((item) => {
    const point = item as { seriesName?: unknown; value?: unknown; marker?: unknown };
    const label = typeof point.seriesName === 'string' ? point.seriesName : '';
    const value = typeof point.value === 'number' ? `${point.value} 个` : '未知';
    const marker = typeof point.marker === 'string' ? point.marker : '';
    return `${marker}${label}：${value}`;
  });
  return [date, ...lines].join('<br/>');
}
