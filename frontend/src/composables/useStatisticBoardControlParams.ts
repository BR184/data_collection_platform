import { computed, shallowRef, watch, type ComputedRef } from 'vue';
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
import { getErrorMessage } from '../utils/user-message';

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
  /** 候选求解入口：由后端在同一窄事实的完整基础范围上计算，请求不携带成员选择。 */
  loadControlOptions: (request: StatisticBoardControlOptionsRequest) => Promise<StatisticBoardControlOptions>;
  /** 范围签名：生效范围、来源选择与用户条件；只有它变化才需要重新求解候选。 */
  scopeSignature: () => string;
  /** 随候选请求下传的范围参数（生效范围、来源与用户条件；不含成员选择与行维度）。 */
  controlScopeParams: () => StatisticBoardControlOptionsRequest;
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
 */
export function useStatisticBoardControlParams(deps: StatisticBoardControlParamsDependencies) {
  const spec = computed<StatisticBoardControlSpec | null>(() =>
    getStatisticBoardControlSpec(deps.pageKey()),
  );
  const controlOptions = shallowRef<StatisticBoardControlOptions | null>(null);
  const candidateStatus = shallowRef<StatisticBoardControlOptionsStatus | null>(null);
  const transportFailureReason = shallowRef('');
  const candidateScopeSignature = shallowRef('');
  // 行维度列键只能由主表定义定位，与候选无关；这里缓存的是列键而不是成员集合。
  const dimensionColumnKeyFromResponse = shallowRef<string | null>(null);
  // 请求序号：快速切换范围时，迟到的候选响应不得覆盖当前状态。
  let optionsRequestSequence = 0;
  let activeOptionsRequestId = 0;
  // 主表最近一次报告的来源代际；候选必须与它同代际，无论两者谁先返回都要核对。
  const boardSourceVersion = shallowRef('');
  const pendingCandidate = shallowRef<{
    options: StatisticBoardControlOptions;
    scopeSignature: string;
  } | null>(null);
  // 已为某个代际重取过一次候选：同一代际不再重复重取，避免来源长期不一致时反复请求。
  let candidateRetriedVersion = '';

  const activeDimension = computed(() => {
    const current = spec.value;
    if (!current) {
      return '';
    }
    return String(deps.routeQuery()[current.dimensionParam] ?? '').trim() || current.defaultDimension;
  });

  /** 固定列键：维度列固定在左侧，由主表定义的维度列组定位。 */
  const fixedColumnKeys = computed<string[]>(() =>
    dimensionColumnKeyFromResponse.value ? [dimensionColumnKeyFromResponse.value] : [],
  );

  const controlOptionsStatus = computed<StatisticBoardControlOptionsStatus | null>(() => {
    if (!spec.value) {
      return null;
    }
    if (candidateScopeSignature.value !== deps.scopeSignature()) {
      return 'loading';
    }
    if (candidateStatus.value === 'unavailable') {
      return 'unavailable';
    }
    if (
      candidateStatus.value === 'ready'
      && boardSourceVersion.value
      && controlOptions.value?.sourceVersion === boardSourceVersion.value
    ) {
      return 'ready';
    }
    return 'loading';
  });

  const acceptedControlOptions = computed(() =>
    controlOptionsStatus.value === 'ready' ? controlOptions.value : null,
  );

  const memberControls = computed<StatisticBoardMemberControl[]>(() => {
    const current = spec.value;
    if (!current) {
      return [];
    }
    return current.members.map((member) => {
      const selection = routeMemberSelection(member.key);
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
  function routeMemberSelection(key: MemberKey): RouteMemberSelection {
    const query = deps.routeQuery();
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
          : disambiguatedMemberLabel(option.label || option.value, member),
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
      label: disambiguatedMemberLabel(selection.value, member),
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
    const params: Record<string, string> = { [current.dimensionParam]: activeDimension.value };
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

  /** 候选不可用时的可读原因：请求失败或后端明确报告范围不可判定。 */
  const controlOptionsMessage = computed(() => {
    if (transportFailureReason.value) {
      return transportFailureReason.value;
    }
    if (controlOptionsStatus.value === 'unavailable') {
      return candidateStatus.value === 'unavailable'
        ? controlOptions.value?.reason || '当前范围的成员候选暂不可用'
        : '当前范围尚未获得匹配的成员候选';
    }
    return '';
  });

  /**
   * 重新求解成员候选。
   *
   * <p>只在范围签名变化或手动刷新时调用；成员选择与行维度不参与候选求解，切换它们不触发请求。
   * 请求失败或来源不可判定时保留原因并维持现有选择，不得把失败渲染成"该范围没有成员"。
   */
  async function refreshControlOptions() {
    const current = spec.value;
    if (!current) {
      controlOptions.value = null;
      candidateStatus.value = null;
      return;
    }
    optionsRequestSequence += 1;
    const requestId = optionsRequestSequence;
    const requestScopeSignature = deps.scopeSignature();
    activeOptionsRequestId = requestId;
    transportFailureReason.value = '';
    controlOptions.value = null;
    pendingCandidate.value = null;
    candidateScopeSignature.value = requestScopeSignature;
    candidateStatus.value = 'loading';
    let next: StatisticBoardControlOptions;
    try {
      const scope = deps.controlScopeParams();
      next = await deps.loadControlOptions({
        ...scope,
        filters: { ...(scope.filters ?? {}), [current.dimensionParam]: current.defaultDimension },
      });
    } catch (error) {
      if (requestId !== optionsRequestSequence) {
        return;
      }
      activeOptionsRequestId = 0;
      if (requestScopeSignature !== deps.scopeSignature()) {
        return;
      }
      controlOptions.value = null;
      pendingCandidate.value = null;
      candidateScopeSignature.value = requestScopeSignature;
      candidateStatus.value = 'unavailable';
      transportFailureReason.value = getErrorMessage(error, '成员候选加载失败');
      return;
    }
    if (requestId !== optionsRequestSequence) {
      return;
    }
    activeOptionsRequestId = 0;
    if (requestScopeSignature !== deps.scopeSignature()) {
      return;
    }
    settleCandidate(next, requestScopeSignature);
  }

  /**
   * 接受候选响应前核对来源代际。
   *
   * <p>主表先返回时，仍在飞行的旧代际候选会晚到：必须丢弃并按当前代际重取一次，不得把过期候选
   * 当成当前候选（用户会按过期成员集合发起新选择）。同一代际只重取一次；来源长期不一致时明确
   * 报"不可用"，既不展示过期候选也不无限重试。
   */
  function settleCandidate(next: StatisticBoardControlOptions, scopeSignature: string) {
    candidateScopeSignature.value = scopeSignature;
    if (!next.scopeReadable) {
      controlOptions.value = next;
      pendingCandidate.value = null;
      candidateStatus.value = 'unavailable';
      return;
    }
    const reported = boardSourceVersion.value;
    if (!reported) {
      controlOptions.value = null;
      pendingCandidate.value = { options: next, scopeSignature };
      candidateStatus.value = 'loading';
      return;
    }
    resolveCandidateVersion(next, scopeSignature, reported);
  }

  function resolveCandidateVersion(
    next: StatisticBoardControlOptions,
    scopeSignature: string,
    reportedVersion: string,
  ) {
    if (scopeSignature !== deps.scopeSignature()) {
      return;
    }
    if (next.sourceVersion && next.sourceVersion === reportedVersion) {
      pendingCandidate.value = null;
      controlOptions.value = next;
      candidateScopeSignature.value = scopeSignature;
      candidateStatus.value = 'ready';
      transportFailureReason.value = '';
      candidateRetriedVersion = '';
      return;
    }
    controlOptions.value = null;
    pendingCandidate.value = null;
    candidateScopeSignature.value = scopeSignature;
    candidateStatus.value = 'loading';
    if (candidateRetriedVersion === reportedVersion) {
      candidateStatus.value = 'unavailable';
      transportFailureReason.value = '成员候选与当前来源代际不一致，请稍后刷新';
      return;
    }
    candidateRetriedVersion = reportedVersion;
    void refreshControlOptions();
  }

  /**
   * 主表来源代际变化后按同一范围重新求解候选。
   *
   * <p>候选缓存与主表必须属于同一代际，否则用户会按过期成员集合发起新的选择；版本一致时不重发请求，
   * 翻页与切换维度因此不产生额外候选查询。主表先返回而没有已接受候选时不必补发：在途响应到达时
   * 会按新代际核对并自行重取。
   */
  function applyBoardSourceVersion(sourceVersion: string) {
    const next = String(sourceVersion ?? '').trim();
    if (!next) {
      optionsRequestSequence += 1;
      activeOptionsRequestId = 0;
      boardSourceVersion.value = '';
      controlOptions.value = null;
      pendingCandidate.value = null;
      candidateScopeSignature.value = deps.scopeSignature();
      candidateStatus.value = 'unavailable';
      transportFailureReason.value = '当前主表未提供来源版本，无法验证成员候选';
      return;
    }
    if (next === boardSourceVersion.value) {
      return;
    }
    boardSourceVersion.value = next;
    const pending = pendingCandidate.value;
    if (pending) {
      resolveCandidateVersion(pending.options, pending.scopeSignature, next);
      return;
    }
    if (
      candidateStatus.value === 'ready'
      && candidateScopeSignature.value === deps.scopeSignature()
      && controlOptions.value?.sourceVersion === next
    ) {
      return;
    }
    if (candidateStatus.value === 'ready') {
      controlOptions.value = null;
      candidateStatus.value = 'loading';
    }
    if (!activeOptionsRequestId) {
      void refreshControlOptions();
    }
  }

  /** 主表响应只用于定位维度列与来源代际；成员候选一律由 `control-options` 提供。 */
  function applyResponse(response: StatisticBoardResponse | null) {
    const groupKey = spec.value?.dimensionGroupKey;
    if (!response || !groupKey) {
      dimensionColumnKeyFromResponse.value = null;
      return;
    }
    const group = response.definition.columnGroups.find((item) => item.key === groupKey);
    dimensionColumnKeyFromResponse.value = group?.columns?.[0]?.key ?? null;
  }

  watch(
    () => [spec.value !== null, deps.scopeSignature()] as const,
    ([enabled]) => {
      // 范围变化后上一代际不再是参照物，必须等新的主表响应重新报告。
      boardSourceVersion.value = '';
      candidateRetriedVersion = '';
      optionsRequestSequence += 1;
      activeOptionsRequestId = 0;
      controlOptions.value = null;
      pendingCandidate.value = null;
      candidateScopeSignature.value = '';
      transportFailureReason.value = '';
      if (!enabled) {
        candidateStatus.value = null;
        return;
      }
      candidateStatus.value = 'loading';
      void refreshControlOptions();
    },
    { immediate: true, flush: 'sync' },
  );

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
    requestParams,
    fixedColumnKeys,
    controlOptionsStatus,
    controlOptionsMessage,
    memberSelectionError,
    applyBoardSourceVersion,
    applyResponse,
    refreshControlOptions,
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

function disambiguatedMemberLabel(
  label: string,
  member: StatisticBoardMemberControlSpec,
) {
  return `真实${member.label}：${label}`;
}
