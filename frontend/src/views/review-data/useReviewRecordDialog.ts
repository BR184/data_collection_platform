import { computed, getCurrentScope, onScopeDispose, ref, watch } from 'vue';
import type {
  ReviewDataRecordDetailResponse,
  ReviewDataRecordSaveRequest,
} from '../../types/api';
import {
  createEmptyReviewRecordForm,
  createReviewRecordFormFromRow,
  type ReviewRecordFormModel,
} from '../review-data-management';
import type { RouteTableLoadOutcome } from '../../composables/useRouteTableState';
import { getErrorMessage } from '../../utils/user-message';

export interface ReviewRecordDialogDependencies {
  loadRecordDetail: (recordId: number) => Promise<ReviewDataRecordDetailResponse>;
  createRecord: (payload: ReviewDataRecordSaveRequest) => Promise<ReviewDataRecordDetailResponse>;
  updateRecord: (recordId: number, payload: ReviewDataRecordSaveRequest) => Promise<unknown>;
  refreshRecords: () => Promise<RouteTableLoadOutcome>;
  afterCreateRecord?: (record: ReviewDataRecordDetailResponse) => Promise<void>;
  afterUpdateRecord?: (recordId: number) => Promise<void>;
  notifySuccess: (message: string) => void;
  notifyError: (message: string) => void;
}

export interface ReviewRecordDialogSubmit {
  sessionId: number;
  payload: ReviewDataRecordSaveRequest;
}

interface RecordSessionTarget {
  operationId: number;
  mode: 'create' | 'edit';
  recordId: number | null;
}

type ReviewRecordSession =
  | (RecordSessionTarget & { phase: 'loading' })
  | (RecordSessionTarget & { phase: 'ready' })
  | (RecordSessionTarget & { phase: 'saving' });

/**
 * 管理评审记录弹窗的会话、详情加载和写入结算。
 * 写入成功后的列表与详情刷新按冻结的记录 ID 执行，即使用户已关闭弹窗；
 * 页面作用域销毁后不再调用页面 UI 回调。
 */
