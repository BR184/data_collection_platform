import { reactive, ref } from 'vue';
import type { LocationQuery } from 'vue-router';
import type {
  StatisticCellData,
  StatisticDetailColumn,
  StatisticDetailResponse,
  StatisticFilterGroup,
  StatisticRowData,
} from '../types/api';
import {
  routeDetailPage,
  routeDetailPageSize,
  routeDetailSortBy,
  routeDetailSortOrder,
  routeDetailVisible,
} from '../components/statistic-board-route-query';

type DetailRouteQuery = LocationQuery;

interface StatisticBoardDetailParams {
  rowKey: string;
  columnKey: string;
  page: number;
  size: number;
  sortField?: string;
  sortOrder?: string;
  filterGroup?: StatisticFilterGroup | null;
}

interface StatisticBoardDetailDependencies {
  boardKey: () => string;
  getFilterGroup: () => StatisticFilterGroup | null;
  loadDetails: (boardKey: string, params: StatisticBoardDetailParams) => Promise<StatisticDetailResponse>;
  notifyError: (message: string) => void;
  replaceRouteQuery: (patch: Record<string, string | number | null | undefined>) => Promise<void>;
}

export function useStatisticBoardDetail(deps: StatisticBoardDetailDependencies) {
  const detailLoading = ref(false);
  const detailVisible = ref(false);
  const activeRow = ref<StatisticRowData | null>(null);
  const activeCell = ref<StatisticCellData | null>(null);
  const detail = ref<StatisticDetailResponse | null>(null);
  const detailPagination = reactive({
    page: 1,
    size: 10,
    sortField: '',
    sortOrder: 'descending',
  });

  function syncPaginationFromRoute(query: DetailRouteQuery, defaultPageSize: number) {
    detailPagination.page = routeDetailPage(query);
    detailPagination.size = routeDetailPageSize(query, defaultPageSize);
    detailPagination.sortField = routeDetailSortBy(query);
    detailPagination.sortOrder = routeDetailSortOrder(query);
  }

  function clearDetailState() {
    detail.value = null;
    activeRow.value = null;
    activeCell.value = null;
  }

  function detailCellValue(record: Record<string, unknown>, column: StatisticDetailColumn) {
    const value = record[column.key];
    if (value == null || value === '') {
      return '-';
    }
    if (typeof value === 'object') {
      return JSON.stringify(value);
    }
    return String(value);
  }

  async function loadDetail() {
    if (!activeRow.value || !activeCell.value) {
      return;
    }
    detailLoading.value = true;
    try {
      detail.value = await deps.loadDetails(deps.boardKey(), {
        rowKey: activeRow.value.rowKey,
        columnKey: activeCell.value.columnKey,
        page: detailPagination.page,
        size: detailPagination.size,
        sortField: detailPagination.sortField || undefined,
        sortOrder: detailPagination.sortOrder || undefined,
        filterGroup: deps.getFilterGroup(),
      });
    } catch (error) {
      deps.notifyError((error as Error).message);
    } finally {
      detailLoading.value = false;
    }
  }

  async function openDetail(row: StatisticRowData, cell: StatisticCellData, defaultPageSize: number) {
    if (!cell.drilldown) {
      return;
    }
    activeRow.value = row;
    activeCell.value = cell;
    await deps.replaceRouteQuery({
      detailVisible: '1',
      detailRowKey: row.rowKey,
      detailColumnKey: cell.columnKey,
      detailPage: 1,
      detailPageSize: defaultPageSize,
      detailSortBy: 'syncedAt',
      detailSortOrder: 'descending',
    });
  }

  function handleDetailSortChange({
    prop,
    order,
  }: {
    column: unknown;
    prop: string;
    order: 'ascending' | 'descending' | null;
  }) {
    void deps.replaceRouteQuery({
      detailSortBy: prop || '',
      detailSortOrder: order ?? 'descending',
      detailPage: 1,
    });
  }

  function handleDetailCurrentChange(nextPage: number) {
    void deps.replaceRouteQuery({
      detailPage: nextPage,
    });
  }

  function handleDetailSizeChange(nextSize: number) {
    void deps.replaceRouteQuery({
      detailPageSize: nextSize,
      detailPage: 1,
    });
  }

  function handleDetailVisibleChange(visible: boolean) {
    if (visible) {
      return;
    }
    detailVisible.value = false;
    clearDetailState();
    void deps.replaceRouteQuery({
      detailVisible: '',
      detailRowKey: '',
      detailColumnKey: '',
      detailPage: '',
      detailPageSize: '',
      detailSortBy: '',
      detailSortOrder: '',
    });
  }

  async function syncFromRoute(query: DetailRouteQuery, rows: StatisticRowData[], defaultPageSize: number) {
    syncPaginationFromRoute(query, defaultPageSize);
    detailVisible.value = routeDetailVisible(query);
    if (!detailVisible.value) {
      clearDetailState();
      return;
    }
    activeRow.value = rows.find((row) => row.rowKey === String(query.detailRowKey ?? '')) ?? null;
    activeCell.value =
      activeRow.value?.cells.find((cell) => cell.columnKey === String(query.detailColumnKey ?? '')) ?? null;
    if (activeRow.value && activeCell.value) {
      await loadDetail();
      return;
    }
    detail.value = null;
  }

  return {
    detailLoading,
    detailVisible,
    activeRow,
    activeCell,
    detail,
    detailPagination,
    detailCellValue,
    loadDetail,
    openDetail,
    handleDetailSortChange,
    handleDetailCurrentChange,
    handleDetailSizeChange,
    handleDetailVisibleChange,
    syncFromRoute,
    syncPaginationFromRoute,
  };
}
