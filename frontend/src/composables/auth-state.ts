import { reactive } from 'vue';
import { authApi, guestUser, type AuthUserResponse } from '../api-client/auth-api';

export const authState = reactive({
  currentUser: { ...guestUser } as AuthUserResponse,
  loading: false,
  error: '',
  initialized: false,
});

function setCurrentUser(user: AuthUserResponse) {
  authState.currentUser = user;
  authState.error = '';
}

export async function loadCurrentUser() {
  authState.loading = true;
  try {
    setCurrentUser(await authApi.current());
  } catch (error) {
    authState.error = error instanceof Error ? error.message : '获取登录状态失败';
    setCurrentUser({ ...guestUser });
  } finally {
    authState.loading = false;
    authState.initialized = true;
  }
}

export async function login(username: string, password: string) {
  authState.loading = true;
  try {
    const user = await authApi.login({ username, password });
    setCurrentUser(user);
    authState.initialized = true;
    return user;
  } catch (error) {
    authState.error = error instanceof Error ? error.message : '登录失败';
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
    authState.error = error instanceof Error ? error.message : '退出登录失败';
    setCurrentUser({ ...guestUser });
  } finally {
    authState.loading = false;
    authState.initialized = true;
  }
}
