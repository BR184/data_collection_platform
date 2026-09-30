import { mount } from '@vue/test-utils';
import { describe, expect, it, vi } from 'vitest';
import StatisticBoardDetailDialog from './StatisticBoardDetailDialog.vue';
import type { StatisticDetailColumn, StatisticDetailResponse } from '../types/api';

const columns: StatisticDetailColumn[] = [
  { key: 'iid', label: 'IID', sortable: true },
  { key: 'title', label: 'Title', sortable: true },
  { key: 'status', label: 'Status', minWidth: 160, sortable: false },
];

const detail: StatisticDetailResponse = {
  title: 'Issue detail',
  description: 'Rows',
  columns,
  records: [
    { iid: { label: '301', href: 'http://gitlab.example.com/-/issues/301' }, title: 'Issue A', status: 'open' },
    { iid: { label: '302' }, title: 'Issue B', status: null },
  ],
  total: 2,
  page: 1,
  size: 10,
};

function mountDialog(overrides: Partial<InstanceType<typeof StatisticBoardDetailDialog>['$props']> = {}) {
  return mount(StatisticBoardDetailDialog, {
    props: {
      modelValue: true,
      loading: false,
      detail,
      pagination: {
        page: 1,
        size: 10,
      },
      quickFilterValues: {},
      quickFilterInputDrafts: {},
      detailTableClass: 'custom-detail-table',
      detailCellValue: (record: Record<string, unknown>, column: StatisticDetailColumn) => {
        const value = record[column.key];
        return value == null || value === '' ? '-' : String(value);
      },
      onSortChange: vi.fn(),
      onCurrentChange: vi.fn(),
      onSizeChange: vi.fn(),
      onQuickFilterInputUpdate: vi.fn(),
      onQuickFilterChange: vi.fn(),
      onResetQuickFilters: vi.fn(),
      ...overrides,
    },
    global: {
      stubs: {
        ElDialog: {
          name: 'ElDialog',
          props: ['modelValue'],
          emits: ['update:modelValue'],
          template: '<section><slot name="header" :close="() => undefined" title-id="detail-title" title-class="dialog-title" /><slot /></section>',
        },
        ElTable: {
          name: 'ElTable',
          props: ['data', 'fit'],
          emits: ['sortChange'],
          template: '<div class="table"><slot /></div>',
        },
        ElTableColumn: {
          name: 'ElTableColumn',
          props: ['prop', 'label', 'sortable'],
          template:
            '<div class="column">{{ label }}: <slot :row="{ cells: { iid: { label: \'301\', href: \'http://gitlab.example.com/-/issues/301\', tags: [], labelColors: {} }, title: { label: \'Issue A\', href: null, tags: [\'Issue A\'], labelColors: {} }, status: { label: \'-\', href: null, tags: [], labelColors: {} } } }" /></div>',
        },
        ElPagination: {
          name: 'ElPagination',
          props: ['currentPage', 'pageSize', 'total'],
          emits: ['currentChange', 'sizeChange'],
          template: '<button class="pagination">{{ total }}</button>',
        },
        ElRadioGroup: {
          name: 'ElRadioGroup',
          props: ['modelValue'],
          emits: ['update:modelValue'],
          template: '<div class="collection-switch"><slot /></div>',
        },
        ElRadioButton: {
          name: 'ElRadioButton',
          props: ['value', 'title'],
          template: '<button class="collection-option">{{ title }}: <slot /></button>',
        },
      },
    },
  });
}

describe('StatisticBoardDetailDialog', () => {
  it('renders detail title, columns and formatted cell values', () => {
    const wrapper = mountDialog();

    expect(wrapper.findComponent({ name: 'ElTable' }).props('fit')).toBe(false);
    expect(wrapper.text()).toContain('Issue detail');
    expect(wrapper.text()).toContain('IID');
    expect(wrapper.text()).toContain('Title');
    expect(wrapper.text()).toContain('Issue A');
    expect(wrapper.text()).toContain('Status');
    expect(wrapper.text()).toContain('-');
    expect(wrapper.text()).toContain('2');
  });

  it('emits dialog and pagination events', async () => {
    const onSortChange = vi.fn();
    const onCurrentChange = vi.fn();
    const onSizeChange = vi.fn();
    const wrapper = mountDialog({ onSortChange, onCurrentChange, onSizeChange });

    await wrapper.findComponent({ name: 'ElDialog' }).vm.$emit('update:modelValue', false);
    await wrapper.findComponent({ name: 'ElTable' }).vm.$emit('sortChange', { prop: 'title', order: 'ascending' });
    await wrapper.findComponent({ name: 'ElPagination' }).vm.$emit('currentChange', 2);
    await wrapper.findComponent({ name: 'ElPagination' }).vm.$emit('sizeChange', 20);

    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([false]);
    expect(onSortChange).toHaveBeenCalledWith({ prop: 'title', order: 'ascending' });
    expect(onCurrentChange).toHaveBeenCalledWith(2);
    expect(onSizeChange).toHaveBeenCalledWith(20);
  });

  it('renders structured href values as external links', () => {
    const wrapper = mountDialog({
      detailCellValue: (record: Record<string, unknown>, column: StatisticDetailColumn) => record[column.key] as string,
    });

    const link = wrapper.find('a.detail-cell-link');
    expect(link.exists()).toBe(true);
    expect(link.text()).toBe('301');
    expect(link.attributes('href')).toBe('http://gitlab.example.com/-/issues/301');
    expect(link.attributes('target')).toBe('_blank');
    expect(link.attributes('rel')).toContain('noopener');
    expect(link.attributes('rel')).toContain('noreferrer');
  });

  it('falls back to plain text for structured values without href', () => {
    const wrapper = mountDialog({
      detail: {
        ...detail,
        columns: [{ key: 'status', label: 'Status', minWidth: 160, sortable: false }],
      },
      detailCellValue: () => ({ label: '302' }) as unknown as string,
    });

    expect(wrapper.find('a.detail-cell-link').exists()).toBe(false);
    expect(wrapper.text()).toContain('-');
  });

  it('renders the collection switcher and reports the selected collection', async () => {
    const onCollectionChange = vi.fn();
    const wrapper = mountDialog({
      collection: 'COUNTED',
      onCollectionChange,
      detail: {
        ...detail,
        collections: [
          { key: 'COUNTED', label: '计数集合', description: '贡献该指标的议题。' },
          { key: 'NUMERATOR', label: '分子集合', description: '达标议题。' },
        ],
        collection: 'COUNTED',
      },
    });

    const group = wrapper.findComponent({ name: 'ElRadioGroup' });
    expect(group.exists()).toBe(true);
    expect(wrapper.text()).toContain('计数集合');
    expect(wrapper.text()).toContain('分子集合');
    expect(wrapper.text()).toContain('贡献该指标的议题。');

    await group.vm.$emit('update:modelValue', 'NUMERATOR');

    expect(onCollectionChange).toHaveBeenCalledWith('NUMERATOR');
  });

  it('hides the collection switcher for single-collection boards', () => {
    const wrapper = mountDialog({
      detail: {
        ...detail,
        collections: [{ key: 'DETAIL', label: '明细', description: '当前指标对应的明细记录。' }],
        collection: 'DETAIL',
      },
    });

    expect(wrapper.findComponent({ name: 'ElRadioGroup' }).exists()).toBe(false);
    expect(wrapper.text()).not.toContain('当前指标对应的明细记录。');
  });
});
