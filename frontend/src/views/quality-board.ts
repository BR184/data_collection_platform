import type {
  CodeReviewMultiBoardOverviewResponse,
  QualityBoardChartRowResponse,
  QualityBoardFixUserSeverityRowResponse,
  QualityBoardRdOverviewResponse,
  ReviewDataSummaryResponse,
  StatisticBoardResponse,
  StatisticRowData,
} from '../types/api';
import type { EChartsOption } from 'echarts';
import {
  buildColumnBarOption,
  buildHorizontalBarOption,
  buildLineOption,
  type NamedValue,
} from '../components/charts/chart-options';

export interface QualityBoardCard {
  key: string;
  label: string;
  value: string;
  hint?: string;
  tone?: 'default' | 'success' | 'warning' | 'danger';
}

const TOTAL_ROW_KEY = '__total__';

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

function totalRow(board: StatisticBoardResponse | null) {
  return board?.rows.find((row) => row.rowKey === TOTAL_ROW_KEY) ?? null;
}

export function formatFixed(value: number | null | undefined, digits = 2, suffix = '') {
  if (value == null || !Number.isFinite(value)) {
    return '-';
  }
  return `${value.toFixed(digits)}${suffix}`;
}

export function computeReviewDensity(summary: ReviewDataSummaryResponse | null) {
  if (!summary || summary.totalRecords <= 0 || summary.averageReviewScalePages <= 0) {
    return null;
  }
  const totalPages = summary.totalRecords * summary.averageReviewScalePages;
  if (totalPages <= 0) {
    return null;
  }
  return summary.totalProblemItems / totalPages;
}

export function computeSystemTestOpenRate(board: StatisticBoardResponse | null) {
  const summary = totalRow(board);
  const total = cellNumber(summary, 'module_total');
  const open = cellNumber(summary, 'open_count');
  if (total <= 0) {
    return null;
  }
  return (open / total) * 100;
}

export function buildQualityBoardCards(input: {
  overview: QualityBoardRdOverviewResponse | null;
}): QualityBoardCard[] {
  const overview = input.overview;
  return [
    {
      key: 'demand-density',
      label: '需求评审缺陷密度',
      value: formatFixed(overview?.demandReviewReportDensity),
      tone: resolveBandTone(overview?.demandReviewReportDensity ?? null, 0.2, 0.6),
    },
    {
      key: 'design-density',
      label: '设计评审缺陷密度',
      value: formatFixed(overview?.designReviewReportDensity),
      tone: resolveBandTone(overview?.designReviewReportDensity ?? null, 0.2, 0.6),
    },
    {
      key: 'code-review-cc',
      label: 'CC代码走查缺陷密度',
      value: `${formatFixed(overview?.codeWalkThroughDefectDensityCc)} KLOC`,
      tone: resolveBandTone(overview?.codeWalkThroughDefectDensityCc ?? null, 2, 10),
    },
    {
      key: 'code-review-dgm',
      label: 'DGM代码走查缺陷密度',
      value: `${formatFixed(overview?.codeWalkThroughDefectDensityDgm)} KLOC`,
      tone: resolveBandTone(overview?.codeWalkThroughDefectDensityDgm ?? null, 2, 10),
    },
    {
      key: 'integration-pass-rate',
      label: '集成测试通过率',
      value: formatFixed(overview?.integrationPassRate, 2, '%'),
      tone: resolveMinTone(overview?.integrationPassRate ?? null, 90),
    },
    {
      key: 'defect-leakage-rate',
      label: '发布缺陷遗留率',
      value: formatFixed(overview?.defectLeakageRate, 2, '%'),
      tone: resolveMaxTone(overview?.defectLeakageRate ?? null, 15),
    },
    {
      key: 'defect-elimination-rate',
      label: '开发缺陷遗留率',
      value: formatFixed(overview?.defectEliminationRate, 2, '%'),
      tone: resolveMinTone(overview?.defectEliminationRate ?? null, 90),
    },
    {
      key: 'new-issue-fix-rate',
      label: '新发缺陷修复率',
      value: formatFixed(overview?.newIssueFixRate, 2, '%'),
      tone: resolveMinTone(overview?.newIssueFixRate ?? null, 90),
    },
  ];
}

