import { afterEach, describe, expect, it, vi } from 'vitest';
import { biDashboardApi } from './bi-dashboard-api';

describe('bi dashboard api contract', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('sends only the coding page stable filters', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ pageKey: 'coding' }));
    vi.stubGlobal('fetch', fetchMock);

    await biDashboardApi.loadCoding(10, { granularity: 'week', source: 'cc', repositoryId: 'repo-1' });

    expect(fetchMock).toHaveBeenCalledOnce();
    const [url] = fetchMock.mock.calls[0]!;
    expect(String(url)).toBe('/api/bi/coding?productVersionId=10&granularity=week&source=cc&repositoryId=repo-1');
  });

  it('authorizes PNG generation with page and source identities', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ authorized: true }));
    vi.stubGlobal('fetch', fetchMock);

    await biDashboardApi.authorizeDownload(10, 'system-test', 'overlay-category-bar', 'source-3');

    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('/api/bi/download/authorize');
    expect(init.method).toBe('POST');
    expect(JSON.parse(String(init.body))).toEqual({
      productVersionId: 10,
      pageKey: 'system-test',
      chartTemplateId: 'overlay-category-bar',
      sourceVersion: 'source-3',
    });
  });
});

function jsonResponse(data: unknown): Response {
  return new Response(JSON.stringify({ success: true, data }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}

