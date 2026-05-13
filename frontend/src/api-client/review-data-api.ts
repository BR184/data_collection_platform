import type {
  ReviewDataFilterOptionsResponse,
  ReviewDataGitlabContextRefreshRequest,
  ReviewDataGitlabContextRefreshResponse,
  ReviewDataProblemItemResponse,
  ReviewDataProblemItemSaveRequest,
  ReviewDataRecordDetailResponse,
  ReviewDataRecordListResponse,
  ReviewDataRecordSaveRequest,
  StatisticFilterGroup,
} from '../types/api';
import { request } from './request';

export const reviewDataApi = {
  getReviewDataRecords(params: {
    keyword?: string;
    title?: string;
    projectName?: string;
    moduleName?: string;
    reviewOwner?: string;
    reviewType?: string;
    problemStatus?: string;
    reviewExpert?: string;
    filterGroup?: StatisticFilterGroup | null;
    page?: number;
    size?: number;
    sortBy?: string;
    sortOrder?: 'asc' | 'desc';
  }) {
    const query = new URLSearchParams({
      page: String(params.page ?? 1),
      size: String(params.size ?? 20),
      ...(params.keyword ? { keyword: params.keyword } : {}),
      ...(params.title ? { title: params.title } : {}),
      ...(params.projectName ? { projectName: params.projectName } : {}),
      ...(params.moduleName ? { moduleName: params.moduleName } : {}),
      ...(params.reviewOwner ? { reviewOwner: params.reviewOwner } : {}),
      ...(params.reviewType ? { reviewType: params.reviewType } : {}),
      ...(params.problemStatus ? { problemStatus: params.problemStatus } : {}),
      ...(params.reviewExpert ? { reviewExpert: params.reviewExpert } : {}),
      ...(params.filterGroup ? { filterGroup: JSON.stringify(params.filterGroup) } : {}),
      ...(params.sortBy ? { sortBy: params.sortBy } : {}),
      ...(params.sortOrder ? { sortOrder: params.sortOrder } : {}),
    });
    return request<ReviewDataRecordListResponse>(`/api/review-data/records?${query.toString()}`);
  },
  getReviewDataFilterOptions() {
    return request<ReviewDataFilterOptionsResponse>('/api/review-data/records/filter-options');
  },
  getReviewDataRecordDetail(recordId: string | number) {
    return request<ReviewDataRecordDetailResponse>(`/api/review-data/records/${recordId}`);
  },
  createReviewDataRecord(payload: ReviewDataRecordSaveRequest) {
    return request<ReviewDataRecordDetailResponse>('/api/review-data/records', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },
  updateReviewDataRecord(recordId: string | number, payload: ReviewDataRecordSaveRequest) {
    return request<ReviewDataRecordDetailResponse>(`/api/review-data/records/${recordId}`, {
      method: 'PUT',
      body: JSON.stringify(payload),
    });
  },
  deleteReviewDataRecord(recordId: string | number) {
    return request<void>(`/api/review-data/records/${recordId}`, {
      method: 'DELETE',
    });
  },
  refreshReviewDataGitlabContext(payload: ReviewDataGitlabContextRefreshRequest) {
    return request<ReviewDataGitlabContextRefreshResponse>('/api/review-data/records/gitlab-context/refresh', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },
  getReviewDataGitlabContextRefreshStatus(jobId: string | number) {
    return request<ReviewDataGitlabContextRefreshResponse>(
      `/api/review-data/records/gitlab-context/refresh/${jobId}`,
    );
  },
  getReviewDataProblemItems(recordId: string | number) {
    return request<ReviewDataProblemItemResponse[]>(`/api/review-data/records/${recordId}/problem-items`);
  },
  createReviewDataProblemItem(recordId: string | number, payload: ReviewDataProblemItemSaveRequest) {
    return request<ReviewDataProblemItemResponse>(`/api/review-data/records/${recordId}/problem-items`, {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },
  updateReviewDataProblemItem(
    recordId: string | number,
    itemId: string | number,
    payload: ReviewDataProblemItemSaveRequest,
  ) {
    return request<ReviewDataProblemItemResponse>(`/api/review-data/records/${recordId}/problem-items/${itemId}`, {
      method: 'PUT',
      body: JSON.stringify(payload),
    });
  },
  deleteReviewDataProblemItem(recordId: string | number, itemId: string | number) {
    return request<void>(`/api/review-data/records/${recordId}/problem-items/${itemId}`, {
      method: 'DELETE',
    });
  },
};
