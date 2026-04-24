import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import BaseSearchInput from './BaseSearchInput.vue';
import SmartSelect from './SmartSelect.vue';
import RecordTableFilterFieldRenderer from './RecordTableFilterFieldRenderer.vue';

describe('RecordTableFilterFieldRenderer', () => {
  it('renders an input filter and forwards draft events', async () => {
    const wrapper = mount(RecordTableFilterFieldRenderer, {
      props: {
        filter: {
          key: 'keyword',
          label: '关键字',
          type: 'input',
          placeholder: '搜索标题',
        },
        modelValue: '',
        inputValue: 'draft',
        inputClass: 'record-filter-main-keyword',
        defaultInputWidth: 260,
      },
      global: {
        plugins: [ElementPlus],
      },
    });

    const input = wrapper.findComponent(BaseSearchInput);
    expect(input.exists()).toBe(true);
    expect(input.props('modelValue')).toBe('draft');
    expect(input.attributes('style')).toContain('260px');
    expect(input.classes()).toContain('record-filter-main-keyword');

    input.vm.$emit('update:modelValue', 'next');
    input.vm.$emit('change', 'committed');
    input.vm.$emit('search');
    input.vm.$emit('clear');

    expect(wrapper.emitted('input-update')).toEqual([['keyword', 'next']]);
    expect(wrapper.emitted('input-change')).toEqual([['keyword', 'committed']]);
    expect(wrapper.emitted('input-search')).toEqual([['keyword']]);
    expect(wrapper.emitted('input-clear')).toEqual([['keyword']]);
  });

  it('renders a select filter and forwards filter changes', () => {
    const wrapper = mount(RecordTableFilterFieldRenderer, {
      props: {
        filter: {
          key: 'status',
          label: '状态',
          type: 'select',
          options: [{ label: '打开', value: 'open' }],
          selectMode: 'compact',
        },
        modelValue: 'open',
        defaultSelectWidth: 180,
      },
      global: {
        plugins: [ElementPlus],
      },
    });

    const select = wrapper.findComponent(SmartSelect);
    expect(select.exists()).toBe(true);
    expect(select.props('modelValue')).toBe('open');
    expect(select.props('compact')).toBe(true);
    expect(select.attributes('style')).toContain('180px');

    select.vm.$emit('change', 'closed');

    expect(wrapper.emitted('filter-change')).toEqual([['status', 'closed']]);
  });
});
