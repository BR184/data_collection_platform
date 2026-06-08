import { beforeEach, describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import TagGroupFilterBar from './TagGroupFilterBar.vue';
import type { TagGroupsResponse } from '../types/api';

const tagGroups: TagGroupsResponse = {
  domain: 'review_data',
  schemaHash: 'hash-a',
  groups: [
    {
      groupKey: 'module',
      label: '评审模块',
      selectionMode: 'multiple',
      sortOrder: 1,
      matchStrategyName: 'eq',
      values: [
        { valueKey: 'sketch', label: '草图', valueType: 'standard', sortOrder: 1, disabled: false },
        { valueKey: 'surface', label: '曲面', valueType: 'standard', sortOrder: 2, disabled: false },
      ],
    },
    {
      groupKey: 'problem_status',
      label: '问题状态',
      selectionMode: 'multiple',
      sortOrder: 2,
      matchStrategyName: 'eq',
      values: [
        { valueKey: 'new', label: '新提交', valueType: 'standard', sortOrder: 1, disabled: false },
      ],
    },
  ],
};

describe('TagGroupFilterBar', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it('renders review module as a compact field and emits multiple tag selections', async () => {
    const wrapper = mount(TagGroupFilterBar, {
      props: {
        modelValue: [],
        tagGroups,
      },
      global: {
        plugins: [ElementPlus],
      },
    });

    const selects = wrapper.findAllComponents({ name: 'ElSelect' });
    expect(selects).toHaveLength(2);
    expect(selects[0].props('placeholder')).toBe('模块');

    selects[0].vm.$emit('update:modelValue', ['sketch', 'surface']);
    await wrapper.vm.$nextTick();

    expect(wrapper.emitted('change')?.at(-1)).toEqual([[{
      groupKey: 'module',
      valueKeys: ['sketch', 'surface'],
    }]]);
  });

  it('clears all tag selections from the action bar', async () => {
    const wrapper = mount(TagGroupFilterBar, {
      props: {
        modelValue: [{ groupKey: 'module', valueKeys: ['sketch'] }],
        tagGroups,
      },
      global: {
        plugins: [ElementPlus],
      },
    });

    await wrapper.findAll('button').find((button) => button.text().includes('清空标签'))?.trigger('click');

    expect(wrapper.emitted('change')?.at(-1)).toEqual([[]]);
  });
});
