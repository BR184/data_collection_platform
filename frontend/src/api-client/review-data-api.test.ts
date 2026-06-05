import { beforeEach, describe, expect, it, vi } from 'vitest';
import { reviewDataApi } from './review-data-api';

vi.mock('./request', () => ({
  request: vi.fn(() => Promise.resolve({})),
  requestBlob: vi.fn(() => Promise.resolve(new Blob())),
  EXPORT_REQUEST_TIMEOUT_MS: 180_000,
}));

import { request, requestBlob } from './request';

describe('reviewDataApi tag selection query contract', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('serializes tagSelections for review record list and export endpoints', () => {
    reviewDataApi.getReviewDataRecords({
      tagSelections: [{ groupKey: 'module', valueKeys: ['sketch'] }],
      page: 2,
      size: 50,
    });

    expect(request).toHaveBeenCalledWith(
      expect.stringContaining(
        `/api/review-data/records?page=2&size=50&tagSelections=${encodeURIComponent(JSON.stringify([{ groupKey: 'module', valueKeys: ['sketch'] }]))}`,
      ),
    );

    reviewDataApi.exportReviewDataRecordsWorkbook({
      tagSelections: [{ groupKey: 'problem_status', valueKeys: ['open', 'fixed'] }],
    });

    expect(requestBlob).toHaveBeenCalledWith(
      expect.stringContaining(
        `/api/review-data/records/export?tagSelections=${encodeURIComponent(JSON.stringify([{ groupKey: 'problem_status', valueKeys: ['open', 'fixed'] }]))}`,
      ),
      expect.any(Object),
    );
  });
});
