import { describe, expect, it } from 'vitest';
import { codingDensityRange, reviewDensityRange } from './quality-targets';

describe('BI defect density target ranges', () => {
  it('returns the design specific band for the design review page', () => {
    expect(reviewDensityRange('design')).toEqual([0.3, 0.8]);
  });

  it('keeps the requirement band unchanged for the requirement review page', () => {
    expect(reviewDensityRange('requirements')).toEqual([0.2, 0.6]);
  });

  it('uses the updated manual walkthrough band for the coding page', () => {
    expect(codingDensityRange).toEqual([3, 12]);
  });
});
