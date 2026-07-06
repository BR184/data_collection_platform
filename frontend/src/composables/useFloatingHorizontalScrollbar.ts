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
  const isFloatingScrollbarVisible = ref(false);
  const floatingScrollbarStyle = ref<Record<string, string>>({});

  let scrollbarAwakeTimer: number | undefined;
  let observedTableScrollWrap: HTMLElement | undefined;
  let resizeObserver: ResizeObserver | undefined;
  let updateFrame: number | undefined;
  let syncingFromTable = false;
  let syncingFromFloating = false;
  let draggingScrollbar = false;

  function setScrollbarDragging(active: boolean) {
    draggingScrollbar = active;
    document.body.classList.toggle('platform-floating-scrollbar-dragging', active);
  }

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
      isFloatingScrollbarVisible.value = false;
      horizontalSpacerWidth.value = 0;
      return;
    }

    attachTableScrollListener(tableBody);
    horizontalSpacerWidth.value = tableBody.scrollWidth;
    const pixelTolerance = 0.25;
    hasHorizontalOverflow.value = tableBody.scrollWidth - tableBody.clientWidth > pixelTolerance;
    updateFloatingScrollbarPosition(tableBody);
    syncFloatingScrollbarFromTable();
  }

  function updateFloatingScrollbarPosition(tableBody = getTableScrollWrap()) {
    const tableShell = options.tableShellRef.value;
    if (!tableShell || !tableBody || !hasHorizontalOverflow.value) {
      isFloatingScrollbarVisible.value = false;
      return;
    }

    const rect = tableShell.getBoundingClientRect();
    const viewportWidth = window.innerWidth || document.documentElement.clientWidth;
    const viewportHeight = window.innerHeight || document.documentElement.clientHeight;
    const gutter = 12;
    const bottomOffset = 12;
    const visibleVertically = rect.top < viewportHeight - bottomOffset && rect.bottom > 0;
    if (!visibleVertically) {
      isFloatingScrollbarVisible.value = false;
      return;
    }

    const left = Math.max(rect.left, gutter);
    const right = Math.min(rect.right, viewportWidth - gutter);
    const width = Math.max(0, right - left);
    if (width < 80) {
      isFloatingScrollbarVisible.value = false;
      return;
    }

    floatingScrollbarStyle.value = {
      left: `${left}px`,
      width: `${width}px`,
      bottom: `${bottomOffset}px`,
    };
    isFloatingScrollbarVisible.value = true;
  }

  function requestHorizontalScrollbarUpdate() {
    if (updateFrame !== undefined) {
      return;
    }
    updateFrame = window.requestAnimationFrame(() => {
      updateFrame = undefined;
      updateHorizontalScrollbar();
    });
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
    if (syncingFromFloating) {
      return;
    }
    const tableBody = getTableScrollWrap();
    const horizontalScrollbar = floatingScrollbarRef.value;
    if (!tableBody || !horizontalScrollbar) {
      return;
    }
    syncingFromTable = true;
    horizontalScrollbar.scrollLeft = tableBody.scrollLeft;
    window.requestAnimationFrame(() => {
      syncingFromTable = false;
    });
  }

  function handleFloatingHorizontalScroll() {
    if (syncingFromTable) {
      return;
    }
    const tableBody = getTableScrollWrap();
    const horizontalScrollbar = floatingScrollbarRef.value;
    if (!tableBody || !horizontalScrollbar) {
      return;
    }
    syncingFromFloating = true;
    tableBody.scrollLeft = horizontalScrollbar.scrollLeft;
    window.requestAnimationFrame(() => {
      syncingFromFloating = false;
    });
    wakeHorizontalScrollbar();
  }

  function handleFloatingScrollbarPointerDown() {
    setScrollbarDragging(true);
    wakeHorizontalScrollbar();
  }

  function handleFloatingScrollbarPointerUp() {
    if (!draggingScrollbar) {
      return;
    }
    setScrollbarDragging(false);
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
      resizeObserver = new ResizeObserver(() => requestHorizontalScrollbarUpdate());
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
    window.addEventListener('resize', requestHorizontalScrollbarUpdate, { passive: true });
    window.addEventListener('scroll', requestHorizontalScrollbarUpdate, { passive: true, capture: true });
    window.addEventListener('pointerup', handleFloatingScrollbarPointerUp, { passive: true });
    window.addEventListener('blur', handleFloatingScrollbarPointerUp);
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
    if (updateFrame !== undefined) {
      window.cancelAnimationFrame(updateFrame);
    }
    window.removeEventListener('resize', requestHorizontalScrollbarUpdate);
    window.removeEventListener('scroll', requestHorizontalScrollbarUpdate, { capture: true });
    window.removeEventListener('pointerup', handleFloatingScrollbarPointerUp);
    window.removeEventListener('blur', handleFloatingScrollbarPointerUp);
    observedTableScrollWrap?.removeEventListener('scroll', syncFloatingScrollbarFromTable);
    resizeObserver?.disconnect();
    if (draggingScrollbar) {
      setScrollbarDragging(false);
    }
  });

  return {
    floatingScrollbarRef,
    scrollbarAwake,
    hasHorizontalOverflow,
    isFloatingScrollbarVisible,
    horizontalSpacerWidth,
    floatingScrollbarStyle,
    wakeHorizontalScrollbar,
    handleHorizontalWheel,
    handleFloatingHorizontalScroll,
    handleFloatingScrollbarPointerDown,
    handleFloatingScrollbarPointerUp,
    scheduleHorizontalScrollbarUpdate,
    updateHorizontalScrollbar,
  };
}
