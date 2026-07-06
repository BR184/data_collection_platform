export type RecordTableColumnType = 'text' | 'number' | 'datetime' | 'tag' | 'tags' | 'link';

export interface RecordTableTagValue {
  label: string;
  type?: 'success' | 'warning' | 'info' | 'danger' | 'primary';
}

export interface RecordTableLinkValue {
  label: string;
  href: string;
}

export interface RecordTableFilterOption {
  label: string;
  value: string;
  variant?: 'normal' | 'label-group';
}

export type RecordTableFilterType = 'input' | 'select' | 'daterange';
export type RecordTableFilterSelectMode = 'default' | 'compact';

export interface RecordTableFilterField {
  key: string;
  label: string;
  type: RecordTableFilterType;
  defaultStrategy?: 'empty' | 'first-available';
  placeholder?: string;
  width?: number;
  advanced?: boolean;
  clearable?: boolean;
  options?: RecordTableFilterOption[];
  selectMode?: RecordTableFilterSelectMode;
  multiple?: boolean;
  labelDimensionKey?: string;
  labelGroupEnabled?: boolean;
  startPlaceholder?: string;
  endPlaceholder?: string;
}

export interface RecordTableActiveFilterTag {
  key: string;
  label: string;
  value: string;
}

export interface RecordTableColumn {
  key: string;
  label: string;
  headerLines?: readonly string[];
  headerTooltip?: string;
  type?: RecordTableColumnType;
  sortable?: boolean;
  width?: number;
  minWidth?: number;
  fixed?: 'left' | 'right';
  align?: 'left' | 'center' | 'right';
  headerAlign?: 'left' | 'center' | 'right';
  showOverflowTooltip?: boolean;
}
