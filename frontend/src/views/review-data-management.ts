import type {
  ReviewDataContentResponse,
  ReviewDataContentSaveRequest,
  ReviewDataDescriptionResponse,
  ReviewDataDescriptionSaveRequest,
  ReviewDataFilterOptionsResponse,
  ReviewDataProblemItemResponse,
  ReviewDataRecordRowResponse,
  ReviewDataSummaryResponse,
  StatisticFilterField,
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
  notReachStandardReason: string;
  sourceFileName: string;
  weightedDefectDensity: number | null;
  descriptions: ReviewRecordDescriptionFormModel[];
  contents: ReviewRecordContentFormModel[];
}

export type ReviewRecordDescriptionFormModel = ReviewDataDescriptionSaveRequest;
export type ReviewRecordContentFormModel = ReviewDataContentSaveRequest;

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
    { key: 'title', label: '标题', sortable: true, width: 320, fixed: 'left', align: 'left', headerAlign: 'center' },
    { key: 'projectName', label: '项目', sortable: true, width: 120 },
    { key: 'problemCount', label: '问题合计(个)', type: 'number', sortable: true, width: 82, align: 'right' },
    { key: 'reviewScalePages', label: '页数', type: 'number', sortable: true, width: 64, align: 'right' },
    { key: 'problemDensity', label: '评审缺陷密度(个/页)', sortable: true, width: 118, align: 'right' },
    { key: 'reviewEfficiency', label: '评审效率(个/小时)', sortable: true, width: 112, align: 'right' },
    { key: 'reviewRate', label: '评审速率(页/小时)', sortable: true, width: 112, align: 'right' },
    { key: 'moduleName', label: '模块', sortable: true, width: 80 },
    { key: 'reviewOwner', label: '负责人', sortable: true, width: 80 },
    { key: 'createdAt', label: '上传时间', sortable: true, width: 160 },
    { key: 'independentReviewWorkload', label: '独立评审工作量合计(小时)', sortable: true, width: 150, align: 'right' },
    { key: 'independentReviewProblemCount', label: '有效的独立评审问题数合计(个)', sortable: true, width: 168, align: 'right' },
    { key: 'meetingReviewWorkload', label: '会议评审工作量(小时）', sortable: true, width: 136, align: 'right' },
    { key: 'meetingReviewProblemCount', label: '有效的会议评审问题数合计(个)', sortable: true, width: 168, align: 'right' },
    { key: 'notReachStandardReason', label: '不达标说明', width: 150 },
    {
      key: 'reachStandard',
      label: '是否达标',
      headerTooltip: '达标判断：评审缺陷密度介于[0.2~0.6]',
      type: 'tag',
      sortable: true,
      width: 78,
      align: 'center',
      fixed: 'right',
    },
  ];
}

export function reviewProblemItemColumns(): RecordTableColumn[] {
  return [
    { key: 'reviewerName', label: '评审专家', minWidth: 96 },
    { key: 'workloadHours', label: '评审工作量', width: 84, align: 'right' },
    { key: 'reviewCategory', label: '评审类别', minWidth: 94 },
    { key: 'documentPosition', label: '在文档中的位置', minWidth: 136 },
    { key: 'problemCategory', label: '问题类别', minWidth: 94 },
    { key: 'problemDescription', label: '问题描述', minWidth: 176 },
    { key: 'suggestedSolution', label: '建议解决方案', minWidth: 176 },
    { key: 'ownerName', label: '责任人', minWidth: 84 },
    { key: 'rejectionReason', label: '不接受理由', minWidth: 112 },
    { key: 'problemStatus', label: '问题状态', type: 'tag', width: 94, align: 'center' },
    { key: 'updatedAt', label: '更新日期', minWidth: 130 },
  ];
}

