import { describe, expect, it } from 'vitest';
import {
  CC_PRODUCT_RECORD_COLUMNS,
  DELAY_RECORD_COLUMNS,
} from './customer-issue-record-columns';

describe('customer issue record columns', () => {
  it('defines the CC_PRODUCT page and Excel column contract in default order', () => {
    expect(CC_PRODUCT_RECORD_COLUMNS.map((column) => column.label)).toEqual([
      '议题编号',
      '模块名',
      '功能名称',
      '议题标题',
      '客户',
      '议题提交人',
      '议题处理人',
      '议题指派人',
      '议题状态',
      '测试状态',
      '测试阶段',
      '严重程度',
      '缺陷优先级',
      '议题类别',
      '里程碑',
      '延期原因',
      '缺陷修复人',
      '计划解决时间',
      '计划合并版本分支',
      '提交时间',
      '缺陷滞留时长（小时）',
      '更新时间',
    ]);
    expect(CC_PRODUCT_RECORD_COLUMNS.find((column) => column.key === 'delayCause')?.type).toBe('tags');
  });

  it('keeps the existing delay page columns isolated', () => {
    expect(DELAY_RECORD_COLUMNS.map((column) => column.key)).toEqual([
      'issueIid',
      'moduleNames',
      'title',
      'authorName',
      'assigneeName',
      'issueState',
      'severityLevel',
      'priorityLevel',
      'bugStatus',
      'category',
      'milestoneTitle',
      'createdAt',
      'updatedAt',
    ]);
  });
});
