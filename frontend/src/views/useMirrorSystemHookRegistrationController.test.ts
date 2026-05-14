import { describe, expect, it, vi } from 'vitest';
import { useMirrorSystemHookRegistrationController } from './useMirrorSystemHookRegistrationController';
import type { GitlabSystemHookRegistrationStatus } from '../types/api';

function createRegistration(
  overrides: Partial<GitlabSystemHookRegistrationStatus> = {},
): GitlabSystemHookRegistrationStatus {
  return {
    supported: true,
    configured: true,
    registered: true,
    projectId: 123,
    systemHookUrl: 'http://localhost:18080/api/gitlab-sync/system-hook',
    message: 'GitLab System Hook 已注册',
    hooks: [],
    ...overrides,
  };
}

function setup() {
  return {
    getRegistrationStatus: vi.fn<() => Promise<GitlabSystemHookRegistrationStatus>>(() =>
      Promise.resolve(createRegistration()),
    ),
    saveConfig: vi.fn<() => Promise<void>>(() => Promise.resolve()),
    registerSystemHook: vi.fn<() => Promise<GitlabSystemHookRegistrationStatus>>(() =>
      Promise.resolve(createRegistration()),
    ),
    loadStatus: vi.fn<(showError: boolean, blocking: boolean) => Promise<void>>(() => Promise.resolve()),
    notifySuccess: vi.fn<(message: string) => void>(),
    notifyError: vi.fn<(message: string) => void>(),
  };
}

describe('useMirrorSystemHookRegistrationController', () => {
  it('loads system hook registration status into local state', async () => {
    const deps = setup();
    const registration = createRegistration({ message: 'GitLab System Hook 已存在' });
    deps.getRegistrationStatus.mockResolvedValueOnce(registration);
    const controller = useMirrorSystemHookRegistrationController(deps);

    await controller.loadSystemHookRegistration(false);

    expect(deps.getRegistrationStatus).toHaveBeenCalledOnce();
    expect(controller.systemHookRegistration.value).toBe(registration);
    expect(controller.systemHookRegistrationLoading.value).toBe(false);
  });

  it('clears registration state and reports load errors only when requested', async () => {
    const deps = setup();
    deps.getRegistrationStatus.mockRejectedValue(new Error('检测失败'));
    const controller = useMirrorSystemHookRegistrationController(deps);
    controller.systemHookRegistrationState.value = createRegistration();

    await controller.loadSystemHookRegistration(false);
    await controller.loadSystemHookRegistration(true);

    expect(controller.systemHookRegistration.value).toBeNull();
    expect(deps.notifyError).toHaveBeenCalledTimes(1);
    expect(deps.notifyError).toHaveBeenCalledWith('检测失败');
    expect(controller.systemHookRegistrationLoading.value).toBe(false);
  });

  it('saves config, registers system hook, and refreshes dependent state', async () => {
    const deps = setup();
    const controller = useMirrorSystemHookRegistrationController(deps);

    await controller.registerSystemHook();

    expect(deps.saveConfig).toHaveBeenCalledOnce();
    expect(deps.registerSystemHook).toHaveBeenCalledOnce();
    expect(deps.notifySuccess).toHaveBeenCalledWith('GitLab System Hook 已注册');
    expect(deps.loadStatus).toHaveBeenCalledWith(false, false);
    expect(deps.getRegistrationStatus).toHaveBeenCalledOnce();
    expect(controller.registeringSystemHook.value).toBe(false);
    expect(deps.saveConfig.mock.invocationCallOrder[0]).toBeLessThan(
      deps.registerSystemHook.mock.invocationCallOrder[0],
    );
  });
});
