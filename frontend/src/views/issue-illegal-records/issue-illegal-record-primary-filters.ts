import type { OptionItemResponse } from '../../types/api';
import type { RecordTableFilterField } from '../../types/record-table';

/** 非法记录页快捷筛选的业务域差异配置。 */
export interface IssueIllegalPrimaryFilterConfig {
  scopeKey: string;
  scopeLabel: string;
  scopeOptions: OptionItemResponse[];
  moduleOptions: OptionItemResponse[];
  illegalReasonOptions: OptionItemResponse[];
  assigneeOptions: OptionItemResponse[];
  severityOptions: OptionItemResponse[];
  issueStateOptions: OptionItemResponse[];
  bugStatusOptions: OptionItemResponse[];
  priorityOptions?: OptionItemResponse[];
  bugStatusWidth: number;
}

function selectFilter(
  key: string,
  label: string,
  options: OptionItemResponse[],
  placeholder: string,
  width: number,
): RecordTableFilterField {
  return {
    key,
    label,
    type: 'select',
    placeholder,
    width,
    options,
  };
}

/**
 * 构建系统测试和客户问题非法记录页共用的快捷筛选字段。
 *
 * 首个范围字段和客户问题的优先级字段由调用方显式提供，避免隐藏两个业务域的真实差异。
 */
export function buildIssueIllegalRecordPrimaryFilters(
  config: IssueIllegalPrimaryFilterConfig,
): RecordTableFilterField[] {
  const filters: RecordTableFilterField[] = [
    {
      key: config.scopeKey,
      label: config.scopeLabel,
      type: 'select',
      defaultStrategy: 'first-available',
      clearable: false,
      width: 240,
      options: config.scopeOptions,
    },
    selectFilter('moduleName', '模块名', config.moduleOptions, '全部模块', 168),
    selectFilter('illegalReason', '非法类型', config.illegalReasonOptions, '全部非法类型', 184),
    {
      key: 'issueIid',
      label: '议题编号',
      type: 'input',
      placeholder: '输入议题编号',
      width: 136,
    },
    {
      key: 'title',
      label: '议题标题',
      type: 'input',
      placeholder: '输入标题关键字',
      width: 184,
    },
    selectFilter('assigneeName', '议题处理人', config.assigneeOptions, '全部处理人', 156),
    selectFilter('severityLevel', '严重程度', config.severityOptions, '全部严重程度', 156),
  ];

  if (config.priorityOptions) {
    filters.push(selectFilter('priorityLevel', '缺陷优先级', config.priorityOptions, '全部优先级', 156));
  }

  filters.push(
    selectFilter('issueState', '议题状态', config.issueStateOptions, '全部状态', 144),
    selectFilter('bugStatus', '测试状态', config.bugStatusOptions, '全部测试状态', config.bugStatusWidth),
  );
  return filters;
}
