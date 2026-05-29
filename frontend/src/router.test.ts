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

  it('keeps persisted projectId but drops unrelated module query params when switching modules', () => {
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
        projectId: '1001',
        keyword: 'should-drop',
      },
    });

    expect(normalizeQuery(to, from)).toEqual({
      projectId: '1001',
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
});

describe('router access guard', () => {
  it('redirects guests away from login-only pages before the page component loads', () => {
    const to = router.resolve('/review-data/home');

    expect(routeAccessRedirect(to, { role: 'GUEST', authenticated: false })).toBe('/quality-board/rd-quality-board');
  });

  it('allows guests to visit public pages', () => {
    const to = router.resolve('/quality-board/rd-quality-board');

    expect(routeAccessRedirect(to, { role: 'GUEST', authenticated: false })).toBeNull();
  });

  it('redirects approval users away from hidden pages', () => {
    const to = router.resolve('/system-settings/mirror-settings');

    expect(routeAccessRedirect(to, { role: 'APPROVAL', authenticated: true })).toBe('/quality-board/rd-quality-board');
  });
});
