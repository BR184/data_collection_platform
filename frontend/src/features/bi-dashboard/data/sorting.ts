import type {
  CategorySeriesData,
  DeveloperWorkloadRow,
  ModuleRepairRow,
  NamedValue,
  ReviewQualityRow,
  ReviewScatterPoint,
  RoundQualityRow,
} from '../charts/chart-data';
import { compareNumericNullsLast } from '../../../utils/missing-value-sorting';
import { systemTestRepairTargets } from './quality-targets';

export type BiSortOrder = 'asc' | 'desc';

export interface BiSortOption {
  label: string;
  value: string;
}

/** 对 NamedValue 列表（柱图/饼图）按数值或名称进行排序 */
export function sortNamedValues(
  items: NamedValue[],
  sortBy: string,
  order: BiSortOrder = 'desc',
): NamedValue[] {
  return items.slice().sort((a, b) => {
    if (sortBy === 'name') {
      const cmp = a.name.localeCompare(b.name, 'zh-CN');
      return order === 'asc' ? cmp : -cmp;
    }
    const cmp = (a.value ?? 0) - (b.value ?? 0);
    return order === 'asc' ? cmp : -cmp;
  });
}

/** 对模块评审质量列表进行多字段排序 */
export function sortReviewQualityRows(
  items: ReviewQualityRow[],
  sortBy: string,
  order: BiSortOrder = 'desc',
): ReviewQualityRow[] {
  return items.slice().sort((a, b) => {
    if (sortBy === 'status') {
      const cmp = Number(a.achieved ?? true) - Number(b.achieved ?? true);
      if (cmp !== 0) return cmp;
      return compareNumericNullsLast(a.density, b.density, 'desc');
    }
    if (sortBy === 'rate') {
      return compareNumericNullsLast(a.rate, b.rate, order);
    }
    if (sortBy === 'name') {
      const cmp = a.name.localeCompare(b.name, 'zh-CN');
      return order === 'asc' ? cmp : -cmp;
    }
    // 默认 density
    return compareNumericNullsLast(a.density, b.density, order);
  });
}

/** 对评审散点进行排序 */
export function sortReviewScatterPoints(
  items: ReviewScatterPoint[],
  sortBy: string,
  order: BiSortOrder = 'desc',
): ReviewScatterPoint[] {
  return items.slice().sort((a, b) => {
    if (sortBy === 'date') {
      const cmp = (a.date ?? '').localeCompare(b.date ?? '');
      return order === 'asc' ? cmp : -cmp;
    }
    if (sortBy === 'rate') {
      return compareNumericNullsLast(a.rate, b.rate, order);
    }
    if (sortBy === 'name') {
      const cmp = a.name.localeCompare(b.name, 'zh-CN');
      return order === 'asc' ? cmp : -cmp;
    }
    return compareNumericNullsLast(a.density, b.density, order);
  });
}

/** 对 CategorySeriesData（如静态代码扫描）按各系列数值或分类名排序 */
export function sortCategorySeriesData(
  data: CategorySeriesData,
  sortBy: string,
  order: BiSortOrder = 'desc',
): CategorySeriesData {
  if (!data.categories.length) return data;
  const indices = data.categories.map((_, i) => i);
  indices.sort((a, b) => {
    if (sortBy === 'name') {
      const cmp = data.categories[a]!.localeCompare(data.categories[b]!, 'zh-CN');
      return order === 'asc' ? cmp : -cmp;
    }
    if (sortBy === 'records') {
      const seriesRecords = data.series.find((s) => s.name.includes('记录')) ?? data.series[0];
      const valA = seriesRecords?.values[a] ?? 0;
      const valB = seriesRecords?.values[b] ?? 0;
      const cmp = valA - valB;
      return order === 'asc' ? cmp : -cmp;
    }
    // 默认 bugs 或总数
    const seriesBugs = data.series.find((s) => s.name.includes('问题') || s.name.includes('违规')) ?? data.series[1] ?? data.series[0];
    const valA = seriesBugs?.values[a] ?? 0;
    const valB = seriesBugs?.values[b] ?? 0;
    const cmp = valA - valB;
    return order === 'asc' ? cmp : -cmp;
  });

  return {
    categories: indices.map((i) => data.categories[i]!),
    series: data.series.map((s) => ({
      name: s.name,
      values: indices.map((i) => s.values[i] ?? 0),
    })),
  };
}

