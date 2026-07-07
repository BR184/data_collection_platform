import type {
  CodeReviewMatchModeCollectionOptionResponse,
  CodeReviewMatchModeConnectionTestResponse,
  CodeReviewMatchModeDbSettingsResponse,
  CodeReviewMatchModeDbSettingsSaveRequest,
  CodeReviewMatchModeSyncResponse,
  CodeReviewMatchModeTableOptionResponse,
  CodeReviewDgmGitlabProjectOptionResponse,
  CodeReviewDgmGitlabProjectSourceResponse,
  CodeReviewDgmGitlabProjectSourceSaveRequest,
  CodeReviewDgmGitlabProjectSyncResponse,
  LegacyPlatformFormalImportRequest,
  LegacyPlatformFormalImportResponse,
} from '../types/api';
import { request } from './request';

// 兼容模式-MatchMode：评审前短期连接老平台数据库，后续外部工具正式接入后整体删除。
const basePath = '/api/code-review/match-mode-db-settings';

export const legacyDatabaseApi = {
  getCodeReviewMatchModeDbSettings() {
    return request<CodeReviewMatchModeDbSettingsResponse>(basePath);
  },
  saveCodeReviewMatchModeDbSettings(payload: CodeReviewMatchModeDbSettingsSaveRequest) {
    return request<CodeReviewMatchModeDbSettingsResponse>(basePath, {
      method: 'PUT',
      body: JSON.stringify(payload),
    });
  },
  testCodeReviewMatchModeDbConnection(payload: CodeReviewMatchModeDbSettingsSaveRequest) {
    return request<CodeReviewMatchModeConnectionTestResponse>(`${basePath}/test-connection`, {
      method: 'POST',
      body: JSON.stringify(payload),
      timeoutMs: 30_000,
    });
  },
  getCodeReviewMatchModeTableOptions(payload: CodeReviewMatchModeDbSettingsSaveRequest) {
    return request<CodeReviewMatchModeTableOptionResponse[]>(`${basePath}/table-options`, {
      method: 'POST',
      body: JSON.stringify(payload),
      timeoutMs: 30_000,
    });
  },
  testCodeReviewMatchModeMongoConnection(payload: CodeReviewMatchModeDbSettingsSaveRequest) {
    return request<CodeReviewMatchModeConnectionTestResponse>(`${basePath}/mongo/test-connection`, {
      method: 'POST',
      body: JSON.stringify(payload),
      timeoutMs: 30_000,
    });
  },
  getCodeReviewMatchModeMongoCollectionOptions(payload: CodeReviewMatchModeDbSettingsSaveRequest) {
    return request<CodeReviewMatchModeCollectionOptionResponse[]>(`${basePath}/mongo/collection-options`, {
      method: 'POST',
      body: JSON.stringify(payload),
      timeoutMs: 30_000,
    });
  },
  syncCodeReviewMatchModeDbNow() {
    return request<CodeReviewMatchModeSyncResponse>(`${basePath}/sync-now`, {
      method: 'POST',
      timeoutMs: 600_000,
    });
  },
  syncCodeReviewMatchModeMongoNow(payload: CodeReviewMatchModeDbSettingsSaveRequest) {
    return request<CodeReviewMatchModeSyncResponse>(`${basePath}/mongo/sync-now`, {
      method: 'POST',
      body: JSON.stringify(payload),
      timeoutMs: 600_000,
    });
  },
  importLegacyPlatformToFormal(payload: LegacyPlatformFormalImportRequest) {
    return request<LegacyPlatformFormalImportResponse>(`${basePath}/formal-import`, {
      method: 'POST',
      body: JSON.stringify(payload),
      timeoutMs: 900_000,
    });
  },
  getCodeReviewDgmGitlabProjectSource() {
    return request<CodeReviewDgmGitlabProjectSourceResponse>(`${basePath}/dgm-gitlab-project-source`);
  },
  saveCodeReviewDgmGitlabProjectSource(payload: CodeReviewDgmGitlabProjectSourceSaveRequest) {
    return request<CodeReviewDgmGitlabProjectSourceResponse>(`${basePath}/dgm-gitlab-project-source`, {
      method: 'PUT',
      body: JSON.stringify(payload),
    });
  },
  testCodeReviewDgmGitlabProjectSource(payload: CodeReviewDgmGitlabProjectSourceSaveRequest) {
    return request<CodeReviewMatchModeConnectionTestResponse>(`${basePath}/dgm-gitlab-project-source/test-connection`, {
      method: 'POST',
      body: JSON.stringify(payload),
      timeoutMs: 60_000,
    });
  },
  syncCodeReviewDgmGitlabProjectOptions() {
    return request<CodeReviewDgmGitlabProjectSyncResponse>(`${basePath}/dgm-gitlab-project-options/sync-now`, {
      method: 'POST',
      timeoutMs: 300_000,
    });
  },
  getCodeReviewDgmGitlabProjectOptions() {
    return request<CodeReviewDgmGitlabProjectOptionResponse[]>(`${basePath}/dgm-gitlab-project-options`);
  },
};
