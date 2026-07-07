import {
  beginPlatformProgress,
  failPlatformProgress,
  finishPlatformProgress,
  type PlatformProgressOptions,
} from './platform-progress-events';

const CSRF_COOKIE_NAME = 'XSRF-TOKEN';
const CSRF_HEADER_NAME = 'X-XSRF-TOKEN';
const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS', 'TRACE']);

export const AUTH_REQUIRED_EVENT = 'platform-auth-required';
export const DEFAULT_REQUEST_TIMEOUT_MS = 15_000;
export const EXPORT_REQUEST_TIMEOUT_MS = 180_000;
const PROGRESS_FIRST_PAINT_PROFILES = new Set([
  'route',
  'table',
  'statistic',
  'filter',
  'refresh',
  'export',
  'heavyExport',
  'template',
]);

export interface RequestOptions extends RequestInit {
  timeoutMs?: number;
  errorPrefix?: string;
  platformProgress?: PlatformProgressOptions | false;
  exportProgress?: PlatformProgressOptions | false;
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
    platformProgress: configuredPlatformProgress,
    exportProgress: configuredExportProgress,
    signal,
    ...fetchInit
  } = init ?? {};
  const platformProgress = resolveRequestProgressOptions(
    url,
    configuredPlatformProgress ?? configuredExportProgress,
  );
  const progressId = beginPlatformProgress(url, platformProgress);
  const timeoutController = timeoutMs > 0 ? new AbortController() : null;
  let didTimeout = false;
  let timeoutId: ReturnType<typeof setTimeout> | undefined;
  let abortListener: (() => void) | undefined;

  try {
    await waitForProgressFirstPaint(platformProgress, signal);
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
      finishPlatformProgress(progressId, url, platformProgress);
      return payload.data as T;
    }

    finishPlatformProgress(progressId, url, platformProgress);
    return payload as T;
  } catch (error) {
    failPlatformProgress(progressId, url, platformProgress, error);
    throw error;
  }
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
  return withPlatformProgress(url, init, async () => {
    const response = await requestRaw(url, init);
    return response.blob();
  });
}

export interface BlobResponse {
  blob: Blob;
  filename?: string;
}

