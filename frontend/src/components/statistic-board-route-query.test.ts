import { describe, expect, it } from 'vitest';
import {
  buildFilterGroupFromRouteQuery,
  buildFilterQueryPatch,
  buildResetFilterQueryPatch,
  mergeRouteQuery,
  routeDetailPage,
  routeDetailSortOrder,
  routeDetailVisible,
} from './statistic-board-route-query';

describe('statistic board route query', () => {
  it('builds filter group from route query', () => {
    const result = buildFilterGroupFromRouteQuery({
      filterLogic: 'OR',
      'filters.0.field': 'projectName',
      'filters.0.operator': 'contains',
      'filters.0.value': 'alpha',
      'filters.1.field': 'severityLevel',
      'filters.1.operator': 'eq',
      'filters.1.value': 'P1',
    });

    expect(result.logic).toBe('OR');
    expect(result.conditions).toHaveLength(2);
    expect(result.conditions[0]).toMatchObject({
      fieldKey: 'projectName',
      operator: 'contains',
      value: 'alpha',
    });
  });

  it('prefers serialized filterGroup query when present', () => {
    const result = buildFilterGroupFromRouteQuery({
      filterGroup:
        '{"logic":"OR","conditions":[{"fieldKey":"moduleName","operator":"eq","value":"module-a","secondaryValue":""}]}',
      'filters.0.field': 'projectName',
      'filters.0.operator': 'contains',
      'filters.0.value': 'legacy',
    });

    expect(result.logic).toBe('OR');
    expect(result.conditions).toHaveLength(1);
    expect(result.conditions[0]).toMatchObject({
      fieldKey: 'moduleName',
      operator: 'eq',
      value: 'module-a',
    });
  });

  it('preserves label group metadata in serialized filterGroup query', () => {
    const result = buildFilterGroupFromRouteQuery({
      filterGroup:
        '{"logic":"AND","conditions":[{"fieldKey":"assigneeName","operator":"eq","value":null,"secondaryValue":null,"valueType":"LABEL_GROUP","labelGroupId":1,"labelGroupName":"核心人员"}]}',
    });

    expect(result.conditions[0]).toMatchObject({
      fieldKey: 'assigneeName',
      operator: 'eq',
      value: '__label_group__:1',
      valueType: 'LABEL_GROUP',
      labelGroupId: 1,
      labelGroupName: '核心人员',
    });
  });

  it('preserves label group metadata in serialized filterGroup query', () => {
    const result = buildFilterGroupFromRouteQuery({
      filterGroup:
        '{"logic":"AND","conditions":[{"fieldKey":"assigneeName","operator":"eq","value":null,"secondaryValue":null,"valueType":"LABEL_GROUP","labelGroupId":1,"labelGroupName":"核心人员"}]}',
    });

    expect(result.conditions[0]).toMatchObject({
      fieldKey: 'assigneeName',
      operator: 'eq',
      value: '__label_group__:1',
      valueType: 'LABEL_GROUP',
      labelGroupId: 1,
      labelGroupName: '核心人员',
    });
  });

  it('clears stale filter keys when rebuilding filter query patch', () => {
    const patch = buildFilterQueryPatch(
      {
        'filters.0.field': 'legacy',
        'filters.0.value': 'legacy',
      },
      {
        logic: 'AND',
        conditions: [
          {
            id: '1',
            fieldKey: 'moduleName',
            operator: 'eq',
            value: 'module-a',
            secondaryValue: '',
          },
        ],
      },
    );

    expect(patch.filterGroup).toBeTruthy();
    expect(patch.filterLogic).toBeNull();
    expect(patch['filters.0.field']).toBeNull();
    expect(String(patch.filterGroup)).toContain('"fieldKey":"moduleName"');
  });

  it('serializes label group conditions without flattening them to literal values', () => {
    const patch = buildFilterQueryPatch(
      {},
      {
        logic: 'AND',
        conditions: [
          {
            id: '1',
            fieldKey: 'assigneeName',
            operator: 'eq',
            value: '__label_group__:1',
            secondaryValue: '',
            valueType: 'LABEL_GROUP',
            labelGroupId: 1,
            labelGroupName: '核心人员',
          },
        ],
      },
    );

    expect(String(patch.filterGroup)).toContain('"valueType":"LABEL_GROUP"');
    expect(String(patch.filterGroup)).toContain('"labelGroupId":1');
    expect(String(patch.filterGroup)).toContain('"labelGroupName":"核心人员"');
  });

  it('only serializes completed filter conditions into route query patch', () => {
    const patch = buildFilterQueryPatch(
      {},
      {
        logic: 'AND',
        conditions: [
          {
            id: '1',
            fieldKey: 'moduleName',
            operator: 'eq',
            value: 'module-a',
            secondaryValue: '',
          },
          {
            id: '2',
            fieldKey: 'reviewOwner',
            operator: 'eq',
            value: '',
            secondaryValue: '',
          },
        ],
      },
    );

    expect(String(patch.filterGroup)).toContain('"fieldKey":"moduleName"');
    expect(String(patch.filterGroup)).not.toContain('"fieldKey":"reviewOwner"');
  });

  it('serializes label group conditions into route query patch', () => {
    const patch = buildFilterQueryPatch(
      {},
      {
        logic: 'AND',
        conditions: [
          {
            id: '1',
            fieldKey: 'assigneeName',
            operator: 'eq',
            value: '__label_group__:1',
            secondaryValue: '',
            valueType: 'LABEL_GROUP',
            labelGroupId: 1,
            labelGroupName: '核心人员',
          },
        ],
      },
    );

    expect(String(patch.filterGroup)).toContain('"valueType":"LABEL_GROUP"');
    expect(String(patch.filterGroup)).toContain('"labelGroupId":1');
    expect(String(patch.filterGroup)).toContain('"labelGroupName":"核心人员"');
  });

  it('builds reset patch for both serialized and legacy filters', () => {
    const patch = buildResetFilterQueryPatch({
      filterGroup: '{"logic":"AND","conditions":[]}',
      filterLogic: 'OR',
      'filters.0.field': 'legacy',
      'filters.0.value': 'legacy',
    });

    expect(patch.filterGroup).toBeNull();
    expect(patch.filterLogic).toBeNull();
    expect(patch['filters.0.field']).toBeNull();
    expect(patch['filters.0.value']).toBeNull();
  });

  it('merges route query patches and removes empty values', () => {
    const nextQuery = mergeRouteQuery(
      {
        detailVisible: '1',
        sortBy: 'count',
      },
      {
        detailVisible: '',
        sortBy: 'moduleName',
      },
    );

    expect(nextQuery.detailVisible).toBeUndefined();
    expect(nextQuery.sortBy).toBe('moduleName');
  });

  it('parses detail route state safely', () => {
    expect(routeDetailPage({ detailPage: '3' })).toBe(3);
    expect(routeDetailSortOrder({ detailSortOrder: 'ascending' })).toBe('ascending');
    expect(routeDetailVisible({ detailVisible: '1' })).toBe(true);
  });
});
