import { describe, expect, it } from 'vitest';
import { canAccessPageKey, getFirstAccessiblePagePath, getVisibleModules, type AccessUser } from './feature-manifest';
import { modules } from './feature-manifest/modules';

const guest: AccessUser = { permissions: [], authenticated: false };
const admin: AccessUser = {
  permissions: modules.flatMap((module) => module.pages.map((page) => page.permission).filter(Boolean) as string[]),
  authenticated: true,
};
const approval: AccessUser = {
  permissions: ['quality.other.view'],
  authenticated: true,
};

/** BI 一级导航页面 key → 该页查看权限码（独立写死，作为导航清单的反查预言机）。 */
const BI_VIEW_PERMISSION_BY_PAGE_KEY: Record<string, string> = {
  'bi-dashboard-requirements': 'bi.dashboard.requirements.view',
  'bi-dashboard-design': 'bi.dashboard.design.view',
  'bi-dashboard-coding': 'bi.dashboard.coding.view',
  'bi-dashboard-unit-test': 'bi.dashboard.unit_test.view',
  'bi-dashboard-integration-test': 'bi.dashboard.integration_test.view',
  'bi-dashboard-system-test': 'bi.dashboard.system_test.view',
  'bi-dashboard-customer-issues': 'bi.dashboard.customer_issues.view',
};

describe('feature manifest access rules', () => {
  it('requires authentication for business pages', () => {
    for (const module of modules) {
      for (const page of module.pages) {
        expect(canAccessPageKey(page.key, guest)).toBe(false);
      }
    }
    expect(canAccessPageKey('database-browser', guest)).toBe(false);
  });

  it('lets admins see system settings and login-only charts', () => {
    expect(canAccessPageKey('review-data-home', admin)).toBe(true);
    expect(canAccessPageKey('quality-board-other-board', admin)).toBe(true);
    expect(canAccessPageKey('code-review-multi-board', admin)).toBe(true);
    expect(canAccessPageKey('bi-dashboard-system-test', admin)).toBe(true);
    expect(canAccessPageKey('bi-dashboard-customer-issues', admin)).toBe(true);
    expect(canAccessPageKey('label-group-settings', admin)).toBe(true);
    expect(canAccessPageKey('database-browser', admin)).toBe(true);
  });

  it('hides approval-restricted management pages from approval users', () => {
    expect(canAccessPageKey('quality-board-other-board', approval)).toBe(true);
    expect(canAccessPageKey('review-data-home', approval)).toBe(false);
    expect(canAccessPageKey('code-review-illegal-records', approval)).toBe(false);
    expect(canAccessPageKey('question-metrics-home', approval)).toBe(false);
    expect(canAccessPageKey('customer-issues-cc-product-issues', approval)).toBe(false);
  });

  it('gates the customer statistics page behind its own view permission', () => {
    const permission = 'customer_issue.customer.view';
    expect(modules.flatMap((module) => module.pages).find((page) => page.key === 'customer-issues-customer-statistics')?.permission)
      .toBe(permission);
    expect(canAccessPageKey('customer-issues-customer-statistics', guest)).toBe(false);
    expect(canAccessPageKey('customer-issues-customer-statistics', approval)).toBe(false);
    expect(
      canAccessPageKey('customer-issues-customer-statistics', {
        permissions: [permission],
        authenticated: true,
      }),
    ).toBe(true);
  });

  it('drops modules with no visible pages for the current user', () => {
    const visibleKeys = getVisibleModules(approval).map((module) => module.key);
    expect(visibleKeys).not.toContain('system-settings');
    expect(visibleKeys).toContain('quality-board');
    expect(visibleKeys).not.toContain('bi-dashboard');
  });

  it('hides system settings from guest users', () => {
    const visibleKeys = getVisibleModules(guest).map((module) => module.key);
    expect(visibleKeys).not.toContain('system-settings');
  });

  it('does not expose the removed integration test module', () => {
    const visibleKeys = getVisibleModules(admin).map((module) => module.key);
    expect(visibleKeys).not.toContain('integration-test');
  });

  it('gates every BI page behind its own view permission', () => {
    const biModule = modules.find((module) => module.key === 'bi-dashboard');
    expect(biModule?.pages).toHaveLength(7);
    for (const page of biModule?.pages ?? []) {
      expect(page.permission).toBe(BI_VIEW_PERMISSION_BY_PAGE_KEY[page.key]);
    }
    expect(biModule?.pages.find((page) => page.key === 'bi-dashboard-customer-issues')?.path)
      .toBe('/bi-dashboard/customer-issues');
  });

  it('shows only the BI pages whose view permission the user holds', () => {
    const customerIssueOnly: AccessUser = {
      permissions: ['bi.dashboard.customer_issues.view'],
      authenticated: true,
    };
    const biModule = getVisibleModules(customerIssueOnly).find((module) => module.key === 'bi-dashboard');

    expect(biModule?.pages.map((page) => page.key)).toEqual(['bi-dashboard-customer-issues']);
    expect(canAccessPageKey('bi-dashboard-customer-issues', customerIssueOnly)).toBe(true);
    expect(canAccessPageKey('bi-dashboard-system-test', customerIssueOnly)).toBe(false);
  });

  it('no longer grants BI pages through the removed module level permission', () => {
    const legacyUser: AccessUser = {
      permissions: ['bi.dashboard.view', 'bi.dashboard.download'],
      authenticated: true,
    };

    expect(getVisibleModules(legacyUser).map((module) => module.key)).not.toContain('bi-dashboard');
  });

  it('keeps the quality board as the authenticated default even when BI is first in navigation', () => {
    expect(getFirstAccessiblePagePath(admin)).toBe('/quality-board/rd-quality-board');
    expect(getFirstAccessiblePagePath(guest)).toBe('/quality-board/rd-quality-board');
  });
});