export function buildReviewDataFilterFields(filterOptions: ReviewDataFilterOptionsResponse): StatisticFilterField[] {
  return [
    {
      key: 'title',
      label: '标题',
      type: 'text',
      operators: ['contains', 'eq', 'ne', 'isEmpty', 'isNotEmpty'],
      options: [],
    },
    {
      key: 'projectName',
      label: '项目',
      type: 'select',
      operators: ['eq', 'ne', 'isEmpty', 'isNotEmpty'],
      options: filterOptions.projectNames,
      labelDimensionKey: 'project',
      labelGroupEnabled: true,
      labelGroupValueType: 'STRING',
    },
    {
      key: 'moduleName',
      label: '模块',
      type: 'select',
      operators: ['eq', 'ne', 'isEmpty', 'isNotEmpty'],
      options: filterOptions.moduleNames,
      labelDimensionKey: 'module',
      labelGroupEnabled: true,
      labelGroupValueType: 'STRING',
    },
    {
      key: 'reviewOwner',
      label: '负责人',
      type: 'select',
      operators: ['eq', 'ne', 'isEmpty', 'isNotEmpty'],
      options: filterOptions.reviewOwners,
      labelDimensionKey: 'review_owner',
      labelGroupEnabled: true,
      labelGroupValueType: 'STRING',
    },
    {
      key: 'reviewType',
      label: '评审类型',
      type: 'select',
      operators: ['eq', 'ne', 'isEmpty', 'isNotEmpty'],
      options: filterOptions.reviewTypes,
    },
    {
      key: 'reviewExpert',
      label: '评审专家',
      type: 'select',
      operators: ['eq', 'ne', 'isEmpty', 'isNotEmpty'],
      options: filterOptions.reviewExperts,
      labelDimensionKey: 'review_expert',
      labelGroupEnabled: true,
      labelGroupValueType: 'STRING',
    },
    {
      key: 'problemStatus',
      label: '问题状态',
      type: 'select',
      operators: ['eq', 'ne', 'isEmpty', 'isNotEmpty'],
      options: filterOptions.problemStatuses,
    },
    {
      key: 'reviewScalePages',
      label: '页数',
      type: 'number',
      operators: ['eq', 'gt', 'gte', 'lt', 'lte', 'between'],
      options: [],
    },
    {
      key: 'problemCount',
      label: '问题合计',
      type: 'number',
      operators: ['eq', 'gt', 'gte', 'lt', 'lte', 'between'],
      options: [],
    },
    {
      key: 'problemDensity',
      label: '缺陷密度',
      type: 'number',
      operators: ['eq', 'gt', 'gte', 'lt', 'lte', 'between'],
      options: [],
    },
    {
      key: 'reviewEfficiency',
      label: '评审效率',
      type: 'number',
      operators: ['eq', 'gt', 'gte', 'lt', 'lte', 'between'],
      options: [],
    },
    {
      key: 'reviewRate',
      label: '评审速率',
      type: 'number',
      operators: ['eq', 'gt', 'gte', 'lt', 'lte', 'between'],
      options: [],
    },
    {
      key: 'independentReviewWorkload',
      label: '独立评审工作量',
      type: 'number',
      operators: ['eq', 'gt', 'gte', 'lt', 'lte', 'between'],
      options: [],
    },
    {
      key: 'independentReviewProblemCount',
      label: '独立评审问题数',
      type: 'number',
      operators: ['eq', 'gt', 'gte', 'lt', 'lte', 'between'],
      options: [],
    },
    {
      key: 'meetingReviewWorkload',
      label: '会议评审工作量',
      type: 'number',
      operators: ['eq', 'gt', 'gte', 'lt', 'lte', 'between'],
      options: [],
    },
    {
      key: 'meetingReviewProblemCount',
      label: '会议评审问题数',
      type: 'number',
      operators: ['eq', 'gt', 'gte', 'lt', 'lte', 'between'],
      options: [],
    },
    {
      key: 'notReachStandardReason',
      label: '不达标说明',
      type: 'text',
      operators: ['contains', 'eq', 'ne', 'isEmpty', 'isNotEmpty'],
      options: [],
    },
    {
      key: 'createdAt',
      label: '上传时间',
      type: 'datetime',
      operators: ['day', 'before', 'after', 'between'],
      options: [],
    },
    {
      key: 'reviewDate',
      label: '评审日期',
      type: 'datetime',
      operators: ['day', 'before', 'after', 'between'],
      options: [],
    },
  ];
}

const reviewDataMetricFilterFieldKeys = new Set([
  'title',
  'reviewScalePages',
  'problemCount',
  'problemDensity',
  'reviewEfficiency',
  'reviewRate',
  'independentReviewWorkload',
  'independentReviewProblemCount',
  'meetingReviewWorkload',
  'meetingReviewProblemCount',
  'notReachStandardReason',
  'createdAt',
  'reviewDate',
]);

export function buildReviewDataMetricFilterFields(filterOptions: ReviewDataFilterOptionsResponse): StatisticFilterField[] {
  return buildReviewDataFilterFields(filterOptions)
    .filter((field) => reviewDataMetricFilterFieldKeys.has(field.key));
}

