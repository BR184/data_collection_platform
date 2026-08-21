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

let currentUserRequest: Promise<AuthUserResponse> | null = null;

function setCurrentUser(user: AuthUserResponse) {
  authState.currentUser = user;
  authState.status = user.authenticated ? 'authenticated' : 'anonymous';
  authState.error = '';
}

export function setGuestUser(message = '') {
  authState.currentUser = { ...guestUser };
  authState.status = 'anonymous';
  authState.initialized = true;
  authState.error = message;
}

export async function loadCurrentUser() {
  if (authState.initialized) {
    return authState.currentUser;
  }
  if (currentUserRequest) {
    return currentUserRequest;
  }
  authState.loading = true;
  authState.status = 'checking';
  currentUserRequest = (async () => {
    try {
      const user = await authApi.current();
      setCurrentUser(user);
      return user;
    } catch (error) {
      authState.currentUser = { ...guestUser };
      authState.status = isAuthServiceUnavailable(error) ? 'unavailable' : 'anonymous';
      authState.error = getErrorMessage(error, '获取登录状态失败');
      return authState.currentUser;
    } finally {
      authState.loading = false;
      authState.initialized = true;
      currentUserRequest = null;
    }
  })();
  return currentUserRequest;
}

export async function login(username: string, password: string) {
  authState.loading = true;
  authState.status = 'checking';
  try {
    const user = await authApi.login({ username, password });
    setCurrentUser(user);
    authState.initialized = true;
    return user;
  } catch (error) {
    authState.status = isAuthServiceUnavailable(error) ? 'unavailable' : 'anonymous';
    authState.error = getErrorMessage(error, '登录失败');
    throw error;
  } finally {
    authState.loading = false;
  }
}

export async function logout() {
  authState.loading = true;
  try {
    setCurrentUser(await authApi.logout());
  } catch (error) {
    authState.currentUser = { ...guestUser };
    authState.status = isAuthServiceUnavailable(error) ? 'unavailable' : 'anonymous';
    authState.error = getErrorMessage(error, '退出登录失败');
    // A missing server session already represents the desired logged-out state.
    // Treat it as an idempotent logout; transport/server failures still reach
    // the caller so the UI can report that the operation did not complete.
    if (error instanceof HttpRequestError && error.status === 401) {
      authState.error = '';
      return authState.currentUser;
    }
    throw error;
  } finally {
    authState.loading = false;
    authState.initialized = true;
  }
}

function isAuthServiceUnavailable(error: unknown) {
  return !(error instanceof HttpRequestError) || error.status >= 500;
}
