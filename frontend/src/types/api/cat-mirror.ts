export interface CatMirrorConfig {
  enabled: boolean;
  baseUrl: string;
  autoSyncEnabled: boolean;
  syncIntervalMinutes: number;
  fullCompensationEnabled: boolean;
  fullCompensationTime: string;
}

export interface CatMirrorProductVersion {
  id: number;
  businessKey: string;
  displayName: string;
  sortOrder: number;
}

export interface CatMirrorMapping {
  productVersionId: number;
  productVersionKey: string;
  catProjectId: string;
  catVersionId: string;
  unitTestingPhaseId: string;
  integrationTestingPhaseId: string;
}

export interface CatMirrorMappingSuggestion {
  productVersionId: number;
  catProjectId?: string | null;
  catVersionId?: string | null;
  unitTestingPhaseId?: string | null;
  integrationTestingPhaseId?: string | null;
}

export interface CatMirrorSaveMapping {
  productVersionId: number;
  catProjectId: string;
  catVersionId: string;
  unitTestingPhaseId: string;
  integrationTestingPhaseId: string;
}

export interface CatMirrorCatalogProject {
  id: string;
  name: string;
  note?: string | null;
  createTime?: string | null;
  createUserId?: string | null;
  defaultProject?: boolean | null;
}

export interface CatMirrorCatalogNode {
  projectId: string;
  nodeType: 'VERSION' | 'TEST_PHASE';
  id: string;
  parentId?: string | null;
  name: string;
  createTime?: string | null;
  note?: string | null;
  endTime?: string | null;
  currentVersion?: boolean | null;
  disabled?: boolean | null;
  defaultProject?: boolean | null;
  groupId?: string | null;
  versionId?: string | null;
}

export interface CatMirrorCatalog {
  snapshotId: string;
  collectedAt: string;
  projects: CatMirrorCatalogProject[];
  nodes: CatMirrorCatalogNode[];
}

export interface CatMirrorRun {
  runId: string;
  runType: 'FULL' | 'FULL_COMPENSATION';
  triggerType: 'MANUAL' | 'SCHEDULED';
  status: 'RUNNING' | 'SUCCEEDED' | 'PARTIAL_SUCCESS' | 'FAILED';
  startedAt: string;
  finishedAt?: string | null;
  catalogSnapshotId?: string | null;
  publishedStageCount: number;
  failedStageCount: number;
  message: string;
}

export interface CatMirrorSettings {
  config: CatMirrorConfig;
  productVersions: CatMirrorProductVersion[];
  mappings: CatMirrorMapping[];
  mappingSuggestions: CatMirrorMappingSuggestion[];
  catalog?: CatMirrorCatalog | null;
  recentRuns: CatMirrorRun[];
  synchronizing: boolean;
}

export interface CatMirrorConnectionResult {
  success: boolean;
  message: string;
  projectCount: number;
  versionCount: number;
  phaseCount: number;
}

export interface CatMirrorSubmission {
  accepted: boolean;
  runId?: string | null;
  status: string;
  message: string;
}
