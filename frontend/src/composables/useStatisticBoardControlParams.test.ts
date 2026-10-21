import { describe, expect, it, vi } from 'vitest';
import { ref } from 'vue';
import { nextTick } from 'vue';
import { useStatisticBoardControlParams } from './useStatisticBoardControlParams';
import type { PageKey } from '../feature-manifest/types';
import type {
  StatisticBoardControlOptions,
  StatisticBoardResponse,
  StatisticRowData,
} from '../types/api';

const NEW_PAGE_KEY: PageKey = 'customer-issues-customer-statistics';

function row(rowKey: string, rowLabel: string): StatisticRowData {
  return { rowKey, rowLabel, cells: [] };
}

function response(
  rows: StatisticRowData[],
  dimensionColumnKey = 'moduleNames',
): StatisticBoardResponse {
  return {
    definition: {
      boardKey: 'customer-issue-customer-statistics',
      title: '客户问题统计',
      description: '',
      queryTitle: '',
      queryDescription: '',
      rowHeaderLabel: '客户名称',
      filters: [],
      columnGroups: [
        {
          key: 'row-dimension',
          label: '模块',
          columns: [
            { key: dimensionColumnKey, label: '模块', drilldown: false, metricType: 'text' },
          ],
        },
        {
          key: 'overall',
          label: '整体',
          columns: [{ key: 'defect_total', label: '缺陷数', drilldown: true, metricType: 'count' }],
        },
      ],
      detailColumns: [],
    },
    appliedFilters: {},
    appliedFilterGroup: { logic: 'AND', conditions: [] },
    rows,
    meta: {
      generatedAt: '',
      queryDurationMs: 0,
      rowCount: rows.length,
      columnCount: 2,
      drilldownColumnCount: 1,
    },
  } as unknown as StatisticBoardResponse;
}

function controlOptions(
  overrides: Partial<StatisticBoardControlOptions> = {},
): StatisticBoardControlOptions {
  return {
    scopeKey: 'CC2026R4第一轮系统测试',
    sourceVersion: 'fact-projection:v1:base',
    scopeReadable: true,
    reason: '',
    groups: [
      {
        key: 'customer',
        options: [
          { kind: 'VALUE', value: '客户丙', label: '客户丙' },
          { kind: 'VALUE', value: '客户乙', label: '客户乙' },
          { kind: 'VALUE', value: '客户甲', label: '客户甲' },
        ],
      },
      {
        key: 'module',
        options: [
          { kind: 'MISSING', value: '', label: '未标注模块' },
          { kind: 'VALUE', value: '模块A', label: '模块A' },
        ],
      },
      {
        key: 'function',
        options: [{ kind: 'VALUE', value: '功能A', label: '功能A' }],
      },
    ],
    ...overrides,
  };
}

function setup(options: {
  pageKey?: PageKey | undefined;
  query?: Record<string, string>;
  candidates?: StatisticBoardControlOptions | null;
  applyPatchToRoute?: boolean;
  boardSourceVersion?: string;
  loadControlOptions?: (request: { filters?: Record<string, string> }) => Promise<StatisticBoardControlOptions>;
} = {}) {
  const routeQuery = ref<Record<string, string>>(options.query ?? {});
  const scopeSignature = ref('scope-1');
  const replaceRouteQuery = vi.fn(
    async (patch: Record<string, string | number | null | undefined>) => {
      if (!options.applyPatchToRoute) {
        return;
      }
      const next = { ...routeQuery.value };
      for (const [key, value] of Object.entries(patch)) {
        if (value == null || value === '') {
          delete next[key];
        } else {
          next[key] = String(value);
        }
      }
      routeQuery.value = next;
    },
  );
  const frame = ref<{ board: StatisticBoardResponse; options: StatisticBoardControlOptions; query: Record<string, string> } | null>({
    board: response([]), options: options.candidates ?? controlOptions(), query: { ...routeQuery.value },
  });
  const loadControlOptions = vi.fn();
  const state = useStatisticBoardControlParams({
    pageKey: () => options.pageKey ?? NEW_PAGE_KEY,
    routeQuery: () => routeQuery.value, replaceRouteQuery,
    acceptedFrame: () => frame.value,
  });
  return { state, routeQuery, replaceRouteQuery, loadControlOptions, scopeSignature, frame };
}

function optionsOf(state: ReturnType<typeof useStatisticBoardControlParams>, key: string) {
  return state.memberControls.value.find((control) => control.key === key)?.options ?? [];
}

