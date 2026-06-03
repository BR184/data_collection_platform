import { beforeEach, describe, expect, it, vi } from 'vitest';
import { integrationTestsApi } from './integration-tests-api';

vi.mock('./request', () => ({
  request: vi.fn(() => Promise.resolve({})),
  requestText: vi.fn(() => Promise.resolve('csv')),
  requestBlob: vi.fn(() => Promise.resolve(new Blob(['xlsx']))),
  EXPORT_REQUEST_TIMEOUT_MS: 180_000,
}));

import { request, requestBlob, requestText } from './request';

describe('integrationTestsApi source instance query contract', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('passes sourceInstance through phase, summary, detail, and export endpoints', () => {
    integrationTestsApi.getIntegrationTestPhaseOptions(null, 'cc');
    expect(request).toHaveBeenCalledWith('/api/integration-tests/phase-options?sourceInstance=cc');

    integrationTestsApi.getIntegrationTestSummary({ sourceInstance: 'cc', testingPhase: 'R1' });
    expect(request).toHaveBeenCalledWith('/api/integration-tests/summary?testingPhase=R1&sourceInstance=cc');

    integrationTestsApi.getIntegrationTestDetails({
      sourceInstance: 'cc',
      testingPhase: 'R1',
      moduleName: 'Sketch',
      page: 1,
      size: 20,
    });
    expect(request).toHaveBeenCalledWith(
      '/api/integration-tests/details?page=1&size=20&testingPhase=R1&moduleName=Sketch&sourceInstance=cc',
    );

    integrationTestsApi.exportIntegrationTestDetails({
      sourceInstance: 'cc',
      testingPhase: 'R1',
      moduleName: 'Sketch',
    });
    expect(requestText).toHaveBeenCalledWith(
      '/api/integration-tests/details/export?testingPhase=R1&moduleName=Sketch&sourceInstance=cc',
      expect.any(Object),
    );

    integrationTestsApi.exportIntegrationTestModuleFunctionWorkbook({
      sourceInstance: 'cc',
      testingPhase: 'R1',
    });
    expect(requestBlob).toHaveBeenCalledWith(
      '/api/integration-tests/module-function/export?testingPhase=R1&sourceInstance=cc',
      expect.any(Object),
    );

    integrationTestsApi.exportIntegrationTestComparisonWorkbook({
      sourceInstance: 'cc',
      basePhase: 'R0',
      targetPhase: 'R1',
    });
    expect(requestBlob).toHaveBeenCalledWith(
      '/api/integration-tests/comparison/export?basePhase=R0&targetPhase=R1&sourceInstance=cc',
      expect.any(Object),
    );
  });
});