export function buildReviewDensityChartOption(input: {
  demandDensity: number | null;
  designDensity: number | null;
}) {
  return buildColumnBarOption({
    title: '评审密度对比',
    subtitle: '需求评审与设计评审缺陷密度',
    categories: ['需求评审', '设计评审'],
    series: [
      {
        name: '缺陷密度',
        data: [roundChartValue(input.demandDensity), roundChartValue(input.designDensity)],
        color: '#1677ff',
      },
    ],
    valueFormatter: (value) => value.toFixed(2),
  });
}

export function buildCodeReviewDensityChartOption(input: {
  ccDensity: number | null;
  dgmDensity: number | null;
}) {
  return buildColumnBarOption({
    title: '代码走查密度对比',
    subtitle: 'CC 与 DGM 代码走查缺陷密度',
    categories: ['CC', 'DGM'],
    series: [
      {
        name: '缺陷密度',
        data: [roundChartValue(input.ccDensity), roundChartValue(input.dgmDensity)],
        color: '#36cfc9',
      },
    ],
    valueFormatter: (value) => value.toFixed(2),
  });
}

export function buildQualityRateChartOption(overview: QualityBoardRdOverviewResponse | null) {
  return buildColumnBarOption({
    title: '测试与缺陷闭环指标',
    subtitle: '系统测试与集成测试指标',
    categories: ['集成测试通过率', '发布缺陷遗留率', '开发缺陷遗留率', '新发缺陷修复率'],
    series: [
      {
        name: '比例',
        data: [
          roundChartValue(overview?.integrationPassRate),
          roundChartValue(overview?.defectLeakageRate),
          roundChartValue(overview?.defectEliminationRate),
          roundChartValue(overview?.newIssueFixRate),
        ],
        color: '#ff9f29',
      },
    ],
    rotateLabels: 18,
    valueFormatter: (value) => `${value.toFixed(2)}%`,
  });
}

