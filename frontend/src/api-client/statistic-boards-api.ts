import type {
  RealtimeWorkspaceStatusResponse,
  StatisticBoardResponse,
  StatisticBoardRuleExplanationResponse,
  StatisticDetailResponse,
  StatisticFilterGroup,
} from '../types/api';
import { EXPORT_REQUEST_TIMEOUT_MS, request, requestBlobResponse } from './request';
import { stringifyStatisticFilterGroup } from '../utils/statistic-filter-group';

export interface StatisticBoardQueryParams {
  filters?: Record<string, string>;
  filterGroup?: StatisticFilterGroup | null;
}

function buildStatisticBoardQuery(params?: StatisticBoardQueryParams) {
  const searchParams = new URLSearchParams(params?.filters ?? {});
  if (params?.filterGroup && params.filterGroup.conditions.length) {
    searchParams.set('filterGroup', stringifyStatisticFilterGroup(params.filterGroup));
  }
  const queryString = searchParams.toString();
  return queryString ? `?${queryString}` : '';
}

export const statisticBoardsApi = {
  getStatisticBoard(boardKey: string, params?: StatisticBoardQueryParams) {
    return request<StatisticBoardResponse>(
      `/api/statistic-boards/${boardKey}${buildStatisticBoardQuery(params)}`,
    );
  },
  getStatisticBoardDetails(
    boardKey: string,
    params: {
      rowKey: string;
      columnKey: string;
      page?: number;
      size?: number;
      sortField?: string;
      sortOrder?: string;
      filters?: Record<string, string>;
      filterGroup?: StatisticFilterGroup | null;
    },
  ) {
    const query = new URLSearchParams({
      rowKey: params.rowKey,
      columnKey: params.columnKey,
      page: String(params.page ?? 1),
      size: String(params.size ?? 10),
      ...(params.sortField ? { sortField: params.sortField } : {}),
      ...(params.sortOrder ? { sortOrder: params.sortOrder } : {}),
      ...(params.filters ?? {}),
    });
    if (params.filterGroup && params.filterGroup.conditions.length) {
      query.set('filterGroup', stringifyStatisticFilterGroup(params.filterGroup));
    }
    return request<StatisticDetailResponse>(`/api/statistic-boards/${boardKey}/details?${query.toString()}`);
  },
  getStatisticBoardRuleExplanation(boardKey: string, params?: StatisticBoardQueryParams) {
    return request<StatisticBoardRuleExplanationResponse>(
      `/api/statistic-boards/${boardKey}/rule-explanation${buildStatisticBoardQuery(params)}`,
    );
  },
  async exportStatisticBoard(boardKey: string, params?: StatisticBoardQueryParams) {
    return requestBlobResponse(`/api/statistic-boards/${boardKey}/export${buildStatisticBoardQuery(params)}`, {
      errorPrefix: '导出失败',
      timeoutMs: EXPORT_REQUEST_TIMEOUT_MS,
    });
  },
  async exportStatisticBoardFile(boardKey: string, params?: StatisticBoardQueryParams) {
    return requestBlobResponse(`/api/statistic-boards/${boardKey}/export${buildStatisticBoardQuery(params)}`, {
      errorPrefix: '导出失败',
      timeoutMs: EXPORT_REQUEST_TIMEOUT_MS,
    });
  },
  async exportSystemTestHorizontalComparison(params?: StatisticBoardQueryParams) {
    return requestBlobResponse(
      `/api/statistic-boards/system-test-defect-summary/horizontal-comparison/export${buildStatisticBoardQuery(params)}`,
      {
        errorPrefix: '横向对比导出失败',
        timeoutMs: EXPORT_REQUEST_TIMEOUT_MS,
      },
    );
  },
  async exportSystemTestDefectSummaryIssues(params?: StatisticBoardQueryParams) {
    return requestBlobResponse(
      `/api/statistic-boards/system-test-defect-summary/issues/export${buildStatisticBoardQuery(params)}`,
      {
        errorPrefix: '议题数据导出失败',
        timeoutMs: EXPORT_REQUEST_TIMEOUT_MS,
      },
    );
  },
  getStatisticBoardRealtimeStatus(boardKey: string) {
    return request<RealtimeWorkspaceStatusResponse>(`/api/statistic-boards/${boardKey}/status`);
  },
  refreshStatisticBoardRealtime(boardKey: string) {
    return request<RealtimeWorkspaceStatusResponse>(`/api/statistic-boards/${boardKey}/refresh`, {
      method: 'POST',
    });
  },
};