const CHINESE_NUMBER_MAP: Record<string, number> = {
  零: 0,
  一: 1,
  二: 2,
  两: 2,
  三: 3,
  四: 4,
  五: 5,
  六: 6,
  七: 7,
  八: 8,
  九: 9,
  十: 10,
};

/** 解析轮次顺序序号，优先依据后端整数 order，兜底解析中文/数字轮次名（回归测试沉底） */
export function parseRoundOrder(name: string, order?: number): number {
  if (order != null && !Number.isNaN(order)) {
    return order;
  }
  if (!name) return 99999;
  if (name.includes('回归')) {
    return 99000;
  }
  if (name.includes('首轮') || name.includes('初轮')) {
    return 1;
  }

  // 匹配形如“第一轮”、“第1轮”、“二轮”、“10轮”
  const roundMatch = name.match(/第?\s*([一二两三四五六七八九十\d]+)\s*轮/);
  if (roundMatch) {
    const raw = roundMatch[1]!;
    if (/^\d+$/.test(raw)) {
      return parseInt(raw, 10);
    }
    if (raw.length === 1 && CHINESE_NUMBER_MAP[raw] != null) {
      return CHINESE_NUMBER_MAP[raw]!;
    }
    if (raw.startsWith('十') && raw.length === 2 && CHINESE_NUMBER_MAP[raw[1]!] != null) {
      return 10 + CHINESE_NUMBER_MAP[raw[1]!]!;
    }
    if (raw.length === 2 && raw.endsWith('十') && CHINESE_NUMBER_MAP[raw[0]!] != null) {
      return CHINESE_NUMBER_MAP[raw[0]!]! * 10;
    }
    if (raw.length === 3 && raw[1] === '十' && CHINESE_NUMBER_MAP[raw[0]!] != null && CHINESE_NUMBER_MAP[raw[2]!] != null) {
      return CHINESE_NUMBER_MAP[raw[0]!]! * 10 + CHINESE_NUMBER_MAP[raw[2]!]!;
    }
  }

  // 匹配类似 Round 1, R2
  const roundEnMatch = name.match(/(?:round|r)\s*(\d+)/i);
  if (roundEnMatch) {
    return parseInt(roundEnMatch[1]!, 10);
  }

  // 末尾数字，如 测试1, Test2
  const trailingDigit = name.match(/(\d+)\s*$/);
  if (trailingDigit) {
    return parseInt(trailingDigit[1]!, 10);
  }

  return 99999;
}

/** 对轮次质量数据进行排序 */
export function sortRoundQualityRows(
  items: RoundQualityRow[],
  sortBy: string,
  order: BiSortOrder = 'asc',
): RoundQualityRow[] {
  return items.slice().sort((a, b) => {
    if (sortBy === 'submitted') {
      const cmp = a.submitted - b.submitted;
      return order === 'asc' ? cmp : -cmp;
    }
    if (sortBy === 'closed') {
      const cmp = a.closed - b.closed;
      return order === 'asc' ? cmp : -cmp;
    }
    if (sortBy === 'open') {
      const cmp = a.open - b.open;
      return order === 'asc' ? cmp : -cmp;
    }
    if (sortBy === 'closeRate') {
      return compareNumericNullsLast(a.closeRate, b.closeRate, order);
    }
    // 默认轮次自然顺序 (name/order)
    const orderA = parseRoundOrder(a.name, a.order);
    const orderB = parseRoundOrder(b.name, b.order);
    const cmp = orderA - orderB || a.name.localeCompare(b.name, 'zh-CN');
    return order === 'asc' ? cmp : -cmp;
  });
}

