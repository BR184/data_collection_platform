import type {
  GitlabSyncConfig,
  GitlabSyncDiagnosticsResponse,
  GitlabSourceHealthResponse,
  SyncRunDiagnosticsResponse,
  GitlabSystemHookRegistrationStatus,
  MirrorPurgeResult,
  MirrorPurgeScope,
  MirrorStatusResponse,
  SyncRunLogDetailSection,
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
  /**
   * 按运行编号分页读取可在界面展开的全部定位项或相关事件。
   *
   * 服务端用与状态查询相同的配置/来源归属校验；旧运行只要记录仍在即可按其 runId 查询，
   * 不受最近日志条数限制。`section` 取 `DIAGNOSTICS` 或 `EVENTS`。
   */
  getRunLogDetails(
    configId: number | undefined,
    runId: number | string,
    section: SyncRunLogDetailSection,
    offset = 0,
    limit = 20,
  ) {
    const query = new URLSearchParams({
      detailsRunId: String(runId),
      detailsSection: section,
      detailsOffset: String(offset),
      detailsLimit: String(limit),
    });
    if (configId != null) {
      query.set('configId', String(configId));
    }
    return request<MirrorStatusResponse>(`/api/gitlab-sync/status?${query.toString()}`);
  },
  getSystemHookRegistrationStatus(configId?: number) {
    return request<GitlabSystemHookRegistrationStatus>(withConfigId('/api/gitlab-sync/system-hook-registration-status', configId));
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
  getTableSyncDiagnostics(configId?: number) {
    return request<SyncRunDiagnosticsResponse>(
      withConfigId('/api/gitlab-sync/table-sync-diagnostics', configId),
    );
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
  startFullCompensationSync(configId?: number) {
    return request<SyncSubmissionResponse>(withConfigId('/api/gitlab-sync/full-compensation-sync/by-config', configId), {
      method: 'POST',
    });
  },
  retryFailedSync(configId?: number) {
    return request<SyncSubmissionResponse>(withConfigId('/api/gitlab-sync/retry-failed/by-config', configId), {
      method: 'POST',
    });
  },
  registerSystemHook(configId?: number) {
    return request<GitlabSystemHookRegistrationStatus>(withConfigId('/api/gitlab-sync/register-system-hook/by-config', configId), {
      method: 'POST',
    });
  },
  cancelSync(configId?: number) {
    return request<{ accepted: boolean; runId?: number; status?: string; message?: string }>(withConfigId('/api/gitlab-sync/cancel/by-config', configId), {
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
