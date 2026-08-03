import { modules } from './modules';
import { pageByKey } from './lookups';
import type { AccessUser, PageKey, ShellModule, ShellPage } from './types';

export function canAccessPage(page: ShellPage, user: AccessUser) {
  return user.authenticated && (!page.permission || (user.permissions ?? []).includes(page.permission));
}

export function hasPermission(user: AccessUser, permission: string) {
  return user.authenticated && (user.permissions ?? []).includes(permission);
}

export function canAccessPageKey(pageKey: PageKey, user: AccessUser) {
  const page = pageByKey.get(pageKey);
  return page ? canAccessPage(page, user) : false;
}

export function getVisibleModules(user: AccessUser): ShellModule[] {
  return modules
    .map((module) => ({
      ...module,
      pages: module.pages.filter((page) => canAccessPage(page, user)),
    }))
    .filter((module) => module.pages.length > 0);
}

export function getFirstAccessiblePagePath(user: AccessUser) {
  return getVisibleModules(user)[0]?.pages[0]?.path ?? '/quality-board/rd-quality-board';
}
