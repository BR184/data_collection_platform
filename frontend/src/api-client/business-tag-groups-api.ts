import type {
  BusinessTagGroupApplyResponse,
  BusinessTagGroupResponse,
  BusinessTagGroupSaveRequest,
  BusinessTagGroupUpdateRequest,
} from '../types/api';
import { request } from './request';

export interface BusinessTagGroupListParams {
  entityType?: string;
  scenarioKey?: string;
  ownerUserId?: string;
}

function buildQuery(params: BusinessTagGroupListParams = {}) {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value) {
      query.set(key, value);
    }
  }
  const queryText = query.toString();
  return queryText ? `?${queryText}` : '';
}

export const businessTagGroupsApi = {
  listBusinessTagGroups(params?: BusinessTagGroupListParams) {
    return request<BusinessTagGroupResponse[]>(`/api/business-tag-groups${buildQuery(params)}`);
  },

  createBusinessTagGroup(payload: BusinessTagGroupSaveRequest) {
    return request<BusinessTagGroupResponse>('/api/business-tag-groups', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },

  updateBusinessTagGroup(id: number, payload: BusinessTagGroupUpdateRequest) {
    return request<BusinessTagGroupResponse>(`/api/business-tag-groups/${id}`, {
      method: 'PATCH',
      body: JSON.stringify(payload),
    });
  },

  deleteBusinessTagGroup(id: number) {
    return request<void>(`/api/business-tag-groups/${id}`, {
      method: 'DELETE',
    });
  },

  applyBusinessTagGroup(id: number, currentTagSchemaHash?: string) {
    const query = currentTagSchemaHash
      ? `?${new URLSearchParams({ currentTagSchemaHash }).toString()}`
      : '';
    return request<BusinessTagGroupApplyResponse>(`/api/business-tag-groups/${id}/apply${query}`, {
      method: 'POST',
    });
  },
};
