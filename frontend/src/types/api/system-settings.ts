export type IssueScopeDimension = 'TESTING_PHASE' | 'MILESTONE';
export interface IssueScopeCatalogResponse { id: number; projectId: number; projectName: string; dimension: IssueScopeDimension; dimensionName: string; enabled: boolean; remark: string; groupCount: number; unassignedValueCount: number; createdAt?: string | null; updatedAt?: string | null; }
export interface IssueScopeCatalogSaveRequest { projectId: number; projectName: string; dimension: IssueScopeDimension; enabled: boolean; remark?: string | null; }
export interface IssueScopeMemberResponse { id: number; catalogId: number; groupId: number; sourceValue: string; displayName: string; sortOrder: number; activeFrom?: string | null; activeUntil?: string | null; enabled: boolean; sourceReferenceId?: number | null; remark: string; issueCount: number; createdAt?: string | null; updatedAt?: string | null; }
export interface IssueScopeMemberSaveRequest { catalogId: number; groupId: number; sourceValue: string; displayName: string; sortOrder?: number | null; activeFrom?: string | null; activeUntil?: string | null; enabled: boolean; sourceReferenceId?: number | null; remark?: string | null; }
export interface IssueScopeGroupResponse { id: number; catalogId: number; projectId: number; projectName: string; dimension: IssueScopeDimension; businessKey: string; displayName: string; sortOrder: number; enabled: boolean; remark: string; issueCount: number; members: IssueScopeMemberResponse[]; createdAt?: string | null; updatedAt?: string | null; }
export interface IssueScopeGroupSaveRequest { catalogId: number; businessKey: string; displayName: string; sortOrder?: number | null; enabled: boolean; remark?: string | null; }
export interface IssueScopeDiscoveredValueResponse { value: string; issueCount: number; }

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
  expectedSettingsUpdatedAt: string;
}

export interface LegacyPlatformFormalImportDomainResponse {
  status: 'SUCCESS' | 'FAILED' | 'NOT_REQUESTED';
  insertedCount: number;
  updatedCount: number;
  skippedCount: number;
  deletedCount: number;
  message: string;
}

export interface LegacyPlatformFormalImportResponse {
  runId: number;
  accepted: boolean;
  status: 'SUCCESS' | 'FAILED';
  message: string;
  review: LegacyPlatformFormalImportDomainResponse;
  codeReview: LegacyPlatformFormalImportDomainResponse;
}

export interface CodeReviewDgmGitlabProjectSourceResponse {
  enabled: boolean;
  gitlabBaseUrl: string;
  accessTokenConfigured: boolean;
  groupPath: string;
  includeSubgroups: boolean;
  includeArchived: boolean;
  syncIntervalMinutes: number;
  lastSyncStatus: string;
  lastSyncMessage?: string | null;
  lastSyncRecordCount: number;
  lastSyncStartedAt?: string | null;
  lastSyncFinishedAt?: string | null;
  updatedAt?: string | null;
}

export interface CodeReviewDgmGitlabProjectSourceSaveRequest {
  enabled: boolean;
  gitlabBaseUrl: string;
  accessToken?: string | null;
  groupPath: string;
  includeSubgroups: boolean;
  includeArchived: boolean;
  syncIntervalMinutes: number;
}

export interface CodeReviewDgmGitlabProjectOptionResponse {
  gitlabProjectId: number;
  name: string;
  path?: string | null;
  pathWithNamespace?: string | null;
  webUrl?: string | null;
  namespaceName?: string | null;
  namespaceFullPath?: string | null;
  archived: boolean;
  visibility?: string | null;
  active: boolean;
}

export interface CodeReviewDgmGitlabProjectSyncResponse {
  success: boolean;
  status: string;
  message: string;
  recordCount: number;
  startedAt?: string | null;
  finishedAt?: string | null;
}
