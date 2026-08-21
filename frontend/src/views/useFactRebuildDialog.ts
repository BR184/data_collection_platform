import { computed, ref } from 'vue';
import type { FactRebuildSubmission } from '../types/api';
import { getErrorMessage } from '../utils/user-message';

const CONFIRMATION_DELAY_MS = 5_000;
const CONFIRMATION_PHRASE = '重建全部事实层';

export interface FactRebuildDialogDependencies {
  rebuildFacts: () => Promise<FactRebuildSubmission>;
  refreshRunStatus: () => Promise<void>;
  notifyError: (message: string) => void;
  showResult: (result: FactRebuildSubmission) => Promise<void> | void;
}

/** 管理事实层全量重建的确认等待、提交状态和结果反馈。 */
export function useFactRebuildDialog(deps: FactRebuildDialogDependencies) {
  const factRebuildDialogVisible = ref(false);
  const factRebuildConfirmText = ref('');
  const factRebuildCountdownSeconds = ref(0);
  const rebuilding = ref(false);
  let confirmationAvailableAt = 0;
  let countdownTimer: ReturnType<typeof setInterval> | undefined;

  const isFactRebuilding = computed(() => rebuilding.value);
  const factRebuildConfirmMatched = computed(
    () => factRebuildConfirmText.value === CONFIRMATION_PHRASE,
  );
  const factRebuildReady = computed(
    () => factRebuildConfirmMatched.value && factRebuildCountdownSeconds.value === 0 && !isFactRebuilding.value,
  );

  function updateCountdown() {
    const remainingMs = Math.max(0, confirmationAvailableAt - Date.now());
    factRebuildCountdownSeconds.value = Math.ceil(remainingMs / 1_000);
    if (remainingMs === 0) {
      clearCountdownTimer();
    }
  }

  function clearCountdownTimer() {
    if (countdownTimer != null) {
      clearInterval(countdownTimer);
      countdownTimer = undefined;
    }
  }

  function openFactRebuildDialog() {
    clearCountdownTimer();
    factRebuildConfirmText.value = '';
    confirmationAvailableAt = Date.now() + CONFIRMATION_DELAY_MS;
    updateCountdown();
    countdownTimer = setInterval(updateCountdown, 100);
    factRebuildDialogVisible.value = true;
  }

  function closeFactRebuildDialog() {
    if (isFactRebuilding.value) {
      return;
    }
    clearCountdownTimer();
    factRebuildDialogVisible.value = false;
    factRebuildConfirmText.value = '';
    factRebuildCountdownSeconds.value = 0;
  }

  async function rebuildFacts() {
    if (!factRebuildReady.value) {
      return;
    }

    rebuilding.value = true;
    try {
      const result = await deps.rebuildFacts();
      closeAfterSuccessfulRebuild();
      try {
        await deps.refreshRunStatus();
      } catch (error) {
        deps.notifyError(
          `事实层重建已提交，但运行状态刷新失败：${getErrorMessage(error, '请手动刷新页面')}`,
        );
      }
      await deps.showResult(result);
    } catch (error) {
      deps.notifyError(getErrorMessage(error, '事实层重建失败'));
    } finally {
      rebuilding.value = false;
    }
  }

  function closeAfterSuccessfulRebuild() {
    clearCountdownTimer();
    factRebuildDialogVisible.value = false;
    factRebuildConfirmText.value = '';
    factRebuildCountdownSeconds.value = 0;
  }

  function handleFactRebuildDialogBeforeClose(done: () => void) {
    if (isFactRebuilding.value) {
      return;
    }
    closeFactRebuildDialog();
    done();
  }

  function disposeFactRebuildDialog() {
    clearCountdownTimer();
  }

  return {
    factRebuildDialogVisible,
    factRebuildConfirmText,
    factRebuildCountdownSeconds,
    factRebuildConfirmationPhrase: CONFIRMATION_PHRASE,
    isFactRebuilding,
    factRebuildConfirmMatched,
    factRebuildReady,
    openFactRebuildDialog,
    closeFactRebuildDialog,
    rebuildFacts,
    handleFactRebuildDialogBeforeClose,
    disposeFactRebuildDialog,
  };
}
