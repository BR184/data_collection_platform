import { describe, expect, it } from 'vitest';
import { canAccessPageKey, getVisibleModules, type AccessUser } from './feature-manifest';
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

  it('drops modules with no visible pages for the current user', () => {
    const visibleKeys = getVisibleModules(approval).map((module) => module.key);
    expect(visibleKeys).not.toContain('system-settings');
    expect(visibleKeys).toContain('quality-board');
  });

  it('hides system settings from guest users', () => {
    const visibleKeys = getVisibleModules(guest).map((module) => module.key);
    expect(visibleKeys).not.toContain('system-settings');
  });

  it('does not expose the removed integration test module', () => {
    const visibleKeys = getVisibleModules(admin).map((module) => module.key);
    expect(visibleKeys).not.toContain('integration-test');
  });
});
