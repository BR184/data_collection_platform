import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import SmartSelect from './SmartSelect.vue';

describe('SmartSelect', () => {
  it('disables keyword reservation so picking an option clears the filter text', () => {
    const wrapper = mount(SmartSelect, {
      props: {
        modelValue: [],
        options: [{ label: '杨晓雨', value: '杨晓雨' }],
        multiple: true,
      },
      global: {
        plugins: [ElementPlus],
      },
    });

    const select = wrapper.findComponent({ name: 'ElSelect' });
    expect(select.exists()).toBe(true);
    expect(select.props('reserveKeyword')).toBe(false);
  });
});
