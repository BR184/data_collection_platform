import type {
  CatMirrorConfig,
  CatMirrorConnectionResult,
  CatMirrorMapping,
  CatMirrorSaveMapping,
  CatMirrorSettings,
  CatMirrorSubmission,
} from '../types/api';
import { request } from './request';

/** 系统设置中的 CAT 独立镜像 API。 */
export const catMirrorApi = {
  getCatMirrorSettings() {
    return request<CatMirrorSettings>('/api/bi-cat-mirror/settings');
  },
  saveCatMirrorConfig(config: CatMirrorConfig) {
    return request<CatMirrorConfig>('/api/bi-cat-mirror/config', {
      method: 'PUT',
      body: JSON.stringify(config),
    });
  },
  saveCatMirrorMappings(mappings: CatMirrorSaveMapping[]) {
    return request<CatMirrorMapping[]>('/api/bi-cat-mirror/mappings', {
      method: 'PUT',
      body: JSON.stringify(mappings),
    });
  },
  testCatMirrorConnection() {
    return request<CatMirrorConnectionResult>('/api/bi-cat-mirror/test-connection', {
      method: 'POST',
      timeoutMs: 120_000,
    });
  },
  startCatMirrorFullSync() {
    return request<CatMirrorSubmission>('/api/bi-cat-mirror/full-sync', {
      method: 'POST',
      timeoutMs: 30_000,
    });
  },
  startCatMirrorFullCompensationSync() {
    return request<CatMirrorSubmission>('/api/bi-cat-mirror/full-compensation-sync', {
      method: 'POST',
      timeoutMs: 30_000,
    });
  },
};