export function useReviewRecordDialog(deps: ReviewRecordDialogDependencies) {
  const recordDialogVisible = ref(false);
  const recordForm = ref<ReviewRecordFormModel>(createEmptyReviewRecordForm());
  const session = ref<ReviewRecordSession | null>(null);
  let operationSeq = 0;
  let scopeActive = true;

  const recordEditMode = computed(() => session.value?.mode === 'edit');
  const editingRecordId = computed(() =>
    session.value?.mode === 'edit' ? session.value.recordId : null,
  );
  const recordDialogSaving = computed(() => session.value?.phase === 'saving');
  const recordDialogReady = computed(() =>
    session.value?.phase === 'ready' || session.value?.phase === 'saving',
  );
  const recordDialogSessionId = computed(() => session.value?.operationId ?? null);

  watch(
    recordDialogVisible,
    (visible) => {
      if (!visible) {
        operationSeq += 1;
        session.value = null;
      }
    },
    { flush: 'sync' },
  );

  if (getCurrentScope()) {
    onScopeDispose(() => {
      scopeActive = false;
      operationSeq += 1;
      session.value = null;
    });
  }

  function isCurrent(operationId: number): boolean {
    return scopeActive
      && operationId === operationSeq
      && session.value?.operationId === operationId;
  }

  function isCurrentGeneration(generation: number): boolean {
    return scopeActive && operationSeq === generation;
  }

  /** 打开新增会话并提供全新的空白草稿。 */
  function openCreateRecord() {
    const operationId = ++operationSeq;
    session.value = { phase: 'ready', operationId, mode: 'create', recordId: null };
    recordForm.value = createEmptyReviewRecordForm();
    recordDialogVisible.value = true;
  }

  /** 先锁定编辑目标并展示加载态，再按会话身份接收详情。 */
  async function openEditRecord(recordId: number) {
    const operationId = ++operationSeq;
    session.value = { phase: 'loading', operationId, mode: 'edit', recordId };
    recordForm.value = createEmptyReviewRecordForm();
    recordDialogVisible.value = true;
    try {
      const detail = await deps.loadRecordDetail(recordId);
      if (!isCurrent(operationId)) {
        return;
      }
      recordForm.value = createReviewRecordFormFromRow(
        detail.record,
        detail.reviewExperts,
        detail.descriptions,
        detail.contents,
      );
      session.value = { phase: 'ready', operationId, mode: 'edit', recordId };
    } catch (error) {
      if (!isCurrent(operationId)) {
        return;
      }
      recordDialogVisible.value = false;
      deps.notifyError(getErrorMessage(error, '评审详情加载失败'));
    }
  }

  /** 只接受当前已就绪会话的表单结果；同一会话在首个 await 前进入 saving。 */
  async function submitRecord(input: ReviewRecordDialogSubmit) {
    const current = session.value;
    if (
      !current
      || current.phase !== 'ready'
      || !recordDialogVisible.value
      || current.operationId !== input.sessionId
      || !isCurrent(input.sessionId)
    ) {
      return;
    }

    const payload = snapshotRecordPayload(input.payload);
    const operationId = current.operationId;
    session.value = { ...current, phase: 'saving' };

    let createdRecord: ReviewDataRecordDetailResponse | null = null;
    try {
      if (current.mode === 'edit' && current.recordId != null) {
        await deps.updateRecord(current.recordId, payload);
      } else {
        createdRecord = await deps.createRecord(payload);
      }
    } catch (error) {
      if (isCurrent(operationId)) {
        session.value = { ...current, phase: 'ready' };
        deps.notifyError(getErrorMessage(error, '评审记录保存失败'));
      }
      return;
    }

    let followUpGeneration: number | null = null;
    if (isCurrent(operationId)) {
      deps.notifySuccess(current.mode === 'edit' ? '评审记录已更新' : '评审记录已创建');
      recordDialogVisible.value = false;
      followUpGeneration = operationSeq;
    }

    if (!scopeActive) {
      return;
    }

    const refreshOutcomePromise = deps.refreshRecords();
    const followUpOperations: Promise<void>[] = [];
    if (current.mode === 'edit' && current.recordId != null && deps.afterUpdateRecord) {
      followUpOperations.push(deps.afterUpdateRecord(current.recordId));
    }
    const followUpFailure = (await Promise.allSettled(followUpOperations))
      .find((result) => result.status === 'rejected');
    const refreshOutcome = await refreshOutcomePromise;
    // 只有真正失败才提示：未提交（被更新的查询取代）说明列表会随后由新查询加载，不算刷新失败。
    const refreshFailure = refreshOutcome.status === 'failed' ? { reason: refreshOutcome.error } : null;
    if (refreshFailure != null || followUpFailure?.status === 'rejected') {
      if (followUpGeneration != null && isCurrentGeneration(followUpGeneration)) {
        const reason = refreshFailure?.reason ?? (followUpFailure as PromiseRejectedResult).reason;
        deps.notifyError(`评审记录已保存，但列表或详情刷新失败：${getErrorMessage(reason, '请重新刷新数据')}`);
      }
      return;
    }

    if (
      current.mode === 'create'
      && createdRecord
      && deps.afterCreateRecord
      && followUpGeneration != null
      && isCurrentGeneration(followUpGeneration)
    ) {
      try {
        await deps.afterCreateRecord(createdRecord);
      } catch (error) {
        if (isCurrentGeneration(followUpGeneration)) {
          deps.notifyError(`评审记录已创建，但后续操作失败：${getErrorMessage(error, '请检查新建记录')}`);
        }
      }
    }
  }

  return {
    recordDialogVisible,
    recordDialogSaving,
    recordDialogReady,
    recordDialogSessionId,
    recordEditMode,
    editingRecordId,
    recordForm,
    openCreateRecord,
    openEditRecord,
    submitRecord,
  };
}

function snapshotRecordPayload(payload: ReviewDataRecordSaveRequest): ReviewDataRecordSaveRequest {
  return {
    ...payload,
    reviewExperts: [...payload.reviewExperts],
    descriptions: payload.descriptions?.map((description) => ({ ...description })),
  };
}
