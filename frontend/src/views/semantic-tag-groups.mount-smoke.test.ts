import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { describe, expect, it, vi } from 'vitest';
import SemanticTagGroupsView from './SemanticTagGroupsView.vue';

function jsonResponse(data: unknown) {
  return Promise.resolve({
    ok: true,
    text: () => Promise.resolve(JSON.stringify({ success: true, data })),
  } as Response);
}

describe('SemanticTagGroupsView mount smoke', () => {
  it('loads static semantic tag group templates', async () => {
    const fetchSpy = vi.fn((url: string) => {
      if (url.includes('/api/semantic-tag-groups/static')) {
        return jsonResponse({
          entityType: 'issue',
          schemaHash: '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
          groups: [
            {
              domain: 'issue',
              groupKey: 'severity_level',
              label: '严重程度',
              sourceMode: 'STATIC',
              rulePolicyKey: 'issue_severity_policy',
              selectionMode: 'MULTIPLE',
              matchStrategyName: 'EXACT',
              enabled: true,
              sortOrder: 10,
              values: [
                {
                  valueKey: 'LEVEL1',
                  label: '一级缺陷',
                  valueType: 'STRING',
                  canonicalValue: 'LEVEL1',
                  enabled: true,
                  sortOrder: 10,
                },
              ],
            },
            {
              domain: 'issue',
              groupKey: 'delay_cause',
              label: '延期原因',
              sourceMode: 'STATIC',
              rulePolicyKey: 'system_test_delay_cause_policy',
              selectionMode: 'MULTIPLE',
              matchStrategyName: 'EXACT',
              enabled: true,
              sortOrder: 10,
              values: [
                {
                  valueKey: 'TECHNICAL_BLOCKER',
                  label: '技术卡点',
                  valueType: 'STRING',
                  canonicalValue: 'TECHNICAL_BLOCKER',
                  enabled: true,
                  sortOrder: 10,
                },
              ],
            },
          ],
        });
      }
      return jsonResponse({});
    });
    vi.stubGlobal('fetch', fetchSpy);

    const wrapper = mount(SemanticTagGroupsView, {
      attachTo: document.body,
      global: { plugins: [ElementPlus] },
    });

    await flushPromises();

    expect(fetchSpy).toHaveBeenCalledWith(
      '/api/semantic-tag-groups/static?entityType=issue',
      expect.any(Object),
    );
    expect(wrapper.text()).toContain('语义标签组');
    expect(wrapper.text()).toContain('严重程度');
    expect(wrapper.text()).toContain('一级缺陷');
    expect(wrapper.text()).toContain('延期原因');
    expect(wrapper.text()).toContain('技术卡点');
    expect(wrapper.text()).not.toContain('severity_level');
    expect(wrapper.text()).not.toContain('delay_cause');
    expect(wrapper.text()).not.toContain('0123456789ab');

    wrapper.unmount();
    vi.unstubAllGlobals();
  });
});
