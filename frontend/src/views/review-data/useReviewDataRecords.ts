import { computed, ref } from 'vue';
import type {
  ReviewDataFilterOptionsResponse,
  ReviewDataRecordListResponse,
  ReviewDataRecordRowResponse,
  StatisticFilterGroup,
} from '../../types/api';
import {
  buildReviewDataSummaryCards,
  buildReviewDataTableRows,
} from '../review-data-management';

export interface ReviewDataRecordQueryParams {
  keyword?: string;
  filterGroup?: StatisticFilterGroup | null;
  page?: number;
  size?: number;
  sortBy?: string;
  sortOrder?: 'asc' | 'desc';
}

export interface ReviewDataRecordsDependencies {
  fetchFilterOptions: () => Promise<ReviewDataFilterOptionsResponse>;
  fetchRecords: (params: ReviewDataRecordQueryParams) => Promise<ReviewDataRecordListResponse>;
}

export function createEmptyReviewDataFilterOptions(): ReviewDataFilterOptionsResponse {
  return {
    projectNames: [],
    moduleNames: [],
    reviewOwners: [],
    reviewTypes: [],
    reviewExperts: [],
    problemStatuses: [],
    reviewCategories: [],
    problemCategories: [],
  };
}

export function useReviewDataRecords(deps: ReviewDataRecordsDependencies) {
  const rows = ref<ReviewDataRecordRowResponse[]>([]);
  const total = ref(0);
  const summary = ref<ReviewDataRecordListResponse['summary'] | null>(null);
  const filterOptions = ref<ReviewDataFilterOptionsResponse>(createEmptyReviewDataFilterOptions());

  const summaryCards = computed(() => buildReviewDataSummaryCards(summary.value));
  const tableRows = computed(() => buildReviewDataTableRows(rows.value));

  async function loadFilterOptions() {
    filterOptions.value = await deps.fetchFilterOptions();
  }

  async function loadRows(params: ReviewDataRecordQueryParams) {
    const response = await deps.fetchRecords(params);
    rows.value = response.records;
    total.value = response.total;
    summary.value = response.summary;
  }

  async function refresh(params: ReviewDataRecordQueryParams) {
    await Promise.all([loadFilterOptions(), loadRows(params)]);
  }

  return {
    rows,
    total,
    summary,
    filterOptions,
    summaryCards,
    tableRows,
    loadFilterOptions,
    loadRows,
    refresh,
  };
}
