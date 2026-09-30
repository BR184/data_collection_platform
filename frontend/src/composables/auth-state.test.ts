import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AuthUserResponse } from '../api-client/auth-api';

const mocks = vi.hoisted(() => ({
  current: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
}));

vi.mock('../api-client/auth-api', () => ({
  authApi: { current: mocks.current, login: mocks.login, logout: mocks.logout },
  guestUser: {
    username: 'guest', displayName: '游客', roleCodes: [], roleNames: [], permissions: [], authenticated: false,
  },
}));

import { authState, ensureCurrentUser, login, logout, refreshCurrentUser, setGuestUser } from './auth-state';

function user(username: string, permissions: string[] = []): AuthUserResponse {
  return {
    username,
    displayName: username,
    roleCodes: ['ADMIN'],
    roleNames: ['管理员'],
    permissions,
    authenticated: true,
  };
}

function guest(): AuthUserResponse {
  return {
    username: 'guest', displayName: '游客', roleCodes: [], roleNames: [], permissions: [], authenticated: false,
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

beforeEach(() => {
  setGuestUser();
  authState.currentUser = {
    username: 'guest', displayName: '游客', roleCodes: [], roleNames: [], permissions: [], authenticated: false,
  };
  authState.status = 'unknown';
  authState.loading = false;
  authState.error = '';
  authState.initialized = false;
  mocks.current.mockReset();
  mocks.login.mockReset();
  mocks.logout.mockReset();
});

describe('auth-state identity-bound current user requests', () => {
  it('coalesces initialization and then serves the initialized user from cache', async () => {
    const pending = deferred<AuthUserResponse>();
    mocks.current.mockReturnValue(pending.promise);

    const first = ensureCurrentUser();
    const second = ensureCurrentUser();
    await Promise.resolve();
    expect(mocks.current).toHaveBeenCalledTimes(1);

    pending.resolve(user('alice', ['read']));
    await Promise.all([first, second]);
    expect((await ensureCurrentUser()).username).toBe('alice');
    expect(mocks.current).toHaveBeenCalledTimes(1);
  });

  it('always contacts the server when refreshing an initialized user', async () => {
    mocks.current.mockResolvedValueOnce(user('alice', ['old']));
    await ensureCurrentUser();
    const refreshed = deferred<AuthUserResponse>();
    mocks.current.mockReturnValueOnce(refreshed.promise);

    const refresh = refreshCurrentUser();
    await Promise.resolve();
    expect(mocks.current).toHaveBeenCalledTimes(2);
    refreshed.resolve(user('alice', ['new']));
    await refresh;

    expect(authState.currentUser.permissions).toEqual(['new']);
    expect(authState.status).toBe('authenticated');
  });

  it('prevents a late initialization response from replacing a newer refresh', async () => {
    const initialization = deferred<AuthUserResponse>();
    const refresh = deferred<AuthUserResponse>();
    mocks.current.mockReturnValueOnce(initialization.promise).mockReturnValueOnce(refresh.promise);

    const oldRequest = ensureCurrentUser();
    const currentRequest = refreshCurrentUser();
    await Promise.resolve();
    refresh.resolve(user('alice', ['refreshed']));
    await currentRequest;
    initialization.resolve(user('alice', ['stale']));
    await oldRequest;

    expect(authState.currentUser.permissions).toEqual(['refreshed']);
  });

  it('does not restore the old user when logout overtakes a refresh', async () => {
    mocks.current.mockResolvedValueOnce(user('alice', ['allowed']));
    await ensureCurrentUser();
    const refresh = deferred<AuthUserResponse>();
    const logoutRequest = deferred<AuthUserResponse>();
    mocks.current.mockReturnValueOnce(refresh.promise);
    mocks.logout.mockReturnValue(logoutRequest.promise);

    const oldRefresh = refreshCurrentUser();
    const logoutResult = logout();
    logoutRequest.resolve({
      username: 'guest', displayName: '游客', roleCodes: [], roleNames: [], permissions: [], authenticated: false,
    });
    await logoutResult;
    refresh.resolve(user('alice', ['late']));
    await oldRefresh;

    expect(authState.currentUser.authenticated).toBe(false);
    expect(authState.currentUser.permissions).toEqual([]);
    expect(authState.loading).toBe(false);
  });

  it('keeps the newer login loading state when an older login or refresh settles', async () => {
    const oldRefresh = deferred<AuthUserResponse>();
    const newRefresh = deferred<AuthUserResponse>();
    mocks.current.mockReturnValueOnce(oldRefresh.promise).mockReturnValueOnce(newRefresh.promise);
    const first = refreshCurrentUser();
    const second = refreshCurrentUser();
    await Promise.resolve();
    oldRefresh.resolve(user('alice', ['old']));
    await Promise.resolve();
    await Promise.resolve();
    expect(authState.loading).toBe(true);

    newRefresh.resolve(user('alice', ['new']));
    await Promise.all([first, second]);
    expect(authState.loading).toBe(false);
    expect(authState.currentUser.permissions).toEqual(['new']);

    const oldLogin = deferred<AuthUserResponse>();
    const newLogin = deferred<AuthUserResponse>();
    mocks.login.mockReturnValueOnce(oldLogin.promise).mockReturnValueOnce(newLogin.promise);
    const firstLogin = login('alice', 'secret');
    const secondLogin = login('bob', 'secret');
    oldLogin.resolve(user('alice'));
    await firstLogin;
    expect(authState.loading).toBe(true);
    newLogin.resolve(user('bob'));
    await secondLogin;
    expect(authState.currentUser.username).toBe('bob');
    expect(authState.loading).toBe(false);
  });

  it('invalidates a pending initialization when a different user logs in', async () => {
    const initialization = deferred<AuthUserResponse>();
    const loginRequest = deferred<AuthUserResponse>();
    mocks.current.mockReturnValue(initialization.promise);
    mocks.login.mockReturnValue(loginRequest.promise);

    const oldRequest = ensureCurrentUser();
    const newLogin = login('bob', 'secret');
    initialization.resolve(user('alice', ['old']));
    await oldRequest;
    loginRequest.resolve(user('bob', ['new']));
    await newLogin;

    expect(authState.currentUser.username).toBe('bob');
    expect(authState.currentUser.permissions).toEqual(['new']);
  });

  it('makes a failed explicit refresh fail closed and reject for visible feedback', async () => {
    mocks.current.mockResolvedValueOnce(user('alice', ['admin']));
    await ensureCurrentUser();
    mocks.current.mockRejectedValueOnce(new Error('network unavailable'));

    await expect(refreshCurrentUser()).rejects.toThrow('network unavailable');

    expect(authState.currentUser.authenticated).toBe(false);
    expect(authState.status).toBe('unavailable');
    expect(authState.error).toContain('network unavailable');
    expect(authState.initialized).toBe(true);
  });

  it('does not start a competing current request while a login is in flight', async () => {
    const loginRequest = deferred<AuthUserResponse>();
    mocks.login.mockReturnValue(loginRequest.promise);

    const loginPromise = login('bob', 'secret');
    const joined = ensureCurrentUser();
    await Promise.resolve();

    expect(mocks.current).not.toHaveBeenCalled();
    expect(authState.loading).toBe(true);

    loginRequest.resolve(user('bob', ['new']));
    await loginPromise;

    expect((await joined).username).toBe('bob');
    expect(authState.currentUser.permissions).toEqual(['new']);
    expect(authState.initialized).toBe(true);
    expect(authState.loading).toBe(false);
  });

  it('does not let a current request in flight during a login roll back the settled login', async () => {
    const loginRequest = deferred<AuthUserResponse>();
    const lateCurrent = deferred<AuthUserResponse>();
    mocks.login.mockReturnValue(loginRequest.promise);
    mocks.current.mockReturnValue(lateCurrent.promise);

    const loginPromise = login('bob', 'secret');
    const lateRead = refreshCurrentUser();
    await Promise.resolve();

    loginRequest.resolve(user('bob', ['new']));
    await loginPromise;
    expect(authState.currentUser.username).toBe('bob');

    lateCurrent.resolve(user('alice', ['old']));
    await lateRead;

    expect(authState.currentUser.username).toBe('bob');
    expect(authState.currentUser.permissions).toEqual(['new']);
    expect(authState.initialized).toBe(true);
  });

  it('does not let a current request in flight during a logout roll back the settled logout', async () => {
    mocks.current.mockResolvedValueOnce(user('alice', ['allowed']));
    await ensureCurrentUser();
    const logoutRequest = deferred<AuthUserResponse>();
    const lateCurrent = deferred<AuthUserResponse>();
    mocks.logout.mockReturnValue(logoutRequest.promise);
    mocks.current.mockReturnValue(lateCurrent.promise);

    const logoutPromise = logout();
    const lateRead = refreshCurrentUser();
    await Promise.resolve();

    logoutRequest.resolve(guest());
    await logoutPromise;

    lateCurrent.resolve(user('alice', ['late']));
    await lateRead;

    expect(authState.currentUser.authenticated).toBe(false);
    expect(authState.currentUser.permissions).toEqual([]);
    expect(authState.loading).toBe(false);
    expect(authState.initialized).toBe(true);
  });

  it('hands a failed login to a reader that joined it without rejecting', async () => {
    const loginRequest = deferred<AuthUserResponse>();
    mocks.login.mockReturnValue(loginRequest.promise);

    const loginPromise = login('bob', 'secret');
    const joined = ensureCurrentUser();
    loginRequest.reject(new Error('bad credentials'));

    await expect(loginPromise).rejects.toThrow('bad credentials');
    expect((await joined).authenticated).toBe(false);
    expect(authState.error).toContain('bad credentials');
    expect(authState.loading).toBe(false);
  });
});
