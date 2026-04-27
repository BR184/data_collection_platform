import type { EChartsOption, SeriesOption } from 'echarts';

export interface NamedValue {
  name: string;
  value: number;
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
      right: 20,
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
      },
    },
    series: [
      {
        type: 'bar',
        data: input.items.map((item) => item.value),
        barWidth: 16,
        itemStyle: {
          borderRadius: [0, 8, 8, 0],
          color: input.color ?? '#1677ff',
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

  return {
    title: {
      text: input.title,
      subtext: input.subtitle ?? '',
      left: 0,
      top: 0,
    },
    tooltip: {
      trigger: 'axis',
      axisPointer: {
        type: 'shadow',
      },
      valueFormatter: tooltipValueFormatter(input.valueFormatter),
    },
    legend: {
      top: 28,
      left: 0,
    },
    grid: {
      top: 72,
      left: 12,
      right: 20,
      bottom: 20,
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
    series: input.series.map(
      (series): SeriesOption => ({
        name: series.name,
        type: 'bar',
        stack: series.stack,
        data: series.data,
        barMaxWidth: 34,
        itemStyle: {
          color: series.color,
          borderRadius: [8, 8, 0, 0],
        },
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

  return {
    title: {
      text: input.title,
      subtext: input.subtitle ?? '',
      left: 0,
      top: 0,
    },
    tooltip: {
      trigger: 'axis',
      valueFormatter: tooltipValueFormatter(input.valueFormatter),
    },
    legend: {
      top: 28,
      left: 0,
    },
    grid: {
      top: 72,
      left: 12,
      right: 20,
      bottom: 20,
      containLabel: true,
    },
    xAxis: {
      type: 'category',
      data: input.categories,
    },
    yAxis: {
      type: 'value',
    },
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
    },
    tooltip: {
      trigger: 'item',
      valueFormatter: tooltipValueFormatter(input.valueFormatter),
    },
    legend: {
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
