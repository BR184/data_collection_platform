import { reactive } from 'vue';
import { authApi, guestUser, type AuthUserResponse } from '../api-client/auth-api';
import { HttpRequestError } from '../api-client/request';
import { getErrorMessage } from '../utils/user-message';

export type AuthStatus = 'unknown' | 'checking' | 'anonymous' | 'authenticated' | 'unavailable';

export const authState = reactive({
  currentUser: { ...guestUser } as AuthUserResponse,
  status: 'unknown' as AuthStatus,
  loading: false,
  error: '',
  initialized: false,
});

/** 身份生命周期中的操作：读取当前用户、登录、退出登录共用同一份归属判定。 */
type IdentityOperationKind = 'read' | 'login' | 'logout';

interface IdentityOperation {
  kind: IdentityOperationKind;
  /** 发起时的身份代次；代次变化说明已有更新的身份变更，本操作不再拥有写回权。 */
  generation: number;
  /** 全局操作序号：读取之间用它判定谁是最新读取。 */
  id: number;
  promise: Promise<AuthUserResponse>;
}

/** 一次读取的身份归属：代次、读取序号与发起时的结算计数必须同时未变，才有写回权。 */
interface ReadIdentity {
  generation: number;
  id: number;
  settlementRevision: number;
}

let identityGeneration = 0;
/**
 * 身份结算次数。登录/退出每次落定身份时递增，使该变更在途期间发起的读取全部失效：
 * 那些读取回答的是变更前的会话，不得回滚已经结算的身份。
 */
let settlementRevision = 0;
let operationSequence = 0;
let latestReadId = 0;
let activeOperation: IdentityOperation | null = null;

function setCurrentUser(user: AuthUserResponse) {
  authState.currentUser = user;
  authState.status = user.authenticated ? 'authenticated' : 'anonymous';
  authState.error = '';
}

/** 开始一次身份变更：递增代次使更早的读取与变更全部失效，并清空当前操作槽。 */
function beginIdentityChange() {
  identityGeneration += 1;
  latestReadId = 0;
  activeOperation = null;
  authState.loading = false;
}

/** 结算身份变更：写入权威身份，并让该变更在途期间发起的读取失去写回权。 */
function settleIdentity(user: AuthUserResponse) {
  setCurrentUser(user);
  authState.initialized = true;
  settlementRevision += 1;
}

/** 结算失败的登录/退出：身份回到访客或不可用，同样使在途读取失去写回权。 */
function settleIdentityFailure(error: unknown, fallbackMessage: string) {
  authState.currentUser = { ...guestUser };
  authState.status = isAuthServiceUnavailable(error) ? 'unavailable' : 'anonymous';
  authState.error = getErrorMessage(error, fallbackMessage);
  authState.initialized = true;
  settlementRevision += 1;
}

/** 结束一次身份变更：只有该变更仍是最新操作时才收尾加载态并释放自己占用的操作槽。 */
function finishIdentityChange(generation: number, operationId: number) {
  if (generation !== identityGeneration) {
    return;
  }
  authState.loading = false;
  if (activeOperation?.id === operationId) {
    activeOperation = null;
  }
}

export function setGuestUser(message = '') {
  beginIdentityChange();
  authState.currentUser = { ...guestUser };
  authState.status = 'anonymous';
  authState.initialized = true;
  authState.error = message;
}

/**
 * 首次需要登录态时读取当前用户；已初始化后返回缓存。
 *
 * 登录/退出在途时不再发起竞争的读取，而是采用同一身份生命周期的结算结果，
 * 因此读取不会用变更前的会话回滚刚刚结算的身份。
 *
 * @return 当前权威用户；网络或服务错误会更新状态为未登录/不可用并返回访客用户
 */
export function ensureCurrentUser(): Promise<AuthUserResponse> {
  if (authState.initialized) {
    return Promise.resolve(authState.currentUser);
  }
  const operation = currentOperation();
  if (operation) {
    return operation.kind === 'read'
      ? operation.promise
      : operation.promise.then((user) => user, () => authState.currentUser);
  }
  return startCurrentUserRequest(false);
}

/**
 * 主动向服务端重新读取当前用户及权限，不受初始化缓存影响。
 *
 * @return 刷新后的当前用户
 * @throws 请求失败时更新认证状态后向调用方返回原错误，供页面提供独立重试
 */
