import type { RecordTableColumn, RecordTableLinkValue, RecordTableTagValue } from '../../types/record-table';

export interface RecordTableCellDisplay {
  tags: RecordTableTagValue[];
  primaryTag: RecordTableTagValue | null;
  link: RecordTableLinkValue | null;
  text: string;
}

export function normalizeRecordTableTagList(value: unknown): RecordTableTagValue[] {
  const rawItems = Array.isArray(value) ? value : splitTagText(value);
  return rawItems
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

function splitTagText(value: unknown) {
  if (typeof value !== 'string') {
    return [];
  }
  return value
    .split(/[、,，;；]/)
    .map((item) => item.trim())
    .filter(Boolean);
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

/**
 * 统一判定记录表列是否在内容溢出时显示完整内容提示；显式列配置优先于类型默认值。
 */
export function shouldShowRecordTableOverflowTooltip(column: RecordTableColumn) {
  if (typeof column.showOverflowTooltip === 'boolean') {
    return column.showOverflowTooltip;
  }
  return column.type !== 'tags' && column.type !== 'tag';
}
