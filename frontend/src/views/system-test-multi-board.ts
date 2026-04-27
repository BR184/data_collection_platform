import type { StatisticBoardResponse, StatisticCellData, StatisticRowData } from '../types/api';
import {
  buildColumnBarOption,
  buildDonutOption,
  buildHorizontalBarOption,
  type NamedValue,
} from '../components/charts/chart-options';

const TOTAL_ROW_KEY = '__total__';

export interface SystemTestBoardSummaryCard {
  key: string;
  label: string;
  value: string;
  tone?: 'default' | 'success' | 'warning' | 'danger';
}

function cellMap(row?: StatisticRowData | null) {
  return new Map((row?.cells ?? []).map((cell) => [cell.columnKey, cell] as const));
}

function cellNumber(row: StatisticRowData | null | undefined, key: string) {
  const value = cellMap(row).get(key)?.numericValue;
  return Number.isFinite(value) ? Number(value) : 0;
}

function totalRow(board: StatisticBoardResponse | null) {
  return board?.rows.find((row) => row.rowKey === TOTAL_ROW_KEY) ?? null;
}

function topRows(board: StatisticBoardResponse | null, metricKey: string, limit = 8) {
  return (board?.rows ?? [])
    .filter((row) => row.rowKey !== TOTAL_ROW_KEY)
    .map((row) => ({ row, value: cellNumber(row, metricKey) }))
    .filter((item) => item.value > 0)
    .sort((left, right) => right.value - left.value)
    .slice(0, limit);
}

function formatCount(value: number) {
  return String(Math.round(value));
}

export function buildSystemTestSummaryCards(summaryBoard: StatisticBoardResponse | null): SystemTestBoardSummaryCard[] {
  const summary = totalRow(summaryBoard);
  if (!summary) {
    return [
      { key: 'defects', label: '系统测试缺陷', value: '0' },
      { key: 'open', label: '未关闭缺陷', value: '0' },
      { key: 'fixed', label: '已修复/未更新', value: '0' },
      { key: 'delay', label: '申请延期', value: '0' },
    ];
  }

  return [
    { key: 'defects', label: '系统测试缺陷', value: formatCount(cellNumber(summary, 'module_total')) },
    { key: 'open', label: '未关闭缺陷', value: formatCount(cellNumber(summary, 'open_count')), tone: 'warning' },
    { key: 'fixed', label: '已修复/未更新', value: formatCount(cellNumber(summary, 'solved_count')), tone: 'success' },
    { key: 'delay', label: '申请延期', value: formatCount(cellNumber(summary, 'extension_count')), tone: 'danger' },
  ];
}

export function buildSeverityChartOption(summaryBoard: StatisticBoardResponse | null) {
  const summary = totalRow(summaryBoard);
  if (!summary) {
    return null;
  }

  const items: NamedValue[] = [
    { name: '一级缺陷', value: cellNumber(summary, 'level1_total') },
    { name: '二级缺陷', value: cellNumber(summary, 'level2_total') },
    { name: '三级缺陷', value: cellNumber(summary, 'level3_total') },
    { name: '建议类', value: cellNumber(summary, 'suggestion_total') },
  ].filter((item) => item.value > 0);

  return buildDonutOption({
    title: '缺陷严重程度分布',
    subtitle: '聚合当前系统测试范围内的严重程度结构',
    items,
    centerLabel: '缺陷总量',
  });
}

export function buildPhaseChartOption(phaseBoard: StatisticBoardResponse | null) {
  const rows = (phaseBoard?.rows ?? []).filter((row) => row.rowKey !== TOTAL_ROW_KEY);
  if (!rows.length) {
    return null;
  }

  return buildColumnBarOption({
    title: '缺陷阶段分布',
    subtitle: '按测试轮次观察一二三级缺陷数量变化',
    categories: rows.map((row) => row.rowLabel),
    series: [
      { name: '一级缺陷', data: rows.map((row) => cellNumber(row, 'level1')), stack: 'severity', color: '#1677ff' },
      { name: '二级缺陷', data: rows.map((row) => cellNumber(row, 'level2')), stack: 'severity', color: '#36cfc9' },
      { name: '三级缺陷', data: rows.map((row) => cellNumber(row, 'level3')), stack: 'severity', color: '#ff9f29' },
      { name: '建议类', data: rows.map((row) => cellNumber(row, 'suggestion')), stack: 'severity', color: '#8c8c8c' },
    ],
    rotateLabels: 20,
  });
}

export function buildModuleChartOption(summaryBoard: StatisticBoardResponse | null) {
  const rows = topRows(summaryBoard, 'module_total', 8);
  return buildHorizontalBarOption({
    title: '模块缺陷 Top 8',
    subtitle: '按模块快速定位当前缺陷压力最高的区域',
    items: rows.map((item) => ({ name: item.row.rowLabel, value: item.value })),
    color: '#1677ff',
  });
}

export function buildRepairRateChartOption(summaryBoard: StatisticBoardResponse | null) {
  const rows = topRows(summaryBoard, 'module_total', 8);
  return buildHorizontalBarOption({
    title: '模块修复率 Top 8',
    subtitle: '仅展示当前缺陷量最高的模块，便于对比修复进度',
    items: rows.map((item) => ({
      name: item.row.rowLabel,
      value: Number(cellNumber(item.row, 'fix_rate').toFixed(2)),
    })),
    color: '#36cfc9',
    valueFormatter: (value) => `${value.toFixed(2)}%`,
  });
}

export function buildCauseChartOption(causeBoard: StatisticBoardResponse | null) {
  const summary = totalRow(causeBoard);
  if (!summary) {
    return null;
  }

  const items: NamedValue[] = [
    { name: '需求理解偏差', value: cellNumber(summary, 'requirement_understanding') },
    { name: '新增需求', value: cellNumber(summary, 'new_requirement') },
    { name: '编码逻辑错误', value: cellNumber(summary, 'implementation_logic') },
    { name: '环境部署问题', value: cellNumber(summary, 'environment_deployment') },
    { name: '算法机制不支持', value: cellNumber(summary, 'algorithm_mechanism') },
    { name: '其他原因', value: cellNumber(summary, 'other_reason') },
  ].filter((item) => item.value > 0);

  return buildDonutOption({
    title: '缺陷原因占比',
    subtitle: '按原因大类观察当前问题主要来源',
    items,
    centerLabel: '原因总量',
  });
}

export function buildDelayCauseChartOption(delayBoard: StatisticBoardResponse | null) {
  const rows = topRows(delayBoard, 'total', 8);
  return buildHorizontalBarOption({
    title: '延期原因分布',
    subtitle: '帮助判断当前系统测试延期的主要阻塞因素',
    items: rows.map((item) => ({ name: item.row.rowLabel, value: item.value })),
    color: '#ff9f29',
  });
}

export function buildDetailLinkMap() {
  return {
    summaryPath: '/question-metrics/home',
    phasePath: '/question-metrics/phase-statistics',
    causePath: '/question-metrics/defect-cause',
    delayPath: '/question-metrics/delay-analysis',
  };
}

export function buildProjectFilter(projectName: string) {
  if (!projectName) {
    return undefined;
  }
  return { projectName };
}
