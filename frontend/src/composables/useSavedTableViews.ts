import { computed, ref } from 'vue';
import type { LocationQueryRaw } from 'vue-router';

export interface SavedTableViewSnapshot {
  routeQuery: Record<string, string>;
  viewPrefs?: unknown;
}

export interface SavedTableView {
  id: string;
  name: string;
  createdAt: string;
  updatedAt: string;
  snapshot: SavedTableViewSnapshot;
}

interface UseSavedTableViewsOptions {
  scopeKey: () => string;
  getCurrentSnapshot: () => SavedTableViewSnapshot;
  applySnapshot: (snapshot: SavedTableViewSnapshot) => void | Promise<void>;
  notifySuccess: (message: string) => void;
  notifyWarning: (message: string) => void;
  confirmDelete: (viewName: string) => Promise<unknown>;
}

const STORAGE_PREFIX = 'saved-table-views:';
const MAX_SAVED_VIEWS = 30;

export function useSavedTableViews(options: UseSavedTableViewsOptions) {
  const savedViews = ref<SavedTableView[]>([]);
  const loadedScopeKey = ref('');

  const hasSavedViews = computed(() => savedViews.value.length > 0);

  function loadSavedViews() {
    const key = options.scopeKey();
    loadedScopeKey.value = key;
    savedViews.value = readViews(key);
  }

  function ensureLoaded() {
    if (loadedScopeKey.value !== options.scopeKey()) {
      loadSavedViews();
    }
  }

  function saveCurrentView(name: string) {
    ensureLoaded();
    const normalizedName = name.trim();
    if (!normalizedName) {
      options.notifyWarning('请先填写视图名称');
      return false;
    }

    const now = new Date().toISOString();
    const existing = savedViews.value.find((view) => view.name === normalizedName);
    if (existing) {
      existing.snapshot = normalizeSnapshot(options.getCurrentSnapshot());
      existing.updatedAt = now;
    } else {
      savedViews.value.unshift({
        id: createViewId(),
        name: normalizedName,
        createdAt: now,
        updatedAt: now,
        snapshot: normalizeSnapshot(options.getCurrentSnapshot()),
      });
    }

    savedViews.value = savedViews.value
      .slice()
      .sort((left, right) => right.updatedAt.localeCompare(left.updatedAt))
      .slice(0, MAX_SAVED_VIEWS);
    persistViews(options.scopeKey(), savedViews.value);
    options.notifySuccess(existing ? '已更新同名固定视图' : '已固定当前表格');
    return true;
  }

  async function applySavedView(viewId: string) {
    ensureLoaded();
    const view = savedViews.value.find((item) => item.id === viewId);
    if (!view) {
      options.notifyWarning('固定视图不存在或已被删除');
      return;
    }
    await options.applySnapshot(view.snapshot);
    options.notifySuccess(`已切换到「${view.name}」`);
  }

  async function deleteSavedView(viewId: string) {
    ensureLoaded();
    const view = savedViews.value.find((item) => item.id === viewId);
    if (!view) {
      return;
    }
    try {
      await options.confirmDelete(view.name);
    } catch {
      return;
    }
    savedViews.value = savedViews.value.filter((item) => item.id !== viewId);
    persistViews(options.scopeKey(), savedViews.value);
    options.notifySuccess('固定视图已删除');
  }

  return {
    savedViews,
    hasSavedViews,
    loadSavedViews,
    saveCurrentView,
    applySavedView,
    deleteSavedView,
  };
}

export function normalizeRouteQueryForSnapshot(query: Record<string, unknown>) {
  const snapshot: Record<string, string> = {};
  for (const [key, rawValue] of Object.entries(query)) {
    if (rawValue == null || rawValue === '') {
      continue;
    }
    snapshot[key] = Array.isArray(rawValue)
      ? String(rawValue[0] ?? '')
      : String(rawValue);
  }
  return snapshot;
}

export function toRouteQueryFromSnapshot(snapshot: SavedTableViewSnapshot): LocationQueryRaw {
  return { ...snapshot.routeQuery };
}

function storageKey(scopeKey: string) {
  return `${STORAGE_PREFIX}${scopeKey}`;
}

function readViews(scopeKey: string): SavedTableView[] {
  const raw = window.localStorage.getItem(storageKey(scopeKey));
  if (!raw) {
    return [];
  }
  try {
    const parsed = JSON.parse(raw) as unknown;
    if (!Array.isArray(parsed)) {
      return [];
    }
    return parsed
      .map(normalizeSavedView)
      .filter((view): view is SavedTableView => Boolean(view))
      .slice(0, MAX_SAVED_VIEWS);
  } catch {
    return [];
  }
}

function persistViews(scopeKey: string, views: SavedTableView[]) {
  window.localStorage.setItem(storageKey(scopeKey), JSON.stringify(views));
}

function normalizeSavedView(value: unknown): SavedTableView | null {
  if (!value || typeof value !== 'object') {
    return null;
  }
  const source = value as Partial<SavedTableView>;
  const name = String(source.name ?? '').trim();
  if (!name || !source.snapshot || typeof source.snapshot !== 'object') {
    return null;
  }
  return {
    id: String(source.id || createViewId()),
    name,
    createdAt: String(source.createdAt || source.updatedAt || new Date().toISOString()),
    updatedAt: String(source.updatedAt || source.createdAt || new Date().toISOString()),
    snapshot: normalizeSnapshot(source.snapshot),
  };
}

function normalizeSnapshot(snapshot: SavedTableViewSnapshot): SavedTableViewSnapshot {
  return {
    routeQuery: normalizeRouteQueryForSnapshot(snapshot.routeQuery),
    viewPrefs: snapshot.viewPrefs,
  };
}

function createViewId() {
  if (typeof globalThis.crypto?.randomUUID === 'function') {
    return globalThis.crypto.randomUUID();
  }
  return `view-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}
