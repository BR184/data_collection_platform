import type { RecordTableTagValue } from '../types/record-table';

export function buildIssueSeverityTag(value: string): RecordTableTagValue {
  const normalized = value.trim().toUpperCase().replace(/\s+/g, '');
  if (normalized === 'LEVEL1' || value.includes('一级缺陷')) {
    return { label: value, type: 'danger' };
  }
  if (normalized === 'LEVEL2' || value.includes('二级缺陷')) {
    return { label: value, type: 'warning' };
  }
  if (normalized === 'LEVEL3' || value.includes('三级缺陷')) {
    return { label: value, type: 'primary' };
  }
  if (normalized === 'SUGGESTION' || value.includes('建议')) {
    return { label: value, type: 'info' };
  }
  return { label: value || '-', type: 'info' };
}

export function displayIssueSeverity(value: string): string {
  const normalized = value.trim().toUpperCase().replace(/\s+/g, '');
  if (normalized === 'LEVEL1') {
    return '一级缺陷';
  }
  if (normalized === 'LEVEL2') {
    return '二级缺陷';
  }
  if (normalized === 'LEVEL3') {
    return '三级缺陷';
  }
  if (normalized === 'SUGGESTION') {
    return '建议类';
  }
  return value;
}
