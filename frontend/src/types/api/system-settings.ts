export interface TestingPhaseDefinitionResponse {
  id: number;
  projectId: number;
  projectName: string;
  testingPhase: string;
  phaseStartAt: string;
  phaseEndAt?: string | null;
  enabled: boolean;
  remark: string;
  issueCount: number;
  createdAt?: string | null;
  updatedAt?: string | null;
}

export interface TestingPhaseDefinitionSaveRequest {
  projectId: number;
  testingPhase: string;
  phaseStartAt: string;
  phaseEndAt?: string | null;
  enabled: boolean;
  remark?: string | null;
}

export interface TestingPhaseProjectOptionResponse {
  projectId: number;
  projectName: string;
}

export interface SemanticTagValueResponse {
  valueKey: string;
  label: string;
  valueType: string;
  canonicalValue: string;
  enabled: boolean;
  sortOrder: number;
}

export interface SemanticTagGroupResponse {
  domain: string;
  groupKey: string;
  label: string;
  sourceMode: 'STATIC' | 'DYNAMIC' | 'HYBRID';
  rulePolicyKey: string;
  selectionMode: 'SINGLE' | 'MULTIPLE';
  matchStrategyName: string;
  enabled: boolean;
  sortOrder: number;
  values: SemanticTagValueResponse[];
}

export interface SemanticTagGroupCatalogResponse {
  entityType: string;
  schemaHash: string;
  groups: SemanticTagGroupResponse[];
}

export interface BusinessTagGroupResponse {
  id: number;
  tagGroupName: string;
  ownerUserId: string;
  visibility: 'PRIVATE' | 'TEAM' | 'PUBLIC';
  entityType: string;
  scenarioKey: string;
  scopeKey: string;
  dslJson: string;
  dslHash: string;
  tagSchemaHash: string;
  sourceDataWatermarkAtSave?: string | null;
  lastUsedAt?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}

export interface BusinessTagGroupSaveRequest {
  tagGroupName: string;
  ownerUserId: string;
  visibility: 'PRIVATE' | 'TEAM' | 'PUBLIC';
  entityType: string;
  scenarioKey: string;
  scopeKey: string;
  dslJson: string;
  tagSchemaHash: string;
  sourceDataWatermarkAtSave?: string | null;
}

export interface BusinessTagGroupUpdateRequest {
  tagGroupName?: string;
  visibility?: 'PRIVATE' | 'TEAM' | 'PUBLIC';
  entityType?: string;
  scenarioKey?: string;
  scopeKey?: string;
  dslJson?: string;
  tagSchemaHash?: string;
  sourceDataWatermarkAtSave?: string | null;
}

export interface BusinessTagGroupApplyResponse {
  tagGroup: BusinessTagGroupResponse;
  schemaCompatible: boolean;
  compatibilityMessage: string;
}
