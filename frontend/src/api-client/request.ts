const CSRF_COOKIE_NAME = 'XSRF-TOKEN';
const CSRF_HEADER_NAME = 'X-XSRF-TOKEN';
const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS', 'TRACE']);
export const DEFAULT_REQUEST_TIMEOUT_MS = 15_000;

export interface RequestOptions extends RequestInit {
  timeoutMs?: number;
}

export class RequestTimeoutError extends Error {
  constructor(url: string, timeoutMs: number) {
    super(`请求超时，请检查网络后重试（${Math.round(timeoutMs / 1000)} 秒）`);
    this.name = 'RequestTimeoutError';
  }
}

export function isRequestTimeoutError(error: unknown): error is RequestTimeoutError {
  return error instanceof RequestTimeoutError || (error instanceof Error && error.name === 'RequestTimeoutError');
}

export async function request<T>(url: string, init?: RequestOptions): Promise<T> {
  const { timeoutMs = DEFAULT_REQUEST_TIMEOUT_MS, signal, ...fetchInit } = init ?? {};
  const timeoutController = timeoutMs > 0 ? new AbortController() : null;
  let didTimeout = false;
  let timeoutId: ReturnType<typeof setTimeout> | undefined;
  let abortListener: (() => void) | undefined;

  if (timeoutController) {
    timeoutId = setTimeout(() => {
      didTimeout = true;
      timeoutController.abort();
    }, timeoutMs);
    if (signal?.aborted) {
      timeoutController.abort();
    } else if (signal) {
      abortListener = () => timeoutController.abort();
      signal.addEventListener('abort', abortListener, { once: true });
    }
  }

  let response: Response;
  try {
    response = await fetch(url, {
      ...fetchInit,
      signal: timeoutController?.signal ?? signal,
      headers: buildRequestHeaders(fetchInit),
    });
  } catch (error) {
    if (didTimeout && isAbortError(error)) {
      throw new RequestTimeoutError(url, timeoutMs);
    }
    throw error;
  } finally {
    if (timeoutId !== undefined) {
      clearTimeout(timeoutId);
    }
    if (signal && abortListener) {
      signal.removeEventListener('abort', abortListener);
    }
  }
  const rawText = await response.text();
  let payload: any = null;
  try {
    payload = rawText ? JSON.parse(rawText) : null;
  } catch {
    payload = null;
  }

  if (!response.ok) {
    throw new Error(payload?.message || rawText || `请求失败，状态码：${response.status}`);
  }

  if (payload && typeof payload === 'object' && 'success' in payload) {
    if (!payload.success) {
      throw new Error(payload.message || '请求失败');
    }
    return payload.data as T;
  }

  return payload as T;
}

export async function requestText(url: string, init?: RequestOptions): Promise<string> {
  const response = await requestRaw(url, init);
  return response.text();
}

export async function requestBlob(url: string, init?: RequestOptions): Promise<Blob> {
  const response = await requestRaw(url, init);
  return response.blob();
}

async function requestRaw(url: string, init?: RequestOptions): Promise<Response> {
  const { timeoutMs = DEFAULT_REQUEST_TIMEOUT_MS, signal, ...fetchInit } = init ?? {};
  const timeoutController = timeoutMs > 0 ? new AbortController() : null;
  let didTimeout = false;
  let timeoutId: ReturnType<typeof setTimeout> | undefined;
  let abortListener: (() => void) | undefined;

  if (timeoutController) {
    timeoutId = setTimeout(() => {
      didTimeout = true;
      timeoutController.abort();
    }, timeoutMs);
    if (signal?.aborted) {
      timeoutController.abort();
    } else if (signal) {
      abortListener = () => timeoutController.abort();
      signal.addEventListener('abort', abortListener, { once: true });
    }
  }

  let response: Response;
  try {
    response = await fetch(url, {
      ...fetchInit,
      signal: timeoutController?.signal ?? signal,
      headers: buildRequestHeaders(fetchInit),
    });
  } catch (error) {
    if (didTimeout && isAbortError(error)) {
      throw new RequestTimeoutError(url, timeoutMs);
    }
    throw error;
  } finally {
    if (timeoutId !== undefined) {
      clearTimeout(timeoutId);
    }
    if (signal && abortListener) {
      signal.removeEventListener('abort', abortListener);
    }
  }

  if (!response.ok) {
    throw new Error(await parseErrorMessage(response));
  }
  return response;
}

async function parseErrorMessage(response: Response): Promise<string> {
  const rawText = await response.text();
  const contentType = response.headers.get('Content-Type') ?? '';
  if (contentType.includes('application/json') && rawText) {
    try {
      const payload = JSON.parse(rawText);
      return payload?.message || rawText;
    } catch {
      return rawText;
    }
  }
  return rawText || `请求失败，状态码：${response.status}`;
}

function isAbortError(error: unknown): boolean {
  return typeof error === 'object'
    && error !== null
    && 'name' in error
    && (error as { name?: string }).name === 'AbortError';
}

function buildRequestHeaders(init?: RequestInit): Headers {
  const headers = new Headers(init?.headers ?? {});
  const hasFormDataBody = typeof FormData !== 'undefined' && init?.body instanceof FormData;
  if (!headers.has('Content-Type') && !hasFormDataBody) {
    headers.set('Content-Type', 'application/json');
  }

  const method = String(init?.method ?? 'GET').toUpperCase();
  const csrfToken = SAFE_METHODS.has(method) ? '' : readCookie(CSRF_COOKIE_NAME);
  if (csrfToken && !headers.has(CSRF_HEADER_NAME)) {
    headers.set(CSRF_HEADER_NAME, csrfToken);
  }

  return headers;
}

function readCookie(name: string): string {
  if (typeof document === 'undefined' || !document.cookie) {
    return '';
  }

  const prefix = `${name}=`;
  const cookie = document.cookie
    .split(';')
    .map((item) => item.trim())
    .find((item) => item.startsWith(prefix));
  return cookie ? decodeURIComponent(cookie.slice(prefix.length)) : '';
}
