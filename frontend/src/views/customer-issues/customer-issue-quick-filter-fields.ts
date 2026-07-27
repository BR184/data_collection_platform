import { CC_PRODUCT_QUICK_FILTER_QUERY_KEYS } from '../../feature-manifest/customer-issue-record-query-contract';

/** CC_PRODUCT 记录页快速筛选允许展示的字段。 */
export const CC_PRODUCT_QUICK_FILTER_KEYS: ReadonlySet<string> = new Set(
  Object.keys(CC_PRODUCT_QUICK_FILTER_QUERY_KEYS),
);

/** 判断字段是否属于 CC_PRODUCT 快速筛选，不影响延期专题的字段策略。 */
export function isCcProductQuickFilterKey(key: string): boolean {
  return CC_PRODUCT_QUICK_FILTER_KEYS.has(key);
}
