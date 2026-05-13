import { computed, ref } from 'vue';
import type { MirrorPurgeResult, MirrorPurgeScope } from '../types/api';

export interface MirrorPurgeDialogDependencies {
  purgeMirrorData: (scope: MirrorPurgeScope) => Promise<MirrorPurgeResult>;
  loadStatus: () => Promise<void>;
  notifyError: (message: string) => void;
  showPurgeSummary: (result: MirrorPurgeResult) => Promise<void> | void;
}

export function useMirrorPurgeDialog(deps: MirrorPurgeDialogDependencies) {
  const purging = ref<MirrorPurgeScope | null>(null);
  const purgeDialogVisible = ref(false);
  const purgeScope = ref<MirrorPurgeScope>('MIRROR_DATA_ONLY');
  const purgeConfirmText = ref('');

  const isPurging = computed(() => purging.value != null);

  const purgeDialogCopy = computed(() => {
    if (purgeScope.value === 'MIRROR_DATA_EXCLUDING_CURRENT_WHITELIST') {
      return {
        title: 'Delete mirror data outside the current whitelist',
        confirmText: 'Delete non-whitelist mirror data',
        detail:
          'This permanently deletes local mirror tables, mirror registry entries, and summary data that are outside the current whitelist. Mirror data for currently selected tables is kept. GitLab source data and local non-mirror data are not affected.',
      };
    }
    return {
      title: 'Delete mirror data',
      confirmText: 'Delete mirror data',
      detail:
        'This permanently deletes all local mirror tables, mirror registry entries, and summary data. GitLab source data and local non-mirror data are not affected. This action cannot be undone.',
    };
  });

  const purgeConfirmMatched = computed(() => purgeConfirmText.value === purgeDialogCopy.value.confirmText);

  const purgeProgressText = computed(() =>
    purging.value === 'MIRROR_DATA_EXCLUDING_CURRENT_WHITELIST'
      ? 'Deleting local mirror data outside the current whitelist. Please keep this page open and avoid duplicate actions.'
      : 'Deleting local mirror data. Please keep this page open and avoid duplicate actions.',
  );

  function openPurgeDialog() {
    purgeScope.value = 'MIRROR_DATA_ONLY';
    purgeConfirmText.value = '';
    purgeDialogVisible.value = true;
  }

  function closePurgeDialog() {
    if (purging.value) {
      return;
    }
    purgeDialogVisible.value = false;
    purgeConfirmText.value = '';
  }

  async function purgeMirrorData() {
    if (!purgeConfirmMatched.value) {
      return;
    }

    purging.value = purgeScope.value;
    let result: MirrorPurgeResult | null = null;
    try {
      result = await deps.purgeMirrorData(purgeScope.value);
      await deps.loadStatus();
      purgeDialogVisible.value = false;
      purgeConfirmText.value = '';
    } catch (error) {
      deps.notifyError((error as Error).message);
    } finally {
      purging.value = null;
    }
    if (result != null) {
      await deps.showPurgeSummary(result);
    }
  }

  function handlePurgeDialogBeforeClose(done: () => void) {
    if (isPurging.value) {
      return;
    }
    closePurgeDialog();
    done();
  }

  return {
    purging,
    purgeDialogVisible,
    purgeScope,
    purgeConfirmText,
    isPurging,
    purgeDialogCopy,
    purgeConfirmMatched,
    purgeProgressText,
    openPurgeDialog,
    closePurgeDialog,
    purgeMirrorData,
    handlePurgeDialogBeforeClose,
  };
}
