import { init } from './bi-echarts-runtime';
import type { BiChart } from './BiChart';
import { registerBiChartTheme } from './theme';
import { biDashboardApi } from '../../../api-client/bi-dashboard-api';
import type { BiPageKey } from '../data/types';

export interface BiChartExportRequest<TData> {
  productVersionId: number;
  pageKey: BiPageKey;
  sourceVersion: string;
  title: string;
  chart: BiChart<TData>;
  data: TData;
}

/** 经后端授权后，用完整数据 option 在离屏画布生成 PNG。 */
export async function exportBiChartPng<TData>(request: BiChartExportRequest<TData>): Promise<void> {
  await biDashboardApi.authorizeDownload(
    request.productVersionId,
    request.pageKey,
    request.chart.templateId,
    request.sourceVersion,
  );

  const size = request.chart.exportSize(request.data);
  const root = document.createElement('div');
  root.style.cssText = `position:fixed;left:-30000px;top:0;width:${size.width}px;height:${size.height}px;background:#fff;`;
  document.body.appendChild(root);
  const chart = init(root, registerBiChartTheme(), { renderer: 'canvas', width: size.width, height: size.height });

  try {
    const option = request.chart.build(request.data, { mode: 'export', width: size.width, height: size.height });
    option.animation = false;
    chart.setOption(option, true);
    chart.getZr().flush();
    const url = chart.getDataURL({ type: 'png', pixelRatio: 1, backgroundColor: '#FFFFFF' });
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = `${sanitizeFilename(request.title)}.png`;
    anchor.click();
  } finally {
    chart.dispose();
    root.remove();
  }
}

function sanitizeFilename(value: string): string {
  return value.replace(/[\\/:*?"<>|]/g, '-').trim() || 'BI图表';
}
