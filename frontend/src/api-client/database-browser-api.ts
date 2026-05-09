import type { DatabaseTableOption, DatabaseTableRowsResponse } from '../types/api';
import { request } from './request';

export const databaseBrowserApi = {
  getDatabaseTables() {
    return request<DatabaseTableOption[]>('/api/database-browser/tables');
  },
  getDatabaseTableRows(params: {
    tableName: string;
    page?: number;
    size?: number;
    keyword?: string;
    sortField?: string;
    sortOrder?: 'asc' | 'desc';
  }) {
    const query = new URLSearchParams({
      tableName: params.tableName,
      page: String(params.page ?? 1),
      size: String(params.size ?? 20),
      ...(params.keyword ? { keyword: params.keyword } : {}),
      ...(params.sortField ? { sortField: params.sortField } : {}),
      ...(params.sortOrder ? { sortOrder: params.sortOrder } : {}),
    });
    return request<DatabaseTableRowsResponse>(`/api/database-browser/rows?${query.toString()}`);
  },
  refreshDatabaseTable(tableName: string) {
    const query = new URLSearchParams({ tableName });
    return request<{ accepted: boolean; plannedTasks: number }>(`/api/database-browser/refresh?${query.toString()}`, {
      method: 'POST',
    });
  },
};
