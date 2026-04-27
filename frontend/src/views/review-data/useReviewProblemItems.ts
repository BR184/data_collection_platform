import { ref } from 'vue';
import type { ReviewDataProblemItemResponse, ReviewDataRecordRowResponse } from '../../types/api';
import { buildProblemItemTableRows } from '../review-data-management';

type TableRowWithRawRecord = {
  __raw?: ReviewDataRecordRowResponse;
};

export function useReviewProblemItems(
  loadProblemItemsApi: (recordId: number) => Promise<ReviewDataProblemItemResponse[]>,
) {
  const expandedRowKeys = ref<Array<number | string>>([]);
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

  function isProblemExpanded(recordId: number) {
    return expandedRowKeys.value.includes(recordId);
  }

  async function toggleProblemPanel(recordId: number) {
    if (isProblemExpanded(recordId)) {
      expandedRowKeys.value = [];
      return;
    }
    expandedRowKeys.value = [recordId];
    if (!problemItemsMap.value[recordId]) {
      await loadProblemItems(recordId);
    }
  }

  async function handleExpandChange(row: TableRowWithRawRecord, expandedRows: TableRowWithRawRecord[]) {
    const raw = row.__raw;
    if (!raw) {
      return;
    }
    const rowIsExpanded = expandedRows.some((item) => Number(item.__raw?.id) === raw.id);
    expandedRowKeys.value = rowIsExpanded
      ? [raw.id]
      : expandedRowKeys.value.filter((item) => Number(item) !== raw.id);
    if (rowIsExpanded && !problemItemsMap.value[raw.id]) {
      await loadProblemItems(raw.id);
    }
  }

  function problemItemsFor(recordId: number) {
    return buildProblemItemTableRows(problemItemsMap.value[recordId] ?? []);
  }

  return {
    expandedRowKeys,
    problemItemsMap,
    problemLoadingMap,
    loadProblemItems,
    isProblemExpanded,
    toggleProblemPanel,
    handleExpandChange,
    problemItemsFor,
  };
}
