import { describe, expect, it, vi } from 'vitest';
import { permissionSettingsApi } from './permission-settings-api';
import { request } from './request';

vi.mock('./request', () => ({
  request: vi.fn(),
}));

describe('permissionSettingsApi', () => {
  it('restores the server-defined default role permissions', async () => {
    vi.mocked(request).mockResolvedValue({
      permissions: [],
      roles: [],
      initialLdapSyncCompleted: true,
    });

    await permissionSettingsApi.restoreDefaults();

    expect(request).toHaveBeenCalledWith('/api/permission-settings/restore-defaults', {
      method: 'POST',
    });
  });
});