/**
 * 对模块系统测试修复率达成数据进行排序（卡片排序控件的唯一权威实现）。
 *
 * 口径依据：后端只为“存在缺陷事实”的模块产出该列表，且整体修复率在分母为 0 时返回 null，
 * 所以列表内 `fixRate === 0` 严格表示“有缺陷且一个都未修复”，是**最高风险**而非“无缺陷/未开始测试”。
 * 按 missing-value-sorting 的全局规则，真实 0 是有效值，不得与“无数据”坍缩同类，
 * 故这里只让 null 恒置底，0% 正常参与比较，保证“异常优先”下最危险的模块排最前。
 * 用户显式选择排序字段时，方向完全由该字段决定，不再按 fixRate 干预。
 */
export function sortModuleRepairRows(
  items: ModuleRepairRow[],
  sortBy: string,
  order: BiSortOrder = 'asc',
): ModuleRepairRow[] {
  const byName = (a: ModuleRepairRow, b: ModuleRepairRow) => a.name.localeCompare(b.name, 'zh-CN');
  // 数值比序：无数据恒置底、方向只作用于有值元素，相等时按模块名稳定收敛。
  const byNumeric = (
    a: ModuleRepairRow,
    b: ModuleRepairRow,
    value: (row: ModuleRepairRow) => number | null,
  ) => compareNumericNullsLast(value(a), value(b), order) || byName(a, b);

  return items.slice().sort((a, b) => {
    if (sortBy === 'open') {
      return byNumeric(a, b, (row) => row.openCount ?? null);
    }
    if (sortBy === 'total') {
      return byNumeric(a, b, (row) => row.totalCount ?? null);
    }
    if (sortBy === 'rate') {
      return byNumeric(a, b, (row) => row.fixRate);
    }
    if (sortBy === 'name') {
      const cmp = byName(a, b);
      return order === 'asc' ? cmp : -cmp;
    }
    // 默认 status「异常优先」：未达标组在前（不随升降序翻转），无可计算数值恒置底。
    const rateA = a.fixRate;
    const rateB = b.fixRate;
    if (rateA == null || rateB == null) {
      if (rateA == null && rateB == null) {
        return byName(a, b);
      }
      return rateA == null ? 1 : -1;
    }
    const statusCmp
      = Number(rateA >= systemTestRepairTargets.overall) - Number(rateB >= systemTestRepairTargets.overall);
    return statusCmp || byNumeric(a, b, (row) => row.fixRate);
  });
}

/** 对指派人统计缺陷数列表进行排序 */
export function sortDeveloperWorkloadRows(
  items: DeveloperWorkloadRow[],
  sortBy: string,
  order: BiSortOrder = 'desc',
): DeveloperWorkloadRow[] {
  return items.slice().sort((a, b) => {
    if (sortBy === 'total') {
      const cmp = a.total - b.total;
      return order === 'asc' ? cmp : -cmp;
    }
    if (sortBy === 'fixed') {
      const cmp = a.fixed - b.fixed;
      return order === 'asc' ? cmp : -cmp;
    }
    if (sortBy === 'rate') {
      const rateA = a.total > 0 ? a.fixed / a.total : 1;
      const rateB = b.total > 0 ? b.fixed / b.total : 1;
      const cmp = rateA - rateB;
      return order === 'asc' ? cmp : -cmp;
    }
    if (sortBy === 'name') {
      const cmp = a.name.localeCompare(b.name, 'zh-CN');
      return order === 'asc' ? cmp : -cmp;
    }
    // 默认 open 待修复降序
    const cmp = a.open - b.open;
    return order === 'asc' ? cmp : -cmp;
  });
}