import type {
  LabelGroup,
  LabelGroupMember,
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
  };
}

export function buildLabelGroupSaveRequest(form: LabelGroupFormState): LabelGroupSaveRequest {
  return {
    name: form.name.trim(),
    groupType: form.groupType,
    description: form.description.trim() || null,
    enabled: form.enabled,
    members: form.groupType === 'COMPOSITE'
      ? []
      : form.members.map((member) => ({
        value: member.value,
        label: member.label || member.value,
      })),
    childGroupIds: form.childGroupIds,
  };
}

export function validateLabelGroupForm(form: LabelGroupFormState) {
  if (!form.name.trim()) {
    return '请输入标签组名称';
  }
  if (form.groupType === 'COMPOSITE' && !form.childGroupIds.length) {
    return '请选择要组合的子标签组';
  }
  if (form.groupType !== 'COMPOSITE' && !form.members.length && !form.childGroupIds.length) {
    return '请选择或输入标签组成员';
  }
  if (form.members.length > 200) {
    return '标签组成员超过 200 个，请拆分后保存';
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
