import { describe, expect, it } from 'vitest';
import { buildAdaptiveValueAxis } from './adaptive-value-axis';

describe('adaptive BI value axis', () => {
  it('uses the configured minimum for empty and unusable values', () => {
    expect(buildAdaptiveValueAxis([null, undefined, Number.NaN, -1], { minimumMax: 10 }))
      .toMatchObject({ min: 0, max: 10, splitNumber: 4, breaks: [] });
  });

  it('keeps an evenly distributed series on a continuous axis', () => {
    const axis = buildAdaptiveValueAxis([0, 10, 20, 30, 40, 50], { minimumMax: 10 });

    expect(axis.max).toBeGreaterThanOrEqual(50);
    expect(axis.breaks).toEqual([]);
  });

  it('creates bounded breaks for sparse extreme values without changing the axis maximum', () => {
    const axis = buildAdaptiveValueAxis([2.52, 3.32, 4.61, 5.83, 7.31, 9.17, 18.66, 43.48, 166.7], {
      minimumMax: 10,
    });

    expect(axis.max).toBeGreaterThanOrEqual(166.7);
    expect(axis.breaks.length).toBeGreaterThan(0);
    expect(axis.breaks.every((item) => Number(item.start) < Number(item.end))).toBe(true);
    expect(axis.breaks.every((item) => Number(item.start) > 0 && Number(item.end) < axis.max)).toBe(true);
  });

  it('deduplicates repeated observations before evaluating gaps', () => {
    const axis = buildAdaptiveValueAxis([3, 3, 3, 7, 7, 1_000], { minimumMax: 10 });

    expect(axis.breaks).toHaveLength(1);
  });
});
