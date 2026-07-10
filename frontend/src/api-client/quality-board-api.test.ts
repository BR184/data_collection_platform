import { beforeEach, describe, expect, it, vi } from 'vitest';
import { qualityBoardApi } from './quality-board-api';

vi.mock('./request', () => ({
  request: vi.fn(() => Promise.resolve({})),
  requestBlob: vi.fn(() => Promise.resolve(new Blob())),
  EXPORT_REQUEST_TIMEOUT_MS: 180_000,
}));

import { requestBlob } from './request';

describe('qualityBoardApi export query contract', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('serializes current project and CC/DGM source for R&D chart and code review exports', () => {
    qualityBoardApi.exportQualityBoardRdChart('CC2026R4', 'dgm', 'assignee-defect-density');

    expect(requestBlob).toHaveBeenCalledWith(
      expect.stringContaining('/api/quality-board/rd/charts/assignee-defect-density/export?projectName=CC2026R4&codeReviewSource=dgm'),
      expect.any(Object),
    );

    qualityBoardApi.exportQualityBoardRdCodeReviewRecords('CC2026R4', 'cc');

    expect(requestBlob).toHaveBeenLastCalledWith(
      expect.stringContaining('/api/quality-board/rd/code-review-records/export?projectName=CC2026R4&codeReviewSource=cc'),
      expect.any(Object),
    );
  });
});
