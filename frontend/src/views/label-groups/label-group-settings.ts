import type {
  LabelGroup,
  LabelGroupDynamicRuleTemplate,
  LabelGroupMember,
  LabelGroupSaveRequest,
} from '../../types/api';

export type LabelGroupType = 'STATIC' | 'DYNAMIC' | 'COMPOSITE';
export type DynamicRuleParamValue = string | number | boolean | null;

export interface LabelGroupFormState {
  id: number | null;
  name: string;
  groupType: LabelGroupType;
  description: string;
  enabled: boolean;
  members: LabelGroupMember[];
  childGroupIds: number[];
  dynamicRuleTemplateKey: string;
  dynamicRuleParams: Record<string, DynamicRuleParamValue>;
}

export function createEmptyLabelGroupForm(): LabelGroupFormState {
  return {
    id: null,
    name: '',
    groupType: 'STATIC',
    description: '',
    enabled: true,
    members: [],
    childGroupIds: [],
    dynamicRuleTemplateKey: '',
    dynamicRuleParams: {},
  };
}

export function createLabelGroupForm(group: LabelGroup): LabelGroupFormState {
  return {
    id: group.id,
    name: group.name,
    groupType: group.groupType,
    description: group.description ?? '',
    enabled: group.enabled,
    members: group.members.map((member) => ({
      id: member.id,
      value: member.value,
      label: member.label,
      currentAvailable: member.currentAvailable,
      sortOrder: member.sortOrder,
    })),
    childGroupIds: (group.childGroups ?? []).map((child) => child.id),
    dynamicRuleTemplateKey: group.dynamicRule?.ruleTemplateKey ?? '',
    dynamicRuleParams: parseDynamicRuleParams(group.dynamicRule?.ruleParamsJson),
  };
}

export function buildLabelGroupSaveRequest(form: LabelGroupFormState): LabelGroupSaveRequest {
  return {
    name: form.name.trim(),
    groupType: form.groupType,
    description: form.description.trim() || null,
    enabled: form.enabled,
    members: form.groupType === 'COMPOSITE' || form.groupType === 'DYNAMIC'
      ? []
      : form.members.map((member) => ({
        value: member.value,
        label: member.label || member.value,
      })),
    childGroupIds: form.childGroupIds,
    dynamicRule: form.groupType === 'DYNAMIC'
      ? {
        ruleTemplateKey: form.dynamicRuleTemplateKey.trim(),
        ruleParamsJson: JSON.stringify(form.dynamicRuleParams),
      }
      : null,
  };
}

export function validateLabelGroupForm(form: LabelGroupFormState) {
  if (!form.name.trim()) {
    return '请输入标签组名称';
  }
  if (form.groupType === 'COMPOSITE' && !form.childGroupIds.length) {
    return '请选择要组合的子标签组';
  }
  if (form.groupType === 'DYNAMIC' && !form.dynamicRuleTemplateKey.trim()) {
    return '请输入动态规则模板';
  }
  if (form.groupType === 'STATIC' && !form.members.length && !form.childGroupIds.length) {
    return '请选择或输入标签组成员';
  }
  if (form.members.length > 200) {
    return '标签组成员超过 200 个，请拆分后保存';
  }
  return '';
}

export function validateDynamicRuleParameters(
    form: LabelGroupFormState,
    templates: LabelGroupDynamicRuleTemplate[],
) {
  if (form.groupType !== 'DYNAMIC' || !form.dynamicRuleTemplateKey) {
    return '';
  }
  const template = templates.find((item) => item.key === form.dynamicRuleTemplateKey);
  if (!template) {
    return '动态规则模板不存在';
  }
  for (const parameter of template.parameters) {
    const value = form.dynamicRuleParams[parameter.key];
    if (parameter.required && (value === null || value === undefined || value === '')) {
      return `请填写${parameter.label}`;
    }
  }
  return '';
}

export function defaultDynamicRuleParams(template: LabelGroupDynamicRuleTemplate | undefined) {
  const params: Record<string, DynamicRuleParamValue> = {};
  if (!template) {
    return params;
  }
  for (const parameter of template.parameters) {
    params[parameter.key] = parameter.defaultValue ?? null;
  }
  return params;
}

export function mergeDynamicRuleParams(
    template: LabelGroupDynamicRuleTemplate | undefined,
    current: Record<string, DynamicRuleParamValue>,
) {
  const defaults = defaultDynamicRuleParams(template);
  return Object.fromEntries(
      Object.keys(defaults).map((key) => [key, current[key] ?? defaults[key]]),
  ) as Record<string, DynamicRuleParamValue>;
}

export function parseDynamicRuleParams(ruleParamsJson?: string | null) {
  if (!ruleParamsJson) {
    return {};
  }
  try {
    const parsed = JSON.parse(ruleParamsJson) as Record<string, DynamicRuleParamValue>;
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {};
  } catch {
    return {};
  }
}

export function buildMemberPreview(group: LabelGroup, limit = 4) {
  const members = group.expandedPreview?.length ? group.expandedPreview : group.members;
  if (!members.length) {
    return '暂无成员';
  }
  const visible = members.slice(0, limit).map((member) => member.label || member.value).join('、');
  const hiddenCount = members.length - limit;
  return hiddenCount > 0 ? `${visible} 等 ${members.length} 个` : visible;
}

export function unavailableMemberCount(members: LabelGroupMember[]) {
  return members.filter((member) => member.currentAvailable === false).length;
}

export function inferValueTypeFromMembers(members: LabelGroupMember[]) {
  let current = '';
  for (const member of members) {
    const next = inferValueType(member.value);
    if (!current) {
      current = next;
      continue;
    }
    if (current !== next) {
      return 'MIXED';
    }
  }
  return current;
}

export function inferValueType(value: string) {
  const text = String(value ?? '').trim();
  if (/^(true|false)$/i.test(text)) {
    return 'BOOLEAN';
  }
  if (text && /^-?\d+(\.\d+)?$/.test(text)) {
    return 'NUMBER';
  }
  if (/^\d{4}-\d{2}-\d{2}(T.*)?$/.test(text)) {
    return 'DATE';
  }
  return 'STRING';
}

export function valueTypeLabel(valueType?: string | null) {
  return ({
    STRING: '字符串',
    NUMBER: '数字',
    DATE: '日期',
    BOOLEAN: '布尔',
    ENUM: '枚举',
    ARRAY: '数组',
    MIXED: '类型不一致',
  } as Record<string, string>)[valueType || ''] ?? '未定型';
}
