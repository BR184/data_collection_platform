import { beforeEach, describe, expect, it, vi } from 'vitest';
import { mirrorApi } from './mirror-api';

vi.mock('./request', () => ({
  request: vi.fn(() => Promise.resolve({})),
}));

import { request } from './request';

describe('mirrorApi system hook endpoints', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('uses system hook registration status path', () => {
    mirrorApi.getSystemHookRegistrationStatus(12);

    expect(request).toHaveBeenCalledWith('/api/gitlab-sync/system-hook-registration-status?configId=12');
  });

  it('uses system hook registration path', () => {
    mirrorApi.registerSystemHook(12);

    expect(request).toHaveBeenCalledWith('/api/gitlab-sync/register-system-hook/by-config?configId=12', {
      method: 'POST',
    });
  });

  it('uses full compensation reconciliation path', () => {
    mirrorApi.startFullCompensationSync(12);

    expect(request).toHaveBeenCalledWith('/api/gitlab-sync/full-compensation-sync/by-config?configId=12', {
      method: 'POST',
    });
  });
});
