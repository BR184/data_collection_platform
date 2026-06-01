export interface DatabaseTableOption {
  tableName: string;
  label: string;
  syncStatus: string;
  lastSyncTime?: string | null;
  tableKind?: 'LOCAL' | 'MIRROR' | 'SOURCE' | string;
  refreshable?: boolean;
}

export interface DatabaseTableColumn {
  key: string;
  label: string;
  sortable: boolean;
}

export interface DatabaseTableRowsResponse {
  tableName: string;
  label: string;
  columns: DatabaseTableColumn[];
  rows: Record<string, unknown>[];
  total: number;
  page: number;
  size: number;
  sortField?: string | null;
  sortOrder?: string | null;
  keyword?: string | null;
  syncStatus: string;
  lastSyncTime?: string | null;
  statusMessage?: string | null;
  tableKind?: 'LOCAL' | 'MIRROR' | 'SOURCE' | string;
  refreshable?: boolean;
}
