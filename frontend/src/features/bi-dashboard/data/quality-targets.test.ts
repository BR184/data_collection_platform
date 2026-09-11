import { describe, expect, it } from 'vitest';
import { codingDensityRange, reviewDensityRange, systemTestRepairTargets } from './quality-targets';

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

describe('BI system test module repair target lines', () => {
  // 表头文案、达标着色与「异常优先」分组共用这一份定义，任何一处改动都必须同步核对表口径。
  it('pins the four repair rate target lines used by the matrix chart and the sorting rule', () => {
    expect(systemTestRepairTargets).toEqual({ overall: 95, levelOne: 100, p1: 90, p2: 80 });
  });
});
