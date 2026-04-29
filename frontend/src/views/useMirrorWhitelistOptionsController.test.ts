import { describe, expect, it, vi } from 'vitest';
import { nextTick, ref } from 'vue';
import { useMirrorWhitelistOptionsController } from './useMirrorWhitelistOptionsController';
import type { GitlabSyncConfig, TableWhitelistOption } from '../types/api';

function createConfig(overrides: Partial<GitlabSyncConfig> = {}): GitlabSyncConfig {
  return {
    name: 'GitLab 默认数据源',
    enabled: true,
    autoSyncEnabled: true,
    sourceMode: 'DOCKER',
    whitelistMode: 'RECOMMENDED',
    whitelistTables: [],
    dbHost: 'localhost',
    dbPort: 5432,
    dbName: 'gitlabhq_production',
    dbUsername: 'gitlab',
    dbPassword: '',
    dockerContainerName: 'gitlab-data-web-1',
    webhookSecret: '',
    webhookProjectId: null,
    compensationIntervalMinutes: 10,
    ...overrides,
  };
}

function createOption(overrides: Partial<TableWhitelistOption> = {}): TableWhitelistOption {
  return {
    tableName: 'issues',
    label: 'Issues',
    primaryKey: 'id',
    updatedAtColumn: 'updated_at',
    recommended: true,
    ...overrides,
  };
}

function setup() {
  return {
    form: ref(createConfig()),
    loadWhitelistOptions: vi.fn<() => Promise<TableWhitelistOption[]>>(() =>
      Promise.resolve([
        createOption(),
        createOption({ tableName: 'merge_requests', label: 'Merge Requests', recommended: false }),
      ]),
    ),
    notifyError: vi.fn<(message: string) => void>(),
  };
}

describe('useMirrorWhitelistOptionsController', () => {
  it('loads whitelist options once and exposes select options plus recommendation count', async () => {
    const deps = setup();
    const controller = useMirrorWhitelistOptionsController(deps);

    await controller.ensureWhitelistOptions();
    await controller.ensureWhitelistOptions();

    expect(deps.loadWhitelistOptions).toHaveBeenCalledOnce();
    expect(controller.whitelistOptionsLoaded.value).toBe(true);
    expect(controller.whitelistOptionsLoading.value).toBe(false);
    expect(controller.recommendedCount.value).toBe(1);
    expect(controller.whitelistSelectOptions.value).toEqual([
      { label: 'Issues (issues)', value: 'issues' },
      { label: 'Merge Requests (merge_requests)', value: 'merge_requests' },
    ]);
  });

  it('reloads options when force is requested', async () => {
    const deps = setup();
    const controller = useMirrorWhitelistOptionsController(deps);

    await controller.ensureWhitelistOptions();
    await controller.ensureWhitelistOptions(true);

    expect(deps.loadWhitelistOptions).toHaveBeenCalledTimes(2);
  });

  it('loads options when whitelist mode switches to custom', async () => {
    const deps = setup();
    const controller = useMirrorWhitelistOptionsController(deps);

    deps.form.value.whitelistMode = 'CUSTOM';
    await nextTick();
    await nextTick();

    expect(deps.loadWhitelistOptions).toHaveBeenCalledOnce();
    expect(controller.whitelistOptionsLoaded.value).toBe(true);
  });

  it('reports load errors and clears loading state', async () => {
    const deps = setup();
    deps.loadWhitelistOptions.mockRejectedValueOnce(new Error('白名单加载失败'));
    const controller = useMirrorWhitelistOptionsController(deps);

    await controller.ensureWhitelistOptions();

    expect(deps.notifyError).toHaveBeenCalledWith('白名单加载失败');
    expect(controller.whitelistOptionsLoaded.value).toBe(false);
    expect(controller.whitelistOptionsLoading.value).toBe(false);
  });
});
