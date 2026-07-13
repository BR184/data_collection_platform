import type {
  CodeReviewIllegalRecordFilterOptionsResponse,
  CodeReviewIllegalRecordListResponse,
  CodeReviewMatchModeStatusResponse,
  CodeReviewMultiBoardOverviewResponse,
  CodeReviewRulePreviewRequest,
  OptionItemResponse,
  RealtimeWorkspaceStatusResponse,
  StatisticBoardRuleExplanationResponse,
  StatisticFilterGroup,
} from '../types/api';
import type { CodeReviewRuleConfig, CodeReviewRulePreviewResponse } from '../types/code-review-rule-config';
import { EXPORT_REQUEST_TIMEOUT_MS, request, requestBlob } from './request';
import { stringifyStatisticFilterGroup } from '../utils/statistic-filter-group';

interface CodeReviewIllegalRecordQueryParams {
  projectId?: string | number | null;
  repositoryName?: string;
  mergedAtStart?: string;
  mergedAtEnd?: string;
  keyword?: string;
  projectName?: string;
  requestType?: string;
  targetBranch?: string;
  mergedBy?: string;
  moduleName?: string;
  illegalType?: string;
  mergeRequestIid?: string;
  owner?: string;
  source?: string;
  filterGroup?: StatisticFilterGroup | null;
  page?: number;
  size?: number;
  sortBy?: string;
  sortOrder?: 'asc' | 'desc';
  ruleConfig?: CodeReviewRuleConfig | null;
}

function buildIllegalRecordQuery(params: CodeReviewIllegalRecordQueryParams, includePagination = true) {
  return new URLSearchParams({
    ...(includePagination ? { page: String(params.page ?? 1), size: String(params.size ?? 20) } : {}),
    ...(params.projectId != null && params.projectId !== '' ? { projectId: String(params.projectId) } : {}),
    ...(params.repositoryName ? { repositoryName: params.repositoryName } : {}),
    ...(params.mergedAtStart ? { mergedAtStart: params.mergedAtStart } : {}),
    ...(params.mergedAtEnd ? { mergedAtEnd: params.mergedAtEnd } : {}),
    ...(params.keyword ? { keyword: params.keyword } : {}),
    ...(params.projectName ? { projectName: params.projectName } : {}),
    ...(params.requestType ? { requestType: params.requestType } : {}),
    ...(params.targetBranch ? { targetBranch: params.targetBranch } : {}),
    ...(params.mergedBy ? { mergedBy: params.mergedBy } : {}),
    ...(params.moduleName ? { moduleName: params.moduleName } : {}),
    ...(params.illegalType ? { illegalType: params.illegalType } : {}),
    ...(params.mergeRequestIid ? { mergeRequestIid: params.mergeRequestIid } : {}),
    ...(params.owner ? { owner: params.owner } : {}),
    ...(params.source ? { source: params.source } : {}),
    ...(params.filterGroup ? { filterGroup: stringifyStatisticFilterGroup(params.filterGroup) } : {}),
    ...(params.sortBy ? { sortBy: params.sortBy } : {}),
    ...(params.sortOrder ? { sortOrder: params.sortOrder } : {}),
    ...(params.ruleConfig ? { ruleConfig: JSON.stringify(params.ruleConfig) } : {}),
  });
}

export const codeReviewApi = {
  getCodeReviewMatchModeStatus() {
    return request<CodeReviewMatchModeStatusResponse>('/api/code-review/match-mode/status');
  },
  getCodeReviewIllegalRecords(params: CodeReviewIllegalRecordQueryParams) {
    const query = buildIllegalRecordQuery(params);
    return request<CodeReviewIllegalRecordListResponse>(`/api/code-review/illegal-records?${query.toString()}`);
  },
  async exportCodeReviewIllegalRecords(params: CodeReviewIllegalRecordQueryParams) {
    const query = buildIllegalRecordQuery(params, false);
    return requestBlob(`/api/code-review/illegal-records/export?${query.toString()}`, {
      errorPrefix: 'Excel 导出失败',
      timeoutMs: EXPORT_REQUEST_TIMEOUT_MS,
    });
  },
  getCodeReviewIllegalRecordFilterOptions(
    projectId?: string | number | null,
    source?: string | null,
    projectName?: string | null,
    repositoryName?: string | null,
  ) {
    const query = new URLSearchParams(
      {
        ...(projectId != null && projectId !== '' ? { projectId: String(projectId) } : {}),
        ...(repositoryName ? { repositoryName } : {}),
        ...(projectName ? { projectName } : {}),
        ...(source ? { source } : {}),
      },
    );
    return request<CodeReviewIllegalRecordFilterOptionsResponse>(
      `/api/code-review/illegal-records/filter-options${query.toString() ? `?${query.toString()}` : ''}`,
    );
  },
  getCodeReviewIllegalRecordRuleExplanation() {
    return request<StatisticBoardRuleExplanationResponse>('/api/code-review/illegal-records/rule-explanation');
  },
  previewCodeReviewIllegalRecordRuleConfig(payload: CodeReviewRulePreviewRequest) {
    return request<CodeReviewRulePreviewResponse>('/api/code-review/illegal-records/rule-config/preview', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },
  getCodeReviewIllegalRecordRealtimeStatus(source?: string | null) {
    const query = new URLSearchParams(source ? { source } : {});
    return request<RealtimeWorkspaceStatusResponse>(
      `/api/code-review/illegal-records/status${query.toString() ? `?${query.toString()}` : ''}`,
    );
  },
  refreshCodeReviewIllegalRecord(payload: {
    source?: string;
    projectId: number;
    mergeRequestIid: number;
  }) {
    return request<CodeReviewIllegalRecordListResponse['records'][number]>(
      '/api/code-review/illegal-records/refresh-one',
      {
        method: 'POST',
        body: JSON.stringify(payload),
        timeoutMs: 120_000,
      },
    );
  },
  refreshCodeReviewIllegalRecords() {
    return request<RealtimeWorkspaceStatusResponse>('/api/code-review/illegal-records/refresh', {
      method: 'POST',
    });
  },
  getCodeReviewMultiBoardSourceOptions() {
    return request<OptionItemResponse[]>('/api/code-review/multi-board/source-options');
  },
  getCodeReviewMultiBoardProjectOptions(source?: string) {
    const query = new URLSearchParams(source ? { source } : {});
    return request<OptionItemResponse[]>(
      `/api/code-review/multi-board/project-options${query.toString() ? `?${query.toString()}` : ''}`,
    );
  },
  getCodeReviewMultiBoardOverview(source?: string) {
    const query = new URLSearchParams(source ? { source } : {});
    return request<CodeReviewMultiBoardOverviewResponse>(
      `/api/code-review/multi-board/overview${query.toString() ? `?${query.toString()}` : ''}`,
    );
  },
  getCodeReviewMultiBoardRealtimeStatus() {
    return request<RealtimeWorkspaceStatusResponse>('/api/code-review/multi-board/status');
  },
  refreshCodeReviewMultiBoardRealtime() {
    return request<RealtimeWorkspaceStatusResponse>('/api/code-review/multi-board/refresh', {
      method: 'POST',
    });
  },
};