describe('useStatisticBoardControlParams', () => {
  it('stays inert for pages without a control spec', async () => {
    const { state, loadControlOptions } = setup({ pageKey: 'customer-issues-home' });

    await nextTick();

    expect(state.controlSpec.value).toBeNull();
    expect(state.memberControls.value).toEqual([]);
    expect(state.requestParams.value).toEqual({});
    expect(state.fixedColumnKeys.value).toEqual([]);
    expect(loadControlOptions).not.toHaveBeenCalled();
  });

  it('declares the resolved row dimension in every request', () => {
    const { state } = setup();

    expect(state.activeDimension.value).toBe('CUSTOMER');
    expect(state.requestParams.value).toEqual({ groupBy: 'CUSTOMER' });

    const explicit = setup({ query: { groupBy: 'CUSTOMER_MODULE' } });
    expect(explicit.state.requestParams.value).toEqual({ groupBy: 'CUSTOMER_MODULE' });
  });

  it('keeps all three member controls regardless of the row dimension', () => {
    const { state } = setup({ query: { groupBy: 'CUSTOMER_MODULE' } });

    expect(state.memberControls.value.map((control) => control.key)).toEqual([
      'customer',
      'module',
      'function',
    ]);
  });

  it('takes candidates from control-options and never shrinks them with the current selection', async () => {
    const { state } = setup({ query: { customer: '客户甲', customerKind: 'VALUE' } });
    await nextTick();

    expect(optionsOf(state, 'customer').map((option) => option.value)).toEqual(
      expect.arrayContaining(['VALUE:客户甲', 'VALUE:客户乙', 'VALUE:客户丙']),
    );
    expect(state.memberControls.value.find((control) => control.key === 'customer')?.value).toBe(
      'VALUE:客户甲',
    );
  });

  it('declares the member type together with the value in the board request', () => {
    const explicitValue = setup({ query: { customer: '客户甲', customerKind: 'VALUE' } });
    expect(explicitValue.state.requestParams.value).toEqual({
      groupBy: 'CUSTOMER',
      customer: '客户甲',
      customerKind: 'VALUE',
    });

    const missing = setup({ query: { customerKind: 'MISSING' } });
    expect(missing.state.requestParams.value).toEqual({
      groupBy: 'CUSTOMER',
      customerKind: 'MISSING',
    });

    const plainValue = setup({ query: { module: '模块A' } });
    expect(plainValue.state.requestParams.value).toEqual({ groupBy: 'CUSTOMER', module: '模块A' });
    expect(plainValue.state.memberSelectionError.value).toContain('moduleKind/module');
  });

  it('rejects a missing-member type that is paired with a concrete value', () => {
    const { state } = setup({ query: { customer: '客户甲', customerKind: 'MISSING' } });

    expect(state.memberSelectionError.value).toContain('customerKind/customer');
    expect(state.requestParams.value).toEqual({
      groupBy: 'CUSTOMER',
      customer: '客户甲',
      customerKind: 'MISSING',
    });
  });

  it('labels the missing member with the page wording and keeps it a type, not a value', async () => {
    const { state } = setup({ query: { moduleKind: 'MISSING' } });
    await nextTick();

    expect(optionsOf(state, 'module')).toContainEqual({
      value: 'MISSING',
      label: '未标注模块（成员缺失）',
    });
    expect(state.memberControls.value.find((control) => control.key === 'module')?.value).toBe(
      'MISSING',
    );
  });

  it('keeps a real member named like the missing wording distinct from the missing member', async () => {
    const { state } = setup({
      query: { customerKind: 'MISSING' },
      candidates: controlOptions({
        groups: controlOptions().groups.map((group) =>
          group.key === 'customer'
            ? {
                key: group.key,
                options: [
                  { kind: 'MISSING', value: '', label: '未标注客户' },
                  { kind: 'VALUE', value: '__missing__', label: '__missing__' },
                  { kind: 'VALUE', value: '未标注客户', label: '未标注客户' },
                ],
              }
            : group,
        ),
      }),
    });
    await nextTick();

    const tokens = optionsOf(state, 'customer').map((option) => option.value);
    expect(tokens).toHaveLength(3);
    expect(tokens).toEqual(
      expect.arrayContaining(['MISSING', 'VALUE:__missing__', 'VALUE:未标注客户']),
    );
    expect(optionsOf(state, 'customer').map((option) => option.label)).toEqual(
      expect.arrayContaining(['未标注客户（成员缺失）', '未标注客户']),
    );
    expect(state.memberControls.value.find((control) => control.key === 'customer')?.value).toBe(
      'MISSING',
    );
  });

  it('distinguishes missing members with missing labels while keeping real member values clean across all dimensions', async () => {
    const memberLabels = { customer: '客户', module: '模块', function: '功能' } as const;
    const defaultGroups = controlOptions().groups;
    const optionsByKey = {
      customer: [
        { kind: 'MISSING' as const, value: '', label: '未标注客户' },
        { kind: 'VALUE' as const, value: 'missing', label: 'missing' },
      ],
      module: [
        { kind: 'MISSING' as const, value: '', label: '未标注模块' },
        { kind: 'VALUE' as const, value: 'missing', label: 'missing' },
      ],
      function: [
        { kind: 'MISSING' as const, value: '', label: '未标注功能' },
        { kind: 'VALUE' as const, value: 'missing', label: 'missing' },
      ],
    };
    const { state } = setup({
      candidates: controlOptions({
        groups: defaultGroups.map((group) => ({
          ...group,
          options: optionsByKey[group.key as keyof typeof optionsByKey],
        })),
      }),
    });
    await nextTick();

    for (const key of ['customer', 'module', 'function'] as const) {
      const options = optionsOf(state, key);
      expect(options).toEqual(
        expect.arrayContaining([
          { value: 'MISSING', label: `未标注${memberLabels[key]}（成员缺失）` },
          { value: 'VALUE:missing', label: 'missing' },
        ]),
      );
    }
  });

  it('writes the member type next to the value so a real name is never read as missing', async () => {
    const { state, replaceRouteQuery } = setup({});

    await state.setMember('customer', 'VALUE:__missing__');
    expect(replaceRouteQuery).toHaveBeenLastCalledWith(
      expect.objectContaining({ customer: '__missing__', customerKind: 'VALUE', tablePage: '1' }),
    );

    await state.setMember('customer', 'MISSING');
    const missingPatch = replaceRouteQuery.mock.calls.at(-1)?.[0] as Record<string, unknown>;
    expect(missingPatch.customer).toBe('');
    expect(missingPatch.customerKind).toBe('MISSING');

    await state.setMember('customer', '');
    const clearedPatch = replaceRouteQuery.mock.calls.at(-1)?.[0] as Record<string, unknown>;
    expect(clearedPatch.customer).toBe('');
    expect(clearedPatch.customerKind).toBe('');
  });

  it('keeps the current selection visible when candidates are unavailable', async () => {
    const { state } = setup({
      query: { customerKind: 'MISSING' },
      candidates: controlOptions({ scopeReadable: false, reason: '来源尚未结算', groups: [] }),
    });
    await nextTick();

    expect(optionsOf(state, 'customer')).toEqual([
      { value: 'MISSING', label: '未标注客户（成员缺失）' },
    ]);
    expect(state.controlOptionsStatus.value).toBe('unavailable');
    expect(state.controlOptionsMessage.value).toBe('来源尚未结算');
  });

  it('keeps candidates when the pending scope changes', async () => {
    const { scopeSignature, loadControlOptions } = setup();
    await nextTick();
    expect(loadControlOptions).not.toHaveBeenCalled();

    scopeSignature.value = 'scope-2';
    await nextTick();

    expect(loadControlOptions).not.toHaveBeenCalled();
  });

  it('sends the selected member and clears stale detail keys', async () => {
    const { state, replaceRouteQuery } = setup({ query: { groupBy: 'CUSTOMER_MODULE' } });

    await state.setMember('module', 'VALUE:模块A');

    expect(replaceRouteQuery).toHaveBeenCalledWith({
      module: '模块A',
      moduleKind: 'VALUE',
      tablePage: '1',
      detailVisible: '',
      detailRowKey: '',
      detailColumnKey: '',
      detailPage: '',
      detailPageSize: '',
      detailSortBy: '',
      detailSortOrder: '',
      detailCollection: '',
    });
  });

  it('omits the default dimension from the URL but keeps it in the request', async () => {
    const { state, replaceRouteQuery } = setup({
      query: { groupBy: 'CUSTOMER_MODULE' },
      applyPatchToRoute: true,
    });

    await state.setDimension('CUSTOMER');

    expect(replaceRouteQuery).toHaveBeenCalledWith(expect.objectContaining({ groupBy: '' }));
    expect(state.requestParams.value).toEqual({ groupBy: 'CUSTOMER' });
  });

  it('pins the row dimension column by its definition group', () => {
    const { state, frame } = setup({ query: { groupBy: 'CUSTOMER_MODULE' } });
    frame.value!.board = response([row('{}', '客户甲')], 'moduleNames');

    expect(state.fixedColumnKeys.value).toEqual(['moduleNames']);
  });
  it('keeps accepted candidates and selected scope while another range is being prepared', () => {
    const { state, routeQuery, frame } = setup({ query: { customerKind: 'VALUE', customer: '客户甲' } });
    routeQuery.value = { customerKind: 'VALUE', customer: '客户乙', groupBy: 'CUSTOMER_MODULE' };
    expect(state.memberControls.value.find(item => item.key === 'customer')?.value).toBe('VALUE:客户甲');
    expect(state.activeDimension.value).toBe('CUSTOMER');
    expect(state.requestParams.value).toEqual({ groupBy: 'CUSTOMER_MODULE', customerKind: 'VALUE', customer: '客户乙' });
    frame.value = { board: response([]), options: controlOptions(), query: { ...routeQuery.value } };
    expect(state.activeDimension.value).toBe('CUSTOMER_MODULE');
    expect(state.memberControls.value.find(item => item.key === 'customer')?.value).toBe('VALUE:客户乙');
  });

  it('clears candidates when the private accepted frame is revoked', () => {
    const { state, frame } = setup();
    expect(optionsOf(state, 'customer')).toHaveLength(3);
    frame.value = null;
    expect(optionsOf(state, 'customer')).toEqual([]);
    expect(state.controlOptionsStatus.value).toBe('loading');
  });
});

