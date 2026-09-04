import type { RecordTableColumn } from '../../types/record-table';
import { resolveRecordTableCellDisplay } from './base-record-table-cell';
import { tableHeaderMinimumWidth } from './table-header-layout';

type RecordTableRow = Readonly<Record<string, unknown>>;

/**
 * 根据列配置和当前页数据计算实际列宽；结果不依赖组件状态或 DOM。
 */
export function effectiveColumnWidth(
  column: RecordTableColumn,
  rows: ReadonlyArray<RecordTableRow>,
): number {
  if (isPersonNameColumn(column)) {
    return effectivePersonNameColumnWidth(column);
  }
  const lowerBound = effectiveColumnLowerBound(column);
  const contentWidth = estimateColumnContentWidth(column, rows);
  const suggestedWidth = column.width ?? 0;
  const upperBound = Math.max(lowerBound, suggestedWidth, defaultColumnUpperBound(column));
  return Math.ceil(clamp(Math.max(lowerBound, contentWidth), lowerBound, upperBound));
}

/**
 * 计算 Element Plus 表格列的最小宽度，保证表头和列类型的最低可读空间。
 */
export function effectiveColumnMinWidth(column: RecordTableColumn): number {
  if (isPersonNameColumn(column)) {
    return effectivePersonNameColumnWidth(column);
  }
  return effectiveColumnLowerBound(column);
}

/**
 * 返回列在表格剩余空间分配中的弹性权重；固定列和身份列不参与扩展。
 */
export function columnFlexWeight(column: RecordTableColumn): number {
  if (column.fixed || isPersonNameColumn(column) || isProjectLikeColumn(column)) {
    return 0;
  }
  if (isNarrativeColumn(column)) {
    return 3.2;
  }
  if (column.type === 'tags') {
    return 2;
  }
  if (/非法类型|标签|原因|说明/.test(column.label)) {
    return 1.2;
  }
  return 0;
}

/**
 * 估算表格单元格文本的像素宽度，用于列宽预估而非精确排版。
 */
export function estimateTextWidthPx(value: string): number {
  const text = String(value ?? '').trim();
  if (!text || text === '-') {
    return 8;
  }
  return Array.from(text).reduce((total, character) => {
    if (/[\u4e00-\u9fff]/.test(character)) {
      return total + 13;
    }
    if (/[A-Z]/.test(character)) {
      return total + 7;
    }
    if (/[a-z]/.test(character)) {
      return total + 6.4;
    }
    if (/[0-9]/.test(character)) {
      return total + 6.8;
    }
    if (/\s/.test(character)) {
      return total + 4;
    }
    return total + 6.2;
  }, 0);
}

function effectiveColumnLowerBound(column: RecordTableColumn): number {
  if (isPersonNameColumn(column)) {
    return effectivePersonNameColumnWidth(column);
  }
  const reservePx = (column.sortable ? 24 : 8) + (column.headerTooltip ? 16 : 0);
  return Math.max(
    adjustedConfiguredMinWidth(column),
    tableHeaderMinimumWidth(column.label, reservePx, column.headerLines),
    columnTypeFloor(column),
  );
}

function adjustedConfiguredMinWidth(column: RecordTableColumn): number {
  if (!column.minWidth) {
    return 0;
  }
  if (column.type === 'tags' || isNarrativeColumn(column)) {
    return column.minWidth;
  }
  return Math.min(column.minWidth, columnTypeFloor(column) + 48);
}

function columnTypeFloor(column: RecordTableColumn): number {
  if (isProjectLikeColumn(column)) {
    return 108;
  }
  if (column.type === 'number') {
    return 58;
  }
  if (column.type === 'datetime') {
    return 128;
  }
  if (column.type === 'tags') {
    return 132;
  }
  if (column.type === 'tag') {
    return 86;
  }
  if (column.type === 'link') {
    return 78;
  }
  return 68;
}

function defaultColumnUpperBound(column: RecordTableColumn): number {
  if (isPersonNameColumn(column)) {
    return effectivePersonNameColumnWidth(column);
  }
  if (isProjectLikeColumn(column)) {
    return 156;
  }
  if (isNarrativeColumn(column)) {
    return column.fixed ? 360 : 460;
  }
  if (column.type === 'tags') {
    return 420;
  }
  if (column.type === 'datetime') {
    return 176;
  }
  if (column.type === 'number') {
    return 128;
  }
  if (column.type === 'tag') {
    return 140;
  }
  if (column.type === 'link') {
    return 132;
  }
  return 172;
}

