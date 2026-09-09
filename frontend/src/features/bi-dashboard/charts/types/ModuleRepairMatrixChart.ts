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
    const headerColumns = [
      { text: '整体修复率\n目标95%', left: '32%' },
      { text: '遗留缺陷\n未修复数', left: '52%' },
      { text: '一级缺陷\n目标100%', left: '67%' },
      { text: 'P1优先级\n目标90%', left: '79%' },
      { text: 'P2优先级\n目标80%', left: '91%' },
    ];
    return {
      ...this.baseOption(`模块修复率达成矩阵，共 ${data.length} 个模块。`),
      legend: { show: false },
      graphic: headerColumns.map((col) => ({
        type: 'text',
        left: col.left,
        top: 8,
        silent: true,
        style: { text: col.text, fill: '#475467', fontSize: 12, fontWeight: 600, lineHeight: 16, textAlign: 'center' },
      })),
      grid: { top: 54, right: context.mode === 'view' && data.length > 10 ? 46 : 28, bottom: context.mode === 'view' && data.length > 10 ? 48 : 24, left: 118, containLabel: true },
      tooltip: {
        trigger: 'item',
        formatter: (params: unknown) => {
          const value = (params as { value?: unknown }).value;
          if (!Array.isArray(value)) return '';
          const open = value[6] ?? 0;
          const total = value[7] ?? 0;
          return `<strong>${value[4]}</strong><br/>`
            + `遗留缺陷数：<span style="color:${open > 0 ? '#EE6666' : '#91CC75'};font-weight:bold;">${open} 个</span>（累计发现 ${total} 个）<br/>`
            + `整体修复率：${displayRate(value[0])}<br/>`
            + `一级缺陷修复率：${displayRate(value[1])}，P1修复率：${displayRate(value[2])}，P2修复率：${displayRate(value[3])}`;
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
        data: data.map((item, index) => [
          item.fixRate,
          item.levelOneRate,
          item.p1Rate,
          item.p2Rate,
          item.name,
          index,
          item.openCount ?? 0,
          item.totalCount ?? 0,
        ]),
        renderItem: (params: CustomSeriesRenderItemParams, api: CustomSeriesRenderItemAPI): CustomSeriesRenderItemReturn => {
          const customApi = api as unknown as { value(dimension: number): unknown; coord(value: unknown[]): [number, number]; size(value: unknown[]): [number, number] };
          const coordSys = params.coordSys as unknown as { x: number; width: number };
          const rowIndex = Number(customApi.value(5));
          const center = customApi.coord([0, rowIndex]);
          const rowHeight = Math.abs(customApi.size([0, 1])[1]);
          const startX = coordSys.x;
          const totalWidth = coordSys.width;
          const overallWidth = totalWidth * 0.35;
          const openStartX = startX + totalWidth * 0.38;
          const openWidth = Math.max(54, totalWidth * 0.13);
          const matrixStart = startX + totalWidth * 0.54;
          const cellGap = 8;
          const cellWidth = Math.max(24, (totalWidth * 0.44 - cellGap * 2) / 3);
          const barHeight = Math.min(26, Math.max(18, rowHeight * 0.56));
          const cellHeight = Math.min(28, Math.max(20, rowHeight * 0.64));
          const overall = numericValue(customApi.value(0));
          const cells = [numericValue(customApi.value(1)), numericValue(customApi.value(2)), numericValue(customApi.value(3))];
          const overallFill = overall == null ? 0 : Math.max(0, Math.min(1, (overall - 70) / 30));
          const openCount = Number(customApi.value(6)) || 0;
          const openFill = openCount > 0 ? 'rgba(238, 102, 102, 0.16)' : 'rgba(145, 204, 117, 0.18)';
          const openTextColor = openCount > 0 ? '#993E45' : '#47724F';
          const openText = openCount > 0 ? `${openCount} 遗留` : '0 遗留';

          const children: Array<Record<string, unknown>> = [
            // 整体修复率进度条
            { type: 'rect', shape: { x: startX, y: center[1] - barHeight / 2, width: overallWidth, height: barHeight, r: 4 }, style: { fill: BI_PALETTE.track } },
            { type: 'rect', shape: { x: startX, y: center[1] - barHeight / 2, width: overallWidth * overallFill, height: barHeight, r: 4 }, style: { fill: statusFill(overall, targets[0]!) } },
            { type: 'text', style: { x: startX + overallWidth - 8, y: center[1], text: displayRate(overall), fill: overallFill > 0.25 ? '#FFFFFF' : '#5B6570', fontSize: 12, fontWeight: 600, align: 'right', verticalAlign: 'middle' } },
            // 遗留缺陷数徽章
            { type: 'rect', shape: { x: openStartX, y: center[1] - cellHeight / 2, width: openWidth, height: cellHeight, r: 12 }, style: { fill: openFill, stroke: openCount > 0 ? 'rgba(238, 102, 102, 0.3)' : 'rgba(145, 204, 117, 0.3)', lineWidth: 1 } },
            { type: 'text', style: { x: openStartX + openWidth / 2, y: center[1], text: openText, fill: openTextColor, fontSize: 12, fontWeight: 650, align: 'center', verticalAlign: 'middle' } },
          ];

          // 右侧 3 项优先级达标格子
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
