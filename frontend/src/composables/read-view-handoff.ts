import { HttpRequestError } from '../api-client/request';
import { readViewsApi } from '../api-client/read-views-api';
import type { ReadViewMetadata } from '../types/api/read-view';
import { createUuid } from '../utils/uuid';

/** 消费一次路由交接；只传不透明租约，服务器重新核验目标页、会话、权限与范围。 */
export async function consumeReadViewHandoff(pageKey: string, scope: Readonly<Record<string, string>>,
  signal?: AbortSignal): Promise<ReadViewMetadata | null> {
  const state = window.history.state as Record<string, unknown> | null;
  if (!state || state.readViewPageKey !== pageKey) return null;
  const id = state.analyticsReadContextId;
  const handoffScope = state.analyticsReadScope;
  if (typeof id !== 'string' || !/^[0-9a-f-]{36}$/i.test(id) || typeof handoffScope !== 'object'
      || handoffScope === null || Array.isArray(handoffScope)) return null;
  const identity = (value: Readonly<Record<string, unknown>>) => JSON.stringify(Object.entries(value)
    .filter(([, item]) => item != null && String(item).trim()).sort(([a], [b]) => a.localeCompare(b)));
  const remaining = { ...state };
  delete remaining.readViewPageKey; delete remaining.analyticsReadContextId; delete remaining.analyticsReadScope;
  window.history.replaceState(remaining, '');
  try {
    const original = await readViewsApi.renew(id, signal);
    if (identity(handoffScope as Record<string, unknown>) === identity(scope)) return original;
    // 目标页可能规范化固定项目或默认范围；重新绑范围也必须保留父完整点。
    try {
      const normalized = await readViewsApi.fork(id, scope, createUuid(), pageKey);
      if (normalized.pageKey !== pageKey || normalized.sourceVersion !== original.sourceVersion
          || normalized.businessDate !== original.businessDate) {
        await readViewsApi.release(normalized.readContextId).catch(() => undefined);
        throw new Error('下钻默认范围未保留父完整版本');
      }
      return normalized;
    } finally { await readViewsApi.release(id).catch(() => undefined); }
  }
  catch (error) {
    if (error instanceof HttpRequestError && error.status === 409 && error.code === 'READ_VIEW_REBIND_REQUIRED') return null;
    throw error;
  }
}
