import type { EChartsOption, SeriesOption } from 'echarts';

export interface NamedValue {
  name: string;
  value: number;
}

const INITIAL_VISIBLE_ITEMS = 11;
const VERTICAL_GRID_RIGHT = 64;
const HORIZONTAL_GRID_BOTTOM = 68;
// Keep the shared controls at the Apache ECharts 30px default instead of compressing them.
const VERTICAL_SLIDER_WIDTH = 30;
const HORIZONTAL_SLIDER_HEIGHT = 30;
const SLIDER_EDGE_GAP = 10;

const dataZoomVisualStyle = {
  show: true,
  showDetail: false,
  borderColor: '#cbd5e1',
  borderRadius: 6,
  backgroundColor: '#f8fafc',
  fillerColor: 'rgba(37, 99, 235, 0.18)',
  dataBackground: {
    lineStyle: { color: '#cbd5e1', width: 1 },
    areaStyle: { color: '#e2e8f0', opacity: 0.58 },
  },
  selectedDataBackground: {
    lineStyle: { color: '#60a5fa', width: 1 },
    areaStyle: { color: '#bfdbfe', opacity: 0.72 },
  },
  handleSize: '110%',
  handleStyle: {
    color: '#ffffff',
    borderColor: '#2563eb',
    borderWidth: 1.5,
    shadowBlur: 3,
    shadowColor: 'rgba(37, 99, 235, 0.18)',
  },
  moveHandleSize: 12,
  moveHandleStyle: { color: 'rgba(37, 99, 235, 0.34)' },
  emphasis: {
    handleStyle: {
      color: '#ffffff',
      borderColor: '#1d4ed8',
      borderWidth: 1.5,
      shadowBlur: 3,
      shadowColor: 'rgba(37, 99, 235, 0.24)',
    },
  },
} as const;

function axisDataZoom(axis: 'x' | 'y', count: number): NonNullable<EChartsOption['dataZoom']> {
  const endValue = Math.min(INITIAL_VISIBLE_ITEMS - 1, Math.max(0, count - 1));
  const axisIndex = axis === 'x' ? { xAxisIndex: 0 } : { yAxisIndex: 0 };
  return [
    { type: 'inside', ...axisIndex, startValue: 0, endValue },
    {
      type: 'slider',
      ...axisIndex,
      startValue: 0,
      endValue,
      ...dataZoomVisualStyle,
      ...(axis === 'x'
        ? { height: HORIZONTAL_SLIDER_HEIGHT, bottom: SLIDER_EDGE_GAP }
        : { width: VERTICAL_SLIDER_WIDTH, right: SLIDER_EDGE_GAP }),
    },
  ] as NonNullable<EChartsOption['dataZoom']>;
}

function tooltipValueFormatter(formatter?: (value: number) => string) {
  if (!formatter) {
    return undefined;
  }
  return (value: unknown) => {
    const normalized = Array.isArray(value) ? Number(value[0] ?? 0) : Number(value ?? 0);
    return formatter(normalized);
  };
}

export function buildHorizontalBarOption(input: {
  title: string;
  subtitle?: string;
  items: NamedValue[];
  color?: string;
  valueFormatter?: (value: number) => string;
}): EChartsOption | null {
  if (!input.items.length) {
    return null;
  }

  return {
    title: {
      text: input.title,
      subtext: input.subtitle ?? '',
      left: 0,
      top: 0,
      itemGap: 8,
    },
    tooltip: {
      trigger: 'axis',
      axisPointer: {
        type: 'shadow',
      },
      valueFormatter: tooltipValueFormatter(input.valueFormatter),
    },
    grid: {
      top: 64,
      left: 12,
      right: VERTICAL_GRID_RIGHT,
      bottom: 8,
      containLabel: true,
    },
    xAxis: {
      type: 'value',
    },
    yAxis: {
      type: 'category',
      data: input.items.map((item) => item.name),
      axisLabel: {
        width: 120,
        overflow: 'truncate',
        color: '#4b5563',
        margin: 12,
      },
      axisLine: {
        show: false,
      },
      axisTick: {
        show: false,
      },
    },
    dataZoom: axisDataZoom('y', input.items.length),
    series: [
      {
        type: 'bar',
        data: input.items.map((item) => item.value),
        showBackground: true,
        backgroundStyle: {
          color: '#f5f7fa',
          borderRadius: 4,
        },
        barWidth: 16,
        itemStyle: {
          borderRadius: 4,
          color: input.color ?? '#5470c6',
        },
        label: {
          show: true,
          position: 'right',
          formatter: ({ value }) =>
            input.valueFormatter ? input.valueFormatter(Number(value)) : String(value ?? 0),
          color: '#4b5563',
        },
      },
    ],
  };
}