function estimateColumnContentWidth(
  column: RecordTableColumn,
  rows: ReadonlyArray<RecordTableRow>,
): number {
  if (isPersonNameColumn(column)) {
    return effectivePersonNameColumnWidth(column);
  }
  const values = rows.slice(0, 80).map((row) => row[column.key]);
  if (!values.length) {
    return columnTypeFloor(column);
  }
  const maxContentPx = values.reduce<number>(
    (max, value) => Math.max(max, cellVisualWidthPx(value, column)),
    0,
  );
  const basePadding = column.type === 'tags'
    ? 48
    : column.type === 'tag'
      ? 36
      : column.type === 'number'
        ? 34
        : column.type === 'link'
          ? 38
          : isProjectLikeColumn(column)
            ? 34
            : isCompactTextColumn(column)
              ? 18
              : 26;
  return maxContentPx + basePadding;
}

function cellVisualWidthPx(value: unknown, column: RecordTableColumn): number {
  const display = resolveRecordTableCellDisplay(value);
  if (column.type === 'tags') {
    if (!display.tags.length) {
      return estimateTextWidthPx('-');
    }
    const visibleLabels = display.tags.slice(0, 3).map((tag) => tag.label);
    const longestLabelWidth = visibleLabels.reduce(
      (max, label) => Math.max(max, estimateTextWidthPx(label)),
      0,
    );
    const combinedWidth = visibleLabels.reduce(
      (total, label) => total + estimateTextWidthPx(label),
      0,
    ) + Math.max(0, visibleLabels.length - 1) * 34;
    return Math.max(longestLabelWidth + 24, Math.min(combinedWidth, 220));
  }
  if (column.type === 'tag') {
    return Math.min(estimateTextWidthPx(display.primaryTag?.label ?? '-') + 20, 260);
  }
  if (column.type === 'link') {
    return estimateTextWidthPx(display.link?.label ?? '-');
  }
  return estimateTextWidthPx(display.text);
}

function isNarrativeColumn(column: RecordTableColumn): boolean {
  return /(title|content|description|solution|reason|remark|note|message|summary|detail)/i.test(column.key)
    || /标题|内容|描述|说明|方案|原因|备注|详情|消息/.test(column.label);
}

function isCompactTextColumn(column: RecordTableColumn): boolean {
  if (isNarrativeColumn(column) || isProjectLikeColumn(column) || column.type === 'tags') {
    return false;
  }
  return /模块|负责人|责任人|处理人|创建人|提交人|合并人|作者|状态|类型|类别|分支/.test(column.label)
    || /(module|owner|assignee|author|user|status|state|type|branch|name)$/i.test(column.key);
}

function isProjectLikeColumn(column: RecordTableColumn): boolean {
  if (column.type === 'number' || /id$/i.test(column.key)) {
    return false;
  }
  return /所属项目|项目名称|代码库/.test(column.label)
    || /^(projectName|repositoryName|repositoryPath|projectPath|repository)$/.test(column.key);
}

function isPersonNameColumn(column: RecordTableColumn): boolean {
  if (column.type === 'tags' || column.type === 'tag' || column.type === 'datetime'
    || column.type === 'number' || column.type === 'link') {
    return false;
  }
  return /被走查人|合并人|负责人|责任人|处理人|创建人|提交人|评审专家|审查人|审核人|作者/.test(column.label)
    || /(owner|assignee|author|reviewer|reviewOwner|mergedBy|createdBy|updatedBy|userName|user)$/i.test(column.key);
}

function personNameColumnWidth(column: RecordTableColumn): number {
  const sortReserve = column.sortable ? 24 : 8;
  const tooltipReserve = column.headerTooltip ? 14 : 0;
  return 60 + sortReserve + tooltipReserve;
}

function effectivePersonNameColumnWidth(column: RecordTableColumn): number {
  const sortReserve = column.sortable ? 24 : 8;
  const tooltipReserve = column.headerTooltip ? 14 : 0;
  return Math.max(
    personNameColumnWidth(column),
    tableHeaderMinimumWidth(column.label, sortReserve + tooltipReserve, column.headerLines),
  );
}

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value));
}
