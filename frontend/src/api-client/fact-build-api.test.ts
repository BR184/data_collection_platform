import { beforeEach, describe, expect, it, vi } from 'vitest';
import { factBuildApi } from './fact-build-api';

vi.mock('./request', () => ({
  request: vi.fn(() => Promise.resolve({})),
}));

import { request } from './request';

describe('factBuildApi', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('submits a full rebuild for the selected source', () => {
    factBuildApi.rebuildFacts(7);

    expect(request).toHaveBeenCalledWith('/api/facts/rebuild?configId=7', {
      method: 'POST',
    });
  });
});
