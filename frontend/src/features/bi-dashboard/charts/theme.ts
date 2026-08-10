import { registerTheme } from './bi-echarts-runtime';
import { BI_PALETTE, BI_SERIES_COLORS } from './palette';

const BI_THEME_NAME = 'bi-dashboard-theme';
let registered = false;

/** 注册仅供 BI 看板使用的 ECharts 主题。 */
export function registerBiChartTheme(): string {
  if (!registered) {
    registerTheme(BI_THEME_NAME, {
      color: [...BI_SERIES_COLORS],
      backgroundColor: 'transparent',
      legend: { textStyle: { color: BI_PALETTE.subtleText } },
      categoryAxis: {
        axisLine: { lineStyle: { color: '#CBD5E1' } },
        axisTick: { show: false },
        axisLabel: { color: BI_PALETTE.subtleText },
      },
      valueAxis: {
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { color: BI_PALETTE.subtleText },
        splitLine: { lineStyle: { color: BI_PALETTE.grid } },
      },
    });
    registered = true;
  }
  return BI_THEME_NAME;
}

