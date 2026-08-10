import type { EChartsOption } from 'echarts';
import { BiChart, type BiChartRenderContext, type BiChartRenderMode } from '../BiChart';
import { BI_PALETTE, BI_SERIES_COLORS } from '../palette';
import type { NamedValue } from '../chart-data';

export const PIE_MINOR_SHARE_THRESHOLD = 1;

const PIE_PAGE_OUTER_RADIUS = 112;
const PIE_EXPORT_OUTER_RADIUS = 196;
const PIE_PAGE_LABEL_SAFE_SPACE = 96;
const PIE_EXPORT_LABEL_SAFE_SPACE = 188;
const PIE_PAGE_LABEL_GAP = 9;
const PIE_EXPORT_LABEL_GAP = 14;
const PIE_FONT_FAMILY = '"Microsoft YaHei UI", "Microsoft YaHei", "Segoe UI", sans-serif';

export interface PreparedDistributionDonutData {
  total: number;
  displayData: NamedValue[];
  minorItems: NamedValue[];
  noteText: string;
}

interface DistributionDonutLayout {
  centerX: number;
  centerY: number;
  innerRadius: number;
  outerRadius: number;
  noteHeight: number;
  labelFontSize: number;
  labelLineLength: number;
  labelLineLength2: number;
  labelWidth: number;
  valueFontSize: number;
  captionFontSize: number;
}

export class DistributionDonutChart extends BiChart<NamedValue[]> {
  readonly templateId = 'distribution-donut' as const;

  hasData(data: NamedValue[]): boolean {
    return data.some((item) => item.value > 0);
  }

  build(data: NamedValue[], context: BiChartRenderContext): EChartsOption {
    // 阶段一：先把占比过小的类别合并为“其他问题”，并保留明细说明文本。
    // 这一步发生在图表配置之前，保证页面展示和导出图片使用同一份口径。
    const prepared = prepareDistributionDonutData(data);
    const width = context.width ?? 520;
    const height = context.height ?? 356;
    const layout = getDistributionDonutLayout(prepared, context.mode, width, height);
    const displayData = prepared.displayData.map((item, index) => ({
      name: item.name,
      value: item.value,
      itemStyle: { color: item.color ?? BI_SERIES_COLORS[index % BI_SERIES_COLORS.length] },
    }));
    // 阶段二：中心指标使用独立 graphic 定位，避免饼环标签布局改变中心数字的位置。
    const centerTextStyle = {
      text: `{value|${prepared.total}}\n{caption|问题数}`,
      textAlign: 'center' as const,
      textVerticalAlign: 'middle' as const,
      rich: {
        value: {
          fill: '#101828',
          fontFamily: PIE_FONT_FAMILY,
          fontSize: layout.valueFontSize,
          fontWeight: 700,
          lineHeight: layout.valueFontSize + 7,
        },
        caption: {
          fill: '#667085',
          fontFamily: PIE_FONT_FAMILY,
          fontSize: layout.captionFontSize,
          fontWeight: 500,
          lineHeight: layout.captionFontSize + 7,
        },
      },
    };
    const graphics = [
      {
        id: 'donut-center-content',
        type: 'text' as const,
        x: layout.centerX,
        y: layout.centerY,
        silent: true,
        z: 10,
        style: centerTextStyle,
      },
      ...(prepared.noteText ? [{
        id: 'donut-minor-detail',
        type: 'text' as const,
        x: context.mode === 'export' ? 36 : 12,
        y: height - layout.noteHeight + (context.mode === 'export' ? 12 : 8),
        silent: true,
        style: {
          text: prepared.noteText,
          width: Math.max(1, width - (context.mode === 'export' ? 72 : 24)),
          overflow: 'break' as const,
          fill: '#475467',
          font: `500 ${context.mode === 'export' ? 16 : 12}px ${PIE_FONT_FAMILY}`,
          lineHeight: context.mode === 'export' ? 24 : 18,
          textAlign: 'left' as const,
          textVerticalAlign: 'top' as const,
        },
      }] : []),
    ];
    // 阶段三：根据页面/导出模式选择尺寸和标签间距，保持静态展示的层次但适应容器宽度。
    return {
      ...this.baseOption(`类别构成图，共 ${displayData.length} 个类别。`),
      tooltip: {
        trigger: 'item',
        formatter: (params: unknown) => {
          const item = params as { name?: string; value?: number };
          return `${item.name ?? ''}<br/>数量：${item.value ?? 0}（${percentage(item.value ?? 0, prepared.total)}）`;
        },
      },
      legend: { show: false },
      graphic: graphics,
      series: [{
        name: '数量',
        type: 'pie',
        radius: [layout.innerRadius, layout.outerRadius],
        center: [layout.centerX, layout.centerY],
        startAngle: 55,
        avoidLabelOverlap: true,
        itemStyle: { borderColor: '#FFFFFF', borderWidth: 2, borderRadius: 3 },
        label: {
          show: true,
          alignTo: 'none',
          bleedMargin: 4,
          distanceToLabelLine: context.mode === 'export' ? PIE_EXPORT_LABEL_GAP : PIE_PAGE_LABEL_GAP,
          width: layout.labelWidth,
          overflow: 'break',
          formatter: (params: unknown) => {
            const item = params as { name?: string; value?: number };
            return `${item.name ?? ''}\n占比 ${percentage(item.value ?? 0, prepared.total)}`;
          },
          color: '#344054',
          fontFamily: PIE_FONT_FAMILY,
          fontSize: layout.labelFontSize,
          lineHeight: layout.labelFontSize + 5,
        },
        labelLine: {
          show: true,
          length: layout.labelLineLength,
          length2: layout.labelLineLength2,
          minTurnAngle: 70,
          maxSurfaceAngle: 80,
          lineStyle: { width: 1.1, color: '#98A2B3' },
        },
        labelLayout: { moveOverlap: 'shiftY', hideOverlap: false },
        emphasis: { scaleSize: 4 },
        data: displayData,
      }],
    };
  }
}

