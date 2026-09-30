import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus, { ElMessageBox } from 'element-plus';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createRouter, createWebHashHistory } from 'vue-router';
import MirrorSettingsView from './MirrorSettingsView.vue';

const WRITEBACK_HELP_TEXT = '关闭时仍会监控延期事实，不会调用 GitLab API 写标签。';
const WRITEBACK_ENABLED_ALERT = '已开启：保存配置后平台会真实调用 GitLab API';

function jsonResponse(data: unknown) {
  return Promise.resolve({
    ok: true,
    text: () => Promise.resolve(JSON.stringify({ success: true, data })),
  } as Response);
}

function baseConfig() {
  return {
    id: 1,
    name: 'GitLab default source',
    enabled: true,
    sourceEnabled: true,
    sourceInstance: 'default',
    webBaseUrl: 'http://127.0.0.1',
    apiToken: '',
    delayLabelWritebackEnabled: false,
    autoSyncEnabled: true,
    sourceMode: 'DOCKER',
    whitelistMode: 'RECOMMENDED',
    whitelistTables: [],
    syncThreadMode: 'FIXED',
    syncThreadValue: 2,
    maxSyncThreads: 16,
  };
}

function stubFetch() {
  const fetchMock = vi.fn((url: string) => {
    if (url.includes('/api/gitlab-sync/configs')) {
      return jsonResponse([baseConfig()]);
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
}

async function mountView() {
  const router = createRouter({
    history: createWebHashHistory(),
    routes: [{ path: '/mirror-settings', component: MirrorSettingsView }],
  });
  await router.push('/mirror-settings');
  await router.isReady();
  const wrapper = mount(
    { template: '<router-view />' },
    { attachTo: document.body, global: { plugins: [router, ElementPlus] } },
  );
  await flushPromises();
  await flushPromises();
  return wrapper;
}

function writebackSwitch(wrapper: ReturnType<typeof mount>) {
  const item = wrapper
    .findAll('.el-form-item')
    .find((node) => node.find('.el-form-item__label').text().includes('延期标签写回'));
  if (!item) {
    throw new Error('未找到「延期标签写回」表单项');
  }
  return item.find('.el-switch');
}

describe('MirrorSettingsView 延期标签写回开关', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('keeps the switch off when the operator cancels the enabling confirmation', async () => {
    stubFetch();
    const confirmSpy = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel' as never);
    const wrapper = await mountView();

    expect(writebackSwitch(wrapper).classes()).not.toContain('is-checked');
    await writebackSwitch(wrapper).trigger('click');
    await flushPromises();

    expect(confirmSpy).toHaveBeenCalledWith(
      expect.stringContaining('真实调用 GitLab API'),
      '开启延期标签写回',
      expect.objectContaining({ confirmButtonText: '确认开启' }),
    );
    expect(writebackSwitch(wrapper).classes()).not.toContain('is-checked');
    expect(wrapper.text()).toContain(WRITEBACK_HELP_TEXT);

    wrapper.unmount();
  });

  it('shows the standing warning after enabling and confirms before disabling', async () => {
    stubFetch();
    const confirmSpy = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never);
    const wrapper = await mountView();

    await writebackSwitch(wrapper).trigger('click');
    await flushPromises();

    expect(writebackSwitch(wrapper).classes()).toContain('is-checked');
    expect(wrapper.text()).toContain(WRITEBACK_ENABLED_ALERT);
    expect(wrapper.text()).not.toContain(WRITEBACK_HELP_TEXT);

    await writebackSwitch(wrapper).trigger('click');
    await flushPromises();

    expect(confirmSpy).toHaveBeenLastCalledWith(
      expect.stringContaining('已排队的写回任务会被跳过'),
      '关闭延期标签写回',
      expect.objectContaining({ confirmButtonText: '确认关闭' }),
    );
    expect(writebackSwitch(wrapper).classes()).not.toContain('is-checked');
    expect(wrapper.text()).toContain(WRITEBACK_HELP_TEXT);

    wrapper.unmount();
  });
});
