import type {
  CodeReviewMultiBoardBreakdownRowResponse,
  CodeReviewMultiBoardOverviewResponse,
} from '../types/api';
import {
  buildColumnBarOption,
  buildHorizontalBarOption,
} from '../components/charts/chart-options';

export function formatPercent(value?: number | null) {
  return value == null ? '-' : `${value.toFixed(2)}%`;
}

export function formatMinutes(value?: number | null) {
  return value == null ? '-' : `${value.toFixed(2)} 分钟`;
}

export function formatLines(value?: number | null) {
  return value == null ? '-' : `${value.toFixed(2)} 行`;
}

export function completionRate(count: number, completed: number) {
  if (!count) {
    return 0;
  }
  return Number(((completed / count) * 100).toFixed(2));
}

function topRows(rows: CodeReviewMultiBoardBreakdownRowResponse[], metric: (row: CodeReviewMultiBoardBreakdownRowResponse) => number) {
  return [...rows]
    .map((row) => ({ row, value: metric(row) }))
    .filter((item) => item.value > 0)
    .sort((left, right) => right.value - left.value)
    .slice(0, 8);
}

export function buildCodeReviewSummaryCards(overview: CodeReviewMultiBoardOverviewResponse) {
  return [
    { key: 'source', label: '数据源', value: overview.sourceLabel || '-' },
    { key: 'merge-requests', label: '合并请求数', value: String(overview.mergeRequestCount) },
    { key: 'completed', label: '已完成走查', value: String(overview.completedCount) },
    { key: 'pending', label: '待处理', value: String(overview.pendingCount) },
    { key: 'density', label: '缺陷密度', value: overview.defectDensityPerKloc == null ? '-' : overview.defectDensityPerKloc.toFixed(2) },
    { key: 'duration', label: '平均走查时长', value: formatMinutes(overview.averageReviewDurationMinutes) },
  ];
}

export function buildModuleDensityChartOption(overview: CodeReviewMultiBoardOverviewResponse | null) {
  const rows = topRows(overview?.moduleRows ?? [], (row) => row.defectDensityPerKloc ?? 0);
  return buildHorizontalBarOption({
    title: '模块缺陷密度 Top 8',
    subtitle: '更适合快速判断当前代码走查最值得优先追踪的模块',
    items: rows.map((item) => ({ name: item.row.rowLabel, value: item.value })),
    color: '#1677ff',
    valueFormatter: (value) => `${value.toFixed(2)}`,
  });
}

export function buildModuleVolumeChartOption(overview: CodeReviewMultiBoardOverviewResponse | null) {
  const rows = topRows(overview?.moduleRows ?? [], (row) => row.mergeRequestCount);
  return buildColumnBarOption({
    title: '模块走查体量',
    subtitle: '按合并请求数量看本次数据源里的主要代码走查负载',
    categories: rows.map((item) => item.row.rowLabel),
    series: [
      {
        name: '合并请求数',
        data: rows.map((item) => item.row.mergeRequestCount),
        color: '#36cfc9',
      },
    ],
    rotateLabels: 18,
  });
}

export function buildOwnerDensityChartOption(overview: CodeReviewMultiBoardOverviewResponse | null) {
  const rows = topRows(overview?.ownerRows ?? [], (row) => row.defectDensityPerKloc ?? 0);
  return buildHorizontalBarOption({
    title: '责任人缺陷密度 Top 8',
    subtitle: '方便识别责任人维度上的质量集中风险',
    items: rows.map((item) => ({ name: item.row.rowLabel, value: item.value })),
    color: '#ff9f29',
    valueFormatter: (value) => `${value.toFixed(2)}`,
  });
}

export function buildOwnerCompletionChartOption(overview: CodeReviewMultiBoardOverviewResponse | null) {
  const rows = topRows(overview?.ownerRows ?? [], (row) => row.mergeRequestCount);
  return buildColumnBarOption({
    title: '责任人完成率',
    subtitle: '兼顾处理量与完成率，避免只看数量不看进度',
    categories: rows.map((item) => item.row.rowLabel),
    series: [
      {
        name: '完成率',
        data: rows.map((item) => completionRate(item.row.mergeRequestCount, item.row.completedCount)),
        color: '#7a5af8',
      },
    ],
    rotateLabels: 18,
    valueFormatter: (value) => `${value.toFixed(2)}%`,
  });
}
