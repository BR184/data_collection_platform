import type {
  CodeReviewMatchModeCollectionOptionResponse,
  CodeReviewMatchModeConnectionTestResponse,
  CodeReviewMatchModeDbSettingsResponse,
  CodeReviewMatchModeDbSettingsSaveRequest,
  CodeReviewMatchModeSyncResponse,
  CodeReviewMatchModeTableOptionResponse,
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
};
