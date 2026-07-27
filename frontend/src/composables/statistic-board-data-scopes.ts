import { computed, ref, watch, type Ref } from 'vue';
import { api } from '../api';
import { authState } from './auth-state';
import type { DataScopeOption, DataScopeProvider } from '../types/data-scope';
import type { IssueScopeDimension, IssueScopeGroupResponse } from '../types/api';

const CROWN_CAD_PROJECT_ID = 9;
const CC_PRODUCT_PROJECT_ID = 325;

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

const CUSTOMER_ISSUE_MILESTONE_SCOPE_PROVIDER: DataScopeProvider = {
  id: 'customer-issue-milestone',
  label: '里程碑',
  queryKey: 'milestoneTitle',
  mode: 'single-select',
  placeholder: '选择里程碑',
  defaultStrategy: 'first-available',
  clearable: false,
  compact: true,
  dropdownLayout: 'list',
  summaryPrefix: '当前里程碑',
};

export function useStatisticBoardDataScope(boardKey: Ref<string>) {
  const testingPhaseGroups = ref<IssueScopeGroupResponse[]>([]);
  const customerMilestoneOptions = ref<DataScopeOption[]>([]);
  const phaseLoading = ref(false);
  const phaseLoaded = ref(false);
  const customerLoading = ref(false);
  const customerLoaded = ref(false);
  const parentOptions = computed(() => buildParentOptions(testingPhaseGroups.value));
  const phaseTreeOptions = computed(() => buildTreeOptions(testingPhaseGroups.value));

  const config = computed<StatisticBoardDataScopeConfig | null>(() => {
    if (CUSTOMER_ISSUE_PHASE_BOARD_KEYS.has(boardKey.value)) {
      return {
        provider: CUSTOMER_ISSUE_MILESTONE_SCOPE_PROVIDER,
        options: customerMilestoneOptions,
        loading: customerLoading,
        loaded: customerLoaded,
      };
    }
    if (!SYSTEM_TEST_BOARD_KEYS.has(boardKey.value)) {
      return null;
    }
    if (boardKey.value !== 'system-test-defect-summary') {
      return {
        provider: SYSTEM_TEST_PARENT_SCOPE_PROVIDER,
        options: parentOptions,
        loading: phaseLoading,
        loaded: phaseLoaded,
      };
    }
    return {
      provider: SYSTEM_TEST_DEFECT_SUMMARY_SCOPE_PROVIDER,
      options: phaseTreeOptions,
      loading: phaseLoading,
      loaded: phaseLoaded,
    };
  });

  watch(
    [boardKey, () => authState.currentUser.authenticated],
    async ([nextBoardKey]) => {
      if (!SYSTEM_TEST_BOARD_KEYS.has(nextBoardKey) || phaseLoaded.value || phaseLoading.value) {
        return;
      }
      phaseLoading.value = true;
      try {
        testingPhaseGroups.value = await loadGroups(CROWN_CAD_PROJECT_ID, 'TESTING_PHASE');
        phaseLoaded.value = true;
      } finally {
        phaseLoading.value = false;
      }
    },
    { immediate: true },
  );

  watch(
    [boardKey, () => authState.currentUser.authenticated],
    async ([nextBoardKey]) => {
      if (!CUSTOMER_ISSUE_PHASE_BOARD_KEYS.has(nextBoardKey) || customerLoaded.value || customerLoading.value) {
        return;
      }
      customerLoading.value = true;
      try {
        customerMilestoneOptions.value = buildParentOptions(
          await loadGroups(CC_PRODUCT_PROJECT_ID, 'MILESTONE'),
        );
      } finally {
        customerLoaded.value = true;
        customerLoading.value = false;
      }
    },
    { immediate: true },
  );

  return config;
}

async function loadGroups(projectId: number, dimension: IssueScopeDimension) {
  const catalogs = await api.getIssueScopeCatalogs();
  const catalog = catalogs.find((item) => item.projectId === projectId && item.dimension === dimension && item.enabled);
  return catalog ? api.getIssueScopeGroups(catalog.id, { enabled: true }) : [];
}

function buildParentOptions(groups: IssueScopeGroupResponse[]): DataScopeOption[] {
  return groups
    .filter((group) => normalizeText(group.businessKey))
    .map((group) => ({ label: normalizeText(group.displayName), value: normalizeText(group.businessKey) }));
}

function buildTreeOptions(groups: IssueScopeGroupResponse[]): DataScopeOption[] {
  return groups
    .map((group) => {
      const businessKey = normalizeText(group.businessKey);
      if (!businessKey) {
        return null;
      }
      const children = (group.members ?? [])
        .filter((member) => member.enabled && normalizeText(member.sourceValue))
        .map((member) => ({ label: normalizeText(member.displayName), value: normalizeText(member.sourceValue) }));
      return {
        label: normalizeText(group.displayName),
        value: businessKey,
        ...(children.length > 0 ? { children } : {}),
      };
    })
    .filter((option): option is DataScopeOption => option != null);
}

function normalizeText(value: string | null | undefined) {
  return String(value ?? '').trim();
}
