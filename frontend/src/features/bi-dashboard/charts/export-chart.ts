import { init } from './bi-echarts-runtime';
import type { BiChart } from './BiChart';
import type {
  CategorySeriesData,
  CodingTrendData,
  DelayHeatmapData,
  DeveloperWorkloadRow,
  ModuleRepairRow,
  NamedValue,
  OverlayBarRow,
  QualityTrendData,
  ReviewQualityRow,
  ReviewScatterPoint,
  RoundQualityRow,
  SubmissionTrendData,
  TestAttainmentRow,
} from './chart-data';
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

export interface BiExcelTableData {
  headers: string[];
  rows: Array<Array<string | number>>;
}

/** 经后端授权后，提取结构化数据并导出为标准 Excel 表格文件 (.xlsx) */
export async function exportBiChartExcel<TData>(request: BiChartExportRequest<TData>): Promise<void> {
  await biDashboardApi.authorizeDownload(
    request.productVersionId,
    request.pageKey,
    request.chart.templateId,
    request.sourceVersion,
  );

  const table = buildChartExcelData(request.chart.templateId, request.data);
  const xmlContent = generateExcelXmlSpreadsheet({
    title: request.title,
    versionName: request.productVersionName || `Version-${request.productVersionId}`,
    exportDate: new Date(),
    headers: table.headers,
    rows: table.rows,
  });

  const blob = new Blob([xmlContent], { type: 'application/vnd.ms-excel;charset=utf-8;' });
  const filename = `${sanitizeFilename(request.title)}_${formatExportFileDate(new Date())}.xlsx`;
  downloadBlob(blob, filename);
}

