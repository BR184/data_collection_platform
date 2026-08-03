import { describe, expect, it } from 'vitest';
import {
  buildRuleConfig,
  buildLabelGroupSaveRequest,
  buildChildGroupExpandedPreview,
  buildMemberPreview,
  createEmptyLabelGroupForm,
  createLabelGroupForm,
  inferValueTypeFromMembers,
  unavailableMemberCount,
  validateDynamicRuleForm,
  validateLabelGroupForm,
  valueTypeLabel,
} from './label-group-settings';

describe('label group settings helpers', () => {
  it('builds create and edit form state without a required dimension', () => {
    expect(createEmptyLabelGroupForm()).toEqual({
      id: null,
      name: '',
      systemDefault: false,
      groupType: 'STATIC',
      applicableScope: 'SAME_TYPE',
      sourceFieldKey: '',
      description: '',
      enabled: true,
      members: [],
      childGroupIds: [],
      dynamicRule: {
        outputSourceKey: '',
        outputFieldKey: '',
        distinct: true,
        filterGroup: expect.objectContaining({ logic: 'AND', conditions: [], groups: [] }),
        relations: [],
        groupBy: [],
        aggregations: [],
        havingGroup: expect.objectContaining({ logic: 'AND', conditions: [], groups: [] }),
        sort: [],
        limit: 200,
      },
    });

    const form = createLabelGroupForm({
      id: 8,
      name: '领导',
      valueType: 'STRING',
      groupType: 'STATIC',
      description: '管理层成员',
      enabled: true,
      memberCount: 2,
      members: [
        { id: 1, value: '张三', label: '张三', currentAvailable: true },
        { id: 2, value: '李四', label: '李四', currentAvailable: false },
      ],
      childGroups: [{ id: 3, name: '基础人员', groupType: 'STATIC', valueType: 'STRING', enabled: true }],
    });

    expect(form.name).toBe('领导');
    expect(form.groupType).toBe('STATIC');
    expect(form.childGroupIds).toEqual([3]);
    expect(form.members).toHaveLength(2);
  });

  it('validates the minimum static group form and builds save payload', () => {
    const empty = createEmptyLabelGroupForm();
    expect(validateLabelGroupForm(empty)).toBe('请输入标签组名称');

    const form = {
      ...empty,
      name: ' 核心人员 ',
      description: ' 常用人员 ',
      members: [{ value: '张三', label: '张三' }],
    };

    expect(validateLabelGroupForm(form)).toBe('');
    expect(buildLabelGroupSaveRequest(form)).toEqual({
      name: '核心人员',
      groupType: 'STATIC',
      applicableScope: 'SAME_TYPE',
      sourceFieldKey: null,
      description: '常用人员',
      enabled: true,
      members: [{ value: '张三', label: '张三' }],
      childGroupIds: [],
      dynamicRule: null,
    });
  });

  it('builds composite save payload from child group ids', () => {
    const form = {
      ...createEmptyLabelGroupForm(),
      name: '重点关注人员',
      groupType: 'COMPOSITE' as const,
      members: [{ value: '不会保存', label: '不会保存' }],
      childGroupIds: [1, 2],
    };

    expect(validateLabelGroupForm(form)).toBe('');
    expect(buildLabelGroupSaveRequest(form)).toMatchObject({
      name: '重点关注人员',
      groupType: 'COMPOSITE',
      members: [],
      childGroupIds: [1, 2],
      dynamicRule: null,
    });
  });

  it('builds dynamic save payload without asking for value type', () => {
    const form = {
      ...createEmptyLabelGroupForm(),
      name: '最近活跃处理人',
      groupType: 'DYNAMIC' as const,
      members: [{ value: '不应手动提交', label: '不应手动提交' }],
      dynamicRule: {
        ...createEmptyLabelGroupForm().dynamicRule,
        outputSourceKey: 'issue_fact',
        outputFieldKey: 'assigneeName',
        filterGroup: {
          ...createEmptyLabelGroupForm().dynamicRule.filterGroup,
          conditions: [{ id: 'condition-1', sourceKey: 'issue_fact', fieldKey: 'updatedAt', aggregateKey: '', operator: 'lastDays', value: '30', secondValue: '', valuesText: '' }],
        },
      },
    };

    expect(validateLabelGroupForm(form)).toBe('');
    expect(buildLabelGroupSaveRequest(form)).toMatchObject({
      name: '最近活跃处理人',
      groupType: 'DYNAMIC',
      members: [],
      childGroupIds: [],
      dynamicRule: {
        ruleConfig: {
          outputSourceKey: 'issue_fact',
          outputFieldKey: 'assigneeName',
          distinct: true,
          filterGroup: {
            logic: 'AND',
            conditions: [{ sourceKey: 'issue_fact', fieldKey: 'updatedAt', aggregateKey: null, operator: 'lastDays', value: '30', secondValue: null, values: [] }],
            groups: [],
          },
          filters: [],
          relations: [],
          groupBy: [],
          aggregations: [],
          havingGroup: null,
          having: [],
          sort: [],
          limit: 200,
        },
      },
    });
  });

  it('validates and builds dynamic rule builder config', () => {
    const empty = createEmptyLabelGroupForm();
    expect(validateDynamicRuleForm(empty.dynamicRule)).toBe('请选择结果来源');

    empty.dynamicRule.outputSourceKey = 'review_records';
    expect(validateDynamicRuleForm(empty.dynamicRule)).toBe('请选择输出字段');

    empty.dynamicRule.outputFieldKey = 'reviewOwner';
    empty.dynamicRule.relations.push({
      leftSourceKey: 'review_records',
      leftFieldKey: 'reviewOwner',
      rightSourceKey: 'merge_request_fact',
      rightFieldKey: 'ownerName',
      matchOperator: 'eq',
      normalizer: 'TRIM_LOWER',
    });
    empty.dynamicRule.aggregations.push({
      key: 'commit_count',
      sourceKey: 'merge_request_fact',
      fieldKey: 'mergeRequestIid',
      function: 'count',
      label: '提交数',
    });

    expect(buildRuleConfig(empty.dynamicRule)).toMatchObject({
      outputSourceKey: 'review_records',
      outputFieldKey: 'reviewOwner',
      aggregations: [{ key: 'commit_count', sourceKey: 'merge_request_fact', fieldKey: 'mergeRequestIid', function: 'count', label: '提交数' }],
      relations: [{ leftSourceKey: 'review_records', leftFieldKey: 'reviewOwner', rightSourceKey: 'merge_request_fact', rightFieldKey: 'ownerName' }],
    });
  });

  it('summarizes expanded members and unavailable saved values', () => {
    const group = {
      id: 1,
      name: '测试组',
      valueType: 'STRING',
      groupType: 'STATIC' as const,
      enabled: true,
      memberCount: 5,
      members: [
        { value: 'A', label: 'A' },
        { value: 'B', label: 'B', currentAvailable: false },
      ],
      expandedPreview: [
        { value: 'A', label: 'A' },
        { value: 'B', label: 'B' },
        { value: 'C', label: 'C' },
        { value: 'D', label: 'D' },
        { value: 'E', label: 'E' },
      ],
    };

    expect(buildMemberPreview(group)).toBe('A、B、C、D 等 5 个');
    expect(unavailableMemberCount(group.members)).toBe(1);
  });

  it('builds child group expanded preview with deduped values', () => {
    const preview = buildChildGroupExpandedPreview([
      {
        id: 1,
        name: '动态处理人',
        valueType: 'STRING',
        groupType: 'DYNAMIC',
        enabled: true,
        memberCount: 2,
        members: [],
        expandedPreview: [
          { value: '张三', label: '张三' },
          { value: '李四', label: '李四' },
        ],
      },
      {
        id: 2,
        name: '静态关注人',
        valueType: 'STRING',
        groupType: 'STATIC',
        enabled: true,
        memberCount: 2,
        members: [
          { value: '李四', label: '李四' },
          { value: '王五', label: '王五' },
        ],
      },
    ], [1, 2], 2);

    expect(preview.total).toBe(3);
    expect(preview.hiddenCount).toBe(1);
    expect(preview.members.map((member) => member.value)).toEqual(['张三', '李四']);
    expect(preview.overLimit).toBe(false);
  });

  it('infers value type from the first member and detects mixed values', () => {
    expect(inferValueTypeFromMembers([{ value: '张三', label: '张三' }])).toBe('STRING');
    expect(inferValueTypeFromMembers([{ value: '100', label: '100' }])).toBe('NUMBER');
    expect(inferValueTypeFromMembers([{ value: '2026-06-10', label: '2026-06-10' }])).toBe('DATE');
    expect(inferValueTypeFromMembers([{ value: '张三', label: '张三' }, { value: '100', label: '100' }])).toBe('MIXED');
    expect(valueTypeLabel('STRING')).toBe('字符串');
  });
});
