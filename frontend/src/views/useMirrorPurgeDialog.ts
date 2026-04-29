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
        title: '删除镜像数据（排除当前白名单）',
        confirmText: '删除白名单外镜像数据',
        detail:
          '将真实删除当前白名单之外的镜像表、镜像注册信息和旧镜像总表数据。当前白名单内的镜像数据会保留，GitLab 源端和本地非镜像数据不会受影响。',
      };
    }
    return {
      title: '删除镜像数据',
      confirmText: '删除镜像数据',
      detail:
        '将真实删除全部镜像表、镜像注册信息和旧镜像总表数据。GitLab 源端和本地非镜像数据不会受影响，此操作不可恢复。',
    };
  });

  const purgeConfirmMatched = computed(() => purgeConfirmText.value === purgeDialogCopy.value.confirmText);

  const purgeProgressText = computed(() =>
    purging.value === 'MIRROR_DATA_EXCLUDING_CURRENT_WHITELIST'
      ? '正在删除白名单外的本地镜像数据，请勿关闭页面或重复操作。'
      : '正在删除本地镜像数据，请勿关闭页面或重复操作。',
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
