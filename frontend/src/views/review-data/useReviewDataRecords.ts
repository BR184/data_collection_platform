import { computed, ref } from 'vue';
import type {
  ReviewDataFilterOptionsResponse,
  ReviewDataRecordListResponse,
  ReviewDataRecordRowResponse,
} from '../../types/api';
import {
  buildReviewDataSummaryCards,
  buildReviewDataTableRows,
} from '../review-data-management';

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

export function useReviewDataRecords() {
  const rows = ref<ReviewDataRecordRowResponse[]>([]);
  const total = ref(0);
  const summary = ref<ReviewDataRecordListResponse['summary'] | null>(null);
  const filterOptions = ref<ReviewDataFilterOptionsResponse>(createEmptyReviewDataFilterOptions());

  const summaryCards = computed(() => buildReviewDataSummaryCards(summary.value));
  const tableRows = computed(() => buildReviewDataTableRows(rows.value));

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
    commitFilterOptions,
    commitRows,
    clearRows,
  };
}
