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
      props: { response, productVersionId: 11, productVersionName: 'v1.0' },
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

    // Verify description on workloadPanel and severityPanel
    expect(workloadPanel?.props('description')).toContain('统计各处理人员被指派的缺陷总数');
    expect(severityPanel?.props('description')).toContain('统计系统测试期间一级缺陷');
  });

  it('renders overview metrics before quality targets in the top metric bar', () => {
    const response: BiPageResponse<BiSystemTestPageData> = {
      pageKey: 'system-test',
      status: 'READY',
      sourceVersion: 'issue-v1',
      snapshotId: 'issue-v1',
      ruleVersion: 'bi-system-test-v3',
      generatedAt: '2026-08-05T00:00:00Z',
      sections: [],
      traces: [],
      data: {
        overview: { totalCount: 1320, fixedCount: 826, openCount: 494, fixRate: 62.58 },
        qualityTargets: [
          { key: 'level-one', label: '一级缺陷', totalCount: 10000, fixedCount: 8304, fixRate: 83.04, targetRate: 100, achieved: false, status: 'READY' },
          { key: 'p1', label: 'P1', totalCount: 10000, fixedCount: 6929, fixRate: 69.29, targetRate: 90, achieved: false, status: 'READY' },
          { key: 'p2', label: 'P2', totalCount: 10000, fixedCount: 4960, fixRate: 49.60, targetRate: 80, achieved: false, status: 'READY' },
        ],
        rounds: [],
        severity: { levelOneCount: 0, levelTwoCount: 0, levelThreeCount: 0 },
        modules: [],
        causeCategories: [],
        causeSubcategories: [],
        delays: [],
        developers: [],
      },
    };

    const wrapper = shallowMount(SystemTestStageContent, {
      props: { response, productVersionId: 11, productVersionName: 'v1.0' },
    });
    const labels = wrapper.findAll('.bi-cell-label').map((node) => node.text());
    expect(labels).toEqual([
      '累计发现缺陷数',
      '已修复缺陷数',
      '当前未修复数',
      '整体修复率',
      '一级缺陷修复率（严重程度）',
      'P1修复率（优先级）',
      'P2修复率（优先级）',
    ]);
  });

  it('sorts test rounds in natural ascending order and sinks 0% module repair rate to bottom', () => {
    const response: BiPageResponse<BiSystemTestPageData> = {
      pageKey: 'system-test',
      status: 'READY',
      sourceVersion: 'issue-v2',
      snapshotId: 'issue-v2',
      ruleVersion: 'bi-system-test-v3',
      generatedAt: '2026-08-05T00:00:00Z',
      sections: [],
      traces: [],
      data: {
        overview: { totalCount: 20, fixedCount: 18, openCount: 2, fixRate: 90.0 },
        qualityTargets: [],
        rounds: [
          { roundId: '2', roundName: 'CC2026R3第二轮系统测试', roundOrder: 2, levelOneCount: 1, levelTwoCount: 2, levelThreeCount: 0, submittedCount: 3, closedCount: 3, openCount: 0, closeRate: 100.0 },
          { roundId: '3', roundName: 'CC2026R3回归测试', roundOrder: 3, levelOneCount: 0, levelTwoCount: 1, levelThreeCount: 0, submittedCount: 1, closedCount: 1, openCount: 0, closeRate: 100.0 },
          { roundId: '1', roundName: 'CC2026R3第一轮系统测试', roundOrder: 1, levelOneCount: 2, levelTwoCount: 5, levelThreeCount: 1, submittedCount: 8, closedCount: 7, openCount: 1, closeRate: 87.5 },
        ],
        severity: { levelOneCount: 3, levelTwoCount: 8, levelThreeCount: 1 },
        modules: [
          { module: { sourceValue: 'M_UNFIXED', displayName: '模块全部未修复', identified: true }, fixRate: 0, fixedCount: 0, openCount: 12, totalCount: 12, levelOneFixRate: null, p1FixRate: 0, p2FixRate: 0, levelOneCount: 2, levelTwoCount: 6, levelThreeCount: 4 },
          { module: { sourceValue: 'M_FAIL', displayName: '模块未达标80%', identified: true }, fixRate: 80, fixedCount: 8, openCount: 2, totalCount: 10, levelOneFixRate: 100, p1FixRate: 80, p2FixRate: 75, levelOneCount: 1, levelTwoCount: 5, levelThreeCount: 4 },
          { module: { sourceValue: 'M_PASS', displayName: '模块已达标96%', identified: true }, fixRate: 96, fixedCount: 24, openCount: 1, totalCount: 25, levelOneFixRate: 100, p1FixRate: 95, p2FixRate: 90, levelOneCount: 2, levelTwoCount: 15, levelThreeCount: 8 },
        ],
        causeCategories: [],
        causeSubcategories: [],
        delays: [],
        developers: [],
      },
    };

    const wrapper = shallowMount(SystemTestStageContent, {
      props: { response, productVersionId: 11, productVersionName: 'v1.0' },
    });

    const roundPanel = wrapper.findAllComponents({ name: 'BiChartPanel' })
      .find((panel) => panel.props('title') === '系统测试各轮次缺陷修复情况');
    expect(roundPanel).toBeDefined();
    const roundData = roundPanel?.props('data') as Array<{ name: string; order?: number }>;
    expect(roundData.map((r) => r.name)).toEqual([
      'CC2026R3第一轮系统测试',
      'CC2026R3第二轮系统测试',
      'CC2026R3回归测试',
    ]);

    const repairPanel = wrapper.findAllComponents({ name: 'BiChartPanel' })
      .find((panel) => panel.props('title') === '各模块系统测试修复率达成情况');
    expect(repairPanel).toBeDefined();
    const repairData = repairPanel?.props('data') as Array<{ name: string; fixRate: number | null }>;
    // 异常优先：未达标组在前并按修复率升序，故“有缺陷且全部未修复”的 0% 模块作为最高风险排最前；
    // 后端只为存在缺陷的模块产出该列表，因此不能再把 0% 当作“无缺陷”沉底。
    expect(repairData.map((r) => r.name)).toEqual([
      '模块全部未修复',
      '模块未达标80%',
      '模块已达标96%',
    ]);
  });
});