export function buildColumnBarOption(input: {
  title: string;
  subtitle?: string;
  categories: string[];
  series: Array<{
    name: string;
    data: number[];
    stack?: string;
    color?: string;
    areaStyle?: boolean;
  }>;
  rotateLabels?: number;
  valueFormatter?: (value: number) => string;
}): EChartsOption | null {
  if (!input.categories.length || !input.series.length) {
    return null;
  }

  const showLegend = input.series.length > 1;

  return {
    title: {
      text: input.title,
      subtext: input.subtitle ?? '',
      left: 0,
      top: 0,
      itemGap: 8,
    },
    tooltip: {
      trigger: 'axis',
      axisPointer: {
        type: 'shadow',
      },
      valueFormatter: tooltipValueFormatter(input.valueFormatter),
    },
    legend: showLegend
      ? {
          top: 48,
          left: 0,
        }
      : undefined,
    grid: {
      top: showLegend ? 92 : 72,
      left: 12,
      right: 20,
      bottom: HORIZONTAL_GRID_BOTTOM,
      containLabel: true,
    },
    xAxis: {
      type: 'category',
      data: input.categories,
      axisLabel: {
        rotate: input.rotateLabels ?? 0,
      },
    },
    yAxis: {
      type: 'value',
    },
    dataZoom: axisDataZoom('x', input.categories.length),
    series: input.series.map(
      (series): SeriesOption => ({
        name: series.name,
        type: 'bar',
        stack: series.stack,
        data: series.data,
        showBackground: !showLegend,
        backgroundStyle: !showLegend
          ? {
              color: '#f5f7fa',
              borderRadius: [6, 6, 0, 0],
            }
          : undefined,
        barMaxWidth: showLegend ? 32 : 24,
        barCategoryGap: showLegend ? '34%' : '42%',
        itemStyle: {
          color: series.color,
          borderRadius: [6, 6, 0, 0],
        },
        label: !showLegend
          ? {
              show: true,
              position: 'top',
              color: '#4b5563',
              formatter: ({ value }) =>
                input.valueFormatter ? input.valueFormatter(Number(value)) : String(value ?? 0),
            }
          : undefined,
      }),
    ),
  };
}

export function buildLineOption(input: {
  title: string;
  subtitle?: string;
  categories: string[];
  series: Array<{
    name: string;
    data: number[];
    color?: string;
    area?: boolean;
  }>;
  valueFormatter?: (value: number) => string;
}): EChartsOption | null {
  if (!input.categories.length || !input.series.length) {
    return null;
  }

  const showLegend = input.series.length > 1;

  return {
    title: {
      text: input.title,
      subtext: input.subtitle ?? '',
      left: 0,
      top: 0,
      itemGap: 8,
    },
    tooltip: {
      trigger: 'axis',
      valueFormatter: tooltipValueFormatter(input.valueFormatter),
    },
    legend: showLegend
      ? {
          top: 52,
          left: 0,
        }
      : undefined,
    grid: {
      top: showLegend ? 96 : 72,
      left: 12,
      right: 20,
      bottom: HORIZONTAL_GRID_BOTTOM,
      containLabel: true,
    },
    xAxis: {
      type: 'category',
      data: input.categories,
    },
    yAxis: {
      type: 'value',
    },
    dataZoom: axisDataZoom('x', input.categories.length),
    series: input.series.map(
      (series): SeriesOption => ({
        name: series.name,
        type: 'line',
        smooth: true,
        data: series.data,
        symbol: 'circle',
        symbolSize: 8,
        itemStyle: {
          color: series.color,
        },
        lineStyle: {
          width: 3,
          color: series.color,
        },
        areaStyle: series.area
          ? {
              opacity: 0.12,
              color: series.color,
            }
          : undefined,
      }),
    ),
  };
}

export function buildDonutOption(input: {
  title: string;
  subtitle?: string;
  items: NamedValue[];
  centerLabel?: string;
  valueFormatter?: (value: number) => string;
}): EChartsOption | null {
  if (!input.items.length) {
    return null;
  }

  const total = input.items.reduce((sum, item) => sum + item.value, 0);

  return {
    title: {
      text: input.title,
      subtext: input.subtitle ?? '',
      left: 0,
      top: 0,
      itemGap: 8,
    },
    tooltip: {
      trigger: 'item',
      valueFormatter: tooltipValueFormatter(input.valueFormatter),
    },
    legend: {
      type: 'scroll',
      orient: 'vertical',
      right: 0,
      top: 'middle',
    },
    series: [
      {
        type: 'pie',
        radius: ['48%', '72%'],
        center: ['38%', '56%'],
        avoidLabelOverlap: true,
        itemStyle: {
          borderRadius: 8,
          borderColor: '#fff',
          borderWidth: 2,
        },
        label: {
          show: true,
          formatter: '{b}\n{d}%',
          color: '#4b5563',
        },
        emphasis: {
          scale: true,
          scaleSize: 8,
        },
        data: input.items,
      },
    ],
    graphic: input.centerLabel
      ? [
          {
            type: 'text',
            left: '30%',
            top: '46%',
            style: {
              text: `${input.centerLabel}\n${total}`,
              align: 'center',
              fill: '#111827',
              fontSize: 15,
              fontWeight: 700,
              lineHeight: 22,
            },
          },
        ]
      : undefined,
  };
}
