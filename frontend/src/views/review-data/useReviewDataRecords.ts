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
  title?: string;
  projectName?: string;
  moduleName?: string;
  reviewOwner?: string;
  reviewType?: string;
  problemStatus?: string;
  reviewExpert?: string;
  filterGroup?: StatisticFilterGroup | null;
  sourceInstance?: string | null;
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
    reviewVersions: [],
    problemStatuses: [],
    reviewCategories: [],
    problemCategories: [],
    formProjectNames: [],
    formModuleNames: [],
  };
}

export function useReviewDataRecords(deps: ReviewDataRecordsDependencies) {
  const rows = ref<ReviewDataRecordRowResponse[]>([]);
  const total = ref(0);
  const summary = ref<ReviewDataRecordListResponse['summary'] | null>(null);
  const filterOptions = ref<ReviewDataFilterOptionsResponse>(createEmptyReviewDataFilterOptions());

  const summaryCards = computed(() => buildReviewDataSummaryCards(summary.value));
  const tableRows = computed(() => buildReviewDataTableRows(rows.value));

  async function fetchFilterOptions() {
    return deps.fetchFilterOptions();
  }

  async function fetchRows(params: ReviewDataRecordQueryParams) {
    return deps.fetchRecords(params);
  }

  function commitFilterOptions(options: ReviewDataFilterOptionsResponse) {
    filterOptions.value = options;
  }

  function commitRows(response: ReviewDataRecordListResponse) {
    rows.value = response.records;
    total.value = response.total;
    summary.value = response.summary;
  }

  function clearRows() {
    rows.value = [];
    total.value = 0;
    summary.value = null;
  }

  return {
    rows,
    total,
    summary,
    filterOptions,
    summaryCards,
    tableRows,
    fetchFilterOptions,
    fetchRows,
    commitFilterOptions,
    commitRows,
    clearRows,
  };
}
