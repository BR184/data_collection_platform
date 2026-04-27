import type {
  CodeReviewMultiBoardOverviewResponse,
  IntegrationTestSummaryResponse,
  ReviewDataSummaryResponse,
  StatisticBoardResponse,
  StatisticRowData,
} from '../types/api';
import { buildColumnBarOption, buildHorizontalBarOption, type NamedValue } from '../components/charts/chart-options';

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

export function computeIntegrationPassRate(summary: IntegrationTestSummaryResponse | null) {
  const rows = summary?.rows ?? [];
  const totalExecute = rows.reduce((sum, row) => sum + (row.executeCase ?? 0), 0);
  const totalPass = rows.reduce((sum, row) => sum + (row.passCase ?? 0), 0);
  if (totalExecute <= 0) {
    return null;
  }
  return (totalPass / totalExecute) * 100;
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
  demandDensity: number | null;
  designDensity: number | null;
  codeReviewCcDensity: number | null;
  codeReviewDgmDensity: number | null;
  integrationPassRate: number | null;
  systemTestOpenRate: number | null;
}): QualityBoardCard[] {
  return [
    {
      key: 'demand-density',
      label: '需求评审缺陷密度',
      value: formatFixed(input.demandDensity),
      tone: resolveBandTone(input.demandDensity, 0.2, 0.6),
    },
    {
      key: 'design-density',
      label: '设计评审缺陷密度',
      value: formatFixed(input.designDensity),
      tone: resolveBandTone(input.designDensity, 0.2, 0.6),
    },
    {
      key: 'code-review-cc',
      label: '代码走查缺陷密度(CC)',
      value: formatFixed(input.codeReviewCcDensity),
      tone: resolveBandTone(input.codeReviewCcDensity, 2, 10),
    },
    {
      key: 'code-review-dgm',
      label: '代码走查缺陷密度(DGM)',
      value: formatFixed(input.codeReviewDgmDensity),
      tone: resolveBandTone(input.codeReviewDgmDensity, 2, 10),
    },
    {
      key: 'integration-pass',
      label: '集成测试平均通过率',
      value: formatFixed(input.integrationPassRate, 2, '%'),
      tone: resolveMinTone(input.integrationPassRate, 90),
    },
    {
      key: 'system-open-rate',
      label: '系统测试未关闭占比',
      value: formatFixed(input.systemTestOpenRate, 2, '%'),
      tone: resolveMaxTone(input.systemTestOpenRate, 15),
    },
  ];
}

export function buildReviewDensityChartOption(input: {
  demandDensity: number | null;
  designDensity: number | null;
}) {
  return buildColumnBarOption({
    title: '评审密度对比',
    subtitle: '用统一量纲对比需求评审与设计评审的问题密度',
    categories: ['需求评审', '设计评审'],
    series: [
      {
        name: '缺陷密度',
        data: [input.demandDensity ?? 0, input.designDensity ?? 0],
        color: '#1677ff',
      },
    ],
  });
}

export function buildCodeReviewDensityChartOption(input: {
  ccDensity: number | null;
  dgmDensity: number | null;
}) {
  return buildColumnBarOption({
    title: '代码走查密度对比',
    subtitle: '对比 CC 与 DGM 两类代码源的总体缺陷密度',
    categories: ['CC', 'DGM'],
    series: [
      {
        name: '缺陷密度',
        data: [input.ccDensity ?? 0, input.dgmDensity ?? 0],
        color: '#36cfc9',
      },
    ],
  });
}

export function buildIntegrationPassChartOption(summary: IntegrationTestSummaryResponse | null) {
  const items: NamedValue[] = (summary?.rows ?? [])
    .map((row) => ({
      name: row.moduleName,
      value: Number(row.passRate ?? 0),
    }))
    .filter((item) => item.value > 0)
    .sort((left, right) => right.value - left.value)
    .slice(0, 8);
  return buildHorizontalBarOption({
    title: '集成测试模块通过率',
    subtitle: '优先看通过率更高的模块，也能快速发现明显落后的模块',
    items,
    color: '#52c41a',
    valueFormatter: (value) => `${value.toFixed(2)}%`,
  });
}

export function buildSystemTestRepairChartOption(board: StatisticBoardResponse | null) {
  const items: NamedValue[] = (board?.rows ?? [])
    .filter((row) => row.rowKey !== TOTAL_ROW_KEY)
    .map((row) => ({
      name: row.rowLabel,
      value: Number(cellNumber(row, 'fix_rate').toFixed(2)),
      weight: cellNumber(row, 'module_total'),
    }))
    .filter((item) => item.weight > 0)
    .sort((left, right) => right.weight - left.weight)
    .slice(0, 8)
    .map((item) => ({ name: item.name, value: item.value }));
  return buildHorizontalBarOption({
    title: '系统测试模块修复率',
    subtitle: '只看缺陷量较高的模块，减少长尾噪音',
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
    subtitle: '按模块看响应效率，优先发现长期拖慢的区域',
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
    subtitle: '把问题最多的功能组合直接拉到前台',
    items,
    color: '#1677ff',
  });
}

export function buildCodeReviewOwnerChartOption(overview: CodeReviewMultiBoardOverviewResponse | null) {
  return buildHorizontalBarOption({
    title: '代码走查责任人密度',
    subtitle: '观察责任人维度下的缺陷密度分布',
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
