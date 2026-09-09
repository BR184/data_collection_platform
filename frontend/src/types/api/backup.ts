export type BackupStorageMode = 'LOCAL' | 'REMOTE';

export interface BackupSettingsResponse {
  enabled: boolean;
  scheduleTime: string;
  retentionCopies: number;
  storageMode: BackupStorageMode;
  localSubdirectory: string | null;
  remoteHost: string;
  remotePort: number;
  remoteUsername: string;
  hasRemotePassword: boolean;
  remoteDirectory: string;
  remoteHostKeyFingerprint: string | null;
  version: number;
  updatedAt: string | null;
  localRootEffective: string;
  secretKeyConfigured: boolean;
  instanceLabel: string;
}

export interface BackupSettingsSaveRequest {
  enabled: boolean;
  scheduleTime: string;
  retentionCopies: number;
  storageMode: BackupStorageMode;
  localSubdirectory: string | null;
  remoteHost: string;
  remotePort: number;
  remoteUsername: string;
  /** 留空 = 保留已存密码；后端永不回显密码明文。 */
  remotePassword: string;
  remoteDirectory: string;
  remoteHostKeyFingerprint: string;
  version: number;
}

export interface BackupConnectionTestRequest {
  remoteHost: string;
  remotePort: number;
  remoteUsername: string;
  remotePassword: string;
  remoteDirectory: string;
  remoteHostKeyFingerprint: string;
}

export interface BackupConnectionCheckResult {
  name: string;
  passed: boolean;
  message: string;
}

export interface BackupConnectionTestResponse {
  ok: boolean;
  checks: BackupConnectionCheckResult[];
  /** 实际采集到的主机密钥指纹（期望指纹不匹配时返回，供页面一键采纳后重新保存）。 */
  actualFingerprint?: string | null;
}

export interface BackupTriggerResponse {
  accepted: boolean;
  runId: number | null;
  message: string;
}

export interface BackupRunResponse {
  id: number;
  triggerType: 'MANUAL' | 'SCHEDULE';
  status: 'RUNNING' | 'SUCCESS' | 'FAILED';
  storageMode: BackupStorageMode;
  stage: string;
  targetPath: string | null;
  fileName: string | null;
  fileBytes: number | null;
  sha256: string | null;
  pgServerVersion: string | null;
  flywayVersion: string | null;
  startedAt: string;
  finishedAt: string | null;
  durationMs: number | null;
  errorMessage: string | null;
}

export interface BackupStatusResponse {
  running: boolean;
  currentRun: BackupRunResponse | null;
  lastCompleted: BackupRunResponse | null;
  enabled: boolean;
  scheduleTime: string;
  nextRunAt: string | null;
}

export interface BackupRunsPageResponse {
  total: number;
  page: number;
  size: number;
  records: BackupRunResponse[];
}
