export interface AdaptiveValueAxisOptions {
  minimumMax: number;
  splitNumber?: number;
}

export interface AdaptiveValueAxis {
  min: 0;
  max: number;
  splitNumber: number;
  breaks: Array<{ start: number; end: number; gap: string }>;
  breakArea?: {
    show: true;
    zigzagAmplitude: number;
    zigzagMinSpan: number;
    zigzagMaxSpan: number;
  };
}

const DEFAULT_SPLIT_NUMBER = 4;
const MAX_BREAK_COUNT = 3;
const MIN_BREAK_RATIO = 0.18;
const SIGNIFICANT_GAP_RATIO = 4;
const BREAK_INSET_RATIO = 0.1;

/**
 * 根据完整数据生成可读且不丢失事实的数值轴。
 * 只有相邻观测之间形成显著空白区间时才启用断轴；极端值仍保留在轴域、序列和 tooltip 中。
 */
export function buildAdaptiveValueAxis(
  values: readonly (number | null | undefined)[],
  options: AdaptiveValueAxisOptions,
): AdaptiveValueAxis {
  const splitNumber = options.splitNumber ?? DEFAULT_SPLIT_NUMBER;
  const usableValues = [...new Set(values.filter(isNonNegativeFinite))].sort((left, right) => left - right);
  const observedMax = usableValues.at(-1) ?? 0;
  const max = niceAxisMax(Math.max(observedMax, options.minimumMax), splitNumber);
  const breaks = buildBreaks(usableValues, observedMax);

  return {
    min: 0,
    max,
    splitNumber,
    breaks,
    ...(breaks.length
      ? {
          breakArea: {
            show: true as const,
            zigzagAmplitude: 4,
            zigzagMinSpan: 6,
            zigzagMaxSpan: 12,
          },
        }
      : {}),
  };
}

function isNonNegativeFinite(value: number | null | undefined): value is number {
  return value != null && Number.isFinite(value) && value >= 0;
}

function buildBreaks(values: number[], observedMax: number): Array<{ start: number; end: number; gap: string }> {
  if (values.length < 3 || observedMax <= 0) {
    return [];
  }

  const gaps = values.slice(1).map((value, index) => ({
    lower: values[index],
    upper: value,
    size: value - values[index],
  }));
  const span = observedMax - values[0];
  if (span <= 0) {
    return [];
  }

  const positiveGaps = gaps.map((item) => item.size).filter((gap) => gap > 0);
  const typicalGap = median(positiveGaps);
  const candidates = gaps
    .filter((item) => {
      const ratio = item.size / span;
      return ratio >= MIN_BREAK_RATIO
        && (item.size >= typicalGap * SIGNIFICANT_GAP_RATIO || ratio >= 0.5);
    })
    .sort((left, right) => right.size - left.size)
    .slice(0, MAX_BREAK_COUNT)
    .sort((left, right) => left.lower - right.lower);

  return candidates.map((item) => ({
    start: roundAxisValue(item.lower + item.size * BREAK_INSET_RATIO),
    end: roundAxisValue(item.upper - item.size * BREAK_INSET_RATIO),
    gap: '8%',
  }));
}

function median(values: number[]): number {
  if (!values.length) {
    return 0;
  }
  const middle = Math.floor(values.length / 2);
  return values.length % 2 === 0
    ? (values[middle - 1] + values[middle]) / 2
    : values[middle];
}

function niceAxisMax(value: number, splitNumber: number): number {
  if (value <= 0) {
    return 1;
  }
  const roughStep = value / splitNumber;
  const magnitude = 10 ** Math.floor(Math.log10(roughStep));
  const normalized = roughStep / magnitude;
  const step = (normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10) * magnitude;
  return roundAxisValue(Math.ceil(value / step) * step);
}

function roundAxisValue(value: number): number {
  return Number(value.toPrecision(12));
}
