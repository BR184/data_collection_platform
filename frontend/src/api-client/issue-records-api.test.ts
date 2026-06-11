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

  it('serializes label group conditions through filterGroup for issue search list and export endpoints', () => {
    const filterGroup = {
      logic: 'AND' as const,
      conditions: [
        {
          fieldKey: 'assigneeName',
          operator: 'eq' as const,
          value: null,
          valueType: 'LABEL_GROUP',
          labelGroupId: 1,
          labelGroupName: '核心人员',
        },
      ],
    };

    issueRecordsApi.getSystemTestIssueSearchRecords({ filterGroup });

    expect(decodeURIComponent(String(vi.mocked(request).mock.calls[0][0]))).toContain(
      'filterGroup={"logic":"AND","conditions":[{"fieldKey":"assigneeName","operator":"eq","value":null,"valueType":"LABEL_GROUP","labelGroupId":1,"labelGroupName":"核心人员"}]}',
    );

    issueRecordsApi.exportSystemTestIssueSearchRecords({ filterGroup });

    expect(decodeURIComponent(String(vi.mocked(requestText).mock.calls[0][0]))).toContain(
      '/api/question-metrics/issues/export?filterGroup={"logic":"AND","conditions":[{"fieldKey":"assigneeName","operator":"eq","value":null,"valueType":"LABEL_GROUP","labelGroupId":1,"labelGroupName":"核心人员"}]}',
    );
  });

});
