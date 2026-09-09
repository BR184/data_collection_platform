import { describe, expect, it, vi, beforeEach } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import BackupSettingsView from './BackupSettingsView.vue';

const mocks = vi.hoisted(() => ({
  getSettings: vi.fn(),
  getStatus: vi.fn(),
  listRuns: vi.fn(),
  testConnection: vi.fn(),
}));

vi.mock('../api-client/database-backup-api', () => ({
  databaseBackupApi: {
    getSettings: mocks.getSettings,
    getStatus: mocks.getStatus,
    listRuns: mocks.listRuns,
    saveSettings: vi.fn(),
    testConnection: mocks.testConnection,
    triggerRun: vi.fn(),
  },
}));

vi.mock('../composables/auth-state', () => ({
  authState: {
    initialized: true,
    currentUser: {
      authenticated: true,
      permissions: ['system.backup.view', 'system.backup.manage'],
    },
  },
}));

const localSettings = {
  enabled: false,
  scheduleTime: '03:00',
  retentionCopies: 14,
  storageMode: 'LOCAL',
  localSubdirectory: null,
  remoteHost: null,
  remotePort: 22,
  remoteUsername: null,
  hasRemotePassword: false,
  remoteDirectory: null,
  remoteHostKeyFingerprint: null,
  version: 0,
  updatedAt: null,
  localRootEffective: '/var/lib/qaflex/backups/testinst',
  secretKeyConfigured: true,
  instanceLabel: 'testinst',
};

const emptyStatus = {
  running: false,
  currentRun: null,
  lastCompleted: null,
  enabled: false,
  scheduleTime: '03:00',
  nextRunAt: null,
};

const emptyHistory = { total: 0, page: 1, size: 10, records: [] };

async function mountView() {
  const wrapper = mount(BackupSettingsView, {
    global: { plugins: [ElementPlus] },
  });
  await flushPromises();
  return wrapper;
}

describe('BackupSettingsView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.getSettings.mockResolvedValue(localSettings);
    mocks.getStatus.mockResolvedValue(emptyStatus);
    mocks.listRuns.mockResolvedValue(emptyHistory);
  });

  it('renders local mode guidance and hides connection test for local storage', async () => {
    const wrapper = await mountView();

    expect(wrapper.text()).toContain('备份管理');
    expect(wrapper.text()).toContain('/var/lib/qaflex/backups/testinst');
    expect(wrapper.text()).toContain('本机存储模式无需远程连接测试');
    const testButton = wrapper
      .findAll('button')
      .find((button) => button.text() === '测试连接');
    expect(testButton).toBeUndefined();

    wrapper.unmount();
  });

  it('keeps stored remote password hidden and offers keep-empty semantics', async () => {
    mocks.getSettings.mockResolvedValue({
      ...localSettings,
      storageMode: 'REMOTE',
      remoteHost: '192.168.1.10',
      remotePort: 22,
      remoteUsername: 'oper',
      hasRemotePassword: true,
      remoteDirectory: '/data/backups',
    });

    const wrapper = await mountView();
    const passwordInput = wrapper.find('input[type="password"]');

    expect(passwordInput.exists()).toBe(true);
    expect((passwordInput.element as HTMLInputElement).value).toBe('');

    wrapper.unmount();
  });

  it('shows check results and adopts the actual fingerprint on mismatch', async () => {
    mocks.getSettings.mockResolvedValue({
      ...localSettings,
      storageMode: 'REMOTE',
      remoteHost: '192.168.1.10',
      remoteUsername: 'oper',
      remoteDirectory: '/data/backups',
    });
    mocks.testConnection.mockResolvedValue({
      ok: false,
      checks: [
        { name: 'SSH 登录与指纹', passed: false, message: '主机密钥指纹与已保存配置不一致' },
        { name: '目录写探针', passed: false, message: '未执行' },
        { name: '磁盘余量', passed: false, message: '未执行' },
      ],
      actualFingerprint: 'SHA256:actual-key',
    });

    const wrapper = await mountView();
    const testButton = wrapper
      .findAll('button')
      .find((button) => button.text().includes('测试连接'));
    await testButton!.trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('主机密钥指纹与已保存配置不一致');

    const adoptButton = wrapper
      .findAll('button')
      .find((button) => button.text().includes('采纳实际指纹'));
    await adoptButton!.trigger('click');
    await flushPromises();

    const fingerprintInput = wrapper
      .findAll('input')
      .find((input) => (input.element as HTMLInputElement).value === 'SHA256:actual-key');
    expect(fingerprintInput).toBeDefined();

    wrapper.unmount();
  });

  it('renders history rows with status label and file names', async () => {
    mocks.listRuns.mockResolvedValue({
      total: 1,
      page: 1,
      size: 10,
      records: [
        {
          id: 7,
          triggerType: 'SCHEDULE',
          status: 'SUCCESS',
          storageMode: 'LOCAL',
          stage: 'RETENTION',
          targetPath: '/var/lib/qaflex/backups/testinst/qaflex_testinst_20260910-030000.dump',
          fileName: 'qaflex_testinst_20260910-030000.dump',
          fileBytes: 2048,
          sha256: 'abc123',
          pgServerVersion: '16.4',
          flywayVersion: '20260903.01',
          startedAt: '2026-09-10T03:00:00Z',
          finishedAt: '2026-09-10T03:01:30Z',
          durationMs: 90_000,
          errorMessage: null,
        },
      ],
    });

    const wrapper = await mountView();

    expect(wrapper.text()).toContain('qaflex_testinst_20260910-030000.dump');
    expect(wrapper.text()).toContain('成功');
    expect(wrapper.text()).toContain('定时');
    expect(wrapper.text()).toContain('2.0 KiB');

    wrapper.unmount();
  });

  it('shows the last completed failure reason reference when idle', async () => {
    mocks.getStatus.mockResolvedValue({
      ...emptyStatus,
      lastCompleted: {
        id: 6,
        triggerType: 'MANUAL',
        status: 'FAILED',
        storageMode: 'LOCAL',
        stage: 'DUMP',
        targetPath: null,
        fileName: null,
        fileBytes: null,
        sha256: null,
        pgServerVersion: null,
        flywayVersion: null,
        startedAt: '2026-09-09T19:00:00Z',
        finishedAt: '2026-09-09T19:00:40Z',
        durationMs: 40_000,
        errorMessage: 'pg_dump 导出失败（退出码 3）',
      },
    });

    const wrapper = await mountView();

    expect(wrapper.text()).toContain('失败');
    // 失败原因绑定在 tooltip 上（悬停可见），空闲态提供"失败原因"提示入口。
    expect(wrapper.text()).toContain('失败原因');

    wrapper.unmount();
  });
});
