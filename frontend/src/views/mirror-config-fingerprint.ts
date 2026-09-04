import type { GitlabSyncConfig } from '../types/api';

/**
 * 归一化配置指纹中的字符串字段，消除首尾空白和大小写差异。
 */
export function normalizeFingerprintPart(value: string | number | null | undefined): string {
  return String(value ?? '').trim().toLowerCase();
}

/**
 * 生成用于判断镜像配置是否有未保存修改的稳定指纹。
 */
export function formSnapshot(config: GitlabSyncConfig): string {
  return JSON.stringify({
    id: config.id ?? null,
    name: config.name ?? '',
    enabled: Boolean(config.sourceEnabled ?? config.enabled),
    sourceEnabled: Boolean(config.sourceEnabled ?? config.enabled),
    sourceInstance: normalizeFingerprintPart(config.sourceInstance) || 'default',
    autoSyncEnabled: Boolean(config.autoSyncEnabled),
    sourceMode: config.sourceMode ?? 'DOCKER',
    whitelistMode: config.whitelistMode ?? 'RECOMMENDED',
    whitelistTables: [...(config.whitelistTables ?? [])].sort(),
    dbHost: normalizeFingerprintPart(config.dbHost),
    dbPort: Number(config.dbPort ?? 5432),
    dbName: normalizeFingerprintPart(config.dbName),
    dbUsername: normalizeFingerprintPart(config.dbUsername),
    dbPassword: config.dbPassword ?? '',
    apiToken: config.apiToken ?? '',
    delayLabelWritebackEnabled: Boolean(config.delayLabelWritebackEnabled),
    matchModeEnabled: config.matchModeEnabled ?? true,
    dockerContainerName: normalizeFingerprintPart(config.dockerContainerName),
    systemHookSecret: config.systemHookSecret ?? '',
    systemHookEnabled: Boolean(config.systemHookEnabled),
    systemHookProjectId: config.systemHookProjectId ?? null,
    compensationIntervalMinutes: Number(config.compensationIntervalMinutes ?? 360),
    compensationScheduleMode: config.compensationScheduleMode ?? 'INTERVAL',
    compensationTime: config.compensationTime ?? '03:30',
    compensationWindowStart: config.compensationWindowStart ?? null,
    compensationWindowEnd: config.compensationWindowEnd ?? null,
    compensationMissedWindowPolicy: config.compensationMissedWindowPolicy ?? 'SKIP',
    fullCompensationEnabled: config.fullCompensationEnabled ?? true,
    fullCompensationTime: config.fullCompensationTime ?? '02:00',
    syncThreadMode: config.syncThreadMode ?? 'FIXED',
    syncThreadValue: Number(config.syncThreadValue ?? 2),
    maxSyncThreads: Number(config.maxSyncThreads ?? 16),
  });
}
