import type { OptionItemResponse } from '../types/api';
import type { DataScopeOption, DataScopeProvider } from '../types/data-scope';

export const SYSTEM_TEST_PHASE_SCOPE_PROVIDER: DataScopeProvider = {
  id: 'system-test-phase',
  label: '测试阶段',
  queryKey: 'testingPhase',
  mode: 'single-select',
  placeholder: '全部测试阶段',
  emptyLabel: '全部测试阶段',
  defaultStrategy: 'empty',
  clearable: true,
  compact: true,
  summaryPrefix: '当前测试阶段',
};

export const INTEGRATION_PHASE_SCOPE_PROVIDER: DataScopeProvider = {
  id: 'integration-phase',
  label: '集成阶段',
  queryKey: 'testingPhase',
  mode: 'single-select',
  placeholder: '选择测试阶段',
  defaultStrategy: 'first-available',
  clearable: false,
  compact: true,
  summaryPrefix: '当前集成阶段',
};

export const CUSTOMER_MILESTONE_SCOPE_PROVIDER: DataScopeProvider = {
  id: 'customer-milestone',
  label: '里程碑',
  queryKey: 'milestoneTitle',
  mode: 'single-select',
  placeholder: '全部里程碑',
  emptyLabel: '全部里程碑',
  defaultStrategy: 'empty',
  clearable: true,
  compact: true,
  summaryPrefix: '当前里程碑',
};

export const CODE_REVIEW_SOURCE_SCOPE_PROVIDER: DataScopeProvider = {
  id: 'code-review-source',
  label: '数据源',
  queryKey: 'source',
  mode: 'segmented',
  defaultStrategy: 'first-available',
  clearable: false,
  compact: true,
  summaryPrefix: '当前数据源',
};

export function buildScopeOptions(
  values: Array<OptionItemResponse | string>,
  emptyLabel?: string,
): DataScopeOption[] {
  const options = values
    .map((item) =>
      typeof item === 'string'
        ? { label: item, value: item }
        : { label: item.label ?? item.value, value: item.value },
    )
    .filter((item) => item.value !== '');
  if (!emptyLabel) {
    return options;
  }
  return [{ label: emptyLabel, value: '' }, ...options];
}