function getDistributionDonutLayout(
  prepared: PreparedDistributionDonutData,
  mode: BiChartRenderMode,
  width: number,
  height: number,
): DistributionDonutLayout {
  const safeWidth = Math.max(240, width);
  const safeHeight = Math.max(160, height);
  const narrow = safeWidth < 420;
  const noteHeight = prepared.noteText ? (mode === 'export' ? 58 : narrow ? 48 : 36) : 0;
  const chartHeight = Math.max(160, safeHeight - noteHeight);
  const targetOuterRadius = mode === 'export' ? PIE_EXPORT_OUTER_RADIUS : PIE_PAGE_OUTER_RADIUS;
  const labelSafeSpace = mode === 'export' ? PIE_EXPORT_LABEL_SAFE_SPACE : PIE_PAGE_LABEL_SAFE_SPACE;
  const maxRadiusByWidth = Math.max(54, (safeWidth - labelSafeSpace * 2) / 2);
  const maxRadiusByHeight = Math.max(54, (chartHeight - (mode === 'export' ? 72 : 48)) / 2);
  const outerRadius = Math.min(targetOuterRadius, maxRadiusByWidth, maxRadiusByHeight);
  const ringWidth = mode === 'export' ? outerRadius * 0.38 : Math.min(42, Math.max(28, outerRadius * 0.3));
  const centerY = Math.min(chartHeight / 2, chartHeight - outerRadius - (mode === 'export' ? 24 : 12));
  const valueFontSize = mode === 'export' ? 48 : outerRadius >= 104 ? 36 : 30;
  const captionFontSize = mode === 'export' ? 18 : outerRadius >= 104 ? 14 : 13;

  return {
    centerX: safeWidth / 2,
    centerY,
    innerRadius: outerRadius - ringWidth,
    outerRadius,
    noteHeight,
    labelFontSize: mode === 'export' ? 17 : narrow ? 11 : 13,
    labelLineLength: mode === 'export' ? 24 : narrow ? 16 : 14,
    labelLineLength2: mode === 'export' ? 20 : narrow ? 14 : 12,
    labelWidth: mode === 'export' ? 142 : narrow ? 80 : 104,
    valueFontSize,
    captionFontSize,
  };
}

export function prepareDistributionDonutData(data: NamedValue[]): PreparedDistributionDonutData {
  // 只对正数参与占比计算；零值类别不占用环形图空间，也不产生误导性的 0% 标签。
  const positiveData = data.filter((item) => item.value > 0);
  const total = positiveData.reduce((sum, item) => sum + item.value, 0);
  const minorItems = positiveData.filter((item) => total > 0 && item.value / total * 100 < PIE_MINOR_SHARE_THRESHOLD);
  if (minorItems.length === 0) {
    return { total, displayData: positiveData, minorItems: [], noteText: '' };
  }

  // 合并低于 1% 的类别，并按原始位置插回“其他问题”，使图例/颜色顺序稳定。
  const minorIndexes = new Set(minorItems.map((item) => positiveData.indexOf(item)));
  const firstMinorIndex = Math.min(...minorIndexes);
  const otherValue = minorItems.reduce((sum, item) => sum + item.value, 0);
  const displayData = positiveData.filter((_item, index) => !minorIndexes.has(index));
  displayData.splice(Math.min(firstMinorIndex, displayData.length), 0, { name: '其他问题', value: otherValue, color: BI_PALETTE.slate });
  const noteText = `其他问题包含：${minorItems.map((item) => `${item.name} ${percentage(item.value, total)}`).join('、')}`;
  return { total, displayData, minorItems, noteText };
}

function percentage(value: number, total: number): string {
  return total <= 0 ? '0%' : `${(value / total * 100).toFixed(1).replace(/\.0$/, '')}%`;
}
