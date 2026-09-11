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

/**
 * BI 系统测试页「模块修复率达成矩阵」的四条达标线（百分数，0-100）。
 *
 * 唯一来源：卡片表头文案、达标着色与「异常优先」分组都从这里取，禁止在页面或图表内重复写字面量。
 *
 * - `levelOne` / `p1` / `p2` 与后端 `BiSystemTestCalculator` 的
 *   `LEVEL_ONE_TARGET` / `P1_TARGET` / `P2_TARGET` 一致（口径核对表 ST-13、ST-25、ST-31）。
 * - `overall`（整体修复率达标线）后端不产出该目标，口径核对表当前亦无对应编号，
 *   属待人工确认并登记的口径；如需调整必须先确认再同步核对表。
 */
export const systemTestRepairTargets = { overall: 95, levelOne: 100, p1: 90, p2: 80 } as const;
