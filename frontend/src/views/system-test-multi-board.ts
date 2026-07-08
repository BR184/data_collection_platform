import type { EChartsOption } from 'echarts';
import type { StatisticBoardResponse, StatisticRowData, SystemTestIssueMultiBoardChartResponse } from '../types/api';
import { buildColumnBarOption, buildDonutOption, buildHorizontalBarOption, type NamedValue } from '../components/charts/chart-options';

const SEVERITY_COLORS = ['#3b82f6', '#14b8a6', '#f59e0b', '#8b95a1'];
const TOTAL_ROW_KEY = '__total__';

export function buildMultiBoardChartOption(chart: SystemTestIssueMultiBoardChartResponse): EChartsOption | null {
  if (chart.chartType === 'pie') {
    return removeInnerTitle(buildDonutOption({
      title: chart.title,
      subtitle: chart.description,
      items: chart.points,
      centerLabel: '总计',
      valueFormatter: valueFormatter(chart),
    }), chart);
  }
  if (chart.chartType === 'bar') {
    const series = chart.series[0];
    return removeInnerTitle(buildHorizontalBarOption({
      title: chart.title,
      subtitle: chart.description,
      items: chart.categories.map((name, index) => ({ name, value: Number(series?.data[index] ?? 0) })),
      color: '#14b8a6',
      valueFormatter: valueFormatter(chart),
    }), chart);
  }
  return removeInnerTitle(buildColumnBarOption({
    title: chart.title,
    subtitle: chart.description,
    categories: chart.categories,
    series: chart.series.map((series, index) => ({
      name: series.name,
      data: series.data.map((value) => Number(value ?? 0)),
      stack: 'severity',
      color: SEVERITY_COLORS[index % SEVERITY_COLORS.length],
    })),
    rotateLabels: chart.categories.length > 6 ? 24 : 0,
    valueFormatter: valueFormatter(chart),
  }), chart);
}

export function isPercentChart(chart: SystemTestIssueMultiBoardChartResponse) {
  return chart.key === 'module-repair-rate';
}

export function buildRepairRateChartOption(board: StatisticBoardResponse | null) {
  const items: NamedValue[] = (board?.rows ?? [])
    .filter((row) => row.rowKey !== TOTAL_ROW_KEY)
    .map((row) => ({
      name: row.rowLabel,
      value: Number(cellPercentNumber(row, 'fix_rate').toFixed(2)),
      weight: cellNumber(row, 'module_total'),
    }))
    .filter((item) => item.weight > 0)
    .sort((left, right) => right.weight - left.weight)
    .slice(0, 8)
    .map((item) => ({ name: item.name, value: item.value }));
  return buildHorizontalBarOption({
    title: '系统测试模块修复率',
    subtitle: '按模块展示系统测试修复率',
    items,
    color: '#ff9f29',
    valueFormatter: (value) => `${value.toFixed(2)}%`,
  });
}

function valueFormatter(chart: SystemTestIssueMultiBoardChartResponse) {
  return (value: number) => {
    if (isPercentChart(chart)) {
      return `${Number(value ?? 0).toFixed(2)}%`;
    }
    return Number(value ?? 0).toFixed(2);
  };
}

function cellNumber(row: StatisticRowData | null | undefined, key: string) {
  const cell = (row?.cells ?? []).find((item) => item.columnKey === key);
  return Number.isFinite(cell?.numericValue) ? Number(cell?.numericValue) : 0;
}

function cellPercentNumber(row: StatisticRowData | null | undefined, key: string) {
  const cell = (row?.cells ?? []).find((item) => item.columnKey === key);
  const displayValue = cell?.displayValue?.trim() ?? '';
  if (displayValue.includes('%')) {
    const parsed = Number(displayValue.replace('%', '').trim());
    if (Number.isFinite(parsed)) {
      return parsed;
    }
  }
  return Number.isFinite(cell?.numericValue) ? Number(cell?.numericValue) : 0;
}

function removeInnerTitle(option: EChartsOption | null, chart: SystemTestIssueMultiBoardChartResponse) {
  if (!option) {
    return null;
  }
  const next = { ...option } as EChartsOption & {
    title?: unknown;
    grid?: Record<string, unknown>;
    legend?: Record<string, unknown>;
  };
  delete next.title;

  if (chart.chartType === 'bar') {
    next.grid = { ...(next.grid ?? {}), top: 16 };
  } else if (chart.chartType === 'stackedBar') {
    next.legend = { ...(next.legend ?? {}), top: 0 };
    next.grid = { ...(next.grid ?? {}), top: 44 };
  }
  return next;
}
