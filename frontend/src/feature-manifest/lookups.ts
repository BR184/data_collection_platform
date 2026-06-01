import { modules } from './modules';

export const moduleByKey = new Map(modules.map((item) => [item.key, item] as const));
export const pageByKey = new Map(modules.flatMap((item) => item.pages.map((page) => [page.key, page] as const)));
export const pageModuleKeyByPageKey = new Map(
  modules.flatMap((item) => item.pages.map((page) => [page.key, item.key] as const)),
);
