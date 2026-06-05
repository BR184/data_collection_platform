import { beforeEach, describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import TagGroupFilter from './TagGroupFilter.vue';
import type { TagGroupsResponse } from '../types/api';

const tagGroups: TagGroupsResponse = {
  domain: 'issue',
  schemaHash: 'hash-a',
  groups: [
    {
      groupKey: 'module',
      label: 'Module',
      selectionMode: 'multiple',
      sortOrder: 1,
      matchStrategyName: 'split_exact_comma',
      values: [
        { valueKey: 'sketch', label: 'Sketch', valueType: 'standard', sortOrder: 1, disabled: false },
        { valueKey: 'surface', label: 'Surface', valueType: 'standard', sortOrder: 2, disabled: false },
      ],
    },
    {
      groupKey: 'severity',
      label: 'Severity',
      selectionMode: 'single',
      sortOrder: 2,
      matchStrategyName: 'eq',
      values: [
        { valueKey: 'major', label: 'Major', valueType: 'standard', sortOrder: 1, disabled: false },
        { valueKey: 'minor', label: 'Minor', valueType: 'standard', sortOrder: 2, disabled: false },
      ],
    },
  ],
};

describe('TagGroupFilter', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it('emits multiple selections and supports canceling a selected value', async () => {
    const wrapper = mount(TagGroupFilter, {
      props: {
        modelValue: [],
        tagGroups,
      },
      global: {
        plugins: [ElementPlus],
      },
    });

    await wrapper.findAll('.tag-group-filter-value').find((button) => button.text().includes('Sketch'))?.trigger('click');
    expect(wrapper.emitted('change')?.at(-1)).toEqual([[{ groupKey: 'module', valueKeys: ['sketch'] }]]);

    await wrapper.setProps({ modelValue: [{ groupKey: 'module', valueKeys: ['sketch'] }] });
    await wrapper.findAll('.tag-group-filter-value').find((button) => button.text().includes('Surface'))?.trigger('click');
    expect(wrapper.emitted('change')?.at(-1)).toEqual([[
      { groupKey: 'module', valueKeys: ['sketch', 'surface'] },
    ]]);

    await wrapper.setProps({ modelValue: [{ groupKey: 'module', valueKeys: ['sketch', 'surface'] }] });
    await wrapper.findAll('.tag-group-filter-value').find((button) => button.text().includes('Sketch'))?.trigger('click');
    expect(wrapper.emitted('change')?.at(-1)).toEqual([[{ groupKey: 'module', valueKeys: ['surface'] }]]);
  });

  it('keeps only one value in single-selection groups', async () => {
    const wrapper = mount(TagGroupFilter, {
      props: {
        modelValue: [{ groupKey: 'severity', valueKeys: ['major'] }],
        tagGroups,
      },
      global: {
        plugins: [ElementPlus],
      },
    });

    await wrapper.findAll('.tag-group-filter-value').find((button) => button.text().includes('Minor'))?.trigger('click');

    expect(wrapper.emitted('change')?.at(-1)).toEqual([[{ groupKey: 'severity', valueKeys: ['minor'] }]]);
  });

  it('restores a snapshot and reports stale entries', async () => {
    window.localStorage.setItem(
      'tag-groups:test',
      JSON.stringify({
        schemaVersion: 1,
        schemaHash: 'old-hash',
        tagSelections: [
          { groupKey: 'module', valueKeys: ['sketch', 'missing'] },
          { groupKey: 'ghost', valueKeys: ['lost'] },
        ],
      }),
    );

    const wrapper = mount(TagGroupFilter, {
      props: {
        modelValue: [],
        tagGroups,
        storageKey: 'tag-groups:test',
      },
      global: {
        plugins: [ElementPlus],
      },
    });

    await wrapper.findAll('button').find((button) => button.text().includes('恢复快照'))?.trigger('click');

    expect(wrapper.emitted('change')?.at(-1)).toEqual([[{ groupKey: 'module', valueKeys: ['sketch'] }]]);
    expect(wrapper.emitted('snapshot-restored')?.at(-1)).toEqual([{ ignoredCount: 2, schemaMismatch: true }]);
  });
});
