import { request } from './request';

export interface AuthUserResponse {
  username: string;
  displayName: string;
  roleCodes: string[];
  roleNames: string[];
  permissions: string[];
  authenticated: boolean;
}

export const guestUser: AuthUserResponse = {
  username: 'guest',
  displayName: '游客',
  roleCodes: [],
  roleNames: [],
  permissions: [],
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
