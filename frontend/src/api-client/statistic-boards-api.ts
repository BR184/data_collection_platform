import type {
  RealtimeWorkspaceStatusResponse,
  StatisticBoardResponse,
  StatisticBoardRuleExplanationResponse,
  StatisticDetailResponse,
  StatisticFilterGroup,
} from '../api';
import { request } from './request';

export interface StatisticBoardQueryParams {
  filters?: Record<string, string>;
  filterGroup?: StatisticFilterGroup | null;
}

function buildStatisticBoardQuery(params?: StatisticBoardQueryParams) {
  const searchParams = new URLSearchParams(params?.filters ?? {});
  if (params?.filterGroup && params.filterGroup.conditions.length) {
    searchParams.set('filterGroup', JSON.stringify(params.filterGroup));
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
      query.set('filterGroup', JSON.stringify(params.filterGroup));
    }
    return request<StatisticDetailResponse>(`/api/statistic-boards/${boardKey}/details?${query.toString()}`);
  },
  getStatisticBoardRuleExplanation(boardKey: string, params?: StatisticBoardQueryParams) {
    return request<StatisticBoardRuleExplanationResponse>(
      `/api/statistic-boards/${boardKey}/rule-explanation${buildStatisticBoardQuery(params)}`,
    );
  },
  async exportStatisticBoard(boardKey: string, params?: StatisticBoardQueryParams) {
    const response = await fetch(`/api/statistic-boards/${boardKey}/export${buildStatisticBoardQuery(params)}`);
    if (!response.ok) {
      const text = await response.text();
      throw new Error(text || `Export failed: ${response.status}`);
    }
    return response.text();
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
