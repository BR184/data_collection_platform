import { shallowMount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import type { BiPageResponse, BiTestQualityPageData } from '../data/types';
import TestQualityStageContent from './TestQualityStageContent.vue';

describe('TestQualityStageContent download identity', () => {
  it('uses a distinct registered page and chart identity for unit and integration test pages', () => {
    for (const pageKey of ['unit-test', 'integration-test'] as const) {
      const response: BiPageResponse<BiTestQualityPageData> = {
        pageKey, status: 'READY', sourceVersion: `${pageKey}-v1`, snapshotId: `${pageKey}-v1`,
        ruleVersion: 'bi-test-quality-v1', generatedAt: '2026-08-05T00:00:00Z',
        sections: [{ key: 'test-quality', label: '测试质量', status: 'READY', message: '' }],
        traces: [],
        data: { overall: null, modules: [], functions: [] },
      };
      const wrapper = shallowMount(TestQualityStageContent, {
        props: {
          response,
          productVersionId: 11,
          stageLabel: pageKey === 'unit-test' ? '单元测试' : '集成测试',
          pageKey,
        },
      });

      expect(wrapper.findAllComponents({ name: 'BiChartPanel' }).map((panel) => [
        panel.props('downloadContext'),
        (panel.props('chart') as { templateId: string }).templateId,
      ])).toEqual([
        [{ pageKey, chartInstanceId: `${pageKey}-module-attainment`, sourceVersion: `${pageKey}-v1`,
          scope: { rangeType: 'PRODUCT_VERSION', productVersionId: 11 } }, 'test-quality-attainment'],
        [{ pageKey, chartInstanceId: `${pageKey}-function-attainment`, sourceVersion: `${pageKey}-v1`,
          scope: { rangeType: 'PRODUCT_VERSION', productVersionId: 11 } }, 'test-quality-attainment'],
      ]);
      wrapper.unmount();
    }
  });
});