describe('useStatisticBoardControlParams member quick filters', () => {
  it('exposes every member control as a shared quick-filter entry backed by the same tokens', async () => {
    const { state } = setup({ query: { customer: '客户甲', customerKind: 'VALUE' } });
    await nextTick();

    expect(state.memberQuickFilters.value.map((field) => field.key)).toEqual(['customer', 'module', 'function']);
    const customer = state.memberQuickFilters.value[0];
    expect(customer.label).toBe('客户');
    expect(customer.type).toBe('select');
    expect(customer.placeholder).toBe('全部客户');
    expect(customer.clearable).toBe(true);
    expect(customer.options?.map((option) => option.value)).toEqual(
      expect.arrayContaining(['VALUE:客户甲', 'VALUE:客户乙', 'VALUE:客户丙']),
    );
    expect(state.memberQuickValues.value).toEqual({ customer: 'VALUE:客户甲', module: '', function: '' });
  });

  it('keeps the member entries and the selected value when the accepted frame is gone', async () => {
    const { state, frame } = setup({ query: { customer: '客户甲', customerKind: 'VALUE' } });
    await nextTick();

    expect(state.memberCandidatesEmpty.value).toBe(false);

    frame.value = null;
    expect(state.memberQuickFilters.value.map((field) => field.key)).toEqual(['customer', 'module', 'function']);
    expect(state.memberQuickValues.value).toEqual({ customer: 'VALUE:客户甲', module: '', function: '' });
    expect(state.controlOptionsStatus.value).toBe('loading');
    expect(state.memberCandidatesEmpty.value).toBe(false);
  });

  it('reports a genuinely empty candidate set only when candidates are ready', async () => {
    const emptyGroups = controlOptions({
      groups: [
        { key: 'customer', options: [] },
        { key: 'module', options: [] },
        { key: 'function', options: [] },
      ],
    });
    const ready = setup({ candidates: emptyGroups });
    await nextTick();
    expect(ready.state.controlOptionsStatus.value).toBe('ready');
    expect(ready.state.memberCandidatesEmpty.value).toBe(true);

    const unavailable = setup({ candidates: controlOptions({ scopeReadable: false, reason: '来源尚未结算', groups: [] }) });
    await nextTick();
    expect(unavailable.state.memberCandidatesEmpty.value).toBe(false);
  });

  it('summarizes only the members that are actually in effect', async () => {
    const { state } = setup({
      query: { customer: '客户甲', customerKind: 'VALUE', moduleKind: 'MISSING' },
    });
    await nextTick();

    expect(state.memberSummaryChips.value).toEqual([
      { id: 'member:customer', label: '客户：客户甲' },
      { id: 'member:module', label: '模块：未标注模块' },
    ]);
  });

  it('omits the summary chip for members that are not restricted', async () => {
    const { state } = setup({ query: { customer: '客户甲', customerKind: 'VALUE' } });
    await nextTick();

    expect(state.memberSummaryChips.value.map((chip) => chip.id)).toEqual(['member:customer']);
  });

  it('clears every member control parameter in one patch', () => {
    const { state } = setup();

    expect(state.clearMemberPatch.value).toEqual({
      customer: '',
      customerKind: '',
      module: '',
      moduleKind: '',
      function: '',
      functionKind: '',
    });
  });

  it('clears nothing for pages without a control spec', () => {
    const { state } = setup({ pageKey: 'customer-issues-home' });

    expect(state.memberQuickFilters.value).toEqual([]);
    expect(state.memberQuickValues.value).toEqual({});
    expect(state.memberSummaryChips.value).toEqual([]);
    expect(state.clearMemberPatch.value).toEqual({});
  });
});
