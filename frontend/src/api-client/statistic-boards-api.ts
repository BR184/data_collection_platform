import type {
  RealtimeWorkspaceStatusResponse,
  StatisticBoardControlOptions,
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
  /**
   * 成员控制条候选：由后端在同一窄事实的完整基础范围上求得，不随当前成员选择收缩。
   *
   * @param boardKey 统计板标识
   * @param params 范围参数（生效范围、来源与用户条件）；成员选择参与主表读取但不参与候选求解
   */
  getStatisticBoardControlOptions(boardKey: string, params?: StatisticBoardQueryParams) {
    return request<StatisticBoardControlOptions>(
      `/api/statistic-boards/${boardKey}/control-options${buildStatisticBoardQuery(params)}`,
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
  async exportCustomerIssueDefectSummaryIssues(params?: StatisticBoardQueryParams) {
    return requestBlobResponse(
      `/api/statistic-boards/customer-issue-defect-summary/issues/export${buildStatisticBoardQuery(params)}`,
      {
        errorPrefix: '客户问题议题数据导出失败',
        timeoutMs: EXPORT_REQUEST_TIMEOUT_MS,
      },
    );
  },
  getStatisticBoardRealtimeStatus(boardKey: string, params?: StatisticBoardQueryParams) {
    return request<RealtimeWorkspaceStatusResponse>(
      `/api/statistic-boards/${boardKey}/status${buildStatisticBoardQuery(params)}`,
    );
  },
  refreshStatisticBoardRealtime(boardKey: string) {
    return request<RealtimeWorkspaceStatusResponse>(`/api/statistic-boards/${boardKey}/refresh`, {
      method: 'POST',
    });
  },
};
