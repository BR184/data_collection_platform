import { describe, expect, it, vi } from 'vitest';
import { HttpRequestError } from '../api-client/request';
import { useAcceptedPageView, SupersededPageReadError } from './useAcceptedPageView';
import type { ReadViewMetadata, ReadViewTransport } from '../types/api/read-view';
import { authState } from './auth-state';
import { flushPromises } from '@vue/test-utils';

function metadata(id: string): ReadViewMetadata {
  return { readContextId: id, pageKey: 'page', scopeKey: 'scope', sourceVersion: id,
    businessDate: '2026-10-13', leaseExpiresAt: '2026-10-13T12:10:00Z', dataAsOf: '2026-10-13T12:00:00Z',
    pendingUpdates: 0, sourceState: 'CURRENT', escalated: false };
}

function setup() {
  let next = 0;
  const views = {
    acquire: vi.fn(async () => metadata(`view-${++next}`)),
    renew: vi.fn(async (id: string) => metadata(id)), release: vi.fn(async (_id: string) => undefined),
  };
  const load = vi.fn(async (scope: Readonly<Record<string, string>>, input: ReadViewTransport) => {
    input.onReadView(metadata(input.readContextId));
    return { main: scope.name, options: [scope.name], title: scope.name };
  });
  return { views, load, page: useAcceptedPageView({ pageKey: 'page', views, load,
    now: () => Date.parse('2026-10-13T12:01:00Z') }) };
}

