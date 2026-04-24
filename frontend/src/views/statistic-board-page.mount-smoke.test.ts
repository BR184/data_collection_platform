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

describe('StatisticBoardPage mount smoke', () => {
  it('maps the route page key to the expected board key', async () => {
    const router = createRouter({
      history: createWebHashHistory(),
      routes: [
        {
          path: '/question-metrics/home',
          component: StatisticBoardPage,
          meta: { pageKey: 'question-metrics-home' },
        },
      ],
    });

    await router.push('/question-metrics/home');
    await router.isReady();

    const wrapper = mount(StatisticBoardPage, {
      global: { plugins: [router] },
    });

    await flushPromises();

    expect(wrapper.get('[data-testid="board-key"]').text()).toBe('system-test-defect-summary');

    wrapper.unmount();
  });
});
