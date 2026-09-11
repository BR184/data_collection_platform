/**
 * 排序时“无数据恒置底”的单一规则，供统计看板表格与 BI 看板表格/图表共用。
 *
 * 业务口径：单元格没有可比较的数据（比率分母为 0 显示 `/`、外部平台未提供计数、时间无法解析、
 * 文本为空）时，该值不参与升序/降序的大小比较，一律排在末尾；真实 `0` 是有效值，必须与
 * “无数据”区分，否则两者比较恒等、只能靠原始行序稳定兜底，表现为升降序下交错乱排。
 *
 * 禁止在各比较器里用 `?? 0`、`?? -1`、`?? 101` 之类哨兵把“无数据”坍缩成某个数字：那会让
 * 无数据随排序方向翻到顶部，或与真实 0 交错。新增数值/文本排序列一律复用本模块。
 *
 * 比较结果为 0 时（两侧同为无数据，或取值相同）由调用方以稳定次序兜底。
 */

/**
 * 数值（含时间戳等已归一为数字的键）比较：无数据恒排最后，方向只作用于有值元素。
 *
 * @param left 左值；null/undefined/NaN 视为无数据。
 * @param right 右值；null/undefined/NaN 视为无数据。
 * @param direction 'asc' 升序、'desc' 降序，仅影响有值元素的相对次序。
 * @returns 负数表示 left 在前，正数表示 left 在后，0 表示相等或同为无数据。
 */
export function compareNumericNullsLast(
  left: number | null | undefined,
  right: number | null | undefined,
  direction: 'asc' | 'desc',
): number {
  const leftMissing = left == null || !Number.isFinite(left);
  const rightMissing = right == null || !Number.isFinite(right);
  if (leftMissing || rightMissing) {
    return compareMissing(leftMissing, rightMissing);
  }
  return applyDirection((left as number) - (right as number), direction);
}

/**
 * 文本比较：空串与平台无数据占位符 `/` 视为无数据恒排最后，其余按既有区域设置次序比较。
 *
 * @param left 左值文本；null/undefined/空白/`/` 视为无数据。
 * @param right 右值文本；null/undefined/空白/`/` 视为无数据。
 * @param direction 'asc' 升序、'desc' 降序，仅影响有值元素的相对次序。
 * @returns 负数表示 left 在前，正数表示 left 在后，0 表示相等或同为无数据。
 */
export function compareTextNullsLast(
  left: string | null | undefined,
  right: string | null | undefined,
  direction: 'asc' | 'desc',
): number {
  const leftMissing = isBlankDisplayValue(left);
  const rightMissing = isBlankDisplayValue(right);
  if (leftMissing || rightMissing) {
    return compareMissing(leftMissing, rightMissing);
  }
  const cmp = (left as string).localeCompare(right as string);
  return applyDirection(cmp, direction);
}

/** 应用排序方向；相等时返回 +0，避免比较器产出 -0 让调用方的零判定变得依赖符号。 */
function applyDirection(cmp: number, direction: 'asc' | 'desc'): number {
  if (cmp === 0) {
    return 0;
  }
  return direction === 'asc' ? cmp : -cmp;
}

function isBlankDisplayValue(value: string | null | undefined): boolean {
  const text = value?.trim() ?? '';
  return text === '' || text === '/';
}

function compareMissing(leftMissing: boolean, rightMissing: boolean): number {
  if (leftMissing && rightMissing) {
    return 0;
  }
  return leftMissing ? 1 : -1;
}
