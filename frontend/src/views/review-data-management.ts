import type {
  ReviewDataProblemItemResponse,
  ReviewDataRecordRowResponse,
  ReviewDataSummaryResponse,
} from '../types/api';
import type { RecordTableActiveFilterTag, RecordTableColumn, RecordTableTagValue } from '../types/record-table';

export interface ReviewDataSummaryCard {
  key: string;
  label: string;
  value: string;
}

export interface ReviewRecordFormModel {
  projectName: string;
  title: string;
  moduleName: string;
  reviewType: string;
  reviewDate: string;
  reviewOwner: string;
  reviewExperts: string[];
  reviewScalePages: number;
  reviewProduct: string;
  authorName: string;
  reviewVersion: string;
}

export interface ReviewProblemItemFormModel {
  reviewerName: string;
  workloadHours: number;
  reviewCategory: string;
  documentPosition: string;
  problemCategory: string;
  problemDescription: string;
  suggestedSolution: string;
  ownerName: string;
  rejectionReason: string;
  problemStatus: string;
}

export function reviewDataColumns(): RecordTableColumn[] {
  return [
    { key: 'title', label: '标题', sortable: true, minWidth: 280, fixed: 'left' },
    { key: 'projectName', label: '项目', sortable: true, minWidth: 140 },
    { key: 'problemCount', label: '问题合计(个)', type: 'number', sortable: true, width: 110, align: 'right' },
    { key: 'reviewScalePages', label: '页数', type: 'number', sortable: true, width: 90, align: 'right' },
    { key: 'problemDensity', label: '评审缺陷密度(个/页)', sortable: true, width: 150, align: 'right' },
    { key: 'reviewType', label: '评审类型', sortable: true, minWidth: 140 },
    { key: 'moduleName', label: '模块', sortable: true, minWidth: 120 },
    { key: 'reviewOwner', label: '负责人', sortable: true, width: 110 },
    { key: 'reviewExpertsSummary', label: '评审专家', minWidth: 180 },
    { key: 'reviewDate', label: '评审日期', sortable: true, width: 120 },
    { key: 'updatedAt', label: '更新时间', sortable: true, minWidth: 170 },
  ];
}

export function reviewProblemItemColumns(): RecordTableColumn[] {
  return [
    { key: 'reviewerName', label: '评审专家', minWidth: 110 },
    { key: 'workloadHours', label: '评审工作量', width: 110, align: 'right' },
    { key: 'reviewCategory', label: '评审类别', minWidth: 110 },
    { key: 'documentPosition', label: '在文档中的位置', minWidth: 150 },
    { key: 'problemCategory', label: '问题类别', minWidth: 110 },
    { key: 'problemDescription', label: '问题描述', minWidth: 220 },
    { key: 'suggestedSolution', label: '建议解决方案', minWidth: 220 },
    { key: 'ownerName', label: '责任人', minWidth: 110 },
    { key: 'rejectionReason', label: '不接受理由', minWidth: 140 },
    { key: 'problemStatus', label: '问题状态', type: 'tag', width: 110, align: 'center' },
    { key: 'updatedAt', label: '更新日期', minWidth: 160 },
  ];
}

export function buildReviewDataTableRows(rows: ReviewDataRecordRowResponse[]) {
  return rows.map((row) => ({
    __raw: row,
    id: row.id,
    title: row.title || '-',
    projectName: row.projectName || '-',
    problemCount: row.problemCount ?? 0,
    reviewScalePages: row.reviewScalePages ?? 0,
    problemDensity: formatNullableNumber(row.problemDensity, 2),
    reviewType: row.reviewType || '-',
    moduleName: row.moduleName || '-',
    reviewOwner: row.reviewOwner || '-',
    reviewExpertsSummary: row.reviewExpertsSummary || '-',
    reviewDate: formatDate(row.reviewDate),
    updatedAt: formatDateTime(row.updatedAt),
  }));
}

export function buildReviewDataExportCsv(rows: ReviewDataRecordRowResponse[]) {
  const columns: Array<{ label: string; value: (row: ReviewDataRecordRowResponse) => unknown }> = [
    { label: '标题', value: (row) => row.title },
    { label: '项目', value: (row) => row.projectName },
    { label: '模块', value: (row) => row.moduleName },
    { label: '评审类型', value: (row) => row.reviewType },
    { label: '评审日期', value: (row) => formatDate(row.reviewDate) },
    { label: '负责人', value: (row) => row.reviewOwner },
    { label: '评审专家', value: (row) => row.reviewExpertsSummary },
    { label: '页数', value: (row) => row.reviewScalePages },
    { label: '评审工作产品', value: (row) => row.reviewProduct },
    { label: '作者', value: (row) => row.authorName },
    { label: '评审版本', value: (row) => row.reviewVersion },
    { label: '问题合计(个)', value: (row) => row.problemCount },
    { label: '缺陷密度(个/页)', value: (row) => formatNullableNumber(row.problemDensity, 2) },
    { label: '更新时间', value: (row) => formatDateTime(row.updatedAt) },
    { label: '状态', value: (row) => (row.deleted ? '已删除' : '有效') },
  ];

  return [
    columns.map((column) => escapeCsvCell(column.label)).join(','),
    ...rows.map((row) => columns.map((column) => escapeCsvCell(column.value(row))).join(',')),
  ].join('\r\n');
}

