import type {
  TestingPhaseDefinitionResponse,
  TestingPhaseDefinitionSaveRequest,
  TestingPhaseGroupResponse,
  TestingPhaseGroupSaveRequest,
  TestingPhaseProjectOptionResponse,
} from '../types/api';
import { request } from './request';

export const testingPhasesApi = {
  getTestingPhases(params?: {
    projectId?: string | number | null;
    keyword?: string;
    enabled?: string | boolean | null;
  }) {
    const query = new URLSearchParams({
      ...(params?.projectId != null && params.projectId !== '' ? { projectId: String(params.projectId) } : {}),
      ...(params?.keyword ? { keyword: params.keyword } : {}),
      ...(params?.enabled != null && params.enabled !== '' ? { enabled: String(params.enabled) } : {}),
    });
    return request<TestingPhaseDefinitionResponse[]>(
      `/api/testing-phases${query.toString() ? `?${query.toString()}` : ''}`,
    );
  },
  getTestingPhaseProjectOptions() {
    return request<TestingPhaseProjectOptionResponse[]>('/api/testing-phases/project-options');
  },
  getTestingPhaseGroups(params?: {
    projectId?: string | number | null;
    keyword?: string;
    enabled?: string | boolean | null;
  }) {
    const query = new URLSearchParams({
      ...(params?.projectId != null && params.projectId !== '' ? { projectId: String(params.projectId) } : {}),
      ...(params?.keyword ? { keyword: params.keyword } : {}),
      ...(params?.enabled != null && params.enabled !== '' ? { enabled: String(params.enabled) } : {}),
    });
    return request<TestingPhaseGroupResponse[]>(
      `/api/testing-phases/groups${query.toString() ? `?${query.toString()}` : ''}`,
    );
  },
  createTestingPhaseGroup(payload: TestingPhaseGroupSaveRequest) {
    return request<TestingPhaseGroupResponse>('/api/testing-phases/groups', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },
  updateTestingPhaseGroup(id: number, payload: TestingPhaseGroupSaveRequest) {
    return request<TestingPhaseGroupResponse>(`/api/testing-phases/groups/${id}`, {
      method: 'PUT',
      body: JSON.stringify(payload),
    });
  },
  setTestingPhaseGroupEnabled(id: number, enabled: boolean) {
    return request<TestingPhaseGroupResponse>(`/api/testing-phases/groups/${id}/enabled`, {
      method: 'PATCH',
      body: JSON.stringify({ enabled }),
    });
  },
  deleteTestingPhaseGroup(id: number) {
    return request<void>(`/api/testing-phases/groups/${id}`, {
      method: 'DELETE',
    });
  },
  createTestingPhase(payload: TestingPhaseDefinitionSaveRequest) {
    return request<TestingPhaseDefinitionResponse>('/api/testing-phases', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },
  updateTestingPhase(id: number, payload: TestingPhaseDefinitionSaveRequest) {
    return request<TestingPhaseDefinitionResponse>(`/api/testing-phases/${id}`, {
      method: 'PUT',
      body: JSON.stringify(payload),
    });
  },
  setTestingPhaseEnabled(id: number, enabled: boolean) {
    return request<TestingPhaseDefinitionResponse>(`/api/testing-phases/${id}/enabled`, {
      method: 'PATCH',
      body: JSON.stringify({ enabled }),
    });
  },
  deleteTestingPhase(id: number) {
    return request<void>(`/api/testing-phases/${id}`, {
      method: 'DELETE',
    });
  },
};
