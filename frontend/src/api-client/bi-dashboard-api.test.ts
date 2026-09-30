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

  it('loads the customer issue page with typed filters and without a product version', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ pageKey: 'customer-issues' }));
    vi.stubGlobal('fetch', fetchMock);

    await biDashboardApi.loadCustomerIssues({
      milestoneBusinessKey: 'customer-mile-1',
      customer: { kind: 'VALUE', value: 'missing' },
      module: { kind: 'MISSING' },
      function: { kind: 'ALL' },
    });

    const [url] = fetchMock.mock.calls[0]!;
    expect(String(url)).toBe('/api/bi/customer-issues?milestoneBusinessKey=customer-mile-1&customerKind=VALUE&customer=missing&moduleKind=MISSING');
    expect(String(url)).not.toContain('productVersionId');
  });

  it('authorizes PNG generation with page and source identities', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ authorized: true }));
    vi.stubGlobal('fetch', fetchMock);

    await biDashboardApi.authorizeDownload({
      pageKey: 'customer-issues',
      chartInstanceId: 'customer-issue-daily-trend',
      chartTemplateId: 'daily-defect-trend',
      sourceVersion: 'source-3',
      scope: {
        rangeType: 'CUSTOMER_ISSUE', milestoneBusinessKey: 'mile-1', businessDate: '2026-09-24',
        customerKind: 'MISSING', moduleKind: 'VALUE', module: '模块A', functionKind: 'ALL',
      },
    });

    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('/api/bi/download/authorize');
    expect(init.method).toBe('POST');
    expect(JSON.parse(String(init.body))).toEqual({
      pageKey: 'customer-issues',
      chartInstanceId: 'customer-issue-daily-trend',
      chartTemplateId: 'daily-defect-trend',
      sourceVersion: 'source-3',
      scope: {
        rangeType: 'CUSTOMER_ISSUE', milestoneBusinessKey: 'mile-1', businessDate: '2026-09-24',
        customerKind: 'MISSING', moduleKind: 'VALUE', module: '模块A', functionKind: 'ALL',
      },
    });
  });
});

function jsonResponse(data: unknown): Response {
  return new Response(JSON.stringify({ success: true, data }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
