import { ref } from 'vue';
import type {
  ReviewDataProblemItemResponse,
  ReviewDataProblemItemSaveRequest,
  ReviewDataRecordDetailResponse,
} from '../../types/api';
import {
  createEmptyProblemItemForm,
  createProblemItemFormFromRow,
  type ReviewProblemItemFormModel,
} from '../review-data-management';

export interface ReviewProblemItemDialogDependencies {
  loadRecordDetail: (recordId: number) => Promise<ReviewDataRecordDetailResponse>;
  createProblemItem: (recordId: number, payload: ReviewDataProblemItemSaveRequest) => Promise<unknown>;
  updateProblemItem: (recordId: number, itemId: number, payload: ReviewDataProblemItemSaveRequest) => Promise<unknown>;
  refreshAfterMutation: (recordId: number) => Promise<void>;
  notifySuccess: (message: string) => void;
  notifyError: (message: string) => void;
}

export function useReviewProblemItemDialog(deps: ReviewProblemItemDialogDependencies) {
  const problemDialogVisible = ref(false);
  const problemDialogSaving = ref(false);
  const problemDialogEditMode = ref(false);
  const currentProblemRecordId = ref<number | null>(null);
  const currentProblemItemId = ref<number | null>(null);
  const currentProblemExpertOptions = ref<string[]>([]);
  const problemForm = ref<ReviewProblemItemFormModel>(createEmptyProblemItemForm());

  async function openCreateProblemItem(recordId: number) {
    try {
      const detail = await deps.loadRecordDetail(recordId);
      currentProblemRecordId.value = recordId;
      currentProblemItemId.value = null;
      currentProblemExpertOptions.value = detail.reviewExperts;
      problemDialogEditMode.value = false;
      problemForm.value = createEmptyProblemItemForm();
      problemDialogVisible.value = true;
    } catch (error) {
      deps.notifyError(error instanceof Error ? error.message : '评审问题初始化失败');
    }
  }

  async function openEditProblemItem(recordId: number, item: ReviewDataProblemItemResponse) {
    try {
      const detail = await deps.loadRecordDetail(recordId);
      currentProblemRecordId.value = recordId;
      currentProblemItemId.value = item.id;
      currentProblemExpertOptions.value = detail.reviewExperts;
      problemDialogEditMode.value = true;
      problemForm.value = createProblemItemFormFromRow(item);
      problemDialogVisible.value = true;
    } catch (error) {
      deps.notifyError(error instanceof Error ? error.message : '评审问题详情加载失败');
    }
  }

  async function submitProblemItem(payload: ReviewDataProblemItemSaveRequest) {
    if (currentProblemRecordId.value == null) {
      return;
    }
    const recordId = currentProblemRecordId.value;
    problemDialogSaving.value = true;
    try {
      if (problemDialogEditMode.value && currentProblemItemId.value != null) {
        await deps.updateProblemItem(recordId, currentProblemItemId.value, payload);
        deps.notifySuccess('评审问题已更新');
      } else {
        await deps.createProblemItem(recordId, payload);
        deps.notifySuccess('评审问题已新增');
      }
      problemDialogVisible.value = false;
      await deps.refreshAfterMutation(recordId);
    } catch (error) {
      deps.notifyError(error instanceof Error ? error.message : '评审问题保存失败');
    } finally {
      problemDialogSaving.value = false;
    }
  }

  return {
    problemDialogVisible,
    problemDialogSaving,
    problemDialogEditMode,
    currentProblemRecordId,
    currentProblemItemId,
    currentProblemExpertOptions,
    problemForm,
    openCreateProblemItem,
    openEditProblemItem,
    submitProblemItem,
  };
}
