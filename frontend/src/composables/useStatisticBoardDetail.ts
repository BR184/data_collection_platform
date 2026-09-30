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
  routeDetailCollection,
  routeDetailPage,
  routeDetailPageSize,
  routeDetailSortBy,
  routeDetailSortOrder,
  routeDetailVisible,
} from '../components/statistic-board-route-query';
import { getErrorMessage } from '../utils/user-message';

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

/** 明细集合切换参数名；与后端 Population 枚举同名，后端拒绝非法值。 */
const DETAIL_COLLECTION_PARAM = 'population';

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
  // 用户显式选择的集合；空表示沿用指标定义的默认集合。
  const detailCollection = ref('');
  const detailQuickFilterValues = reactive<Record<string, string>>({});
  const detailQuickFilterInputDrafts = reactive<Record<string, string>>({});
  const detailPagination = reactive({
    page: 1,
    size: 10,
    sortField: '',
    sortOrder: 'descending',
  });
  // 明细请求序号：切换页码、集合、排序或关闭明细都会让在途请求失效。
  // 迟到的成功会写回旧集合的内容、迟到的失败会弹出已经不相关的错误、迟到的结束会提前清掉 loading。
  let detailRequestSequence = 0;

  function syncPaginationFromRoute(query: DetailRouteQuery, defaultPageSize: number) {
    detailPagination.page = routeDetailPage(query);
    detailPagination.size = routeDetailPageSize(query, defaultPageSize);
    detailPagination.sortField = routeDetailSortBy(query);
    detailPagination.sortOrder = routeDetailSortOrder(query);
  }

  /** 关闭或无行可下钻时作废在途请求，避免其响应回填已经关闭的明细。 */
  function invalidateDetailRequests() {
    detailRequestSequence += 1;
    detailLoading.value = false;
  }

  function clearDetailState() {
    invalidateDetailRequests();
    detail.value = null;
    detailCollection.value = '';
    activeRow.value = null;
    activeCell.value = null;
    resetDetailQuickFilterState();
  }

  /** 路由/范围失效时立即关闭旧明细并作废在途请求；URL 由有效的新路由决定是否重新打开。 */
  function invalidateForRouteChange() {
    detailVisible.value = false;
    clearDetailState();
  }

  /**
   * 单元格是否可下钻只取决于后端声明的能力：数量、比率与周期指标即便为 0 或空也可打开。
   * 旧看板的历史数值条件已由生产端写入该能力，前端不再按数值猜测。
   */
  function canOpenDetailCell(cell: StatisticCellData | null | undefined) {
    return Boolean(cell?.drilldown);
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
      detailCollection: '',
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
    detailRequestSequence += 1;
    const requestId = detailRequestSequence;
    detailLoading.value = true;
    try {
      const detailFilters = buildDetailFilters();
      const response = await deps.loadDetails(deps.boardKey(), {
        rowKey: activeRow.value.rowKey,
        columnKey: activeCell.value.columnKey,
        page: detailPagination.page,
        size: detailPagination.size,
        sortField: detailPagination.sortField || undefined,
        sortOrder: detailPagination.sortOrder || undefined,
        ...(Object.keys(detailFilters).length ? { filters: detailFilters } : {}),
        filterGroup: deps.getFilterGroup(),
      });
      if (requestId !== detailRequestSequence) {
        return;
      }
      detail.value = response;
    } catch (error) {
      if (requestId !== detailRequestSequence) {
        return;
      }
      deps.notifyError(getErrorMessage(error, '看板详情加载失败'));
    } finally {
      if (requestId === detailRequestSequence) {
        detailLoading.value = false;
      }
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
      detailCollection: '',
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
    detailCollection.value = routeDetailCollection(query);
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
    // 来源版本、业务日与来源选择来自单元格 detailParams，集合切换与快速筛选只在其上追加。
    const filters: Record<string, string> = { ...(activeCell.value?.detailParams ?? {}) };
    if (detailCollection.value) {
      filters[DETAIL_COLLECTION_PARAM] = detailCollection.value;
    }
    for (const [key, value] of Object.entries(detailQuickFilterValues)) {
      const trimmed = value.trim();
      if (trimmed) {
        filters[key] = trimmed;
      }
    }
    return filters;
  }

  /**
   * 切换下钻集合：换集合必须重取并回到第一页，避免沿用旧集合的页码。
   *
   * <p>这里只负责规范化选择与路由写入；明细重取由路由监听统一触发，避免同一集合切换发出两次请求。
   */
  async function selectDetailCollection(key: string) {
    const next = String(key ?? '').trim();
    if (next === detailCollection.value) {
      return;
    }
    detailCollection.value = next;
    detailPagination.page = 1;
    await deps.replaceRouteQuery({ detailCollection: next, detailPage: 1 });
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

  /**
   * 快速筛选与重置回第一页。
   *
   * <p>筛选值只存在本地状态里，不在路由上：页码本来就在第一页时路由不会变化，路由监听不会触发，
   * 因此这里必须自己发起一次请求；页码不在第一页时只改路由、由监听统一重取。两条路径互斥，
   * 同一次筛选变更不会发出两次请求。
   */
  function reloadDetailFromFirstPage() {
    if (detailPagination.page === 1) {
      void loadDetail();
      return;
    }
    detailPagination.page = 1;
    void deps.replaceRouteQuery({ detailPage: 1 });
  }

  return {
    detailLoading,
    detailVisible,
    activeRow,
    activeCell,
    detail,
    detailPagination,
    detailCollection,
    detailQuickFilterValues,
    detailQuickFilterInputDrafts,
    detailCellValue,
    loadDetail,
    selectDetailCollection,
    openDetail,
    handleDetailSortChange,
    handleDetailCurrentChange,
    handleDetailSizeChange,
    handleDetailQuickFilterInputUpdate,
    handleDetailQuickFilterChange,
    resetDetailQuickFilters,
    handleDetailVisibleChange,
    invalidateForRouteChange,
    syncFromRoute,
    syncPaginationFromRoute,
  };
}
