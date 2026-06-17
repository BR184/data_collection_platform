import { ref, shallowRef } from 'vue';
import type { StatisticBoardResponse, StatisticFilterGroup } from '../types/api';
import type { BlobResponse } from '../api-client/request';

interface StatisticBoardDataRequest {
  filterGroup: StatisticFilterGroup | null;
}

export interface StatisticBoardDataDependencies {
  boardKey: () => string;
  getFilterGroup: () => StatisticFilterGroup | null;
  loadBoardData: (boardKey: string, request: StatisticBoardDataRequest) => Promise<StatisticBoardResponse>;
  exportBoardFile: (boardKey: string, request: StatisticBoardDataRequest) => Promise<BlobResponse>;
  onBoardLoaded: (response: StatisticBoardResponse) => void;
  notifySuccess: (message: string) => void;
  notifyError: (message: string) => void;
  downloadFile?: (blob: Blob, filename: string) => void;
}

export function downloadStatisticBoardFile(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  document.body.removeChild(anchor);
  URL.revokeObjectURL(url);
}

export function useStatisticBoardData(deps: StatisticBoardDataDependencies) {
  const loading = ref(false);
  const board = shallowRef<StatisticBoardResponse | null>(null);
  const errorMessage = ref('');

  function buildRequest(): StatisticBoardDataRequest {
    return {
      filterGroup: deps.getFilterGroup(),
    };
  }

  async function loadBoard(showError = true) {
    loading.value = true;
    errorMessage.value = '';
    try {
      const response = await deps.loadBoardData(deps.boardKey(), buildRequest());
      board.value = response;
      deps.onBoardLoaded(response);
    } catch (error) {
      errorMessage.value = (error as Error).message;
      if (showError) {
        deps.notifyError((error as Error).message);
      }
    } finally {
      loading.value = false;
    }
  }

  async function exportBoard() {
    try {
      const file = await deps.exportBoardFile(deps.boardKey(), buildRequest());
      const downloadFile = deps.downloadFile ?? downloadStatisticBoardFile;
      downloadFile(file.blob, file.filename || exportFilename(deps.boardKey()));
      deps.notifySuccess('导出成功');
    } catch (error) {
      deps.notifyError((error as Error).message);
    }
  }

  return {
    loading,
    board,
    errorMessage,
    loadBoard,
    exportBoard,
  };
}

function exportFilename(boardKey: string) {
  if (boardKey === 'system-test-defect-cause') {
    return '缺陷原因统计表.xlsx';
  }
  if (boardKey === 'customer-issue-defect-cause') {
    return '客户问题缺陷原因统计表.xlsx';
  }
  return `${boardKey}.csv`;
}
