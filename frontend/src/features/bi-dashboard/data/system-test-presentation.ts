import type { DefectCauseBreakdownData, DefectCauseBreakdownItem, DelayHeatmapData } from '../charts/chart-data';
import { BI_PALETTE } from '../charts/palette';
import type { BiSystemTestPageData } from './types';

interface CauseGroupDefinition {
  id: string;
  name: string;
  color: string;
  members: ReadonlyArray<{ id: string; name: string }>;
}

const CAUSE_GROUPS: readonly CauseGroupDefinition[] = [
  {
    id: 'requirement',
    name: '需求问题',
    color: BI_PALETTE.blue,
    members: [
      { id: 'demand_misunderstand', name: '新增理解偏差' },
      { id: 'missing_requirement', name: '需求遗漏' },
      { id: 'add_demand_2', name: '新增需求' },
      { id: 'demand_change_not_sync', name: '需求变更未同步' },
    ],
  },
  {
    id: 'design',
    name: '设计问题',
    color: '#F2A66F',
    members: [
      { id: 'design_forget', name: '功能设计遗漏' },
      { id: 'design_scheme', name: '设计方案不合理' },
      { id: 'incomplete', name: '场景考虑不全' },
      { id: 'prompt_message', name: '术语、提示信息不合适' },
    ],
  },
  {
    id: 'coding',
    name: '编码问题',
    color: BI_PALETTE.blue,
    members: [
      { id: 'standard_error', name: '编码规范错误' },
      { id: 'function_forget', name: '功能编码遗漏' },
      { id: 'logic_calculation_algorithm_error', name: '编码逻辑：计算与算法错误' },
      { id: 'logic_flow_control_error', name: '编码逻辑：流程控制错误' },
      { id: 'logic_data_state_process_error', name: '编码逻辑：数据与状态处理错误' },
      { id: 'logic_business_logic_error', name: '编码逻辑：业务逻辑错误' },
      { id: 'logic_integration_interface_error', name: '编码逻辑：集成与接口错误' },
    ],
  },
  {
    id: 'packaging',
    name: '打包问题',
    color: BI_PALETTE.teal,
    members: [
      { id: 'environment_config_issue', name: '环境配置问题' },
      { id: 'compilation_package_deployment_issue', name: '编译/打包/部署问题' },
    ],
  },
  {
    id: 'dependency',
    name: '依赖问题',
    color: BI_PALETTE.blue,
    members: [
      { id: 'other_thirdParty', name: '第三方库问题' },
      { id: 'algorithm_not_support', name: '算法不支持' },
      { id: 'mechanism_not_support', name: '机制不支持' },
      { id: 'precondition_data_exception', name: '前置数据异常' },
      { id: 'other_unIdentifyTask', name: '未识别的前后置任务' },
    ],
  },
  {
    id: 'precision',
    name: '精度问题',
    color: '#E99862',
    members: [
      { id: 'precision_constraint_exception', name: '精度导致约束求解异常' },
      { id: 'precision_algorithm_exception', name: '精度导致算法执行异常' },
    ],
  },
] as const;

const SEVERITIES = [
  { code: 'LEVEL1', label: '一级缺陷' },
  { code: 'LEVEL2', label: '二级缺陷' },
  { code: 'LEVEL3', label: '三级缺陷' },
] as const;

/** 将稀疏的后端原因统计映射为王老师确认的固定 24 子类展示顺序。 */
export function buildDefectCauseBreakdownData(
  source: BiSystemTestPageData['causeSubcategories'],
): DefectCauseBreakdownData {
  const sourceById = new Map(source.map((item) => [item.subcategoryId, item]));
  const items: DefectCauseBreakdownItem[] = CAUSE_GROUPS.flatMap((group) => group.members.map((member) => {
    const value = sourceById.get(member.id);
    return {
      id: member.id,
      name: member.name,
      groupId: group.id,
      groupName: group.name,
      count: value?.count ?? 0,
      sharePercent: value ? value.sharePercent : 0,
      color: group.color,
    };
  }));
  const unclassified = sourceById.get('unclassified');
  return {
    items,
    unclassifiedCount: unclassified?.count ?? 0,
    unclassifiedSharePercent: unclassified ? unclassified.sharePercent : 0,
  };
}

/** 将延期接口的稀疏聚合补成固定三级严重度矩阵；缺失组合表示确定的零计数。 */
export function buildDelayHeatmapData(source: BiSystemTestPageData['delays']): DelayHeatmapData {
  const reasons = [...new Set(source.map((item) => item.reason))];
  const counts = new Map(source.map((item) => [`${item.reason}\u0000${item.severity}`, item.count]));
  return {
    reasons,
    severities: SEVERITIES.map((item) => item.label),
    values: reasons.flatMap((reason, reasonIndex) => SEVERITIES.map((severity, severityIndex) => [
      severityIndex,
      reasonIndex,
      counts.get(`${reason}\u0000${severity.code}`) ?? 0,
    ] as [number, number, number])),
  };
}
