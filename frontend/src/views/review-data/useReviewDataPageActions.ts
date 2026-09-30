import { ref } from 'vue';
import type {
  ReviewDataProblemItemResponse,
  ReviewDataRecordRowResponse,
} from '../../types/api';
import type { RouteTableLoadOutcome } from '../../composables/useRouteTableState';
import { getErrorMessage } from '../../utils/user-message';

type TableRowWithRawRecord = Record<string, unknown> & {
  __raw?: ReviewDataRecordRowResponse;
};

export interface ReviewDataPageActionsDependencies {
  refreshRecords: () => Promise<RouteTableLoadOutcome>;
  openProblemList: (record: ReviewDataRecordRowResponse) => Promise<void>;
  openDetail: (recordId: number) => Promise<void>;
  openCreateRecord: () => void;
  openEditRecord: (recordId: number) => Promise<void>;
  openCreateProblemItem: (recordId: number) => Promise<void>;
  openEditProblemItem: (recordId: number, item: ReviewDataProblemItemResponse) => Promise<void>;
  deleteRecord: (recordId: number) => Promise<void>;
  deleteProblemItem: (recordId: number, itemId: number) => Promise<void>;
  refreshAfterProblemItemMutation: (recordId: number) => Promise<void>;
  confirm: (message: string, title: string) => Promise<unknown>;
  notifySuccess: (message: string) => void;
  notifyError: (message: string) => void;
}

export function useReviewDataPageActions(deps: ReviewDataPageActionsDependencies) {
  const ruleExplanationVisible = ref(false);

  function recordFromTableRow(row: TableRowWithRawRecord) {
    const raw = row.__raw;
    return typeof raw?.id === 'number' ? raw : null;
  }

  async function handleRefresh() {
    // 失败与未提交都由加载回路自身的错误通道或后续查询负责，只有真正提交的新数据才算刷新成功。
    const outcome = await deps.refreshRecords();
    if (outcome.status === 'committed') {
      deps.notifySuccess('评审数据列表已刷新');
    }
  }

  async function handleOpenProblemList(row: TableRowWithRawRecord) {
    const raw = recordFromTableRow(row);
    if (!raw) {
      return;
    }
    await deps.openProblemList(raw);
  }

  async function handleCreateProblemItemByRow(row: TableRowWithRawRecord) {
    const raw = recordFromTableRow(row);
    if (!raw) {
      return;
    }
    await deps.openCreateProblemItem(raw.id);
  }

  async function handleOpenDetail(row: TableRowWithRawRecord) {
    const raw = recordFromTableRow(row);
    if (!raw) {
      return;
    }
    await deps.openDetail(raw.id);
  }

  async function handleEditRecord(row: TableRowWithRawRecord) {
    const raw = recordFromTableRow(row);
    if (!raw) {
      return;
    }
    await deps.openEditRecord(raw.id);
  }

  function handleCreateRecord() {
    deps.openCreateRecord();
  }

  async function handleDeleteRecord(row: TableRowWithRawRecord) {
    const raw = recordFromTableRow(row);
    if (!raw) {
      return;
    }
    try {
      await deps.confirm(`确认删除评审“${raw.title}”吗？`, '删除评审');
      await deps.deleteRecord(raw.id);
      deps.notifySuccess('评审记录已删除');
      await deps.refreshRecords();
    } catch (error) {
      if (error !== 'cancel') {
        deps.notifyError(getErrorMessage(error, '评审记录删除失败'));
      }
    }
  }

  async function handleDeleteProblemItem(recordId: number, itemId: number) {
    try {
      await deps.confirm('确认删除这条评审问题吗？', '删除评审问题');
      await deps.deleteProblemItem(recordId, itemId);
      deps.notifySuccess('评审问题已删除');
      await deps.refreshAfterProblemItemMutation(recordId);
    } catch (error) {
      if (error !== 'cancel') {
        deps.notifyError(getErrorMessage(error, '评审问题删除失败'));
      }
    }
  }

  function openRuleExplanation() {
    ruleExplanationVisible.value = true;
  }

  return {
    ruleExplanationVisible,
    recordFromTableRow,
    handleRefresh,
    handleOpenProblemList,
    handleCreateProblemItemByRow,
    handleOpenDetail,
    handleEditRecord,
    handleCreateRecord,
    handleDeleteRecord,
    handleDeleteProblemItem,
    openRuleExplanation,
  };
}
