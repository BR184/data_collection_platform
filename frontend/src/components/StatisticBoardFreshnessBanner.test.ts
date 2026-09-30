import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import StatisticBoardFreshnessBanner from './StatisticBoardFreshnessBanner.vue';

function mountBanner(props: { dataAsOf?: string | null; pendingUpdates?: number } = {}) {
  return mount(StatisticBoardFreshnessBanner, { props });
}

describe('StatisticBoardFreshnessBanner', () => {
  it('does not render for an authoritative result', () => {
    expect(mountBanner().find('[data-testid="stat-board-freshness-banner"]').exists()).toBe(false);
    expect(mountBanner({ pendingUpdates: 0 }).find('[data-testid="stat-board-freshness-banner"]').exists()).toBe(
      false,
    );
  });

  it('shows the data time and pending change count in a warning alert', () => {
    const wrapper = mountBanner({ dataAsOf: '2026-09-30T10:00:00', pendingUpdates: 34 });

    const banner = wrapper.get('[data-testid="stat-board-freshness-banner"]');
    expect(banner.text()).toContain('数据截至 2026-09-30 10:00:00');
    expect(banner.text()).toContain('仍有 34 项待更新');
    expect(banner.find('.el-alert--warning').exists()).toBe(true);
  });

  it('falls back to an explicit unknown time when the data time is missing', () => {
    const wrapper = mountBanner({ pendingUpdates: 2 });

    expect(wrapper.get('[data-testid="stat-board-freshness-banner"]').text()).toContain('数据截至 未知时刻');
  });
});
