import { beforeEach, describe, expect, it, vi } from 'vitest';
import { reviewDataApi } from './review-data-api';

vi.mock('./request', () => ({
  request: vi.fn(() => Promise.resolve({})),
  requestBlob: vi.fn(() => Promise.resolve(new Blob())),
  EXPORT_REQUEST_TIMEOUT_MS: 180_000,
}));

import { request, requestBlob } from './request';

describe('reviewDataApi source instance query contract', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('serializes sourceInstance for review record list and export endpoints', () => {
    reviewDataApi.getReviewDataRecords({
      sourceInstance: 'cc',
      page: 2,
      size: 50,
    });

    expect(request).toHaveBeenCalledWith(
      expect.stringContaining('/api/review-data/records?page=2&size=50&sourceInstance=cc'),
    );

    reviewDataApi.exportReviewDataRecordsWorkbook({
      sourceInstance: 'dgm',
    });

    expect(requestBlob).toHaveBeenCalledWith(
      expect.stringContaining('/api/review-data/records/export?sourceInstance=dgm'),
      expect.any(Object),
    );

    reviewDataApi.getReviewDataFilterOptions();

    expect(request).toHaveBeenLastCalledWith('/api/review-data/records/filter-options');
  });
});