export function buildReviewDataTableRows(rows: ReviewDataRecordRowResponse[]) {
  return rows.map((row) => ({
    __raw: row,
    id: row.id,
    title: row.title || '-',
    projectName: row.projectName || '-',
    problemCount: row.problemCount ?? 0,
    reviewScalePages: row.reviewScalePages ?? 0,
    problemDensity: formatFlooredNullableNumber(row.problemDensity, 2),
    reviewCategorySummary: row.reviewCategorySummary || '-',
    docSpecificationCount: row.docSpecificationCount ?? 0,
    integrityCount: row.integrityCount ?? 0,
    functionalityCount: row.functionalityCount ?? 0,
    feasibilityCount: row.feasibilityCount ?? 0,
    reviewEfficiency: formatFlooredNullableNumber(row.reviewEfficiency, 2),
    reviewRate: formatFlooredNullableNumber(row.reviewRate, 2),
    reviewType: row.reviewType || '-',
    moduleName: row.moduleName || '-',
    reviewOwner: row.reviewOwner || '-',
    reviewExpertsSummary: row.reviewExpertsSummary || '-',
    createdAt: formatDateTime(row.createdAt),
    independentReviewWorkload: formatNullableNumber(row.independentReviewWorkload, 2),
    independentReviewProblemCount: row.independentReviewProblemCount ?? 0,
    meetingReviewWorkload: formatNullableNumber(row.meetingReviewWorkload, 2),
    meetingReviewProblemCount: row.meetingReviewProblemCount ?? 0,
    notReachStandardReason: row.notReachStandardReason || '-',
    sourceFileName: row.sourceFileName || '-',
    weightedDefectDensity: formatNullableNumber(row.weightedDefectDensity, 2),
    reachStandard: [reachStandardTag(row.reachStandard)],
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
    { label: '缺陷密度(个/页)', value: (row) => formatFlooredNullableNumber(row.problemDensity, 2) },
    { label: '评审效率(个/小时)', value: (row) => formatFlooredNullableNumber(row.reviewEfficiency, 2) },
    { label: '评审速率(页/小时)', value: (row) => formatFlooredNullableNumber(row.reviewRate, 2) },
    { label: '独立评审工作量合计(小时)', value: (row) => formatNullableNumber(row.independentReviewWorkload, 2) },
    { label: '有效独立评审问题数合计(个)', value: (row) => row.independentReviewProblemCount },
    { label: '会议评审工作量合计(小时)', value: (row) => formatNullableNumber(row.meetingReviewWorkload, 2) },
    { label: '有效会议评审问题数合计(个)', value: (row) => row.meetingReviewProblemCount },
    { label: '不达标说明', value: (row) => row.notReachStandardReason },
    { label: '是否达标', value: (row) => (row.reachStandard ? '是' : '否') },
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
    notReachStandardReason: '',
    sourceFileName: '',
    weightedDefectDensity: null,
    descriptions: [createPrimaryDescriptionForm()],
    contents: [],
  };
}

export function createReviewRecordFormFromRow(
  row: ReviewDataRecordRowResponse,
  experts: string[],
  descriptions: ReviewDataDescriptionResponse[] = [],
  contents: ReviewDataContentResponse[] = [],
): ReviewRecordFormModel {
  const matchMode = row.id < 0;
  const expertNames = matchMode ? legacyEditExperts(row, experts) : [...experts];
  const fallbackAuthor = expertNames[0] || row.reviewOwner || '未填写';
  const fallbackReviewDate = dateInputValue(row.reviewDate) || dateInputValue(row.createdAt) || todayInputValue();
  const primaryValues = {
    projectName: legacyEditValue(row.projectName, '未标注项目名', matchMode),
    title: legacyEditValue(row.title, '老平台评审记录', matchMode),
    moduleName: legacyEditValue(row.moduleName, '未标注模块名', matchMode),
    reviewType: legacyEditValue(row.reviewType, '其他', matchMode),
    reviewDate: legacyEditValue(row.reviewDate, fallbackReviewDate, matchMode),
    reviewOwner: legacyEditValue(row.reviewOwner, fallbackAuthor, matchMode),
    reviewProduct: legacyEditValue(row.reviewProduct, row.title || '老平台评审记录', matchMode),
    authorName: legacyEditValue(row.authorName, fallbackAuthor, matchMode),
    reviewVersion: legacyEditValue(row.reviewVersion, 'V1', matchMode),
  };
  const descriptionForms = descriptions.length > 0
    ? descriptions.map((description, index) => ({
      reviewProduct: legacyEditValue(description.reviewProduct, primaryValues.reviewProduct, matchMode),
      reviewVersion: legacyEditValue(description.reviewVersion, primaryValues.reviewVersion, matchMode),
      authorName: legacyEditValue(description.authorName, primaryValues.authorName, matchMode),
      reviewScalePages: description.reviewScalePages ?? 0,
      unit: description.unit || '页',
      sortOrder: description.sortOrder ?? index,
    }))
    : [createPrimaryDescriptionFormFromValues(primaryValues, row.reviewScalePages ?? 0)];
  return {
    projectName: primaryValues.projectName,
    title: primaryValues.title,
    moduleName: primaryValues.moduleName,
    reviewType: primaryValues.reviewType,
    reviewDate: primaryValues.reviewDate,
    reviewOwner: primaryValues.reviewOwner,
    reviewExperts: expertNames,
    reviewScalePages: row.reviewScalePages ?? 0,
    reviewProduct: primaryValues.reviewProduct,
    authorName: primaryValues.authorName,
    reviewVersion: primaryValues.reviewVersion,
    notReachStandardReason: row.notReachStandardReason || '',
    sourceFileName: row.sourceFileName || '',
    weightedDefectDensity: row.weightedDefectDensity ?? null,
    descriptions: descriptionForms,
    contents: contents.map((content, index) => ({
      reviewerName: content.reviewerName || '',
      assignmentContent: content.assignmentContent || '',
      independentWorkloadHours: content.independentWorkloadHours ?? 0,
      independentProblemCount: content.independentProblemCount ?? 0,
      meetingWorkloadHours: content.meetingWorkloadHours ?? 0,
      meetingProblemCount: content.meetingProblemCount ?? 0,
      sortOrder: content.sortOrder ?? index,
    })),
  };
}

//兼容模式-MatchMode：老平台 Mongo 评审记录可能缺少新平台编辑表单必填项；兜底值只用于打开编辑弹窗，用户保存后转为正式记录。
function legacyEditValue(value: string | null | undefined, fallback: string, matchMode: boolean) {
  const normalized = value?.trim() ?? '';
  if (normalized) {
    return normalized;
  }
  return matchMode ? fallback : '';
}

function legacyEditExperts(row: ReviewDataRecordRowResponse, experts: string[]) {
  const names = [...experts, ...splitLegacyExperts(row.reviewExpertsSummary)]
    .map((value) => value.trim())
    .filter((value) => value.length > 0);
  const uniqueNames = [...new Set(names)];
  if (uniqueNames.length > 0) {
    return uniqueNames;
  }
  const ownerName = row.reviewOwner?.trim();
  return ownerName ? [ownerName] : ['未填写'];
}

function splitLegacyExperts(value?: string | null) {
  return (value ?? '').split(/[、,，;；]/);
}

function dateInputValue(value?: string | null) {
  const normalized = value?.trim() ?? '';
  return /^\d{4}-\d{2}-\d{2}/.test(normalized) ? normalized.slice(0, 10) : '';
}

function todayInputValue() {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${now.getFullYear()}-${month}-${day}`;
}

function createPrimaryDescriptionFormFromValues(
  values: Pick<ReviewRecordFormModel, 'reviewProduct' | 'reviewVersion' | 'authorName'>,
  reviewScalePages: number,
): ReviewRecordDescriptionFormModel {
  return {
    reviewProduct: values.reviewProduct,
    reviewVersion: values.reviewVersion,
    authorName: values.authorName,
    reviewScalePages,
    unit: '页',
    sortOrder: 0,
  };
}

export function createPrimaryDescriptionForm(row?: ReviewDataRecordRowResponse): ReviewRecordDescriptionFormModel {
  return {
    reviewProduct: row?.reviewProduct || '',
    reviewVersion: row?.reviewVersion || '',
    authorName: row?.authorName || '',
    reviewScalePages: row?.reviewScalePages ?? 0,
    unit: '页',
    sortOrder: 0,
  };
}

export function createEmptyContentForm(sortOrder = 0): ReviewRecordContentFormModel {
  return {
    reviewerName: '',
    assignmentContent: '',
    independentWorkloadHours: 0,
    independentProblemCount: 0,
    meetingWorkloadHours: 0,
    meetingProblemCount: 0,
    sortOrder,
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

function reachStandardTag(reachStandard: boolean | null | undefined): RecordTableTagValue {
  return reachStandard
    ? { label: '是', type: 'success' }
    : { label: '否', type: 'danger' };
}

function formatNullableNumber(value: number | null | undefined, fractionDigits = 0) {
  if (value == null) {
    return '-';
  }
  return formatFixed(value, fractionDigits);
}

function formatFlooredNullableNumber(value: number | null | undefined, fractionDigits = 0) {
  if (value == null) {
    return '-';
  }
  return formatFlooredFixed(value, fractionDigits);
}

function formatFixed(value: number, fractionDigits: number) {
  return Number.isFinite(value) ? value.toFixed(fractionDigits) : '0';
}

function formatFlooredFixed(value: number, fractionDigits: number) {
  if (!Number.isFinite(value)) {
    return '0';
  }
  const factor = 10 ** fractionDigits;
  return (Math.floor(value * factor + Number.EPSILON) / factor).toFixed(fractionDigits);
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
