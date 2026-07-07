import {
  beginExportProgress,
  failExportProgress,
  finishExportProgress,
  type ExportProgressOptions,
} from './export-progress-events';

const CSRF_COOKIE_NAME = 'XSRF-TOKEN';
const CSRF_HEADER_NAME = 'X-XSRF-TOKEN';
const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS', 'TRACE']);

export const AUTH_REQUIRED_EVENT = 'platform-auth-required';
export const DEFAULT_REQUEST_TIMEOUT_MS = 15_000;
export const EXPORT_REQUEST_TIMEOUT_MS = 180_000;

export interface RequestOptions extends RequestInit {
  timeoutMs?: number;
  errorPrefix?: string;
  exportProgress?: ExportProgressOptions | false;
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
  const {
    timeoutMs = DEFAULT_REQUEST_TIMEOUT_MS,
    errorPrefix: _errorPrefix,
    exportProgress: _exportProgress,
    signal,
    ...fetchInit
  } = init ?? {};
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
  const payload: any = parseJsonPayload(rawText);

  if (!response.ok) {
    notifyAuthRequired(response.status, payload?.message || rawText);
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

function parseJsonPayload(rawText: string): any {
  try {
    return rawText ? JSON.parse(rawText) : null;
  } catch {
    return null;
  }
}

export async function requestText(url: string, init?: RequestOptions): Promise<string> {
  const response = await requestRaw(url, init);
  return response.text();
}

export async function requestBlob(url: string, init?: RequestOptions): Promise<Blob> {
  return withExportProgress(url, init, async () => {
    const response = await requestRaw(url, init);
    return response.blob();
  });
}

export interface BlobResponse {
  blob: Blob;
  filename?: string;
}

export async function requestBlobResponse(url: string, init?: RequestOptions): Promise<BlobResponse> {
  return withExportProgress(url, init, async () => {
    const response = await requestRaw(url, init);
    return {
      blob: await response.blob(),
      filename: parseContentDispositionFilename(response.headers?.get('Content-Disposition')),
    };
  });
}

async function requestRaw(url: string, init?: RequestOptions): Promise<Response> {
  const {
    timeoutMs = DEFAULT_REQUEST_TIMEOUT_MS,
    errorPrefix,
    exportProgress: _exportProgress,
    signal,
    ...fetchInit
  } = init ?? {};
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
    const message = await parseErrorMessage(response, errorPrefix);
    notifyAuthRequired(response.status, message);
    throw new Error(message);
  }
  return response;
}

async function withExportProgress<T>(url: string, init: RequestOptions | undefined, action: () => Promise<T>) {
  const exportProgress = resolveExportProgressOptions(url, init?.exportProgress);
  const progressId = beginExportProgress(url, exportProgress);
  try {
    const result = await action();
    finishExportProgress(progressId, url, exportProgress);
    return result;
  } catch (error) {
    failExportProgress(progressId, url, exportProgress, error);
    throw error;
  }
}

function resolveExportProgressOptions(
  url: string,
  configured?: ExportProgressOptions | false,
): ExportProgressOptions | false {
  if (configured === false) {
    return false;
  }
  const inferred = inferExportProgressOptions(url);
  return {
    ...inferred,
    ...(configured ?? {}),
  };
}

function inferExportProgressOptions(url: string): ExportProgressOptions {
  if (url.includes('/horizontal-comparison/export')) {
    return { label: '正在导出横向对比', profile: 'heavyStatistic', endpointKey: 'system-test-horizontal-comparison' };
  }
  if (url.includes('/statistic-boards/') && url.includes('/issues/export')) {
    return { label: '正在导出议题数据', profile: 'illegal', endpointKey: 'statistic-board-issues' };
  }
  if (url.includes('/statistic-boards/') && url.includes('/export')) {
    return { label: '正在导出统计表', profile: 'statistic', endpointKey: 'statistic-board' };
  }
  if (url.includes('/code-review/illegal-records/export')) {
    return { label: '正在导出代码走查数据', profile: 'codeReview', endpointKey: 'code-review-illegal-records' };
  }
  if (url.includes('/review-data/problem-items/export')) {
    return { label: '正在导出评审问题', profile: 'review', endpointKey: 'review-data-problems' };
  }
  if (url.includes('/review-data/records/') && url.includes('/problem-items/export')) {
    return { label: '正在导出问题详情', profile: 'review', endpointKey: 'review-data-problem-detail' };
  }
  if (url.includes('/review-data/records/export')) {
    return { label: '正在导出评审列表', profile: 'review', endpointKey: 'review-data-records' };
  }
  if (url.includes('/review-data/template')) {
    return { label: '正在下载评审模板', profile: 'template', endpointKey: 'review-data-template' };
  }
  if (url.includes('/illegal-records/export')) {
    return { label: '正在导出非法数据', profile: 'illegal', endpointKey: 'illegal-records' };
  }
  if (url.includes('/records/export') || url.includes('/issues/export') || url.includes('/export')) {
    return { label: '正在生成导出文件', profile: 'record', endpointKey: 'record-export' };
  }
  return { label: '正在生成文件', profile: 'record', endpointKey: 'blob-download' };
}

function notifyAuthRequired(status: number, message?: string) {
  if (status !== 401 || typeof window === 'undefined') {
    return;
  }
  window.dispatchEvent(new CustomEvent(AUTH_REQUIRED_EVENT, {
    detail: {
      message: message || '登录状态已过期，请重新登录',
    },
  }));
}

function parseContentDispositionFilename(contentDisposition: string | null): string | undefined {
  if (!contentDisposition) {
    return undefined;
  }
  const encodedMatch = contentDisposition.match(/filename\*=UTF-8''([^;]+)/i);
  if (encodedMatch?.[1]) {
    try {
      return decodeURIComponent(encodedMatch[1]);
    } catch {
      return encodedMatch[1];
    }
  }
  const quotedMatch = contentDisposition.match(/filename="([^"]+)"/i);
  if (quotedMatch?.[1]) {
    return quotedMatch[1];
  }
  const plainMatch = contentDisposition.match(/filename=([^;]+)/i);
  return plainMatch?.[1]?.trim();
}

async function parseErrorMessage(response: Response, errorPrefix = '请求失败'): Promise<string> {
  const rawText = await response.text();
  const contentType = response.headers?.get('Content-Type') ?? '';
  if (contentType.includes('application/json') && rawText) {
    try {
      const payload = JSON.parse(rawText);
      return payload?.message || rawText;
    } catch {
      return rawText;
    }
  }
  return rawText || `${errorPrefix}，状态码：${response.status}`;
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
