import { computed, ref, shallowRef } from 'vue';
import type { GitlabSystemHookRegistrationStatus } from '../types/api';

export interface MirrorSystemHookRegistrationControllerDependencies {
  getRegistrationStatus: () => Promise<GitlabSystemHookRegistrationStatus>;
  saveConfig: () => Promise<void>;
  registerSystemHook: () => Promise<GitlabSystemHookRegistrationStatus>;
  loadStatus: (showError: boolean, blocking: boolean) => Promise<void>;
  notifySuccess: (message: string) => void;
  notifyError: (message: string) => void;
}

export function useMirrorSystemHookRegistrationController(
  deps: MirrorSystemHookRegistrationControllerDependencies,
) {
  const registeringSystemHook = ref(false);
  const systemHookRegistrationState = shallowRef<GitlabSystemHookRegistrationStatus | null>(null);
  const systemHookRegistrationLoading = ref(false);
  const systemHookRegistration = computed(() => systemHookRegistrationState.value ?? null);

  async function loadSystemHookRegistration(showError = false) {
    systemHookRegistrationLoading.value = true;
    try {
      systemHookRegistrationState.value = await deps.getRegistrationStatus();
    } catch (error) {
      systemHookRegistrationState.value = null;
      if (showError) {
        deps.notifyError((error as Error).message);
      }
    } finally {
      systemHookRegistrationLoading.value = false;
    }
  }

  async function registerSystemHook() {
    registeringSystemHook.value = true;
    try {
      await deps.saveConfig();
      await deps.registerSystemHook();
      deps.notifySuccess('GitLab System Hook 已注册');
      await deps.loadStatus(false, false);
      await loadSystemHookRegistration(false);
    } catch (error) {
      deps.notifyError((error as Error).message);
    } finally {
      registeringSystemHook.value = false;
    }
  }

  return {
    registeringSystemHook,
    systemHookRegistrationState,
    systemHookRegistrationLoading,
    systemHookRegistration,
    loadSystemHookRegistration,
    registerSystemHook,
  };
}
