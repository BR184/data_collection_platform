import type { BiPageKey } from './types';

/**
 * BI 页面 → 该页面的查看与下载权限码。
 *
 * 与后端 `BiPagePermissionResolver` 一一对应：页面身份在后端只存在于端点路径常量与下载请求体，
 * 前端需要在菜单（查看）与图表下载按钮（下载）两处使用同一份映射，避免各自硬编码页面清单。
 * `Record<BiPageKey, ...>` 由页面 key 联合类型约束完整性——新增 BI 页面时此处缺项会直接编译失败。
 */
export const BI_PAGE_PERMISSIONS: Record<BiPageKey, { view: string; download: string }> = {
  requirements: {
    view: 'bi.dashboard.requirements.view',
    download: 'bi.dashboard.requirements.download',
  },
  design: { view: 'bi.dashboard.design.view', download: 'bi.dashboard.design.download' },
  coding: { view: 'bi.dashboard.coding.view', download: 'bi.dashboard.coding.download' },
  'unit-test': {
    view: 'bi.dashboard.unit_test.view',
    download: 'bi.dashboard.unit_test.download',
  },
  'integration-test': {
    view: 'bi.dashboard.integration_test.view',
    download: 'bi.dashboard.integration_test.download',
  },
  'system-test': {
    view: 'bi.dashboard.system_test.view',
    download: 'bi.dashboard.system_test.download',
  },
  'customer-issues': {
    view: 'bi.dashboard.customer_issues.view',
    download: 'bi.dashboard.customer_issues.download',
  },
};

/**
 * 页面查看权限码，供菜单与路由鉴权按页面取值。
 *
 * @param pageKey BI 页面标识
 * @returns 该页面的查看权限码
 */
export function biPageViewPermission(pageKey: BiPageKey): string {
  return BI_PAGE_PERMISSIONS[pageKey].view;
}

/**
 * 页面下载权限码。
 *
 * 图表下载按钮的可见性与后端下载通道使用同一权限码：按钮只是提前隐藏，真正的拒绝由后端按
 * pageKey 精确校验（查看 + 下载）给出，前端不以隐藏按钮代替授权。
 *
 * @param pageKey BI 页面标识
 * @returns 该页面的下载权限码
 */
export function biPageDownloadPermission(pageKey: BiPageKey): string {
  return BI_PAGE_PERMISSIONS[pageKey].download;
}
