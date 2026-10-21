import { shallowRef, readonly, shallowReadonly, watch, toValue, type MaybeRefOrGetter } from 'vue';
import { AUTH_REQUIRED_EVENT, HttpRequestError } from '../api-client/request';
import { readViewsApi } from '../api-client/read-views-api';
import type { ReadViewMetadata, ReadViewTransport } from '../types/api/read-view';
import { authState } from './auth-state';
import { consumeReadViewHandoff } from './read-view-handoff';
import { createUuid } from '../utils/uuid';

export interface AcceptedPageFrame<T> {
  readonly data: T;
  readonly scope: Readonly<Record<string, string>>;
  readonly metadata: ReadViewMetadata;
}

export interface AcceptedPageDependencies<T> {
  pageKey: MaybeRefOrGetter<string>;
  /** 所有主数据、候选、标题及已打开明细准备完毕才返回；每项请求共用显式 transport。 */
  load: (scope: Readonly<Record<string, string>>, transport: ReadViewTransport) => Promise<T>;
  /** 默认范围由固定版本返回时，在同一来源版本复制范围租约并重读整批，最多一次。 */
  resolveScope?: (data: T, scope: Readonly<Record<string, string>>) => Readonly<Record<string, string>>;
  views?: Pick<typeof readViewsApi, 'acquire' | 'renew' | 'release'> & Partial<Pick<typeof readViewsApi, 'fork'>>;
  now?: () => number;
  /** 整批页面准备的总等待上限，默认 5 秒；超时保持已接受画面。 */
  prepareTimeoutMs?: number;
}

/** 迟到的旧身份结果不能再写入已换版的页面。 */
export class SupersededPageReadError extends Error {
  constructor() { super('页面已换版，已丢弃迟到结果'); this.name = 'SupersededPageReadError'; }
}

/**
 * 完整页面的两阶段接受：准备失败保留原画面；只有整批匹配身份的数据就绪才原子替换。
 * 分页/下钻/下载使用 acceptedTransport，不能使用正在准备的候选页面身份。
 */
