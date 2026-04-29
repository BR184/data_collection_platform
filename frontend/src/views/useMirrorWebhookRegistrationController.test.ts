import { describe, expect, it, vi } from 'vitest';
import { useMirrorWebhookRegistrationController } from './useMirrorWebhookRegistrationController';
import type { GitlabWebhookRegistrationStatus } from '../types/api';

function createRegistration(
  overrides: Partial<GitlabWebhookRegistrationStatus> = {},
): GitlabWebhookRegistrationStatus {
  return {
    supported: true,
    configured: true,
    registered: true,
    projectId: 123,
    webhookUrl: 'http://localhost:18080/api/gitlab-sync/webhook',
    message: 'Webhook 已注册',
    hooks: [],
    ...overrides,
  };
}

function setup() {
  return {
    getRegistrationStatus: vi.fn<() => Promise<GitlabWebhookRegistrationStatus>>(() =>
      Promise.resolve(createRegistration()),
    ),
    saveConfig: vi.fn<() => Promise<void>>(() => Promise.resolve()),
    registerWebhook: vi.fn<() => Promise<GitlabWebhookRegistrationStatus>>(() =>
      Promise.resolve(createRegistration()),
    ),
    loadStatus: vi.fn<(showError: boolean, blocking: boolean) => Promise<void>>(() => Promise.resolve()),
    notifySuccess: vi.fn<(message: string) => void>(),
    notifyError: vi.fn<(message: string) => void>(),
  };
}

describe('useMirrorWebhookRegistrationController', () => {
  it('loads webhook registration status into local state', async () => {
    const deps = setup();
    const registration = createRegistration({ message: 'GitLab Webhook 已存在' });
    deps.getRegistrationStatus.mockResolvedValueOnce(registration);
    const controller = useMirrorWebhookRegistrationController(deps);

    await controller.loadWebhookRegistration(false);

    expect(deps.getRegistrationStatus).toHaveBeenCalledOnce();
    expect(controller.webhookRegistration.value).toBe(registration);
    expect(controller.webhookRegistrationLoading.value).toBe(false);
  });

  it('clears registration state and reports load errors only when requested', async () => {
    const deps = setup();
    deps.getRegistrationStatus.mockRejectedValue(new Error('检测失败'));
    const controller = useMirrorWebhookRegistrationController(deps);
    controller.webhookRegistrationState.value = createRegistration();

    await controller.loadWebhookRegistration(false);
    await controller.loadWebhookRegistration(true);

    expect(controller.webhookRegistration.value).toBeNull();
    expect(deps.notifyError).toHaveBeenCalledTimes(1);
    expect(deps.notifyError).toHaveBeenCalledWith('检测失败');
    expect(controller.webhookRegistrationLoading.value).toBe(false);
  });

  it('saves config, registers webhook, and refreshes dependent state', async () => {
    const deps = setup();
    const controller = useMirrorWebhookRegistrationController(deps);

    await controller.registerWebhook();

    expect(deps.saveConfig).toHaveBeenCalledOnce();
    expect(deps.registerWebhook).toHaveBeenCalledOnce();
    expect(deps.notifySuccess).toHaveBeenCalledWith('GitLab Webhook 已注册');
    expect(deps.loadStatus).toHaveBeenCalledWith(false, false);
    expect(deps.getRegistrationStatus).toHaveBeenCalledOnce();
    expect(controller.registeringWebhook.value).toBe(false);
    expect(deps.saveConfig.mock.invocationCallOrder[0]).toBeLessThan(
      deps.registerWebhook.mock.invocationCallOrder[0],
    );
  });
});
