import { afterEach, describe, expect, it, vi } from 'vitest';
import { isRequestTimeoutError, request } from './request';

describe('request', () => {
  afterEach(() => {
    document.cookie = 'XSRF-TOKEN=; Max-Age=0';
    vi.useRealTimers();
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

  it('should abort requests after configured timeout', async () => {
    vi.useFakeTimers();
    vi.stubGlobal(
      'fetch',
      vi.fn((_input: Parameters<typeof fetch>[0], init?: Parameters<typeof fetch>[1]) => new Promise((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => {
          reject(new DOMException('The operation was aborted.', 'AbortError'));
        });
      })),
    );

    const pending = request('/api/slow', { timeoutMs: 1000 });
    const expectation = expect(pending).rejects.toMatchObject({ name: 'RequestTimeoutError' });
    await vi.advanceTimersByTimeAsync(1000);

    await expectation;
  });

  it('should preserve caller abort signal without reporting timeout', async () => {
    vi.useFakeTimers();
    const controller = new AbortController();
    vi.stubGlobal(
      'fetch',
      vi.fn((_input: Parameters<typeof fetch>[0], init?: Parameters<typeof fetch>[1]) => new Promise((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => {
          reject(new DOMException('The operation was aborted.', 'AbortError'));
        });
      })),
    );

    const pending = request('/api/cancelled', { signal: controller.signal, timeoutMs: 1000 });
    const expectation = expect(pending).rejects.toMatchObject({ name: 'AbortError' });
    controller.abort();

    await expectation;
  });

  it('should identify request timeout errors', () => {
    const error = new Error('timeout');
    error.name = 'RequestTimeoutError';

    expect(isRequestTimeoutError(error)).toBe(true);
    expect(isRequestTimeoutError(new Error('other'))).toBe(false);
  });

  it('should use Chinese fallback copy when a non-OK response has no body', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({
        ok: false,
        status: 500,
        text: async () => '',
      } as Response)),
    );

    await expect(request('/api/fail')).rejects.toThrow('请求失败，状态码：500');
  });

  it('should use Chinese fallback copy when an API envelope has no error message', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({
        ok: true,
        text: async () => JSON.stringify({ success: false }),
      } as Response)),
    );

    await expect(request('/api/fail-envelope')).rejects.toThrow('请求失败');
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
