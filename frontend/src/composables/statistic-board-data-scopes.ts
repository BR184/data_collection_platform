import { computed, ref, watch, type Ref } from 'vue';
import { api } from '../api';
import { authState } from './auth-state';
import type { DataScopeOption, DataScopeProvider } from '../types/data-scope';
import type { TestingPhaseGroupResponse } from '../types/api';

const LEGACY_CROWN_CAD_PROJECT_ID = 9;
const LEGACY_CC_PRODUCT_PROJECT_ID = 325;

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
  const testingPhaseGroups = ref<TestingPhaseGroupResponse[]>([]);
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
        testingPhaseGroups.value = await api.getTestingPhaseGroups({
          projectId: LEGACY_CROWN_CAD_PROJECT_ID,
          enabled: true,
        });
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
        const options = await api.getCustomerIssueRecordFilterOptions('cc-product', LEGACY_CC_PRODUCT_PROJECT_ID);
        customerMilestoneOptions.value = sortLatestCcMilestoneOptions(
          (options.milestoneTitles ?? [])
            .map((item) => ({
              label: item.label ?? item.value,
              value: item.value,
            }))
            .filter((item) => normalizeText(item.value)),
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

const CC_RELEASE_PATTERN = /\bCC\s*(\d{4})\s*R\s*(\d+)\b/i;
const SPACED_CC_RELEASE_PATTERN = /\bCC\s*\d{4}\s+R\s*\d+\b/i;

function sortLatestCcMilestoneOptions(options: DataScopeOption[]) {
  return [...options].sort((left, right) => compareLatestCcMilestone(left.value, right.value));
}

function compareLatestCcMilestone(left: string, right: string) {
  const leftVersion = parseCcRelease(left);
  const rightVersion = parseCcRelease(right);
  if (leftVersion.matched && rightVersion.matched) {
    const byYear = rightVersion.year - leftVersion.year;
    if (byYear !== 0) {
      return byYear;
    }
    const byRelease = rightVersion.release - leftVersion.release;
    if (byRelease !== 0) {
      return byRelease;
    }
    const bySpacedFormat = Number(rightVersion.spacedFormat) - Number(leftVersion.spacedFormat);
    if (bySpacedFormat !== 0) {
      return bySpacedFormat;
    }
  } else if (leftVersion.matched !== rightVersion.matched) {
    return leftVersion.matched ? -1 : 1;
  }
  return right.localeCompare(left, 'zh-Hans-CN');
}

function parseCcRelease(value: string) {
  const normalized = normalizeText(value);
  const match = CC_RELEASE_PATTERN.exec(normalized);
  if (!match) {
    return { matched: false, year: 0, release: 0, spacedFormat: false };
  }
  return {
    matched: true,
    year: Number.parseInt(match[1] ?? '0', 10),
    release: Number.parseInt(match[2] ?? '0', 10),
    spacedFormat: SPACED_CC_RELEASE_PATTERN.test(normalized),
  };
}
