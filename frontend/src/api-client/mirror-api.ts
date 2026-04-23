import type {
  GitlabSyncConfig,
  GitlabWebhookRegistrationStatus,
  MirrorPurgeResult,
  MirrorPurgeScope,
  MirrorStatusResponse,
  SyncSubmissionResponse,
  TableWhitelistOption,
} from '../api';
import { request } from './request';

export const mirrorApi = {
  getStatus() {
    return request<MirrorStatusResponse>('/api/gitlab-sync/status');
  },
  getWebhookRegistrationStatus() {
    return request<GitlabWebhookRegistrationStatus>('/api/gitlab-sync/webhook-registration-status');
  },
  getWhitelistOptions() {
    return request<TableWhitelistOption[]>('/api/gitlab-sync/whitelist-options');
  },
  saveConfig(config: GitlabSyncConfig) {
    return request<GitlabSyncConfig>('/api/gitlab-sync/config', {
      method: 'PUT',
      body: JSON.stringify(config),
    });
  },
  testConnection() {
    return request<{ success: boolean; message: string }>('/api/gitlab-sync/test-connection', {
      method: 'POST',
    });
  },
  startFullSync() {
    return request<SyncSubmissionResponse>('/api/gitlab-sync/full-sync', {
      method: 'POST',
    });
  },
  startIncrementalSync() {
    return request<SyncSubmissionResponse>('/api/gitlab-sync/incremental-sync', {
      method: 'POST',
    });
  },
  registerWebhook() {
    return request<GitlabWebhookRegistrationStatus>('/api/gitlab-sync/register-webhook', {
      method: 'POST',
    });
  },
  cancelSync() {
    return request<{ accepted: boolean; taskId?: number; status?: string }>('/api/gitlab-sync/cancel', {
      method: 'POST',
    });
  },
  purgeMirrorData(scope: MirrorPurgeScope) {
    return request<MirrorPurgeResult>('/api/gitlab-sync/purge', {
      method: 'POST',
      body: JSON.stringify({ scope }),
    });
  },
};
