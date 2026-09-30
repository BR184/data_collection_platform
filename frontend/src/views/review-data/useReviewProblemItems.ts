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

  /**
   * 指定评审的问题清单当前是否仍是用户正在查看的那个。
   *
   * 供页面级后续流程在每个 await 之后确认资格：清单被关闭、切换到别的评审，都会让结果变为 false，
   * 迟到的后续动作（例如新增完成后自动打开问题编辑器）必须据此放弃，而不是继续操作已关闭的界面。
   */
  function isProblemListOpenFor(recordId: number) {
    return problemDialogVisible.value && activeProblemRecord.value?.id === recordId;
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
    isProblemListOpenFor,
  };
}
