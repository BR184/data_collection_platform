import { init } from './bi-echarts-runtime';
import type { BiChart } from './BiChart';
import { registerBiChartTheme } from './theme';
import { biDashboardApi } from '../../../api-client/bi-dashboard-api';
import { downloadBlob, formatExportFileDate } from '../../../utils/csv-download';
import type { BiPageKey } from '../data/types';

export interface BiChartExportRequest<TData> {
  productVersionId: number;
  productVersionName?: string;
  pageKey: BiPageKey;
  sourceVersion: string;
  title: string;
  /** 图表业务口径与达标标准说明（看板问号词条），仅 Excel 导出写入说明行；PNG 不使用。 */
  description?: string;
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

/**
 * 导出图表数据表为标准 .xlsx。
 *
 * 职责边界：前端只按图表语义提取表格（各图表类的 `excelTable`），后端负责下载授权与 OOXML 序列化，
 * 因此这里不再本地拼接 XML Spreadsheet，也不再单独调用授权端点（导出端点内部已复用同一道授权门）。
 * 文件名优先采用服务端 `Content-Disposition` 提供的名称，缺失时回退到本地时间戳命名。
 */
export async function exportBiChartExcel<TData>(request: BiChartExportRequest<TData>): Promise<void> {
  const table = request.chart.excelTable(request.data);
  const { blob, filename } = await biDashboardApi.exportExcel({
    productVersionId: request.productVersionId,
    pageKey: request.pageKey,
    chartTemplateId: request.chart.templateId,
    sourceVersion: request.sourceVersion,
    title: request.title,
    productVersionName: request.productVersionName ?? '',
    explanation: request.description ?? '',
    headers: table.headers,
    rows: table.rows,
  });
  const fallback = `${sanitizeFilename(request.title)}_${formatExportFileDate(new Date())}.xlsx`;
  downloadBlob(blob, filename || fallback);
}

function sanitizeFilename(value: string): string {
  return value.replace(/[\\/:*?"<>|]/g, '-').trim() || 'BI图表';
}
