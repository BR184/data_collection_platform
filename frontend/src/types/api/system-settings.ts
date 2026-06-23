export interface TestingPhaseDefinitionResponse {
  id: number;
  projectId: number;
  projectName: string;
  legacySourceId?: number | null;
  legacyPhaseName?: string | null;
  legacySortOrder?: number | null;
  phaseGroupId?: number | null;
  childSortOrder?: number | null;
  testingPhase: string;
  phaseStartAt?: string | null;
  phaseEndAt?: string | null;
  enabled: boolean;
  remark: string;
  issueCount: number;
  createdAt?: string | null;
  updatedAt?: string | null;
}

export interface TestingPhaseDefinitionSaveRequest {
  projectId: number;
  legacySourceId?: number | null;
  legacyPhaseName?: string | null;
  legacySortOrder?: number | null;
  phaseGroupId?: number | null;
  childSortOrder?: number | null;
  testingPhase: string;
  phaseStartAt?: string | null;
  phaseEndAt?: string | null;
  enabled: boolean;
  remark?: string | null;
}

export interface TestingPhaseProjectOptionResponse {
  projectId: number;
  projectName: string;
}

export interface TestingPhaseGroupResponse {
  id: number;
  projectId: number;
  name: string;
  sortOrder: number;
  enabled: boolean;
  remark: string;
  issueCount: number;
  children: TestingPhaseDefinitionResponse[];
  createdAt?: string | null;
  updatedAt?: string | null;
}

export interface TestingPhaseGroupSaveRequest {
  projectId: number;
  name: string;
  sortOrder?: number | null;
  enabled: boolean;
  remark?: string | null;
}
