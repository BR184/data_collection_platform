import { beforeEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import TagGroupFilterBar from './TagGroupFilterBar.vue';
import type { TagGroupsResponse } from '../types/api';
import { ElMessageBox } from '../element-plus-services';

vi.mock('../element-plus-services', () => ({
  ElMessage: {
    warning: vi.fn(),
    success: vi.fn(),
    info: vi.fn(),
    error: vi.fn(),
  },
  ElMessageBox: {
    confirm: vi.fn(),
  },
}));

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
    vi.mocked(ElMessageBox.confirm).mockReset();
    vi.mocked(ElMessageBox.confirm).mockResolvedValue('confirm' as never);
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

  it('clears all tag selections only after user confirmation', async () => {
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

    expect(ElMessageBox.confirm).toHaveBeenCalledWith(
      '只清空标签组筛选，不会清空关键词、指标与例外条件或排序。',
      '清空标签',
      expect.objectContaining({ confirmButtonText: '清空标签' }),
    );
    expect(wrapper.emitted('change')?.at(-1)).toEqual([[]]);
  });

  it('keeps tag selections when clear confirmation is canceled', async () => {
    vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce(new Error('cancel'));
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

    expect(wrapper.emitted('change')).toBeUndefined();
  });

  it('requires a named snapshot before saving', async () => {
    const wrapper = mount(TagGroupFilterBar, {
      props: {
        modelValue: [{ groupKey: 'module', valueKeys: ['sketch'] }],
        tagGroups,
        storageKey: 'tag-groups:test',
      },
      global: {
        plugins: [ElementPlus],
        stubs: {
          ElPopover: {
            props: ['visible'],
            template: '<div><slot name="reference" /><slot /></div>',
          },
        },
      },
    });

    await wrapper.findAll('button').find((button) => button.text().includes('保存快照'))?.trigger('click');
    const confirmButton = wrapper.get('[data-testid="tag-group-snapshot-save-confirm"]');
    expect(confirmButton.attributes('disabled')).toBeDefined();

    await wrapper.get('[data-testid="tag-group-snapshot-name-input"]').setValue('高风险模块');
    await confirmButton.trigger('click');

    const rawStore = window.localStorage.getItem('tag-groups:test');
    expect(rawStore).toContain('高风险模块');
    expect(wrapper.emitted('snapshot-saved')?.at(-1)).toEqual([{ name: '高风险模块' }]);
  });

  it('opens a formal restore selector instead of restoring from the trigger button', async () => {
    window.localStorage.setItem(
      'tag-groups:test',
      JSON.stringify({
        schemaVersion: 1,
        snapshots: [
          {
            schemaVersion: 1,
            id: 'snapshot-a',
            name: '复盘筛选',
            schemaHash: 'hash-a',
            tagSelections: [{ groupKey: 'module', valueKeys: ['surface'] }],
            fixedFilters: { keyword: 'design' },
            savedAt: '2026-06-08T14:30:00.000Z',
            expiresAt: '2026-07-08T14:30:00.000Z',
            pinned: true,
          },
        ],
        activeSnapshotId: 'snapshot-a',
      }),
    );
    const wrapper = mount(TagGroupFilterBar, {
      props: {
        modelValue: [],
        tagGroups,
        storageKey: 'tag-groups:test',
        autoRestore: false,
      },
      global: {
        plugins: [ElementPlus],
        stubs: {
          ElPopover: {
            props: ['visible'],
            template: '<div><slot name="reference" /><slot /></div>',
          },
        },
      },
    });

    await wrapper.get('[data-testid="tag-group-snapshot-restore-trigger"]').trigger('click');
    expect(wrapper.emitted('snapshot-restored')).toBeUndefined();
    expect(wrapper.text()).toContain('复盘筛选');
    expect(wrapper.text()).toContain('2026-06-08 14:30:00 · 2 个条件');

    await wrapper.get('.tag-group-filter-bar-snapshot-option').trigger('click');

    expect(wrapper.emitted('snapshot-restored')?.at(-1)).toEqual([{
      tagSelections: [{ groupKey: 'module', valueKeys: ['surface'] }],
      ignoredCount: 0,
      schemaMismatch: false,
      fixedFilters: { keyword: 'design' },
      source: 'manual',
    }]);
  });
});
