import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
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

const inlinePopoverStub = {
  props: ['visible'],
  template: '<div><slot name="reference" /><slot /></div>',
};

function mountFilterBar(props: Record<string, unknown> = {}) {
  return mount(TagGroupFilterBar, {
    props: {
      modelValue: [],
      tagGroups,
      ...props,
    },
    global: {
      plugins: [ElementPlus],
      stubs: {
        ElPopover: inlinePopoverStub,
      },
    },
  });
}

describe('TagGroupFilterBar', () => {
  beforeEach(() => {
    window.localStorage.clear();
    vi.mocked(ElMessageBox.confirm).mockReset();
    vi.mocked(ElMessageBox.confirm).mockResolvedValue('confirm' as never);
  });

  it('renders review module in a grouped popover and emits multiple tag selections', async () => {
    const wrapper = mountFilterBar();

    expect(wrapper.get('[data-testid="tag-group-filter-bar-group-module"]').text()).toContain('模块');

    await wrapper.get('[data-testid="tag-group-filter-bar-value-sketch"]').trigger('click');
    await wrapper.setProps({
      modelValue: [{ groupKey: 'module', valueKeys: ['sketch'] }],
    });
    await wrapper.get('[data-testid="tag-group-filter-bar-value-surface"]').trigger('click');

    expect(wrapper.emitted('change')?.at(-1)).toEqual([[{
      groupKey: 'module',
      valueKeys: ['sketch', 'surface'],
    }]]);
  });

  it('shows a lightweight summary and selected tag chips', async () => {
    const wrapper = mountFilterBar({
      currentTotal: 42,
      currentViewName: 'Last saved 2026-06-07 14:30',
    });

    const header = wrapper.get('[data-testid="tag-group-filter-bar-header"]');
    expect(header.attributes('aria-expanded')).toBe('false');
    expect(wrapper.get('[data-testid="tag-group-filter-bar-summary"]').text()).toContain('Last saved 2026-06-07 14:30');
    expect(wrapper.get('[data-testid="tag-group-filter-bar-summary"]').text()).toContain('42');
    expect(wrapper.get('[data-testid="tag-group-filter-bar-summary"]').text()).toContain('0');

    await wrapper.setProps({
      modelValue: [{ groupKey: 'module', valueKeys: ['sketch', 'surface'] }],
    });

    expect(wrapper.get('[data-testid="tag-group-filter-bar-summary"]').text()).toContain('2');
    expect(wrapper.text()).toContain('模块: 草图, 曲面');
  });

  it('clears a single selected group from its chip', async () => {
    const wrapper = mountFilterBar({
      modelValue: [
        { groupKey: 'module', valueKeys: ['sketch'] },
        { groupKey: 'problem_status', valueKeys: ['new'] },
      ],
    });

    await wrapper.findAll('.tag-group-filter-bar-chip .el-tag__close')[0].trigger('click');

    expect(wrapper.emitted('change')?.at(-1)).toEqual([[{
      groupKey: 'problem_status',
      valueKeys: ['new'],
    }]]);
  });

  it('clears all tag selections only after user confirmation', async () => {
    const wrapper = mountFilterBar({
      modelValue: [{ groupKey: 'module', valueKeys: ['sketch'] }],
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
    const wrapper = mountFilterBar({
      modelValue: [{ groupKey: 'module', valueKeys: ['sketch'] }],
    });

    await wrapper.findAll('button').find((button) => button.text().includes('清空标签'))?.trigger('click');

    expect(wrapper.emitted('change')).toBeUndefined();
  });

  it('requires a named snapshot before saving', async () => {
    const wrapper = mountFilterBar({
      modelValue: [{ groupKey: 'module', valueKeys: ['sketch'] }],
      storageKey: 'tag-groups:test',
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

  it('keeps the save snapshot form open for typing', async () => {
    const wrapper = mount(TagGroupFilterBar, {
      attachTo: document.body,
      props: {
        modelValue: [{ groupKey: 'module', valueKeys: ['sketch'] }],
        tagGroups,
        storageKey: 'tag-groups:test',
      },
      global: {
        plugins: [ElementPlus],
      },
    });

    await wrapper.findAll('button').find((button) => button.text().includes('保存快照'))?.trigger('click');
    await flushPromises();

    expect(document.body.querySelector('[data-testid="tag-group-snapshot-name-input"]')).not.toBeNull();

    wrapper.unmount();
  });

  it('deletes a saved snapshot without restoring it', async () => {
    window.localStorage.setItem(
      'tag-groups:test',
      JSON.stringify({
        schemaVersion: 1,
        snapshots: [
          {
            schemaVersion: 1,
            id: 'snapshot-a',
            name: '模块筛选',
            schemaHash: 'hash-a',
            tagSelections: [{ groupKey: 'module', valueKeys: ['surface'] }],
            fixedFilters: {},
            savedAt: '2026-06-08T14:30:00.000Z',
            expiresAt: '2026-07-08T14:30:00.000Z',
            pinned: true,
          },
        ],
        activeSnapshotId: 'snapshot-a',
      }),
    );
    const wrapper = mountFilterBar({
      storageKey: 'tag-groups:test',
      autoRestore: false,
    });

    await wrapper.get('[data-testid="tag-group-snapshot-delete-snapshot-a"]').trigger('click');

    expect(wrapper.emitted('snapshot-restored')).toBeUndefined();
    expect(window.localStorage.getItem('tag-groups:test')).not.toContain('snapshot-a');
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
    const wrapper = mountFilterBar({
      storageKey: 'tag-groups:test',
      autoRestore: false,
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
