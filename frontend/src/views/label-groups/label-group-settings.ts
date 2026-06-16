import type {
  LabelGroup,
  LabelGroupMember,
  LabelGroupRuleCondition,
  LabelGroupRuleConfig,
  LabelGroupRuleRelation,
  LabelGroupSaveRequest,
} from '../../types/api';

export type LabelGroupType = 'STATIC' | 'DYNAMIC' | 'COMPOSITE';

export interface LabelGroupFormState {
  id: number | null;
  name: string;
  groupType: LabelGroupType;
  description: string;
  enabled: boolean;
  members: LabelGroupMember[];
  childGroupIds: number[];
  dynamicRule: DynamicRuleFormState;
}

export interface DynamicRuleFormState {
  outputSourceKey: string;
  outputFieldKey: string;
  distinct: boolean;
  filters: RuleConditionFormState[];
  relations: RuleRelationFormState[];
  groupBy: RuleFieldRefFormState[];
  aggregations: RuleAggregationFormState[];
  having: RuleConditionFormState[];
  sort: RuleSortFormState[];
  limit: number | null;
}

export interface RuleSourceFieldOption {
  sourceKey: string;
  sourceName: string;
  fieldKey: string;
  fieldName: string;
  valueType: string;
  operators: string[];
  outputSupported: boolean;
  filterSupported: boolean;
  groupSupported: boolean;
  aggregateSupported: boolean;
}

export interface RuleConditionFormState {
  sourceKey: string;
  fieldKey: string;
  aggregateKey: string;
  operator: string;
  value: string;
  secondValue: string;
  valuesText: string;
}

export interface RuleRelationFormState {
  leftSourceKey: string;
  leftFieldKey: string;
  rightSourceKey: string;
  rightFieldKey: string;
  matchOperator: string;
  normalizer: string;
}

export interface RuleFieldRefFormState {
  sourceKey: string;
  fieldKey: string;
}

export interface RuleAggregationFormState {
  key: string;
  sourceKey: string;
  fieldKey: string;
  function: string;
  label: string;
}

export interface RuleSortFormState {
  sourceKey: string;
  fieldKey: string;
  aggregateKey: string;
  direction: 'asc' | 'desc';
}

export interface ChildGroupExpandedPreview {
  members: LabelGroupMember[];
  total: number;
  hiddenCount: number;
  overLimit: boolean;
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
    dynamicRule: createEmptyDynamicRuleForm(),
  };
}

export function createEmptyDynamicRuleForm(): DynamicRuleFormState {
  return {
    outputSourceKey: '',
    outputFieldKey: '',
    distinct: true,
    filters: [],
    relations: [],
    groupBy: [],
    aggregations: [],
    having: [],
    sort: [],
    limit: 50,
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
    dynamicRule: createDynamicRuleForm(group.dynamicRule?.ruleConfig),
  };
}

export function createDynamicRuleForm(ruleConfig?: LabelGroupRuleConfig | null): DynamicRuleFormState {
  return {
    outputSourceKey: ruleConfig?.outputSourceKey ?? '',
    outputFieldKey: ruleConfig?.outputFieldKey ?? '',
    distinct: ruleConfig?.distinct ?? true,
    filters: (ruleConfig?.filters ?? []).map(toConditionFormState),
    relations: (ruleConfig?.relations ?? []).map(toRelationFormState),
    groupBy: (ruleConfig?.groupBy ?? []).map((item) => ({ sourceKey: item.sourceKey, fieldKey: item.fieldKey })),
    aggregations: (ruleConfig?.aggregations ?? []).map((item) => ({
      key: item.key,
      sourceKey: item.sourceKey,
      fieldKey: item.fieldKey,
      function: item.function,
      label: item.label ?? '',
    })),
    having: (ruleConfig?.having ?? []).map(toConditionFormState),
    sort: (ruleConfig?.sort ?? []).map((item) => ({
      sourceKey: item.sourceKey ?? '',
      fieldKey: item.fieldKey ?? '',
      aggregateKey: item.aggregateKey ?? '',
      direction: item.direction,
    })),
    limit: ruleConfig?.limit ?? 50,
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
        ruleConfig: buildRuleConfig(form.dynamicRule),
      }
      : null,
  };
}

