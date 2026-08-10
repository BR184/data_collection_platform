import type { CustomSeriesRenderItemAPI, CustomSeriesRenderItemParams, CustomSeriesRenderItemReturn, EChartsOption } from 'echarts';
import { BiChart, type BiChartRenderContext, type BiChartSize } from '../BiChart';
import type { ModuleRepairRow } from '../chart-data';
import { BI_PALETTE } from '../palette';

export class ModuleRepairMatrixChart extends BiChart<ModuleRepairRow[]> {
  readonly templateId = 'module-repair-matrix' as const;

  hasData(data: ModuleRepairRow[]): boolean {
    return data.length > 0;
  }

  exportSize(data: ModuleRepairRow[]): BiChartSize {
    return this.horizontalExportSize(data.length);
  }

  build(data: ModuleRepairRow[], context: BiChartRenderContext): EChartsOption {
    const targets = [95, 100, 90, 80];
    const labels = ['整体修复率\n目标95%', '一级缺陷\n目标100%', 'P1优先级\n目标90%', 'P2优先级\n目标80%'];
    return {
      ...this.baseOption(`模块修复率达成矩阵，共 ${data.length} 个模块。`),
      legend: { show: false },
      graphic: labels.map((text, index) => ({
        type: 'text',
        left: `${index === 0 ? 39 : 58 + (index - 1) * 13}%`,
        top: 8,
        silent: true,
        style: { text, fill: '#475467', fontSize: 12, fontWeight: 600, lineHeight: 16, textAlign: 'center' },
      })),
      grid: { top: 54, right: context.mode === 'view' && data.length > 10 ? 46 : 28, bottom: context.mode === 'view' && data.length > 10 ? 48 : 24, left: 118, containLabel: true },
      tooltip: {
        trigger: 'item',
        formatter: (params: unknown) => {
          const value = (params as { value?: unknown }).value;
          if (!Array.isArray(value)) return '';
          return `${value[4]}<br/>整体修复率：${displayRate(value[0])}<br/>一级缺陷：${displayRate(value[1])}，P1：${displayRate(value[2])}，P2：${displayRate(value[3])}`;
        },
      },
      xAxis: { type: 'value', min: 0, max: 100, show: false },
      yAxis: { type: 'category', inverse: true, data: data.map((item) => item.name), axisLabel: { width: 104, overflow: 'truncate', color: '#5F6B7A' } },
      dataZoom: context.mode === 'view' && data.length > 10
        ? [{ type: 'slider', yAxisIndex: 0, startValue: 0, endValue: 9, width: 14, right: 4 }]
        : [],
      series: [{
        name: '模块修复率',
        type: 'custom',
        encode: { y: 5 },
        data: data.map((item, index) => [item.fixRate, item.levelOneRate, item.p1Rate, item.p2Rate, item.name, index]),
        renderItem: (params: CustomSeriesRenderItemParams, api: CustomSeriesRenderItemAPI): CustomSeriesRenderItemReturn => {
          const customApi = api as unknown as { value(dimension: number): unknown; coord(value: unknown[]): [number, number]; size(value: unknown[]): [number, number] };
          const coordSys = params.coordSys as unknown as { x: number; width: number };
          const rowIndex = Number(customApi.value(5));
          const center = customApi.coord([0, rowIndex]);
          const rowHeight = Math.abs(customApi.size([0, 1])[1]);
          const startX = coordSys.x;
          const totalWidth = coordSys.width;
          const overallWidth = totalWidth * 0.43;
          const matrixStart = startX + totalWidth * 0.52;
          const cellGap = 8;
          const cellWidth = Math.max(24, (totalWidth * 0.43 - cellGap * 2) / 3);
          const barHeight = Math.min(26, Math.max(18, rowHeight * 0.56));
          const cellHeight = Math.min(28, Math.max(20, rowHeight * 0.64));
          const overall = numericValue(customApi.value(0));
          const cells = [numericValue(customApi.value(1)), numericValue(customApi.value(2)), numericValue(customApi.value(3))];
          const overallFill = overall == null ? 0 : Math.max(0, Math.min(1, (overall - 70) / 30));
          const children: Array<Record<string, unknown>> = [
            { type: 'rect', shape: { x: startX, y: center[1] - barHeight / 2, width: overallWidth, height: barHeight, r: 4 }, style: { fill: BI_PALETTE.track } },
            { type: 'rect', shape: { x: startX, y: center[1] - barHeight / 2, width: overallWidth * overallFill, height: barHeight, r: 4 }, style: { fill: statusFill(overall, targets[0]!) } },
            { type: 'text', style: { x: startX + overallWidth - 8, y: center[1], text: displayRate(overall), fill: overallFill > 0.25 ? '#FFFFFF' : '#5B6570', fontSize: 12, fontWeight: 600, align: 'right', verticalAlign: 'middle' } },
          ];
          cells.forEach((value, index) => {
            const x = matrixStart + index * (cellWidth + cellGap);
            const fill = statusFill(value, targets[index + 1]!);
            children.push(
              { type: 'rect', shape: { x, y: center[1] - cellHeight / 2, width: cellWidth, height: cellHeight, r: 4 }, style: { fill } },
              { type: 'text', style: { x: x + cellWidth / 2, y: center[1], text: displayRate(value), fill: value == null ? '#5B6570' : '#FFFFFF', fontSize: 12, fontWeight: 600, align: 'center', verticalAlign: 'middle' } },
            );
          });
          return { type: 'group', children } as unknown as CustomSeriesRenderItemReturn;
        },
      }],
    };
  }
}

function numericValue(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function displayRate(value: unknown): string {
  const number = numericValue(value);
  return number == null ? '--' : `${number.toFixed(2)}%`;
}

function statusFill(value: number | null, target: number): string {
  if (value == null) return '#D0D5DD';
  return value >= target ? BI_PALETTE.repairGreen : BI_PALETTE.repairOrange;
}
