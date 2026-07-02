import type {
  CodeReviewMatchModeConnectionTestResponse,
  CodeReviewMatchModeDbSettingsResponse,
  CodeReviewMatchModeDbSettingsSaveRequest,
  CodeReviewMatchModeSyncResponse,
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
  syncCodeReviewMatchModeDbNow() {
    return request<CodeReviewMatchModeSyncResponse>(`${basePath}/sync-now`, {
      method: 'POST',
      timeoutMs: 180_000,
    });
  },
};