export function buildChartExcelData(templateId: string, data: unknown): BiExcelTableData {
  if (!data) return { headers: ['无数据'], rows: [] };

  switch (templateId) {
    case 'distribution-donut': {
      const list = data as NamedValue[];
      const total = list.reduce((sum, item) => sum + (item.value ?? 0), 0);
      return {
        headers: ['分类名称', '数量', '占比 (%)'],
        rows: list.map((item) => [
          item.name,
          item.value,
          total > 0 ? Number(((item.value / total) * 100).toFixed(2)) : 0,
        ]),
      };
    }
    case 'review-quality-dual-panel': {
      const list = data as ReviewQualityRow[];
      return {
        headers: ['模块名称', '缺陷密度 (个/页)', '评审速率 (页/小时)', '达标状态'],
        rows: list.map((item) => [
          item.name,
          item.density ?? '--',
          item.rate ?? '--',
          item.achieved == null ? '未判定' : item.achieved ? '已达标' : '未达标',
        ]),
      };
    }
    case 'review-quality-scatter': {
      const list = data as ReviewScatterPoint[];
      return {
        headers: ['模块名称', '评审日期', '评审速率 (页/小时)', '缺陷密度 (个/页)', '达标状态'],
        rows: list.map((item) => [
          item.name,
          item.date ?? '--',
          item.rate ?? '--',
          item.density ?? '--',
          item.achieved == null ? '未判定' : item.achieved ? '已达标' : '未达标',
        ]),
      };
    }
    case 'vertical-category-bar': {
      const list = data as NamedValue[];
      return {
        headers: ['名称/人员/模块', '代码量 (行)'],
        rows: list.map((item) => [item.name, item.value]),
      };
    }
    case 'stacked-category-bar':
    case 'defect-cause-breakdown': {
      const catData = data as CategorySeriesData;
      return {
        headers: ['分类/模块', ...catData.series.map((s) => s.name)],
        rows: catData.categories.map((cat, idx) => [
          cat,
          ...catData.series.map((s) => s.values[idx] ?? 0),
        ]),
      };
    }
    case 'coding-trend-combo': {
      const trend = data as CodingTrendData;
      return {
        headers: ['统计周期', '周期新增代码量 (行)', '累计代码量 (行)'],
        rows: trend.periods.map((period, idx) => [
          period,
          trend.addedLines[idx] ?? 0,
          trend.cumulativeLines[idx] ?? 0,
        ]),
      };
    }
    case 'submission-trend-combo': {
      const trend = data as SubmissionTrendData;
      return {
        headers: ['统计周期', '提交次数 (Commit)', '合并请求数 (MR)'],
        rows: trend.periods.map((period, idx) => [
          period,
          trend.commits[idx] ?? 0,
          trend.mergeRequests[idx] ?? 0,
        ]),
      };
    }
    case 'submission-frequency-bar': {
      const list = data as NamedValue[];
      return {
        headers: ['周期/日期', '提交频次'],
        rows: list.map((item) => [item.name, item.value]),
      };
    }
    case 'quality-trend-small-multiples': {
      const trend = data as QualityTrendData;
      return {
        headers: ['统计周期', '代码注释率 (%)', '走查缺陷密度 (个/KLOC)'],
        rows: trend.periods.map((period, idx) => [
          period,
          trend.commentRates[idx] != null ? Number((trend.commentRates[idx]! * 100).toFixed(2)) : '--',
          trend.defectDensities[idx] ?? '--',
        ]),
      };
    }
    case 'test-quality-attainment': {
      const list = data as TestAttainmentRow[];
      return {
        headers: ['模块/功能名称', '通过率 (%)', '目标通过率 (%)', '达标状态', '达标/通过数', '统计/执行总数'],
        rows: list.map((item) => [
          item.name,
          item.passRate != null ? Number((item.passRate * 100).toFixed(1)) : '--',
          item.targetRate != null ? Number((item.targetRate * 100).toFixed(1)) : '--',
          item.achieved == null ? '未判定' : item.achieved ? '已达标' : '未达标',
          item.counts?.attained ?? '--',
          item.counts?.total ?? '--',
        ]),
      };
    }
    case 'quality-round-track': {
      const list = data as RoundQualityRow[];
      return {
        headers: ['测试轮次', '提交缺陷总数', '已关闭数', '未关闭数', '关闭率 (%)', '一级缺陷', '二级缺陷', '三级缺陷'],
        rows: list.map((item) => [
          item.name,
          item.submitted,
          item.closed,
          item.open,
          item.closeRate != null ? Number((item.closeRate * 100).toFixed(1)) : '--',
          item.levelOne,
          item.levelTwo,
          item.levelThree,
        ]),
      };
    }
    case 'module-repair-matrix': {
      const list = data as ModuleRepairRow[];
      return {
        headers: ['模块名称', '遗留缺陷数 (个)', '累计缺陷数 (个)', '整体修复率 (%)', '一级缺陷修复率 (%)', 'P1修复率 (%)', 'P2修复率 (%)'],
        rows: list.map((item) => [
          item.name,
          item.openCount ?? 0,
          item.totalCount ?? 0,
          item.fixRate != null ? Number((item.fixRate * 100).toFixed(1)) : '--',
          item.levelOneRate != null ? Number((item.levelOneRate * 100).toFixed(1)) : '--',
          item.p1Rate != null ? Number((item.p1Rate * 100).toFixed(1)) : '--',
          item.p2Rate != null ? Number((item.p2Rate * 100).toFixed(1)) : '--',
        ]),
      };
    }
    case 'overlay-category-bar': {
      const list = data as OverlayBarRow[];
      return {
        headers: ['模块名称', '累计发现缺陷数', '当前未修复缺陷数', '已修复缺陷数', '修复率 (%)'],
        rows: list.map((item) => [
          item.name,
          item.total,
          item.overlay,
          item.total - item.overlay,
          item.total > 0 ? Number((((item.total - item.overlay) / item.total) * 100).toFixed(1)) : 100,
        ]),
      };
    }
    case 'developer-workload': {
      const list = data as DeveloperWorkloadRow[];
      return {
        headers: ['指派责任人', '缺陷总数', '已修复缺陷数', '待修复缺陷数', '修复率 (%)'],
        rows: list.map((item) => [
          item.name,
          item.total,
          item.fixed,
          item.open,
          item.total > 0 ? Number(((item.fixed / item.total) * 100).toFixed(1)) : 100,
        ]),
      };
    }
    case 'delay-heatmap': {
      const heatmap = data as DelayHeatmapData;
      const rows: Array<Array<string | number>> = [];
      for (const [rIdx, sIdx, val] of heatmap.values) {
        rows.push([heatmap.reasons[rIdx] ?? '', heatmap.severities[sIdx] ?? '', val]);
      }
      return {
        headers: ['原因分类', '缺陷级别', '延期缺陷数'],
        rows,
      };
    }
    default:
      return { headers: ['数据内容'], rows: [] };
  }
}

interface GenerateExcelOptions {
  title: string;
  versionName: string;
  exportDate: Date;
  headers: string[];
  rows: Array<Array<string | number>>;
}

