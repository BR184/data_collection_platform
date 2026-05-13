import { computed, ref, shallowRef } from 'vue';
import type { GitlabWebhookRegistrationStatus } from '../types/api';

export interface MirrorWebhookRegistrationControllerDependencies {
  getRegistrationStatus: () => Promise<GitlabWebhookRegistrationStatus>;
  saveConfig: () => Promise<void>;
  registerWebhook: () => Promise<GitlabWebhookRegistrationStatus>;
  loadStatus: (showError: boolean, blocking: boolean) => Promise<void>;
  notifySuccess: (message: string) => void;
  notifyError: (message: string) => void;
}

export function useMirrorWebhookRegistrationController(
  deps: MirrorWebhookRegistrationControllerDependencies,
) {
  const registeringWebhook = ref(false);
  const webhookRegistrationState = shallowRef<GitlabWebhookRegistrationStatus | null>(null);
  const webhookRegistrationLoading = ref(false);
  const webhookRegistration = computed(() => webhookRegistrationState.value ?? null);

  async function loadWebhookRegistration(showError = false) {
    webhookRegistrationLoading.value = true;
    try {
      webhookRegistrationState.value = await deps.getRegistrationStatus();
    } catch (error) {
      webhookRegistrationState.value = null;
      if (showError) {
        deps.notifyError((error as Error).message);
      }
    } finally {
      webhookRegistrationLoading.value = false;
    }
  }

  async function registerWebhook() {
    registeringWebhook.value = true;
    try {
      await deps.saveConfig();
      await deps.registerWebhook();
      deps.notifySuccess('GitLab System Hook registered');
      await deps.loadStatus(false, false);
      await loadWebhookRegistration(false);
    } catch (error) {
      deps.notifyError((error as Error).message);
    } finally {
      registeringWebhook.value = false;
    }
  }

  return {
    registeringWebhook,
    webhookRegistrationState,
    webhookRegistrationLoading,
    webhookRegistration,
    loadWebhookRegistration,
    registerWebhook,
  };
}
