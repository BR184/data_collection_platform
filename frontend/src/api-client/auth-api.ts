import type { UserRole } from '../feature-manifest';
import { request } from './request';

export interface AuthUserResponse {
  username: string;
  displayName: string;
  role: UserRole;
  authenticated: boolean;
}

export const guestUser: AuthUserResponse = {
  username: 'guest',
  displayName: '游客',
  role: 'GUEST',
  authenticated: false,
};

export const authApi = {
  current() {
    return request<AuthUserResponse>('/api/auth/current');
  },
  login(payload: { username: string; password: string }) {
    return request<AuthUserResponse>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },
  logout() {
    return request<AuthUserResponse>('/api/auth/logout', {
      method: 'POST',
    });
  },
};
