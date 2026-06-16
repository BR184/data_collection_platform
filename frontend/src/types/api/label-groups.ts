export type LabelValueKind = 'STRING_LITERAL' | 'GITLAB_USER_ID' | 'ENUM_KEY' | 'BRANCH_NAME';

export interface LabelDimension {
  key: string;
  name: string;
  description?: string;
  valueKind: LabelValueKind;
  staticSupported: boolean;
  dynamicSupported: boolean;
}

export interface LabelValue {
  value: string;
  label: string;
  valueKind: LabelValueKind;
  source: string;
  hitCount: number;
}

export interface LabelValuePage {
  items: LabelValue[];
  total: number;
  page: number;
  size: number;
}

export interface LabelGroupCompatiblePage {
  pageKey: string;
  pageName: string;
  fieldKey: string;
  fieldName: string;
  mvpEnabled: boolean;
}

export interface LabelGroupMember {
  id?: number | null;
  value: string;
  label: string;
  currentAvailable?: boolean;
  sortOrder?: number;
}

export interface LabelGroupChild {
  id: number;
  name: string;
  groupType: 'STATIC' | 'DYNAMIC' | 'COMPOSITE';
  valueType?: string | null;
  enabled: boolean;
}

export interface LabelGroupRuleFieldRef {
  sourceKey: string;
  fieldKey: string;
}

export interface LabelGroupRuleCondition {
  sourceKey?: string | null;
  fieldKey?: string | null;
  aggregateKey?: string | null;
  operator: string;
  value?: string | null;
  secondValue?: string | null;
  values?: string[];
}

export interface LabelGroupRuleConditionGroup {
  logic: 'AND' | 'OR';
  conditions?: LabelGroupRuleCondition[];
  groups?: LabelGroupRuleConditionGroup[];
}

export interface LabelGroupRuleRelation {
  leftSourceKey: string;
  leftFieldKey: string;
  rightSourceKey: string;
  rightFieldKey: string;
  matchOperator: string;
  normalizer?: string | null;
}

export interface LabelGroupRuleAggregation {
  key: string;
  sourceKey: string;
  fieldKey: string;
  function: string;
  label?: string | null;
}

export interface LabelGroupRuleSort {
  sourceKey?: string | null;
  fieldKey?: string | null;
  aggregateKey?: string | null;
  direction: 'asc' | 'desc';
}

export interface LabelGroupRuleConfig {
  outputSourceKey: string;
  outputFieldKey: string;
  distinct?: boolean;
  filterGroup?: LabelGroupRuleConditionGroup | null;
  filters?: LabelGroupRuleCondition[];
  relations?: LabelGroupRuleRelation[];
  groupBy?: LabelGroupRuleFieldRef[];
  aggregations?: LabelGroupRuleAggregation[];
  havingGroup?: LabelGroupRuleConditionGroup | null;
  having?: LabelGroupRuleCondition[];
  sort?: LabelGroupRuleSort[];
  limit?: number | null;
}

export interface LabelGroupDynamicRule {
  ruleConfig: LabelGroupRuleConfig;
  outputValueType?: string | null;
  lastStatus?: string | null;
  lastError?: string | null;
  lastComputedAt?: string | null;
}

export interface LabelGroupDynamicRuleSourceField {
  key: string;
  name: string;
  valueType: string;
  outputSupported: boolean;
  filterSupported: boolean;
  groupSupported: boolean;
  aggregateSupported: boolean;
  operators: string[];
  candidateMode: 'NONE' | 'STATIC' | 'DISTINCT';
  candidateValues: Array<{ label: string; value: string }>;
}

export interface LabelGroupDynamicRuleSource {
  key: string;
  name: string;
  description: string;
  fields: LabelGroupDynamicRuleSourceField[];
}

export interface LabelGroupDynamicRuleRelation {
  name: string;
  leftSourceKey: string;
  leftFieldKey: string;
  rightSourceKey: string;
  rightFieldKey: string;
  matchOperator: string;
  normalizer?: string | null;
}

export interface LabelGroupDynamicRulePreviewRequest {
  ruleConfig: LabelGroupRuleConfig;
}

export interface LabelGroupDynamicRulePreview {
  outputValueType: string;
  status: string;
  message: string;
  members: LabelGroupMember[];
}

export interface LabelGroup {
  id: number;
  name: string;
  valueType?: string | null;
  groupType: 'STATIC' | 'DYNAMIC' | 'COMPOSITE';
  description?: string | null;
  enabled: boolean;
  memberCount: number;
  members: LabelGroupMember[];
  childGroups?: LabelGroupChild[];
  dynamicRule?: LabelGroupDynamicRule | null;
  expandedPreview?: LabelGroupMember[];
  createdBy?: string | null;
  createdAt?: string | null;
  updatedBy?: string | null;
  updatedAt?: string | null;
}

export interface LabelGroupSaveRequest {
  name: string;
  groupType?: 'STATIC' | 'DYNAMIC' | 'COMPOSITE';
  description?: string | null;
  enabled?: boolean;
  members?: LabelGroupMember[];
  childGroupIds?: number[];
  dynamicRule?: Pick<LabelGroupDynamicRule, 'ruleConfig'> | null;
}

export interface LabelGroupExpansion {
  groupId: number;
  groupName: string;
  valueType?: string | null;
  values: string[];
  members: LabelGroupMember[];
}
