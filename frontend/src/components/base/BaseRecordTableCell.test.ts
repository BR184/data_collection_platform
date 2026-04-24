import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import BaseRecordTableCell from './BaseRecordTableCell.vue';

describe('BaseRecordTableCell', () => {
  it('renders text values through the shared cell display helper', () => {
    const wrapper = mount(BaseRecordTableCell, {
      props: {
        column: { key: 'title', label: '标题' },
        value: '评审记录',
      },
      global: {
        plugins: [ElementPlus],
      },
    });

    expect(wrapper.find('.record-table-text').text()).toBe('评审记录');
  });

  it('renders tag arrays and links without table-level branches', () => {
    const tagsWrapper = mount(BaseRecordTableCell, {
      props: {
        column: { key: 'status', label: '状态', type: 'tags' },
        value: [{ label: '待处理', type: 'warning' }],
      },
      global: {
        plugins: [ElementPlus],
      },
    });
    expect(tagsWrapper.find('.record-table-tags').exists()).toBe(true);
    expect(tagsWrapper.text()).toContain('待处理');

    const linkWrapper = mount(BaseRecordTableCell, {
      props: {
        column: { key: 'detail', label: '详情', type: 'link' },
        value: { label: '查看', href: 'https://example.com/detail' },
      },
      global: {
        plugins: [ElementPlus],
      },
    });
    const link = linkWrapper.find('a.record-table-link');
    expect(link.text()).toBe('查看');
    expect(link.attributes('href')).toBe('https://example.com/detail');
  });
});
