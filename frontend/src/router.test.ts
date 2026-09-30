import { beforeEach, describe, expect, it } from 'vitest';
import router, { normalizeQuery, routeAccessRedirect } from './router';

describe('router query normalization', () => {
  beforeEach(() => {
    sessionStorage.clear();
  });

  it('preserves serialized filterGroup on statistic board routes', () => {
    const to = router.resolve({
      path: '/question-metrics/home',
      query: {
        detailPage: '2',
        filterGroup:
          '{"logic":"AND","conditions":[{"fieldKey":"tableName","operator":"eq","value":"issues","secondaryValue":""}]}',
      },
    });

    expect(normalizeQuery(to)).toBeNull();
  });

  it('preserves review-data keyword, serialized filterGroup, and legacy filter keys', () => {
    const to = router.resolve({
      path: '/review-data/home',
      query: {
        keyword: 'alpha',
        filterGroup:
          '{"logic":"OR","conditions":[{"fieldKey":"title","operator":"contains","value":"alpha","secondaryValue":""}]}',
        'filters.0.field': 'title',
        'filters.0.operator': 'contains',
        'filters.0.value': 'alpha',
      },
    });

    expect(normalizeQuery(to)).toBeNull();
  });

  it('drops legacy projectId when switching into fixed customer issue pages', () => {
    const from = router.resolve({
      path: '/review-data/home',
      query: {
        projectId: '1001',
        keyword: 'alpha',
        reviewType: '专题评审',
      },
    });
    const to = router.resolve({
      path: '/customer-issues/home',
      query: {
        projectId: '9',
        keyword: 'should-drop',
      },
    });

    expect(normalizeQuery(to, from)).toEqual({});
  });

  it('does not restore persisted projectId on customer issue routes', () => {
    sessionStorage.setItem('route-query:projectId', '9');
    const to = router.resolve('/customer-issues/cc-product-issues');

    expect(normalizeQuery(to)).toBeNull();
  });

  it('strips projectId from legacy customer issue links while keeping allowed milestone filters', () => {
    const to = router.resolve({
      path: '/customer-issues/cc-product-issues',
      query: {
        projectId: '9',
        milestoneTitle: '2026R3',
      },
    });

    expect(normalizeQuery(to)).toEqual({
      milestoneTitle: '2026R3',
    });
  });

  it('uses milestoneTitle instead of testingPhase on customer issue statistic boards', () => {
    const to = router.resolve({
      path: '/customer-issues/home',
      query: {
        testingPhase: 'CC2026R3',
        milestoneTitle: 'CC2026R3',
      },
    });

    expect(normalizeQuery(to)).toEqual({
      milestoneTitle: 'CC2026R3',
    });
  });

  it('drops testingPhase on customer issue illegal records', () => {
    const to = router.resolve({
      path: '/customer-issues/illegal-records',
      query: {
        testingPhase: 'CC2026R3',
        milestoneTitle: 'CC_Product_V1',
      },
    });

    expect(normalizeQuery(to)).toEqual({
      milestoneTitle: 'CC_Product_V1',
    });
  });

  it('keeps row dimension and member selection on the customer statistics board', () => {
    const to = router.resolve({
      path: '/customer-issues/customer-statistics',
      query: {
        groupBy: 'CUSTOMER_MODULE',
        customer: '客户甲',
        module: '模块A',
        milestoneTitle: 'CC2026R3',
        unknown: 'drop-me',
      },
    });

    expect(normalizeQuery(to)).toEqual({
      groupBy: 'CUSTOMER_MODULE',
      customer: '客户甲',
      module: '模块A',
      milestoneTitle: 'CC2026R3',
    });
  });

  it('keeps the drilldown collection on statistic board deep links', () => {
    const to = router.resolve({
      path: '/customer-issues/customer-statistics',
      query: {
        detailVisible: '1',
        detailRowKey: '{"groupBy":"CUSTOMER"}',
        detailColumnKey: 'defect_fix_rate',
        detailCollection: 'NUMERATOR',
        unknown: 'drop-me',
      },
    });

    expect(normalizeQuery(to)).toMatchObject({ detailCollection: 'NUMERATOR' });
  });

  it('carries row dimension and member selection across customer issue pages', () => {
    const from = router.resolve({
      path: '/customer-issues/customer-statistics',
      query: { groupBy: 'CUSTOMER_FUNCTION', function: '功能A' },
    });
    const to = router.resolve({
      path: '/customer-issues/customer-statistics',
      query: { groupBy: 'CUSTOMER_FUNCTION', function: '功能A', detailVisible: '1' },
    });

    expect(normalizeQuery(to, from)).toBeNull();
  });

  it('drops non-whitelisted query params on special standalone routes', () => {
    const to = router.resolve({
      path: '/external/code-review-form',
      query: {
        gitlabBaseUrl: 'https://gitlab.example.com',
        projectId: '2002',
        mrIid: '123',
        keyword: 'should-drop',
      },
    });

    expect(normalizeQuery(to)).toEqual({
      gitlabBaseUrl: 'https://gitlab.example.com',
      projectId: '2002',
      mrIid: '123',
    });
  });

  it('preserves whitelisted system-test issue search query keys', () => {
    const to = router.resolve({
      path: '/question-metrics/issue-search',
      query: {
        sourceInstance: 'cc',
        searchType: 'issueIid',
        keyword: '22637',
        projectId: '1001',
        filterGroup:
          '{"logic":"AND","conditions":[{"fieldKey":"assigneeName","operator":"eq","valueType":"LABEL_GROUP","labelGroupId":1,"labelGroupName":"核心人员"}]}',
      },
    });

    expect(normalizeQuery(to)).toEqual({
      sourceInstance: 'cc',
      projectId: '1001',
      filterGroup:
        '{"logic":"AND","conditions":[{"fieldKey":"assigneeName","operator":"eq","valueType":"LABEL_GROUP","labelGroupId":1,"labelGroupName":"核心人员"}]}',
    });
  });

  it('preserves record page filterGroup on customer issue record routes', () => {
    const to = router.resolve({
      path: '/customer-issues/cc-product-issues',
      query: {
        keyword: 'alpha',
        filterGroup:
          '{"logic":"AND","conditions":[{"fieldKey":"moduleName","operator":"eq","value":"草图","secondaryValue":""}]}',
        'filters.0.field': 'moduleName',
        'filters.0.operator': 'eq',
        'filters.0.value': '草图',
      },
    });

    expect(normalizeQuery(to)).toBeNull();
  });

  it('preserves every CC_PRODUCT quick filter query parameter', () => {
    const to = router.resolve({
      path: '/customer-issues/cc-product-issues',
      query: {
        customerName: '极目数字',
        reasonCategory: '需求变更',
        authorName: '张三',
        testingPhase: '新增需求',
        handlerName: '李四',
        assigneeName: '王五',
        severityLevel: '一级缺陷',
        priorityLevel: 'P1',
        issueState: 'opened',
        bugStatus: '未关闭',
        category: '功能问题',
        delayCause: '需求变更',
        fixUser: '赵六',
        createdAtStart: '2026-01-01',
        createdAtEnd: '2026-01-31',
        updatedAtStart: '2026-02-01',
        updatedAtEnd: '2026-02-28',
      },
    });

    expect(normalizeQuery(to)).toBeNull();
  });

  it('keeps label group settings query keys inside system settings', () => {
    const to = router.resolve({
      path: '/system-settings/label-group-settings',
      query: {
        valueType: 'STRING',
        keyword: '核心',
        temp_debug: 'drop',
      },
    });

    expect(normalizeQuery(to)).toEqual({
      valueType: 'STRING',
      keyword: '核心',
    });
  });

  it('keeps the stable product version across BI stages and limits coding filters', () => {
    const coding = router.resolve({
      path: '/bi-dashboard/coding',
      query: {
        productVersionId: '10',
        granularity: 'week',
        source: 'cc',
        repositoryId: 'repo-1',
        testingPhase: 'drop',
      },
    });
    expect(normalizeQuery(coding)).toEqual({
      productVersionId: '10',
      granularity: 'week',
      source: 'cc',
      repositoryId: 'repo-1',
    });

    const systemTest = router.resolve({
      path: '/bi-dashboard/system-test',
      query: { productVersionId: '10', source: 'cc' },
    });
    expect(normalizeQuery(systemTest)).toEqual({ productVersionId: '10' });
  });

  it('uses the managed BI default when entering the module without an explicit version', () => {
    sessionStorage.setItem('route-query:productVersionId', '9');
    const to = router.resolve('/bi-dashboard/system-test');
    const from = router.resolve('/quality-board/rd-quality-board');

    expect(normalizeQuery(to, from)).toBeNull();
  });

  it('carries the current product version only while navigating inside the BI module', () => {
    sessionStorage.setItem('route-query:productVersionId', '9');
    const from = router.resolve('/bi-dashboard/coding?productVersionId=10');
    const to = router.resolve('/bi-dashboard/system-test');

    expect(normalizeQuery(to, from)).toEqual({ productVersionId: '10' });
  });

  it('keeps an explicit BI deep-link version instead of replacing it with the current selection', () => {
    const from = router.resolve('/bi-dashboard/coding?productVersionId=10');
    const to = router.resolve('/bi-dashboard/system-test?productVersionId=11');

    expect(normalizeQuery(to, from)).toBeNull();
  });

  it('keeps the customer issue page independent from product-version BI scopes', () => {
    const from = router.resolve('/bi-dashboard/coding?productVersionId=10&repositoryId=repo-1');
    const to = router.resolve({
      path: '/bi-dashboard/customer-issues',
      query: {
        productVersionId: '11',
        milestoneBusinessKey: 'customer-mile-1',
        customerKind: 'VALUE',
        customer: 'missing',
        moduleKind: 'MISSING',
        repositoryId: 'drop-me',
      },
    });

    expect(normalizeQuery(to, from)).toEqual({
      milestoneBusinessKey: 'customer-mile-1',
      customerKind: 'VALUE',
      customer: 'missing',
      moduleKind: 'MISSING',
    });
  });
});

