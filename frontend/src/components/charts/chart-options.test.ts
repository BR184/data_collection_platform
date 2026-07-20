import { describe, expect, it } from 'vitest';
import { buildColumnBarOption, buildHorizontalBarOption, buildLineOption } from './chart-options';

describe('shared ECharts options', () => {
  it('keeps the legacy first-eleven-item viewport with one filtering slider owner', () => {
    const option = buildHorizontalBarOption({
      title: '按人员统计',
      items: Array.from({ length: 15 }, (_, index) => ({ name: `人员${index}`, value: index })),
    });

    expect(option?.dataZoom).toEqual([
      expect.objectContaining({
        type: 'slider',
        yAxisIndex: 0,
        startValue: 0,
        endValue: 10,
        width: 30,
        right: 16,
        filterMode: 'filter',
        handleSize: '100%',
      }),
    ]);
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
      expect.objectContaining({
        type: 'slider', xAxisIndex: 0, startValue: 0, endValue: 10, height: 30, bottom: 28,
      }),
    ]));
    expect(line?.dataZoom).toEqual(expect.arrayContaining([
      expect.objectContaining({
        type: 'slider', xAxisIndex: 0, startValue: 0, endValue: 10, height: 30, bottom: 28,
      }),
    ]));
    expect(column?.grid).toEqual(expect.objectContaining({ bottom: 108 }));
    expect(line?.grid).toEqual(expect.objectContaining({ bottom: 108 }));
  });

  it('keeps dense category charts readable when a consumer expands the slider window', () => {
    const categories = Array.from({ length: 80 }, (_, index) => `非常长的分类名称-${index}`);
    const option = buildColumnBarOption({
      title: '数量',
      categories,
      series: [{ name: '数量', data: categories.map((_, index) => index) }],
      rotateLabels: 28,
    });

    expect(option?.xAxis).toEqual(expect.objectContaining({
      axisLabel: expect.objectContaining({
        interval: 'auto', hideOverlap: true, overflow: 'truncate', width: 104,
      }),
    }));
    expect(option?.series).toEqual(expect.arrayContaining([
      expect.objectContaining({ labelLayout: expect.objectContaining({ hideOverlap: true }) }),
    ]));
  });

  it('rounds only the top edge of the visible top stacked column bar segment', () => {
    const option = buildColumnBarOption({
      title: '严重程度',
      categories: ['阶段一', '阶段二', '阶段三'],
      series: [
        { name: '一级缺陷', stack: 'severity', data: [1, 0, 5], color: '#3b82f6' },
        { name: '二级缺陷', stack: 'severity', data: [2, 3, 0], color: '#14b8a6' },
        { name: '三级缺陷', stack: 'severity', data: [0, 4, 0], color: '#f59e0b' },
      ],
    });
    const series = option?.series as Array<{ data: Array<{ itemStyle: { borderRadius: number[] } }> }>;

    expect(series[0].data[0].itemStyle.borderRadius).toEqual([0, 0, 0, 0]);
    expect(series[1].data[0].itemStyle.borderRadius).toEqual([6, 6, 0, 0]);
    expect(series[1].data[1].itemStyle.borderRadius).toEqual([0, 0, 0, 0]);
    expect(series[2].data[1].itemStyle.borderRadius).toEqual([6, 6, 0, 0]);
    expect(series[0].data[2].itemStyle.borderRadius).toEqual([6, 6, 0, 0]);
  });

  it('preserves column bar data item metadata while applying shared visual styling', () => {
    const option = buildColumnBarOption({
      title: '可下钻数量',
      categories: ['阶段一'],
      series: [{
        name: '一级缺陷',
        data: [{ name: '阶段一', value: 8, pointKey: 'phase:one' }],
      }],
    });
    const series = option?.series as Array<{ data: Array<{ pointKey: string; itemStyle: { borderRadius: number[] } }> }>;

    expect(series[0].data[0]).toEqual(expect.objectContaining({
      pointKey: 'phase:one',
      itemStyle: expect.objectContaining({ borderRadius: [6, 6, 0, 0] }),
    }));
  });
});
