import type {
  AnalyticsDashboardDetailColumn,
  AnalyticsDashboardDetailResponse,
} from '../../types/api';

export interface AnalyticsDashboardSafeLink {
  label: string;
  href: string;
}

function textValue(value: unknown): string {
  if (value === null || value === undefined || value === '') {
    return '-';
  }
  if (Array.isArray(value)) {
    return value.map((item) => textValue(item)).join('、');
  }
  if (typeof value === 'object') {
    return JSON.stringify(value);
  }
  return String(value);
}

function numericValue(value: unknown) {
  const numberValue = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(numberValue) ? numberValue : null;
}

function dateValue(value: unknown, includeTime: boolean) {
  const date = value instanceof Date ? value : new Date(String(value));
  if (Number.isNaN(date.getTime())) {
    return textValue(value);
  }
  return includeTime
    ? date.toLocaleString('zh-CN', { hour12: false })
    : date.toLocaleDateString('zh-CN');
}

export function normalizeAnalyticsDetailFormat(column: AnalyticsDashboardDetailColumn) {
  return String(column.format || 'text').trim().toLowerCase();
}

export function formatAnalyticsDetailValue(
  value: unknown,
  column: AnalyticsDashboardDetailColumn,
) {
  const format = normalizeAnalyticsDetailFormat(column);
  if (format === 'date') {
    return dateValue(value, false);
  }
  if (format === 'datetime') {
    return dateValue(value, true);
  }
  if (format === 'number') {
    const numberValue = numericValue(value);
    return numberValue == null ? textValue(value) : new Intl.NumberFormat('zh-CN').format(numberValue);
  }
  if (format === 'percent') {
    const numberValue = numericValue(value);
    return numberValue == null
      ? textValue(value)
      : `${new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 }).format(numberValue * 100)}%`;
  }
  if (format === 'link') {
    return safeAnalyticsDetailLink(value)?.label ?? textValue(value);
  }
  return textValue(value);
}

export function analyticsDetailTags(value: unknown) {
  const values = Array.isArray(value)
    ? value
    : typeof value === 'string'
      ? value.split(/[,，]/)
      : [];
  return values.map((item) => String(item).trim()).filter(Boolean);
}

export function safeAnalyticsDetailLink(value: unknown): AnalyticsDashboardSafeLink | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return null;
  }
  const candidate = value as Record<string, unknown>;
  const href = String(candidate.href ?? '').trim();
  if (!/^https?:\/\//i.test(href)) {
    return null;
  }
  const label = String(candidate.label ?? href).trim() || href;
  return { label, href };
}

export function shouldShowAnalyticsDetailPagination(
  detail: AnalyticsDashboardDetailResponse | null,
) {
  return Boolean(detail && detail.total > detail.size);
}
