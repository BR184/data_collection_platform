import { mount } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { nextTick } from 'vue';
import SmartTableHeader from './SmartTableHeader.vue';

class ResizeObserverStub {
  observe() {}
  disconnect() {}
}

describe('SmartTableHeader', () => {
  beforeEach(() => {
    vi.stubGlobal('ResizeObserver', ResizeObserverStub);
    vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockImplementation(function (this: HTMLElement) {
      return this.classList.contains('smart-table-header') ? 66 : 0;
    });
    vi.spyOn(HTMLElement.prototype, 'scrollWidth', 'get').mockImplementation(function (this: HTMLElement) {
      return this.textContent === '是否达标' ? 52 : 26;
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('带帮助图标的窄表头按语义分行，避免图标重叠', async () => {
    const wrapper = mount(SmartTableHeader, {
      props: {
        label: '是否达标',
        tooltip: '达标判断说明',
      },
      global: {
        stubs: {
          ElIcon: { template: '<span><slot /></span>' },
          ElTooltip: { template: '<span><slot /></span>' },
          QuestionFilled: true,
        },
      },
    });

    await nextTick();
    await nextTick();

    expect(wrapper.get('.smart-table-header').classes()).toContain('is-stacked');
    expect(wrapper.findAll('.smart-table-header__line').map((line) => line.text())).toEqual(['是否', '达标']);
    expect(wrapper.findAll('.smart-table-header__help-trigger')).toHaveLength(1);
  });
});
