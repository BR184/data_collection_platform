import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import ExportActionMenu from './ExportActionMenu.vue';

const DropdownStub = {
  name: 'ElDropdown',
  emits: ['command'],
  template: '<div><slot /><slot name="dropdown" /></div>',
};

function mountMenu(actions: Array<{ key: string; label: string }>) {
  return mount(ExportActionMenu, {
    props: { actions },
    global: {
      stubs: {
        ElButton: {
          props: ['disabled', 'loading'],
          emits: ['click'],
          template: '<button type="button" :disabled="disabled" @click="$emit(\'click\', $event)"><slot /></button>',
        },
        ElDropdown: DropdownStub,
        ElDropdownMenu: { template: '<div><slot /></div>' },
        ElDropdownItem: { props: ['command', 'disabled'], template: '<span><slot /></span>' },
        ElIcon: { template: '<span><slot /></span>' },
      },
    },
  });
}

describe('ExportActionMenu', () => {
  it('renders one green-style export action as a direct button', async () => {
    const wrapper = mountMenu([{ key: 'summary', label: '导出汇总' }]);

    expect(wrapper.text()).toContain('导出汇总');
    expect(wrapper.findComponent(DropdownStub).exists()).toBe(false);
    await wrapper.get('button').trigger('click');
    expect(wrapper.emitted('select')).toEqual([['summary']]);
  });

  it('consolidates multiple export actions into one dropdown command source', async () => {
    const wrapper = mountMenu([
      { key: 'summary', label: '导出阶段统计' },
      { key: 'cc-details', label: '导出 CC 详细数据' },
      { key: 'htgc-details', label: '导出 HTGC 详细数据' },
    ]);

    expect(wrapper.text()).toContain('导出...');
    const dropdown = wrapper.findComponent(DropdownStub);
    expect(dropdown.exists()).toBe(true);
    dropdown.vm.$emit('command', 'cc-details');
    await wrapper.vm.$nextTick();
    expect(wrapper.emitted('select')).toEqual([['cc-details']]);
  });
});
