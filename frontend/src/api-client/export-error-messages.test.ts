import { afterEach, describe, expect, it, vi } from 'vitest';
import { codeReviewApi } from './code-review-api';
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

    await expect(codeReviewApi.exportCodeReviewIllegalRecords({})).rejects.toThrow('Excel 导出失败，状态码：503');
  });

});

function stubEmptyExportFailure(status: number) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => ({
      ok: false,
      status,
      headers: new Headers(),
      text: async () => '',
    } as Response)),
  );
}