export function buildSystemTestRepairChartOption(board: StatisticBoardResponse | null) {
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

export function buildCustomerResponseChartOption(board: StatisticBoardResponse | null) {
  const items: NamedValue[] = (board?.rows ?? [])
    .filter((row) => row.rowKey !== TOTAL_ROW_KEY)
    .map((row) => ({
      name: row.rowLabel,
      value: Number(cellNumber(row, 'response_rate').toFixed(2)),
    }))
    .filter((item) => item.value > 0)
    .sort((left, right) => right.value - left.value)
    .slice(0, 8);
  return buildHorizontalBarOption({
    title: '客户问题响应率',
    subtitle: '按模块展示客户问题响应率',
    items,
    color: '#7a5af8',
    valueFormatter: (value) => `${value.toFixed(2)}%`,
  });
}

export function buildCustomerFunctionChartOption(board: StatisticBoardResponse | null) {
  const items: NamedValue[] = (board?.rows ?? [])
    .filter((row) => row.rowKey !== TOTAL_ROW_KEY)
    .map((row) => ({
      name: row.rowLabel,
      value: cellNumber(row, 'total'),
    }))
    .filter((item) => item.value > 0)
    .sort((left, right) => right.value - left.value)
    .slice(0, 8);
  return buildHorizontalBarOption({
    title: '客户问题功能缺陷 Top 8',
    subtitle: '按功能展示客户问题缺陷数量',
    items,
    color: '#1677ff',
  });
}

export function buildCodeReviewOwnerChartOption(overview: CodeReviewMultiBoardOverviewResponse | null) {
  return buildHorizontalBarOption({
    title: '代码走查责任人密度',
    subtitle: '按责任人展示代码走查缺陷密度',
    items: (overview?.ownerRows ?? [])
      .map((row) => ({
        name: row.rowLabel,
        value: Number((row.defectDensityPerKloc ?? 0).toFixed(2)),
      }))
      .filter((item) => item.value > 0)
      .slice(0, 8),
    color: '#36cfc9',
    valueFormatter: (value) => formatFixed(value) ?? '-',
  });
}

export function buildQualityBoardValueRowsChartOption(input: {
  title: string;
  subtitle?: string;
  rows: QualityBoardChartRowResponse[] | null | undefined;
  color?: string;
  suffix?: string;
  includeZero?: boolean;
}) {
  const items = (input.rows ?? [])
    .map((row) => ({
      name: row.name,
      value: Number((row.value ?? 0).toFixed(2)),
    }))
    .filter((item) => input.includeZero ? item.value >= 0 : item.value > 0)
    .slice(0, 12);
  const option = buildHorizontalBarOption({
    title: input.title,
    subtitle: input.subtitle,
    items,
    color: input.color,
    valueFormatter: (value) => `${value.toFixed(2)}${input.suffix ?? ''}`,
  });
  return stripEmbeddedChartTitle(option, 12);
}

export function buildQualityBoardTrendRowsChartOption(input: {
  title: string;
  subtitle?: string;
  rows: QualityBoardChartRowResponse[] | null | undefined;
  color: string;
  suffix?: string;
}) {
  const rows = input.rows ?? [];
  const option = buildLineOption({
    title: input.title,
    subtitle: input.subtitle,
    categories: rows.map((row) => row.name),
    series: [
      {
        name: input.title,
        data: rows.map((row) => Number((row.value ?? 0).toFixed(2))),
        color: input.color,
        area: true,
      },
    ],
    valueFormatter: (value) => `${value.toFixed(2)}${input.suffix ?? ''}`,
  });
  return stripEmbeddedChartTitle(option, 12);
}

export function buildFixUserSeverityChartOption(
  rows: QualityBoardFixUserSeverityRowResponse[] | null | undefined,
): EChartsOption | null {
  const visibleRows = (rows ?? []).filter((row) => row.total > 0).slice(0, 12);
  if (!visibleRows.length) {
    return null;
  }
  const categories = visibleRows.map((row) => row.name);
  return {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      valueFormatter: (value) => `${Number(value ?? 0).toFixed(2)}`,
    },
    legend: {
      top: 0,
      left: 0,
    },
    grid: {
      top: 44,
      left: 12,
      right: 20,
      bottom: 20,
      containLabel: true,
    },
    xAxis: { type: 'value' },
    yAxis: {
      type: 'category',
      data: categories,
      axisLabel: {
        width: 120,
        overflow: 'truncate',
        color: '#4b5563',
        margin: 12,
      },
      axisLine: { show: false },
      axisTick: { show: false },
    },
    series: [
      {
        name: '一级缺陷',
        type: 'bar',
        stack: 'severity',
        data: visibleRows.map((row) => row.level1),
        itemStyle: { color: '#f56c6c' },
      },
      {
        name: '二级缺陷',
        type: 'bar',
        stack: 'severity',
        data: visibleRows.map((row) => row.level2),
        itemStyle: { color: '#e6a23c' },
      },
      {
        name: '三级缺陷',
        type: 'bar',
        stack: 'severity',
        data: visibleRows.map((row) => row.level3),
        itemStyle: { color: '#409eff' },
      },
      {
        name: '建议类',
        type: 'bar',
        stack: 'severity',
        data: visibleRows.map((row) => row.suggestion),
        itemStyle: { color: '#909399' },
      },
    ],
  };
}

function resolveBandTone(value: number | null, min: number, max: number) {
  if (value == null) {
    return 'default';
  }
  return value >= min && value <= max ? 'success' : 'warning';
}

function resolveMinTone(value: number | null, min: number) {
  if (value == null) {
    return 'default';
  }
  return value >= min ? 'success' : 'warning';
}

function resolveMaxTone(value: number | null, max: number) {
  if (value == null) {
    return 'default';
  }
  return value <= max ? 'success' : 'danger';
}

function roundChartValue(value: number | null | undefined) {
  if (value == null || !Number.isFinite(value)) {
    return 0;
  }
  return Number(value.toFixed(2));
}

function stripEmbeddedChartTitle(option: EChartsOption | null, gridTop: number): EChartsOption | null {
  if (!option) {
    return null;
  }
  const grid = Array.isArray(option.grid) ? option.grid : { ...(option.grid as Record<string, unknown> | undefined) };
  return {
    ...option,
    title: undefined,
    grid: Array.isArray(option.grid)
      ? option.grid.map((item) => ({ ...(item as Record<string, unknown>), top: gridTop }))
      : {
          ...grid,
          top: gridTop,
        },
  };
}
