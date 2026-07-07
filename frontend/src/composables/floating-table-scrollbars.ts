const managedTableClass = 'platform-floating-scrollbar-managed';
const externalScrollbarClass = 'platform-table-floating-horizontal';
const trackClass = 'platform-floating-horizontal-track';
const thumbClass = 'platform-floating-horizontal-thumb';
const minimumThumbWidth = 48;
const floatingScrollbarSideInset = 20;

let floatingScrollbar: HTMLElement | undefined;
let floatingTrack: HTMLElement | undefined;
let floatingThumb: HTMLElement | undefined;
let activeScrollWrap: HTMLElement | undefined;
let activeTable: HTMLElement | undefined;
let mutationObserver: MutationObserver | undefined;
let resizeObserver: ResizeObserver | undefined;
let updateFrame: number | undefined;
let syncingFromFloating = false;
let draggingScrollbar = false;
let dragStartClientX = 0;
let dragStartScrollLeft = 0;

export function installFloatingTableScrollbars() {
  if (typeof window === 'undefined') {
    return;
  }
  ensureFloatingScrollbar();
  scanTables();
  mutationObserver = new MutationObserver(() => scanTables());
  mutationObserver.observe(document.body, { childList: true, subtree: true });
  window.addEventListener('resize', requestUpdate, { passive: true });
  window.addEventListener('scroll', requestUpdate, { passive: true, capture: true });
  window.addEventListener('pointermove', handleFloatingPointerMove, { passive: true });
}

function ensureFloatingScrollbar() {
  if (floatingScrollbar) {
    return;
  }
  floatingScrollbar = document.createElement('div');
  floatingScrollbar.className = externalScrollbarClass;
  floatingScrollbar.setAttribute('aria-hidden', 'true');

  floatingTrack = document.createElement('div');
  floatingTrack.className = trackClass;
  floatingThumb = document.createElement('div');
  floatingThumb.className = thumbClass;
  floatingTrack.appendChild(floatingThumb);
  floatingScrollbar.appendChild(floatingTrack);

  floatingTrack.addEventListener('pointerdown', handleFloatingTrackPointerDown);
  floatingThumb.addEventListener('pointerdown', handleFloatingThumbPointerDown);
  floatingScrollbar.addEventListener('pointerup', handleFloatingPointerUp, { passive: true });
  window.addEventListener('pointerup', handleFloatingPointerUp, { passive: true });
  window.addEventListener('blur', handleFloatingPointerUp);
  document.body.appendChild(floatingScrollbar);
}

function scanTables() {
  const tables = Array.from(document.querySelectorAll<HTMLElement>('.el-table'))
    .filter((table) => !table.closest('.record-table-frame, .stat-matrix-wrapper, .stat-detail-table-shell, .sync-log-table-shell'));
  for (const table of tables) {
    if (table.classList.contains(managedTableClass)) {
      continue;
    }
    table.classList.add(managedTableClass);
    table.addEventListener('mouseenter', () => activateTable(table), { passive: true });
    table.addEventListener('focusin', () => activateTable(table));
    table.addEventListener('wheel', handleTableWheel, { passive: false });
  }
  if (!activeTable || !document.body.contains(activeTable)) {
    hideFloatingScrollbar();
  }
}

function activateTable(table: HTMLElement) {
  const scrollWrap = findScrollWrap(table);
  if (!scrollWrap || !hasHorizontalOverflow(scrollWrap)) {
    hideFloatingScrollbar();
    return;
  }
  if (activeScrollWrap !== scrollWrap) {
    activeScrollWrap?.removeEventListener('scroll', syncFloatingFromTable);
    activeScrollWrap = scrollWrap;
    activeScrollWrap.addEventListener('scroll', syncFloatingFromTable, { passive: true });
  }
  activeTable = table;
  resizeObserver?.disconnect();
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(() => requestUpdate());
    resizeObserver.observe(table);
  }
  updateFloatingScrollbar();
}

function findScrollWrap(table: HTMLElement) {
  const candidates = [
    ...Array.from(table.querySelectorAll<HTMLElement>('.el-table__body-wrapper .el-scrollbar__wrap')),
    ...Array.from(table.querySelectorAll<HTMLElement>('.el-scrollbar__wrap')),
    table,
  ];
  return candidates.find((candidate) => hasHorizontalOverflow(candidate)) ?? candidates[0];
}

function hasHorizontalOverflow(element: HTMLElement) {
  return element.scrollWidth - element.clientWidth > 0.25;
}

function updateFloatingScrollbar() {
  if (!floatingScrollbar || !activeTable || !activeScrollWrap || !hasHorizontalOverflow(activeScrollWrap)) {
    hideFloatingScrollbar();
    return;
  }
  const rect = activeTable.getBoundingClientRect();
  const viewportWidth = window.innerWidth || document.documentElement.clientWidth;
  const viewportHeight = window.innerHeight || document.documentElement.clientHeight;
  const gutter = 12;
  const bottomOffset = 12;
  if (rect.top >= viewportHeight - bottomOffset || rect.bottom <= 0) {
    hideFloatingScrollbar();
    return;
  }
  const left = Math.max(rect.left + floatingScrollbarSideInset, gutter);
  const right = Math.min(rect.right - floatingScrollbarSideInset, viewportWidth - gutter);
  const width = right - left;
  if (width < 80) {
    hideFloatingScrollbar();
    return;
  }
  floatingScrollbar.style.left = `${left}px`;
  floatingScrollbar.style.width = `${width}px`;
  floatingScrollbar.style.bottom = `${bottomOffset}px`;
  floatingScrollbar.classList.add('is-visible');
  syncFloatingFromTable();
}

