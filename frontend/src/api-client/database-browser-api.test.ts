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

  it('loads the lightweight table list by default', () => {
    databaseBrowserApi.getDatabaseTables();

    expect(request).toHaveBeenCalledWith('/api/database-browser/tables');
  });

  it('can include source preview table options on demand', () => {
    databaseBrowserApi.getDatabaseTables({ includeSourceTables: true });

    expect(request).toHaveBeenCalledWith('/api/database-browser/tables?includeSourceTables=true');
  });
});
