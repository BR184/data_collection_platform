import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import RecordTableFilterFields from './RecordTableFilterFields.vue';
import RecordTableFilterFieldRenderer from './RecordTableFilterFieldRenderer.vue';

describe('RecordTableFilterFields', () => {
  it('renders a filter group with committed values and draft input values', () => {
    const wrapper = mount(RecordTableFilterFields, {
      props: {
        filters: [
          { key: 'keyword', label: '关键词', type: 'input' },
          { key: 'status', label: '状态', type: 'select', options: [{ label: '打开', value: 'open' }] },
        ],
        filterValues: {
          keyword: 'applied',
          status: 'open',
        },
        inputDrafts: {
          keyword: 'draft',
        },
        keywordFieldVisible: true,
        defaultSelectWidth: 180,
      },
      global: {
        plugins: [ElementPlus],
      },
    });

    const renderers = wrapper.findAllComponents(RecordTableFilterFieldRenderer);

    expect(renderers).toHaveLength(2);
    expect(renderers[0].props('modelValue')).toBe('applied');
    expect(renderers[0].props('inputValue')).toBe('draft');
    expect(renderers[0].props('inputClass')).toEqual({ 'record-filter-main-keyword': true });
    expect(renderers[0].props('defaultInputWidth')).toBe(260);
    expect(renderers[1].props('modelValue')).toBe('open');
    expect(renderers[1].props('defaultSelectWidth')).toBe(180);
  });

  it('forwards field renderer events without table-level duplication', () => {
    const wrapper = mount(RecordTableFilterFields, {
      props: {
        filters: [{ key: 'keyword', label: '关键词', type: 'input' }],
        filterValues: {},
        inputDrafts: {},
      },
      global: {
        plugins: [ElementPlus],
      },
    });

    const renderer = wrapper.findComponent(RecordTableFilterFieldRenderer);
    renderer.vm.$emit('input-update', 'keyword', 'draft');
    renderer.vm.$emit('input-change', 'keyword', 'committed');
    renderer.vm.$emit('input-search', 'keyword');
    renderer.vm.$emit('input-clear', 'keyword');
    renderer.vm.$emit('filter-change', 'keyword', 'value');

    expect(wrapper.emitted('input-update')).toEqual([['keyword', 'draft']]);
    expect(wrapper.emitted('input-change')).toEqual([['keyword', 'committed']]);
    expect(wrapper.emitted('input-search')).toEqual([['keyword']]);
    expect(wrapper.emitted('input-clear')).toEqual([['keyword']]);
    expect(wrapper.emitted('filter-change')).toEqual([['keyword', 'value']]);
  });
});
