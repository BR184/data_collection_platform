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
});
