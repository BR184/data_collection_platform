import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch, type Ref } from 'vue';

const STORAGE_PREFIX = 'table-sticky-header:';
const DEFAULT_STICKY_HEADER_ENABLED = true;
const DEFAULT_TABLE_MAX_HEIGHT = 560;
const MIN_TABLE_MAX_HEIGHT = 320;
const TABLE_BOTTOM_GAP = 72;

type ScopeKeySource = string | (() => string);

function resolveScopeKey(scopeKey: ScopeKeySource) {
  return typeof scopeKey === 'function' ? scopeKey() : scopeKey;
}

function storageKey(scopeKey: string) {
  return `${STORAGE_PREFIX}${scopeKey}`;
}

function readStickyHeaderPreference(scopeKey: string) {
  if (!scopeKey) {
    return DEFAULT_STICKY_HEADER_ENABLED;
  }
  try {
    const raw = window.localStorage.getItem(storageKey(scopeKey));
    if (raw === null) {
      return DEFAULT_STICKY_HEADER_ENABLED;
    }
    return raw !== 'false';
  } catch {
    return DEFAULT_STICKY_HEADER_ENABLED;
  }
}

function writeStickyHeaderPreference(scopeKey: string, enabled: boolean) {
  if (!scopeKey) {
    return;
  }
  try {
    window.localStorage.setItem(storageKey(scopeKey), String(enabled));
  } catch {
    // Local storage can be unavailable in hardened browser modes; in-memory state still applies.
  }
}

export function useTableStickyHeaderPreference(scopeKey: ScopeKeySource) {
  const stickyHeaderEnabled = ref(DEFAULT_STICKY_HEADER_ENABLED);

  function syncFromStorage() {
    stickyHeaderEnabled.value = readStickyHeaderPreference(resolveScopeKey(scopeKey));
  }

  function setStickyHeaderEnabled(enabled: boolean) {
    stickyHeaderEnabled.value = enabled;
    writeStickyHeaderPreference(resolveScopeKey(scopeKey), enabled);
  }

  onMounted(syncFromStorage);

  watch(
    () => resolveScopeKey(scopeKey),
    syncFromStorage,
  );

  return {
    stickyHeaderEnabled,
    setStickyHeaderEnabled,
  };
}

export function useStickyTableMaxHeight(
  tableShellRef: Ref<HTMLElement | undefined>,
  enabled: Readonly<Ref<boolean>>,
) {
  const tableMaxHeight = ref(DEFAULT_TABLE_MAX_HEIGHT);
  const tableMaxHeightValue = computed(() => (enabled.value ? `${tableMaxHeight.value}px` : undefined));
  let resizeObserver: ResizeObserver | undefined;
  let pageResizeObserver: ResizeObserver | undefined;
  let updateFrame: number | undefined;

  function updateTableMaxHeight() {
    updateFrame = undefined;
    if (!enabled.value) {
      return;
    }
    const tableShell = tableShellRef.value;
    const viewportHeight = window.innerHeight || document.documentElement.clientHeight || DEFAULT_TABLE_MAX_HEIGHT;
    const top = tableShell ? Math.max(tableShell.getBoundingClientRect().top, 0) : 160;
    const availableHeight = Math.floor(viewportHeight - top - TABLE_BOTTOM_GAP);
    tableMaxHeight.value = Math.max(MIN_TABLE_MAX_HEIGHT, availableHeight);
  }

  function scheduleTableMaxHeightUpdate() {
    if (updateFrame !== undefined) {
      return;
    }
    updateFrame = window.requestAnimationFrame(updateTableMaxHeight);
  }

  onMounted(() => {
    resizeObserver = new ResizeObserver(scheduleTableMaxHeightUpdate);
    if (tableShellRef.value) {
      resizeObserver.observe(tableShellRef.value);
    }
    pageResizeObserver = new ResizeObserver(scheduleTableMaxHeightUpdate);
    pageResizeObserver.observe(document.body);
    window.addEventListener('resize', scheduleTableMaxHeightUpdate, { passive: true });
    window.addEventListener('scroll', scheduleTableMaxHeightUpdate, { passive: true, capture: true });
    void nextTick(scheduleTableMaxHeightUpdate);
  });

  onBeforeUnmount(() => {
    resizeObserver?.disconnect();
    pageResizeObserver?.disconnect();
    window.removeEventListener('resize', scheduleTableMaxHeightUpdate);
    window.removeEventListener('scroll', scheduleTableMaxHeightUpdate, { capture: true });
    if (updateFrame !== undefined) {
      window.cancelAnimationFrame(updateFrame);
      updateFrame = undefined;
    }
  });

  watch(enabled, () => {
    void nextTick(scheduleTableMaxHeightUpdate);
  });

  watch(tableShellRef, (nextElement, previousElement) => {
    if (previousElement) {
      resizeObserver?.unobserve(previousElement);
    }
    if (nextElement) {
      resizeObserver?.observe(nextElement);
    }
    void nextTick(scheduleTableMaxHeightUpdate);
  });

  return {
    tableMaxHeightValue,
    scheduleTableMaxHeightUpdate,
  };
}
