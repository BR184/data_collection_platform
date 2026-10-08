import { nextTick, onBeforeUnmount, onMounted, ref, type Ref, watch, type WatchSource } from 'vue';

interface FloatingHorizontalScrollbarOptions {
  tableShellRef: Ref<HTMLElement | undefined>;
  watchedSources?: WatchSource<unknown>[];
  /**
   * 横条定位模式。
   *
   * `viewport`（默认）：横条相对视口固定在页面底部，模块滚出视口即隐藏；记录表、统计矩阵与明细弹窗沿用该模式。
   * `container`：横条停靠在表格模块自身的底部，按容器与表体 `clientWidth` 定位，随模块滚动且不会漂浮到页面其他区域；
   * 日志表使用该模式，因为其外壳有固定高度、需要模块内双向滚动。
   */
  positioning?: 'viewport' | 'container';
}

const minimumThumbWidth = 48;
const floatingScrollbarSideInset = 20;

export function useFloatingHorizontalScrollbar(options: FloatingHorizontalScrollbarOptions) {
  const floatingScrollbarRef = ref<HTMLElement>();
  const floatingTrackRef = ref<HTMLElement>();
  const scrollbarAwake = ref(false);
  const hasHorizontalOverflow = ref(false);
  const isFloatingScrollbarVisible = ref(false);
  const floatingScrollbarStyle = ref<Record<string, string>>({});
  const floatingThumbStyle = ref<Record<string, string>>({
    width: `${minimumThumbWidth}px`,
    transform: 'translateX(0px)',
  });

  let scrollbarAwakeTimer: number | undefined;
  let observedTableScrollWrap: HTMLElement | undefined;
  let resizeObserver: ResizeObserver | undefined;
  let updateFrame: number | undefined;
  let syncingFromFloating = false;
  let draggingScrollbar = false;
  let dragStartClientX = 0;
  let dragStartScrollLeft = 0;

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
      return;
    }

    attachTableScrollListener(tableBody);
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

    if (options.positioning === 'container') {
      updateContainerScrollbarPosition(tableShell, tableBody);
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

    const left = Math.max(rect.left + floatingScrollbarSideInset, gutter);
    const right = Math.min(rect.right - floatingScrollbarSideInset, viewportWidth - gutter);
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

  // 模块内停靠：宽度取表体 clientWidth（已排除纵向条），水平位置取表体相对外壳的偏移，
  // 底部对齐外壳 padding 盒下沿；外壳用 padding-bottom 预留横条空间，因此不会盖住最后一行。
  function updateContainerScrollbarPosition(tableShell: HTMLElement, tableBody: HTMLElement) {
    const shellRect = tableShell.getBoundingClientRect();
    const bodyRect = tableBody.getBoundingClientRect();
    const width = Math.max(0, Math.round(tableBody.clientWidth));
    if (width < 80) {
      isFloatingScrollbarVisible.value = false;
      return;
    }

    const left = Math.max(0, Math.round(bodyRect.left - shellRect.left));
    floatingScrollbarStyle.value = {
      position: 'absolute',
      left: `${left}px`,
      width: `${width}px`,
      bottom: '0px',
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

  // 内网浏览器/兼容模式可能丢失 WebKit 滚动条伪类样式，导致原生滚动条高度和圆角不可控。
  // 这里把表格横向滚动当作逻辑状态同步，自绘 track/thumb 负责视觉，确保内外网和浏览器缩放下表现一致。
  function syncFloatingScrollbarFromTable() {
    if (syncingFromFloating) {
      return;
    }
    const tableBody = getTableScrollWrap();
    const track = floatingTrackRef.value ?? floatingScrollbarRef.value;
    if (!tableBody || !track) {
      return;
    }
    updateFloatingThumb(tableBody, track);
  }

  function updateFloatingThumb(tableBody: HTMLElement, track: HTMLElement) {
    const trackWidth = track.clientWidth;
    const scrollableWidth = tableBody.scrollWidth - tableBody.clientWidth;
    if (trackWidth <= 0 || scrollableWidth <= 0) {
      floatingThumbStyle.value = {
        width: `${Math.max(0, trackWidth)}px`,
        transform: 'translateX(0px)',
      };
      return;
    }

    const proportionalWidth = (tableBody.clientWidth / tableBody.scrollWidth) * trackWidth;
    const thumbWidth = Math.min(trackWidth, Math.max(minimumThumbWidth, proportionalWidth));
    const movableWidth = Math.max(0, trackWidth - thumbWidth);
    const scrollRatio = tableBody.scrollLeft / scrollableWidth;
    const thumbLeft = movableWidth * scrollRatio;
    floatingThumbStyle.value = {
      width: `${thumbWidth}px`,
      transform: `translateX(${thumbLeft}px)`,
    };
  }

  function scrollTableToThumbPosition(clientX: number) {
    const tableBody = getTableScrollWrap();
    const track = floatingTrackRef.value ?? floatingScrollbarRef.value;
    if (!tableBody || !track) {
      return;
    }

    const trackRect = track.getBoundingClientRect();
    const trackWidth = track.clientWidth;
    const scrollableWidth = tableBody.scrollWidth - tableBody.clientWidth;
    if (trackWidth <= 0 || scrollableWidth <= 0) {
      return;
    }

    const proportionalWidth = (tableBody.clientWidth / tableBody.scrollWidth) * trackWidth;
    const thumbWidth = Math.min(trackWidth, Math.max(minimumThumbWidth, proportionalWidth));
    const movableWidth = Math.max(1, trackWidth - thumbWidth);
    const thumbLeft = Math.min(Math.max(clientX - trackRect.left - thumbWidth / 2, 0), movableWidth);
    syncingFromFloating = true;
    tableBody.scrollLeft = (thumbLeft / movableWidth) * scrollableWidth;
    window.requestAnimationFrame(() => {
      syncingFromFloating = false;
      syncFloatingScrollbarFromTable();
    });
  }

  function handleFloatingTrackPointerDown(event: PointerEvent) {
    if (!(event.currentTarget instanceof HTMLElement)) {
      return;
    }
    event.preventDefault();
    scrollTableToThumbPosition(event.clientX);
    const tableBody = getTableScrollWrap();
    dragStartClientX = event.clientX;
    dragStartScrollLeft = tableBody?.scrollLeft ?? 0;
    setScrollbarDragging(true);
    wakeHorizontalScrollbar();
  }

  function handleFloatingThumbPointerDown(event: PointerEvent) {
    event.preventDefault();
    event.stopPropagation();
    const tableBody = getTableScrollWrap();
    dragStartClientX = event.clientX;
    dragStartScrollLeft = tableBody?.scrollLeft ?? 0;
    setScrollbarDragging(true);
    wakeHorizontalScrollbar();
  }

  function handleFloatingPointerMove(event: PointerEvent) {
    if (!draggingScrollbar) {
      return;
    }
    const tableBody = getTableScrollWrap();
    const track = floatingTrackRef.value ?? floatingScrollbarRef.value;
    if (!tableBody || !track) {
      return;
    }

    const trackWidth = track.clientWidth;
    const scrollableWidth = tableBody.scrollWidth - tableBody.clientWidth;
    const proportionalWidth = (tableBody.clientWidth / tableBody.scrollWidth) * trackWidth;
    const thumbWidth = Math.min(trackWidth, Math.max(minimumThumbWidth, proportionalWidth));
    const movableWidth = trackWidth - thumbWidth;
    if (trackWidth <= 0 || scrollableWidth <= 0 || movableWidth <= 0) {
      return;
    }

    const deltaX = event.clientX - dragStartClientX;
    syncingFromFloating = true;
    tableBody.scrollLeft = dragStartScrollLeft + (deltaX / movableWidth) * scrollableWidth;
    window.requestAnimationFrame(() => {
      syncingFromFloating = false;
      syncFloatingScrollbarFromTable();
    });
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
    window.addEventListener('pointermove', handleFloatingPointerMove, { passive: true });
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
    window.removeEventListener('pointermove', handleFloatingPointerMove);
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
    floatingTrackRef,
    scrollbarAwake,
    hasHorizontalOverflow,
    isFloatingScrollbarVisible,
    floatingScrollbarStyle,
    floatingThumbStyle,
    wakeHorizontalScrollbar,
    handleHorizontalWheel,
    handleFloatingTrackPointerDown,
    handleFloatingThumbPointerDown,
    handleFloatingScrollbarPointerUp,
    scheduleHorizontalScrollbarUpdate,
    updateHorizontalScrollbar,
  };
}
