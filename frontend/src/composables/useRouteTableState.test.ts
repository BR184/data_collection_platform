import { mount } from '@vue/test-utils';
import { defineComponent, nextTick, ref } from 'vue';
import { createRouter, createWebHashHistory } from 'vue-router';
import { describe, expect, it, vi } from 'vitest';
import { useRouteTableState, type RouteTableLoadContext, type RouteTableLoadOutcome } from './useRouteTableState';

type Loader = (context: RouteTableLoadContext) => Promise<string>;

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function createHarness(loader: Loader = vi.fn(async () => 'loaded')) {
  const observed = {
    rows: ref(''),
    errors: ref<string[]>([]),
    contexts: [] as RouteTableLoadContext[],
    reload: null as null | (() => Promise<RouteTableLoadOutcome>),
    patchQuery: null as null | ((patch: { keyword?: string }) => Promise<void>),
    debouncedPatchQuery: null as null | ((patch: { keyword?: string }) => void),
  };
  const Harness = defineComponent({
    setup() {
      const tableState = useRouteTableState({ minLoadingMs: 0 });
      tableState.bindLoader(async (context) => {
        observed.contexts.push(context);
        return loader(context);
      }, (result) => {
        observed.rows.value = result;
      }, (error) => {
        observed.errors.value.push(String(error));
      });
      observed.reload = tableState.reload;
      observed.patchQuery = tableState.patchQuery;
      observed.debouncedPatchQuery = tableState.debouncedPatchQuery;
      return {};
    },
    template: '<div />',
  });
  const router = createRouter({
    history: createWebHashHistory(),
    routes: [{ path: '/records', component: Harness }],
  });
  return { Harness, router, observed };
}

async function flushRouteWatchers() {
  await nextTick();
  await Promise.resolve();
  await nextTick();
}

async function mountHarness(loader?: Loader, path = '/records') {
  const harness = createHarness(loader);
  await harness.router.push(path);
  await harness.router.isReady();
  const wrapper = mount(harness.Harness, { global: { plugins: [harness.router] } });
  await flushRouteWatchers();
  return { ...harness, wrapper };
}

