import { ref } from 'vue';
import type { ReviewDataRecordDetailResponse } from '../../types/api';
import { getErrorMessage } from '../../utils/user-message';

export interface ReviewDataDetailDependencies {
  loadRecordDetail: (recordId: number, commit: (detail: ReviewDataRecordDetailResponse) => void) => Promise<void>;
  notifyError: (message: string) => void;
}

export function useReviewDataDetail(deps: ReviewDataDetailDependencies) {
  const detailVisible = ref(false);
  const detailData = ref<ReviewDataRecordDetailResponse | null>(null);
  let requestSequence = 0;

  async function openDetail(recordId: number) {
    const ticket = ++requestSequence;
    try {
      await deps.loadRecordDetail(recordId, detail => {
        if (ticket !== requestSequence) return;
        detailData.value = detail; detailVisible.value = true;
      });
    } catch (error) {
      if (ticket === requestSequence) deps.notifyError(getErrorMessage(error, '评审详情加载失败'));
    }
  }

  async function refreshDetailIfOpen(recordId: number) {
    if (detailVisible.value && detailData.value?.record.id === recordId) {
      const ticket = ++requestSequence;
      await deps.loadRecordDetail(recordId, detail => {
        if (ticket === requestSequence && detailVisible.value && detailData.value?.record.id === recordId) detailData.value = detail;
      });
    }
  }

  return {
    detailVisible,
    detailData,
    openDetail,
    refreshDetailIfOpen,
  };
}
