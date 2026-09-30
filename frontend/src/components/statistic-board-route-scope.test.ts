import { describe, expect, it } from 'vitest';
import { resolveStatisticBoardRouteScopeState } from './statistic-board-route-scope';
import type { DataScopeProvider } from '../types/data-scope';

const provider: DataScopeProvider = {
  id: 'customer-issue-milestone',
  label: '里程碑',
  queryKey: 'milestoneTitle',
  mode: 'single-select',
  defaultStrategy: 'first-available',
};

describe('resolveStatisticBoardRouteScopeState', () => {
  it('waits while the scope catalog is still loading', () => {
    expect(resolveStatisticBoardRouteScopeState(provider, [], false, undefined)).toEqual({
      ready: false,
      catalogMissing: false,
    });
  });

  it('reports a catalog state instead of widening to all scopes', () => {
    expect(resolveStatisticBoardRouteScopeState(provider, [], true, undefined)).toEqual({
      ready: false,
      catalogMissing: true,
    });
  });

  it('requires an explicit scope value once options exist', () => {
    expect(resolveStatisticBoardRouteScopeState(provider, [{ value: 'CC2026R3' }], true, undefined).ready).toBe(false);
    expect(resolveStatisticBoardRouteScopeState(provider, [{ value: 'CC2026R3' }], true, 'CC2026R3').ready).toBe(true);
  });

  it('stays ready for boards without a first-available scope provider', () => {
    expect(resolveStatisticBoardRouteScopeState(null, [], true, undefined)).toEqual({
      ready: true,
      catalogMissing: false,
    });
    expect(
      resolveStatisticBoardRouteScopeState({ ...provider, defaultStrategy: 'empty' }, [], true, undefined).ready,
    ).toBe(true);
  });
});
