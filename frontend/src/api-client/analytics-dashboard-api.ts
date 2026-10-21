import type {
  AnalyticsDashboardDetailResponse,
  AnalyticsDashboardQuery,
  AnalyticsDashboardResponse,
  AnalyticsDashboardRulesResponse,
} from '../types/api';
import { EXPORT_REQUEST_TIMEOUT_MS, request, requestBlobResponse } from './request';
import type { ReadViewTransport } from '../types/api/read-view';

function buildQuery(parameters?: AnalyticsDashboardQuery) {
  const query = new URLSearchParams();
  Object.entries(parameters ?? {}).forEach(([key, value]) => {
    if (value !== null && value !== undefined && String(value).trim()) {
      query.set(key, String(value));
    }
  });
  const queryString = query.toString();
  return queryString ? `?${queryString}` : '';
}

function segment(value: string) {
  return encodeURIComponent(value);
}

export const analyticsDashboardApi = {
  getDashboard(dashboardKey: string, parameters?: AnalyticsDashboardQuery, read?: ReadViewTransport) {
    return request<AnalyticsDashboardResponse>(
      `/api/analytics-dashboards/${segment(dashboardKey)}${buildQuery(parameters)}`,
      read,
    );
  },

  getRules(dashboardKey: string, parameters?: AnalyticsDashboardQuery, read?: ReadViewTransport) {
    return request<AnalyticsDashboardRulesResponse>(
      `/api/analytics-dashboards/${segment(dashboardKey)}/rules${buildQuery(parameters)}`,
      read,
    );
  },

  getDetails(
    dashboardKey: string,
    viewKey: string,
    parameters?: AnalyticsDashboardQuery,
    read?: ReadViewTransport,
  ) {
    return request<AnalyticsDashboardDetailResponse>(
      `/api/analytics-dashboards/${segment(dashboardKey)}/details/${segment(viewKey)}${buildQuery(parameters)}`,
      read,
    );
  },

  export(dashboardKey: string, exportKey: string, parameters?: AnalyticsDashboardQuery, read?: ReadViewTransport) {
    return requestBlobResponse(
      `/api/analytics-dashboards/${segment(dashboardKey)}/exports/${segment(exportKey)}${buildQuery(parameters)}`,
      {
        ...read,
        errorPrefix: '导出失败',
        timeoutMs: EXPORT_REQUEST_TIMEOUT_MS,
      },
    );
  },
};
