import type {
  AnalyticsDashboardDetailResponse,
  AnalyticsDashboardQuery,
  AnalyticsDashboardResponse,
  AnalyticsDashboardRulesResponse,
} from '../types/api';
import { EXPORT_REQUEST_TIMEOUT_MS, request, requestBlobResponse } from './request';

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
  getDashboard(dashboardKey: string, parameters?: AnalyticsDashboardQuery) {
    return request<AnalyticsDashboardResponse>(
      `/api/analytics-dashboards/${segment(dashboardKey)}${buildQuery(parameters)}`,
    );
  },

  getRules(dashboardKey: string, parameters?: AnalyticsDashboardQuery) {
    return request<AnalyticsDashboardRulesResponse>(
      `/api/analytics-dashboards/${segment(dashboardKey)}/rules${buildQuery(parameters)}`,
    );
  },

  getDetails(
    dashboardKey: string,
    viewKey: string,
    parameters?: AnalyticsDashboardQuery,
  ) {
    return request<AnalyticsDashboardDetailResponse>(
      `/api/analytics-dashboards/${segment(dashboardKey)}/details/${segment(viewKey)}${buildQuery(parameters)}`,
    );
  },

  export(dashboardKey: string, exportKey: string, parameters?: AnalyticsDashboardQuery) {
    return requestBlobResponse(
      `/api/analytics-dashboards/${segment(dashboardKey)}/exports/${segment(exportKey)}${buildQuery(parameters)}`,
      {
        errorPrefix: '导出失败',
        timeoutMs: EXPORT_REQUEST_TIMEOUT_MS,
      },
    );
  },
};
