import { describe, expect, it } from 'vitest';
import { buildColumnBarOption, buildHorizontalBarOption, buildLineOption } from './chart-options';

describe('shared ECharts options', () => {
  it('keeps the legacy first-eleven-item viewport and exposes inside/slider controls', () => {
    const option = buildHorizontalBarOption({
      title: '按人员统计',
      items: Array.from({ length: 15 }, (_, index) => ({ name: `人员${index}`, value: index })),
    });

    expect(option?.dataZoom).toEqual(expect.arrayContaining([
      expect.objectContaining({ type: 'inside', yAxisIndex: 0, startValue: 0, endValue: 10 }),
      expect.objectContaining({ type: 'slider', yAxisIndex: 0, startValue: 0, endValue: 10 }),
    ]));
  });

  it('adds a category-axis viewport to column and line charts as well', () => {
    const categories = Array.from({ length: 12 }, (_, index) => `项目${index}`);
    const column = buildColumnBarOption({
      title: '数量',
      categories,
      series: [{ name: '数量', data: categories.map((_, index) => index) }],
    });
    const line = buildLineOption({
      title: '趋势',
      categories,
      series: [{ name: '趋势', data: categories.map((_, index) => index) }],
    });

    expect(column?.dataZoom).toEqual(expect.arrayContaining([
      expect.objectContaining({ type: 'slider', xAxisIndex: 0, startValue: 0, endValue: 10 }),
    ]));
    expect(line?.dataZoom).toEqual(expect.arrayContaining([
      expect.objectContaining({ type: 'slider', xAxisIndex: 0, startValue: 0, endValue: 10 }),
    ]));
  });
});
