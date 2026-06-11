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

  it('serializes label group conditions through filterGroup for list and export endpoints', () => {
    const filterGroup = {
      logic: 'AND' as const,
      conditions: [
        {
          fieldKey: 'moduleName',
          operator: 'eq' as const,
          value: null,
          valueType: 'LABEL_GROUP',
          labelGroupId: 1,
          labelGroupName: '核心模块',
        },
      ],
    };

    reviewDataApi.getReviewDataRecords({
      filterGroup,
      page: 1,
      size: 20,
    });

    expect(decodeURIComponent(String(vi.mocked(request).mock.calls[0][0]))).toContain(
      'filterGroup={"logic":"AND","conditions":[{"fieldKey":"moduleName","operator":"eq","value":null,"valueType":"LABEL_GROUP","labelGroupId":1,"labelGroupName":"核心模块"}]}',
    );

    reviewDataApi.exportReviewDataRecordsWorkbook({
      filterGroup,
    });

    expect(decodeURIComponent(String(vi.mocked(requestBlob).mock.calls[0][0]))).toContain(
      '/api/review-data/records/export?filterGroup={"logic":"AND","conditions":[{"fieldKey":"moduleName","operator":"eq","value":null,"valueType":"LABEL_GROUP","labelGroupId":1,"labelGroupName":"核心模块"}]}',
    );
  });
});
