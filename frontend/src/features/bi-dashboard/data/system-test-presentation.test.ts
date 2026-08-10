import { describe, expect, it } from 'vitest';
import { buildDefectCauseBreakdownData, buildDelayHeatmapData } from './system-test-presentation';

describe('system test chart presentation', () => {
  it('keeps the confirmed 24 cause subclasses and exposes the unclassified share separately', () => {
    const data = buildDefectCauseBreakdownData([
      {
        categoryId: 'design',
        categoryName: '设计问题',
        subcategoryId: 'design_scheme',
        subcategoryName: '设计方案不合理',
        count: 14,
        sharePercent: 0.44,
      },
      {
        categoryId: 'unclassified',
        categoryName: '未归类',
        subcategoryId: 'unclassified',
        subcategoryName: '未归类',
        count: 3_175,
        sharePercent: 99.28,
      },
    ]);

    expect(data.items).toHaveLength(24);
    expect(data.items.map((item) => item.groupName)).toEqual([
      ...Array<string>(4).fill('需求问题'),
      ...Array<string>(4).fill('设计问题'),
      ...Array<string>(7).fill('编码问题'),
      ...Array<string>(2).fill('打包问题'),
      ...Array<string>(5).fill('依赖问题'),
      ...Array<string>(2).fill('精度问题'),
    ]);
    expect(data.items.find((item) => item.id === 'design_scheme')).toMatchObject({ count: 14, sharePercent: 0.44 });
    expect(data.items.find((item) => item.id === 'missing_requirement')).toMatchObject({ count: 0, sharePercent: 0 });
    expect(data).toMatchObject({ unclassifiedCount: 3_175, unclassifiedSharePercent: 99.28 });
  });

  it('renders absent delay reason and severity combinations as explicit zero cells', () => {
    const data = buildDelayHeatmapData([
      { reason: '数据异常', severity: 'LEVEL1', count: 12 },
      { reason: '数据异常', severity: 'LEVEL2', count: 39 },
      { reason: '技术卡点', severity: 'LEVEL3', count: 26 },
    ]);

    expect(data.severities).toEqual(['一级缺陷', '二级缺陷', '三级缺陷']);
    expect(data.reasons).toEqual(['数据异常', '技术卡点']);
    expect(data.values).toEqual([
      [0, 0, 12], [1, 0, 39], [2, 0, 0],
      [0, 1, 0], [1, 1, 0], [2, 1, 26],
    ]);
  });
});
