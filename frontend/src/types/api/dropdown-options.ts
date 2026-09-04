import type { StatisticFilterGroup } from './statistics';

/**
 * 下拉框选项设置的单条黑白名单规则。
 *
 * listType 为 BLACKLIST（命中剔除）或 WHITELIST（命中保留）；
 * filterGroup 复用统计条件筛选线格式，条件字段统一为 optionValue（选项值）；
 * 规则在列表中的顺序即优先级，越靠上越先判定。
 */
export interface DropdownOptionRule {
  listType: 'BLACKLIST' | 'WHITELIST';
  name?: string | null;
  filterGroup: StatisticFilterGroup;
}

/** 一个下拉框配置内的双套规则：自动获取值与手动添加值各自独立判定后取并集。 */
export interface DropdownOptionRulesPayload {
  acquiredRules: DropdownOptionRule[];
  manualRules: DropdownOptionRule[];
}

/** 下拉框选项设置页面的字段列表项。 */
export interface DropdownOptionFieldSummary {
  fieldKey: string;
  displayName: string;
  configured: boolean;
  configId: number | null;
  configLabel: string | null;
}

/** 单个下拉框字段的完整配置视图。 */
export interface DropdownOptionFieldConfig {
  fieldKey: string;
  displayName: string;
  configId: number | null;
  configLabel: string | null;
  rules: DropdownOptionRulesPayload;
  manualOptions: string[];
  version: number;
  /** 正在使用该配置的全部字段位置路径（含当前字段）。 */
  consumerFields: string[];
}

/** 字段绑定/拆分目标。 */
export type DropdownOptionBindingTarget = 'NEW' | 'COPY' | 'CONFIG';

export interface DropdownOptionBindingRequest {
  target: DropdownOptionBindingTarget;
  configId?: number | null;
}

export interface DropdownOptionConfigSaveRequest {
  rules: DropdownOptionRulesPayload;
  manualOptions: string[];
  version: number;
}

export interface DropdownOptionPreviewRequest {
  rules: DropdownOptionRulesPayload;
  manualOptions: string[];
}

/** 预览结果：自动值过自动规则、手动值过手动规则后的并集（去重、自动值在前）。 */
export interface DropdownOptionPreviewResponse {
  finalOptions: string[];
}
