import { beforeEach, describe, expect, it, vi } from 'vitest';
import { issueRecordsApi } from './issue-records-api';

vi.mock('./request', () => ({
  request: vi.fn(() => Promise.resolve({})),
  requestBlob: vi.fn(() => Promise.resolve(new Blob())),
  EXPORT_REQUEST_TIMEOUT_MS: 180_000,
}));

import { request, requestBlob } from './request';

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

    expect(requestBlob).toHaveBeenCalledWith(
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
      'filterGroup={"logic":"AND","conditions":[{"fieldKey":"assigneeName","operator":"intersects","value":null,"valueType":"LABEL_GROUP","labelGroupId":1,"labelGroupName":"核心人员"}]}',
    );

    issueRecordsApi.exportSystemTestIssueSearchRecords({ filterGroup });

    expect(decodeURIComponent(String(vi.mocked(requestBlob).mock.calls[0][0]))).toContain(
      '/api/question-metrics/issues/export?filterGroup={"logic":"AND","conditions":[{"fieldKey":"assigneeName","operator":"intersects","value":null,"valueType":"LABEL_GROUP","labelGroupId":1,"labelGroupName":"核心人员"}]}',
    );
  });

  it('passes CC_PRODUCT display-field filters to list and export endpoints', () => {
    const params = {
      topic: 'cc-product' as const,
      functionName: '装配',
      customerName: '郑州新世纪',
      handlerName: '王五',
      assigneeName: '张三',
      testingPhase: '新增需求',
      fixUser: '李四',
      delayCause: '需求变更',
      plannedResolutionAtStart: '2026-08-01',
      plannedResolutionAtEnd: '2026-08-31',
      plannedMergeVersionBranch: 'CC2026R4',
      retentionHoursMin: 24,
      retentionHoursMax: 72,
      createdAtStart: '2026-01-01',
      createdAtEnd: '2026-08-31',
      updatedAtStart: '2026-07-01',
      updatedAtEnd: '2026-08-31',
    };

    issueRecordsApi.getCustomerIssueRecords(params);

    expect(decodeURIComponent(String(vi.mocked(request).mock.calls[0][0]))).toContain(
      '/api/customer-issues/records?topic=cc-product&page=1&size=20&functionName=装配&customerName=郑州新世纪&handlerName=王五&assigneeName=张三&testingPhase=新增需求&fixUser=李四&delayCause=需求变更&plannedResolutionAtStart=2026-08-01&plannedResolutionAtEnd=2026-08-31&plannedMergeVersionBranch=CC2026R4&retentionHoursMin=24&retentionHoursMax=72&createdAtStart=2026-01-01&createdAtEnd=2026-08-31&updatedAtStart=2026-07-01&updatedAtEnd=2026-08-31',
    );

    issueRecordsApi.exportCustomerIssueRecords(params);

    expect(decodeURIComponent(String(vi.mocked(requestBlob).mock.calls[0][0]))).toContain(
      '/api/customer-issues/records/export?topic=cc-product&functionName=装配&customerName=郑州新世纪&handlerName=王五&assigneeName=张三&testingPhase=新增需求&fixUser=李四&delayCause=需求变更&plannedResolutionAtStart=2026-08-01&plannedResolutionAtEnd=2026-08-31&plannedMergeVersionBranch=CC2026R4&retentionHoursMin=24&retentionHoursMax=72&createdAtStart=2026-01-01&createdAtEnd=2026-08-31&updatedAtStart=2026-07-01&updatedAtEnd=2026-08-31',
    );
  });

});
