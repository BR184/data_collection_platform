import type {
  IssueScopeCatalogResponse,
  IssueScopeCatalogSaveRequest,
  IssueScopeDiscoveredValueResponse,
  IssueScopeGroupResponse,
  IssueScopeGroupSaveRequest,
  IssueScopeMemberResponse,
  IssueScopeMemberSaveRequest,
} from '../types/api';
import { request } from './request';

function queryString(params?: { keyword?: string; enabled?: string | boolean | null }) {
  const query = new URLSearchParams({
    ...(params?.keyword ? { keyword: params.keyword } : {}),
    ...(params?.enabled != null && params.enabled !== '' ? { enabled: String(params.enabled) } : {}),
  });
  return query.toString() ? `?${query.toString()}` : '';
}

export const issueScopesApi = {
  getIssueScopeCatalogs() {
    return request<IssueScopeCatalogResponse[]>('/api/issue-scopes/catalogs');
  },
  createIssueScopeCatalog(payload: IssueScopeCatalogSaveRequest) {
    return request<IssueScopeCatalogResponse>('/api/issue-scopes/catalogs', { method: 'POST', body: JSON.stringify(payload) });
  },
  updateIssueScopeCatalog(id: number, payload: IssueScopeCatalogSaveRequest) {
    return request<IssueScopeCatalogResponse>(`/api/issue-scopes/catalogs/${id}`, { method: 'PUT', body: JSON.stringify(payload) });
  },
  setIssueScopeCatalogEnabled(id: number, enabled: boolean) {
    return request<IssueScopeCatalogResponse>(`/api/issue-scopes/catalogs/${id}/enabled`, { method: 'PATCH', body: JSON.stringify({ enabled }) });
  },
  getIssueScopeGroups(catalogId: number, params?: { keyword?: string; enabled?: string | boolean | null }) {
    return request<IssueScopeGroupResponse[]>(`/api/issue-scopes/catalogs/${catalogId}/groups${queryString(params)}`);
  },
  getUnassignedIssueScopeValues(catalogId: number) {
    return request<IssueScopeDiscoveredValueResponse[]>(`/api/issue-scopes/catalogs/${catalogId}/unassigned-values`);
  },
  createIssueScopeGroup(payload: IssueScopeGroupSaveRequest) {
    return request<IssueScopeGroupResponse>('/api/issue-scopes/groups', { method: 'POST', body: JSON.stringify(payload) });
  },
  updateIssueScopeGroup(id: number, payload: IssueScopeGroupSaveRequest) {
    return request<IssueScopeGroupResponse>(`/api/issue-scopes/groups/${id}`, { method: 'PUT', body: JSON.stringify(payload) });
  },
  setIssueScopeGroupEnabled(id: number, enabled: boolean) {
    return request<IssueScopeGroupResponse>(`/api/issue-scopes/groups/${id}/enabled`, { method: 'PATCH', body: JSON.stringify({ enabled }) });
  },
  reorderIssueScopeGroups(catalogId: number, ids: number[]) {
    return request<void>(`/api/issue-scopes/catalogs/${catalogId}/group-order`, { method: 'PUT', body: JSON.stringify({ ids }) });
  },
  deleteIssueScopeGroup(id: number) { return request<void>(`/api/issue-scopes/groups/${id}`, { method: 'DELETE' }); },
  createIssueScopeMember(payload: IssueScopeMemberSaveRequest) {
    return request<IssueScopeMemberResponse>('/api/issue-scopes/members', { method: 'POST', body: JSON.stringify(payload) });
  },
  updateIssueScopeMember(id: number, payload: IssueScopeMemberSaveRequest) {
    return request<IssueScopeMemberResponse>(`/api/issue-scopes/members/${id}`, { method: 'PUT', body: JSON.stringify(payload) });
  },
  setIssueScopeMemberEnabled(id: number, enabled: boolean) {
    return request<IssueScopeMemberResponse>(`/api/issue-scopes/members/${id}/enabled`, { method: 'PATCH', body: JSON.stringify({ enabled }) });
  },
  reorderIssueScopeMembers(groupId: number, ids: number[]) {
    return request<void>(`/api/issue-scopes/groups/${groupId}/member-order`, { method: 'PUT', body: JSON.stringify({ ids }) });
  },
  deleteIssueScopeMember(id: number) { return request<void>(`/api/issue-scopes/members/${id}`, { method: 'DELETE' }); },
};
