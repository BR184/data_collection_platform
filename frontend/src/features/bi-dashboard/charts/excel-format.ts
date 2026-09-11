/**
 * 各图表类 `excelTable()` 复用的单元格格式化助手。
 *
 * 口径原则：Excel 数据表必须忠实呈现图表所展示的数值。BI 的比率类字段
 * （修复率、通过率、关闭率、注释率、占比等）在页面数据中已是百分数量纲（0-100），
 * 与 `formatPercent` 及各图表坐标轴 `{value}%` 一致，因此原样输出、不再乘 100；
 * 仅"由计数现算的占比/比率"才做 value/total*100。
 */

/** 比率类百分数字段：空值保留不可计算语义为 `--`，否则四舍五入到指定小数位。 */
export function excelPercent(value: number | null | undefined, digits = 2): string | number {
  return value == null ? '--' : Number(value.toFixed(digits));
}

/** 由计数现算占比（%）：分母非正时返回 0，避免除零产生 NaN/Infinity。 */
export function excelSharePercent(value: number, total: number, digits = 2): number {
  return total > 0 ? Number(((value / total) * 100).toFixed(digits)) : 0;
}

/** 达标状态文案：未判定 / 已达标 / 未达标。 */
export function excelAchieved(achieved: boolean | null | undefined): string {
  if (achieved == null) return '未判定';
  return achieved ? '已达标' : '未达标';
}
