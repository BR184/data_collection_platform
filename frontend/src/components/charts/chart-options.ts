import type { EChartsOption, SeriesOption } from 'echarts';

export interface NamedValue {
  name: string;
  value: number;
}

const INITIAL_VISIBLE_ITEMS = 11;
const VERTICAL_GRID_RIGHT = 64;
// Keep rotated labels, the slider and a visible lower edge separate in the SVG viewport.
const HORIZONTAL_GRID_BOTTOM = 108;
// Keep the shared controls at the Apache ECharts 30px default instead of compressing them.
const VERTICAL_SLIDER_WIDTH = 30;
const HORIZONTAL_SLIDER_HEIGHT = 30;
const COLUMN_BAR_RADIUS = 6;
// A vertical slider is rendered by rotating ECharts' horizontal control. Its end-handle
// geometry reaches 11.5px beyond the nominal 30px rail, so a 10px gap still crosses the
// SVG boundary by roughly 2px. Keep the complete handle, border and shadow inside the canvas.
const VERTICAL_SLIDER_RIGHT = 16;
const HORIZONTAL_SLIDER_BOTTOM = 28;

type ColumnBarDataItem = number | {
  value?: number | string | null;
  itemStyle?: Record<string, unknown>;
  [key: string]: unknown;
};

type CornerRadius = [number, number, number, number];

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
  // Native ECharts handle sizing fills the rail without pushing its shadow below the canvas.
  handleSize: '100%',
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
    {
      type: 'slider',
      ...axisIndex,
      startValue: 0,
      endValue,
      // The old platform has one slider as the range owner. Keep that model so a dashboard
      // refresh cannot reconcile two linked controls into an unintended all-data viewport.
      filterMode: 'filter',
      ...dataZoomVisualStyle,
      ...(axis === 'x'
        ? { height: HORIZONTAL_SLIDER_HEIGHT, bottom: HORIZONTAL_SLIDER_BOTTOM }
        : { width: VERTICAL_SLIDER_WIDTH, right: VERTICAL_SLIDER_RIGHT }),
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
        labelLayout: {
          hideOverlap: true,
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
    data: ColumnBarDataItem[];
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
  const roundedSeriesData = buildColumnBarSeriesData(input.series, input.categories.length);

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
        interval: 'auto',
        hideOverlap: true,
        width: 104,
        overflow: 'truncate',
        margin: 12,
      },
    },
    yAxis: {
      type: 'value',
    },
    dataZoom: axisDataZoom('x', input.categories.length),
    series: input.series.map(
      (series, seriesIndex): SeriesOption => ({
        name: series.name,
        type: 'bar',
        stack: series.stack,
        data: roundedSeriesData[seriesIndex],
        showBackground: !showLegend,
        backgroundStyle: !showLegend
          ? {
              color: '#f5f7fa',
              borderRadius: COLUMN_BAR_RADIUS,
            }
          : undefined,
        barMaxWidth: showLegend ? 32 : 24,
        barCategoryGap: showLegend ? '34%' : '42%',
        itemStyle: {
          color: series.color,
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
        labelLayout: {
          hideOverlap: true,
        },
      }),
    ),
  };
}

function buildColumnBarSeriesData(
  seriesList: Array<{ data: ColumnBarDataItem[]; stack?: string }>,
  categoryCount: number,
) {
  const radiusBySeries = seriesList.map(() =>
    Array.from({ length: categoryCount }, () => [0, 0, 0, 0] as CornerRadius),
  );
  const stackNames = Array.from(new Set(
    seriesList.map((series, index) => series.stack || `__single_${index}`),
  ));

  for (let categoryIndex = 0; categoryIndex < categoryCount; categoryIndex += 1) {
    for (const stackName of stackNames) {
      const stackSeriesIndexes = seriesList
        .map((series, index) => ({ series, index }))
        .filter(({ series, index }) => (series.stack || `__single_${index}`) === stackName)
        .map(({ index }) => index);
      applyVisibleStackRadius(stackSeriesIndexes, categoryIndex, seriesList, radiusBySeries);
    }
  }

  return seriesList.map((series, seriesIndex) =>
    Array.from({ length: categoryCount }, (_, categoryIndex) =>
      withColumnBarRadius(series.data[categoryIndex] ?? 0, radiusBySeries[seriesIndex][categoryIndex]),
    ),
  );
}

function applyVisibleStackRadius(
  seriesIndexes: number[],
  categoryIndex: number,
  seriesList: Array<{ data: ColumnBarDataItem[] }>,
  radiusBySeries: CornerRadius[][],
) {
  const positiveSeriesIndexes = seriesIndexes
    .filter((seriesIndex) => columnBarValue(seriesList[seriesIndex].data[categoryIndex]) > 0);
  const negativeSeriesIndexes = seriesIndexes
    .filter((seriesIndex) => columnBarValue(seriesList[seriesIndex].data[categoryIndex]) < 0);

  markStackTopRadius(positiveSeriesIndexes, radiusBySeries, categoryIndex);
  markStackTopRadius(negativeSeriesIndexes, radiusBySeries, categoryIndex);
}

function markStackTopRadius(
  visibleSeriesIndexes: number[],
  radiusBySeries: CornerRadius[][],
  categoryIndex: number,
) {
  const topSeriesIndex = visibleSeriesIndexes[visibleSeriesIndexes.length - 1];
  if (topSeriesIndex != null) {
    radiusBySeries[topSeriesIndex][categoryIndex] = withTopRadius([0, 0, 0, 0]);
  }
}

function withTopRadius(radius: CornerRadius): CornerRadius {
  return [COLUMN_BAR_RADIUS, COLUMN_BAR_RADIUS, radius[2], radius[3]];
}

function withColumnBarRadius(item: ColumnBarDataItem, borderRadius: CornerRadius) {
  if (typeof item === 'object' && item !== null && !Array.isArray(item)) {
    return {
      ...item,
      itemStyle: {
        ...(item.itemStyle ?? {}),
        borderRadius,
      },
    };
  }
  return {
    value: item,
    itemStyle: {
      borderRadius,
    },
  };
}

function columnBarValue(item: ColumnBarDataItem) {
  const rawValue = typeof item === 'object' && item !== null && !Array.isArray(item)
    ? item.value
    : item;
  const value = Number(rawValue ?? 0);
  return Number.isFinite(value) ? value : 0;
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
      axisLabel: {
        interval: 'auto',
        hideOverlap: true,
        width: 104,
        overflow: 'truncate',
        margin: 12,
      },
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
