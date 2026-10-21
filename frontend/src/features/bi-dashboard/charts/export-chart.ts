import type { ReadViewTransport } from '../../../types/api/read-view';
import type { BlobResponse } from '../../../api-client/request';
import { init } from './bi-echarts-runtime';
import type { BiChart } from './BiChart';
import { registerBiChartTheme } from './theme';
import { biDashboardApi } from '../../../api-client/bi-dashboard-api';
import { formatExportFileDate } from '../../../utils/csv-download';
import type { BiChartDownloadContext } from '../data/types';

export interface BiChartExportRequest<TData> {
  downloadContext: BiChartDownloadContext;
  title: string;
  /** 图表业务口径与达标标准说明（看板问号词条），仅 Excel 导出写入说明行；PNG 不使用。 */
  description?: string;
  chart: BiChart<TData>;
  data: TData;
}

/** 使用页面完整身份授权并离屏生成 PNG；只返回文件，下载由页面接受边界提交。 */
export async function exportBiChartPng<TData>(request: BiChartExportRequest<TData>, read: ReadViewTransport): Promise<{ url: string; filename: string }> {
  const snapshot = {
    context: structuredClone(request.downloadContext),
    data: structuredClone(request.data),
    title: request.title,
    chart: request.chart,
  };
  await biDashboardApi.authorizeDownload({
    ...snapshot.context,
    chartTemplateId: snapshot.chart.templateId,
  }, read);

  const size = snapshot.chart.exportSize(snapshot.data);
  const root = document.createElement('div');
  root.style.cssText = `position:fixed;left:-30000px;top:0;width:${size.width}px;height:${size.height}px;background:#fff;`;
  document.body.appendChild(root);
  const chart = init(root, registerBiChartTheme(), { renderer: 'canvas', width: size.width, height: size.height });

  try {
    const option = snapshot.chart.build(snapshot.data, { mode: 'export', width: size.width, height: size.height });
    option.animation = false;
    chart.setOption(option, true);
    chart.getZr().flush();
    const url = chart.getDataURL({ type: 'png', pixelRatio: 1, backgroundColor: '#FFFFFF' });
    return { url, filename: `${sanitizeFilename(snapshot.title)}.png` };
  } finally {
    chart.dispose();
    root.remove();
  }
}

/**
 * 使用页面完整身份生成标准 .xlsx，返回文件而不执行下载。
 *
 * 职责边界：前端只按图表语义提取表格（各图表类的 `excelTable`），后端负责下载授权与 OOXML 序列化，
 * 因此这里不再本地拼接 XML Spreadsheet，也不再单独调用授权端点（导出端点内部已复用同一道授权门）。
 * 文件名优先采用服务端 `Content-Disposition` 提供的名称，缺失时回退到本地时间戳命名。
 */
export async function exportBiChartExcel<TData>(request: BiChartExportRequest<TData>, read: ReadViewTransport): Promise<BlobResponse> {
  const snapshot = {
    context: structuredClone(request.downloadContext),
    data: structuredClone(request.data),
    title: request.title,
    description: request.description ?? '',
    chart: request.chart,
  };
  const table = snapshot.chart.excelTable(snapshot.data);
  const { blob, filename } = await biDashboardApi.exportExcel({
    ...snapshot.context,
    chartTemplateId: snapshot.chart.templateId,
    title: snapshot.title,
    explanation: snapshot.description,
    headers: table.headers,
    rows: table.rows,
  }, read);
  const fallback = `${sanitizeFilename(snapshot.title)}_${formatExportFileDate(new Date())}.xlsx`;
  return { blob, filename: filename || fallback };
}

function sanitizeFilename(value: string): string {
  return value.replace(/[\\/:*?"<>|]/g, '-').trim() || 'BI图表';
}
