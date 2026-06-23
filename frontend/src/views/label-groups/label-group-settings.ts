import type {
  LabelGroup,
  LabelGroupMember,
  LabelGroupRuleCondition,
  LabelGroupRuleConditionGroup,
  LabelGroupRuleConfig,
  LabelGroupRuleRelation,
  LabelGroupSaveRequest,
} from '../../types/api';

export type LabelGroupType = 'STATIC' | 'DYNAMIC' | 'COMPOSITE';

export interface LabelGroupFormState {
  id: number | null;
  name: string;
  groupType: LabelGroupType;
  applicableScope: 'SAME_TYPE' | 'SAME_FIELD';
  sourceFieldKey: string;
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
  filterGroup: RuleConditionGroupFormState;
  relations: RuleRelationFormState[];
  groupBy: RuleFieldRefFormState[];
  aggregations: RuleAggregationFormState[];
  havingGroup: RuleConditionGroupFormState;
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
  candidateMode: 'NONE' | 'STATIC' | 'DISTINCT';
  candidateValues: Array<{ label: string; value: string }>;
}

export interface RuleConditionFormState {
  id: string;
  sourceKey: string;
  fieldKey: string;
  aggregateKey: string;
  operator: string;
  value: string;
  secondValue: string;
  valuesText: string;
}

export interface RuleConditionGroupFormState {
  id: string;
  sourceKey?: string;
  logic: 'AND' | 'OR';
  conditions: RuleConditionFormState[];
  groups: RuleConditionGroupFormState[];
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
    applicableScope: 'SAME_TYPE',
    sourceFieldKey: '',
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
    filterGroup: createEmptyConditionGroup(),
    relations: [],
    groupBy: [],
    aggregations: [],
    havingGroup: createEmptyConditionGroup(),
    sort: [],
    limit: 200,
  };
}

export function createEmptyConditionGroup(sourceKey = ''): RuleConditionGroupFormState {
  return {
    id: createRuleDraftId('group'),
    sourceKey,
    logic: 'AND',
    conditions: [],
    groups: [],
  };
}

export function createEmptyCondition(sourceKey = ''): RuleConditionFormState {
  return {
    id: createRuleDraftId('condition'),
    sourceKey,
    fieldKey: '',
    aggregateKey: '',
    operator: 'eq',
    value: '',
    secondValue: '',
    valuesText: '',
  };
}

export function createLabelGroupForm(group: LabelGroup): LabelGroupFormState {
  return {
    id: group.id,
    name: group.name,
    groupType: group.groupType,
    applicableScope: group.applicableScope === 'SAME_FIELD' ? 'SAME_FIELD' : 'SAME_TYPE',
    sourceFieldKey: group.sourceFieldKey ?? '',
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
    filterGroup: toConditionGroupFormState(ruleConfig?.filterGroup, ruleConfig?.filters),
    relations: (ruleConfig?.relations ?? []).map(toRelationFormState),
    groupBy: (ruleConfig?.groupBy ?? []).map((item) => ({ sourceKey: item.sourceKey, fieldKey: item.fieldKey })),
    aggregations: (ruleConfig?.aggregations ?? []).map((item) => ({
      key: item.key,
      sourceKey: item.sourceKey,
      fieldKey: item.fieldKey,
      function: item.function,
      label: item.label ?? '',
    })),
    havingGroup: toConditionGroupFormState(ruleConfig?.havingGroup, ruleConfig?.having),
    sort: (ruleConfig?.sort ?? []).map((item) => ({
      sourceKey: item.sourceKey ?? '',
      fieldKey: item.fieldKey ?? '',
      aggregateKey: item.aggregateKey ?? '',
      direction: item.direction,
    })),
    limit: ruleConfig?.limit ?? 200,
  };
}

