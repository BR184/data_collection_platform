import { computed, type ComputedRef } from 'vue';
import type { LocationQuery } from 'vue-router';
import type {
  StatisticBoardControlOptionGroup,
  StatisticBoardControlOptions,
  StatisticBoardMemberSelectionKind,
  StatisticBoardResponse,
  StatisticFilterGroup,
} from '../types/api';
import {
  getStatisticBoardControlSpec,
  memberKindParam,
  type StatisticBoardControlSpec,
  type StatisticBoardMemberControlSpec,
} from '../feature-manifest/statistic-board-controls';
import type { PageKey } from '../feature-manifest/types';
import type { RecordTableFilterField } from '../types/record-table';

/** 一个成员选择：类型决定语义，取值只对 VALUE 有意义。 */
export interface MemberSelection {
  kind: StatisticBoardMemberSelectionKind;
  value: string;
}

interface RouteMemberSelection extends MemberSelection {
  valid: boolean;
  error: string;
  rawKindPresent: boolean;
  rawKind: string;
  rawValuePresent: boolean;
  rawValue: string;
}

/**
 * 成员候选项：value 是不透明的选择令牌（见 {@link memberSelectionToken}），label 是展示文本。
 *
 * <p>下拉框以令牌取值，因此"缺失"与"真实名恰好等于缺失文案的成员"是两个不同选项，不会互相顶替。
 */
export interface StatisticBoardMemberOption {
  label: string;
  value: string;
}

/** 一个成员控制项：value 是当前选择令牌，空串表示不限。 */
export interface StatisticBoardMemberControl {
  key: StatisticBoardMemberControlSpec['key'];
  label: string;
  value: string;
  options: StatisticBoardMemberOption[];
}

/**
 * 把成员选择合成下拉框使用的不透明令牌。
 *
 * <p>类型与取值必须一起流转：MISSING 不带取值，VALUE 只带成员名，"不限"用空串表示。
 * 成员名里出现冒号也安全，只有第一个冒号是类型分隔符。
 */
export function memberSelectionToken(
  kind: StatisticBoardMemberSelectionKind,
  value: string,
): string {
  if (kind === 'VALUE' && value) {
    return `VALUE:${value}`;
  }
  return kind === 'MISSING' ? 'MISSING' : '';
}

/** 解析下拉框内部令牌；空令牌表示不限，格式非法的令牌也不产生成员值。 */
export function parseMemberSelectionToken(raw: string): MemberSelection {
  const token = String(raw ?? '').trim();
  if (!token) {
    return { kind: 'ALL', value: '' };
  }
  const separator = token.indexOf(':');
  const kind = (separator < 0 ? token : token.slice(0, separator)).toUpperCase();
  const value = separator < 0 ? '' : token.slice(separator + 1);
  if (kind === 'MISSING') {
    return { kind: 'MISSING', value: '' };
  }
  if (kind === 'VALUE' && value) {
    return { kind: 'VALUE', value };
  }
  return { kind: 'ALL', value: '' };
}

/** 候选求解状态：区分"确实没有成员"与"来源或范围不可判定"。 */
export type StatisticBoardControlOptionsStatus = 'loading' | 'ready' | 'unavailable';

/** 候选请求参数：与主表请求同构，但不携带成员选择。 */
export interface StatisticBoardControlOptionsRequest {
  filters?: Record<string, string>;
  filterGroup?: StatisticFilterGroup | null;
}

export interface StatisticBoardControlParamsDependencies {
  pageKey: () => PageKey | undefined;
  routeQuery: () => LocationQuery;
  replaceRouteQuery: (patch: Record<string, string | number | null | undefined>) => Promise<void>;
  /** 主表和候选已由页面准备器同时核验并接受；控制条不自行查询当前来源。 */
  acceptedFrame: () => {
    board: StatisticBoardResponse;
    options: StatisticBoardControlOptions | null;
    query: LocationQuery;
  } | null;

}

/** 明细下钻键：切换行维度或成员后旧单元格语义失效，必须一并清理。 */
const DETAIL_INVALIDATION_PATCH = {
  detailVisible: '',
  detailRowKey: '',
  detailColumnKey: '',
  detailPage: '',
  detailPageSize: '',
  detailSortBy: '',
  detailSortOrder: '',
  detailCollection: '',
};

type MemberKey = StatisticBoardMemberControlSpec['key'];

