import { beforeEach, describe, expect, it, vi } from 'vitest';
import { dropdownOptionApi } from './dropdown-option-api';

vi.mock('./request', () => ({
  request: vi.fn(() => Promise.resolve({})),
}));

import { request } from './request';

describe('dropdownOptionApi', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('lists fields without query', () => {
    dropdownOptionApi.listFields();

    expect(request).toHaveBeenCalledWith('/api/dropdown-option-fields');
  });

  it('fetches field config with encoded field key', () => {
    dropdownOptionApi.getFieldConfig('review-data.form.project-name');

    expect(request).toHaveBeenCalledWith('/api/dropdown-option-fields/review-data.form.project-name');
  });

  it('fetches acquired options with keyword and limit', () => {
    dropdownOptionApi.listAcquiredOptions('review-data.form.project-name', '王', 20);

    const url = String(vi.mocked(request).mock.calls.at(-1)?.[0]);
    expect(url).toContain('/api/dropdown-option-fields/review-data.form.project-name/acquired-options?');
    expect(url).toContain('limit=20');
    expect(url).toContain(encodeURIComponent('王'));
  });

  it('previews draft rules via POST payload', () => {
    const payload = {
      rules: { acquiredRules: [], manualRules: [] },
      manualOptions: ['李四'],
    };

    dropdownOptionApi.previewOptions('review-data.form.project-name', payload);

    expect(request).toHaveBeenCalledWith('/api/dropdown-option-fields/review-data.form.project-name/preview', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  });

  it('saves config via PUT payload', () => {
    const payload = {
      rules: { acquiredRules: [], manualRules: [] },
      manualOptions: [],
      version: 3,
    };

    dropdownOptionApi.saveConfig('review-data.form.project-name', payload);

    expect(request).toHaveBeenCalledWith('/api/dropdown-option-fields/review-data.form.project-name', {
      method: 'PUT',
      body: JSON.stringify(payload),
    });
  });

  it('binds field via binding payload', () => {
    dropdownOptionApi.bindField('review-data.form.project-name', { target: 'COPY' });

    expect(request).toHaveBeenCalledWith('/api/dropdown-option-fields/review-data.form.project-name/binding', {
      method: 'PUT',
      body: JSON.stringify({ target: 'COPY' }),
    });
  });
});
