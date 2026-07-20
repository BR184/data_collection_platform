import { request } from './request';

export interface PermissionCatalogItem {
  permissionCode: string;
  permissionName: string;
  moduleName: string;
  description: string;
  sortOrder: number;
}

export interface PermissionRole {
  roleCode: string;
  roleName: string;
  displayOrder: number;
  permissionCodes: string[];
}

export interface PermissionSettingsResponse {
  permissions: PermissionCatalogItem[];
  roles: PermissionRole[];
  initialLdapSyncCompleted: boolean;
}

export const permissionSettingsApi = {
  load() {
    return request<PermissionSettingsResponse>('/api/permission-settings');
  },
  saveRole(roleCode: string, permissionCodes: string[]) {
    return request<PermissionSettingsResponse>(
      `/api/permission-settings/roles/${encodeURIComponent(roleCode)}`,
      {
        method: 'PUT',
        body: JSON.stringify({ permissionCodes }),
      },
    );
  },
  restoreDefaults() {
    return request<PermissionSettingsResponse>('/api/permission-settings/restore-defaults', {
      method: 'POST',
    });
  },
};
