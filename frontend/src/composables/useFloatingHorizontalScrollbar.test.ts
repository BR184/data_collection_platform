import { mount } from '@vue/test-utils';
import { defineComponent, h, ref } from 'vue';
import { describe, expect, it } from 'vitest';
import { useFloatingHorizontalScrollbar } from './useFloatingHorizontalScrollbar';

interface ShellMetrics {
  shellRect: Partial<DOMRect>;
  bodyRect: Partial<DOMRect>;
  bodyClientWidth: number;
  bodyScrollWidth: number;
  bodyClientHeight?: number;
  bodyScrollHeight?: number;
}

function applyMetrics(element: HTMLElement, rect: Partial<DOMRect>, sizes: Record<string, number>) {
  element.getBoundingClientRect = () =>
    ({
      left: 0,
      right: 0,
      top: 0,
      bottom: 0,
      width: 0,
      height: 0,
      x: 0,
      y: 0,
      toJSON: () => ({}),
      ...rect,
    }) as DOMRect;
  for (const [key, value] of Object.entries(sizes)) {
    Object.defineProperty(element, key, { value, configurable: true });
  }
}

function mountHarness(positioning: 'viewport' | 'container', metrics: ShellMetrics) {
  const tableShellRef = ref<HTMLElement>();
  let api: ReturnType<typeof useFloatingHorizontalScrollbar> | undefined;

  const Harness = defineComponent({
    setup() {
      api = useFloatingHorizontalScrollbar({ tableShellRef, positioning });
      return () =>
        h('div', { ref: tableShellRef, class: 'shell' }, [h('div', { class: 'el-scrollbar__wrap' })]);
    },
  });

  const wrapper = mount(Harness, { attachTo: document.body });
  const shell = wrapper.element as HTMLElement;
  const body = shell.querySelector<HTMLElement>('.el-scrollbar__wrap') as HTMLElement;
  applyMetrics(shell, metrics.shellRect, {});
  applyMetrics(body, metrics.bodyRect, {
    clientWidth: metrics.bodyClientWidth,
    scrollWidth: metrics.bodyScrollWidth,
    clientHeight: metrics.bodyClientHeight ?? 300,
    scrollHeight: metrics.bodyScrollHeight ?? 300,
  });
  return { wrapper, get api() { return api!; } };
}

describe('useFloatingHorizontalScrollbar', () => {
  const baseMetrics: ShellMetrics = {
    shellRect: { left: 100, right: 600, top: 0, bottom: 500, width: 500, height: 500 },
    bodyRect: { left: 112, right: 592, top: 40, bottom: 340, width: 480, height: 300 },
    bodyClientWidth: 480,
    bodyScrollWidth: 1200,
  };

  it('docks the bar inside the module when positioning is container', async () => {
    const { wrapper, api } = mountHarness('container', baseMetrics);
    api.updateHorizontalScrollbar();
    await wrapper.vm.$nextTick();

    expect(api.isFloatingScrollbarVisible.value).toBe(true);
    expect(api.floatingScrollbarStyle.value.position).toBe('absolute');
    expect(api.floatingScrollbarStyle.value.left).toBe('12px');
    expect(api.floatingScrollbarStyle.value.width).toBe('480px');
    expect(api.floatingScrollbarStyle.value.bottom).toBe('0px');
    wrapper.unmount();
  });

  it('keeps the viewport-fixed layout for the other shared tables', async () => {
    const { wrapper, api } = mountHarness('viewport', baseMetrics);
    api.updateHorizontalScrollbar();
    await wrapper.vm.$nextTick();

    expect(api.isFloatingScrollbarVisible.value).toBe(true);
    expect(api.floatingScrollbarStyle.value.position).toBeUndefined();
    expect(api.floatingScrollbarStyle.value.left).toBe('120px');
    expect(api.floatingScrollbarStyle.value.width).toBe('460px');
    expect(api.floatingScrollbarStyle.value.bottom).toBe('12px');
    wrapper.unmount();
  });

  it('hides the container bar when the table has no horizontal overflow', async () => {
    const { wrapper, api } = mountHarness('container', {
      ...baseMetrics,
      bodyScrollWidth: 480,
    });
    api.updateHorizontalScrollbar();
    await wrapper.vm.$nextTick();

    expect(api.hasHorizontalOverflow.value).toBe(false);
    expect(api.isFloatingScrollbarVisible.value).toBe(false);
    wrapper.unmount();
  });
});
