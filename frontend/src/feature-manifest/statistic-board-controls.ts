import type { PageKey } from './types';

/**
 * 统计板行维度与精确成员选择的页面级配置。
 *
 * <p>这些参数是控制参数而不是业务筛选条件：它们决定读哪些行、哪些成员，必须与业务 filterGroup 分离
 * 传递，且不进入筛选字段白名单。配置按 pageKey 声明，组件侧只按配置渲染，不判断 boardKey。
 */
export interface StatisticBoardMemberControlSpec {
  /** 控制参数名，与后端 ControlParams 字段及候选组 key 同名。 */
  key: 'customer' | 'module' | 'function';
  label: string;
  /** 缺失成员的展示文案，与后端缺失标签一致；候选不可用时用于回显当前缺失选择。 */
  missingLabel: string;
}

export interface StatisticBoardControlSpec {
  /** 行维度控制参数名，与后端 ControlParams.groupBy 同名。 */
  dimensionParam: string;
  dimensionLabel: string;
  dimensionOptions: Array<{ label: string; value: string }>;
  defaultDimension: string;
  /** 行维度所在列组键，用于定位固定维度列。 */
  dimensionGroupKey: string;
  members: StatisticBoardMemberControlSpec[];
}

/** 成员类型参数名：与成员取值分成两个参数，缺失与真实同名成员因此不会互相顶替。 */
export function memberKindParam(key: StatisticBoardMemberControlSpec['key']): string {
  return `${key}Kind`;
}

const CUSTOMER_ISSUE_CUSTOMER_STATISTICS_CONTROL: StatisticBoardControlSpec = {
  dimensionParam: 'groupBy',
  dimensionLabel: '行维度',
  dimensionOptions: [
    { label: '按客户', value: 'CUSTOMER' },
    { label: '按客户×模块', value: 'CUSTOMER_MODULE' },
    { label: '按客户×功能', value: 'CUSTOMER_FUNCTION' },
  ],
  defaultDimension: 'CUSTOMER',
  dimensionGroupKey: 'row-dimension',
  members: [
    { key: 'customer', label: '客户', missingLabel: '未标注客户' },
    { key: 'module', label: '模块', missingLabel: '未标注模块' },
    { key: 'function', label: '功能', missingLabel: '未标注功能' },
  ],
};

const controlSpecByPageKey: Partial<Record<PageKey, StatisticBoardControlSpec>> = {
  'customer-issues-customer-statistics': CUSTOMER_ISSUE_CUSTOMER_STATISTICS_CONTROL,
};

/** 当前所有控制参数名：路由白名单与请求参数都以此为准。 */
export const STATISTIC_BOARD_CONTROL_QUERY_KEYS = Array.from(
  new Set(
    Object.values(controlSpecByPageKey).flatMap((spec) =>
      spec
        ? [
            spec.dimensionParam,
            ...spec.members.flatMap((member) => [member.key, memberKindParam(member.key)]),
          ]
        : [],
    ),
  ),
);

export function getStatisticBoardControlSpec(pageKey?: PageKey | null): StatisticBoardControlSpec | null {
  if (!pageKey) {
    return null;
  }
  return controlSpecByPageKey[pageKey] ?? null;
}
