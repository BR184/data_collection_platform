import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import PermissionSettingsView from './PermissionSettingsView.vue';

const mocks = vi.hoisted(() => ({
  loadSettings: vi.fn(),
  saveRole: vi.fn(),
  restoreDefaults: vi.fn(),
  refreshCurrentUser: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
  warning: vi.fn(),
  confirm: vi.fn(),
}));

vi.mock('../api-client/permission-settings-api', () => ({
  permissionSettingsApi: {
    load: mocks.loadSettings,
    saveRole: mocks.saveRole,
    restoreDefaults: mocks.restoreDefaults,
  },
}));

vi.mock('../composables/auth-state', () => ({
  refreshCurrentUser: mocks.refreshCurrentUser,
}));
vi.mock('../element-plus-services', () => ({
  ElMessage: { success: mocks.success, error: mocks.error, warning: mocks.warning },
  ElMessageBox: { confirm: mocks.confirm },
}));

describe('PermissionSettingsView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.loadSettings.mockResolvedValue({ permissions: [], roles: [], initialLdapSyncCompleted: true });
    mocks.saveRole.mockResolvedValue({ permissions: [], roles: [], initialLdapSyncCompleted: true });
    mocks.restoreDefaults.mockResolvedValue({ permissions: [], roles: [], initialLdapSyncCompleted: true });
    mocks.refreshCurrentUser.mockResolvedValue({
      username: 'admin', displayName: '管理员', roleCodes: ['ADMIN'], roleNames: ['管理员'],
      permissions: ['system.permission.manage'], authenticated: true,
    });
    mocks.confirm.mockResolvedValue(undefined);
  });

  it('renders Chinese role and permission descriptions without stable codes', async () => {
    mocks.loadSettings.mockResolvedValue({
      permissions: [
        {
          permissionCode: 'system.permission.manage',
          permissionName: '管理角色权限',
          moduleName: '系统设置',
          description: '修改本地角色权限映射',
          sortOrder: 1,
        },
      ],
      roles: [
        {
          roleCode: 'SUPER_ADMIN',
          roleName: '超级管理员',
          displayOrder: 10,
          permissionCodes: ['system.permission.manage'],
        },
      ],
      initialLdapSyncCompleted: true,
    });

    const wrapper = mount(PermissionSettingsView, {
      global: { plugins: [ElementPlus] },
    });
    await flushPromises();

    expect(wrapper.text()).toContain('超级管理员');
    expect(wrapper.text()).toContain('管理角色权限');
    expect(wrapper.text()).toContain('修改本地角色权限映射');
    expect(wrapper.text()).not.toContain('SUPER_ADMIN');
    expect(wrapper.text()).not.toContain('system.permission.manage');
    expect(wrapper.text()).not.toContain('按角色配置数据采集平台的页面和操作权限');
  });

  it('renders roles from the highest configured display order to the lowest', async () => {
    mocks.loadSettings.mockResolvedValue({
      permissions: [],
      roles: [
        { roleCode: 'NORMAL_USER', roleName: '普通用户', displayOrder: 50, permissionCodes: [] },
        { roleCode: 'SUPER_ADMIN', roleName: '超级管理员', displayOrder: 10, permissionCodes: [] },
        { roleCode: 'ADMIN', roleName: '管理员', displayOrder: 20, permissionCodes: [] },
      ],
      initialLdapSyncCompleted: true,
    });

    const wrapper = mount(PermissionSettingsView, {
      global: { plugins: [ElementPlus] },
    });
    await flushPromises();

    const labels = wrapper.findAll('.role-option').map((item) => item.find('span').text());
    expect(labels).toEqual(['超级管理员', '管理员', '普通用户']);
  });

  it('exposes a guarded restore-default action', async () => {
    mocks.loadSettings.mockResolvedValue({ permissions: [], roles: [], initialLdapSyncCompleted: true });

    const wrapper = mount(PermissionSettingsView, {
      global: { plugins: [ElementPlus] },
    });
    await flushPromises();

    expect(wrapper.text()).toContain('恢复默认设置');
  });

  it('reports a saved role separately when current-user refresh fails', async () => {
    const savedSettings = {
      permissions: [],
      roles: [{ roleCode: 'ADMIN', roleName: '管理员', displayOrder: 1, permissionCodes: [] }],
      initialLdapSyncCompleted: true,
    };
    mocks.loadSettings.mockResolvedValue(savedSettings);
    mocks.saveRole.mockResolvedValue(savedSettings);
    mocks.refreshCurrentUser.mockRejectedValue(new Error('refresh offline'));

    const wrapper = mount(PermissionSettingsView, { global: { plugins: [ElementPlus] } });
    await flushPromises();
    await wrapper.findAll('button').find((button) => button.text().includes('保存权限'))!.trigger('click');
    await flushPromises();

    expect(mocks.saveRole).toHaveBeenCalledTimes(1);
    expect(mocks.refreshCurrentUser).toHaveBeenCalledTimes(1);
    expect(mocks.error).toHaveBeenCalledWith(expect.stringContaining('角色权限已保存'));
    expect(mocks.error).toHaveBeenCalledWith(expect.stringContaining('刷新失败'));
    wrapper.unmount();
  });
});
