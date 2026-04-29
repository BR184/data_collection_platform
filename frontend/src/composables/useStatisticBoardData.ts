import { ref, shallowRef } from 'vue';
import type { StatisticBoardResponse, StatisticFilterGroup } from '../types/api';

interface StatisticBoardDataRequest {
  filterGroup: StatisticFilterGroup | null;
}

export interface StatisticBoardDataDependencies {
  boardKey: () => string;
  getFilterGroup: () => StatisticFilterGroup | null;
  loadBoardData: (boardKey: string, request: StatisticBoardDataRequest) => Promise<StatisticBoardResponse>;
  exportBoardCsv: (boardKey: string, request: StatisticBoardDataRequest) => Promise<string>;
  onBoardLoaded: (response: StatisticBoardResponse) => void;
  notifySuccess: (message: string) => void;
  notifyError: (message: string) => void;
  downloadCsv?: (csv: string, filename: string) => void;
}

export function downloadStatisticBoardCsv(csv: string, filename: string) {
  const blob = new Blob([`\uFEFF${csv}`], { type: 'text/csv;charset=utf-8;' });
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
      const csv = await deps.exportBoardCsv(deps.boardKey(), buildRequest());
      const downloadCsv = deps.downloadCsv ?? downloadStatisticBoardCsv;
      downloadCsv(csv, `${deps.boardKey()}.csv`);
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
