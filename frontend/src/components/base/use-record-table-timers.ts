import { onBeforeUnmount, ref, watch, type Ref } from 'vue';

function clearTimer(timerId: number | null) {
  if (timerId != null) {
    window.clearTimeout(timerId);
  }
}

export function useDelayedLoading(loading: Ref<boolean>, delay: Ref<number>) {
  const displayedLoading = ref(false);
  let timerId: number | null = null;

  function stopTimer() {
    clearTimer(timerId);
    timerId = null;
  }

  watch(
    loading,
    (nextLoading) => {
      stopTimer();
      if (!nextLoading) {
        displayedLoading.value = false;
        return;
      }
      if (delay.value <= 0) {
        displayedLoading.value = true;
        return;
      }
      timerId = window.setTimeout(() => {
        displayedLoading.value = true;
        timerId = null;
      }, delay.value);
    },
    { immediate: true },
  );

  onBeforeUnmount(stopTimer);

  return {
    displayedLoading,
  };
}

export function useDebouncedTask(delay: Ref<number>) {
  let timerId: number | null = null;

  function clear() {
    clearTimer(timerId);
    timerId = null;
  }

  function schedule(callback: () => void) {
    clear();
    timerId = window.setTimeout(() => {
      timerId = null;
      callback();
    }, delay.value);
  }

  onBeforeUnmount(clear);

  return {
    clear,
    schedule,
  };
}
