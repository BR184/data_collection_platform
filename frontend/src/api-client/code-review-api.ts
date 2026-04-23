import type {
  CodeReviewIllegalRecordFilterOptionsResponse,
  CodeReviewIllegalRecordListResponse,
  CodeReviewRulePreviewRequest,
  RealtimeWorkspaceStatusResponse,
  StatisticBoardRuleExplanationResponse,
  StatisticFilterGroup,
} from '../types/api';
import type { CodeReviewRuleConfig, CodeReviewRulePreviewResponse } from '../types/code-review-rule-config';
import { request } from './request';

export const codeReviewApi = {
  getCodeReviewIllegalRecords(params: {
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
    filterGroup?: StatisticFilterGroup | null;
    page?: number;
    size?: number;
    sortBy?: string;
    sortOrder?: 'asc' | 'desc';
    ruleConfig?: CodeReviewRuleConfig | null;
  }) {
    const query = new URLSearchParams({
      page: String(params.page ?? 1),
      size: String(params.size ?? 20),
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
      ...(params.filterGroup ? { filterGroup: JSON.stringify(params.filterGroup) } : {}),
      ...(params.sortBy ? { sortBy: params.sortBy } : {}),
      ...(params.sortOrder ? { sortOrder: params.sortOrder } : {}),
      ...(params.ruleConfig ? { ruleConfig: JSON.stringify(params.ruleConfig) } : {}),
    });
    return request<CodeReviewIllegalRecordListResponse>(`/api/code-review/illegal-records?${query.toString()}`);
  },
  getCodeReviewIllegalRecordFilterOptions(projectId?: string | number | null) {
    const query = new URLSearchParams(
      projectId != null && projectId !== '' ? { projectId: String(projectId) } : {},
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
  getCodeReviewIllegalRecordRealtimeStatus() {
    return request<RealtimeWorkspaceStatusResponse>('/api/code-review/illegal-records/status');
  },
  refreshCodeReviewIllegalRecords() {
    return request<RealtimeWorkspaceStatusResponse>('/api/code-review/illegal-records/refresh', {
      method: 'POST',
    });
  },
};