export function useAcceptedPageView<T>(deps: AcceptedPageDependencies<T>) {
  const views = deps.views ?? readViewsApi;
  const now = deps.now ?? Date.now;
  const prepareTimeoutMs = deps.prepareTimeoutMs ?? 5_000;
  if (!Number.isFinite(prepareTimeoutMs) || prepareTimeoutMs <= 0) throw new Error('页面准备等待上限必须为正数毫秒');
  const accepted = shallowRef<AcceptedPageFrame<T> | null>(null);
  const preparing = shallowRef(false);
  const lastError = shallowRef<unknown>(null);
  let preparation = 0;
  let acceptedGeneration = 0;
  let pending: AbortController | null = null;
  let heartbeat: ReturnType<typeof setInterval> | undefined;
  let renewing = false;
  let disposed = false;
  let acceptedLoader = deps.load;
  let stopIdentityWatch: (() => void) | undefined;

  /** 取消尚未接受的准备批次，保留当前完整画面及其租约。 */
  function cancelPreparation() {
    preparation += 1;
    pending?.abort();
    pending = null;
    preparing.value = false;
  }

  function clearPrivateData() {
    const id = accepted.value?.metadata.readContextId;
    preparation += 1;
    acceptedGeneration += 1;
    pending?.abort();
    pending = null;
    accepted.value = null;
    preparing.value = false;
    if (id) { void release(id); }
  }

  function handleFailure(error: unknown) {
    lastError.value = error;
    if (error instanceof HttpRequestError && (error.status === 401 || error.status === 403)) {
      clearPrivateData();
    }
  }

  function transport(metadata: ReadViewMetadata, scope: Readonly<Record<string, string>>, signal?: AbortSignal): ReadViewTransport {
    return {
      readContextId: metadata.readContextId, signal,
      readScope: scope,
      onReadView: incoming => {
        if (incoming.readContextId !== metadata.readContextId || incoming.sourceVersion !== metadata.sourceVersion
            || incoming.businessDate !== metadata.businessDate || incoming.pageKey !== metadata.pageKey
            || incoming.scopeKey !== metadata.scopeKey) {
          throw new Error('页面数据不属于同一完整版本，已保留原画面');
        }
      },
    };
  }

  async function release(id: string) {
    // 释放不是换版成功条件；失联时由服务端到期维护释放，不能撤销已接受的新画面。
    try { await views.release(id); } catch { /* 到期维护兜底。 */ }
  }

  async function prepare(scope: Readonly<Record<string, string>>, load = deps.load, pageKey = toValue(deps.pageKey)): Promise<boolean> {
    if (disposed) { return false; }
    const ticket = ++preparation;
    pending?.abort();
    const controller = new AbortController();
    pending = controller;
    preparing.value = true;
    lastError.value = null;
    let fixedScope = Object.freeze({ ...scope });
    let prepared: ReadViewMetadata | null = null;
    let timedOut = false;
    let rejectCanceled!: (error: Error) => void;
    const canceled = new Promise<never>((_resolve, reject) => { rejectCanceled = reject; });
    const aborted = () => rejectCanceled(timedOut ? new Error('页面更新等待超时，已保留当前完整数据，请稍后重试') : new SupersededPageReadError());
    controller.signal.addEventListener('abort', aborted, { once: true });
    const timeout = setTimeout(() => { timedOut = true; controller.abort(); }, prepareTimeoutMs);
    const bounded = <R>(work: Promise<R>): Promise<R> => Promise.race([work, canceled]);
    const acquireWithCleanup = (work: Promise<ReadViewMetadata>): Promise<ReadViewMetadata> => {
      void work.then(metadata => {
        if (controller.signal.aborted || ticket !== preparation || disposed) void release(metadata.readContextId);
      }).catch(() => undefined);
      return bounded(work);
    };
    try {
      const acquisition = deps.views ? views.acquire(pageKey, fixedScope, createUuid(), controller.signal) : (async () => {
        const handoff = deps.views ? null : await consumeReadViewHandoff(pageKey, fixedScope, controller.signal);
        return handoff ?? views.acquire(pageKey, fixedScope, createUuid(), controller.signal);
      })();
      prepared = await acquireWithCleanup(acquisition);
      if (prepared.pageKey !== pageKey) { throw new Error('页面读取身份不匹配'); }
      const loadBatch = async (): Promise<T> => {
        const input = transport(prepared!, fixedScope, controller.signal);
        let received = false;
        const data = await load(fixedScope, { ...input, onReadView: incoming => {
          input.onReadView(incoming); received = true;
        } });
        if (!received) { throw new Error('页面数据未确认完整读取身份，已保留原画面'); }
        return data;
      };
      let data = await bounded(loadBatch());
      const scopeIdentity = (value: Readonly<Record<string, string>>) => JSON.stringify(Object.entries(value).sort(([a], [b]) => a.localeCompare(b)));
      const resolvedScope = Object.freeze({ ...(deps.resolveScope?.(data, fixedScope) ?? fixedScope) });
      if (scopeIdentity(resolvedScope) !== scopeIdentity(fixedScope)) {
        if (!views.fork) throw new Error('页面默认范围需要完整版本的范围租约支持');
        const initial = prepared;
        prepared = await acquireWithCleanup(views.fork(initial.readContextId, resolvedScope, createUuid()));
        void release(initial.readContextId);
        if (prepared.pageKey !== pageKey || prepared.sourceVersion !== initial.sourceVersion || prepared.businessDate !== initial.businessDate) {
          throw new Error('默认范围未保持同一完整来源版本');
        }
        fixedScope = resolvedScope;
        data = await bounded(loadBatch());
        if (scopeIdentity(deps.resolveScope?.(data, fixedScope) ?? fixedScope) !== scopeIdentity(fixedScope)) {
          throw new Error('固定版本的默认范围不稳定，已保留原画面');
        }
      }
      if (ticket !== preparation || controller.signal.aborted || disposed) { return false; }
      const previous = accepted.value;
      acceptedGeneration += 1;
      acceptedLoader = load;
      accepted.value = Object.freeze({ data, scope: fixedScope, metadata: prepared });
      prepared = null;
      if (previous) { void release(previous.metadata.readContextId); }
      return true;
    } catch (error) {
      if (ticket === preparation && (!controller.signal.aborted || timedOut)) { handleFailure(error); }
      return false;
    } finally {
      clearTimeout(timeout); controller.signal.removeEventListener('abort', aborted);
      if (prepared) { void release(prepared.readContextId); }
      if (ticket === preparation) { preparing.value = false; pending = null; }
    }
  }

  async function renew(): Promise<void> {
    const frame = accepted.value;
    const generation = acceptedGeneration;
    if (!frame || renewing || disposed) { return; }
    renewing = true;
    try {
      const metadata = await views.renew(frame.metadata.readContextId);
      transport(frame.metadata, frame.scope).onReadView(metadata);
      if (generation === acceptedGeneration) {
        accepted.value = Object.freeze({ ...frame, metadata });
        // 有新完整发布点或跨业务日时后台准备整批画面；旧画面在准备全过程继续服务。
        if (metadata.sourceState === 'NEWER_AVAILABLE' && !preparing.value) {
          await prepare(frame.scope, acceptedLoader, frame.metadata.pageKey);
        }
      }
    } catch (error) {
      if (generation !== acceptedGeneration) { return; }
      handleFailure(error);
      if (error instanceof HttpRequestError && error.status === 409 && error.code === 'READ_VIEW_REBIND_REQUIRED') {
        await prepare(frame.scope, acceptedLoader, frame.metadata.pageKey);
      }
    } finally { renewing = false; }
  }

  /** 暂停或隐藏后先恢复完整身份，再开始下钻/分页/下载；不能把新身份绑定到旧主表。 */
  async function readAccepted<R>(reader: (data: T, input: ReadViewTransport,
    scope: Readonly<Record<string, string>>) => Promise<R>,
    commit?: (result: R, data: T) => void): Promise<R> {
    if (accepted.value && Date.parse(accepted.value.metadata.leaseExpiresAt) <= now()) {
      if (!await prepare(accepted.value.scope, acceptedLoader, accepted.value.metadata.pageKey)) { throw lastError.value ?? new Error('页面版本尚未恢复，请重试'); }
    }
    const frame = accepted.value;
    const generation = acceptedGeneration;
    if (!frame) { throw new Error('页面尚无完整产出'); }
    try {
      const result = await reader(frame.data, transport(frame.metadata, frame.scope), frame.scope);
      if (generation !== acceptedGeneration) { throw new SupersededPageReadError(); }
      // 校验和界面提交在同一个同步段完成，不能由调用者在 await 后覆盖已经换版的画面。
      commit?.(result, frame.data);
      return result;
    } catch (error) {
      // 迟到的普通错误不能写入新画面；认证/权限收紧始终清理私人数据。
      if (generation === acceptedGeneration || (error instanceof HttpRequestError && (error.status === 401 || error.status === 403))) {
        handleFailure(error);
      }
      throw error;
    }
  }

  function visibilityChanged() {
    if (document.visibilityState === 'visible') { void renew(); }
  }

  /** 可见页面每分钟续约，隐藏页停心跳；离开页面必须调用 dispose。 */
  function start() {
    if (heartbeat || disposed) { return; }
    heartbeat = setInterval(() => { if (document.visibilityState === 'visible') { void renew(); } }, 60_000);
    document.addEventListener('visibilitychange', visibilityChanged);
    window.addEventListener(AUTH_REQUIRED_EVENT, clearPrivateData);
    stopIdentityWatch = watch(() => JSON.stringify({ username: authState.currentUser.username,
      authenticated: authState.currentUser.authenticated,
      permissions: [...authState.currentUser.permissions].sort(), roles: [...authState.currentUser.roleCodes].sort() }),
    (nextIdentity, previousIdentity) => {
      const frame = accepted.value;
      const samePrincipal = JSON.parse(nextIdentity).username === JSON.parse(previousIdentity).username;
      clearPrivateData();
      if (frame && samePrincipal && authState.currentUser.authenticated) { void prepare(frame.scope, acceptedLoader, frame.metadata.pageKey); }
    }, { flush: 'sync' });
  }

  function dispose() {
    disposed = true;
    if (heartbeat) { clearInterval(heartbeat); heartbeat = undefined; }
    document.removeEventListener('visibilitychange', visibilityChanged);
    window.removeEventListener(AUTH_REQUIRED_EVENT, clearPrivateData);
    stopIdentityWatch?.(); stopIdentityWatch = undefined;
    clearPrivateData();
  }

  return { accepted: shallowReadonly(accepted), preparing: readonly(preparing), lastError: readonly(lastError),
    prepare, readAccepted, renew, cancelPreparation, clearPrivateData, start, dispose };
}
