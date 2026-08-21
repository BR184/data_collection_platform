import { ref } from 'vue';
import type { ReviewDataRecordDetailResponse } from '../../types/api';
import { getErrorMessage } from '../../utils/user-message';

export interface ReviewDataDetailDependencies {
  loadRecordDetail: (recordId: number) => Promise<ReviewDataRecordDetailResponse>;
  notifyError: (message: string) => void;
}

export function useReviewDataDetail(deps: ReviewDataDetailDependencies) {
  const detailVisible = ref(false);
  const detailData = ref<ReviewDataRecordDetailResponse | null>(null);

  async function openDetail(recordId: number) {
    try {
      detailData.value = await deps.loadRecordDetail(recordId);
      detailVisible.value = true;
    } catch (error) {
      deps.notifyError(getErrorMessage(error, '评审详情加载失败'));
    }
  }

  async function refreshDetailIfOpen(recordId: number) {
    if (detailVisible.value && detailData.value?.record.id === recordId) {
      detailData.value = await deps.loadRecordDetail(recordId);
    }
  }

  return {
    detailVisible,
    detailData,
    openDetail,
    refreshDetailIfOpen,
  };
}
