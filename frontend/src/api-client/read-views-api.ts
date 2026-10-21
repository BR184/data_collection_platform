import { request, requireReadMetadata } from './request';
import type { ReadViewMetadata } from '../types/api/read-view';

export const readViewsApi = {
  /** 准备完整来源组合；客户端幂等键属于一次准备，不能在不同范围间复用。 */
  async acquire(pageKey: string, scope: Readonly<Record<string, string>>, clientViewId: string,
    signal?: AbortSignal): Promise<ReadViewMetadata> {
    return requireReadMetadata(await request('/api/read-views', {
      method: 'POST', body: JSON.stringify({ pageKey, scope, clientViewId }), signal, platformProgress: false,
    }));
  },
  /** 下钻复制完整来源到独立租约，目标页权限及闭包由服务器核验。 */
  async fork(id: string, scope: Readonly<Record<string, string>>, clientViewId: string, pageKey?: string): Promise<ReadViewMetadata> {
    return requireReadMetadata(await request(`/api/read-views/${encodeURIComponent(id)}/fork`, {
      method: 'POST', body: JSON.stringify({ scope, clientViewId, pageKey }), platformProgress: false,
    }));
  },
  /** 续约仍核验当前权限；409 表示需要完整重准备，不能只换主表版本号。 */
  async renew(id: string, signal?: AbortSignal): Promise<ReadViewMetadata> {
    return requireReadMetadata(await request(`/api/read-views/${encodeURIComponent(id)}/renew`, {
      method: 'POST', signal, platformProgress: false,
    }));
  },
  /** 释放被替换或离开的页面资源；204 沿用统一请求的空响应语义。 */
  async release(id: string): Promise<void> {
    await request(`/api/read-views/${encodeURIComponent(id)}`, { method: 'DELETE', platformProgress: false });
  },
};
