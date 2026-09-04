import { beforeEach, describe, expect, it, vi } from 'vitest';
import { labelGroupsApi } from './label-groups-api';

vi.mock('./request', () => ({
  request: vi.fn(() => Promise.resolve({})),
}));

import { request } from './request';

describe('labelGroupsApi', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('queries dimensions and dimension values', () => {
    labelGroupsApi.listLabelDimensions();
    expect(request).toHaveBeenCalledWith('/api/label-groups/dimensions');

    labelGroupsApi.listLabelDimensionValues('module', {
      pageKey: 'review-data-home',
      sourceInstanceId: 'cc',
      keyword: '草',
      page: 2,
      size: 50,
    });

    const valuesUrl = String(vi.mocked(request).mock.calls.at(-1)?.[0]);
    expect(valuesUrl).toContain('/api/label-groups/dimensions/module/values?');
    expect(valuesUrl).toContain('pageKey=review-data-home');
    expect(valuesUrl).toContain('sourceInstanceId=cc');
    expect(valuesUrl).toContain('keyword=%E8%8D%89');
    expect(valuesUrl).toContain('page=2');
    expect(valuesUrl).toContain('size=50');
  });

  it('loads dynamic rule builder sources and relations', () => {
    labelGroupsApi.listDynamicRuleSources();

    expect(request).toHaveBeenCalledWith('/api/label-groups/dynamic-rule-sources');

    labelGroupsApi.listDynamicRuleRelations();

    expect(request).toHaveBeenCalledWith('/api/label-groups/dynamic-rule-relations');
  });

  it('previews dynamic rule members through builder payload', () => {
    labelGroupsApi.previewDynamicRule({
      ruleConfig: {
        outputSourceKey: 'issue_fact',
        outputFieldKey: 'assigneeName',
        distinct: true,
        filters: [{ sourceKey: 'issue_fact', fieldKey: 'updatedAt', operator: 'lastDays', value: '30' }],
      },
    });

    expect(request).toHaveBeenCalledWith('/api/label-groups/dynamic-rule-preview', {
      method: 'POST',
      body: JSON.stringify({
        ruleConfig: {
          outputSourceKey: 'issue_fact',
          outputFieldKey: 'assigneeName',
          distinct: true,
          filters: [{ sourceKey: 'issue_fact', fieldKey: 'updatedAt', operator: 'lastDays', value: '30' }],
        },
      }),
    });
  });

  it('creates updates and deletes groups with Chinese errors passed through by request', async () => {
    labelGroupsApi.createLabelGroup({
      name: '核心人员',
      groupType: 'STATIC',
      members: [{ value: '张三', label: '张三' }],
      childGroupIds: [],
    });

    expect(request).toHaveBeenCalledWith('/api/label-groups', {
      method: 'POST',
      body: JSON.stringify({
        name: '核心人员',
        groupType: 'STATIC',
        members: [{ value: '张三', label: '张三' }],
        childGroupIds: [],
      }),
    });

    labelGroupsApi.updateLabelGroup(3, {
      name: '核心人员',
      groupType: 'STATIC',
      enabled: true,
      members: [{ value: '李四', label: '李四' }],
      childGroupIds: [],
    });
    expect(request).toHaveBeenCalledWith('/api/label-groups/3', expect.objectContaining({ method: 'PUT' }));

    labelGroupsApi.deleteLabelGroup(3);
    expect(request).toHaveBeenCalledWith('/api/label-groups/3', { method: 'DELETE' });

    vi.mocked(request).mockRejectedValueOnce(new Error('标签组成员不能为空'));
    await expect(labelGroupsApi.listLabelGroups()).rejects.toThrow('标签组成员不能为空');
  });

  it('loads groups compatible pages and expansion result', () => {
    labelGroupsApi.listLabelGroups({ valueType: 'STRING', keyword: '核心', enabled: true });
    expect(request).toHaveBeenCalledWith('/api/label-groups?valueType=STRING&keyword=%E6%A0%B8%E5%BF%83&enabled=true');

    labelGroupsApi.listLabelGroupCompatiblePages('person');
    expect(request).toHaveBeenCalledWith('/api/label-groups/dimensions/person/compatible-pages');

    labelGroupsApi.expandLabelGroup(1, { valueType: 'STRING', fieldKey: 'moduleName', pageKey: 'review-data-home' });
    expect(request).toHaveBeenCalledWith('/api/label-groups/1/expand?valueType=STRING&fieldKey=moduleName&pageKey=review-data-home', {
      method: 'POST',
    });
  });
});
