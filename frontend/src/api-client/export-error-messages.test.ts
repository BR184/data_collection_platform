import { afterEach, describe, expect, it, vi } from 'vitest';
import { codeReviewApi } from './code-review-api';
import { integrationTestsApi } from './integration-tests-api';
import { statisticBoardsApi } from './statistic-boards-api';

describe('export API error messages', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('uses Chinese fallback copy for statistic board export failures', async () => {
    stubEmptyExportFailure(502);

    await expect(statisticBoardsApi.exportStatisticBoard('system-test-defect-summary')).rejects.toThrow(
      '导出失败，状态码：502',
    );
  });

  it('uses Chinese fallback copy for code review export failures', async () => {
    stubEmptyExportFailure(503);

    await expect(codeReviewApi.exportCodeReviewIllegalRecords({})).rejects.toThrow('导出失败，状态码：503');
  });

  it('uses Chinese fallback copy for integration test export failures', async () => {
    stubEmptyExportFailure(504);

    await expect(integrationTestsApi.exportIntegrationTestDetails({})).rejects.toThrow('导出失败，状态码：504');
  });
});

function stubEmptyExportFailure(status: number) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => ({
      ok: false,
      status,
      text: async () => '',
    } as Response)),
  );
}
