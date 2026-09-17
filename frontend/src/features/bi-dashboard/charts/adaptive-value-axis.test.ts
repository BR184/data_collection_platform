import { describe, expect, it } from 'vitest';
import { buildAdaptiveValueAxis } from './adaptive-value-axis';

describe('adaptive BI value axis', () => {
  it('uses the configured minimum for empty and unusable values', () => {
    expect(buildAdaptiveValueAxis([null, undefined, Number.NaN, -1], { minimumMax: 10 }))
      .toMatchObject({ min: 0, max: 10, interval: 2, breaks: [] });
  });

  it('keeps an evenly distributed series on a continuous axis', () => {
    const axis = buildAdaptiveValueAxis([0, 10, 20, 30, 40, 50], { minimumMax: 10 });

    expect(axis.breaks).toEqual([]);
    expect(axis.max).toBeGreaterThanOrEqual(50);
    expect(axis.interval).toBe(20);
  });

  it('compresses a continuous heavy tail into a single break while keeping the body readable', () => {
    const axis = buildAdaptiveValueAxis(
      [2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 14, 16, 18, 20, 55, 78, 114, 139],
      { minimumMax: 0.8 },
    );

    // 主体只覆盖密集多数（0–40，均匀 10 一步），连续重尾整体压入单段断轴，带顶标签为真实极大值。
    expect(axis.breaks).toEqual([{ start: 40, end: 139, gap: '10%' }]);
    expect(axis.max).toBe(139);
    expect(axis.interval).toBe(10);
    expect(axis.breakArea).toMatchObject({ show: true });
  });

  it('caps the body by four times the median so sparse mid-range values cannot flatten it', () => {
    const axis = buildAdaptiveValueAxis([2, 3, 4, 5, 6, 40, 70, 966], { minimumMax: 0.8 });

    // 中位数 4×4=16 与 95 分位 70 取小者约束主体，中档稀疏值不会顶高主刻度区。
    expect(axis.breaks).toHaveLength(1);
    expect(axis.breaks[0].start).toBeLessThanOrEqual(20);
    expect(axis.breaks[0].end).toBe(966);
    expect(axis.max).toBe(966);
  });

  it('deduplicates repeated observations before evaluating the body', () => {
    const axis = buildAdaptiveValueAxis([3, 3, 3, 7, 7, 1_000], { minimumMax: 10 });

    expect(axis.breaks).toEqual([{ start: 10, end: 1_000, gap: '10%' }]);
    expect(axis.interval).toBe(2);
  });

  it('keeps a single extreme of a tiny sample behind a break', () => {
    const axis = buildAdaptiveValueAxis([3.2, 4.1, 5.3, 166.7], { minimumMax: 0.8 });

    expect(axis.breaks).toEqual([{ start: 4, end: 166.7, gap: '10%' }]);
    expect(axis.interval).toBe(1);
  });

  it('does not break when the maximum stays within the tail factor of the body', () => {
    const axis = buildAdaptiveValueAxis([0, 5, 10, 15, 20], { minimumMax: 10 });

    expect(axis.breaks).toEqual([]);
    expect(axis.max).toBe(20);
    expect(axis.interval).toBe(5);
  });

  it('preserves the minimum range when the nearest nice step would undershoot it', () => {
    const axis = buildAdaptiveValueAxis([0, 3, 6, 9, 12], { minimumMax: 12.1 });

    expect(axis.breaks).toEqual([]);
    expect(axis.max).toBeGreaterThanOrEqual(12.1);
    expect(axis.max).toBe(15);
    expect(axis.interval).toBe(5);
  });

  it('makes the break decoration non-interactive so points remain hoverable', () => {
    const axis = buildAdaptiveValueAxis([1, 2, 3, 166.7], { minimumMax: 0.8 });

    expect(axis.breakArea).toMatchObject({ show: true, expandOnClick: false });
  });
});
