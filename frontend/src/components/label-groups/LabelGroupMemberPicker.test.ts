import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { describe, expect, it, vi } from 'vitest';
import LabelGroupMemberPicker from './LabelGroupMemberPicker.vue';
import type { LabelValuePage } from '../../types/api';

describe('LabelGroupMemberPicker', () => {
  it('allows manual member input without a candidate source', () => {
    const wrapper = mount(LabelGroupMemberPicker, {
      props: {
        modelValue: [],
        dimensionKey: '',
      },
      global: { plugins: [ElementPlus] },
    });

    expect(wrapper.findComponent({ name: 'ElSelect' }).exists()).toBe(true);
    expect(wrapper.findComponent({ name: 'ElSelect' }).props('placeholder')).toContain('直接输入成员');
  });

  it('loads candidates for the selected dimension and supports keyword search', async () => {
    const fetchValues = vi.fn(async (_dimensionKey: string, keyword: string, _page: number, _size: number): Promise<LabelValuePage> => page([
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

    expect(fetchValues).toHaveBeenCalledWith('module', '', 1, 200);

    await (wrapper.vm as unknown as { loadCandidates: (keyword: string) => Promise<void> }).loadCandidates('草');
    await flushPromises();

    expect(fetchValues).toHaveBeenLastCalledWith('module', '草', 1, 200);
    const vm = wrapper.vm as unknown as { candidateOptions: Array<{ value: string; label: string }> };
    expect(vm.candidateOptions).toEqual([{ value: '草图', label: '草图', currentAvailable: true }]);
  });

  it('loads all pages until the dimension total is reached', async () => {
    const firstPage = [
      { value: 'alpha', label: 'Alpha' },
      { value: 'bravo', label: 'Bravo' },
    ];
    const secondPage = [{ value: '王工', label: '王工' }];
    const fetchValues = vi.fn(async (_dimensionKey: string, _keyword: string, pageNumber: number, _size: number): Promise<LabelValuePage> => page(
      pageNumber === 1 ? firstPage : secondPage,
      firstPage.length + secondPage.length,
      pageNumber,
    ));
    const wrapper = mount(LabelGroupMemberPicker, {
      props: {
        modelValue: [],
        dimensionKey: 'module',
        fetchValues,
      },
      global: { plugins: [ElementPlus] },
    });
    await flushPromises();

    expect(fetchValues).toHaveBeenCalledTimes(2);
    expect(fetchValues).toHaveBeenNthCalledWith(1, 'module', '', 1, 200);
    expect(fetchValues).toHaveBeenNthCalledWith(2, 'module', '', 2, 200);
    const vm = wrapper.vm as unknown as { candidateOptions: Array<{ value: string; label: string }> };
    expect(vm.candidateOptions.map((item) => item.value)).toEqual(['alpha', 'bravo', '王工']);
  });

  it('discards results from a stale load loop when a new load starts mid-flight', async () => {
    let resolveStaleLoad: (value: LabelValuePage) => void = () => undefined;
    const staleLoad = new Promise<LabelValuePage>((resolve) => {
      resolveStaleLoad = resolve;
    });
    const fetchValues = vi
      .fn<(dimensionKey: string, keyword: string, page: number, size: number) => Promise<LabelValuePage>>()
      .mockImplementationOnce(() => staleLoad)
      .mockImplementation(async (_dimensionKey: string, _keyword: string, pageNumber: number) => page(
        pageNumber === 1 ? [{ value: '王工', label: '王工' }] : [],
        1,
        pageNumber,
      ));
    const wrapper = mount(LabelGroupMemberPicker, {
      props: {
        modelValue: [],
        dimensionKey: 'module',
        fetchValues,
      },
      global: { plugins: [ElementPlus] },
    });

    await (wrapper.vm as unknown as { loadCandidates: (keyword: string) => Promise<void> }).loadCandidates('王');
    await flushPromises();
    resolveStaleLoad(page([{ value: '旧值', label: '旧值' }]));
    await flushPromises();

    const vm = wrapper.vm as unknown as { candidateOptions: Array<{ value: string; label: string }> };
    expect(vm.candidateOptions.map((item) => item.value)).toEqual(['王工']);
  });

  it('keeps saved unavailable members visible instead of deleting them', async () => {
    const wrapper = mount(LabelGroupMemberPicker, {
      props: {
        modelValue: [{ value: '历史模块', label: '历史模块', currentAvailable: false }],
        dimensionKey: 'module',
        fetchValues: async (_dimensionKey: string, _keyword: string, _page: number, _size: number) => page([]),
      },
      global: { plugins: [ElementPlus] },
    });
    await flushPromises();

    expect(wrapper.text()).toContain('历史模块');
    expect(wrapper.text()).toContain('当前数据中暂无命中');
  });

  it('does not show selected members in the dropdown candidates for another source', async () => {
    const wrapper = mount(LabelGroupMemberPicker, {
      props: {
        modelValue: [{ value: '张三', label: '张三' }],
        dimensionKey: 'module',
        fetchValues: async (_dimensionKey: string, _keyword: string, _page: number, _size: number) => page([
          { value: '草图', label: '草图' },
          { value: '张三', label: '张三' },
        ]),
      },
      global: { plugins: [ElementPlus] },
    });
    await flushPromises();

    const vm = wrapper.vm as unknown as { candidateOptions: Array<{ value: string; label: string }> };
    expect(vm.candidateOptions).toEqual([{ value: '草图', label: '草图', currentAvailable: true }]);
    expect(wrapper.text()).toContain('张三');
  });

  it('emits selected members from existing candidates', async () => {
    const wrapper = mount(LabelGroupMemberPicker, {
      props: {
        modelValue: [],
        dimensionKey: 'module',
        fetchValues: async (_dimensionKey: string, _keyword: string, _page: number, _size: number) => page([{ value: '草图', label: '草图' }]),
      },
      global: { plugins: [ElementPlus] },
    });
    await flushPromises();

    const selectModel = wrapper.findComponent({ name: 'ElSelect' });
    selectModel.vm.$emit('update:modelValue', ['草图']);

    expect(wrapper.emitted('update:modelValue')?.[0][0]).toEqual([{ value: '草图', label: '草图', currentAvailable: true }]);
  });

  it('keeps manually entered values that are not present in the candidate list', async () => {
    const wrapper = mount(LabelGroupMemberPicker, {
      props: {
        modelValue: [],
        dimensionKey: 'module',
        fetchValues: async (_dimensionKey: string, _keyword: string, _page: number, _size: number) => page([{ value: '草图', label: '草图' }]),
      },
      global: { plugins: [ElementPlus] },
    });
    await flushPromises();

    const selectModel = wrapper.findComponent({ name: 'ElSelect' });
    selectModel.vm.$emit('update:modelValue', ['不存在的值']);

    expect(wrapper.emitted('update:modelValue')?.[0][0]).toEqual([
      { value: '不存在的值', label: '不存在的值', currentAvailable: false },
    ]);
  });

  it('filters candidates after the group has inferred a value type', async () => {
    const wrapper = mount(LabelGroupMemberPicker, {
      props: {
        modelValue: [],
        dimensionKey: 'module',
        valueType: 'STRING',
        fetchValues: async (_dimensionKey: string, _keyword: string, _page: number, _size: number) => page([
          { value: '草图', label: '草图' },
          { value: '100', label: '100' },
        ]),
      },
      global: { plugins: [ElementPlus] },
    });
    await flushPromises();

    const vm = wrapper.vm as unknown as { candidateOptions: Array<{ value: string; label: string }> };
    expect(vm.candidateOptions.map((item) => item.value)).toEqual(['草图']);
  });
});

function page(items: Array<{ value: string; label: string }>, total = items.length, pageNumber = 1): LabelValuePage {
  return {
    items: items.map((item) => ({
      ...item,
      valueKind: 'STRING_LITERAL',
      source: 'FACT',
      hitCount: 0,
    })),
    total,
    page: pageNumber,
    size: 20,
  };
}
