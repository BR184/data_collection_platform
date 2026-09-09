import type {
  BackupConnectionTestRequest,
  BackupConnectionTestResponse,
  BackupRunsPageResponse,
  BackupSettingsResponse,
  BackupSettingsSaveRequest,
  BackupStatusResponse,
  BackupTriggerResponse,
} from '../types/api';
import { request } from './request';

/** 数据库备份管理 API：配置读写、连接测试（纯只读三查）、手动触发、状态与历史。 */
export const databaseBackupApi = {
  getSettings() {
    return request<BackupSettingsResponse>('/api/database-backup/settings');
  },
  saveSettings(payload: BackupSettingsSaveRequest) {
    return request<BackupSettingsResponse>('/api/database-backup/settings', {
      method: 'PUT',
      body: JSON.stringify(payload),
    });
  },
  testConnection(payload: BackupConnectionTestRequest) {
    return request<BackupConnectionTestResponse>('/api/database-backup/test-connection', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },
  triggerRun() {
    return request<BackupTriggerResponse>('/api/database-backup/runs', { method: 'POST' });
  },
  getStatus() {
    return request<BackupStatusResponse>('/api/database-backup/status');
  },
  listRuns(page: number, size: number) {
    const query = new URLSearchParams({ page: String(page), size: String(size) });
    return request<BackupRunsPageResponse>(`/api/database-backup/runs?${query.toString()}`);
  },
};
