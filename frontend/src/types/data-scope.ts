export type DataScopeMode = 'single-select' | 'tree-single' | 'segmented';
export type DataScopeDefaultStrategy = 'empty' | 'first-available';

export interface DataScopeOption {
  label: string;
  value: string;
  disabled?: boolean;
  children?: DataScopeOption[];
}

export interface DataScopeProvider {
  id: string;
  label: string;
  queryKey: string;
  mode: DataScopeMode;
  placeholder?: string;
  emptyLabel?: string;
  defaultStrategy?: DataScopeDefaultStrategy;
  clearable?: boolean;
  compact?: boolean;
  summaryPrefix?: string;
}

export interface DataScopeSelectionSummary {
  label: string;
  value: string;
}
