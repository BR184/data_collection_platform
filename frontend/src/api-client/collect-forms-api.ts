import type {
  CollectFormNotificationPayloadResponse,
  FormAdminBindingOption,
  FormAdminMilestoneAvailability,
  FormAdminPreviewView,
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
import { request } from './request';

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

export const collectFormsApi = {
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
};
