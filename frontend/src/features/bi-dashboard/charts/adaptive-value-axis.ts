export interface AdaptiveValueAxisOptions {
  minimumMax: number;
  splitNumber?: number;
}

export interface AdaptiveValueAxis {
  min: 0;
  max: number;
  interval: number;
  breaks: Array<{ start: number; end: number; gap: string }>;
  breakArea?: {
    show: true;
    zigzagAmplitude: number;
    zigzagMinSpan: number;
    zigzagMaxSpan: number;
    expandOnClick: false;
  };
}

const DEFAULT_SPLIT_NUMBER = 4;
// 主体上界候选：95 分位（大样本稳健，小样本自然退化为次大值）。
const BODY_QUANTILE = 0.95;
// 主体上界不得超过中位数的该倍数，防止稀疏中档离群值顶高主刻度区。
const BODY_MEDIAN_MULTIPLE = 4;
// 观测极大值超过主体上界该倍数时才启用尾部断轴，避免无意义的窄带。
const TAIL_BREAK_FACTOR = 1.25;
const TAIL_BREAK_GAP = '10%';

/**
 * 为重尾数据生成直观且不丢失事实的数值轴。
 *
 * 主体区取「min(95 分位, 4×中位数) 且不低于 minimumMax」做 nice 取整，保证密集多数占满量程、
 * 刻度均匀可读；观测极大值显著超出主体（>1.25×）时，整个尾部压缩进单段原生断轴锯齿带，
 * 带内极值点仍绘制、带顶标签即真实极大值，悬停与 Excel/PNG 导出保留真值。
 * 无尾部时退化为连续均匀轴（interval = max / splitNumber）。
 */
export function buildAdaptiveValueAxis(
  values: readonly (number | null | undefined)[],
  options: AdaptiveValueAxisOptions,
): AdaptiveValueAxis {
  const splitNumber = options.splitNumber ?? DEFAULT_SPLIT_NUMBER;
  const usableValues = [...new Set(values.filter(isNonNegativeFinite))].sort((left, right) => left - right);
  const observedMax = usableValues.at(-1) ?? 0;
  const bodyTarget = Math.max(
    options.minimumMax,
    Math.min(quantileFloor(usableValues, BODY_QUANTILE), median(usableValues) * BODY_MEDIAN_MULTIPLE),
  );
  // 最近 nice 步长可能向下取整（例如 12.1 / 4≈3.025 取到 3），
  // 主体区至少要覆盖 minimumMax 的 nice 上界，否则无极值也会被误判为断轴。
  const interval = nearestNiceStep(bodyTarget / splitNumber);
  const bodyMax = roundAxisValue(Math.max(
    interval * splitNumber,
    niceAxisMax(options.minimumMax, splitNumber),
  ));
  const hasTailBreak = observedMax > bodyMax * TAIL_BREAK_FACTOR;
  const max = hasTailBreak ? observedMax : niceAxisMax(Math.max(observedMax, options.minimumMax), splitNumber);
  const ticksInterval = hasTailBreak ? interval : nearestNiceStep(max / splitNumber);
  const breaks = hasTailBreak ? [{ start: bodyMax, end: observedMax, gap: TAIL_BREAK_GAP }] : [];

  return {
    min: 0,
    max,
    interval: ticksInterval,
    breaks,
    ...(breaks.length
      ? {
          breakArea: {
            show: true as const,
            zigzagAmplitude: 4,
            zigzagMinSpan: 6,
            zigzagMaxSpan: 12,
            // 断轴仅作视觉提示；原生断轴组必须静默，才能让其下方散点继续接收悬浮事件。
            expandOnClick: false,
          },
        }
      : {}),
  };
}

function isNonNegativeFinite(value: number | null | undefined): value is number {
  return value != null && Number.isFinite(value) && value >= 0;
}

/** 不下插的分位数：取排序后 floor(q*(n-1)) 位置的真实观测，避免极值污染主体上界。 */
function quantileFloor(sortedValues: number[], quantile: number): number {
  if (!sortedValues.length) {
    return 0;
  }
  return sortedValues[Math.floor(quantile * (sortedValues.length - 1))];
}

function median(sortedValues: number[]): number {
  if (!sortedValues.length) {
    return 0;
  }
  const middle = Math.floor(sortedValues.length / 2);
  return sortedValues.length % 2 === 0
    ? (sortedValues[middle - 1] + sortedValues[middle]) / 2
    : sortedValues[middle];
}

function niceStep(roughStep: number): number {
  if (roughStep <= 0) {
    return 1;
  }
  const magnitude = 10 ** Math.floor(Math.log10(roughStep));
  const normalized = roughStep / magnitude;
  return (normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10) * magnitude;
}

/** 取与期望步长对数距离最近的 nice 步长（1/2/5/10 梯），避免向上取整把主体区撑大到近两倍。 */
function nearestNiceStep(roughStep: number): number {
  if (roughStep <= 0) {
    return 1;
  }
  const magnitude = 10 ** Math.floor(Math.log10(roughStep));
  const normalized = roughStep / magnitude;
  let best = 1;
  let bestDistance = Number.POSITIVE_INFINITY;
  for (const candidate of [1, 2, 5, 10]) {
    const distance = Math.abs(Math.log(normalized / candidate));
    if (distance < bestDistance) {
      bestDistance = distance;
      best = candidate;
    }
  }
  return roundAxisValue(best * magnitude);
}

function niceAxisMax(value: number, splitNumber: number): number {
  if (value <= 0) {
    return 1;
  }
  const step = niceStep(value / splitNumber);
  return roundAxisValue(Math.ceil(value / step) * step);
}

function roundAxisValue(value: number): number {
  return Number(value.toPrecision(12));
}
