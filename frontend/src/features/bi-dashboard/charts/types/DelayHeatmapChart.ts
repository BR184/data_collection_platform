import type { EChartsOption } from 'echarts';
import { BiChart, type BiChartRenderContext, type BiChartSize, type BiExcelTableData } from '../BiChart';
import type { DelayHeatmapData } from '../chart-data';
import { BI_PALETTE } from '../palette';
import { callbackTupleValue } from '../formatters';

export class DelayHeatmapChart extends BiChart<DelayHeatmapData> {
  readonly templateId = 'delay-heatmap' as const;

  hasData(data: DelayHeatmapData): boolean {
    return data.values.length > 0;
  }

  exportSize(data: DelayHeatmapData): BiChartSize {
    return { width: Math.max(1200, data.severities.length * 150 + 260), height: Math.max(620, data.reasons.length * 48 + 180) };
  }

  excelTable(data: DelayHeatmapData): BiExcelTableData {
    // 热力元组为 [严重级别索引, 原因索引, 延期缺陷数]，与图表 xAxis=严重级别、yAxis=原因一致。
    return {
      headers: ['原因分类', '缺陷级别', '延期缺陷数'],
      rows: data.values.map(([severityIndex, reasonIndex, count]) => [
        data.reasons[reasonIndex] ?? '',
        data.severities[severityIndex] ?? '',
        count,
      ]),
    };
  }

  build(data: DelayHeatmapData, _context: BiChartRenderContext): EChartsOption {
    const maxValue = Math.max(1, ...data.values.map((item) => item[2]));
    return {
      ...this.baseOption(`延期原因与严重度热力矩阵，共 ${data.values.length} 个单元格。`),
      grid: { top: 28, right: 72, bottom: 44, left: 20, containLabel: true },
      tooltip: { position: 'top', formatter: (params: unknown) => {
        const value = callbackTupleValue(params);
        return value ? `${data.reasons[value[1]] ?? ''}<br/>${data.severities[value[0]] ?? ''}：${value[2]}` : '';
      } },
      xAxis: { type: 'category', data: data.severities, splitArea: { show: true } },
      yAxis: { type: 'category', data: data.reasons, inverse: true, splitArea: { show: true } },
      visualMap: {
        min: 0,
        max: maxValue,
        calculable: false,
        orient: 'vertical',
        right: 4,
        top: 'middle',
        inRange: { color: [BI_PALETTE.orange, BI_PALETTE.red] },
      },
      series: [{
        name: '延期缺陷数',
        type: 'heatmap',
        data: data.values.map((value) => ({
          value,
          itemStyle: value[2] === 0 ? { color: '#F3F4F6' } : undefined,
          label: { color: value[2] / maxValue > 0.52 ? '#FFFFFF' : '#111827' },
        })),
        label: { show: true, fontWeight: 600 },
        itemStyle: { borderColor: '#FFFFFF', borderWidth: 2 },
      }],
    };
  }
}
