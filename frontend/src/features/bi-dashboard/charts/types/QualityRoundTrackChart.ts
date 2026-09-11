import type { EChartsOption } from 'echarts';
import { BiChart, type BiChartRenderContext, type BiChartSize, type BiExcelTableData } from '../BiChart';
import type { RoundQualityRow } from '../chart-data';
import { BI_PALETTE, readableTextColor } from '../palette';
import { callbackDataIndex, callbackNumericValue } from '../formatters';
import { excelPercent } from '../excel-format';

export class QualityRoundTrackChart extends BiChart<RoundQualityRow[]> {
  readonly templateId = 'quality-round-track' as const;

  hasData(data: RoundQualityRow[]): boolean {
    return data.length > 0;
  }

  exportSize(data: RoundQualityRow[]): BiChartSize {
    return this.horizontalExportSize(data.length);
  }

  excelTable(data: RoundQualityRow[]): BiExcelTableData {
    return {
      headers: ['测试轮次', '提交缺陷总数', '已关闭数', '未关闭数', '关闭率 (%)', '一级缺陷', '二级缺陷', '三级缺陷'],
      rows: data.map((item) => [
        item.name,
        item.submitted,
        item.closed,
        item.open,
        excelPercent(item.closeRate, 1),
        item.levelOne,
        item.levelTwo,
        item.levelThree,
      ]),
    };
  }

  build(data: RoundQualityRow[], context: BiChartRenderContext): EChartsOption {
    const closePercent = data.map((item) => percentage(item.closed, item.closed + item.open));
    const openPercent = data.map((item) => percentage(item.open, item.closed + item.open));
    const category = { type: 'category' as const, inverse: true, data: data.map((item) => item.name), axisTick: { show: false } };
    const hasViewZoom = context.mode === 'view' && data.length > 7;
    const gridBottom = hasViewZoom ? 64 : 44;
    return {
      ...this.baseOption(`系统测试轮次质量轨道，共 ${data.length} 个轮次。`),
      legend: { top: 0 },
      grid: [
        { left: 20, right: '31%', top: 50, bottom: gridBottom, containLabel: true },
        { left: '74%', right: 28, top: 50, bottom: gridBottom, containLabel: false },
      ],
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
      xAxis: [
        { type: 'value', minInterval: 1, gridIndex: 0, name: '缺陷数', nameLocation: 'middle', nameGap: 28 },
        { type: 'value', min: 0, max: 100, gridIndex: 1, name: '关闭状态', nameLocation: 'middle', nameGap: 28, axisLabel: { formatter: '{value}%' } },
      ],
      yAxis: [category, { ...category, gridIndex: 1, axisLabel: { show: false }, axisLine: { show: false } }],
      dataZoom: hasViewZoom
        ? [{ type: 'slider', yAxisIndex: [0, 1], startValue: 0, endValue: 6, width: 14, right: 4 }]
        : [],
      series: [
        severitySeries('一级', data.map((item) => item.levelOne), BI_PALETTE.red),
        severitySeries('二级', data.map((item) => item.levelTwo), BI_PALETTE.orange),
        severitySeries('三级', data.map((item) => item.levelThree), BI_PALETTE.blue, data.map((item) => item.submitted)),
        {
          name: '已关闭', type: 'bar', stack: 'state', xAxisIndex: 1, yAxisIndex: 1, barWidth: 22,
          itemStyle: { color: BI_PALETTE.green },
          label: { show: true, position: 'inside', color: readableTextColor(BI_PALETTE.green), formatter: (params: unknown) => String(data[callbackDataIndex(params)]?.closed ?? '') },
          data: closePercent,
        },
        {
          name: '未关闭', type: 'bar', stack: 'state', xAxisIndex: 1, yAxisIndex: 1, barWidth: 22,
          itemStyle: { color: BI_PALETTE.slate },
          label: { show: true, position: 'inside', color: readableTextColor(BI_PALETTE.slate), formatter: (params: unknown) => String(data[callbackDataIndex(params)]?.open ?? '') },
          data: openPercent,
        },
      ],
    };
  }
}

function percentage(value: number, total: number): number {
  return total <= 0 ? 0 : Number(((value * 100) / total).toFixed(2));
}

function severitySeries(name: string, values: number[], color: string, submitted?: number[]) {
  return {
    name,
    type: 'bar' as const,
    stack: 'severity',
    xAxisIndex: 0,
    yAxisIndex: 0,
    barWidth: 22,
    itemStyle: { color },
    label: {
      show: true,
      position: name === '三级' ? 'right' as const : 'inside' as const,
      color: name === '三级' ? '#334155' : readableTextColor(color),
      formatter: (params: unknown) => {
        const dataIndex = callbackDataIndex(params);
        if (name === '三级' && submitted) {
          return `提交 ${submitted[dataIndex]}`;
        }
        const value = callbackNumericValue(params);
        return value != null && value > 0 ? String(value) : '';
      },
    },
    data: values,
  };
}
