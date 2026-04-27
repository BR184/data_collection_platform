import * as echarts from 'echarts';

const THEME_NAME = 'qa-flex-theme';

let registered = false;

export function registerChartTheme() {
  if (registered) {
    return THEME_NAME;
  }

  echarts.registerTheme(THEME_NAME, {
    color: ['#1677ff', '#36cfc9', '#ff9f29', '#ff6b72', '#7a5af8', '#52c41a', '#8c8c8c'],
    backgroundColor: 'transparent',
    textStyle: {
      fontFamily:
        'Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
    },
    title: {
      textStyle: {
        color: '#111827',
        fontWeight: 700,
      },
      subtextStyle: {
        color: '#6b7280',
      },
    },
    legend: {
      textStyle: {
        color: '#4b5563',
      },
    },
    tooltip: {
      backgroundColor: 'rgba(17, 24, 39, 0.92)',
      borderWidth: 0,
      textStyle: {
        color: '#f9fafb',
      },
    },
    categoryAxis: {
      axisLine: {
        lineStyle: {
          color: '#d0d5dd',
        },
      },
      axisTick: {
        show: false,
      },
      axisLabel: {
        color: '#4b5563',
      },
      splitLine: {
        show: false,
      },
    },
    valueAxis: {
      axisLine: {
        show: false,
      },
      axisTick: {
        show: false,
      },
      axisLabel: {
        color: '#6b7280',
      },
      splitLine: {
        lineStyle: {
          color: '#eef2f7',
        },
      },
    },
    grid: {
      containLabel: true,
      top: 24,
      left: 16,
      right: 20,
      bottom: 16,
    },
  });

  registered = true;
  return THEME_NAME;
}