function requestUpdate() {
  if (updateFrame !== undefined) {
    return;
  }
  updateFrame = window.requestAnimationFrame(() => {
    updateFrame = undefined;
    updateFloatingScrollbar();
  });
}

function hideFloatingScrollbar() {
  floatingScrollbar?.classList.remove('is-visible');
}

function syncFloatingFromTable() {
  if (syncingFromFloating || !floatingTrack || !floatingThumb || !activeScrollWrap) {
    return;
  }
  updateFloatingThumb(activeScrollWrap, floatingTrack, floatingThumb);
}

function updateFloatingThumb(scrollWrap: HTMLElement, track: HTMLElement, thumb: HTMLElement) {
  const trackWidth = track.clientWidth;
  const scrollableWidth = scrollWrap.scrollWidth - scrollWrap.clientWidth;
  if (trackWidth <= 0 || scrollableWidth <= 0) {
    thumb.style.width = `${Math.max(0, trackWidth)}px`;
    thumb.style.transform = 'translateX(0px)';
    return;
  }

  const proportionalWidth = (scrollWrap.clientWidth / scrollWrap.scrollWidth) * trackWidth;
  const thumbWidth = Math.min(trackWidth, Math.max(minimumThumbWidth, proportionalWidth));
  const movableWidth = Math.max(0, trackWidth - thumbWidth);
  const scrollRatio = scrollWrap.scrollLeft / scrollableWidth;
  thumb.style.width = `${thumbWidth}px`;
  thumb.style.transform = `translateX(${movableWidth * scrollRatio}px)`;
}

function scrollTableToThumbPosition(clientX: number) {
  if (!floatingTrack || !activeScrollWrap) {
    return;
  }
  const trackRect = floatingTrack.getBoundingClientRect();
  const trackWidth = floatingTrack.clientWidth;
  const scrollableWidth = activeScrollWrap.scrollWidth - activeScrollWrap.clientWidth;
  if (trackWidth <= 0 || scrollableWidth <= 0) {
    return;
  }

  const proportionalWidth = (activeScrollWrap.clientWidth / activeScrollWrap.scrollWidth) * trackWidth;
  const thumbWidth = Math.min(trackWidth, Math.max(minimumThumbWidth, proportionalWidth));
  const movableWidth = Math.max(1, trackWidth - thumbWidth);
  const thumbLeft = Math.min(Math.max(clientX - trackRect.left - thumbWidth / 2, 0), movableWidth);
  syncingFromFloating = true;
  activeScrollWrap.scrollLeft = (thumbLeft / movableWidth) * scrollableWidth;
  window.requestAnimationFrame(() => {
    syncingFromFloating = false;
    syncFloatingFromTable();
  });
}

function handleFloatingTrackPointerDown(event: PointerEvent) {
  event.preventDefault();
  scrollTableToThumbPosition(event.clientX);
  dragStartClientX = event.clientX;
  dragStartScrollLeft = activeScrollWrap?.scrollLeft ?? 0;
  setDraggingScrollbar(true);
}

function handleFloatingThumbPointerDown(event: PointerEvent) {
  event.preventDefault();
  event.stopPropagation();
  dragStartClientX = event.clientX;
  dragStartScrollLeft = activeScrollWrap?.scrollLeft ?? 0;
  setDraggingScrollbar(true);
}

function handleFloatingPointerMove(event: PointerEvent) {
  if (!draggingScrollbar || !floatingTrack || !activeScrollWrap) {
    return;
  }

  const trackWidth = floatingTrack.clientWidth;
  const scrollableWidth = activeScrollWrap.scrollWidth - activeScrollWrap.clientWidth;
  const proportionalWidth = (activeScrollWrap.clientWidth / activeScrollWrap.scrollWidth) * trackWidth;
  const thumbWidth = Math.min(trackWidth, Math.max(minimumThumbWidth, proportionalWidth));
  const movableWidth = trackWidth - thumbWidth;
  if (trackWidth <= 0 || scrollableWidth <= 0 || movableWidth <= 0) {
    return;
  }

  const deltaX = event.clientX - dragStartClientX;
  syncingFromFloating = true;
  activeScrollWrap.scrollLeft = dragStartScrollLeft + (deltaX / movableWidth) * scrollableWidth;
  window.requestAnimationFrame(() => {
    syncingFromFloating = false;
    syncFloatingFromTable();
  });
}

function handleFloatingPointerUp() {
  if (!draggingScrollbar) {
    return;
  }
  setDraggingScrollbar(false);
  requestUpdate();
}

function setDraggingScrollbar(active: boolean) {
  draggingScrollbar = active;
  document.body.classList.toggle('platform-floating-scrollbar-dragging', active);
}

function handleTableWheel(event: WheelEvent) {
  const table = event.currentTarget;
  if (!(table instanceof HTMLElement)) {
    return;
  }
  activateTable(table);
  if (!event.shiftKey || !activeScrollWrap || Math.abs(event.deltaY) <= Math.abs(event.deltaX)) {
    return;
  }
  activeScrollWrap.scrollLeft += event.deltaY;
  event.preventDefault();
  requestUpdate();
}
