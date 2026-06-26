import { ref } from 'vue';

const STORAGE_KEY = 'platform:auto-refresh-page-data-on-enter';
const SCOPED_STORAGE_PREFIX = `${STORAGE_KEY}:`;

type ScopeKeySource = string | (() => string);

function resolveScopeKey(scopeKey?: ScopeKeySource) {
  return typeof scopeKey === 'function' ? scopeKey() : scopeKey;
}

function storageKey(scopeKey?: string) {
  return scopeKey ? `${SCOPED_STORAGE_PREFIX}${scopeKey}` : STORAGE_KEY;
}

function readStoredPreference(scopeKey?: string) {
  if (typeof window === 'undefined') {
    return true;
  }
  const scopedValue = window.localStorage.getItem(storageKey(scopeKey));
  if (scopedValue != null) {
    return scopedValue !== 'false';
  }
  return window.localStorage.getItem(STORAGE_KEY) === 'true';
}

export function usePageAutoRefreshPreference(scopeKey?: ScopeKeySource) {
  const autoRefreshOnEnter = ref(readStoredPreference(resolveScopeKey(scopeKey)));

  function setAutoRefreshOnEnter(enabled: boolean) {
    autoRefreshOnEnter.value = enabled;
    if (typeof window !== 'undefined') {
      window.localStorage.setItem(storageKey(resolveScopeKey(scopeKey)), String(enabled));
    }
  }

  function toggleAutoRefreshOnEnter() {
    setAutoRefreshOnEnter(!autoRefreshOnEnter.value);
  }

  return {
    autoRefreshOnEnter,
    readAutoRefreshOnEnter: () => readStoredPreference(resolveScopeKey(scopeKey)),
    setAutoRefreshOnEnter,
    toggleAutoRefreshOnEnter,
  };
}
