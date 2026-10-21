import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import StatisticBoardFreshnessBanner from './StatisticBoardFreshnessBanner.vue';
import type { ReadViewMetadata } from '../types/api/read-view';

function mountBanner(props: { dataAsOf?: string | null; pendingUpdates?: number;
  sourceState?: ReadViewMetadata['sourceState']; sourceStateDetail?: string; escalated?: boolean } = {}) {
  return mount(StatisticBoardFreshnessBanner, { props });
}

describe('StatisticBoardFreshnessBanner', () => {
  it('does not render for an authoritative result', () => {
    expect(mountBanner().find('[data-testid="stat-board-freshness-banner"]').exists()).toBe(false);
    expect(mountBanner({ pendingUpdates: 0 }).find('[data-testid="stat-board-freshness-banner"]').exists()).toBe(
      false,
    );
  });

  it('shows the data time and pending change count without implying data is unavailable', () => {
    const wrapper = mountBanner({ dataAsOf: '2026-09-30T10:00:00', pendingUpdates: 34 });

    const banner = wrapper.get('[data-testid="stat-board-freshness-banner"]');
    expect(banner.text()).toContain('数据截至 2026-09-30 10:00:00');
    expect(banner.text()).toContain('仍有 34 项待更新');
    expect(banner.find('.el-alert--info').exists()).toBe(true);
  });

  it('shows updating or attention even when no pending root count is known', () => {
    for (const state of ['UPDATING', 'ATTENTION'] as const) {
      const banner = mountBanner({ sourceState: state }).get('[data-testid="stat-board-freshness-banner"]');
      expect(banner.text()).toContain('当前完整数据可继续使用');
      expect(banner.text()).not.toContain('0 项待更新');
    }
  });

  it('renders administrator detail and escalated alert only when supplied by the server', () => {
    const banner = mountBanner({ sourceState: 'ATTENTION', sourceStateDetail: '来源连接异常', escalated: true });
    expect(banner.text()).toContain('来源连接异常');
    expect(banner.text()).toContain('超过提醒时限');
    expect(banner.find('.el-alert--warning').exists()).toBe(true);
    expect(mountBanner({ sourceState: 'ATTENTION' }).text()).not.toContain('来源连接异常');
  });

  it('falls back to an explicit unknown time when the data time is missing', () => {
    const wrapper = mountBanner({ pendingUpdates: 2 });

    expect(wrapper.get('[data-testid="stat-board-freshness-banner"]').text()).toContain('数据截至 未知时刻');
  });
});