export function buildLabelGroupSaveRequest(form: LabelGroupFormState): LabelGroupSaveRequest {
  return {
    name: form.name.trim(),
    groupType: form.groupType,
    applicableScope: form.applicableScope,
    sourceFieldKey: form.applicableScope === 'SAME_FIELD' ? form.sourceFieldKey.trim() : null,
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
    filterGroup: toConditionGroupPayload(form.filterGroup),
    filters: [],
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
    havingGroup: toConditionGroupPayload(form.havingGroup),
    having: [],
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
  if (form.applicableScope === 'SAME_FIELD' && !form.sourceFieldKey.trim()) {
    return '请选择适用来源字段';
  }
  if (form.groupType === 'STATIC' && !form.members.length && !form.childGroupIds.length) {
    return '请选择标签组成员';
  }
  if (form.members.length > 200) {
    return '标签组成员超过 200 个，请拆分后保存';
  }
  return '';
}

export function validateDynamicRuleForm(form: DynamicRuleFormState) {
  if (!form.outputSourceKey.trim()) {
    return '请选择结果来源';
  }
  if (!form.outputFieldKey.trim()) {
    return '请选择输出字段';
  }
  return '';
}

export function buildDynamicRuleSummary(
    form: DynamicRuleFormState,
    sourceName: string,
    fieldName: string,
) {
  const parts = [
    sourceName && fieldName ? `从${sourceName}中取${fieldName}` : '请选择结果来源和输出字段',
  ];
  const conditionCount = countConditions(form.filterGroup);
  if (conditionCount) {
    parts.push(`包含 ${conditionCount} 条过滤条件`);
  }
  if (form.relations.length) {
    parts.push(`使用 ${form.relations.length} 条逻辑关联`);
  }
  if (form.aggregations.length) {
    parts.push(`包含 ${form.aggregations.length} 个聚合项`);
  }
  parts.push(form.distinct ? '结果去重' : '结果不去重');
  parts.push(`最多保留 ${form.limit ?? 200} 个成员`);
  return parts.join('，');
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
    id: createRuleDraftId('condition'),
    sourceKey: condition.sourceKey ?? '',
    fieldKey: condition.fieldKey ?? '',
    aggregateKey: condition.aggregateKey ?? '',
    operator: condition.operator ?? 'eq',
    value: condition.value ?? '',
    secondValue: condition.secondValue ?? '',
    valuesText: (condition.values ?? []).join('\n'),
  };
}

function toConditionGroupFormState(
    group?: LabelGroupRuleConditionGroup | null,
    legacyConditions?: LabelGroupRuleCondition[],
): RuleConditionGroupFormState {
  const effective = group ?? (
    legacyConditions?.length
      ? { logic: 'AND' as const, conditions: legacyConditions, groups: [] }
      : null
  );
  if (!effective) {
    return createEmptyConditionGroup();
  }
  return {
    id: createRuleDraftId('group'),
    sourceKey: inferGroupSourceKey(effective),
    logic: effective.logic === 'OR' ? 'OR' : 'AND',
    conditions: (effective.conditions ?? []).map(toConditionFormState),
    groups: (effective.groups ?? []).map((child) => toConditionGroupFormState(child)),
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

function toConditionGroupPayload(group: RuleConditionGroupFormState): LabelGroupRuleConditionGroup | null {
  const conditions = group.conditions
    .filter((condition) => isReadyCondition(condition))
    .map(toConditionPayload);
  const groups = group.groups
    .map(toConditionGroupPayload)
    .filter((item): item is LabelGroupRuleConditionGroup => Boolean(item));
  if (!conditions.length && !groups.length) {
    return null;
  }
  return {
    logic: group.logic === 'OR' ? 'OR' : 'AND',
    conditions,
    groups,
  };
}

function isReadyCondition(condition: RuleConditionFormState) {
  return Boolean(
    (condition.aggregateKey.trim() || (condition.sourceKey.trim() && condition.fieldKey.trim()))
    && condition.operator.trim(),
  );
}

function countConditions(group: RuleConditionGroupFormState): number {
  return group.conditions.length + group.groups.reduce((total, child) => total + countConditions(child), 0);
}

function inferGroupSourceKey(group: LabelGroupRuleConditionGroup) {
  const sourceKeys = new Set(
    (group.conditions ?? [])
      .map((condition) => condition.sourceKey ?? '')
      .filter(Boolean),
  );
  return sourceKeys.size === 1 ? Array.from(sourceKeys)[0] : '';
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

function createRuleDraftId(prefix: string) {
  return `${prefix}-${Math.random().toString(36).slice(2, 10)}`;
}
