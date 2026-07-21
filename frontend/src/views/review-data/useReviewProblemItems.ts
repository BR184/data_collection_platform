import { ref } from 'vue';
import type { ReviewDataProblemItemResponse, ReviewDataRecordRowResponse } from '../../types/api';
import { buildProblemItemTableRows } from '../review-data-management';

export function useReviewProblemItems(
  loadProblemItemsApi: (recordId: number) => Promise<ReviewDataProblemItemResponse[]>,
) {
  const problemDialogVisible = ref(false);
  const activeProblemRecord = ref<ReviewDataRecordRowResponse | null>(null);
  const problemItemsMap = ref<Record<number, ReviewDataProblemItemResponse[]>>({});
  const problemLoadingMap = ref<Record<number, boolean>>({});

  async function loadProblemItems(recordId: number) {
    problemLoadingMap.value[recordId] = true;
    try {
      problemItemsMap.value[recordId] = await loadProblemItemsApi(recordId);
    } finally {
      problemLoadingMap.value[recordId] = false;
    }
  }

  async function openProblemList(record: ReviewDataRecordRowResponse) {
    activeProblemRecord.value = record;
    problemDialogVisible.value = true;
    const recordId = record.id;
    if (!problemItemsMap.value[recordId]) {
      await loadProblemItems(recordId);
    }
  }

  function closeProblemList() {
    problemDialogVisible.value = false;
    activeProblemRecord.value = null;
  }

  function problemItemsFor(recordId: number) {
    return buildProblemItemTableRows(problemItemsMap.value[recordId] ?? []);
  }

  return {
    problemDialogVisible,
    activeProblemRecord,
    problemItemsMap,
    problemLoadingMap,
    loadProblemItems,
    openProblemList,
    closeProblemList,
    problemItemsFor,
  };
}
