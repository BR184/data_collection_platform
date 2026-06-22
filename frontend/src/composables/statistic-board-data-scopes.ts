import { computed, ref, watch, type Ref } from 'vue';
import { api } from '../api';
import { authState } from './auth-state';
import type { DataScopeOption, DataScopeProvider } from '../types/data-scope';
import type { TestingPhaseDefinitionResponse } from '../types/api';

const LEGACY_CROWN_CAD_PROJECT_ID = 9;

export interface StatisticBoardDataScopeConfig {
  provider: DataScopeProvider;
  options: Ref<DataScopeOption[]>;
  loading: Ref<boolean>;
}

const SYSTEM_TEST_BOARD_KEYS = new Set([
  'system-test-defect-summary',
  'system-test-delay-analysis',
  'system-test-defect-cause',
  'system-test-phase-statistics',
]);

const SYSTEM_TEST_DEFECT_SUMMARY_SCOPE_PROVIDER: DataScopeProvider = {
  id: 'system-test-defect-summary-phase',
  label: '测试阶段',
  queryKey: 'testingPhase',
  mode: 'single-select',
  placeholder: '选择测试阶段',
  defaultStrategy: 'first-available',
  clearable: false,
  compact: true,
  summaryPrefix: '当前测试阶段',
};

const SYSTEM_TEST_PARENT_SCOPE_PROVIDER: DataScopeProvider = {
  id: 'system-test-parent-phase',
  label: '里程碑',
  queryKey: 'testingPhase',
  mode: 'single-select',
  placeholder: '全部里程碑',
  emptyLabel: '全部里程碑',
  defaultStrategy: 'first-available',
  clearable: true,
  compact: true,
  summaryPrefix: '当前里程碑',
};

export function useStatisticBoardDataScope(boardKey: Ref<string>) {
  const testingPhaseDefinitions = ref<TestingPhaseDefinitionResponse[]>([]);
  const loading = ref(false);
  const loaded = ref(false);
  const options = computed(() => buildTestingPhaseTree(testingPhaseDefinitions.value));
  const parentOptions = computed(() => buildParentOptions(testingPhaseDefinitions.value));

  const config = computed<StatisticBoardDataScopeConfig | null>(() => {
    if (!SYSTEM_TEST_BOARD_KEYS.has(boardKey.value)) {
      return null;
    }
    if (boardKey.value !== 'system-test-defect-summary') {
      return {
        provider: SYSTEM_TEST_PARENT_SCOPE_PROVIDER,
        options: parentOptions,
        loading,
      };
    }
    return {
      provider: SYSTEM_TEST_DEFECT_SUMMARY_SCOPE_PROVIDER,
      options: parentOptions,
      loading,
    };
  });

  watch(
    [boardKey, () => authState.currentUser.authenticated],
    async ([nextBoardKey]) => {
      if (!SYSTEM_TEST_BOARD_KEYS.has(nextBoardKey) || loaded.value || loading.value) {
        return;
      }
      loading.value = true;
      try {
        testingPhaseDefinitions.value = await api.getTestingPhases({
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

function buildTestingPhaseTree(definitions: TestingPhaseDefinitionResponse[]): DataScopeOption[] {
  const projectMap = new Map<string, DataScopeOption>();
  for (const definition of definitions) {
    const testingPhase = normalizeText(definition.testingPhase);
    const parentName = normalizeText(definition.legacyPhaseName) || normalizeText(definition.projectName);
    if (!testingPhase) {
      continue;
    }
    if (!parentName) {
      continue;
    }
    const projectName = phaseScopeValue(parentName);
    const parent = projectMap.get(projectName) ?? {
      label: projectName,
      value: phaseScopeValue(projectName),
      children: [],
    };
    parent.children = parent.children ?? [];
    parent.children.push({
      label: testingPhase,
      value: testingPhase,
    });
    projectMap.set(projectName, parent);
  }
  return [...projectMap.values()]
    .map((project) => ({
      ...project,
      children: [...(project.children ?? [])],
    }));
}

function buildParentOptions(definitions: TestingPhaseDefinitionResponse[]): DataScopeOption[] {
  const parents = new Map<string, DataScopeOption>();
  for (const definition of definitions) {
    const parent = normalizeText(definition.legacyPhaseName) || normalizeText(definition.projectName);
    if (!parent) {
      continue;
    }
    parents.set(parent, { label: parent, value: parent });
  }
  return [...parents.values()];
}

function phaseScopeValue(projectName: string) {
  return projectName;
}

function normalizeText(value: string | null | undefined) {
  return String(value ?? '').trim();
}
