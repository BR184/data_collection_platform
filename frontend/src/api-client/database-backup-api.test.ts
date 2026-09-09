import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { BackupSettingsSaveRequest } from '../types/api';
import { databaseBackupApi } from './database-backup-api';

vi.mock('./request', () => ({
  request: vi.fn(() => Promise.resolve({})),
}));

import { request } from './request';

describe('databaseBackupApi', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('reads settings without query', () => {
    databaseBackupApi.getSettings();

    expect(request).toHaveBeenCalledWith('/api/database-backup/settings');
  });

  it('saves settings via PUT payload', () => {
    const payload: BackupSettingsSaveRequest = {
      enabled: true,
      scheduleTime: '03:30',
      retentionCopies: 14,
      storageMode: 'LOCAL',
      localSubdirectory: null,
      remoteHost: '',
      remotePort: 22,
      remoteUsername: '',
      remotePassword: '',
      remoteDirectory: '',
      remoteHostKeyFingerprint: '',
      version: 2,
    };

    databaseBackupApi.saveSettings(payload);

    expect(request).toHaveBeenCalledWith('/api/database-backup/settings', {
      method: 'PUT',
      body: JSON.stringify(payload),
    });
  });

  it('tests connection via POST payload without triggering a run', () => {
    const payload = {
      remoteHost: '192.168.1.10',
      remotePort: 22,
      remoteUsername: 'oper',
      remotePassword: 's3cret',
      remoteDirectory: '/data/backups',
      remoteHostKeyFingerprint: '',
    };

    databaseBackupApi.testConnection(payload);

    expect(request).toHaveBeenCalledWith('/api/database-backup/test-connection', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  });

  it('triggers run via POST without body', () => {
    databaseBackupApi.triggerRun();

    expect(request).toHaveBeenCalledWith('/api/database-backup/runs', { method: 'POST' });
  });

  it('reads status without query', () => {
    databaseBackupApi.getStatus();

    expect(request).toHaveBeenCalledWith('/api/database-backup/status');
  });

  it('lists runs with page and size query', () => {
    databaseBackupApi.listRuns(3, 20);

    expect(request).toHaveBeenCalledWith('/api/database-backup/runs?page=3&size=20');
  });
});
