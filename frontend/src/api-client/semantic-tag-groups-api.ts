import type { SemanticTagGroupCatalogResponse } from '../types/api';
import { request } from './request';

export const semanticTagGroupsApi = {
  getStaticSemanticTagGroups(entityType = 'issue') {
    const query = new URLSearchParams({ entityType });
    return request<SemanticTagGroupCatalogResponse>(
      `/api/semantic-tag-groups/static?${query.toString()}`,
    );
  },
};
