import { describe, expect, it, vi } from 'vitest';
import type { LocationQuery } from 'vue-router';
import { useStatisticBoardRouteController } from './useStatisticBoardRouteController';
import type { StatisticFilterDraftGroup } from '../components/statistic-board-filters';

interface ReplaceRouteLocation {
  path: string;
  query: Record<string, string>;
  hash: string;
}

function createFilterDraft(): StatisticFilterDraftGroup {
  return {
    logic: 'AND',
    conditions: [
      {
        id: 'condition-1',
        fieldKey: 'moduleName',
        operator: 'eq',
        value: 'module-a',
        secondaryValue: '',
      },
    ],
  };
}

function setup(routeQuery: LocationQuery) {
  return {
    getRouteQuery: vi.fn(() => routeQuery),
    getRoutePath: vi.fn(() => '/statistics/code-review'),
    getRouteHash: vi.fn(() => '#board'),
    replaceRoute: vi.fn<(location: ReplaceRouteLocation) => Promise<void>>(() => Promise.resolve()),
    resetFilterDraft: vi.fn<() => void>(),
  };
}

describe('useStatisticBoardRouteController', () => {
  it('applies filter query and clears drilldown route state', async () => {
    const deps = setup({
      filterGroup: '{"logic":"AND","conditions":[]}',
      detailVisible: '1',
      detailRowKey: 'row-a',
      detailColumnKey: 'blocked',
      detailPage: '3',
      detailPageSize: '50',
      detailSortBy: 'syncedAt',
      detailSortOrder: 'ascending',
    });
    const controller = useStatisticBoardRouteController(deps);

    await controller.applyFiltersToRoute(createFilterDraft());

    const query = deps.replaceRoute.mock.calls[0][0].query;
    expect(JSON.parse(String(query.filterGroup))).toMatchObject({
      logic: 'AND',
      conditions: [{ fieldKey: 'moduleName', operator: 'eq', value: 'module-a' }],
    });
    expect(query.detailVisible).toBeUndefined();
    expect(query.detailRowKey).toBeUndefined();
    expect(query.detailColumnKey).toBeUndefined();
    expect(query.detailPage).toBeUndefined();
    expect(query.detailPageSize).toBeUndefined();
    expect(query.detailSortBy).toBeUndefined();
    expect(query.detailSortOrder).toBeUndefined();
    expect(deps.replaceRoute).toHaveBeenCalledWith({
      path: '/statistics/code-review',
      query,
      hash: '#board',
    });
  });

  it('resets filter draft and removes serialized and legacy filter query keys', async () => {
    const deps = setup({
      filterGroup: '{"logic":"AND","conditions":[]}',
      filterLogic: 'OR',
      'filters.0.field': 'moduleName',
      'filters.0.operator': 'eq',
      'filters.0.value': 'module-a',
    });
    const controller = useStatisticBoardRouteController(deps);

    await controller.resetFilters();

    expect(deps.resetFilterDraft).toHaveBeenCalledOnce();
    const query = deps.replaceRoute.mock.calls[0][0].query;
    expect(query.filterGroup).toBeUndefined();
    expect(query.filterLogic).toBeUndefined();
    expect(query['filters.0.field']).toBeUndefined();
    expect(query['filters.0.operator']).toBeUndefined();
    expect(query['filters.0.value']).toBeUndefined();
  });
});
