import type { TagGroupsResponse } from '../types/api';
import { request } from './request';

export const tagGroupsApi = {
  getTagGroups(domain: string) {
    const query = new URLSearchParams({ domain });
    return request<TagGroupsResponse>(`/api/tag-groups?${query.toString()}`);
  },
  reloadTagMappings() {
    return request<void>('/api/admin/reload-tag-mappings', {
      method: 'POST',
    });
  },
};
