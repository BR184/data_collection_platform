import { computed, getCurrentScope, onScopeDispose, ref, watch } from 'vue';
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
import { getErrorMessage } from '../../utils/user-message';

export interface ReviewProblemItemDialogDependencies {
  loadRecordDetail: (recordId: number) => Promise<ReviewDataRecordDetailResponse>;
  createProblemItem: (recordId: number, payload: ReviewDataProblemItemSaveRequest) => Promise<unknown>;
  updateProblemItem: (recordId: number, itemId: number, payload: ReviewDataProblemItemSaveRequest) => Promise<unknown>;
  refreshAfterMutation: (recordId: number) => Promise<void>;
  notifySuccess: (message: string) => void;
  notifyError: (message: string) => void;
}

export interface ReviewProblemItemDialogSubmit {
  sessionId: number;
  payload: ReviewDataProblemItemSaveRequest;
}

interface ProblemItemSessionTarget {
  operationId: number;
  mode: 'create' | 'edit';
  recordId: number;
  itemId: number | null;
}

type ReviewProblemItemSession =
  | (ProblemItemSessionTarget & { phase: 'loading' })
  | (ProblemItemSessionTarget & { phase: 'ready' })
  | (ProblemItemSessionTarget & { phase: 'saving' });

/**
 * 管理问题项弹窗的会话、所属评审详情加载和写入结算。
 * 写入成功后的列表、问题缓存与详情刷新按冻结的评审 ID 执行，即使用户已关闭弹窗；
 * 页面作用域销毁后不再调用页面 UI 回调。
 */
export function useReviewProblemItemDialog(deps: ReviewProblemItemDialogDependencies) {
  const problemDialogVisible = ref(false);
  const currentProblemExpertOptions = ref<string[]>([]);
  const problemForm = ref<ReviewProblemItemFormModel>(createEmptyProblemItemForm());
  const session = ref<ReviewProblemItemSession | null>(null);
  let operationSeq = 0;
  let scopeActive = true;

  const problemDialogEditMode = computed(() => session.value?.mode === 'edit');
  const currentProblemRecordId = computed(() => session.value?.recordId ?? null);
  const currentProblemItemId = computed(() => session.value?.itemId ?? null);
  const problemDialogSaving = computed(() => session.value?.phase === 'saving');
  const problemDialogReady = computed(() =>
    session.value?.phase === 'ready' || session.value?.phase === 'saving',
  );
  const problemDialogSessionId = computed(() => session.value?.operationId ?? null);

  watch(
    problemDialogVisible,
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

  /** 加载评审上下文后创建问题项草稿；详情未就绪时不允许提交。 */
  async function openCreateProblemItem(recordId: number) {
    if (!scopeActive) {
      return;
    }
    const operationId = ++operationSeq;
    session.value = { phase: 'loading', operationId, mode: 'create', recordId, itemId: null };
    problemForm.value = createEmptyProblemItemForm();
    currentProblemExpertOptions.value = [];
    problemDialogVisible.value = true;
    try {
      const detail = await deps.loadRecordDetail(recordId);
      if (!isCurrent(operationId)) {
        return;
      }
      currentProblemExpertOptions.value = [...detail.reviewExperts];
      problemForm.value = {
        ...createEmptyProblemItemForm(),
        ownerName: detail.record.reviewOwner || '',
      };
      session.value = { phase: 'ready', operationId, mode: 'create', recordId, itemId: null };
    } catch (error) {
      if (!isCurrent(operationId)) {
        return;
      }
      problemDialogVisible.value = false;
      deps.notifyError(getErrorMessage(error, '评审问题初始化失败'));
    }
  }

  /** 先锁定所属评审与问题项，再加载专家选项并补齐编辑草稿。 */
  async function openEditProblemItem(recordId: number, item: ReviewDataProblemItemResponse) {
    if (!scopeActive) {
      return;
    }
    const operationId = ++operationSeq;
    const targetItem = { ...item };
    session.value = { phase: 'loading', operationId, mode: 'edit', recordId, itemId: targetItem.id };
    problemForm.value = createEmptyProblemItemForm();
    currentProblemExpertOptions.value = [];
    problemDialogVisible.value = true;
    try {
      const detail = await deps.loadRecordDetail(recordId);
      if (!isCurrent(operationId)) {
        return;
      }
      currentProblemExpertOptions.value = [...detail.reviewExperts];
      problemForm.value = createProblemItemFormFromRow(targetItem);
      session.value = { phase: 'ready', operationId, mode: 'edit', recordId, itemId: targetItem.id };
    } catch (error) {
      if (!isCurrent(operationId)) {
        return;
      }
      problemDialogVisible.value = false;
      deps.notifyError(getErrorMessage(error, '评审问题详情加载失败'));
    }
  }

  /** 只接受当前已就绪会话的表单结果；重复提交在首个 await 前被拒绝。 */
  async function submitProblemItem(input: ReviewProblemItemDialogSubmit) {
    const current = session.value;
    if (
      !current
      || current.phase !== 'ready'
      || !problemDialogVisible.value
      || current.operationId !== input.sessionId
      || !isCurrent(input.sessionId)
    ) {
      return;
    }

    const payload = { ...input.payload };
    const operationId = current.operationId;
    session.value = { ...current, phase: 'saving' };

    try {
      if (current.mode === 'edit' && current.itemId != null) {
        await deps.updateProblemItem(current.recordId, current.itemId, payload);
      } else {
        await deps.createProblemItem(current.recordId, payload);
      }
    } catch (error) {
      if (isCurrent(operationId)) {
        session.value = { ...current, phase: 'ready' };
        deps.notifyError(getErrorMessage(error, '评审问题保存失败'));
      }
      return;
    }

    let followUpGeneration: number | null = null;
    if (isCurrent(operationId)) {
      deps.notifySuccess(current.mode === 'edit' ? '评审问题已更新' : '评审问题已新增');
      problemDialogVisible.value = false;
      followUpGeneration = operationSeq;
    }

    if (!scopeActive) {
      return;
    }

    try {
      await deps.refreshAfterMutation(current.recordId);
    } catch (error) {
      if (followUpGeneration != null && isCurrentGeneration(followUpGeneration)) {
        deps.notifyError(`评审问题已保存，但列表或详情刷新失败：${getErrorMessage(error, '请重新刷新数据')}`);
      }
      return;
    }
  }

  return {
    problemDialogVisible,
    problemDialogSaving,
    problemDialogReady,
    problemDialogSessionId,
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
