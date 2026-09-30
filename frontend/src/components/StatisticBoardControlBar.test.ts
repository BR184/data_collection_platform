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
      optionsStatus: 'ready',
      memberControls: [
        {
          key: 'customer',
          label: '客户',
          // 选择令牌把类型与取值合在一起：选择客户甲之后，客户乙依然在列表里。
          value: 'VALUE:客户甲',
          options: [
            { label: '客户乙', value: 'VALUE:客户乙' },
            { label: '客户甲', value: 'VALUE:客户甲' },
          ],
        },
        {
          key: 'module',
          label: '模块',
          value: '',
          options: [
            { label: '模块A', value: 'VALUE:模块A' },
            { label: '未标注模块', value: 'MISSING' },
          ],
        },
        {
          key: 'function',
          label: '功能',
          value: '',
          options: [{ label: '功能A', value: 'VALUE:功能A' }],
        },
      ],
      onDimensionChange: vi.fn(),
      onMemberChange: vi.fn(),
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
  it('renders the row dimension and every member control', () => {
    const wrapper = mountBar();

    expect(wrapper.text()).toContain('行维度');
    expect(wrapper.text()).toContain('按客户');
    expect(wrapper.text()).toContain('按客户×模块');
    expect(wrapper.text()).toContain('客户');
    expect(wrapper.text()).toContain('模块');
    expect(wrapper.text()).toContain('模块A');
    expect(wrapper.text()).toContain('未标注模块');
    expect(wrapper.text()).toContain('功能A');
  });

  it('reports unavailable candidates with the reason instead of an empty list', () => {
    const wrapper = mountBar({
      optionsStatus: 'unavailable',
      optionsMessage: '来源 q3chain 的全量事实重建尚未结算，暂不产出统计结果',
    });

    const state = wrapper.get('[data-testid="control-options-state"]');
    expect(state.text()).toContain('尚未结算');
  });

  it('reports the selected row dimension and member', async () => {
    const onDimensionChange = vi.fn();
    const onMemberChange = vi.fn();
    const wrapper = mountBar({ onDimensionChange, onMemberChange });

    const selects = wrapper.findAllComponents({ name: 'ElSelect' });
    await selects[0].vm.$emit('update:modelValue', 'CUSTOMER');
    await selects[2].vm.$emit('update:modelValue', 'MISSING');

    expect(onDimensionChange).toHaveBeenCalledWith('CUSTOMER');
    expect(onMemberChange).toHaveBeenCalledWith('module', 'MISSING');
  });

  it('offers missing and real members as separate options of the same control', () => {
    const wrapper = mountBar({
      memberControls: [
        {
          key: 'customer',
          label: '客户',
          value: 'VALUE:__missing__',
          options: [
            { label: '未标注客户（成员缺失）', value: 'MISSING' },
            { label: '__missing__', value: 'VALUE:__missing__' },
            { label: '真实客户：未标注客户', value: 'VALUE:未标注客户' },
          ],
        },
      ],
    });

    const tokens = wrapper
      .findAllComponents({ name: 'ElOption' })
      .map((option) => option.props('value'));
    expect(tokens).toContain('MISSING');
    expect(tokens).toContain('VALUE:__missing__');
    expect(tokens).toContain('VALUE:未标注客户');
    expect(wrapper.text()).toContain('未标注客户（成员缺失）');
    expect(wrapper.text()).toContain('真实客户：未标注客户');
  });

  it('disables member selectors until candidates are verified for the current scope', () => {
    const wrapper = mountBar({ optionsStatus: 'loading' });
    const selects = wrapper.findAllComponents({ name: 'ElSelect' });

    expect(selects[0].props('disabled')).toBe(false);
    expect(selects.slice(1).map((select) => select.props('disabled'))).toEqual([true, true, true]);
  });

  it('shows invalid URL member parameters without treating them as an all-members choice', () => {
    const wrapper = mountBar({
      memberSelectionError: '客户成员筛选参数无效：customerKind/customer',
    });

    expect(wrapper.text()).toContain('customerKind/customer');
  });

  it('renders member controls independently of the active row dimension', () => {
    const wrapper = mountBar({ activeDimension: 'CUSTOMER' });

    const labels = wrapper.findAll('.stat-board-controls__label').map((node) => node.text());
    expect(labels).toEqual(['行维度', '客户', '模块', '功能']);
  });
});
