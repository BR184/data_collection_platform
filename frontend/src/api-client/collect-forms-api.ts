import type {
  CollectFormNotificationPayloadResponse,
  FormAdminBindingOption,
  FormAdminMilestoneAvailability,
  FormAdminPreviewView,
  FormAdminProjectCandidatePage,
  FormAdminPublicationView,
  FormAdminScopeDetail,
  FormAdminTemplateSummary,
  FormCreateResponse,
  FormDetailResponse,
  FormDraftUpdatePayload,
  FormPublicationCreatePayload,
  FormRecordView,
  FormReviewerCandidatesResponse,
} from '../types/api';
import {
  EXPORT_REQUEST_TIMEOUT_MS,
  request,
  requestBlobResponse,
} from './request';

export interface FormContextPayload {
  gitlabBaseUrl: string;
  projectId: number;
  resourceType: string;
  resourceId: string;
  templateKey: string;
}

export interface FormCreatePayload extends FormContextPayload {
  scopeId: string;
  storageVersionId: string;
  requestKey: string;
  confirmSameReviewer: boolean;
  fields: Record<string, unknown>;
}

export interface FormUpdatePayload extends FormContextPayload {
  scopeId: string;
  storageVersionId: string;
  expectedStorageVersionId: string;
  recordVersion: number;
  fields: Record<string, unknown>;
}

export interface FormVoidPayload extends FormContextPayload {
  scopeId: string;
  storageVersionId: string;
  expectedStorageVersionId: string;
  recordVersion: number;
  confirmVoid: boolean;
  confirmationText: string;
}

/** 来源与项目共用的议题接入配置；enabledAt为带时区的ISO时刻，revision用于并发保存。 */
export interface FormIssueSettings {
  enabled: boolean;
  enabledAt: string | null;
  linkEnabled: boolean;
  externalBaseUrl: string;
  revision: number;
}
/** ready表示该项目响应、回复均具备唯一有效的已发布范围。 */
export interface FormIssueSettingsView { settings: FormIssueSettings; ready: boolean }

