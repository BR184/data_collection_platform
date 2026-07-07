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

export interface CodeReviewMatchModeDbSettingsResponse {
  enabled: boolean;
  syncEnabled: boolean;
  mysqlHost: string;
  mysqlPort: number;
  mysqlDatabase: string;
  dgmMysqlDatabase: string;
  mysqlUsername: string;
  mysqlPasswordConfigured: boolean;
  mysqlTableName: string;
  legacyApiBaseUrl: string;
  dgmLegacyApiBaseUrl: string;
  selectedTableNames: string[];
  mysqlFetchSize: number;
  mongoUriConfigured: boolean;
  mongoDatabase: string;
  selectedMongoCollectionNames: string[];
  reviewReportCollectionName: string;
  reviewProblemCollectionName: string;
  reviewDataReadMode: 'compatibility' | 'formal';
  codeReviewReadMode: 'compatibility' | 'formal';
  syncStatus: string;
  syncMessage?: string | null;
  syncRecordCount: number;
  syncStartedAt?: string | null;
  syncFinishedAt?: string | null;
  updatedAt?: string | null;
}

export interface CodeReviewMatchModeDbSettingsSaveRequest {
  enabled: boolean;
  syncEnabled: boolean;
  mysqlHost: string;
  mysqlPort: number;
  mysqlDatabase: string;
  dgmMysqlDatabase: string;
  mysqlUsername: string;
  mysqlPassword?: string | null;
  mysqlTableName: string;
  legacyApiBaseUrl: string;
  dgmLegacyApiBaseUrl: string;
  selectedTableNames: string[];
  mysqlFetchSize: number;
  mongoUri?: string | null;
  mongoDatabase: string;
  selectedMongoCollectionNames: string[];
  reviewReportCollectionName: string;
  reviewProblemCollectionName: string;
  reviewDataReadMode?: 'compatibility' | 'formal';
  codeReviewReadMode?: 'compatibility' | 'formal';
}

export interface CodeReviewMatchModeTableOptionResponse {
  tableName: string;
  label: string;
  selected: boolean;
}

export interface CodeReviewMatchModeCollectionOptionResponse {
  collectionName: string;
  label: string;
  selected: boolean;
}

export interface CodeReviewMatchModeConnectionTestResponse {
  success: boolean;
  message: string;
  recordCount: number;
}

export interface CodeReviewMatchModeSyncResponse {
  accepted: boolean;
  status: string;
  message: string;
  recordCount: number;
  startedAt?: string | null;
  finishedAt?: string | null;
}

export interface LegacyPlatformFormalImportRequest {
  importReviewData: boolean;
  importCodeReviewData: boolean;
  confirmationText: string;
}

export interface LegacyPlatformFormalImportResponse {
  accepted: boolean;
  message: string;
  reviewInsertedCount: number;
  reviewUpdatedCount: number;
  codeReviewInsertedCount: number;
  codeReviewUpdatedCount: number;
}
