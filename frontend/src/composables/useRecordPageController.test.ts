import { describe, expect, it, vi } from 'vitest';
import { useRecordPageController } from './useRecordPageController';

describe('useRecordPageController', () => {
  it('builds reset query with defaults, cleared keys, and reset filter patch', async () => {
    const patchQuery = vi.fn(() => Promise.resolve());
    const resetDraft = vi.fn();

    const controller = useRecordPageController({
      getRouteQuery: () => ({ keyword: 'abc', projectId: '325' }),
      patchQuery,
      resetDraft,
      buildResetQueryPatch: () => ({ filterGroup: null, legacyFilter: null }),
      defaultSortBy: 'updatedAt',
      defaultSortOrder: 'desc',
      resetClearKeys: ['keyword', 'projectId'],
    });

    await controller.handleReset();

    expect(resetDraft).toHaveBeenCalledOnce();
    expect(patchQuery).toHaveBeenCalledWith({
      filterGroup: null,
      legacyFilter: null,
      page: 1,
      sortBy: 'updatedAt',
      sortOrder: 'desc',
      keyword: null,
      projectId: null,
    });
  });

  it('applies query patch and clears query-only keys when querying', async () => {
    const patchQuery = vi.fn(() => Promise.resolve());

    const controller = useRecordPageController({
      getRouteQuery: () => ({ keyword: 'abc' }),
      patchQuery,
      buildApplyQueryPatch: () => ({ filterGroup: '{"logic":"AND","conditions":[]}' }),
      defaultSortBy: 'updatedAt',
      queryClearKeys: ['issueIid', 'title'],
    });

    await controller.handleQuery();

    expect(patchQuery).toHaveBeenCalledWith({
      filterGroup: '{"logic":"AND","conditions":[]}',
      page: 1,
      issueIid: null,
      title: null,
    });
  });

  it('supports keyword, pagination, sort, refresh, and range clearing behaviors', async () => {
    const patchQuery = vi.fn(() => Promise.resolve());
    const loadTableData = vi.fn(() => Promise.resolve());

    const controller = useRecordPageController({
      getRouteQuery: () => ({ updatedAtStart: '2026-04-01', updatedAtEnd: '2026-04-02' }),
      patchQuery,
      loadTableData,
      defaultSortBy: 'updatedAt',
      defaultSortOrder: 'desc',
      rangeKeys: {
        updatedAtRange: {
          startKey: 'updatedAtStart',
          endKey: 'updatedAtEnd',
        },
      },
    });

    await controller.handleKeywordSearch('hello');
    await controller.handleKeywordSearch('');
    await controller.handleSizeChange(50);
    await controller.handleCurrentChange(3);
    await controller.handleSortChange({ prop: 'createdAt', order: 'ascending' });
    await controller.handleSortChange({ prop: '', order: null });
    await controller.handleRefresh();
    await controller.handleClearFilter('updatedAtRange');
    await controller.handleClearFilter('severityLevel');

    expect(patchQuery).toHaveBeenNthCalledWith(1, { page: 1, keyword: 'hello' });
    expect(patchQuery).toHaveBeenNthCalledWith(2, { page: 1, keyword: null });
    expect(patchQuery).toHaveBeenNthCalledWith(3, { pageSize: 50, page: 1 });
    expect(patchQuery).toHaveBeenNthCalledWith(4, { page: 3 });
    expect(patchQuery).toHaveBeenNthCalledWith(5, {
      sortBy: 'createdAt',
      sortOrder: 'asc',
      page: 1,
    });
    expect(patchQuery).toHaveBeenNthCalledWith(6, {
      sortBy: 'updatedAt',
      sortOrder: 'desc',
      page: 1,
    });
    expect(loadTableData).toHaveBeenCalledOnce();
    expect(patchQuery).toHaveBeenNthCalledWith(7, {
      page: 1,
      updatedAtStart: null,
      updatedAtEnd: null,
    });
    expect(patchQuery).toHaveBeenNthCalledWith(8, {
      page: 1,
      severityLevel: null,
    });
  });

  it('resets filterGroup through reset draft and reset patch', async () => {
    const patchQuery = vi.fn(() => Promise.resolve());
    const resetDraft = vi.fn();

    const controller = useRecordPageController({
      getRouteQuery: () => ({ filterGroup: 'serialized' }),
      patchQuery,
      resetDraft,
      buildResetQueryPatch: () => ({ filterGroup: null, filtersLegacy: null }),
      defaultSortBy: 'updatedAt',
    });

    await controller.handleClearFilter('filterGroup');

    expect(resetDraft).toHaveBeenCalledOnce();
    expect(patchQuery).toHaveBeenCalledWith({
      filterGroup: null,
      filtersLegacy: null,
      page: 1,
    });
  });
});
