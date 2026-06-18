import { computed, ref, watch, type Ref } from 'vue';
import { api } from '../api';
import type { DataScopeOption, DataScopeProvider } from '../types/data-scope';
import type { TestingPhaseDefinitionResponse } from '../types/api';

export interface StatisticBoardDataScopeConfig {
  provider: DataScopeProvider;
  options: Ref<DataScopeOption[]>;
  loading: Ref<boolean>;
}

const SYSTEM_TEST_DEFECT_SUMMARY_SCOPE_PROVIDER: DataScopeProvider = {
  id: 'system-test-defect-summary-phase',
  label: '测试阶段',
  queryKey: 'testingPhase',
  mode: 'cascader-single',
  placeholder: '全部测试阶段',
  emptyLabel: '全部测试阶段',
  defaultStrategy: 'empty',
  clearable: true,
  compact: true,
  summaryPrefix: '当前测试阶段',
};

export function useStatisticBoardDataScope(boardKey: Ref<string>) {
  const testingPhaseDefinitions = ref<TestingPhaseDefinitionResponse[]>([]);
  const loading = ref(false);
  const loaded = ref(false);
  const options = computed(() => buildTestingPhaseTree(testingPhaseDefinitions.value));

  const config = computed<StatisticBoardDataScopeConfig | null>(() => {
    if (boardKey.value !== 'system-test-defect-summary') {
      return null;
    }
    return {
      provider: SYSTEM_TEST_DEFECT_SUMMARY_SCOPE_PROVIDER,
      options,
      loading,
    };
  });

  watch(
    boardKey,
    async (nextBoardKey) => {
      if (nextBoardKey !== 'system-test-defect-summary' || loaded.value || loading.value) {
        return;
      }
      loading.value = true;
      try {
        testingPhaseDefinitions.value = await api.getTestingPhases({ enabled: true });
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
    const projectName = phaseScopeValue(normalizeText(definition.projectName));
    const testingPhase = normalizeText(definition.testingPhase);
    if (!testingPhase) {
      continue;
    }
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
      children: [...(project.children ?? [])].sort((left, right) =>
        left.label.localeCompare(right.label, 'zh-CN'),
      ),
    }))
    .sort((left, right) => left.label.localeCompare(right.label, 'zh-CN'));
}

function phaseScopeValue(projectName: string) {
  return projectName;
}

function normalizeText(value: string | null | undefined) {
  return String(value ?? '').trim();
}