export function refreshCurrentUser(): Promise<AuthUserResponse> {
  return startCurrentUserRequest(true);
}

function currentOperation() {
  const operation = activeOperation;
  return operation && operation.generation === identityGeneration ? operation : null;
}

function startCurrentUserRequest(throwOnFailure: boolean) {
  const read: ReadIdentity = {
    generation: identityGeneration,
    id: ++operationSequence,
    settlementRevision,
  };
  latestReadId = read.id;
  authState.loading = true;
  authState.status = 'checking';
  authState.error = '';

  const promise = Promise.resolve().then(async () => {
    try {
      const user = await authApi.current();
      if (isCurrentRead(read)) {
        setCurrentUser(user);
      }
      return await authoritativeUserAfter(read);
    } catch (error) {
      if (!isCurrentRead(read)) {
        if (error instanceof HttpRequestError && error.status === 401) throw error;
        return authoritativeUserAfter(read);
      }
      authState.currentUser = { ...guestUser };
      authState.status = isAuthServiceUnavailable(error) ? 'unavailable' : 'anonymous';
      authState.error = getErrorMessage(error, '获取登录状态失败');
      if (throwOnFailure) {
        throw error;
      }
      return authState.currentUser;
    } finally {
      if (isCurrentRead(read)) {
        authState.loading = false;
        authState.initialized = true;
        if (activeOperation?.id === read.id) activeOperation = null;
      }
    }
  });
  activeOperation = { kind: 'read', generation: read.generation, id: read.id, promise };
  return promise;
}

function isCurrentRead(read: ReadIdentity) {
  return read.generation === identityGeneration
    && read.id === latestReadId
    && read.settlementRevision === settlementRevision;
}

async function authoritativeUserAfter(read: ReadIdentity): Promise<AuthUserResponse> {
  if (isCurrentRead(read)) {
    return authState.currentUser;
  }
  const operation = currentOperation();
  if (operation?.kind === 'read' && operation.id > read.id) {
    return operation.promise;
  }
  return authState.currentUser;
}

/**
 * 使用用户名口令登录，并把本次身份变更登记进同一身份生命周期。
 *
 * @return 登录后的权威用户
 * @throws 登录失败时更新认证状态后向调用方返回原错误
 */
export function login(username: string, password: string): Promise<AuthUserResponse> {
  beginIdentityChange();
  const generation = identityGeneration;
  const operationId = ++operationSequence;
  authState.currentUser = { ...guestUser };
  authState.initialized = false;
  authState.loading = true;
  authState.status = 'checking';
  authState.error = '';

  const promise = (async () => {
    try {
      const user = await authApi.login({ username, password });
      if (generation === identityGeneration) {
        settleIdentity(user);
      }
      return authState.currentUser;
    } catch (error) {
      if (generation === identityGeneration) {
        settleIdentityFailure(error, '登录失败');
      }
      throw error;
    } finally {
      finishIdentityChange(generation, operationId);
    }
  })();
  activeOperation = { kind: 'login', generation, id: operationId, promise };
  return promise;
}

/**
 * 退出登录，并把本次身份变更登记进同一身份生命周期。
 *
 * @return 退出后的权威用户
 * @throws 退出失败时更新认证状态后向调用方返回原错误
 */
export function logout(): Promise<AuthUserResponse> {
  beginIdentityChange();
  const generation = identityGeneration;
  const operationId = ++operationSequence;
  authState.currentUser = { ...guestUser };
  authState.status = 'checking';
  authState.loading = true;
  authState.error = '';

  const promise = (async () => {
    try {
      const user = await authApi.logout();
      if (generation === identityGeneration) {
        settleIdentity(user);
      }
      return authState.currentUser;
    } catch (error) {
      if (generation !== identityGeneration) {
        return authState.currentUser;
      }
      settleIdentityFailure(error, '退出登录失败');
      // 服务端本就没有会话时，目标状态已经达成，不再提示错误。
      if (error instanceof HttpRequestError && error.status === 401) {
        authState.error = '';
        return authState.currentUser;
      }
      throw error;
    } finally {
      finishIdentityChange(generation, operationId);
    }
  })();
  activeOperation = { kind: 'logout', generation, id: operationId, promise };
  return promise;
}

function isAuthServiceUnavailable(error: unknown) {
  return !(error instanceof HttpRequestError) || error.status >= 500;
}
