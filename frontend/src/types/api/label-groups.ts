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

export interface LabelGroupDynamicRule {
  ruleTemplateKey: string;
  ruleParamsJson: string;
  outputValueType?: string | null;
  lastStatus?: string | null;
  lastError?: string | null;
  lastComputedAt?: string | null;
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
  dynamicRule?: Pick<LabelGroupDynamicRule, 'ruleTemplateKey' | 'ruleParamsJson'> | null;
}

export interface LabelGroupExpansion {
  groupId: number;
  groupName: string;
  valueType?: string | null;
  values: string[];
  members: LabelGroupMember[];
}
