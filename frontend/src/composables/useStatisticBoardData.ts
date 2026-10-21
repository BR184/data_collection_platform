import { computed, watch, getCurrentScope, onScopeDispose } from 'vue';
import type { LocationQuery } from 'vue-router';
import type { StatisticBoardControlOptions, StatisticBoardResponse, StatisticDetailResponse, StatisticFilterGroup, StatisticBoardRuleExplanationResponse } from '../types/api';
import type { ReadViewTransport } from '../types/api/read-view';
import type { BlobResponse } from '../api-client/request';
import { getErrorMessage } from '../utils/user-message';
import { useAcceptedPageView } from './useAcceptedPageView';
import { readViewsApi } from '../api-client/read-views-api';

/** 筛选是已接受画面的一部分，下载与明细不能重新取当前编辑中的条件。 */
export interface StatisticBoardDataRequest {
  filterGroup: StatisticFilterGroup | null;
  filters?: Record<string, string>;
}

export interface PreparedStatisticDetail {
  rowKey: string;
  columnKey: string;
  response: StatisticDetailResponse;
}

export interface StatisticBoardFrame {
  board: StatisticBoardResponse;
  options: StatisticBoardControlOptions | null;
  query: LocationQuery;
  request: StatisticBoardDataRequest;
  openedDetail: PreparedStatisticDetail | null;
  openedRule: StatisticBoardRuleExplanationResponse | null;
}

export interface StatisticBoardDataDependencies {
  boardKey: () => string;
  getFilterGroup: () => StatisticFilterGroup | null;
  getControlParams: () => Record<string, string>;
  getRouteQuery: () => LocationQuery;
  getReadScope: () => Record<string, string>;
  loadBoardData: (boardKey: string, request: StatisticBoardDataRequest, read: ReadViewTransport) => Promise<StatisticBoardResponse>;
  loadControlOptions: (boardKey: string, request: StatisticBoardDataRequest, read: ReadViewTransport) => Promise<StatisticBoardControlOptions | null>;
  loadOpenedDetail: (board: StatisticBoardResponse, query: LocationQuery, request: StatisticBoardDataRequest,
    read: ReadViewTransport) => Promise<PreparedStatisticDetail | null>;
  loadOpenedRule: (boardKey: string, request: StatisticBoardDataRequest,
    read: ReadViewTransport) => Promise<StatisticBoardRuleExplanationResponse | null>;
  exportBoardFile: (boardKey: string, request: StatisticBoardDataRequest, read: ReadViewTransport) => Promise<BlobResponse>;
  onBoardLoaded: (response: StatisticBoardResponse) => void;
  notifySuccess: (message: string) => void;
  notifyError: (message: string) => void;
  downloadFile?: (blob: Blob, filename: string) => void;
  views?: Pick<typeof readViewsApi, 'acquire' | 'renew' | 'release'>;
}

/** 只把已经完成身份核验的文件交给浏览器下载。 */
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

/** 主表、成员候选、范围标题及打开的明细一起准备；失败不撤销已经接受的画面。 */
export function useStatisticBoardData(deps: StatisticBoardDataDependencies) {
  const page = useAcceptedPageView<StatisticBoardFrame>({
    pageKey: deps.boardKey(), views: deps.views,
    load: async () => { throw new Error('统计页准备缺少固定筛选输入'); },
  });
  const acceptedFrame = computed(() => page.accepted.value?.data ?? null);
  const board = computed(() => acceptedFrame.value?.board ?? null);
  const errorMessage = computed(() => page.lastError.value ? getErrorMessage(page.lastError.value, '看板数据加载失败') : '');
  watch(() => acceptedFrame.value, (frame, previous) => {
    if (frame && frame !== previous) { deps.onBoardLoaded(frame.board); }
  }, { flush: 'sync' });

  async function loadBoard(showError = true) {
    const key = deps.boardKey();
    const request: StatisticBoardDataRequest = JSON.parse(JSON.stringify({ filterGroup: deps.getFilterGroup(), filters: deps.getControlParams() }));
    const query = JSON.parse(JSON.stringify(deps.getRouteQuery()));
    const scope = { ...deps.getReadScope() };
    const accepted = await page.prepare(scope, async (_scope, read) => {
      // 自动换版保持接受的主查询，但每次准备都捕获当时实际打开的详情集合。
      const companionQuery: LocationQuery = { ...query };
      for (const key of Object.keys(companionQuery)) { if (key.startsWith('detail')) delete companionQuery[key]; }
      for (const [key, value] of Object.entries(deps.getRouteQuery())) {
        if (key.startsWith('detail')) companionQuery[key] = value;
      }
      const [nextBoard, options] = await Promise.all([
        deps.loadBoardData(key, request, read), deps.loadControlOptions(key, request, read),
      ]);
      if (read.signal?.aborted) { throw new DOMException('页面准备已取消', 'AbortError'); }
      if (options && !options.scopeReadable) { throw new Error(options.reason || '该范围尚无完整成员产出'); }
      const [openedDetail, openedRule] = await Promise.all([
        deps.loadOpenedDetail(nextBoard, companionQuery, request, read), deps.loadOpenedRule(key, request, read),
      ]);
      return { board: nextBoard, options, request, query: companionQuery, openedDetail, openedRule };
    });
    if (!accepted && showError && page.lastError.value) { deps.notifyError(errorMessage.value); }
  }

  async function exportBoard() {
    try {
      await page.readAccepted((frame, read) => deps.exportBoardFile(deps.boardKey(), frame.request, read), file => {
        (deps.downloadFile ?? downloadStatisticBoardFile)(file.blob, file.filename || `${deps.boardKey()}.xlsx`);
        deps.notifySuccess('导出成功');
      });
    } catch (error) { deps.notifyError(getErrorMessage(error, '看板导出失败')); }
  }

  page.start();
  if (getCurrentScope()) { onScopeDispose(page.dispose); }
  return { loading: page.preparing, board, acceptedFrame, readMetadata: computed(() => page.accepted.value?.metadata ?? null),
    errorMessage, loadBoard, exportBoard, readAccepted: page.readAccepted,
    // 作废只取消准备中的请求，不能清空已经接受的版本；离开组件由 dispose 释放。
    invalidateBoardRequest: page.cancelPreparation, clearPrivateData: page.clearPrivateData, dispose: page.dispose };
}
