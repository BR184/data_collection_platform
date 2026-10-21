import { afterEach, describe, expect, it, vi } from 'vitest';
import { consumeReadViewHandoff } from './read-view-handoff';
import { readViewsApi } from '../api-client/read-views-api';
import { HttpRequestError } from '../api-client/request';
import type { ReadViewMetadata } from '../types/api/read-view';

const id = '11111111-1111-4111-8111-111111111111';
const metadata = (value: string): ReadViewMetadata => ({ readContextId: value, pageKey: 'system-test-issues',
  scopeKey: 'scope', sourceVersion: 'parent-complete', businessDate: '2026-10-14',
  leaseExpiresAt: '2099-01-01T00:00:00Z', dataAsOf: '2026-10-14T00:00:00Z', sourceState: 'CURRENT', pendingUpdates: 0, escalated: false });
afterEach(() => { window.history.replaceState(null,''); vi.restoreAllMocks(); });
function handoff() { window.history.replaceState({ readViewPageKey: 'system-test-issues', analyticsReadContextId: id, analyticsReadScope: {} },''); }

describe('complete point handoff with target defaults', () => {
  it('normalizes the target fixed project within the parent complete point without acquiring current data', async () => {
    handoff(); vi.spyOn(readViewsApi,'renew').mockResolvedValue(metadata(id));
    const fork = vi.spyOn(readViewsApi,'fork').mockResolvedValue(metadata('normalized-child'));
    const release = vi.spyOn(readViewsApi,'release').mockResolvedValue(undefined);
    const acquire = vi.spyOn(readViewsApi,'acquire');
    expect(await consumeReadViewHandoff('system-test-issues',{ projectId: '9' })).toEqual(metadata('normalized-child'));
    expect(fork).toHaveBeenCalledWith(id,{ projectId: '9' },expect.any(String),'system-test-issues');
    expect(release).toHaveBeenCalledWith(id); expect(acquire).not.toHaveBeenCalled();
  });
  it('does not replace a denied handoff with a fresh current view', async () => {
    handoff(); vi.spyOn(readViewsApi,'renew').mockRejectedValue(new HttpRequestError('权限已撤销',403,''));
    const fork = vi.spyOn(readViewsApi,'fork');
    await expect(consumeReadViewHandoff('system-test-issues',{ projectId: '9' })).rejects.toThrow('权限已撤销');
    expect(fork).not.toHaveBeenCalled();
  });
  it('rejects a default scope fork that changed source version and releases both unused leases', async () => {
    handoff(); vi.spyOn(readViewsApi,'renew').mockResolvedValue(metadata(id));
    vi.spyOn(readViewsApi,'fork').mockResolvedValue({ ...metadata('unrelated-child'), sourceVersion: 'newer' });
    const release = vi.spyOn(readViewsApi,'release').mockResolvedValue(undefined);
    await expect(consumeReadViewHandoff('system-test-issues',{ projectId: '9' })).rejects.toThrow('未保留父完整版本');
    expect(release).toHaveBeenCalledWith('unrelated-child'); expect(release).toHaveBeenCalledWith(id);
  });
});