/**
 * 行维度与精确成员选择的状态与请求参数。
 *
 * <p>成员候选来自后端 `control-options`：同一份窄事实在"完整基础范围"（生效范围、来源与用户条件，
 * 不施加成员选择）上求得，因此选择客户甲之后客户乙依然可选，空结果也不会清空候选或静默重置筛选。
 * 候选不是第二套成员权威源，只是同一事实源的一次查询结果。
 *
 * <p>行维度只是展示维度，与客户/模块/功能筛选相互独立：切换维度保留仍然有效的成员选择，
 * 只清理明细下钻键并回到第一页。
 *
 * <p>成员选择是控制参数而非业务条件：它与 `filterGroup` 分属两个命名空间，因此同一维度只有一份
 * 生效状态。界面把这些成员项并入共用"常用筛选"区（见 `StatisticBoardMemberFilters`），只是归属调整，
 * 请求语义与统计口径不变。
 */
export function useStatisticBoardControlParams(deps: StatisticBoardControlParamsDependencies) {
  const spec = computed<StatisticBoardControlSpec | null>(() =>
    getStatisticBoardControlSpec(deps.pageKey()),
  );
  const controlOptions = computed(() => deps.acceptedFrame()?.options ?? null);
  const activeDimension = computed(() => {
    const current = spec.value;
    return current ? String(deps.acceptedFrame()?.query[current.dimensionParam] ?? '').trim() || current.defaultDimension : '';
  });
  const fixedColumnKeys = computed<string[]>(() => {
    const groupKey = spec.value?.dimensionGroupKey;
    const key = deps.acceptedFrame()?.board.definition.columnGroups.find(group => group.key === groupKey)?.columns?.[0]?.key;
    return key ? [key] : [];
  });
  const controlOptionsStatus = computed<StatisticBoardControlOptionsStatus | null>(() => {
    if (!spec.value) { return null; }
    if (!controlOptions.value) { return 'loading'; }
    return controlOptions.value.scopeReadable ? 'ready' : 'unavailable';
  });
  const acceptedControlOptions = computed(() => controlOptionsStatus.value === 'ready' ? controlOptions.value : null);

  /** 界面与请求必须读取同一份选择来源：已接受画面的 query，否则退回当前路由。 */
  function selectionFor(key: MemberKey): RouteMemberSelection {
    return routeMemberSelection(key, deps.acceptedFrame()?.query ?? deps.routeQuery());
  }

  const memberControls = computed<StatisticBoardMemberControl[]>(() => {
    const current = spec.value;
    if (!current) {
      return [];
    }
    return current.members.map((member) => {
      const selection = selectionFor(member.key);
      return {
        key: member.key,
        label: member.label,
        value: selection.valid ? memberSelectionToken(selection.kind, selection.value) : '',
        options: withCurrentOption(
          memberOptions(findOptionGroup(acceptedControlOptions.value?.groups, member.key), member),
          currentSelectionOption(member, selection),
        ),
      };
    });
  });

  /** 同一批成员控制项在"常用筛选"行内的字段形态；取值仍是令牌，变更走控制参数通道。 */
  const memberQuickFilters = computed<RecordTableFilterField[]>(() => {
    const current = spec.value;
    if (!current) {
      return [];
    }
    const controls = memberControls.value;
    return current.members.map((member) => ({
      key: member.key,
      label: member.label,
      type: 'select',
      placeholder: `全部${member.label}`,
      width: 150,
      clearable: true,
      options: controls.find((control) => control.key === member.key)?.options ?? [],
    }));
  });

  const memberQuickValues = computed<Record<string, unknown>>(() =>
    Object.fromEntries(memberControls.value.map((control) => [control.key, control.value])));

  /** 候选就绪但后端未返回任何成员：这是"确实没有成员"，不是加载失败。 */
  const memberCandidatesEmpty = computed(() => {
    const current = spec.value;
    if (!current || controlOptionsStatus.value !== 'ready') {
      return false;
    }
    return current.members.every((member) =>
      memberOptions(findOptionGroup(acceptedControlOptions.value?.groups, member.key), member).length === 0);
  });

  /** 已生效成员选择的摘要，与服务端文案区分开：缺失用页面缺失文案，精确成员用候选原始名。 */
  const memberSummaryChips = computed<Array<{ id: string; label: string }>>(() => {
    const current = spec.value;
    if (!current) {
      return [];
    }
    return current.members.flatMap((member) => {
      const selection = selectionFor(member.key);
      if (!selection.valid || selection.kind === 'ALL') {
        return [];
      }
      const display = selection.kind === 'MISSING'
        ? member.missingLabel
        : rawMemberLabel(member.key, selection.value) || selection.value;
      return [{ id: `member:${member.key}`, label: `${member.label}：${display}` }];
    });
  });

  /** 重置一次性清除全部成员控制参数；行维度与范围不参与筛选重置。 */
  const clearMemberPatch = computed<Record<string, string>>(() => {
    const current = spec.value;
    if (!current) {
      return {};
    }
    return Object.fromEntries(
      current.members.flatMap((member) => [[member.key, ''], [memberKindParam(member.key), '']]),
    );
  });

  /** 候选原始展示名；未在候选中时退回成员取值本身。 */
  function rawMemberLabel(key: MemberKey, value: string): string {
    const option = findOptionGroup(acceptedControlOptions.value?.groups, key)?.options
      .find((item) => item.kind !== 'MISSING' && item.value === value);
    return option?.label || '';
  }

  const memberSelectionError = computed(() => {
    const current = spec.value;
    if (!current) {
      return '';
    }
    return current.members
      .flatMap((member) => {
        const selection = routeMemberSelection(member.key);
        return selection.valid
          ? []
          : [`${member.label}成员筛选参数无效：${memberKindParam(member.key)}/${member.key}（${selection.error}）`];
      })
      .join('；');
  });

  /** 路由成员选择必须使用完整类型契约；不把非法组合降级为“不限”。 */
  function routeMemberSelection(key: MemberKey, query = deps.routeQuery()): RouteMemberSelection {
    const kindParam = memberKindParam(key);
    const rawKindPresent = Object.prototype.hasOwnProperty.call(query, kindParam);
    const rawValuePresent = Object.prototype.hasOwnProperty.call(query, key);
    const rawKind = queryValueText(query[kindParam]);
    const rawValue = queryValueText(query[key]);
    const invalid = (error: string): RouteMemberSelection => ({
      kind: 'ALL',
      value: '',
      valid: false,
      error,
      rawKindPresent,
      rawKind,
      rawValuePresent,
      rawValue,
    });
    if (!rawKindPresent && !rawValuePresent) {
      return {
        kind: 'ALL', value: '', valid: true, error: '',
        rawKindPresent, rawKind, rawValuePresent, rawValue,
      };
    }
    if (!rawKindPresent || Array.isArray(query[kindParam]) || Array.isArray(query[key])) {
      return invalid('类型与成员值必须按单一参数成对提供');
    }
    const kind = rawKind.trim().toUpperCase();
    if (!kind) {
      return invalid('成员类型不能为空');
    }
    if (kind === 'ALL' || kind === 'MISSING') {
      if (rawValuePresent) {
        return invalid(`${kind} 类型不能携带成员值`);
      }
      return {
        kind: kind as 'ALL' | 'MISSING',
        value: '',
        valid: true,
        error: '',
        rawKindPresent,
        rawKind,
        rawValuePresent,
        rawValue,
      };
    }
    if (kind === 'VALUE') {
      if (!rawValuePresent || !rawValue.trim()) {
        return invalid('VALUE 类型必须携带非空成员值');
      }
      return {
        kind: 'VALUE',
        value: rawValue.trim(),
        valid: true,
        error: '',
        rawKindPresent,
        rawKind,
        rawValuePresent,
        rawValue,
      };
    }
    return invalid(`未知成员类型 ${rawKind}`);
  }

  /**
   * 候选可选项。
   *
   * <p>缺失与同名真实成员使用类型提示；真实成员名原样保留。
   */
  function memberOptions(
    group: StatisticBoardControlOptionGroup | undefined,
    member: StatisticBoardMemberControlSpec,
  ): StatisticBoardMemberOption[] {
    if (!group) {
      return [];
    }
    return group.options
      .map((option) => ({
        value: memberSelectionToken(option.kind === 'MISSING' ? 'MISSING' : 'VALUE', option.value),
        label: option.kind === 'MISSING'
          ? `${member.missingLabel}（成员缺失）`
          : (option.label || option.value),
      }))
      .sort((left, right) => left.label.localeCompare(right.label, 'zh-Hans-CN'));
  }

  /** 候选尚未到位或范围不可判定时，当前选择仍需可见，不得显示成空白或未选。 */
  function currentSelectionOption(
    member: StatisticBoardMemberControlSpec,
    selection: RouteMemberSelection,
  ): StatisticBoardMemberOption | null {
    if (!selection.valid || selection.kind === 'ALL') {
      return null;
    }
    if (selection.kind === 'MISSING') {
      return {
        value: memberSelectionToken('MISSING', ''),
        label: `${member.missingLabel}（成员缺失）`,
      };
    }
    return {
      value: memberSelectionToken('VALUE', selection.value),
      label: rawMemberLabel(member.key, selection.value) || selection.value,
    };
  }

  function withCurrentOption(
    options: StatisticBoardMemberOption[],
    current: StatisticBoardMemberOption | null,
  ): StatisticBoardMemberOption[] {
    if (!current || options.some((option) => option.value === current.value)) {
      return options;
    }
    return [current, ...options];
  }

  /** 请求参数：行维度始终显式声明，成员的类型与取值同时下发，不限则不声明。 */
  const requestParams = computed<Record<string, string>>(() => {
    const current = spec.value;
    if (!current) {
      return {};
    }
    const params: Record<string, string> = { [current.dimensionParam]: String(deps.routeQuery()[current.dimensionParam] ?? '').trim() || current.defaultDimension };
    for (const member of current.members) {
      const selection = routeMemberSelection(member.key);
      if (!selection.valid) {
        if (selection.rawValuePresent) {
          params[member.key] = selection.rawValue;
        }
        if (selection.rawKindPresent) {
          params[memberKindParam(member.key)] = selection.rawKind;
        }
        continue;
      }
      if (selection.kind === 'ALL') {
        continue;
      }
      if (selection.kind === 'VALUE') {
        params[member.key] = selection.value;
      }
      params[memberKindParam(member.key)] = selection.kind;
    }
    return params;
  });

  /** 无完整候选时披露原因，不能把失败表示为无成员。 */
  const controlOptionsMessage = computed(() => controlOptionsStatus.value === 'unavailable'
    ? controlOptions.value?.reason || '当前范围的成员候选暂不可用' : '');

  async function setDimension(value: string) {
    const current = spec.value;
    if (!current || value === activeDimension.value) {
      return;
    }
    // 行维度只是展示方式：成员筛选与维度独立，切换维度必须保留仍然有效的选择。
    await deps.replaceRouteQuery({
      [current.dimensionParam]: value === current.defaultDimension ? '' : value,
      tablePage: '1',
      ...DETAIL_INVALIDATION_PATCH,
    });
  }

  /**
   * 写入一个成员选择。
   *
   * <p>类型与取值同时写入路由：缺失只写类型，精确成员同时写成员名，不限则两个参数都清空。
   * 成员名因此可以是任意文本，包括与缺失文案同形的名称。
   *
   * @param key 成员控制项键
   * @param token 下拉框选择令牌，空串表示不限
   */
  async function setMember(key: MemberKey, token: string) {
    const current = spec.value;
    if (!current || !current.members.some((member) => member.key === key)) {
      return;
    }
    const selection = parseMemberSelectionToken(token);
    await deps.replaceRouteQuery({
      [key]: selection.kind === 'VALUE' ? selection.value : '',
      [memberKindParam(key)]: selection.kind === 'ALL' ? '' : selection.kind,
      tablePage: '1',
      ...DETAIL_INVALIDATION_PATCH,
    });
  }

  return {
    controlSpec: spec as ComputedRef<StatisticBoardControlSpec | null>,
    activeDimension,
    memberControls,
    memberQuickFilters,
    memberQuickValues,
    memberCandidatesEmpty,
    memberSummaryChips,
    clearMemberPatch,
    requestParams,
    fixedColumnKeys,
    controlOptionsStatus,
    controlOptionsMessage,
    memberSelectionError,
    setDimension,
    setMember,
  };
}

function findOptionGroup(
  groups: StatisticBoardControlOptionGroup[] | undefined,
  key: MemberKey,
): StatisticBoardControlOptionGroup | undefined {
  return groups?.find((group) => group.key === key);
}

function queryValueText(raw: LocationQuery[string] | undefined) {
  return Array.isArray(raw)
    ? raw.map((value) => String(value ?? '')).join(',')
    : String(raw ?? '');
}
