import { reactive, ref } from 'vue';
import type { LocationQuery } from 'vue-router';
import type {
  StatisticCellData,
  StatisticDetailCellValue,
  StatisticDetailColumn,
  StatisticDetailLinkValue,
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
  filters?: Record<string, string>;
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
  const detailQuickFilterValues = reactive<Record<string, string>>({});
  const detailQuickFilterInputDrafts = reactive<Record<string, string>>({});
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
    resetDetailQuickFilterState();
  }

  function canOpenDetailCell(cell: StatisticCellData | null | undefined) {
    return Boolean(cell?.drilldown && Number(cell.numericValue) > 0);
  }

  function clearDetailRouteQuery() {
    return deps.replaceRouteQuery({
      detailVisible: '',
      detailRowKey: '',
      detailColumnKey: '',
      detailPage: '',
      detailPageSize: '',
      detailSortBy: '',
      detailSortOrder: '',
    });
  }

  function isStructuredCellValue(value: unknown): value is StatisticDetailLinkValue {
    return value != null && typeof value === 'object' && 'label' in value;
  }

  function detailCellValue(record: Record<string, unknown>, column: StatisticDetailColumn): StatisticDetailCellValue {
    const value = record[column.key];
    const fallbackLink = issueLinkForColumn(record, column);
    if (value == null || value === '') {
      return '-';
    }
    if (isStructuredCellValue(value)) {
      return {
        label: String(value.label ?? '-'),
        href: typeof value.href === 'string' && value.href ? value.href : null,
      };
    }
    if (fallbackLink) {
      return {
        label: String(value),
        href: fallbackLink,
      };
    }
    if (typeof value === 'object') {
      return JSON.stringify(value);
    }
    return String(value);
  }

  function issueLinkForColumn(record: Record<string, unknown>, column: StatisticDetailColumn) {
    if (!isIssueColumn(column)) {
      return null;
    }
    const candidates = [record.issueUrl, record.gitlabUrl, record.issueLink, record.webUrl];
    const link = candidates.find((item) => typeof item === 'string' && item.trim());
    return typeof link === 'string' ? link : null;
  }

  function isIssueColumn(column: StatisticDetailColumn) {
    const key = column.key.toLowerCase();
    return key === 'iid' || key === 'issueiid' || key === 'issueid' || key === 'issuenumber';
  }

  async function loadDetail() {
    if (!activeRow.value || !activeCell.value) {
      return;
    }
    detailLoading.value = true;
    try {
      const detailFilters = buildDetailFilters();
      detail.value = await deps.loadDetails(deps.boardKey(), {
        rowKey: activeRow.value.rowKey,
        columnKey: activeCell.value.columnKey,
        page: detailPagination.page,
        size: detailPagination.size,
        sortField: detailPagination.sortField || undefined,
        sortOrder: detailPagination.sortOrder || undefined,
        ...(Object.keys(detailFilters).length ? { filters: detailFilters } : {}),
        filterGroup: deps.getFilterGroup(),
      });
    } catch (error) {
      deps.notifyError((error as Error).message);
    } finally {
      detailLoading.value = false;
    }
  }

  async function openDetail(row: StatisticRowData, cell: StatisticCellData, defaultPageSize: number) {
    if (!canOpenDetailCell(cell)) {
      return;
    }
    resetDetailQuickFilterState();
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

  function handleDetailQuickFilterInputUpdate(key: string, value: string) {
    detailQuickFilterInputDrafts[key] = value;
  }

  function handleDetailQuickFilterChange(key: string, value: string | string[] | null) {
    const nextValue = Array.isArray(value) ? value.join(',') : String(value ?? '');
    setDetailQuickFilterValue(key, nextValue);
    reloadDetailFromFirstPage();
  }

  function applyDetailQuickFilters() {
    for (const [key, value] of Object.entries(detailQuickFilterInputDrafts)) {
      setDetailQuickFilterValue(key, value);
    }
    reloadDetailFromFirstPage();
  }

  function resetDetailQuickFilters() {
    resetDetailQuickFilterState();
    reloadDetailFromFirstPage();
  }

  function handleDetailVisibleChange(visible: boolean) {
    if (visible) {
      return;
    }
    detailVisible.value = false;
    clearDetailState();
    void clearDetailRouteQuery();
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
    if (activeRow.value && canOpenDetailCell(activeCell.value)) {
      await loadDetail();
      return;
    }
    detailVisible.value = false;
    clearDetailState();
    await clearDetailRouteQuery();
  }

  function buildDetailFilters() {
    const filters: Record<string, string> = { ...(activeCell.value?.detailParams ?? {}) };
    for (const [key, value] of Object.entries(detailQuickFilterValues)) {
      const trimmed = value.trim();
      if (trimmed) {
        filters[key] = trimmed;
      }
    }
    return filters;
  }

  function setDetailQuickFilterValue(key: string, value: string) {
    const trimmed = value.trim();
    detailQuickFilterInputDrafts[key] = value;
    if (trimmed) {
      detailQuickFilterValues[key] = trimmed;
      return;
    }
    delete detailQuickFilterValues[key];
  }

  function resetDetailQuickFilterState() {
    for (const key of Object.keys(detailQuickFilterValues)) {
      delete detailQuickFilterValues[key];
    }
    for (const key of Object.keys(detailQuickFilterInputDrafts)) {
      delete detailQuickFilterInputDrafts[key];
    }
  }

  function reloadDetailFromFirstPage() {
    detailPagination.page = 1;
    void deps.replaceRouteQuery({ detailPage: 1 });
    void loadDetail();
  }

  return {
    detailLoading,
    detailVisible,
    activeRow,
    activeCell,
    detail,
    detailPagination,
    detailQuickFilterValues,
    detailQuickFilterInputDrafts,
    detailCellValue,
    loadDetail,
    openDetail,
    handleDetailSortChange,
    handleDetailCurrentChange,
    handleDetailSizeChange,
    handleDetailQuickFilterInputUpdate,
    handleDetailQuickFilterChange,
    applyDetailQuickFilters,
    resetDetailQuickFilters,
    handleDetailVisibleChange,
    syncFromRoute,
    syncPaginationFromRoute,
  };
}
