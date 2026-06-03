import { beforeEach, describe, expect, it, vi } from 'vitest';
import { issueRecordsApi } from './issue-records-api';

vi.mock('./request', () => ({
  request: vi.fn(() => Promise.resolve({})),
  requestText: vi.fn(() => Promise.resolve('csv')),
  EXPORT_REQUEST_TIMEOUT_MS: 180_000,
}));

import { request, requestText } from './request';

describe('issueRecordsApi source instance query contract', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('passes sourceInstance to issue search list and export endpoints', () => {
    issueRecordsApi.getSystemTestIssueSearchRecords({
      sourceInstance: 'cc',
      searchType: 'issueIid',
      keyword: '22637',
      issueIid: '22637',
      page: 2,
      size: 50,
    });

    expect(request).toHaveBeenCalledWith(
      expect.stringContaining('/api/question-metrics/issues?page=2&size=50&keyword=22637&searchType=issueIid&issueIid=22637&sourceInstance=cc'),
    );

    issueRecordsApi.exportSystemTestIssueSearchRecords({
      sourceInstance: 'cc',
      searchType: 'issueIid',
      keyword: '22637',
      issueIid: '22637',
    });

    expect(requestText).toHaveBeenCalledWith(
      expect.stringContaining('/api/question-metrics/issues/export?keyword=22637&searchType=issueIid&issueIid=22637&sourceInstance=cc'),
      expect.any(Object),
    );

    issueRecordsApi.getSystemTestIssueSearchFilterOptions(null, 'cc');

    expect(request).toHaveBeenCalledWith('/api/question-metrics/issues/filter-options?sourceInstance=cc');
  });
});