export function buildRuleConfig(form: DynamicRuleFormState): LabelGroupRuleConfig {
  return {
    outputSourceKey: form.outputSourceKey.trim(),
    outputFieldKey: form.outputFieldKey.trim(),
    distinct: form.distinct,
    filters: form.filters.map(toConditionPayload),
    relations: form.relations.map(toRelationPayload),
    groupBy: form.groupBy
      .filter((item) => item.sourceKey.trim() && item.fieldKey.trim())
      .map((item) => ({ sourceKey: item.sourceKey.trim(), fieldKey: item.fieldKey.trim() })),
    aggregations: form.aggregations
      .filter((item) => item.key.trim() && item.sourceKey.trim() && item.fieldKey.trim())
      .map((item) => ({
        key: item.key.trim(),
        sourceKey: item.sourceKey.trim(),
        fieldKey: item.fieldKey.trim(),
        function: item.function.trim(),
        label: item.label.trim() || null,
      })),
    having: form.having.map(toConditionPayload),
    sort: form.sort
      .filter((item) => item.sourceKey.trim() || item.aggregateKey.trim())
      .map((item) => ({
        sourceKey: item.sourceKey.trim() || null,
        fieldKey: item.fieldKey.trim() || null,
        aggregateKey: item.aggregateKey.trim() || null,
        direction: item.direction,
      })),
    limit: form.limit == null ? null : Math.max(1, Math.min(200, form.limit)),
  };
}

export function validateLabelGroupForm(form: LabelGroupFormState) {
  if (!form.name.trim()) {
    return '请输入标签组名称';
  }
  if (form.groupType === 'COMPOSITE' && !form.childGroupIds.length) {
    return '请选择要组合的子标签组';
  }
  if (form.groupType === 'DYNAMIC') {
    const ruleError = validateDynamicRuleForm(form.dynamicRule);
    if (ruleError) {
      return ruleError;
    }
  }
  if (form.groupType === 'STATIC' && !form.members.length && !form.childGroupIds.length) {
    return '请选择或输入标签组成员';
  }
  if (form.members.length > 200) {
    return '标签组成员超过 200 个，请拆分后保存';
  }
  return '';
}

export function validateDynamicRuleForm(form: DynamicRuleFormState) {
  if (!form.outputSourceKey.trim()) {
    return '请选择输出数据源';
  }
  if (!form.outputFieldKey.trim()) {
    return '请选择输出字段';
  }
  return '';
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

export function buildChildGroupExpandedPreview(
    groups: LabelGroup[],
    childGroupIds: number[],
    limit = 20,
): ChildGroupExpandedPreview {
  const deduped = new Map<string, LabelGroupMember>();
  for (const childGroupId of childGroupIds) {
    const group = groups.find((item) => item.id === childGroupId);
    const members = group?.expandedPreview?.length ? group.expandedPreview : group?.members ?? [];
    for (const member of members) {
      if (!deduped.has(member.value)) {
        deduped.set(member.value, {
          value: member.value,
          label: member.label || member.value,
        });
      }
    }
  }
  const members = Array.from(deduped.values());
  return {
    members: members.slice(0, limit),
    total: members.length,
    hiddenCount: Math.max(0, members.length - limit),
    overLimit: members.length > 200,
  };
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

function toConditionFormState(condition: LabelGroupRuleCondition): RuleConditionFormState {
  return {
    sourceKey: condition.sourceKey ?? '',
    fieldKey: condition.fieldKey ?? '',
    aggregateKey: condition.aggregateKey ?? '',
    operator: condition.operator ?? 'eq',
    value: condition.value ?? '',
    secondValue: condition.secondValue ?? '',
    valuesText: (condition.values ?? []).join('\n'),
  };
}

function toRelationFormState(relation: LabelGroupRuleRelation): RuleRelationFormState {
  return {
    leftSourceKey: relation.leftSourceKey,
    leftFieldKey: relation.leftFieldKey,
    rightSourceKey: relation.rightSourceKey,
    rightFieldKey: relation.rightFieldKey,
    matchOperator: relation.matchOperator,
    normalizer: relation.normalizer ?? 'NONE',
  };
}

function toConditionPayload(condition: RuleConditionFormState): LabelGroupRuleCondition {
  return {
    sourceKey: condition.sourceKey.trim() || null,
    fieldKey: condition.fieldKey.trim() || null,
    aggregateKey: condition.aggregateKey.trim() || null,
    operator: condition.operator,
    value: condition.value.trim() || null,
    secondValue: condition.secondValue.trim() || null,
    values: condition.valuesText
      .split(/\r?\n|,/)
      .map((item) => item.trim())
      .filter(Boolean),
  };
}

function toRelationPayload(relation: RuleRelationFormState): LabelGroupRuleRelation {
  return {
    leftSourceKey: relation.leftSourceKey.trim(),
    leftFieldKey: relation.leftFieldKey.trim(),
    rightSourceKey: relation.rightSourceKey.trim(),
    rightFieldKey: relation.rightFieldKey.trim(),
    matchOperator: relation.matchOperator.trim() || 'eq',
    normalizer: relation.normalizer.trim() || 'NONE',
  };
}
