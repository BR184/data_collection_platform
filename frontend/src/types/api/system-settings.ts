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
