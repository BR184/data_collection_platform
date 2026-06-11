import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { describe, expect, it, vi } from 'vitest';
import LabelGroupMemberPicker from './LabelGroupMemberPicker.vue';
import type { LabelValuePage } from '../../types/api';

describe('LabelGroupMemberPicker', () => {
  it('allows manual entry before candidate source is selected', () => {
    const wrapper = mount(LabelGroupMemberPicker, {
      props: {
        modelValue: [],
        dimensionKey: '',
      },
      global: { plugins: [ElementPlus] },
    });

    expect(wrapper.findComponent({ name: 'ElSelect' }).exists()).toBe(true);
  });

  it('loads candidates for the selected dimension and supports keyword search', async () => {
    const fetchValues = vi.fn(async (_dimensionKey: string, keyword: string) => page([
      { value: keyword ? '草图' : '工程图', label: keyword ? '草图' : '工程图' },
    ]));
    const wrapper = mount(LabelGroupMemberPicker, {
      props: {
        modelValue: [],
        dimensionKey: 'module',
        fetchValues,
      },
      global: { plugins: [ElementPlus] },
    });
    await flushPromises();

    expect(fetchValues).toHaveBeenCalledWith('module', '');

    await (wrapper.vm as unknown as { loadCandidates: (keyword: string) => Promise<void> }).loadCandidates('草');
    await flushPromises();

    expect(fetchValues).toHaveBeenLastCalledWith('module', '草');
    const vm = wrapper.vm as unknown as { candidateOptions: Array<{ value: string; label: string }> };
    expect(vm.candidateOptions).toEqual([{ value: '草图', label: '草图', currentAvailable: true }]);
  });

  it('keeps saved unavailable members visible instead of deleting them', async () => {
    const wrapper = mount(LabelGroupMemberPicker, {
      props: {
        modelValue: [{ value: '历史模块', label: '历史模块', currentAvailable: false }],
        dimensionKey: 'module',
        fetchValues: async () => page([]),
      },
      global: { plugins: [ElementPlus] },
    });
    await flushPromises();

    expect(wrapper.text()).toContain('历史模块');
    expect(wrapper.text()).toContain('当前数据中暂无命中');
  });

  it('emits selected members from existing candidates only', async () => {
    const wrapper = mount(LabelGroupMemberPicker, {
      props: {
        modelValue: [],
        dimensionKey: 'module',
        fetchValues: async () => page([{ value: '草图', label: '草图' }]),
      },
      global: { plugins: [ElementPlus] },
    });
    await flushPromises();

    const selectModel = wrapper.findComponent({ name: 'ElSelect' });
    selectModel.vm.$emit('update:modelValue', ['草图']);

    expect(wrapper.emitted('update:modelValue')?.[0][0]).toEqual([{ value: '草图', label: '草图', currentAvailable: true }]);
  });

  it('filters candidates after the group has inferred a value type', async () => {
    const wrapper = mount(LabelGroupMemberPicker, {
      props: {
        modelValue: [],
        dimensionKey: 'module',
        valueType: 'STRING',
        fetchValues: async () => page([{ value: '草图', label: '草图' }, { value: '100', label: '100' }]),
      },
      global: { plugins: [ElementPlus] },
    });
    await flushPromises();

    const vm = wrapper.vm as unknown as { candidateOptions: Array<{ value: string; label: string }> };
    expect(vm.candidateOptions.map((item) => item.value)).toEqual(['草图']);
  });
});

function page(items: Array<{ value: string; label: string }>): LabelValuePage {
  return {
    items: items.map((item) => ({
      ...item,
      valueKind: 'STRING_LITERAL',
      source: 'FACT',
      hitCount: 0,
    })),
    total: items.length,
    page: 1,
    size: 20,
  };
}
