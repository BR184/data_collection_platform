import { beforeEach, describe, expect, it } from 'vitest';
import { usePageAutoRefreshPreference } from './usePageAutoRefreshPreference';

describe('usePageAutoRefreshPreference', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it('defaults to disabled and persists user changes', () => {
    const first = usePageAutoRefreshPreference();
    expect(first.autoRefreshOnEnter.value).toBe(false);

    first.toggleAutoRefreshOnEnter();
    expect(first.autoRefreshOnEnter.value).toBe(true);

    const second = usePageAutoRefreshPreference();
    expect(second.autoRefreshOnEnter.value).toBe(true);
  });
});
