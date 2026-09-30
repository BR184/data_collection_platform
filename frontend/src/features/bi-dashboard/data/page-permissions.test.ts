import { describe, expect, it } from 'vitest';
import { modules } from '../../../feature-manifest/modules';
import {
  BI_PAGE_PERMISSIONS,
  biPageDownloadPermission,
  biPageViewPermission,
} from './page-permissions';
import type { BiPageKey } from './types';

/** 后端页面 key（下载请求体的 pageKey）→ 前端一级导航页面 key。 */
const SHELL_PAGE_KEY_BY_BI_PAGE: Record<BiPageKey, string> = {
  requirements: 'bi-dashboard-requirements',
  design: 'bi-dashboard-design',
  coding: 'bi-dashboard-coding',
  'unit-test': 'bi-dashboard-unit-test',
  'integration-test': 'bi-dashboard-integration-test',
  'system-test': 'bi-dashboard-system-test',
  'customer-issues': 'bi-dashboard-customer-issues',
};

const BI_PAGE_KEYS = Object.keys(BI_PAGE_PERMISSIONS) as BiPageKey[];

describe('BI page permission codes', () => {
  it('exposes a view and a download code for every BI page', () => {
    expect(BI_PAGE_KEYS).toHaveLength(7);
    for (const pageKey of BI_PAGE_KEYS) {
      expect(biPageViewPermission(pageKey)).toBe(`bi.dashboard.${pageKey.replaceAll('-', '_')}.view`);
      expect(biPageDownloadPermission(pageKey))
        .toBe(`bi.dashboard.${pageKey.replaceAll('-', '_')}.download`);
    }
  });

  it('keeps view and download codes paired within the same page', () => {
    for (const pageKey of BI_PAGE_KEYS) {
      const view = biPageViewPermission(pageKey);
      const download = biPageDownloadPermission(pageKey);
      expect(download.slice(0, download.length - '.download'.length))
        .toBe(view.slice(0, view.length - '.view'.length));
    }
  });

  it('keeps the navigation manifest in sync with the page view codes', () => {
    const biPages = modules.find((module) => module.key === 'bi-dashboard')?.pages ?? [];
    const shellKeys = BI_PAGE_KEYS.map((pageKey) => SHELL_PAGE_KEY_BY_BI_PAGE[pageKey]);

    expect(biPages.map((page) => page.key).slice().sort()).toEqual(shellKeys.slice().sort());
    for (const page of biPages) {
      const biPageKey = BI_PAGE_KEYS.find((key) => SHELL_PAGE_KEY_BY_BI_PAGE[key] === page.key);
      expect(biPageKey).toBeDefined();
      expect(page.permission).toBe(biPageViewPermission(biPageKey as BiPageKey));
    }
  });
});
