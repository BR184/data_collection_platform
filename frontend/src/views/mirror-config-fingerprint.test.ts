import { describe, expect, it } from 'vitest';
import type { GitlabSyncConfig } from '../types/api';
import { formSnapshot, normalizeFingerprintPart } from './mirror-config-fingerprint';

function createConfig(overrides: Partial<GitlabSyncConfig> = {}): GitlabSyncConfig {
  return {
    id: 1,
    name: 'GitLab default source',
    enabled: true,
    sourceEnabled: true,
    sourceInstance: 'default',
    webBaseUrl: '',
    apiToken: '',
    delayLabelWritebackEnabled: false,
    matchModeEnabled: true,
    autoSyncEnabled: true,
    sourceMode: 'DOCKER',
    whitelistMode: 'RECOMMENDED',
    whitelistTables: ['issues', 'merge_requests'],
    dbHost: 'localhost',
    dbPort: 5432,
    dbName: 'gitlabhq_production',
    dbUsername: 'gitlab',
    dbPassword: '',
    dockerContainerName: 'gitlab-data-web-1',
    systemHookSecret: '',
    systemHookEnabled: false,
    systemHookProjectId: null,
    compensationIntervalMinutes: 360,
    compensationScheduleMode: 'INTERVAL',
    compensationTime: '03:30',
    compensationWindowStart: null,
    compensationWindowEnd: null,
    compensationMissedWindowPolicy: 'SKIP',
    fullCompensationEnabled: true,
    fullCompensationTime: '02:00',
    syncThreadMode: 'FIXED',
    syncThreadValue: 2,
    maxSyncThreads: 16,
    ...overrides,
  };
}

describe('mirror-config-fingerprint', () => {
  it('normalizes connection strings and sorts whitelist tables without mutating input', () => {
    const config = createConfig({
      sourceInstance: '  DEFAULT ',
      dbHost: '  DB-HOST ',
      dbName: ' GitLabHQ_PRODUCTION ',
      dbUsername: ' GITLAB ',
      whitelistTables: ['merge_requests', 'issues'],
    });
    const sameConfig = createConfig({
      sourceInstance: 'default',
      dbHost: 'db-host',
      dbName: 'gitlabhq_production',
      dbUsername: 'gitlab',
      whitelistTables: ['issues', 'merge_requests'],
    });

    expect(normalizeFingerprintPart('  MiXeD  ')).toBe('mixed');
    expect(formSnapshot(config)).toBe(formSnapshot(sameConfig));
    expect(config.whitelistTables).toEqual(['merge_requests', 'issues']);
  });

  it('applies stable defaults and changes when a persisted setting changes', () => {
    const base = createConfig({
      sourceEnabled: undefined,
      sourceInstance: '',
      dbPort: undefined as unknown as number,
      compensationScheduleMode: undefined,
      compensationTime: undefined,
      fullCompensationEnabled: undefined,
      syncThreadValue: undefined as unknown as number,
    });
    const explicitDefaults = createConfig({
      sourceEnabled: true,
      sourceInstance: 'default',
      dbPort: 5432,
      compensationScheduleMode: 'INTERVAL',
      compensationTime: '03:30',
      fullCompensationEnabled: true,
      syncThreadValue: 2,
    });

    expect(formSnapshot(base)).toBe(formSnapshot(explicitDefaults));
    expect(formSnapshot(base)).not.toBe(formSnapshot({ ...base, dbPort: 5433 }));
  });
});
