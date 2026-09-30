import { defineComponent } from 'vue';
import { describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createRouter, createWebHashHistory } from 'vue-router';

vi.mock('../components/StatisticBoardView.vue', () => ({
  default: defineComponent({
    props: {
      boardKey: {
        type: String,
        required: true,
      },
    },
    template: '<div data-testid="board-key">{{ boardKey }}</div>',
  }),
}));

import StatisticBoardPage from './StatisticBoardPage.vue';
import type { PageKey } from '../feature-manifest/types';

describe('StatisticBoardPage mount smoke', () => {
  async function mountFor(pageKey: PageKey, path: string) {
    const router = createRouter({
      history: createWebHashHistory(),
      routes: [{ path, component: StatisticBoardPage, meta: { pageKey } }],
    });
    await router.push(path);
    await router.isReady();

    const wrapper = mount(StatisticBoardPage, {
      global: { plugins: [router] },
    });

    await flushPromises();
    return wrapper;
  }

  it('maps the route page key to the expected board key', async () => {
    const wrapper = await mountFor('question-metrics-home', '/question-metrics/home');

    expect(wrapper.get('[data-testid="board-key"]').text()).toBe('system-test-defect-summary');

    wrapper.unmount();
  });

  it('maps the customer statistics page key to its own board key', async () => {
    const wrapper = await mountFor('customer-issues-customer-statistics', '/customer-issues/customer-statistics');

    expect(wrapper.get('[data-testid="board-key"]').text()).toBe('customer-issue-customer-statistics');

    wrapper.unmount();
  });
});
