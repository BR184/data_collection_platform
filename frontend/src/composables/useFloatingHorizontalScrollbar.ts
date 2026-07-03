import { nextTick, onBeforeUnmount, onMounted, ref, type Ref, watch, type WatchSource } from 'vue';

interface FloatingHorizontalScrollbarOptions {
  tableShellRef: Ref<HTMLElement | undefined>;
  watchedSources?: WatchSource<unknown>[];
}

export function useFloatingHorizontalScrollbar(options: FloatingHorizontalScrollbarOptions) {
  const floatingScrollbarRef = ref<HTMLElement>();
  const scrollbarAwake = ref(false);
  const hasHorizontalOverflow = ref(false);
  const horizontalSpacerWidth = ref(0);

  let scrollbarAwakeTimer: number | undefined;
  let observedTableScrollWrap: HTMLElement | undefined;
  let resizeObserver: ResizeObserver | undefined;
  let syncingHorizontalScroll = false;

  function wakeHorizontalScrollbar() {
    updateHorizontalScrollbar();
    scrollbarAwake.value = true;
    if (scrollbarAwakeTimer !== undefined) {
      window.clearTimeout(scrollbarAwakeTimer);
    }
    scrollbarAwakeTimer = window.setTimeout(() => {
      scrollbarAwake.value = false;
      scrollbarAwakeTimer = undefined;
    }, 1200);
  }

  function handleHorizontalWheel(event: WheelEvent) {
    if (!event.shiftKey || Math.abs(event.deltaY) <= Math.abs(event.deltaX)) {
      return;
    }
    const tableBody = getTableScrollWrap();
    if (!tableBody) {
      return;
    }
    tableBody.scrollLeft += event.deltaY;
    event.preventDefault();
    wakeHorizontalScrollbar();
  }

  function getTableScrollWrap() {
    const tableShell = options.tableShellRef.value;
    if (!tableShell) {
      return undefined;
    }
    const candidates = [
      ...Array.from(tableShell.querySelectorAll<HTMLElement>('.el-table__body-wrapper .el-scrollbar__wrap')),
      ...Array.from(tableShell.querySelectorAll<HTMLElement>('.el-scrollbar__wrap')),
      tableShell,
    ];
    return candidates.find((candidate) => candidate.scrollWidth - candidate.clientWidth > 0.25)
      ?? candidates[0];
  }

  function updateHorizontalScrollbar() {
    const tableBody = getTableScrollWrap();
    if (!tableBody) {
      hasHorizontalOverflow.value = false;
      horizontalSpacerWidth.value = 0;
      return;
    }

    attachTableScrollListener(tableBody);
    horizontalSpacerWidth.value = tableBody.scrollWidth;
    const pixelTolerance = 0.25;
    hasHorizontalOverflow.value = tableBody.scrollWidth - tableBody.clientWidth > pixelTolerance;
    syncFloatingScrollbarFromTable();
  }

  function attachTableScrollListener(tableBody: HTMLElement) {
    if (observedTableScrollWrap === tableBody) {
      return;
    }
    observedTableScrollWrap?.removeEventListener('scroll', syncFloatingScrollbarFromTable);
    observedTableScrollWrap = tableBody;
    observedTableScrollWrap.addEventListener('scroll', syncFloatingScrollbarFromTable, { passive: true });
  }

  function syncFloatingScrollbarFromTable() {
    if (syncingHorizontalScroll) {
      return;
    }
    const tableBody = getTableScrollWrap();
    const horizontalScrollbar = floatingScrollbarRef.value;
    if (!tableBody || !horizontalScrollbar) {
      return;
    }
    syncingHorizontalScroll = true;
    horizontalScrollbar.scrollLeft = tableBody.scrollLeft;
    window.requestAnimationFrame(() => {
      syncingHorizontalScroll = false;
    });
  }

  function handleFloatingHorizontalScroll() {
    if (syncingHorizontalScroll) {
      return;
    }
    const tableBody = getTableScrollWrap();
    const horizontalScrollbar = floatingScrollbarRef.value;
    if (!tableBody || !horizontalScrollbar) {
      return;
    }
    syncingHorizontalScroll = true;
    tableBody.scrollLeft = horizontalScrollbar.scrollLeft;
    window.requestAnimationFrame(() => {
      syncingHorizontalScroll = false;
    });
    wakeHorizontalScrollbar();
  }

  async function scheduleHorizontalScrollbarUpdate() {
    await nextTick();
    updateHorizontalScrollbar();
  }

  function observeTableShell() {
    resizeObserver?.disconnect();
    resizeObserver = undefined;
    if (typeof ResizeObserver !== 'undefined' && options.tableShellRef.value) {
      resizeObserver = new ResizeObserver(() => updateHorizontalScrollbar());
      resizeObserver.observe(options.tableShellRef.value);
    }
  }

  for (const source of options.watchedSources ?? []) {
    watch(source, () => {
      void scheduleHorizontalScrollbarUpdate();
    });
  }

  onMounted(() => {
    void scheduleHorizontalScrollbarUpdate();
    observeTableShell();
  });

  watch(
    () => options.tableShellRef.value,
    () => {
      observeTableShell();
      void scheduleHorizontalScrollbarUpdate();
    },
  );

  onBeforeUnmount(() => {
    if (scrollbarAwakeTimer !== undefined) {
      window.clearTimeout(scrollbarAwakeTimer);
    }
    observedTableScrollWrap?.removeEventListener('scroll', syncFloatingScrollbarFromTable);
    resizeObserver?.disconnect();
  });

  return {
    floatingScrollbarRef,
    scrollbarAwake,
    hasHorizontalOverflow,
    horizontalSpacerWidth,
    wakeHorizontalScrollbar,
    handleHorizontalWheel,
    handleFloatingHorizontalScroll,
    scheduleHorizontalScrollbarUpdate,
    updateHorizontalScrollbar,
  };
}
