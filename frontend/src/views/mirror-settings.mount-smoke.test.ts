import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus, { ElMessageBox } from 'element-plus';
import { describe, expect, it, vi } from 'vitest';
import MirrorSettingsView from './MirrorSettingsView.vue';

function jsonResponse(data: unknown) {
  return Promise.resolve({
    ok: true,
    text: () => Promise.resolve(JSON.stringify({ success: true, data })),
  } as Response);
}

function baseConfig(id = 1, sourceInstance = 'default') {
  return {
    id,
    name: id === 1 ? 'GitLab default source' : 'CC duplicate source',
    enabled: true,
    sourceEnabled: true,
    sourceInstance,
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
    systemHookSecret: '',
    systemHookEnabled: false,
    systemHookProjectId: null,
    compensationIntervalMinutes: 360,
    syncThreadMode: 'FIXED',
    syncThreadValue: 2,
    maxSyncThreads: 16,
  };
}

describe('MirrorSettingsView mount smoke', () => {
  it('shows translated purge logs and keeps the purge dialog locked while deleting', async () => {
    let resolvePurgeResponse: ((value: Response) => void) | null = null;
    const purgeResponsePromise = new Promise<Response>((resolve) => {
      resolvePurgeResponse = resolve;
    });

    const fetchMock = vi.fn((url: string) => {
      if (url.includes('/api/gitlab-sync/configs')) {
        return jsonResponse([baseConfig(), baseConfig(2, 'cc')]);
      }
      if (url.includes('/api/gitlab-sync/status')) {
        return jsonResponse({
          config: baseConfig(),
          currentTask: {
            id: 201,
            runId: 'task-201',
            taskType: 'FULL',
            triggerType: 'MANUAL',
            sourceMode: 'DOCKER',
            scopeKey: 'scope',
            dedupeKey: 'dedupe',
            status: 'SUCCESS',
            cancelRequested: false,
            pendingResync: false,
            retryCount: 0,
            cooldownUntil: null,
            heartbeatAt: null,
            queuedAt: null,
            runAfter: null,
            startedAt: '2026-04-27T10:00:00',
            finishedAt: '2026-04-27T10:00:12',
            finishedReason: 'Sync completed successfully',
            lockOwner: null,
            payloadJson: null,
          },
          currentStatus: 'SUCCESS',
          currentMessage: 'Sync completed successfully',
          currentStartedAt: null,
          progress: null,
          logs: [
            {
              id: 101,
              syncType: 'PURGE',
              status: 'SUCCESS',
              message: 'Delete mirror data',
              tableCount: 5,
              recordCount: 0,
              startedAt: '2026-04-27T10:00:00',
              finishedAt: '2026-04-27T10:00:12',
            },
          ],
          systemHookUrl: 'http://localhost:18080/api/gitlab-sync/system-hook',
          systemHookRegistration: null,
          availableProcessors: 16,
          resolvedSyncThreads: 2,
        });
      }
      if (url.includes('/api/gitlab-sync/system-hook-registration-status')) {
        return jsonResponse({
          supported: true,
          configured: true,
          registered: true,
          projectId: 1,
          systemHookUrl: 'http://localhost:18080/api/gitlab-sync/system-hook',
          message: 'GitLab System Hook 已注册',
          hooks: [],
        });
      }
      if (url.includes('/api/gitlab-sync/table-sync-diagnostics')) {
        return jsonResponse({
          configId: 1,
          sourceInstance: 'default',
          generatedAt: '2026-04-27T10:00:00',
          tableCount: 1,
          dirtyTableCount: 0,
          pendingTaskCount: 0,
          runningTaskCount: 0,
          retryingTaskCount: 0,
          failedTaskCount: 0,
          timedOutTaskCount: 0,
          tables: [
            {
              sourceTable: 'issues',
              mirrorTable: 'ods_gitlab_issues',
              primaryKeyColumns: 'id',
              updatedAtColumn: 'updated_at',
              rowStrategy: 'INCREMENTAL',
              syncEnabled: true,
              dirty: true,
              dirtyReason: 'row_count_drift',
              blockingRunId: 'sr_full_alpha',
              lastVerifiedAt: '2026-04-27T09:58:00',
              lastAppliedAt: '2026-04-27T09:59:00',
              lastWatermarkAt: '2026-04-27T09:59:00',
              sourceRows: 120,
              mirrorRows: 100,
              driftSummary: 'source=120, mirror=100, delta=20',
              latestTaskStatus: 'RUNNING',
            },
          ],
        });
      }
      if (url.includes('/api/gitlab-sync/purge')) {
        return purgeResponsePromise;
      }
      return jsonResponse({});
    });

    vi.stubGlobal('fetch', fetchMock);
    const alertSpy = vi.spyOn(ElMessageBox, 'alert').mockResolvedValue('confirm' as never);

    const wrapper = mount(MirrorSettingsView, {
      attachTo: document.body,
      global: {
        plugins: [ElementPlus],
      },
    });

    await flushPromises();
    await flushPromises();

    expect(wrapper.find('.sync-log-table-shell').exists()).toBe(true);
    expect(wrapper.text()).toContain('固定线程数');
    expect(wrapper.text()).toContain('预计本次配置会使用');
    expect(wrapper.text()).toContain('数据镜像监控');
    expect(wrapper.text()).toContain('表任务状态');
    expect(wrapper.text()).toContain('sr_full_alpha');
    expect(wrapper.text()).not.toContain('Sync completed successfully');

    const openDialogButton = wrapper.findAll('button').find((button) => button.text().includes('删除镜像数据'));
    expect(openDialogButton).toBeTruthy();
    await openDialogButton!.trigger('click');
    await flushPromises();

    const confirmInput = wrapper.get('.purge-confirm-panel input');
    await confirmInput.setValue('删除镜像数据');
    await flushPromises();

    const confirmButton = [...document.body.querySelectorAll('button')].find((button) =>
      button.textContent?.includes('确认删除'),
    ) as HTMLButtonElement | undefined;
    expect(confirmButton).toBeTruthy();
    confirmButton!.click();
    await flushPromises();

    const scopeInputs = [...document.body.querySelectorAll('.purge-scope-card input')] as HTMLInputElement[];
    expect(scopeInputs).toHaveLength(2);
    expect(scopeInputs.every((input) => input.disabled)).toBe(true);
    expect((document.body.querySelector('.purge-confirm-panel input') as HTMLInputElement).disabled).toBe(true);

    resolvePurgeResponse!(
      {
        ok: true,
        text: () =>
          Promise.resolve(
            JSON.stringify({
              success: true,
              data: {
                scope: 'MIRROR_DATA_ONLY',
                droppedMirrorTables: 3,
                droppedTableNames: ['ods_gitlab_issues'],
                truncatedTables: 2,
                truncatedTableNames: ['sys_table_registry', 'gitlab_mirror_records'],
                syncTimestampsReset: true,
              },
            }),
          ),
      } as Response,
    );
    await flushPromises();
    await flushPromises();

    expect(alertSpy).toHaveBeenCalledWith(
      expect.stringContaining('删除镜像表：3'),
      '删除完成',
      expect.objectContaining({ type: 'success' }),
    );
    expect(fetchMock.mock.calls.filter(([url]) => String(url).includes('/api/gitlab-sync/status')).length).toBeGreaterThanOrEqual(2);

    wrapper.unmount();
    alertSpy.mockRestore();
    vi.unstubAllGlobals();
  });

  it('enters the settings page when local diagnostics fail during initialization', async () => {
    const fetchMock = vi.fn((url: string) => {
      if (url.includes('/api/gitlab-sync/configs')) {
        return jsonResponse([baseConfig()]);
      }
      if (url.includes('/api/gitlab-sync/source-health') || url.includes('/api/gitlab-sync/table-sync-diagnostics')) {
        return Promise.resolve({
          ok: false,
          text: () => Promise.resolve(JSON.stringify({ success: false, message: '诊断接口暂不可用' })),
        } as Response);
      }
      if (url.includes('/api/gitlab-sync/status')) {
        return jsonResponse({
          config: baseConfig(),
          currentTask: null,
          currentStatus: 'IDLE',
          currentMessage: '',
          currentStartedAt: null,
          progress: null,
          logs: [],
          systemHookUrl: 'http://localhost:18080/api/gitlab-sync/system-hook',
          systemHookRegistration: null,
          availableProcessors: 16,
          resolvedSyncThreads: 2,
        });
      }
      if (url.includes('/api/gitlab-sync/system-hook-registration-status')) {
        return jsonResponse({
          supported: false,
          configured: false,
          registered: false,
          projectId: null,
          systemHookUrl: 'http://localhost:18080/api/gitlab-sync/system-hook',
          message: '未检测',
          hooks: [],
        });
      }
      return jsonResponse({});
    });
    vi.stubGlobal('fetch', fetchMock);
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});

    const wrapper = mount(MirrorSettingsView, {
      attachTo: document.body,
      global: {
        plugins: [ElementPlus],
      },
    });

    await flushPromises();
    await flushPromises();

    expect(wrapper.text()).toContain('GitLab 数据镜像设置');
    expect(wrapper.text()).toContain('GitLab default source');
    expect(wrapper.text()).toContain('暂无当前数据源诊断信息');
    expect(warnSpy).toHaveBeenCalledWith('数据源健康状态 加载失败', expect.any(Error));

    wrapper.unmount();
    warnSpy.mockRestore();
    vi.unstubAllGlobals();
  });
});