export function buildProblemItemTableRows(rows: ReviewDataProblemItemResponse[]) {
  return rows.map((row) => ({
    __raw: row,
    id: row.id,
    reviewerName: row.reviewerName || '-',
    workloadHours: formatNullableNumber(row.workloadHours, 1),
    reviewCategory: row.reviewCategory || '-',
    documentPosition: row.documentPosition || '-',
    problemCategory: row.problemCategory || '-',
    problemDescription: row.problemDescription || '-',
    suggestedSolution: row.suggestedSolution || '-',
    ownerName: row.ownerName || '-',
    rejectionReason: row.rejectionReason || '-',
    problemStatus: [problemStatusTag(row.problemStatus)],
    updatedAt: formatDateTime(row.updatedAt),
  }));
}

export function buildReviewDataSummaryCards(summary: ReviewDataSummaryResponse | null): ReviewDataSummaryCard[] {
  if (!summary) {
    return [
      { key: 'total', label: '评审记录', value: '0' },
      { key: 'problems', label: '评审问题', value: '0' },
      { key: 'pages', label: '平均页数', value: '0.0' },
      { key: 'density', label: '平均问题数', value: '0.0' },
    ];
  }
  return [
    { key: 'total', label: '评审记录', value: String(summary.totalRecords) },
    { key: 'problems', label: '评审问题', value: String(summary.totalProblemItems) },
    { key: 'pages', label: '平均页数', value: formatFixed(summary.averageReviewScalePages, 1) },
    { key: 'density', label: '平均问题数', value: formatFixed(summary.averageProblemCount, 1) },
  ];
}

export function buildReviewDataFilterTags(values: Record<string, unknown>): RecordTableActiveFilterTag[] {
  const tags: RecordTableActiveFilterTag[] = [];
  pushTag(tags, 'title', '标题', values.title);
  pushTag(tags, 'projectName', '项目', values.projectName);
  pushTag(tags, 'moduleName', '模块', values.moduleName);
  pushTag(tags, 'reviewOwner', '负责人', values.reviewOwner);
  pushTag(tags, 'reviewType', '评审类型', values.reviewType);
  pushTag(tags, 'problemStatus', '问题状态', values.problemStatus);
  pushTag(tags, 'reviewExpert', '评审专家', values.reviewExpert);
  return tags;
}

export function createEmptyReviewRecordForm(): ReviewRecordFormModel {
  return {
    projectName: '',
    title: '',
    moduleName: '',
    reviewType: '',
    reviewDate: '',
    reviewOwner: '',
    reviewExperts: [],
    reviewScalePages: 0,
    reviewProduct: '',
    authorName: '',
    reviewVersion: '',
  };
}

export function createReviewRecordFormFromRow(
  row: ReviewDataRecordRowResponse,
  experts: string[],
): ReviewRecordFormModel {
  return {
    projectName: row.projectName || '',
    title: row.title || '',
    moduleName: row.moduleName || '',
    reviewType: row.reviewType || '',
    reviewDate: row.reviewDate || '',
    reviewOwner: row.reviewOwner || '',
    reviewExperts: [...experts],
    reviewScalePages: row.reviewScalePages ?? 0,
    reviewProduct: row.reviewProduct || '',
    authorName: row.authorName || '',
    reviewVersion: row.reviewVersion || '',
  };
}

export function createEmptyProblemItemForm(): ReviewProblemItemFormModel {
  return {
    reviewerName: '',
    workloadHours: 0,
    reviewCategory: '',
    documentPosition: '',
    problemCategory: '',
    problemDescription: '',
    suggestedSolution: '',
    ownerName: '',
    rejectionReason: '',
    problemStatus: '',
  };
}

export function createProblemItemFormFromRow(row: ReviewDataProblemItemResponse): ReviewProblemItemFormModel {
  return {
    reviewerName: row.reviewerName || '',
    workloadHours: row.workloadHours ?? 0,
    reviewCategory: row.reviewCategory || '',
    documentPosition: row.documentPosition || '',
    problemCategory: row.problemCategory || '',
    problemDescription: row.problemDescription || '',
    suggestedSolution: row.suggestedSolution || '',
    ownerName: row.ownerName || '',
    rejectionReason: row.rejectionReason || '',
    problemStatus: row.problemStatus || '',
  };
}

function pushTag(tags: RecordTableActiveFilterTag[], key: string, label: string, value: unknown) {
  const text = String(value ?? '').trim();
  if (text) {
    tags.push({ key, label, value: text });
  }
}

function problemStatusTag(status: string): RecordTableTagValue {
  switch (status) {
    case '已修复':
      return { label: status, type: 'success' };
    case '已关闭':
      return { label: status, type: 'info' };
    case '已拒绝':
      return { label: status, type: 'danger' };
    case '无问题':
      return { label: status, type: 'primary' };
    case '未评审':
      return { label: status, type: 'warning' };
    default:
      return { label: status || '新提交', type: 'warning' };
  }
}

function formatNullableNumber(value: number | null | undefined, fractionDigits = 0) {
  if (value == null) {
    return '-';
  }
  return formatFixed(value, fractionDigits);
}

function formatFixed(value: number, fractionDigits: number) {
  return Number.isFinite(value) ? value.toFixed(fractionDigits) : '0';
}

function formatDateTime(value?: string | null) {
  return value ? value.replace('T', ' ').slice(0, 19) : '-';
}

function formatDate(value?: string | null) {
  return value ? value.slice(0, 10) : '-';
}

function escapeCsvCell(value: unknown) {
  const text = String(value ?? '').trim();
  const safeText = /^[=+\-@]/.test(text) ? `'${text}` : text;
  return `"${safeText.replace(/"/g, '""')}"`;
}
