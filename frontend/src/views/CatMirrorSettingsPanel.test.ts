import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { authState } from '../composables/auth-state';
import type { CatMirrorSettings } from '../types/api';
import CatMirrorSettingsPanel from './CatMirrorSettingsPanel.vue';

function settings(enabled = false): CatMirrorSettings {
  return {
    config: {
      enabled,
      baseUrl: 'http://172.22.10.56:88',
      autoSyncEnabled: true,
      syncIntervalMinutes: 20,
      fullCompensationEnabled: true,
      fullCompensationTime: '02:00:00',
    },
    productVersions: [
      { id: 10, businessKey: 'CC2026R4', displayName: 'CC 2026 R4', sortOrder: 1 },
    ],
    mappings: [],
    mappingSuggestions: [],
    catalog: null,
    recentRuns: [],
    synchronizing: false,
  };
}

function respond(data: CatMirrorSettings) {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
    ok: true,
    text: () => Promise.resolve(JSON.stringify({ success: true, data })),
  } as Response));
}

function setPermissions(permissions: string[]) {
  authState.currentUser = {
    username: 'admin',
    displayName: '管理员',
    roleCodes: ['ADMIN'],
    roleNames: ['管理员'],
    permissions,
    authenticated: true,
  };
}

function button(wrapper: VueWrapper, label: string) {
  const found = wrapper.findAll('button').find((item) => item.text().includes(label));
  if (!found) throw new Error(`未找到按钮：${label}`);
  return found;
}

afterEach(() => {
  vi.unstubAllGlobals();
  authState.currentUser = {
    username: 'guest',
    displayName: '游客',
    roleCodes: [],
    roleNames: [],
    permissions: [],
    authenticated: false,
  };
  document.body.innerHTML = '';
});

describe('CatMirrorSettingsPanel', () => {
  it('emptyCatalog_showsFirstSyncGuidanceAndKeepsDisabledConfigActionsStable', async () => {
    setPermissions(['system.mirror.config', 'system.mirror.sync']);
    respond(settings(false));
    const wrapper = mount(CatMirrorSettingsPanel, {
      attachTo: document.body,
      global: { plugins: [ElementPlus] },
    });

    await flushPromises();
    await flushPromises();

    expect(wrapper.text()).toContain('先保存并启用配置，再执行一次全量同步');
    expect(wrapper.text()).not.toContain('增量同步');
    expect((button(wrapper, '立即全量同步').element as HTMLButtonElement).disabled).toBe(true);
    expect((button(wrapper, '全量补偿').element as HTMLButtonElement).disabled).toBe(true);
    expect((button(wrapper, '保存映射').element as HTMLButtonElement).disabled).toBe(true);
    expect((wrapper.get('.el-date-editor input').element as HTMLInputElement).value)
      .toBe('02:00');

    wrapper.unmount();
  });

  it('viewOnlyUser_disablesAllMutationAndSyncCommands', async () => {
    setPermissions(['system.mirror.view']);
    respond(settings(true));
    const wrapper = mount(CatMirrorSettingsPanel, {
      attachTo: document.body,
      global: { plugins: [ElementPlus] },
    });

    await flushPromises();
    await flushPromises();

    for (const label of ['保存配置', '测试连接', '保存映射', '立即全量同步', '全量补偿']) {
      expect((button(wrapper, label).element as HTMLButtonElement).disabled).toBe(true);
    }

    wrapper.unmount();
  });

  it('directorySuggestion_prefillsMappingAndHidesTransportProtectionSettings', async () => {
    setPermissions(['system.mirror.config', 'system.mirror.sync']);
    const data = settings(true);
    data.catalog = {
      snapshotId: 'catalog-1',
      collectedAt: '2026-08-06T08:00:00Z',
      projects: [{ id: 'project-1', name: 'CrownCAD', defaultProject: true }],
      nodes: [
        {
          projectId: 'project-1', nodeType: 'VERSION', id: 'version-1', name: 'CC 2026 R4',
          currentVersion: true,
        },
        {
          projectId: 'project-1', nodeType: 'TEST_PHASE', id: 'unit-1', name: '单元测试',
          versionId: 'version-1',
        },
        {
          projectId: 'project-1', nodeType: 'TEST_PHASE', id: 'integration-1', name: '集成测试',
          versionId: 'version-1',
        },
      ],
    };
    data.mappingSuggestions = [{
      productVersionId: 10,
      catProjectId: 'project-1',
      catVersionId: 'version-1',
      unitTestingPhaseId: 'unit-1',
      integrationTestingPhaseId: 'integration-1',
    }];
    respond(data);
    const wrapper = mount(CatMirrorSettingsPanel, {
      attachTo: document.body,
      global: { plugins: [ElementPlus] },
    });

    await flushPromises();
    await flushPromises();

    expect(wrapper.text()).toContain('系统建议');
    expect(wrapper.text()).toContain('CrownCAD（默认）');
    expect(wrapper.text()).toContain('CC 2026 R4（当前）');
    expect(wrapper.text()).not.toContain('连接超时');
    expect(wrapper.text()).not.toContain('读取超时');
    expect(wrapper.text()).not.toContain('单次响应上限');
    expect(wrapper.text()).not.toContain('每阶段保留快照');

    wrapper.unmount();
  });
});
