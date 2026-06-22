export interface TestingPhaseDefinitionResponse {
  id: number;
  projectId: number;
  projectName: string;
  legacySourceId?: number | null;
  legacyPhaseName?: string | null;
  legacySortOrder?: number | null;
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
