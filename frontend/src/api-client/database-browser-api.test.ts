import { beforeEach, describe, expect, it, vi } from 'vitest';
import { databaseBrowserApi } from './database-browser-api';

vi.mock('./request', () => ({
  request: vi.fn(() => Promise.resolve({})),
}));

import { request } from './request';

describe('databaseBrowserApi', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('reads published table rows with an explicit context and scope', () => {
    const read = { readContextId: 'held-view', readScope: { tableName: 'issue_fact' }, onReadView: vi.fn() };
    databaseBrowserApi.getDatabaseTableRows({ tableName: 'issue_fact', page: 2 }, read);
    expect(request).toHaveBeenCalledWith('/api/database-browser/rows?tableName=issue_fact&page=2&size=20', read);
  });
  it('loads the lightweight table list by default', () => {
    databaseBrowserApi.getDatabaseTables();

    expect(request).toHaveBeenCalledWith('/api/database-browser/tables');
  });

  it('can include source preview table options on demand', () => {
    databaseBrowserApi.getDatabaseTables({ includeSourceTables: true });

    expect(request).toHaveBeenCalledWith('/api/database-browser/tables?includeSourceTables=true');
  });
});
