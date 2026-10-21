import type { EChartsOption } from 'echarts';
import { BiChart, type BiChartRenderContext, type BiChartSize, type BiExcelTableData } from '../BiChart';
import type { DailyDefectTrendData } from '../chart-data';
import { BI_PALETTE } from '../palette';

/** 客户缺陷累计新增与累计修复趋势；对同一来源版本的日期贡献作前缀累加，不代表历史事件流水。 */
export class DailyDefectTrendChart extends BiChart<DailyDefectTrendData> {
  readonly templateId = 'daily-defect-trend' as const;

  hasData(data: DailyDefectTrendData): boolean {
    return data.dates.length > 0
      && [...data.cumulativeCreatedCounts, ...data.cumulativeFixedCounts]
        .some((value) => value != null && value > 0);
  }

  exportSize(data: DailyDefectTrendData): BiChartSize {
    return { width: Math.max(1440, Math.min(32000, data.dates.length * 36 + 260)), height: 760 };
  }

  excelTable(data: DailyDefectTrendData): BiExcelTableData {
    return {
      headers: ['日期', '缺陷累计新增（个）', '缺陷累计修复（个）'],
      rows: data.dates.map((date, index) => [
        date,
        data.cumulativeCreatedCounts[index] ?? null,
        data.cumulativeFixedCounts[index] ?? null,
      ]),
    };
  }

  build(data: DailyDefectTrendData, context: BiChartRenderContext): EChartsOption {
    return {
      ...this.baseOption('客户缺陷按自然日累计新增和累计修复；对同一来源版本的日期贡献作前缀累加，撤销或重新修复可能改变历史累计值。'),
      legend: { top: 0, data: ['缺陷累计新增', '缺陷累计修复'] },
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
      yAxis: { type: 'value', name: '累计缺陷数（个）', min: 0, minInterval: 1 },
      dataZoom: this.categoryZoom(data.dates.length, context.mode, 30),
      series: [
        {
          name: '缺陷累计新增',
          type: 'line',
          data: data.cumulativeCreatedCounts,
          smooth: false,
          connectNulls: false,
          showSymbol: false,
          lineStyle: { width: 2, color: BI_PALETTE.blue },
          itemStyle: { color: BI_PALETTE.blue },
        },
        {
          name: '缺陷累计修复',
          type: 'line',
          data: data.cumulativeFixedCounts,
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

/**
 * 转换客户问题日趋势数据，默认从首个存在实际缺陷（累计新增或累计修复大于 0）的日期开始截取，
 * 避免因起始日期（如 2026-01-01）至首个缺陷产生之间的无数据区间导致图表默认显示大段 0 值平线。
 * 若整段数据均无记录（均为 0 或 null），则保留原数据，由图表 hasData 触发空状态展示。
 */
export function buildCustomerIssueDailyTrend(
  rows: Array<{ date: string; cumulativeCreatedCount: number | null; cumulativeFixedCount: number | null }>,
): DailyDefectTrendData {
  if (!rows || rows.length === 0) {
    return { dates: [], cumulativeCreatedCounts: [], cumulativeFixedCounts: [] };
  }
  const firstDataIndex = rows.findIndex(
    (row) => (row.cumulativeCreatedCount != null && row.cumulativeCreatedCount > 0)
      || (row.cumulativeFixedCount != null && row.cumulativeFixedCount > 0),
  );
  const effectiveRows = firstDataIndex >= 0 ? rows.slice(firstDataIndex) : rows;
  return {
    dates: effectiveRows.map((row) => row.date),
    cumulativeCreatedCounts: effectiveRows.map((row) => row.cumulativeCreatedCount),
    cumulativeFixedCounts: effectiveRows.map((row) => row.cumulativeFixedCount),
  };
}
