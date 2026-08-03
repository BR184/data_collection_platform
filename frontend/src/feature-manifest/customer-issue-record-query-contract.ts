export const CUSTOMER_ISSUE_RECORD_RANGE_QUERY_KEYS = {
  createdAtRange: {
    startKey: 'createdAtStart',
    endKey: 'createdAtEnd',
    valueType: 'date',
  },
  updatedAtRange: {
    startKey: 'updatedAtStart',
    endKey: 'updatedAtEnd',
    valueType: 'date',
  },
  plannedResolutionAtRange: {
    startKey: 'plannedResolutionAtStart',
    endKey: 'plannedResolutionAtEnd',
    valueType: 'date',
  },
  retentionHoursRange: {
    startKey: 'retentionHoursMin',
    endKey: 'retentionHoursMax',
    valueType: 'number',
  },
} as const;

export type CustomerIssueRecordRangeFilterKey =
  keyof typeof CUSTOMER_ISSUE_RECORD_RANGE_QUERY_KEYS;

/** 判断快速筛选字段是否为客户问题记录页的范围字段。 */
export function isCustomerIssueRecordRangeFilterKey(
  key: string,
): key is CustomerIssueRecordRangeFilterKey {
  return Object.hasOwn(CUSTOMER_ISSUE_RECORD_RANGE_QUERY_KEYS, key);
}

export const DELAY_RECORD_RANGE_FILTER_KEYS: readonly CustomerIssueRecordRangeFilterKey[] = [
  'createdAtRange',
  'updatedAtRange',
];

export const CC_PRODUCT_RECORD_RANGE_FILTER_KEYS: readonly CustomerIssueRecordRangeFilterKey[] =
  Object.keys(CUSTOMER_ISSUE_RECORD_RANGE_QUERY_KEYS) as CustomerIssueRecordRangeFilterKey[];

function rangeQueryKeys(key: CustomerIssueRecordRangeFilterKey): readonly string[] {
  const range = CUSTOMER_ISSUE_RECORD_RANGE_QUERY_KEYS[key];
  return [range.startKey, range.endKey];
}

/**
 * CC_PRODUCT 快速筛选字段到 URL 查询参数的权威映射。
 *
 * 范围字段对应两个查询参数；其他字段对应同名参数。路由白名单和页面字段可见性
 * 必须从此映射派生，避免新增筛选控件后参数被路由归一化静默删除。
 */
export const CC_PRODUCT_QUICK_FILTER_QUERY_KEYS: Record<string, readonly string[]> = {
  milestoneTitle: ['milestoneTitle'],
  moduleName: ['moduleName'],
  functionName: ['functionName'],
  customerName: ['customerName'],
  reasonCategory: ['reasonCategory'],
  authorName: ['authorName'],
  testingPhase: ['testingPhase'],
  handlerName: ['handlerName'],
  assigneeName: ['assigneeName'],
  severityLevel: ['severityLevel'],
  priorityLevel: ['priorityLevel'],
  issueState: ['issueState'],
  bugStatus: ['bugStatus'],
  category: ['category'],
  delayCause: ['delayCause'],
  fixUser: ['fixUser'],
  plannedResolutionAtRange: rangeQueryKeys('plannedResolutionAtRange'),
  plannedMergeVersionBranch: ['plannedMergeVersionBranch'],
  retentionHoursRange: rangeQueryKeys('retentionHoursRange'),
  createdAtRange: rangeQueryKeys('createdAtRange'),
  updatedAtRange: rangeQueryKeys('updatedAtRange'),
};

const baseQueryKeys = [
  'page',
  'pageSize',
  'sortBy',
  'sortOrder',
  'keyword',
  'issueIid',
  'title',
  'projectName',
  'filterGroup',
  'filterLogic',
];

/** CC_PRODUCT 记录页允许保留并触发列表加载的全部 URL 查询参数。 */
export const CUSTOMER_ISSUE_RECORD_QUERY_KEYS = [
  ...baseQueryKeys,
  ...new Set(Object.values(CC_PRODUCT_QUICK_FILTER_QUERY_KEYS).flat()),
];
