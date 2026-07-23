import type { FactRebuildSubmission } from '../types/api';
import { request } from './request';

/** 提交指定数据源的全量事实重建运行。 */
export const factBuildApi = {
  rebuildFacts(configId: number) {
    const query = new URLSearchParams({ configId: String(configId) });
    return request<FactRebuildSubmission>(`/api/facts/rebuild?${query.toString()}`, {
      method: 'POST',
    });
  },
};
