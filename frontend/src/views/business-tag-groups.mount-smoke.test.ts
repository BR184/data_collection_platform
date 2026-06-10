import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { describe, expect, it, vi } from 'vitest';
import BusinessTagGroupsView from './BusinessTagGroupsView.vue';

function jsonResponse(data: unknown) {
  return Promise.resolve({
    ok: true,
    text: () => Promise.resolve(JSON.stringify({ success: true, data })),
  } as Response);
}

describe('BusinessTagGroupsView mount smoke', () => {
  it('loads saved business tag groups in system settings', async () => {
    const schemaHash = '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef';
    const fetchSpy = vi.fn((url: string) => {
      if (url.includes('/api/business-tag-groups')) {
        return jsonResponse([
          {
            id: 1,
            tagGroupName: '领导',
            ownerUserId: 'admin',
            visibility: 'TEAM',
            entityType: 'issue',
            scenarioKey: 'all_tables',
            scopeKey: 'people_fields',
            dslJson:
              '{"logic":"AND","conditions":[{"fieldKey":"module","fieldLabel":"模块","operator":"IN","values":["草图"]}]}',
            dslHash: 'hash-v1',
            tagSchemaHash: 'schema-v1',
            sourceDataWatermarkAtSave: '2026-06-09T10:00:00',
            lastUsedAt: null,
            createdAt: '2026-06-09T10:00:00',
            updatedAt: '2026-06-09T10:00:00',
          },
        ]);
      }
      if (url.includes('/api/semantic-tag-groups/static')) {
        return jsonResponse({
          entityType: 'issue',
          schemaHash,
          groups: [
            {
              domain: 'issue',
              groupKey: 'module',
              label: '模块',
              sourceMode: 'DYNAMIC',
              rulePolicyKey: 'legacy_module_policy',
              selectionMode: 'MULTIPLE',
              matchStrategyName: 'EXACT',
              enabled: true,
              sortOrder: 10,
              values: [
                {
                  valueKey: 'sketch',
                  label: '草图',
                  valueType: 'STRING',
                  canonicalValue: '草图',
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

    const wrapper = mount(BusinessTagGroupsView, {
      attachTo: document.body,
      global: { plugins: [ElementPlus] },
    });

    await flushPromises();

    expect(fetchSpy).toHaveBeenCalledWith(
      '/api/business-tag-groups?ownerUserId=admin',
      expect.any(Object),
    );
    expect(wrapper.text()).toContain('业务标签组');
    expect(wrapper.text()).toContain('新建业务标签组');
    expect(wrapper.text()).toContain('领导');
    expect(wrapper.text()).toContain('团队');
    expect(wrapper.text()).toContain('议题类表格');
    expect(wrapper.text()).toContain('人员');
    expect(wrapper.text()).not.toContain('选择适用页面');
    expect(wrapper.text()).not.toContain('选择字段范围');
    expect(wrapper.text()).not.toContain('schemaHash');
    expect(wrapper.text()).not.toContain('保存 DSL');
    expect(fetchSpy).toHaveBeenCalledWith(
      '/api/semantic-tag-groups/static?entityType=issue',
      expect.any(Object),
    );
    expect((wrapper.vm as unknown as { semanticCatalog?: { schemaHash: string } }).semanticCatalog?.schemaHash)
      .toBe(schemaHash);
    expect(wrapper.text()).not.toContain('新建语义标签组');

    wrapper.unmount();
    vi.unstubAllGlobals();
  });
});
