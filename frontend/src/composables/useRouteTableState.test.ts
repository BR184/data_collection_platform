import { mount } from '@vue/test-utils';
import { defineComponent, nextTick } from 'vue';
import { createRouter, createWebHashHistory } from 'vue-router';
import { describe, expect, it, vi } from 'vitest';
import { useRouteTableState } from './useRouteTableState';

function createHarness(loader: () => Promise<void> = vi.fn(async () => undefined)) {
  const Harness = defineComponent({
    setup() {
      const tableState = useRouteTableState({
        watchedQueryKeys: ['moduleName'],
        minLoadingMs: 0,
      });
      tableState.bindLoader(loader);
      return {};
    },
    template: '<div />',
  });
  const router = createRouter({
    history: createWebHashHistory(),
    routes: [{ path: '/records', component: Harness }],
  });
  return { Harness, router, loader };
}

async function flushRouteWatchers() {
  await nextTick();
  await Promise.resolve();
  await nextTick();
}

describe('useRouteTableState', () => {
  it('does not reload when unrelated route query keys change', async () => {
    const { Harness, router, loader } = createHarness();
    await router.push('/records?page=1&moduleName=alpha');
    await router.isReady();
    const wrapper = mount(Harness, { global: { plugins: [router] } });
    await flushRouteWatchers();

    await router.replace('/records?page=1&moduleName=alpha&detailVisible=true');
    await flushRouteWatchers();

    expect(loader).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });

  it('reloads when watched route query keys change', async () => {
    const { Harness, router, loader } = createHarness();
    await router.push('/records?page=1&moduleName=alpha');
    await router.isReady();
    const wrapper = mount(Harness, { global: { plugins: [router] } });
    await flushRouteWatchers();

    await router.replace('/records?page=1&moduleName=beta');
    await flushRouteWatchers();

    expect(loader).toHaveBeenCalledTimes(2);
    wrapper.unmount();
  });

  it('keeps pagination and sorting query keys watched by default', async () => {
    const { Harness, router, loader } = createHarness();
    await router.push('/records?page=1&moduleName=alpha');
    await router.isReady();
    const wrapper = mount(Harness, { global: { plugins: [router] } });
    await flushRouteWatchers();

    await router.replace('/records?page=2&moduleName=alpha');
    await flushRouteWatchers();

    expect(loader).toHaveBeenCalledTimes(2);
    wrapper.unmount();
  });
});
