import { computed, ref, shallowRef, watch, type Ref } from 'vue';
import type { GitlabSyncConfig, TableWhitelistOption } from '../types/api';

export interface MirrorWhitelistOptionsControllerDependencies {
  form: Ref<GitlabSyncConfig>;
  loadWhitelistOptions: () => Promise<TableWhitelistOption[]>;
  notifyError: (message: string) => void;
}

export function useMirrorWhitelistOptionsController(
  deps: MirrorWhitelistOptionsControllerDependencies,
) {
  const whitelistOptions = shallowRef<TableWhitelistOption[]>([]);
  const whitelistOptionsLoading = ref(false);
  const whitelistOptionsLoaded = ref(false);

  const recommendedCount = computed(() => whitelistOptions.value.filter((item) => item.recommended).length);
  const whitelistSelectOptions = computed(() =>
    whitelistOptions.value.map((option) => ({
      label: `${option.label} (${option.tableName})`,
      value: option.tableName,
    })),
  );

  async function ensureWhitelistOptions(force = false) {
    if (whitelistOptionsLoading.value) {
      return;
    }
    if (!force && whitelistOptionsLoaded.value) {
      return;
    }
    whitelistOptionsLoading.value = true;
    try {
      whitelistOptions.value = await deps.loadWhitelistOptions();
      whitelistOptionsLoaded.value = true;
    } catch (error) {
      deps.notifyError((error as Error).message);
    } finally {
      whitelistOptionsLoading.value = false;
    }
  }

  watch(
    () => deps.form.value.whitelistMode,
    (nextMode) => {
      if (nextMode === 'CUSTOM' || nextMode === 'ALL') {
        void ensureWhitelistOptions();
      }
    },
  );

  return {
    whitelistOptions,
    whitelistOptionsLoading,
    whitelistOptionsLoaded,
    recommendedCount,
    whitelistSelectOptions,
    ensureWhitelistOptions,
  };
}
