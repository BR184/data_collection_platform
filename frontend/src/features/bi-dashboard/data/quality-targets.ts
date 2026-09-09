import type { BiPageKey } from './types';

/**
 * BI 前端展示用评审缺陷密度目标区间（个/页）。
 * 与后端 `BiReviewCalculator` 的人工确认口径一致：需求页与设计页目标不同，按页面键区分。
 */
export function reviewDensityRange(pageKey: BiPageKey): readonly [number, number] {
  return pageKey === 'design' ? [0.3, 0.8] : [0.2, 0.6];
}

/**
 * BI 编码页人工走查缺陷密度目标区间（个/KLOC）。
 * 与后端 `BiCodingCalculator` 的人工确认口径一致。
 */
export const codingDensityRange: readonly [number, number] = [3, 12];
