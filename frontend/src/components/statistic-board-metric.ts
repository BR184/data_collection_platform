/**
 * 统计看板列类型契约：哪些列携带可比较的数值排序键。
 *
 * 后端 `StatisticCellData.numericValue` 只对计数、比率和时长列下发真实排序键；文本等列的
 * `numericValue` 只是占位 0。排序与列宽都必须依据同一契约判断，禁止再用
 * `metricType.includes('ratio')` 这类子串匹配——它依赖 “duration 恰好包含 ratio” 的巧合，
 * 一旦列类型命名调整就会静默改变表格排序和列宽。
 */
const NUMERIC_SORT_METRIC_TYPES = new Set(['count', 'ratio', 'duration']);

/** 判断列是否携带数值排序键（numericValue），可用于数值比较与紧凑列宽预算。 */
export function hasNumericSortKey(metricType: string): boolean {
  return NUMERIC_SORT_METRIC_TYPES.has(metricType);
}