export async function requestBlobResponse(url: string, init?: RequestOptions): Promise<BlobResponse> {
  return withPlatformProgress(url, init, async () => {
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
    platformProgress: _platformProgress,
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

async function withPlatformProgress<T>(url: string, init: RequestOptions | undefined, action: () => Promise<T>) {
  const platformProgress = resolveExportProgressOptions(url, init?.exportProgress ?? init?.platformProgress);
  const progressId = beginPlatformProgress(url, platformProgress);
  try {
    await waitForProgressFirstPaint(platformProgress, init?.signal ?? null);
    const result = await action();
    finishPlatformProgress(progressId, url, platformProgress);
    return result;
  } catch (error) {
    failPlatformProgress(progressId, url, platformProgress, error);
    throw error;
  }
}

async function waitForProgressFirstPaint(
  options: PlatformProgressOptions | false | undefined,
  signal?: AbortSignal | null,
) {
  if (
    options === false
    || typeof window === 'undefined'
    || signal?.aborted
    || !PROGRESS_FIRST_PAINT_PROFILES.has(options?.profile ?? 'background')
  ) {
    return;
  }
  await new Promise<void>((resolve) => {
    window.requestAnimationFrame(() => {
      window.setTimeout(resolve, 0);
    });
  });
}

function resolveExportProgressOptions(
  url: string,
  configured?: PlatformProgressOptions | false,
): PlatformProgressOptions | false {
  if (configured === false) {
    return false;
  }
  const inferred = inferExportProgressOptions(url);
  return {
    ...inferred,
    ...(configured ?? {}),
  };
}

function resolveRequestProgressOptions(
  url: string,
  configured?: PlatformProgressOptions | false,
): PlatformProgressOptions | false {
  if (configured === false) {
    return false;
  }
  const inferred = inferRequestProgressOptions(url);
  if (inferred === false) {
    return false;
  }
  return {
    ...inferred,
    ...(configured ?? {}),
  };
}

function inferRequestProgressOptions(url: string): PlatformProgressOptions | false {
  if (isBackgroundRequest(url)) {
    return false;
  }
  if (url.includes('/statistic-boards/') && !url.includes('/export')) {
    return {
      label: '正在计算统计',
      profile: 'statistic',
      endpointKey: endpointKeyFromUrl(url, 'statistic-board'),
      learnDuration: true,
    };
  }
  if (
    url.includes('/filter-options')
    || url.includes('/source-options')
    || url.includes('/candidates')
    || url.includes('/candidate')
    || url.includes('/options')
  ) {
    return {
      label: '正在加载筛选项',
      profile: 'filter',
      endpointKey: endpointKeyFromUrl(url, 'filter-options'),
    };
  }
  if (url.includes('/refresh') || url.includes('/sync') || url.includes('/rebuild')) {
    return {
      label: '正在刷新数据',
      profile: 'refresh',
      endpointKey: endpointKeyFromUrl(url, 'refresh'),
      learnDuration: true,
    };
  }
  if (
    url.includes('/illegal-records')
    || url.includes('/records')
    || url.includes('/issues')
    || url.includes('/review-data')
    || url.includes('/customer-issues')
    || url.includes('/code-review')
  ) {
    return {
      label: '正在加载数据',
      profile: 'table',
      endpointKey: endpointKeyFromUrl(url, 'table'),
      learnDuration: true,
    };
  }
  return {
    label: '正在处理请求',
    profile: 'background',
    endpointKey: endpointKeyFromUrl(url, 'request'),
  };
}

function isBackgroundRequest(url: string) {
  const normalizedUrl = url.split('?')[0] ?? url;
  return normalizedUrl.includes('/api/auth/')
    || normalizedUrl.endsWith('/status')
    || normalizedUrl.includes('/status/')
    || normalizedUrl.includes('/rule-explanation')
    || normalizedUrl.includes('/settings/views')
    || normalizedUrl.includes('/match-mode/status');
}

function endpointKeyFromUrl(url: string, fallback: string) {
  const normalizedUrl = url
    .split('?')[0]
    ?.replace(/^\/api\//, '')
    .replace(/\/\d+(?=\/|$)/g, '/:id');
  return normalizedUrl || fallback;
}

function inferExportProgressOptions(url: string): PlatformProgressOptions {
  if (url.includes('/horizontal-comparison/export')) {
    return { label: '正在导出横向对比', profile: 'heavyExport', endpointKey: 'system-test-horizontal-comparison', learnDuration: true };
  }
  if (url.includes('/statistic-boards/') && url.includes('/issues/export')) {
    return { label: '正在导出议题数据', profile: 'heavyExport', endpointKey: 'statistic-board-issues', learnDuration: true };
  }
  if (url.includes('/statistic-boards/') && url.includes('/export')) {
    return { label: '正在导出统计表', profile: 'export', endpointKey: 'statistic-board', learnDuration: true };
  }
  if (url.includes('/code-review/illegal-records/export')) {
    return { label: '正在导出代码走查数据', profile: 'heavyExport', endpointKey: 'code-review-illegal-records', learnDuration: true };
  }
  if (url.includes('/review-data/problem-items/export')) {
    return { label: '正在导出评审问题', profile: 'export', endpointKey: 'review-data-problems', learnDuration: true };
  }
  if (url.includes('/review-data/records/') && url.includes('/problem-items/export')) {
    return { label: '正在导出问题详情', profile: 'export', endpointKey: 'review-data-problem-detail', learnDuration: true };
  }
  if (url.includes('/review-data/records/export')) {
    return { label: '正在导出评审列表', profile: 'export', endpointKey: 'review-data-records', learnDuration: true };
  }
  if (url.includes('/review-data/template')) {
    return { label: '正在下载评审模板', profile: 'template', endpointKey: 'review-data-template' };
  }
  if (url.includes('/illegal-records/export')) {
    return { label: '正在导出非法数据', profile: 'heavyExport', endpointKey: 'illegal-records', learnDuration: true };
  }
  if (url.includes('/records/export') || url.includes('/issues/export') || url.includes('/export')) {
    return { label: '正在生成导出文件', profile: 'export', endpointKey: 'record-export', learnDuration: true };
  }
  return { label: '正在生成文件', profile: 'export', endpointKey: 'blob-download', learnDuration: true };
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
