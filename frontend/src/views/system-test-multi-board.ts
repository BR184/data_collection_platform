import type { StatisticBoardResponse, StatisticRowData } from '../types/api';
import {
  buildColumnBarOption,
  buildDonutOption,
  buildHorizontalBarOption,
  type NamedValue,
} from '../components/charts/chart-options';

const TOTAL_ROW_KEY = '__total__';

const CAUSE_METRICS = [
  { key: 'demand_misunderstand', label: '新增理解偏差' },
  { key: 'missing_requirement', label: '需求遗漏' },
  { key: 'add_demand_2', label: '新增需求' },
  { key: 'demand_change_not_sync', label: '需求变更未同步' },
  { key: 'design_forget', label: '功能设计遗漏' },
  { key: 'design_scheme', label: '设计方案不合理' },
  { key: 'incomplete', label: '场景考虑不全' },
  { key: 'prompt_message', label: '术语、提示信息不合适' },
  { key: 'standard_error', label: '编码规范错误' },
  { key: 'function_forget', label: '功能编码遗漏' },
  { key: 'logic_calculation_algorithm_error', label: '编码逻辑：计算与算法错误' },
  { key: 'logic_flow_control_error', label: '编码逻辑：流程控制错误' },
  { key: 'logic_data_state_process_error', label: '编码逻辑：数据与状态处理错误' },
  { key: 'logic_business_logic_error', label: '编码逻辑：业务逻辑错误' },
  { key: 'logic_integration_interface_error', label: '编码逻辑：集成与接口错误' },
  { key: 'environment_config_issue', label: '环境配置问题' },
  { key: 'compilation_package_deployment_issue', label: '编译/打包/部署问题' },
  { key: 'other_thirdParty', label: '第三方库问题' },
  { key: 'algorithm_not_support', label: '算法不支持' },
  { key: 'mechanism_not_support', label: '机制不支持' },
  { key: 'precondition_data_exception', label: '前置数据异常' },
  { key: 'other_unIdentifyTask', label: '未识别的前后置任务' },
  { key: 'precision_constraint_exception', label: '精度导致约束求解异常' },
  { key: 'precision_algorithm_exception', label: '精度导致算法执行异常' },
] as const;

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

function cellPercentNumber(row: StatisticRowData | null | undefined, key: string) {
  const cell = cellMap(row).get(key);
  const displayValue = cell?.displayValue?.trim() ?? '';
  if (displayValue.includes('%')) {
    const parsed = Number(displayValue.replace('%', '').trim());
    if (Number.isFinite(parsed)) {
      return parsed;
    }
  }
  return Number.isFinite(cell?.numericValue) ? Number(cell?.numericValue) : 0;
}

function detailParamNumber(row: StatisticRowData | null | undefined, key: string, paramKey: string) {
  const raw = cellMap(row).get(key)?.detailParams?.[paramKey];
  const parsed = Number(raw ?? 0);
  return Number.isFinite(parsed) ? parsed : 0;
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
    subtitle: '按测试轮次展示一二三级缺陷数量',
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
    subtitle: '按模块展示缺陷数量',
    items: rows.map((item) => ({ name: item.row.rowLabel, value: item.value })),
    color: '#1677ff',
  });
}

export function buildRepairRateChartOption(summaryBoard: StatisticBoardResponse | null) {
  const rows = topRows(summaryBoard, 'module_total', 8);
  return buildHorizontalBarOption({
    title: '模块修复率 Top 8',
    subtitle: '按模块展示缺陷修复率',
    items: rows.map((item) => ({
      name: item.row.rowLabel,
      value: Number(cellPercentNumber(item.row, 'fix_rate').toFixed(2)),
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

  const rows = CAUSE_METRICS
    .map((metric) => {
      const level1 = detailParamNumber(summary, metric.key, 'level1');
      const level2 = detailParamNumber(summary, metric.key, 'level2');
      const level3 = detailParamNumber(summary, metric.key, 'level3');
      const suggestion = detailParamNumber(summary, metric.key, 'suggestion');
      return {
        ...metric,
        level1,
        level2,
        level3,
        suggestion,
        total: level1 + level2 + level3 + suggestion,
      };
    })
    .filter((item) => item.total > 0)
    .sort((left, right) => {
      const byTotal = right.total - left.total;
      return byTotal === 0 ? right.level1 - left.level1 : byTotal;
    });

  return buildColumnBarOption({
    title: '缺陷原因分析',
    subtitle: '按缺陷原因拆分一级、二级、三级和建议类数量',
    categories: rows.map((row) => row.label),
    series: [
      { name: '一级缺陷(个)', data: rows.map((row) => row.level1), stack: 'cause', color: '#1677ff' },
      { name: '二级缺陷(个)', data: rows.map((row) => row.level2), stack: 'cause', color: '#36cfc9' },
      { name: '三级缺陷(个)', data: rows.map((row) => row.level3), stack: 'cause', color: '#ff9f29' },
      { name: '建议(个)', data: rows.map((row) => row.suggestion), stack: 'cause', color: '#8c8c8c' },
    ],
    rotateLabels: 45,
  });
}

export function buildDelayCauseChartOption(delayBoard: StatisticBoardResponse | null) {
  const rows = topRows(delayBoard, 'total', 8);
  return buildHorizontalBarOption({
    title: '延期原因分布',
    subtitle: '按延期原因展示系统测试缺陷数量',
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
