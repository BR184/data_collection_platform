import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import BiMetricStrip from './BiMetricStrip.vue';

function items(count: number) {
  return Array.from({ length: count }, (_, index) => ({
    label: `指标 ${index + 1}`,
    value: String(index + 1),
  }));
}

describe('BiMetricStrip', () => {
  it('marks four-item and high-density layouts without changing metric order', () => {
    const four = mount(BiMetricStrip, { props: { items: items(4) } });
    const many = mount(BiMetricStrip, { props: { items: items(6) } });

    expect(four.classes()).toContain('bi-metric-strip--four');
    expect(many.classes()).toContain('bi-metric-strip--many');
    expect(many.findAll('.bi-metric__label').map((item) => item.text()))
      .toEqual(['指标 1', '指标 2', '指标 3', '指标 4', '指标 5', '指标 6']);
  });
});
