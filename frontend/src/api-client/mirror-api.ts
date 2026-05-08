import type {
  GitlabSyncConfig,
  GitlabSyncDiagnosticsResponse,
  GitlabSourceHealthResponse,
  GitlabWebhookRegistrationStatus,
  MirrorPurgeResult,
  MirrorPurgeScope,
  MirrorStatusResponse,
  SyncSubmissionResponse,
  TableWhitelistOption,
} from '../types/api';
import { request } from './request';

export const mirrorApi = {
  getConfigs() {
    return request<GitlabSyncConfig[]>('/api/gitlab-sync/configs');
  },
  getSourceHealth() {
    return request<GitlabSourceHealthResponse[]>('/api/gitlab-sync/source-health');
  },
  getStatus(configId?: number) {
    return request<MirrorStatusResponse>(withConfigId('/api/gitlab-sync/status', configId));
  },
  getWebhookRegistrationStatus(configId?: number) {
    return request<GitlabWebhookRegistrationStatus>(withConfigId('/api/gitlab-sync/webhook-registration-status', configId));
  },
  getWhitelistOptions(configId?: number) {
    return request<TableWhitelistOption[]>(withConfigId('/api/gitlab-sync/whitelist-options', configId));
  },
  saveConfig(config: GitlabSyncConfig) {
    return request<GitlabSyncConfig>('/api/gitlab-sync/config', {
      method: 'PUT',
      body: JSON.stringify(config),
    });
  },
  testConnection(configId?: number) {
    return request<{ success: boolean; message: string }>(withConfigId('/api/gitlab-sync/test-connection/by-config', configId), {
      method: 'POST',
    });
  },
  runDiagnostics(configId?: number) {
    return request<GitlabSyncDiagnosticsResponse>(withConfigId('/api/gitlab-sync/diagnostics/by-config', configId), {
      method: 'POST',
    });
  },
  startFullSync(configId?: number) {
    return request<SyncSubmissionResponse>(withConfigId('/api/gitlab-sync/full-sync/by-config', configId), {
      method: 'POST',
    });
  },
  startIncrementalSync(configId?: number) {
    return request<SyncSubmissionResponse>(withConfigId('/api/gitlab-sync/incremental-sync/by-config', configId), {
      method: 'POST',
    });
  },
  registerWebhook(configId?: number) {
    return request<GitlabWebhookRegistrationStatus>(withConfigId('/api/gitlab-sync/register-webhook/by-config', configId), {
      method: 'POST',
    });
  },
  cancelSync(configId?: number) {
    return request<{ accepted: boolean; taskId?: number; status?: string }>(withConfigId('/api/gitlab-sync/cancel/by-config', configId), {
      method: 'POST',
    });
  },
  purgeMirrorData(scope: MirrorPurgeScope, configId?: number) {
    return request<MirrorPurgeResult>('/api/gitlab-sync/purge', {
      method: 'POST',
      body: JSON.stringify({ scope, configId }),
    });
  },
};

function withConfigId(path: string, configId?: number) {
  return configId == null ? path : `${path}?configId=${encodeURIComponent(String(configId))}`;
}
