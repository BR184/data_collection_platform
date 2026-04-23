import type {
  FactBuildResponse,
  IntegrationTestDetailResponse,
  IntegrationTestPhaseOptionResponse,
  IntegrationTestProjectOptionResponse,
  IntegrationTestSummaryResponse,
} from '../types/api';
import { request } from './request';

export const integrationTestsApi = {
  rebuildIntegrationTestFacts(full = false) {
    const query = new URLSearchParams({ full: String(full) });
    return request<FactBuildResponse>(`/api/integration-tests/rebuild?${query.toString()}`, {
      method: 'POST',
    });
  },
  getIntegrationTestProjectOptions() {
    return request<IntegrationTestProjectOptionResponse[]>('/api/integration-tests/project-options');
  },
  getIntegrationTestPhaseOptions(projectId?: string | number | null) {
    const query = new URLSearchParams(
      projectId != null && projectId !== '' ? { projectId: String(projectId) } : {},
    );
    return request<IntegrationTestPhaseOptionResponse[]>(
      `/api/integration-tests/phase-options${query.toString() ? `?${query.toString()}` : ''}`,
    );
  },
  getIntegrationTestSummary(params?: {
    projectId?: string | number | null;
    testingPhase?: string | null;
  }) {
    const query = new URLSearchParams({
      ...(params?.projectId != null && params.projectId !== '' ? { projectId: String(params.projectId) } : {}),
      ...(params?.testingPhase ? { testingPhase: params.testingPhase } : {}),
    });
    return request<IntegrationTestSummaryResponse>(
      `/api/integration-tests/summary${query.toString() ? `?${query.toString()}` : ''}`,
    );
  },
  getIntegrationTestDetails(params: {
    projectId?: string | number | null;
    testingPhase?: string | null;
    moduleName?: string | null;
    page?: number;
    size?: number;
    sortBy?: string;
    sortOrder?: 'asc' | 'desc';
  }) {
    const query = new URLSearchParams({
      page: String(params.page ?? 1),
      size: String(params.size ?? 20),
      ...(params.projectId != null && params.projectId !== '' ? { projectId: String(params.projectId) } : {}),
      ...(params.testingPhase ? { testingPhase: params.testingPhase } : {}),
      ...(params.moduleName ? { moduleName: params.moduleName } : {}),
      ...(params.sortBy ? { sortField: params.sortBy } : {}),
      ...(params.sortOrder ? { sortOrder: params.sortOrder } : {}),
    });
    return request<IntegrationTestDetailResponse>(`/api/integration-tests/details?${query.toString()}`);
  },
};
