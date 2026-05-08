import { afterEach, describe, expect, it, vi } from 'vitest';
import { request } from './request';

describe('request', () => {
  afterEach(() => {
    document.cookie = 'XSRF-TOKEN=; Max-Age=0';
    vi.unstubAllGlobals();
  });

  it('should attach xsrf token for unsafe requests when cookie exists', async () => {
    document.cookie = 'XSRF-TOKEN=csrf-token';
    const fetchSpy = stubSuccessfulFetch();

    await request('/api/test', {
      method: 'POST',
      body: JSON.stringify({ value: 1 }),
    });

    const headers = getFetchHeaders(fetchSpy);
    expect(headers.get('X-XSRF-TOKEN')).toBe('csrf-token');
    expect(headers.get('Content-Type')).toBe('application/json');
  });

  it('should not attach xsrf token for safe requests', async () => {
    document.cookie = 'XSRF-TOKEN=csrf-token';
    const fetchSpy = stubSuccessfulFetch();

    await request('/api/test');

    const headers = getFetchHeaders(fetchSpy);
    expect(headers.get('X-XSRF-TOKEN')).toBeNull();
  });

  it('should preserve caller headers', async () => {
    document.cookie = 'XSRF-TOKEN=csrf-token';
    const fetchSpy = stubSuccessfulFetch();

    await request('/api/test', {
      method: 'POST',
      headers: {
        'Content-Type': 'text/plain',
        'X-XSRF-TOKEN': 'caller-token',
      },
    });

    const headers = getFetchHeaders(fetchSpy);
    expect(headers.get('Content-Type')).toBe('text/plain');
    expect(headers.get('X-XSRF-TOKEN')).toBe('caller-token');
  });
});

function stubSuccessfulFetch() {
  const fetchSpy = vi.fn(async (_input: Parameters<typeof fetch>[0], _init?: Parameters<typeof fetch>[1]) => ({
    ok: true,
    text: async () => JSON.stringify({ success: true, data: { ok: true } }),
  } as Response));
  vi.stubGlobal('fetch', fetchSpy);
  return fetchSpy;
}

function getFetchHeaders(fetchSpy: ReturnType<typeof stubSuccessfulFetch>): Headers {
  return (fetchSpy.mock.calls[0][1] as RequestInit).headers as Headers;
}
