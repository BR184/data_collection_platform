import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => {
  const authorizeDownload = vi.fn(async () => undefined);
  const flush = vi.fn();
  const setOption = vi.fn();
  const getDataURL = vi.fn(() => 'data:image/png;base64,AAAA');
  const dispose = vi.fn();
  const init = vi.fn(() => ({
    setOption,
    getZr: () => ({ flush }),
    getDataURL,
    dispose,
  }));
  return { authorizeDownload, flush, setOption, getDataURL, dispose, init };
});

vi.mock('./bi-echarts-runtime', () => ({ init: mocks.init, registerTheme: vi.fn() }));
vi.mock('../../../api-client/bi-dashboard-api', () => ({
  biDashboardApi: { authorizeDownload: mocks.authorizeDownload },
}));

import { SubmissionTrendComboChart } from './types';
import { exportBiChartPng } from './export-chart';

describe('BI chart PNG export', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders complete data without animation before reading the canvas', async () => {
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    const initialChildren = document.body.childElementCount;

    await exportBiChartPng({
      productVersionId: 10,
      pageKey: 'coding',
      sourceVersion: 'source-1',
      title: '提交趋势',
      chart: new SubmissionTrendComboChart(),
      data: {
        periods: Array.from({ length: 16 }, (_, index) => `07-${String(index + 1).padStart(2, '0')}`),
        commits: Array.from({ length: 16 }, (_, index) => index + 1),
        mergeRequests: Array.from({ length: 16 }, (_, index) => index),
      },
    });

    expect(mocks.authorizeDownload).toHaveBeenCalledWith(10, 'coding', 'submission-trend-combo', 'source-1');
    const option = mocks.setOption.mock.calls[0]?.[0] as { animation?: boolean; dataZoom?: unknown[] };
    expect(option.animation).toBe(false);
    expect(option.dataZoom).toEqual([]);
    expect(mocks.flush).toHaveBeenCalledOnce();
    expect(mocks.flush.mock.invocationCallOrder[0]).toBeLessThan(mocks.getDataURL.mock.invocationCallOrder[0]!);
    expect(click).toHaveBeenCalledOnce();
    expect(mocks.dispose).toHaveBeenCalledOnce();
    expect(document.body.childElementCount).toBe(initialChildren);

    click.mockRestore();
  });
});
