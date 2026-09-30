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
  const loadControlOptions = vi.fn(
    options.loadControlOptions
      ?? (async () => options.candidates ?? controlOptions()),
  );
  const state = useStatisticBoardControlParams({
    pageKey: () => options.pageKey ?? NEW_PAGE_KEY,
    routeQuery: () => routeQuery.value,
    replaceRouteQuery,
    loadControlOptions,
    scopeSignature: () => scopeSignature.value,
    controlScopeParams: () => ({ filters: {}, filterGroup: null }),
  });
  if (options.boardSourceVersion !== '') {
    state.applyBoardSourceVersion(options.boardSourceVersion ?? 'fact-projection:v1:base');
  }
  return { state, routeQuery, replaceRouteQuery, loadControlOptions, scopeSignature };
}

function optionsOf(state: ReturnType<typeof useStatisticBoardControlParams>, key: string) {
  return state.memberControls.value.find((control) => control.key === key)?.options ?? [];
}

/** 冲刷足够多轮微任务：候选被丢弃后要重取一次，需要比单轮 nextTick 更深的推进。 */
async function flushMicrotasks(rounds = 8) {
  for (let index = 0; index < rounds; index += 1) {
    await Promise.resolve();
  }
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
      expect.arrayContaining(['未标注客户（成员缺失）', '真实客户：未标注客户']),
    );
    expect(state.memberControls.value.find((control) => control.key === 'customer')?.value).toBe(
      'MISSING',
    );
  });

  it('shows the member type for same-name values and missing members across all dimensions', async () => {
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
          { value: 'VALUE:missing', label: `真实${memberLabels[key]}：missing` },
        ]),
      );
    }
  });

  it('withdraws old range candidates immediately while keeping the selected value visible', async () => {
    type CandidateResolver = (value: StatisticBoardControlOptions) => void;
    let resolveSecond: CandidateResolver | null = null;
    let callCount = 0;
    const { state, scopeSignature } = setup({
      query: { customer: '客户甲', customerKind: 'VALUE' },
      loadControlOptions: () => {
        callCount += 1;
        if (callCount === 1) {
          return Promise.resolve(controlOptions());
        }
        return new Promise<StatisticBoardControlOptions>((resolve) => {
          resolveSecond = resolve;
        });
      },
    });
    await flushMicrotasks();
    expect(optionsOf(state, 'customer').map((option) => option.value)).toEqual(
      expect.arrayContaining(['VALUE:客户甲', 'VALUE:客户乙', 'VALUE:客户丙']),
    );

    scopeSignature.value = 'scope-2';
    await nextTick();

    expect(state.controlOptionsStatus.value).toBe('loading');
    expect(state.memberControls.value.find((control) => control.key === 'customer')?.value)
      .toBe('VALUE:客户甲');
    expect(optionsOf(state, 'customer')).toEqual([
      { value: 'VALUE:客户甲', label: '真实客户：客户甲' },
    ]);

    (resolveSecond as CandidateResolver | null)?.(controlOptions({ scopeKey: 'scope-2' }));
    await flushMicrotasks();
  });

  it('holds candidates until the board generation arrives when candidates return first', async () => {
    type CandidateResolver = (value: StatisticBoardControlOptions) => void;
    let resolveFirst: CandidateResolver | null = null;
    let resolveSecond: CandidateResolver | null = null;
    let callCount = 0;
    const fresh = controlOptions({
      sourceVersion: 'fact-projection:v1:new',
      groups: controlOptions().groups.map((group) =>
        group.key === 'customer'
          ? { key: group.key, options: [{ kind: 'VALUE', value: '客户丁', label: '客户丁' }] }
          : group,
      ),
    });
    const { state, loadControlOptions } = setup({
      boardSourceVersion: '',
      loadControlOptions: () => {
        callCount += 1;
        return new Promise<StatisticBoardControlOptions>((resolve) => {
          if (callCount === 1) {
            resolveFirst = resolve;
          } else {
            resolveSecond = resolve;
          }
        });
      },
    });
    await nextTick();

    (resolveFirst as CandidateResolver | null)?.(
      controlOptions({ sourceVersion: 'fact-projection:v1:old' }),
    );
    await flushMicrotasks();

    expect(state.controlOptionsStatus.value).toBe('loading');
    expect(optionsOf(state, 'customer')).toEqual([]);

    state.applyBoardSourceVersion('fact-projection:v1:new');
    await flushMicrotasks();

    expect(loadControlOptions).toHaveBeenCalledTimes(2);
    expect(state.controlOptionsStatus.value).toBe('loading');
    expect(optionsOf(state, 'customer')).toEqual([]);

    (resolveSecond as CandidateResolver | null)?.(fresh);
    await flushMicrotasks();

    expect(state.controlOptionsStatus.value).toBe('ready');
    expect(optionsOf(state, 'customer')).toEqual([
      { value: 'VALUE:客户丁', label: '真实客户：客户丁' },
    ]);
  });

  it('keeps candidates unavailable when the current board has no source version', async () => {
    type CandidateResolver = (value: StatisticBoardControlOptions) => void;
    let resolveCandidate: CandidateResolver | null = null;
    const { state } = setup({
      boardSourceVersion: '',
      loadControlOptions: () => new Promise<StatisticBoardControlOptions>((resolve) => {
        resolveCandidate = resolve;
      }),
    });
    await nextTick();

    state.applyBoardSourceVersion('');
    (resolveCandidate as CandidateResolver | null)?.(controlOptions());
    await flushMicrotasks();

    expect(state.controlOptionsStatus.value).toBe('unavailable');
    expect(state.controlOptionsMessage.value).toContain('未提供来源版本');
    expect(optionsOf(state, 'customer')).toEqual([]);
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

  it('reports transport failures as unavailable instead of an empty candidate list', async () => {
    const { state } = setup({
      loadControlOptions: () => Promise.reject(new Error('502 网关中断')),
    });
    await nextTick();

    expect(state.controlOptionsStatus.value).toBe('unavailable');
    expect(state.controlOptionsMessage.value).toContain('502 网关中断');
    expect(state.memberControls.value).toHaveLength(3);
  });

  it('ignores a late candidate response that loses the scope race', async () => {
    type CandidateResolver = (value: StatisticBoardControlOptions) => void;
    let resolveFirst: CandidateResolver | null = null;
    const { scopeSignature, state } = setup({
      loadControlOptions: (request) => {
        const scope = request.filters?.probeScope;
        if (scope === 'first') {
          return new Promise<StatisticBoardControlOptions>((resolve) => {
            resolveFirst = resolve;
          });
        }
        return Promise.resolve(
          controlOptions({
            groups: controlOptions().groups.map((group) =>
              group.key === 'customer'
                ? {
                    key: group.key,
                    options: [{ kind: 'VALUE', value: '客户丁', label: '客户丁' }],
                  }
                : group,
            ),
          }),
        );
      },
    });
    // 第一次请求尚未返回就切换范围：迟到的第一次响应必须被丢弃。
    scopeSignature.value = 'scope-2';
    await nextTick();
    state.applyBoardSourceVersion('fact-projection:v1:base');
    (resolveFirst as CandidateResolver | null)?.(controlOptions());
    await nextTick();

    expect(optionsOf(state, 'customer').map((option) => option.value)).toEqual(['VALUE:客户丁']);
  });

  it('does not refetch candidates while only switching dimension or member', async () => {
    const { state, loadControlOptions } = setup();
    await nextTick();
    expect(loadControlOptions).toHaveBeenCalledTimes(1);

    await state.setDimension('CUSTOMER_MODULE');
    await state.setMember('customer', 'VALUE:客户甲');
    await nextTick();

    expect(loadControlOptions).toHaveBeenCalledTimes(1);
  });

  it('preserves valid member selections while switching the row dimension', async () => {
    const { state, replaceRouteQuery } = setup({
      query: { groupBy: 'CUSTOMER_MODULE', module: '模块A' },
    });

    await state.setDimension('CUSTOMER_FUNCTION');

    expect(replaceRouteQuery).toHaveBeenCalledWith(
      expect.objectContaining({ groupBy: 'CUSTOMER_FUNCTION', tablePage: '1' }),
    );
    const patch = replaceRouteQuery.mock.calls[0]?.[0] as Record<string, unknown>;
    expect(patch).not.toHaveProperty('customer');
    expect(patch).not.toHaveProperty('module');
    expect(patch).not.toHaveProperty('function');
    expect(patch.detailRowKey).toBe('');
  });

  it('refetches candidates when the scope signature changes', async () => {
    const { scopeSignature, loadControlOptions } = setup();
    await nextTick();
    expect(loadControlOptions).toHaveBeenCalledTimes(1);

    scopeSignature.value = 'scope-2';
    await nextTick();

    expect(loadControlOptions).toHaveBeenCalledTimes(2);
  });

  it('refetches candidates only when the board reports a new source generation', async () => {
    let generation = 'fact-projection:v1:base';
    const { state, loadControlOptions } = setup({
      loadControlOptions: async () => controlOptions({ sourceVersion: generation }),
    });
    await nextTick();
    expect(loadControlOptions).toHaveBeenCalledTimes(1);

    state.applyBoardSourceVersion('fact-projection:v1:base');
    await flushMicrotasks();
    expect(loadControlOptions).toHaveBeenCalledTimes(1);

    generation = 'fact-projection:v1:next';
    state.applyBoardSourceVersion('fact-projection:v1:next');
    await flushMicrotasks();
    expect(loadControlOptions).toHaveBeenCalledTimes(2);
    expect(state.controlOptionsStatus.value).toBe('ready');
  });

  it('discards a candidate response from an older generation that arrives after the board reported a new one', async () => {
    const stale = controlOptions({ sourceVersion: 'fact-projection:v1:old' });
    const fresh = controlOptions({
      sourceVersion: 'fact-projection:v1:new',
      groups: controlOptions().groups.map((group) =>
        group.key === 'customer'
          ? { key: group.key, options: [{ kind: 'VALUE', value: '客户戊', label: '客户戊' }] }
          : group,
      ),
    });
    type CandidateResolver = (value: StatisticBoardControlOptions) => void;
    let resolveFirst: CandidateResolver | null = null;
    let callCount = 0;
    const { state, loadControlOptions } = setup({
      boardSourceVersion: '',
      loadControlOptions: () => {
        callCount += 1;
        if (callCount === 1) {
          return new Promise<StatisticBoardControlOptions>((resolve) => {
            resolveFirst = resolve;
          });
        }
        return Promise.resolve(fresh);
      },
    });

    // 主表先返回并报告新代际，此时第一次候选请求仍在飞行。
    state.applyBoardSourceVersion('fact-projection:v1:new');
    await flushMicrotasks();
    expect(loadControlOptions).toHaveBeenCalledTimes(1);

    // 旧代际候选晚到：不得被接受，必须按当前代际重取。
    (resolveFirst as CandidateResolver | null)?.(stale);
    await flushMicrotasks();

    expect(loadControlOptions).toHaveBeenCalledTimes(2);
    expect(optionsOf(state, 'customer').map((option) => option.value)).toEqual(['VALUE:客户戊']);
    expect(state.controlOptionsStatus.value).toBe('ready');
  });

  it('reports unavailable instead of retrying forever when candidate generations never converge', async () => {
    const stale = controlOptions({ sourceVersion: 'fact-projection:v1:old' });
    const { state, loadControlOptions } = setup({ loadControlOptions: async () => stale });

    state.applyBoardSourceVersion('fact-projection:v1:new');
    await flushMicrotasks();

    expect(loadControlOptions).toHaveBeenCalledTimes(2);
    expect(state.controlOptionsStatus.value).toBe('unavailable');
    expect(state.controlOptionsMessage.value).toContain('来源代际不一致');
    expect(optionsOf(state, 'customer')).toEqual([]);
  });

  it.each([
    ['customer', 'customerKind', 'FUTURE', '客户甲'],
    ['module', 'moduleKind', 'FUTURE', '模块甲'],
    ['function', 'functionKind', 'FUTURE', '功能甲'],
    ['customer', 'customerKind', 'VALUE', undefined],
    ['module', 'moduleKind', 'MISSING', '模块甲'],
    ['function', 'functionKind', 'ALL', '功能甲'],
  ])('preserves invalid %s kind/value parameters for server rejection', (key, kindKey, kind, value) => {
    const query: Record<string, string> = { [kindKey]: kind };
    if (value !== undefined) {
      query[key] = value;
    }
    const { state } = setup({ query });

    expect(state.requestParams.value).toEqual({
      groupBy: 'CUSTOMER',
      ...(value === undefined ? {} : { [key]: value }),
      [kindKey]: kind,
    });
    expect(state.memberSelectionError.value).toContain(key);
  });

  it.each([
    ['customer', '客户甲'],
    ['module', '模块甲'],
    ['function', '功能甲'],
  ])('rejects a %s value without its explicit kind', (key, value) => {
    const { state } = setup({ query: { [key]: value } });

    expect(state.requestParams.value).toEqual({ groupBy: 'CUSTOMER', [key]: value });
    expect(state.memberSelectionError.value).toContain(key);
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
    const { state } = setup({ query: { groupBy: 'CUSTOMER_MODULE' } });
    state.applyResponse(response([row('{}', '客户甲')], 'moduleNames'));

    expect(state.fixedColumnKeys.value).toEqual(['moduleNames']);
  });
});
