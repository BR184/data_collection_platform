import type { RecordTableLinkValue, RecordTableTagValue } from '../../types/record-table';

export interface RecordTableCellDisplay {
  tags: RecordTableTagValue[];
  primaryTag: RecordTableTagValue | null;
  link: RecordTableLinkValue | null;
  text: string;
}

export function normalizeRecordTableTagList(value: unknown): RecordTableTagValue[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value
    .map((item) => {
      if (!item) {
        return null;
      }
      if (typeof item === 'string') {
        return { label: item } satisfies RecordTableTagValue;
      }
      if (typeof item === 'object' && 'label' in item) {
        const record = item as Record<string, unknown>;
        return {
          label: String(record.label ?? ''),
          type: typeof record.type === 'string' ? (record.type as RecordTableTagValue['type']) : undefined,
        } satisfies RecordTableTagValue;
      }
      return null;
    })
    .filter((item): item is RecordTableTagValue => Boolean(item?.label));
}

export function normalizeRecordTableLink(value: unknown): RecordTableLinkValue | null {
  if (!value) {
    return null;
  }
  if (typeof value === 'object' && 'href' in value) {
    const record = value as Record<string, unknown>;
    const href = String(record.href ?? '').trim();
    if (!href) {
      return null;
    }
    return {
      href,
      label: String(record.label ?? href),
    };
  }
  const href = String(value).trim();
  if (!href) {
    return null;
  }
  return {
    href,
    label: href,
  };
}

export function formatRecordTableCellValue(value: unknown) {
  if (value == null || value === '') {
    return '-';
  }
  if (typeof value === 'object') {
    return JSON.stringify(value);
  }
  return String(value);
}

export function resolveRecordTableCellDisplay(value: unknown): RecordTableCellDisplay {
  const tags = normalizeRecordTableTagList(value);
  return {
    tags,
    primaryTag: tags[0] ?? null,
    link: normalizeRecordTableLink(value),
    text: formatRecordTableCellValue(value),
  };
}
