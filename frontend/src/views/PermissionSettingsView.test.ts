import { describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import PermissionSettingsView from './PermissionSettingsView.vue';

const mocks = vi.hoisted(() => ({
  loadSettings: vi.fn(),
  loadCurrentUser: vi.fn(),
}));

vi.mock('../api-client/permission-settings-api', () => ({
  permissionSettingsApi: {
    load: mocks.loadSettings,
    saveRole: vi.fn(),
    restoreDefaults: vi.fn(),
  },
}));

vi.mock('../composables/auth-state', () => ({
  loadCurrentUser: mocks.loadCurrentUser,
}));

describe('PermissionSettingsView', () => {
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
});