export const collectFormsApi = {
  /** 通过已绑定范围读取该项目响应/回复共用设置。 */
  getIssueSettings(scopeId: string, projectId: string) {
    return request<FormIssueSettingsView>(`/api/collect-forms/template-scopes/${encodeURIComponent(scopeId)}/issue-settings/${encodeURIComponent(projectId)}`);
  },
  /** 按expectedRevision保存；冲突时保留本地输入，由管理员重新加载。 */
  saveIssueSettings(scopeId: string, projectId: string, payload: Omit<FormIssueSettings, 'revision'> & { expectedRevision: number }) {
    return request<FormIssueSettingsView>(`/api/collect-forms/template-scopes/${encodeURIComponent(scopeId)}/issue-settings/${encodeURIComponent(projectId)}`, {
      method: 'PUT', body: JSON.stringify(payload),
    });
  },
  getFormDetail(params: FormContextPayload) {
    const query = new URLSearchParams({
      gitlabBaseUrl: params.gitlabBaseUrl,
      projectId: String(params.projectId),
      resourceType: params.resourceType,
      resourceId: params.resourceId,
      templateKey: params.templateKey,
    });
    return request<FormDetailResponse>(`/api/collect-forms/detail?${query.toString()}`);
  },
  createFormRecord(payload: FormCreatePayload) {
    return request<FormCreateResponse>('/api/collect-forms/records', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },
  updateFormRecord(id: string, payload: FormUpdatePayload) {
    return request<FormRecordView>(`/api/collect-forms/records/${encodeURIComponent(id)}`, {
      method: 'PUT',
      body: JSON.stringify(payload),
    });
  },
  voidFormRecord(id: string, payload: FormVoidPayload) {
    return request<FormRecordView>(
      `/api/collect-forms/records/${encodeURIComponent(id)}/void`,
      {
        method: 'POST',
        body: JSON.stringify(payload),
      },
    );
  },
  getFormReviewerCandidates(params: {
    keyword?: string;
    limit?: number;
    cursor?: string;
  }) {
    const query = new URLSearchParams();
    if (params.keyword) {
      query.set('keyword', params.keyword);
    }
    if (params.limit) {
      query.set('limit', String(params.limit));
    }
    if (params.cursor) {
      query.set('cursor', params.cursor);
    }
    const suffix = query.toString();
    return request<FormReviewerCandidatesResponse>(
      `/api/collect-forms/reviewer-candidates${suffix ? `?${suffix}` : ''}`,
    );
  },
  getCollectFormNotificationPayload(params: {
    gitlabBaseUrl: string;
    projectId: number;
    requestIid: number;
    resourceType: string;
  }) {
    const query = new URLSearchParams({
      gitlabBaseUrl: params.gitlabBaseUrl,
      projectId: String(params.projectId),
      requestIid: String(params.requestIid),
      resourceType: params.resourceType,
    });
    return request<CollectFormNotificationPayloadResponse>(
      `/api/collect-forms/notification-payload?${query.toString()}`,
    );
  },
  getFormTemplates() {
    return request<FormAdminTemplateSummary[]>('/api/collect-forms/templates');
  },
  getFormEntries(context: { resourceType: string; gitlabBaseUrl: string; projectId: string }) {
    return request<Array<{ templateKey: string; title: string }>>(
      `/api/collect-forms/entries?${new URLSearchParams(context).toString()}`,
    );
  },
  createFormTemplate(payload: {
    displayName: string;
    resourceType: 'issue' | 'merge_request';
    fillPolicy: 'AUTHORIZED_USER' | 'RESOURCE_AUTHOR';
    copyFromVersionId: number | null;
  }) {
    return request<FormAdminScopeDetail>('/api/collect-forms/templates', {
      method: 'POST', body: JSON.stringify(payload),
    });
  },
  getFormScope(scopeId: number) {
    return request<FormAdminScopeDetail>(`/api/collect-forms/template-scopes/${scopeId}`);
  },
  createFormScope(payload: { templateKey: string; scopeKey: string }) {
    return request<FormAdminScopeDetail>('/api/collect-forms/template-scopes', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },
  saveFormDraft(scopeId: number, payload: FormDraftUpdatePayload) {
    return request<FormAdminScopeDetail['draft']>(
      `/api/collect-forms/template-scopes/${scopeId}/draft`,
      { method: 'PUT', body: JSON.stringify(payload) },
    );
  },
  previewFormScope(scopeId: number, draftRevision: number) {
    return request<FormAdminPreviewView>(
      `/api/collect-forms/template-scopes/${scopeId}/preview`,
      { method: 'POST', body: JSON.stringify({ draftRevision }) },
    );
  },
  createFormPublication(scopeId: number, payload: FormPublicationCreatePayload) {
    return request<FormAdminPublicationView>(
      `/api/collect-forms/template-scopes/${scopeId}/publications`,
      { method: 'POST', body: JSON.stringify(payload) },
    );
  },
  getFormPublication(publicationId: number) {
    return request<FormAdminPublicationView>(
      `/api/collect-forms/publications/${publicationId}`,
    );
  },
  recoverFormPublication(publicationId: number) {
    return request<FormAdminPublicationView>(
      `/api/collect-forms/publications/${publicationId}/recover`,
      { method: 'POST', body: '{}' },
    );
  },
  retireFormScope(scopeId: number, expectedCurrentVersionId: number) {
    return request<FormAdminScopeDetail>(
      `/api/collect-forms/template-scopes/${scopeId}/retire`,
      { method: 'POST', body: JSON.stringify({ expectedCurrentVersionId }) },
    );
  },
  deleteFormScope(scopeId: number) {
    return request<void>(`/api/collect-forms/template-scopes/${scopeId}`, {
      method: 'DELETE',
    });
  },
  getFormBindingOptions() {
    return request<FormAdminBindingOption[]>('/api/collect-forms/binding-options');
  },
  getFormMilestones(sourceConfigId: number, projectId: number) {
    const query = new URLSearchParams({
      sourceConfigId: String(sourceConfigId),
      projectId: String(projectId),
    });
    return request<FormAdminMilestoneAvailability>(
      `/api/collect-forms/version-milestones?${query.toString()}`,
    );
  },
  getFormProjectCandidates(params: {
    sourceConfigId: number;
    keyword?: string;
    size?: number;
    cursor?: string;
    projectIds?: Array<number | string>;
  }) {
    const query = new URLSearchParams({ sourceConfigId: String(params.sourceConfigId) });
    if (params.keyword?.trim()) {
      query.set('keyword', params.keyword.trim());
    }
    if (params.size) {
      query.set('size', String(params.size));
    }
    if (params.cursor) {
      query.set('cursor', params.cursor);
    }
    if (params.projectIds?.length) {
      query.set('projectIds', params.projectIds.join(','));
    }
    return request<FormAdminProjectCandidatePage>(
      `/api/collect-forms/admin/projects?${query.toString()}`,
    );
  },
  exportFormRecords(versionId: number, projectIds: Array<number | string>) {
    const query = new URLSearchParams({ versionId: String(versionId) });
    if (projectIds.length) {
      query.set('projectIds', projectIds.join(','));
    }
    return requestBlobResponse(`/api/collect-forms/exports/records?${query.toString()}`, {
      errorPrefix: '表单记录导出失败',
      timeoutMs: EXPORT_REQUEST_TIMEOUT_MS,
    });
  },
};