describe('accepted page view', () => {
  it('bounds all preparation to five seconds and preserves the complete accepted frame on timeout', async () => {
    vi.useFakeTimers();
    const { page, load, views } = setup();
    try {
      await page.prepare({ name: 'old' });
      load.mockImplementationOnce(() => new Promise(() => undefined));
      const preparing = page.prepare({ name: 'new' });
      await flushPromises(); await vi.advanceTimersByTimeAsync(4_999);
      expect(page.preparing.value).toBe(true); expect(page.accepted.value?.data.main).toBe('old');
      await vi.advanceTimersByTimeAsync(1);
      expect(await preparing).toBe(false); expect(page.preparing.value).toBe(false);
      expect(page.accepted.value?.data.main).toBe('old');
      expect(String(page.lastError.value)).toContain('等待超时');
      expect(views.release).toHaveBeenCalledWith('view-2');
    } finally { page.dispose(); vi.useRealTimers(); }
  });
  it('releases an acquisition that arrives after the deadline without replacing the old frame', async () => {
    vi.useFakeTimers();
    const { page, views } = setup();
    try {
      await page.prepare({ name: 'old' });
      let resolve!: (value: ReadViewMetadata) => void;
      views.acquire.mockImplementationOnce(() => new Promise(res => { resolve = res; }));
      const preparing = page.prepare({ name: 'new' });
      await vi.advanceTimersByTimeAsync(5_000); expect(await preparing).toBe(false);
      resolve(metadata('late-acquired')); await flushPromises();
      expect(views.release).toHaveBeenCalledWith('late-acquired');
      expect(page.accepted.value?.data.main).toBe('old');
    } finally { page.dispose(); vi.useRealTimers(); }
  });
  it('resolves a default scope within the same complete source version before accepting any data', async () => {
    const parent = metadata('default-parent');
    const child = { ...parent, readContextId: 'scoped-child', scopeKey: 'resolved-scope' };
    const views = { acquire: vi.fn(async () => parent), renew: vi.fn(async () => child),
      release: vi.fn(async () => undefined), fork: vi.fn(async () => child) };
    const scopes: Readonly<Record<string, string>>[] = [];
    const page = useAcceptedPageView({ pageKey: 'page', views,
      now: () => Date.parse('2026-10-13T12:01:00Z'),
      load: async (scope, read) => {
        scopes.push(scope); read.onReadView(read.readContextId === parent.readContextId ? parent : child);
        return { selectedMilestone: 'complete-default', requestedMilestone: scope.milestoneBusinessKey };
      },
      resolveScope: (data, scope) => scope.milestoneBusinessKey ? scope : { milestoneBusinessKey: data.selectedMilestone },
    });
    expect(await page.prepare({})).toBe(true);
    expect(scopes).toEqual([{}, { milestoneBusinessKey: 'complete-default' }]);
    expect(views.fork).toHaveBeenCalledWith('default-parent', { milestoneBusinessKey: 'complete-default' }, expect.any(String));
    expect(page.accepted.value?.metadata.sourceVersion).toBe(parent.sourceVersion);
    expect(page.accepted.value?.data.requestedMilestone).toBe('complete-default');
    expect(views.release).toHaveBeenCalledWith('default-parent'); page.dispose();
  });
  it('switching users clears the old frame and does not reuse the previous user query', async () => {
    const previousUser = authState.currentUser;
    authState.currentUser = { ...previousUser, username: 'first-user', authenticated: true };
    const { page, views } = setup(); page.start();
    try {
      await page.prepare({ name: 'private-first-query' });
      authState.currentUser = { ...authState.currentUser, username: 'second-user' };
      await flushPromises();
      expect(page.accepted.value).toBeNull();
      expect(views.acquire).toHaveBeenCalledTimes(1);
      expect(views.release).toHaveBeenCalledWith('view-1');
    } finally { page.dispose(); authState.currentUser = previousUser; }
  });
  it('permission changes clear private data immediately and acquire a fresh authorized frame', async () => {
    const previousUser = authState.currentUser;
    authState.currentUser = { ...previousUser, username: 'same-user', authenticated: true,
      permissions: ['page.read', 'admin.details'], roleCodes: ['SUPER_ADMIN'] };
    const { page, views } = setup(); page.start();
    try {
      await page.prepare({ name: 'old' });
      authState.currentUser = { ...authState.currentUser, permissions: ['page.read'], roleCodes: ['READER'] };
      expect(page.accepted.value).toBeNull();
      await flushPromises();
      expect(views.release).toHaveBeenCalledWith('view-1');
      expect(page.accepted.value?.metadata.readContextId).toBe('view-2');
    } finally { page.dispose(); authState.currentUser = previousUser; }
  });
  it('prepares a newer complete version in the background and retains the old frame on failure', async () => {
    const { page, views, load } = setup();
    await page.prepare({ name: 'old' });
    views.renew.mockResolvedValueOnce({ ...metadata('view-1'), sourceState: 'NEWER_AVAILABLE' });
    load.mockRejectedValueOnce(new Error('new detail failed'));
    await page.renew();
    expect(page.accepted.value?.metadata.readContextId).toBe('view-1');
    expect(page.accepted.value?.data.main).toBe('old');
    expect(page.accepted.value?.metadata.sourceState).toBe('NEWER_AVAILABLE');
    expect(views.release).toHaveBeenCalledWith('view-2');
    page.dispose();
  });
  it('keeps complete main/candidates/title together when new preparation fails', async () => {
    const { page, load, views } = setup();
    await page.prepare({ name: 'old' });
    load.mockRejectedValueOnce(new Error('projection failed'));
    expect(await page.prepare({ name: 'new' })).toBe(false);
    expect(page.accepted.value?.data).toEqual({ main: 'old', options: ['old'], title: 'old' });
    expect(page.accepted.value?.metadata.readContextId).toBe('view-1');
    expect(views.release).toHaveBeenCalledWith('view-2');
    page.dispose();
  });

  it('rejects a mixed source version without accepting any part of the new frame', async () => {
    const { page, load } = setup();
    await page.prepare({ name: 'old' });
    load.mockImplementationOnce(async (_scope, input) => {
      input.onReadView({ ...metadata(input.readContextId), sourceVersion: 'wrong-generation' });
      return { main: 'new', options: ['new'], title: 'new' };
    });
    expect(await page.prepare({ name: 'new' })).toBe(false);
    expect(page.accepted.value?.data.main).toBe('old');
    page.dispose();
  });

  it('discards late drilldown data after an entire new frame is accepted', async () => {
    const { page } = setup();
    await page.prepare({ name: 'old' });
    let finish!: (value: string) => void;
    const detail = page.readAccepted(async () => new Promise<string>(resolve => { finish = resolve; }));
    const rejected = expect(detail).rejects.toBeInstanceOf(SupersededPageReadError);
    await page.prepare({ name: 'new' });
    finish('old detail');
    await rejected;
    expect(page.accepted.value?.data.main).toBe('new');
    expect(page.lastError.value).toBeNull();
    page.dispose();
  });

  it('allows old page actions throughout preparation and changes identity only on complete acceptance', async () => {
    const { page, load } = setup();
    await page.prepare({ name: 'old' });
    let finish!: () => void;
    load.mockImplementationOnce(async (_scope, input) => {
      await new Promise<void>(resolve => { finish = resolve; });
      input.onReadView(metadata(input.readContextId));
      return { main: 'new', options: ['new'], title: 'new' };
    });
    const preparing = page.prepare({ name: 'new' });
    await Promise.resolve();
    expect(await page.readAccepted(async (_data, input) => input.readContextId)).toBe('view-1');
    finish();
    expect(await preparing).toBe(true);
    expect(await page.readAccepted(async (_data, input) => input.readContextId)).toBe('view-2');
    page.dispose();
  });

  it('clears private content on permission loss but retains it on structured rebind failure', async () => {
    const { page, views } = setup();
    await page.prepare({ name: 'old' });
    views.renew.mockRejectedValueOnce(new HttpRequestError('rebind', 409, 'READ_VIEW_REBIND_REQUIRED'));
    views.acquire.mockRejectedValueOnce(new HttpRequestError('not ready', 409, 'READ_VIEW_UNAVAILABLE'));
    await page.renew();
    expect(page.accepted.value?.data.main).toBe('old');
    await expect(page.readAccepted(async () => { throw new HttpRequestError('revoked', 403); })).rejects.toThrow('revoked');
    expect(page.accepted.value).toBeNull();
    page.dispose();
  });
});
