import { beforeEach, describe, expect, it } from 'vitest';
import { usePageAutoRefreshPreference } from './usePageAutoRefreshPreference';

describe('usePageAutoRefreshPreference', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it('defaults to enabled and persists user changes', () => {
    const first = usePageAutoRefreshPreference();
    expect(first.autoRefreshOnEnter.value).toBe(true);

    first.toggleAutoRefreshOnEnter();
    expect(first.autoRefreshOnEnter.value).toBe(false);

    const second = usePageAutoRefreshPreference();
    expect(second.autoRefreshOnEnter.value).toBe(false);
  });
});
