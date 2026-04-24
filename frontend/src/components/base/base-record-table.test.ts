import { afterEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { nextTick } from 'vue';
import ElementPlus from 'element-plus';
import BaseRecordTable from './BaseRecordTable.vue';
import BaseSearchInput from './BaseSearchInput.vue';
import { resolveRecordTableCellDisplay } from './base-record-table-cell';

describe('BaseRecordTable', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('emits query with the current keyword when query button is clicked', async () => {
    const wrapper = mount(BaseRecordTable, {
      props: {
        columns: [],
        rows: [],
        page: 1,
        pageSize: 20,
        total: 0,
      },
      global: {
        plugins: [ElementPlus],
      },
    });

    const input = wrapper.find('input');
    await input.setValue('review');
    const buttons = wrapper.findAll('button');
    const queryButton = buttons.find((button) => button.text() === '查询');

    expect(queryButton).toBeTruthy();
    await queryButton!.trigger('click');

    expect(wrapper.emitted('query')).toBeTruthy();
    expect(wrapper.emitted('query')?.[0]).toEqual(['review']);
  });

  it('commits primary keyword draft before query is triggered', async () => {
    const wrapper = mount(BaseRecordTable, {
      props: {
        columns: [],
        rows: [],
        page: 1,
        pageSize: 20,
        total: 0,
        primaryFilters: [
          {
            key: 'keyword',
            label: '关键字',
            type: 'input',
            placeholder: '搜索标题',
          },
        ],
        filterValues: {
          keyword: '',
        },
      },
      global: {
        plugins: [ElementPlus],
      },
    });

    const input = wrapper.find('input');
    await input.setValue('ceshi');
    const queryButton = wrapper.find('button.el-button--primary');

    expect(queryButton.exists()).toBe(true);
    await queryButton.trigger('click');

    expect(wrapper.emitted('filter-change')?.at(-1)).toEqual([{ key: 'keyword', value: 'ceshi' }]);
    expect(wrapper.emitted('query')).toEqual([['ceshi']]);
  });

  it('debounces standalone keyword auto search without forcing a manual query', async () => {
    vi.useFakeTimers();

    const wrapper = mount(BaseRecordTable, {
      props: {
        columns: [],
        rows: [],
        page: 1,
        pageSize: 20,
        total: 0,
        keywordAutoSearch: true,
        keywordAutoSearchDelay: 250,
      },
      global: {
        plugins: [ElementPlus],
      },
    });

    const input = wrapper.find('input');
    await input.setValue('ceshi');

    expect(wrapper.emitted('search')).toBeFalsy();
    await vi.advanceTimersByTimeAsync(250);

    expect(wrapper.emitted('search')).toEqual([['ceshi']]);
    expect(wrapper.emitted('query')).toBeFalsy();
  });

  it('debounces keyword filter updates while leaving manual query available for other conditions', async () => {
    vi.useFakeTimers();

    const wrapper = mount(BaseRecordTable, {
      props: {
        columns: [],
        rows: [],
        page: 1,
        pageSize: 20,
        total: 0,
        keywordAutoSearch: true,
        keywordAutoSearchDelay: 250,
        primaryFilters: [
          {
            key: 'keyword',
            label: '关键字',
            type: 'input',
            placeholder: '搜索标题',
          },
        ],
        filterValues: {
          keyword: '',
        },
      },
      global: {
        plugins: [ElementPlus],
      },
    });

    const input = wrapper.find('input');
    await input.setValue('cs');

    await vi.advanceTimersByTimeAsync(250);

    expect(wrapper.emitted('filter-change')?.at(-1)).toEqual([{ key: 'keyword', value: 'cs' }]);
    expect(wrapper.emitted('query')).toBeFalsy();
  });

  it('normalizes tags and links through the extracted cell helper', () => {
    const tagCell = resolveRecordTableCellDisplay(['待处理', { label: '高优', type: 'danger' }]);
    const singleTagCell = resolveRecordTableCellDisplay([{ label: '进行中', type: 'warning' }]);
    const linkCell = resolveRecordTableCellDisplay({ href: 'https://example.com/detail', label: '查看详情' });

    expect(tagCell.tags).toEqual([
      { label: '待处理' },
      { label: '高优', type: 'danger' },
    ]);
    expect(singleTagCell.primaryTag).toEqual({ label: '进行中', type: 'warning' });
    expect(linkCell.link).toEqual({ href: 'https://example.com/detail', label: '查看详情' });
  });

  it('emits empty search when standalone keyword is cleared during auto search mode', async () => {
    const wrapper = mount(BaseRecordTable, {
      props: {
        columns: [],
        rows: [],
        page: 1,
        pageSize: 20,
        total: 0,
        keyword: '待清空',
        keywordAutoSearch: true,
      },
      global: {
        plugins: [ElementPlus],
      },
    });

    wrapper.findComponent(BaseSearchInput).vm.$emit('clear');
    await nextTick();

    expect(wrapper.emitted('search')).toEqual([['']]);
  });

  it('delays the loading mask until the configured timeout', async () => {
    vi.useFakeTimers();

    const wrapper = mount(BaseRecordTable, {
      props: {
        columns: [],
        rows: [],
        loading: true,
        loadingDelay: 250,
        page: 1,
        pageSize: 20,
        total: 0,
      },
      global: {
        plugins: [ElementPlus],
      },
    });

    await nextTick();
    expect(wrapper.find('.el-loading-mask').exists()).toBe(false);

    await vi.advanceTimersByTimeAsync(250);
    await nextTick();
    expect(wrapper.find('.el-loading-mask').exists()).toBe(true);
  });
});
