import { mount } from '@vue/test-utils';
import { describe, expect, it, vi } from 'vitest';
import StatisticBoardControlBar from './StatisticBoardControlBar.vue';
import type { StatisticBoardControlSpec } from '../feature-manifest/statistic-board-controls';

const spec: StatisticBoardControlSpec = {
  dimensionParam: 'groupBy',
  dimensionLabel: '行维度',
  dimensionOptions: [
    { label: '按客户', value: 'CUSTOMER' },
    { label: '按客户×模块', value: 'CUSTOMER_MODULE' },
  ],
  defaultDimension: 'CUSTOMER',
  dimensionGroupKey: 'row-dimension',
  members: [
    { key: 'customer', label: '客户', missingLabel: '未标注客户' },
    { key: 'module', label: '模块', missingLabel: '未标注模块' },
    { key: 'function', label: '功能', missingLabel: '未标注功能' },
  ],
};

function mountBar(overrides: Record<string, unknown> = {}) {
  return mount(StatisticBoardControlBar, {
    props: {
      spec,
      activeDimension: 'CUSTOMER_MODULE',
      onDimensionChange: vi.fn(),
      ...overrides,
    },
    global: {
      stubs: {
        ElSelect: {
          name: 'ElSelect',
          props: ['modelValue', 'placeholder', 'clearable', 'disabled'],
          emits: ['update:modelValue'],
          template: '<div class="select"><slot /></div>',
        },
        ElOption: {
          name: 'ElOption',
          props: ['label', 'value'],
          template: '<span class="option">{{ label }}</span>',
        },
      },
    },
  });
}

describe('StatisticBoardControlBar', () => {
  it('renders the row dimension as an analysis setting', () => {
    const wrapper = mountBar();

    expect(wrapper.text()).toContain('行维度');
    expect(wrapper.text()).toContain('按客户');
    expect(wrapper.text()).toContain('按客户×模块');
    expect(wrapper.findAll('.stat-board-controls__label').map((node) => node.text())).toEqual(['行维度']);
  });

  it('reports the selected row dimension', async () => {
    const onDimensionChange = vi.fn();
    const wrapper = mountBar({ onDimensionChange });

    await wrapper.findAllComponents({ name: 'ElSelect' })[0].vm.$emit('update:modelValue', 'CUSTOMER');

    expect(onDimensionChange).toHaveBeenCalledWith('CUSTOMER');
  });

  it('disables the row dimension while the page is not ready', () => {
    const wrapper = mountBar({ disabled: true });

    expect(wrapper.findAllComponents({ name: 'ElSelect' }).map((select) => select.props('disabled')))
      .toEqual([true]);
  });

  it('does not render member selectors: they belong to the shared quick-filter row', () => {
    const wrapper = mountBar();

    expect(wrapper.findAllComponents({ name: 'ElSelect' })).toHaveLength(1);
    expect(wrapper.find('[data-testid="control-options-state"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="member-selection-error"]').exists()).toBe(false);
  });
});
