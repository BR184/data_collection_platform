import { describe, expect, it } from 'vitest';
import type { ModuleRepairRow, RoundQualityRow } from '../charts/chart-data';
import { parseRoundOrder, sortModuleRepairRows, sortRoundQualityRows } from './sorting';

describe('sorting.ts', () => {
  describe('parseRoundOrder', () => {
    it('prefers explicit order number when provided', () => {
      expect(parseRoundOrder('第三轮', 1)).toBe(1);
      expect(parseRoundOrder('第一轮', 3)).toBe(3);
    });

    it('parses Chinese round names into correct numerical sequence', () => {
      expect(parseRoundOrder('第一轮系统测试')).toBe(1);
      expect(parseRoundOrder('第二轮系统测试')).toBe(2);
      expect(parseRoundOrder('第三轮系统测试')).toBe(3);
      expect(parseRoundOrder('第四轮系统测试')).toBe(4);
      expect(parseRoundOrder('第五轮系统测试')).toBe(5);
      expect(parseRoundOrder('第六轮系统测试')).toBe(6);
    });

    it('places regression test after regular rounds', () => {
      expect(parseRoundOrder('CC2026R3回归测试')).toBe(99000);
      expect(parseRoundOrder('第一轮系统测试')).toBeLessThan(parseRoundOrder('CC2026R3回归测试'));
      expect(parseRoundOrder('第六轮系统测试')).toBeLessThan(parseRoundOrder('CC2026R3回归测试'));
    });

    it('parses numeric digits in round name when Chinese numbers are absent', () => {
      expect(parseRoundOrder('Round 1 Test')).toBe(1);
      expect(parseRoundOrder('R2-Testing')).toBe(2);
    });
  });

  describe('sortRoundQualityRows', () => {
    const rawRounds: RoundQualityRow[] = [
      { name: 'CC2026R3第二轮系统测试', submitted: 14, closed: 13, open: 1, closeRate: 92.86, levelOne: 0, levelTwo: 0, levelThree: 0 },
      { name: 'CC2026R3回归测试', submitted: 4, closed: 4, open: 0, closeRate: 100.0, levelOne: 0, levelTwo: 0, levelThree: 0 },
      { name: 'CC2026R3第一轮系统测试', submitted: 13, closed: 12, open: 1, closeRate: 92.31, levelOne: 0, levelTwo: 0, levelThree: 0 },
      { name: 'CC2026R3第三轮系统测试', submitted: 4, closed: 4, open: 0, closeRate: 100.0, levelOne: 0, levelTwo: 0, levelThree: 0 },
    ];

    it('sorts rounds in natural ascending order (一, 二, 三, 回归) rather than pinyin order', () => {
      const sorted = sortRoundQualityRows(rawRounds, 'name', 'asc');
      expect(sorted.map((r) => r.name)).toEqual([
        'CC2026R3第一轮系统测试',
        'CC2026R3第二轮系统测试',
        'CC2026R3第三轮系统测试',
        'CC2026R3回归测试',
      ]);
    });

    it('supports sorting by submitted defects descending', () => {
      const sorted = sortRoundQualityRows(rawRounds, 'submitted', 'desc');
      expect(sorted[0]?.name).toBe('CC2026R3第二轮系统测试');
      expect(sorted[0]?.submitted).toBe(14);
    });
  });

  describe('sortModuleRepairRows', () => {
    // 后端只为“存在缺陷事实”的模块产出该列表（按 issues 分组，totalCount 必 >= 1），
    // 故 fixRate === 0 的真实含义是“有缺陷且一个都未修复”，属最高风险而非“无缺陷/未开始”。
    const modules: ModuleRepairRow[] = [
      { name: '模块A (全部未修复)', fixRate: 0, openCount: 50, totalCount: 50, levelOneRate: 0, p1Rate: 0, p2Rate: 0 },
      { name: '模块B (80%未达标)', fixRate: 80, openCount: 2, totalCount: 10, levelOneRate: 100, p1Rate: 80, p2Rate: 75 },
      { name: '模块C (98%已达标)', fixRate: 98, openCount: 1, totalCount: 50, levelOneRate: 100, p1Rate: 100, p2Rate: 95 },
      { name: '模块D (修复率不可计算)', fixRate: null, openCount: 3, totalCount: 3, levelOneRate: null, p1Rate: null, p2Rate: null },
      { name: '模块E (90%未达标)', fixRate: 90, openCount: 1, totalCount: 10, levelOneRate: 100, p1Rate: 90, p2Rate: 85 },
    ];

    it('把“有缺陷且全部未修复”的 0% 模块按最高风险排在异常优先最前', () => {
      const names = sortModuleRepairRows(modules, 'status', 'asc').map((m) => m.name);

      expect(names).toEqual([
        '模块A (全部未修复)', // 0% 且有 50 个未修复＝最危险，必须最先看到
        '模块B (80%未达标)',
        '模块E (90%未达标)',
        '模块C (98%已达标)',
        '模块D (修复率不可计算)', // 仅真正无可计算数值才恒置底
      ]);
    });

    // “异常优先”是分组而不是排序方向：降序只把组内按修复率从高到低重排，
    // 不得把已达标模块翻到未达标模块之前（否则该维度会变成普通的“整体修复率降序”）。
    it('异常优先的分组不随降序翻转，方向只作用于组内比较', () => {
      const names = sortModuleRepairRows(modules, 'status', 'desc').map((m) => m.name);

      expect(names).toEqual([
        '模块E (90%未达标)', // 未达标组仍在前，组内按修复率降序
        '模块B (80%未达标)',
        '模块A (全部未修复)',
        '模块C (98%已达标)',
        '模块D (修复率不可计算)',
      ]);
    });

    it('按未修复数降序时不再被 0% 沉底规则劫持', () => {
      const names = sortModuleRepairRows(modules, 'open', 'desc').map((m) => m.name);

      // 该维度完全由 openCount 决定：50 > 3 > 2 > 1(名称升序兜底)
      expect(names).toEqual([
        '模块A (全部未修复)',
        '模块D (修复率不可计算)',
        '模块B (80%未达标)',
        '模块C (98%已达标)',
        '模块E (90%未达标)',
      ]);
    });

    it('按整体修复率降序时真实 0% 参与比较、仅不可计算置底', () => {
      const names = sortModuleRepairRows(modules, 'rate', 'desc').map((m) => m.name);

      expect(names).toEqual([
        '模块C (98%已达标)',
        '模块E (90%未达标)',
        '模块B (80%未达标)',
        '模块A (全部未修复)', // 真实 0 是有效值，按降序排在有值元素末尾
        '模块D (修复率不可计算)',
      ]);
    });

    it('按名称排序时完全不受 fixRate 影响', () => {
      const names = sortModuleRepairRows(modules, 'name', 'asc').map((m) => m.name);

      expect(names).toEqual([
        '模块A (全部未修复)',
        '模块B (80%未达标)',
        '模块C (98%已达标)',
        '模块D (修复率不可计算)',
        '模块E (90%未达标)',
      ]);
    });
  });
});
