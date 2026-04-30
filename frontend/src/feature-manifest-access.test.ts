import { describe, expect, it } from 'vitest';
import { canAccessPageKey, getVisibleModules, type AccessUser } from './feature-manifest';

const guest: AccessUser = { role: 'GUEST', authenticated: false };
const admin: AccessUser = { role: 'ADMIN', authenticated: true };
const approval: AccessUser = { role: 'APPROVAL', authenticated: true };

describe('feature manifest access rules', () => {
  it('keeps guest users on query-oriented pages and hides login-only pages', () => {
    expect(canAccessPageKey('quality-board-rd-quality-board', guest)).toBe(true);
    expect(canAccessPageKey('question-metrics-illegal-records', guest)).toBe(true);
    expect(canAccessPageKey('quality-board-other-board', guest)).toBe(false);
    expect(canAccessPageKey('testing-phase-definition', guest)).toBe(false);
  });

  it('lets admins see system settings and login-only charts', () => {
    expect(canAccessPageKey('quality-board-other-board', admin)).toBe(true);
    expect(canAccessPageKey('code-review-multi-board', admin)).toBe(true);
    expect(canAccessPageKey('testing-phase-definition', admin)).toBe(true);
  });

  it('hides approval-restricted management pages from approval users', () => {
    expect(canAccessPageKey('quality-board-other-board', approval)).toBe(true);
    expect(canAccessPageKey('code-review-illegal-records', approval)).toBe(false);
    expect(canAccessPageKey('question-metrics-home', approval)).toBe(false);
    expect(canAccessPageKey('customer-issues-cc-product-issues', approval)).toBe(false);
    expect(canAccessPageKey('testing-phase-definition', approval)).toBe(false);
  });

  it('drops modules with no visible pages for the current user', () => {
    const visibleKeys = getVisibleModules(approval).map((module) => module.key);
    expect(visibleKeys).not.toContain('system-settings');
    expect(visibleKeys).toContain('quality-board');
  });
});