describe('useRouteTableState', () => {
  it('does not reload when unrelated route query keys change', async () => {
    const loader = vi.fn(async () => 'loaded');
    const { router, wrapper, observed } = await mountHarness(loader, '/records?page=1&keyword=alpha');

    await router.replace('/records?page=1&keyword=alpha&detailVisible=true');
    await flushRouteWatchers();

    expect(loader).toHaveBeenCalledTimes(1);
    expect(observed.rows.value).toBe('loaded');
    wrapper.unmount();
  });

  it('reloads when watched route query keys change', async () => {
    const loader = vi.fn(async (context: RouteTableLoadContext) => String(context.query.keyword ?? ''));
    const { router, wrapper, observed } = await mountHarness(loader, '/records?page=1&keyword=alpha');

    await router.replace('/records?page=1&keyword=beta');
    await flushRouteWatchers();

    expect(loader).toHaveBeenCalledTimes(2);
    expect(observed.rows.value).toBe('beta');
    wrapper.unmount();
  });

  it('keeps pagination and sorting query keys watched by default', async () => {
    const loader = vi.fn(async (context: RouteTableLoadContext) => String(context.query.page ?? ''));
    const { router, wrapper, observed } = await mountHarness(loader, '/records?page=1&keyword=alpha');

    await router.replace('/records?page=2&keyword=alpha');
    await flushRouteWatchers();

    expect(loader).toHaveBeenCalledTimes(2);
    expect(observed.rows.value).toBe('2');
    wrapper.unmount();
  });

  it('supports an explicit initial reload without changing the default immediate contract', async () => {
    const loader: Loader = vi.fn(async () => 'manual');
    const harness = createHarness(loader);
    const Harness = defineComponent({
      setup() {
        const state = useRouteTableState({ immediate: false, minLoadingMs: 0 });
        state.bindLoader(async (context) => {
          harness.observed.contexts.push(context);
          return loader(context);
        }, (result) => { harness.observed.rows.value = result; });
        harness.observed.reload = state.reload;
        return {};
      },
      template: '<div />',
    });
    harness.router.addRoute({ path: '/manual', component: Harness });
    await harness.router.push('/manual');
    await harness.router.isReady();
    const wrapper = mount(Harness, { global: { plugins: [harness.router] } });
    await flushRouteWatchers();

    expect(loader).not.toHaveBeenCalled();
    await harness.observed.reload?.();
    expect(loader).toHaveBeenCalledTimes(1);
    expect(harness.observed.rows.value).toBe('manual');
    wrapper.unmount();
  });

  it('commits only the newest successful result when requests finish in reverse order', async () => {
    const requests = [deferred<string>(), deferred<string>()];
    let index = 0;
    const loader: Loader = () => requests[index++]!.promise;
    const { wrapper, observed } = await mountHarness(loader);

    const newerRun = observed.reload!();
    requests[1]!.resolve('new');
    await newerRun;
    requests[0]!.resolve('old');
    await flushRouteWatchers();

    expect(observed.rows.value).toBe('new');
    wrapper.unmount();
  });

  it('ignores an old failure after a newer query has committed', async () => {
    const requests = [deferred<string>(), deferred<string>()];
    let index = 0;
    const loader: Loader = () => requests[index++]!.promise;
    const { wrapper, observed } = await mountHarness(loader);

    const newerRun = observed.reload!();
    requests[1]!.resolve('current rows');
    await newerRun;
    requests[0]!.reject(new Error('stale failure'));
    await flushRouteWatchers();

    expect(observed.rows.value).toBe('current rows');
    expect(observed.errors.value).toEqual([]);
    wrapper.unmount();
  });

  it('invalidates old work as soon as a route query intent is patched', async () => {
    const old = deferred<string>();
    const next = deferred<string>();
    let index = 0;
    const loader: Loader = () => [old.promise, next.promise][index++]!;
    const { wrapper, observed } = await mountHarness(loader, '/records?keyword=old');
    const oldContext = observed.contexts[0]!;

    const navigation = observed.patchQuery!({ keyword: 'new' });
    expect(oldContext.isCurrent()).toBe(false);
    old.resolve('old rows');
    await navigation;
    await flushRouteWatchers();
    expect(observed.contexts).toHaveLength(2);

    next.resolve('new rows');
    await flushRouteWatchers();
    expect(observed.rows.value).toBe('new rows');
    wrapper.unmount();
  });

  it('keeps an immutable query snapshot and discards commits after unmount', async () => {
    const request = deferred<string>();
    const loader: Loader = () => request.promise;
    const { wrapper, observed } = await mountHarness(loader, '/records?keyword=frozen');
    const context = observed.contexts[0]!;

    expect(context.query.keyword).toBe('frozen');
    expect(Object.isFrozen(context.query)).toBe(true);
    wrapper.unmount();
    request.resolve('after unmount');
    await flushRouteWatchers();

    expect(observed.rows.value).toBe('');
  });

  it('reports a committed outcome only for the load that actually commits', async () => {
    const loader = vi.fn(async () => 'fresh');
    const { wrapper, observed } = await mountHarness(loader);

    expect(await observed.reload!()).toEqual({ status: 'committed' });
    expect(observed.rows.value).toBe('fresh');
    wrapper.unmount();
  });

  it('reports a superseded outcome for a result replaced by a newer request', async () => {
    const requests = [deferred<string>(), deferred<string>(), deferred<string>()];
    let index = 0;
    const loader: Loader = () => requests[index++]!.promise;
    const { wrapper, observed } = await mountHarness(loader);

    const olderRun = observed.reload!();
    const newerRun = observed.reload!();
    requests[2]!.resolve('newest');
    expect(await newerRun).toEqual({ status: 'committed' });
    requests[1]!.resolve('stale');
    expect(await olderRun).toEqual({ status: 'superseded' });

    expect(observed.rows.value).toBe('newest');
    wrapper.unmount();
  });

  it('reports a failed outcome and notifies the failure channel when a load fails', async () => {
    const loader: Loader = vi.fn(async () => {
      throw new Error('boom');
    });
    const { wrapper, observed } = await mountHarness(loader);

    const outcome = await observed.reload!();

    expect(outcome).toEqual({ status: 'failed', error: expect.any(Error) });
    expect(observed.errors.value.at(-1)).toBe('Error: boom');
    wrapper.unmount();
  });

  it('applies a pending query intent before loading so a reload cannot commit the superseded query', async () => {
    const loader = vi.fn(async (context: RouteTableLoadContext) => String(context.query.keyword ?? ''));
    const { wrapper, observed } = await mountHarness(loader, '/records?keyword=old');
    expect(observed.rows.value).toBe('old');

    observed.debouncedPatchQuery!({ keyword: 'new' });
    const outcome = await observed.reload!();
    await flushRouteWatchers();

    expect(outcome).toEqual({ status: 'committed' });
    expect(observed.rows.value).toBe('new');
    expect(observed.contexts.at(-1)!.query.keyword).toBe('new');
    wrapper.unmount();
  });
});
