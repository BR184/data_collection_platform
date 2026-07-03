import { modules } from './modules';
import { pageByKey, pageModuleKeyByPageKey } from './lookups';
import type { AccessUser, PageKey, ShellModule, ShellPage } from './types';

export function canAccessPage(page: ShellPage, user: AccessUser) {
  const moduleKey = pageModuleKeyByPageKey.get(page.key);
  if (moduleKey === 'system-settings') {
    return user.authenticated && user.role === 'ADMIN';
  }
  if (page.hiddenForApproval && user.role === 'APPROVAL') {
    return false;
  }
  return true;
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
