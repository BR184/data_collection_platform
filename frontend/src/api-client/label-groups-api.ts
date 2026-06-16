import type {
  LabelDimension,
  LabelGroup,
  LabelGroupDynamicRuleRelation,
  LabelGroupDynamicRulePreview,
  LabelGroupDynamicRulePreviewRequest,
  LabelGroupCompatiblePage,
  LabelGroupDynamicRuleSource,
  LabelGroupExpansion,
  LabelGroupSaveRequest,
  LabelValuePage,
} from '../types/api';
import { request } from './request';

export interface LabelValueQueryParams {
  pageKey?: string;
  sourceInstanceId?: string;
  keyword?: string;
  page?: number;
  size?: number;
}

export interface LabelGroupListParams {
  valueType?: string;
  keyword?: string;
  enabled?: boolean;
}

export const labelGroupsApi = {
  listLabelDimensions() {
    return request<LabelDimension[]>('/api/label-groups/dimensions');
  },
  listLabelDimensionValues(dimensionKey: string, params: LabelValueQueryParams = {}) {
    const query = new URLSearchParams({
      page: String(params.page ?? 1),
      size: String(params.size ?? 20),
      ...(params.pageKey ? { pageKey: params.pageKey } : {}),
      ...(params.sourceInstanceId ? { sourceInstanceId: params.sourceInstanceId } : {}),
      ...(params.keyword ? { keyword: params.keyword } : {}),
    });
    return request<LabelValuePage>(
      `/api/label-groups/dimensions/${encodeURIComponent(dimensionKey)}/values?${query.toString()}`,
    );
  },
  listLabelGroupCompatiblePages(dimensionKey: string) {
    return request<LabelGroupCompatiblePage[]>(
      `/api/label-groups/dimensions/${encodeURIComponent(dimensionKey)}/compatible-pages`,
    );
  },
  listDynamicRuleSources() {
    return request<LabelGroupDynamicRuleSource[]>('/api/label-groups/dynamic-rule-sources');
  },
  listDynamicRuleRelations() {
    return request<LabelGroupDynamicRuleRelation[]>('/api/label-groups/dynamic-rule-relations');
  },
  listDynamicRuleFieldCandidates(sourceKey: string, fieldKey: string, params: LabelValueQueryParams = {}) {
    const query = new URLSearchParams({
      page: String(params.page ?? 1),
      size: String(params.size ?? 50),
      ...(params.keyword ? { keyword: params.keyword } : {}),
    });
    return request<LabelValuePage>(
      `/api/label-groups/dynamic-rule-sources/${encodeURIComponent(sourceKey)}/fields/${encodeURIComponent(fieldKey)}/candidates?${query.toString()}`,
    );
  },
  previewDynamicRule(payload: LabelGroupDynamicRulePreviewRequest) {
    return request<LabelGroupDynamicRulePreview>('/api/label-groups/dynamic-rule-preview', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },
  listLabelGroups(params: LabelGroupListParams = {}) {
    const query = new URLSearchParams({
      ...(params.valueType ? { valueType: params.valueType } : {}),
      ...(params.keyword ? { keyword: params.keyword } : {}),
      ...(params.enabled !== undefined ? { enabled: String(params.enabled) } : {}),
    });
    const suffix = query.size > 0 ? `?${query.toString()}` : '';
    return request<LabelGroup[]>(`/api/label-groups${suffix}`);
  },
  createLabelGroup(payload: LabelGroupSaveRequest) {
    return request<LabelGroup>('/api/label-groups', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },
  updateLabelGroup(groupId: number, payload: LabelGroupSaveRequest) {
    return request<LabelGroup>(`/api/label-groups/${groupId}`, {
      method: 'PUT',
      body: JSON.stringify(payload),
    });
  },
  deleteLabelGroup(groupId: number) {
    return request<void>(`/api/label-groups/${groupId}`, {
      method: 'DELETE',
    });
  },
  expandLabelGroup(
      groupId: number,
      params: { valueType?: string; fieldKey?: string; pageKey?: string; sourceInstanceId?: string } = {},
  ) {
    const query = new URLSearchParams({
      ...(params.valueType ? { valueType: params.valueType } : {}),
      ...(params.fieldKey ? { fieldKey: params.fieldKey } : {}),
      ...(params.pageKey ? { pageKey: params.pageKey } : {}),
      ...(params.sourceInstanceId ? { sourceInstanceId: params.sourceInstanceId } : {}),
    });
    const suffix = query.size > 0 ? `?${query.toString()}` : '';
    return request<LabelGroupExpansion>(`/api/label-groups/${groupId}/expand${suffix}`, {
      method: 'POST',
    });
  },
};
