import type {
  DropdownOptionBindingRequest,
  DropdownOptionFieldConfig,
  DropdownOptionFieldSummary,
  DropdownOptionPreviewRequest,
  DropdownOptionPreviewResponse,
  DropdownOptionConfigSaveRequest,
} from '../types/api';
import { request } from './request';

/** 下拉框选项设置 API：字段清单、配置读写、值池联想、预览与绑定/拆分。 */
export const dropdownOptionApi = {
  listFields() {
    return request<DropdownOptionFieldSummary[]>('/api/dropdown-option-fields');
  },
  getFieldConfig(fieldKey: string) {
    return request<DropdownOptionFieldConfig>(
      `/api/dropdown-option-fields/${encodeURIComponent(fieldKey)}`,
    );
  },
  listAcquiredOptions(fieldKey: string, keyword: string, limit = 50) {
    const query = new URLSearchParams({
      limit: String(limit),
      ...(keyword ? { keyword } : {}),
    });
    return request<string[]>(
      `/api/dropdown-option-fields/${encodeURIComponent(fieldKey)}/acquired-options?${query.toString()}`,
    );
  },
  previewOptions(fieldKey: string, payload: DropdownOptionPreviewRequest) {
    return request<DropdownOptionPreviewResponse>(
      `/api/dropdown-option-fields/${encodeURIComponent(fieldKey)}/preview`,
      { method: 'POST', body: JSON.stringify(payload) },
    );
  },
  saveConfig(fieldKey: string, payload: DropdownOptionConfigSaveRequest) {
    return request<DropdownOptionFieldConfig>(
      `/api/dropdown-option-fields/${encodeURIComponent(fieldKey)}`,
      { method: 'PUT', body: JSON.stringify(payload) },
    );
  },
  bindField(fieldKey: string, payload: DropdownOptionBindingRequest) {
    return request<DropdownOptionFieldConfig>(
      `/api/dropdown-option-fields/${encodeURIComponent(fieldKey)}/binding`,
      { method: 'PUT', body: JSON.stringify(payload) },
    );
  },
};
