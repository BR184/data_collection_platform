export type TagGroupSelectionMode = 'single' | 'multiple';
export type TagGroupValueType = 'standard' | 'unmapped' | 'disabled' | 'raw';
export type TagGroupMatchStrategyName = 'split_exact_comma' | 'array_exact' | 'like' | 'eq';

export interface TagGroupValueResponse {
  valueKey: string;
  label: string;
  valueType: TagGroupValueType;
  sortOrder: number;
  disabled: boolean;
  unmappedReason?: string | null;
}

export interface TagGroupResponse {
  groupKey: string;
  label: string;
  selectionMode: TagGroupSelectionMode;
  sortOrder: number;
  matchStrategyName: TagGroupMatchStrategyName;
  values: TagGroupValueResponse[];
}

export interface TagGroupsResponse {
  domain: string;
  schemaHash: string;
  groups: TagGroupResponse[];
}

export interface TagSelectionRequest {
  groupKey: string;
  valueKeys: string[];
}
