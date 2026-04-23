import type { CollectFormDetailResponse, CollectFormNotificationPayloadResponse } from '../types/api';
import { request } from './request';

export const collectFormsApi = {
  getCollectFormDetail(params: {
    gitlabBaseUrl: string;
    projectId: number;
    resourceType: string;
    resourceId: string;
    templateCode: string;
  }) {
    const query = new URLSearchParams({
      gitlabBaseUrl: params.gitlabBaseUrl,
      projectId: String(params.projectId),
      resourceType: params.resourceType,
      resourceId: params.resourceId,
      templateCode: params.templateCode,
    });
    return request<CollectFormDetailResponse | null>(`/api/collect-forms/detail?${query.toString()}`);
  },
  saveCollectForm(payload: {
    gitlabBaseUrl: string;
    projectId: number;
    requestIid?: number | null;
    resourceType: string;
    resourceId: string;
    templateCode: string;
    formTitle: string;
    reviewer: string;
    reviewDurationMinutes: number;
    specificationScore: number;
    logicScore: number;
    performanceScore: number;
    designScore: number;
    otherScore: number;
    remark: string;
  }) {
    return request<CollectFormDetailResponse>('/api/collect-forms/save', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },
  updateCollectFormRecord(payload: {
    id: number;
    formTitle: string;
    reviewer: string;
    reviewDurationMinutes: number;
    specificationScore: number;
    logicScore: number;
    performanceScore: number;
    designScore: number;
    otherScore: number;
    remark: string;
    deleted: boolean;
  }) {
    return request<CollectFormDetailResponse>('/api/collect-forms/update-record', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },
  deleteCollectForm(payload: {
    gitlabBaseUrl: string;
    projectId: number;
    resourceType: string;
    resourceId: string;
    templateCode: string;
  }) {
    return request<boolean>('/api/collect-forms/delete', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
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
};
