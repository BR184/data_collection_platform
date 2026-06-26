import { computed, ref, watch, type Ref } from 'vue';
import { api } from '../api';
import { authState } from './auth-state';
import type { DataScopeOption, DataScopeProvider } from '../types/data-scope';
import type { TestingPhaseGroupResponse } from '../types/api';

const LEGACY_CROWN_CAD_PROJECT_ID = 9;

export interface StatisticBoardDataScopeConfig {
  provider: DataScopeProvider;
  options: Ref<DataScopeOption[]>;
  loading: Ref<boolean>;
  loaded: Ref<boolean>;
}

const SYSTEM_TEST_BOARD_KEYS = new Set([
  'system-test-defect-summary',
  'system-test-delay-analysis',
  'system-test-defect-cause',
  'system-test-phase-statistics',
]);

const CUSTOMER_ISSUE_PHASE_BOARD_KEYS = new Set([
  'customer-issue-defect-summary',
  'customer-issue-defect-cause',
  'customer-issue-delay-issues',
  'customer-issue-response-efficiency',
  'customer-issue-by-function',
]);

const SYSTEM_TEST_DEFECT_SUMMARY_SCOPE_PROVIDER: DataScopeProvider = {
  id: 'system-test-defect-summary-phase',
  label: '测试阶段',
  queryKey: 'testingPhase',
  mode: 'tree-single',
  placeholder: '选择测试阶段',
  defaultStrategy: 'first-available',
  clearable: false,
  compact: true,
  summaryPrefix: '当前测试阶段',
};

const SYSTEM_TEST_PARENT_SCOPE_PROVIDER: DataScopeProvider = {
  id: 'system-test-parent-phase',
  label: '测试阶段',
  queryKey: 'testingPhase',
  mode: 'single-select',
  placeholder: '选择测试阶段',
  defaultStrategy: 'first-available',
  clearable: false,
  compact: true,
  dropdownLayout: 'list',
  summaryPrefix: '当前测试阶段',
};

const CUSTOMER_ISSUE_PHASE_SCOPE_PROVIDER: DataScopeProvider = {
  id: 'customer-issue-phase',
  label: '测试阶段',
  queryKey: 'testingPhase',
  mode: 'single-select',
  placeholder: '选择测试阶段',
  defaultStrategy: 'first-available',
  clearable: false,
  compact: true,
  dropdownLayout: 'list',
  summaryPrefix: '当前测试阶段',
};

export function useStatisticBoardDataScope(boardKey: Ref<string>) {
  const testingPhaseGroups = ref<TestingPhaseGroupResponse[]>([]);
  const loading = ref(false);
  const loaded = ref(false);
  const parentOptions = computed(() => buildParentOptions(testingPhaseGroups.value));
  const phaseTreeOptions = computed(() => buildTreeOptions(testingPhaseGroups.value));

  const config = computed<StatisticBoardDataScopeConfig | null>(() => {
    if (CUSTOMER_ISSUE_PHASE_BOARD_KEYS.has(boardKey.value)) {
      return {
        provider: CUSTOMER_ISSUE_PHASE_SCOPE_PROVIDER,
        options: parentOptions,
        loading,
        loaded,
      };
    }
    if (!SYSTEM_TEST_BOARD_KEYS.has(boardKey.value)) {
      return null;
    }
    if (boardKey.value !== 'system-test-defect-summary') {
      return {
        provider: SYSTEM_TEST_PARENT_SCOPE_PROVIDER,
        options: parentOptions,
        loading,
        loaded,
      };
    }
    return {
      provider: SYSTEM_TEST_DEFECT_SUMMARY_SCOPE_PROVIDER,
      options: phaseTreeOptions,
      loading,
      loaded,
    };
  });

  watch(
    [boardKey, () => authState.currentUser.authenticated],
    async ([nextBoardKey]) => {
      if (
        (!SYSTEM_TEST_BOARD_KEYS.has(nextBoardKey) && !CUSTOMER_ISSUE_PHASE_BOARD_KEYS.has(nextBoardKey))
        || loaded.value
        || loading.value
      ) {
        return;
      }
      loading.value = true;
      try {
        testingPhaseGroups.value = await api.getTestingPhaseGroups({
          projectId: LEGACY_CROWN_CAD_PROJECT_ID,
          enabled: true,
        });
        loaded.value = true;
      } finally {
        loading.value = false;
      }
    },
    { immediate: true },
  );

  return config;
}

function buildParentOptions(groups: TestingPhaseGroupResponse[]): DataScopeOption[] {
  return groups
    .map((group) => normalizeText(group.name))
    .filter(Boolean)
    .map((parent) => ({
      label: parent,
      value: parent,
    }));
}

function buildTreeOptions(groups: TestingPhaseGroupResponse[]): DataScopeOption[] {
  return groups
    .map((group) => {
      const parent = normalizeText(group.name);
      if (!parent) {
        return null;
      }
      const children = (group.children ?? [])
        .map((child) => normalizeText(child.testingPhase))
        .filter(Boolean)
        .map((phase) => ({
          label: phase,
          value: phase,
        }));
      return {
        label: parent,
        value: parent,
        ...(children.length > 0 ? { children } : {}),
      };
    })
    .filter((option): option is DataScopeOption => option != null);
}

function normalizeText(value: string | null | undefined) {
  return String(value ?? '').trim();
}
