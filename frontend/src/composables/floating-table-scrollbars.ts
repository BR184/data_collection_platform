const managedTableClass = 'platform-floating-scrollbar-managed';
const externalScrollbarClass = 'platform-table-floating-horizontal';
const spacerClass = 'platform-table-floating-horizontal-spacer';

let floatingScrollbar: HTMLElement | undefined;
let floatingSpacer: HTMLElement | undefined;
let activeScrollWrap: HTMLElement | undefined;
let activeTable: HTMLElement | undefined;
let mutationObserver: MutationObserver | undefined;
let resizeObserver: ResizeObserver | undefined;
let updateFrame: number | undefined;
let syncingFromTable = false;
let syncingFromFloating = false;
let draggingScrollbar = false;

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
}

function ensureFloatingScrollbar() {
  if (floatingScrollbar) {
    return;
  }
  floatingScrollbar = document.createElement('div');
  floatingScrollbar.className = externalScrollbarClass;
  floatingScrollbar.setAttribute('aria-hidden', 'true');
  floatingSpacer = document.createElement('div');
  floatingSpacer.className = spacerClass;
  floatingScrollbar.appendChild(floatingSpacer);
  floatingScrollbar.addEventListener('scroll', handleFloatingScroll, { passive: true });
  floatingScrollbar.addEventListener('pointerdown', handleFloatingPointerDown, { passive: true });
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
  if (!floatingScrollbar || !floatingSpacer || !activeTable || !activeScrollWrap || !hasHorizontalOverflow(activeScrollWrap)) {
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
  const left = Math.max(rect.left, gutter);
  const right = Math.min(rect.right, viewportWidth - gutter);
  const width = right - left;
  if (width < 80) {
    hideFloatingScrollbar();
    return;
  }
  floatingSpacer.style.width = `${activeScrollWrap.scrollWidth}px`;
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
  if (syncingFromFloating || !floatingScrollbar || !activeScrollWrap) {
    return;
  }
  syncingFromTable = true;
  floatingScrollbar.scrollLeft = activeScrollWrap.scrollLeft;
  window.requestAnimationFrame(() => {
    syncingFromTable = false;
  });
}

function handleFloatingScroll() {
  if (syncingFromTable || !floatingScrollbar || !activeScrollWrap) {
    return;
  }
  syncingFromFloating = true;
  activeScrollWrap.scrollLeft = floatingScrollbar.scrollLeft;
  window.requestAnimationFrame(() => {
    syncingFromFloating = false;
  });
}

function handleFloatingPointerDown() {
  setDraggingScrollbar(true);
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
