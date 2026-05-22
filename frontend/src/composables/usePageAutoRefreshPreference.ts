import { ref } from 'vue';

const STORAGE_KEY = 'platform:auto-refresh-page-data-on-enter';

function readStoredPreference() {
  if (typeof window === 'undefined') {
    return true;
  }
  return window.localStorage.getItem(STORAGE_KEY) !== 'false';
}

export function usePageAutoRefreshPreference() {
  const autoRefreshOnEnter = ref(readStoredPreference());

  function setAutoRefreshOnEnter(enabled: boolean) {
    autoRefreshOnEnter.value = enabled;
    if (typeof window !== 'undefined') {
      window.localStorage.setItem(STORAGE_KEY, String(enabled));
    }
  }

  function toggleAutoRefreshOnEnter() {
    setAutoRefreshOnEnter(!autoRefreshOnEnter.value);
  }

  return {
    autoRefreshOnEnter,
    setAutoRefreshOnEnter,
    toggleAutoRefreshOnEnter,
  };
}
