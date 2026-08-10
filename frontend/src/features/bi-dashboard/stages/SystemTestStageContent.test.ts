import { shallowMount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import type { BiPageResponse, BiSystemTestPageData } from '../data/types';
import SystemTestStageContent from './SystemTestStageContent.vue';

describe('SystemTestStageContent', () => {
  it('renders the current assignee workload as total and pending defects', () => {
    const response: BiPageResponse<BiSystemTestPageData> = {
      pageKey: 'system-test',
      status: 'READY',
      sourceVersion: 'issue-v1',
      snapshotId: 'issue-v1',
      ruleVersion: 'bi-system-test-v3',
      generatedAt: '2026-08-05T00:00:00Z',
      sections: [
        { key: 'developer-workload', label: '按指派人统计缺陷数', status: 'READY', message: '' },
      ],
      traces: [],
      data: {
        overview: { totalCount: 3, fixedCount: 1, openCount: 2, fixRate: 33.33 },
        qualityTargets: [],
        rounds: [],
        severity: { levelOneCount: 1, levelTwoCount: 1, levelThreeCount: 1 },
        modules: [],
        causeCategories: [],
        causeSubcategories: [],
        delays: [],
        developers: [{
          assignee: { sourceValue: '李四', displayName: '李四', identified: true },
          totalCount: 3,
          fixedCount: 1,
          openCount: 2,
        }],
      },
    };

    const wrapper = shallowMount(SystemTestStageContent, {
      props: { response, productVersionId: 11 },
    });
    const workloadPanel = wrapper.findAllComponents({ name: 'BiChartPanel' })
      .find((panel) => panel.props('title') === '按指派人统计缺陷数');

    expect(workloadPanel).toBeDefined();
    expect(workloadPanel?.props('data')).toEqual([
      { name: '李四', total: 3, open: 2, fixed: 1 },
    ]);

    const severityPanel = wrapper.findAllComponents({ name: 'BiChartPanel' })
      .find((panel) => panel.props('title') === '缺陷严重度分布');
    expect(severityPanel?.props('data')).toEqual([
      { name: '一级', value: 1, color: '#EE6666' },
      { name: '二级', value: 1, color: '#FAC858' },
      { name: '三级', value: 1, color: '#5470C6' },
    ]);

    const causePanels = wrapper.findAllComponents({ name: 'BiChartPanel' })
      .filter((panel) => String(panel.props('title')).startsWith('系统测试缺陷原因'));
    expect(causePanels).toHaveLength(2);
    expect(causePanels.map((panel) => panel.props('height'))).toEqual([432, 432]);
    expect(causePanels.every((panel) => panel.classes().includes('bi-cause-chart-panel'))).toBe(true);
  });
});
