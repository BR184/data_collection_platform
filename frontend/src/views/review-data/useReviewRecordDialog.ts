import { ref } from 'vue';
import type {
  ReviewDataRecordDetailResponse,
  ReviewDataRecordSaveRequest,
} from '../../types/api';
import {
  createEmptyReviewRecordForm,
  createReviewRecordFormFromRow,
  type ReviewRecordFormModel,
} from '../review-data-management';
import { getErrorMessage } from '../../utils/user-message';

export interface ReviewRecordDialogDependencies {
  loadRecordDetail: (recordId: number) => Promise<ReviewDataRecordDetailResponse>;
  createRecord: (payload: ReviewDataRecordSaveRequest) => Promise<ReviewDataRecordDetailResponse>;
  updateRecord: (recordId: number, payload: ReviewDataRecordSaveRequest) => Promise<unknown>;
  refreshRecords: () => Promise<void>;
  afterCreateRecord?: (record: ReviewDataRecordDetailResponse) => Promise<void>;
  afterUpdateRecord?: (recordId: number) => Promise<void>;
  notifySuccess: (message: string) => void;
  notifyError: (message: string) => void;
}

export function useReviewRecordDialog(deps: ReviewRecordDialogDependencies) {
  const recordDialogVisible = ref(false);
  const recordDialogSaving = ref(false);
  const recordEditMode = ref(false);
  const editingRecordId = ref<number | null>(null);
  const recordForm = ref<ReviewRecordFormModel>(createEmptyReviewRecordForm());

  function openCreateRecord() {
    editingRecordId.value = null;
    recordEditMode.value = false;
    recordForm.value = createEmptyReviewRecordForm();
    recordDialogVisible.value = true;
  }

  async function openEditRecord(recordId: number) {
    try {
      const detail = await deps.loadRecordDetail(recordId);
      editingRecordId.value = recordId;
      recordEditMode.value = true;
      recordForm.value = createReviewRecordFormFromRow(
        detail.record,
        detail.reviewExperts,
        detail.descriptions,
        detail.contents,
      );
      recordDialogVisible.value = true;
    } catch (error) {
      deps.notifyError(getErrorMessage(error, '评审详情加载失败'));
    }
  }

  async function submitRecord(payload: ReviewDataRecordSaveRequest) {
    recordDialogSaving.value = true;
    try {
      if (recordEditMode.value && editingRecordId.value != null) {
        const recordId = editingRecordId.value;
        await deps.updateRecord(recordId, payload);
        deps.notifySuccess('评审记录已更新');
        recordDialogVisible.value = false;
        await deps.refreshRecords();
        await deps.afterUpdateRecord?.(recordId);
        return;
      } else {
        const createdRecord = await deps.createRecord(payload);
        deps.notifySuccess('评审记录已创建');
        await deps.refreshRecords();
        recordDialogVisible.value = false;
        await deps.afterCreateRecord?.(createdRecord);
        return;
      }
    } catch (error) {
      deps.notifyError(getErrorMessage(error, '评审记录保存失败'));
    } finally {
      recordDialogSaving.value = false;
    }
  }

  return {
    recordDialogVisible,
    recordDialogSaving,
    recordEditMode,
    editingRecordId,
    recordForm,
    openCreateRecord,
    openEditRecord,
    submitRecord,
  };
}