describe('router access guard', () => {
  it('allows guests to visit non-system pages before the page component loads', () => {
    const to = router.resolve('/review-data/home');

    expect(routeAccessRedirect(to, { permissions: [], authenticated: false })).toBe('/quality-board/rd-quality-board');
  });

  it('keeps guests on the fallback page when it is already the target', () => {
    const to = router.resolve('/quality-board/rd-quality-board');

    expect(routeAccessRedirect(to, { permissions: [], authenticated: false })).toBeNull();
  });

  it('redirects approval users away from hidden pages', () => {
    const to = router.resolve('/system-settings/mirror-settings');

    expect(routeAccessRedirect(to, { permissions: ['quality.rd.view'], authenticated: true })).toBe('/quality-board/rd-quality-board');
  });

  it('redirects guests away from system settings', () => {
    const to = router.resolve('/system-settings/mirror-settings');

    expect(routeAccessRedirect(to, { permissions: [], authenticated: false })).toBe('/quality-board/rd-quality-board');
  });

  it('protects each BI stage route with its own view permission', () => {
    const to = router.resolve('/bi-dashboard/system-test');
    expect(routeAccessRedirect(to, { permissions: ['quality.rd.view'], authenticated: true }))
      .toBe('/quality-board/rd-quality-board');
    // 只持有需求页权限时不得进入系统测试页，改跳到自己可访问的第一个页面。
    expect(routeAccessRedirect(to, { permissions: ['bi.dashboard.requirements.view'], authenticated: true }))
      .toBe('/bi-dashboard/requirements');
    expect(routeAccessRedirect(to, { permissions: ['bi.dashboard.system_test.view'], authenticated: true }))
      .toBeNull();
    const customerPage = router.resolve('/bi-dashboard/customer-issues');
    expect(routeAccessRedirect(customerPage, { permissions: ['bi.dashboard.customer_issues.view'], authenticated: true }))
      .toBeNull();
    // 反向：持有系统测试页权限时同样不得进入客户问题页，改跳到自己可访问的第一个页面。
    expect(routeAccessRedirect(customerPage, { permissions: ['bi.dashboard.system_test.view'], authenticated: true }))
      .toBe('/bi-dashboard/system-test');
  });
});
