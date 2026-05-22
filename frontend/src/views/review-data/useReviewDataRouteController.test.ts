import { describe, expect, it, vi } from 'vitest';
import type { LocationQuery } from 'vue-router';
import type { StatisticFilterGroup } from '../../types/api';
import { useReviewDataRouteController } from './useReviewDataRouteController';

function setup(routeQuery: LocationQuery = {}) {
  let filterPayload: StatisticFilterGroup | null = {
    logic: 'AND',
    conditions: [{ fieldKey: 'projectName', operator: 'eq', value: 'Project A' }],
  };
  return {
    getRouteQuery: vi.fn(() => routeQuery),
    getKeyword: vi.fn(() => ' keyword '),
    getPage: vi.fn(() => 2),
    getPageSize: vi.fn(() => 50),
    getSortBy: vi.fn(() => 'updatedAt'),
    getSortOrder: vi.fn(() => 'desc' as 'asc' | 'desc' | ''),
    patchQuery: vi.fn<(patch: Record<string, string | number | null | undefined>) => Promise<void>>(() =>
      Promise.resolve(),
    ),
    debouncedPatchQuery: vi.fn<(patch: Record<string, string | number | null | undefined>) => void>(),
    initializeFromQuery: vi.fn<(query: LocationQuery) => void>(),
    buildFilterPayload: vi.fn(() => filterPayload),
    resetDraft: vi.fn<() => void>(),
    buildApplyQueryPatch: vi.fn(() => ({ filterGroup: 'serialized-filter' })),
    buildResetQueryPatch: vi.fn(() => ({ filterGroup: null, 'filters.0.field': null })),
    loadRows: vi.fn<() => Promise<void>>(() => Promise.resolve()),
    setFilterPayload: (next: StatisticFilterGroup | null) => {
      filterPayload = next;
    },
  };
}

describe('useReviewDataRouteController', () => {
  it('builds record query params from route table state and applied filters', () => {
    const deps = setup({ filterGroup: 'legacy' });
    const controller = useReviewDataRouteController(deps);

    controller.syncFilterDraftFromRoute();

    expect(deps.initializeFromQuery).toHaveBeenCalledWith({ filterGroup: 'legacy' });
    expect(controller.buildRecordQueryParams()).toEqual({
      keyword: 'keyword',
      filterGroup: {
        logic: 'AND',
        conditions: [{ fieldKey: 'projectName', operator: 'eq', value: 'Project A' }],
      },
      page: 2,
      size: 50,
      sortBy: 'updatedAt',
      sortOrder: 'desc',
    });
    expect(controller.buildRecordQueryParams({ page: 1, size: 100 }).page).toBe(1);
  });

  it('resets draft filters and table query keys', async () => {
    const deps = setup({ keyword: 'abc', filterGroup: 'serialized-filter' });
    const controller = useReviewDataRouteController(deps);

    await controller.handleReset();

    expect(deps.resetDraft).toHaveBeenCalledOnce();
    expect(controller.appliedFilterGroup.value).toBeNull();
    expect(deps.patchQuery).toHaveBeenCalledWith({
      keyword: '',
      filterGroup: null,
      'filters.0.field': null,
      title: '',
      projectName: '',
      moduleName: '',
      reviewOwner: '',
      reviewType: '',
      problemStatus: '',
      reviewExpert: '',
      sortBy: 'updatedAt',
      sortOrder: 'desc',
      page: 1,
    });
  });

  it('applies filters and reloads rows when the query did not change', async () => {
    const deps = setup({
      keyword: 'keyword',
      filterGroup: 'serialized-filter',
      page: '1',
    });
    const controller = useReviewDataRouteController(deps);

    await controller.handleQuery(' keyword ');

    expect(deps.patchQuery).toHaveBeenCalledWith({
      keyword: 'keyword',
      filterGroup: 'serialized-filter',
      page: 1,
    });
    expect(deps.loadRows).toHaveBeenCalledOnce();
  });

  it('patches keyword, pagination, and sort changes', async () => {
    const deps = setup();
    const controller = useReviewDataRouteController(deps);

    controller.handleKeywordSearch(' next ');
    await controller.handleSortChange({ prop: 'title', order: 'ascending' });
    await controller.handleSortChange({ prop: '', order: null });
    await controller.handlePageChange(3);
    await controller.handleSizeChange(100);

    expect(deps.debouncedPatchQuery).not.toHaveBeenCalled();
    expect(deps.patchQuery).toHaveBeenNthCalledWith(1, { keyword: 'next', page: 1 });
    expect(deps.patchQuery).toHaveBeenNthCalledWith(2, { sortBy: 'title', sortOrder: 'asc', page: 1 });
    expect(deps.patchQuery).toHaveBeenNthCalledWith(3, { sortBy: 'updatedAt', sortOrder: 'desc', page: 1 });
    expect(deps.patchQuery).toHaveBeenNthCalledWith(4, { page: 3 });
    expect(deps.patchQuery).toHaveBeenNthCalledWith(5, { pageSize: 100, page: 1 });
  });
});
