import { ref, shallowRef } from 'vue';
import type { StatisticBoardResponse, StatisticFilterGroup } from '../types/api';
import type { BlobResponse } from '../api-client/request';
import { getErrorMessage } from '../utils/user-message';

interface StatisticBoardDataRequest {
  filterGroup: StatisticFilterGroup | null;
  filters?: Record<string, string>;
}

export interface StatisticBoardDataDependencies {
  boardKey: () => string;
  getFilterGroup: () => StatisticFilterGroup | null;
  /** 控制参数（行维度、成员选择等）；未配置的看板返回空对象。 */
  getControlParams?: () => Record<string, string>;
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
  // 请求序号：主表加载可以被并发的范围/成员切换重复触发，只有最后一次请求能改写状态。
  // 迟到的成功会覆盖新数据、迟到的失败会把新数据替换成错误、迟到的结束会提前清掉 loading，
  // 三种都必须按序号丢弃。
  let requestSequence = 0;

  function buildRequest(): StatisticBoardDataRequest {
    const controlParams = deps.getControlParams?.() ?? {};
    return {
      filterGroup: deps.getFilterGroup(),
      ...(Object.keys(controlParams).length ? { filters: controlParams } : {}),
    };
  }

  async function loadBoard(showError = true) {
    requestSequence += 1;
    const requestId = requestSequence;
    loading.value = true;
    errorMessage.value = '';
    try {
      const response = await deps.loadBoardData(deps.boardKey(), buildRequest());
      if (requestId !== requestSequence) {
        return;
      }
      board.value = response;
      deps.onBoardLoaded(response);
    } catch (error) {
      if (requestId !== requestSequence) {
        return;
      }
      errorMessage.value = getErrorMessage(error, '看板数据加载失败');
      if (showError) {
        deps.notifyError(errorMessage.value);
      }
    } finally {
      if (requestId === requestSequence) {
        loading.value = false;
      }
    }
  }

  /** 使当前主表请求失效，并结束其 loading；只允许由本 composable 写入 loading。 */
  function invalidateBoardRequest() {
    requestSequence += 1;
    loading.value = false;
  }

  async function exportBoard() {
    try {
      const file = await deps.exportBoardFile(deps.boardKey(), buildRequest());
      const downloadFile = deps.downloadFile ?? downloadStatisticBoardFile;
      downloadFile(file.blob, file.filename || exportFilename(deps.boardKey()));
      deps.notifySuccess('导出成功');
    } catch (error) {
      deps.notifyError(getErrorMessage(error, '看板导出失败'));
    }
  }

  return {
    loading,
    board,
    errorMessage,
    loadBoard,
    invalidateBoardRequest,
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
  return `${boardKey}.xlsx`;
}
