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
  createdAtRange: ['createdAtStart', 'createdAtEnd'],
  updatedAtRange: ['updatedAtStart', 'updatedAtEnd'],
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