/** 生成符合 Microsoft Excel XML 格式的 Spreadsheet 文档，确保原生 Excel / WPS 双击完美打开 */
function generateExcelXmlSpreadsheet(options: GenerateExcelOptions): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  const dateStr = `${options.exportDate.getFullYear()}-${pad(options.exportDate.getMonth() + 1)}-${pad(options.exportDate.getDate())} ${pad(options.exportDate.getHours())}:${pad(options.exportDate.getMinutes())}:${pad(options.exportDate.getSeconds())}`;

  const escapeXml = (str: string | number) => String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;');

  const headerCells = options.headers.map((h) => `<Cell ss:StyleID="Header"><Data ss:Type="String">${escapeXml(h)}</Data></Cell>`).join('');

  const dataRows = options.rows.map((row) => {
    const cells = row.map((val) => {
      const isNum = typeof val === 'number' && Number.isFinite(val);
      const type = isNum ? 'Number' : 'String';
      const styleId = isNum ? 'NumberCell' : 'StringCell';
      return `<Cell ss:StyleID="${styleId}"><Data ss:Type="${type}">${escapeXml(val)}</Data></Cell>`;
    }).join('');
    return `<Row>${cells}</Row>`;
  }).join('\n');

  return `<?xml version="1.0" encoding="UTF-8"?>
<?mso-application progid="Excel.Sheet"?>
<Workbook xmlns="urn:schemas-microsoft-com:office:spreadsheet"
 xmlns:o="urn:schemas-microsoft-com:office:office"
 xmlns:x="urn:schemas-microsoft-com:office:excel"
 xmlns:ss="urn:schemas-microsoft-com:office:spreadsheet"
 xmlns:html="http://www.w3.org/TR/REC-html40">
 <Styles>
  <Style ss:ID="Default" ss:Name="Normal">
   <Alignment ss:Vertical="Center"/>
   <Font ss:FontName="Microsoft YaHei" ss:Size="10" ss:Color="#1D2939"/>
  </Style>
  <Style ss:ID="Title">
   <Font ss:FontName="Microsoft YaHei" ss:Size="14" ss:Bold="1" ss:Color="#0F172A"/>
  </Style>
  <Style ss:ID="Meta">
   <Font ss:FontName="Microsoft YaHei" ss:Size="9" ss:Color="#64748B"/>
  </Style>
  <Style ss:ID="Header">
   <Font ss:FontName="Microsoft YaHei" ss:Size="10" ss:Bold="1" ss:Color="#FFFFFF"/>
   <Interior ss:Color="#1E40AF" ss:Pattern="Solid"/>
   <Alignment ss:Horizontal="Center" ss:Vertical="Center"/>
   <Borders>
    <Border ss:Position="Bottom" ss:LineStyle="Continuous" ss:Weight="1" ss:Color="#CBD5E1"/>
    <Border ss:Position="Top" ss:LineStyle="Continuous" ss:Weight="1" ss:Color="#CBD5E1"/>
    <Border ss:Position="Left" ss:LineStyle="Continuous" ss:Weight="1" ss:Color="#CBD5E1"/>
    <Border ss:Position="Right" ss:LineStyle="Continuous" ss:Weight="1" ss:Color="#CBD5E1"/>
   </Borders>
  </Style>
  <Style ss:ID="StringCell">
   <Alignment ss:Horizontal="Left" ss:Vertical="Center"/>
   <Borders>
    <Border ss:Position="Bottom" ss:LineStyle="Continuous" ss:Weight="1" ss:Color="#E2E8F0"/>
    <Border ss:Position="Top" ss:LineStyle="Continuous" ss:Weight="1" ss:Color="#E2E8F0"/>
    <Border ss:Position="Left" ss:LineStyle="Continuous" ss:Weight="1" ss:Color="#E2E8F0"/>
    <Border ss:Position="Right" ss:LineStyle="Continuous" ss:Weight="1" ss:Color="#E2E8F0"/>
   </Borders>
  </Style>
  <Style ss:ID="NumberCell">
   <Alignment ss:Horizontal="Right" ss:Vertical="Center"/>
   <Borders>
    <Border ss:Position="Bottom" ss:LineStyle="Continuous" ss:Weight="1" ss:Color="#E2E8F0"/>
    <Border ss:Position="Top" ss:LineStyle="Continuous" ss:Weight="1" ss:Color="#E2E8F0"/>
    <Border ss:Position="Left" ss:LineStyle="Continuous" ss:Weight="1" ss:Color="#E2E8F0"/>
    <Border ss:Position="Right" ss:LineStyle="Continuous" ss:Weight="1" ss:Color="#E2E8F0"/>
   </Borders>
  </Style>
 </Styles>
 <Worksheet ss:Name="${escapeXml(options.title.slice(0, 30))}">
  <Table ss:DefaultColumnWidth="120" ss:DefaultRowHeight="22">
   <Row ss:Height="28">
    <Cell ss:StyleID="Title"><Data ss:Type="String">${escapeXml(options.title)}</Data></Cell>
   </Row>
   <Row ss:Height="18">
    <Cell ss:StyleID="Meta"><Data ss:Type="String">产品版本：${escapeXml(options.versionName)}  |  导出时间：${escapeXml(dateStr)}</Data></Cell>
   </Row>
   <Row ss:Height="8"/>
   <Row ss:Height="24">
    ${headerCells}
   </Row>
   ${dataRows}
  </Table>
 </Worksheet>
</Workbook>`;
}

function sanitizeFilename(value: string): string {
  return value.replace(/[\\/:*?"<>|]/g, '-').trim() || 'BI图表';
}
