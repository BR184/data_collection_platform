import { computed, onBeforeUnmount, ref, watch, watchEffect, type MaybeRefOrGetter, toValue } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import type { DataScopeOption, DataScopeProvider, DataScopeSelectionSummary } from '../types/data-scope';
import { clearShellDataScope, registerShellDataScope } from './shell-data-scope';

type QueryValue = string | number | null | undefined;

export interface UseDataScopeOptions {
  provider?: DataScopeProvider | null;
  options: MaybeRefOrGetter<DataScopeOption[]>;
  clearQueryKeysOnChange?: string[];
  extraPatchOnChange?: (nextValue: string) => Record<string, QueryValue>;
  mountToShell?: boolean;
  loading?: MaybeRefOrGetter<boolean>;
}

function flattenOptions(options: DataScopeOption[]): DataScopeOption[] {
  return options.flatMap((option) => [option, ...flattenOptions(option.children ?? [])]);
}

export function useDataScope(options: UseDataScopeOptions) {
  const route = useRoute();
  const router = useRouter();
  const syncing = ref(false);
  const shellToken = `scope-${Math.random().toString(36).slice(2, 10)}`;
  const scopeOptions = computed(() => toValue(options.options));
  const flatOptions = computed(() => flattenOptions(scopeOptions.value));
  const provider = computed(() => options.provider ?? null);

  const value = computed(() => {
    const queryKey = provider.value?.queryKey;
    if (!queryKey) {
      return '';
    }
    return String(route.query[queryKey] ?? '');
  });

  const selectedOption = computed(
    () => flatOptions.value.find((option) => option.value === value.value) ?? null,
  );

  const summary = computed<DataScopeSelectionSummary | null>(() => {
    if (!provider.value || !selectedOption.value || !selectedOption.value.value) {
      return null;
    }
    return {
      label: provider.value.summaryPrefix || provider.value.label,
      value: selectedOption.value.label,
    };
  });

  async function patchQuery(nextValue: string) {
    const currentProvider = provider.value;
    if (!currentProvider) {
      return;
    }
    const nextQuery = { ...route.query } as Record<string, string>;
    if (nextValue) {
      nextQuery[currentProvider.queryKey] = nextValue;
    } else {
      delete nextQuery[currentProvider.queryKey];
    }
    nextQuery.page = '1';
    for (const key of options.clearQueryKeysOnChange ?? []) {
      delete nextQuery[key];
    }
    const extraPatch = options.extraPatchOnChange?.(nextValue) ?? {};
    for (const [key, patchValue] of Object.entries(extraPatch)) {
      if (patchValue == null || patchValue === '') {
        delete nextQuery[key];
      } else {
        nextQuery[key] = String(patchValue);
      }
    }
    syncing.value = true;
    try {
      await router.replace({
        path: route.path,
        query: nextQuery,
        hash: route.hash,
      });
    } finally {
      syncing.value = false;
    }
  }

  function resolveFallbackValue() {
    const currentProvider = provider.value;
    if (!currentProvider) {
      return '';
    }
    if (currentProvider.defaultStrategy === 'first-available') {
      return flatOptions.value.find((option) => !option.disabled && option.value)?.value ?? '';
    }
    return '';
  }

  watch(
    [provider, flatOptions, value],
    async ([currentProvider, currentOptions, currentValue]) => {
      if (!currentProvider || syncing.value) {
        return;
      }
      if (!currentOptions.length) {
        return;
      }
      const optionExists = currentValue === '' || currentOptions.some((option) => option.value === currentValue);
      if (optionExists) {
        if (currentValue || currentProvider.defaultStrategy !== 'first-available') {
          return;
        }
      }
      const nextValue = optionExists ? resolveFallbackValue() : resolveFallbackValue();
      if (nextValue === currentValue) {
        return;
      }
      await patchQuery(nextValue);
    },
    { immediate: true },
  );

  watchEffect(() => {
    if (!options.mountToShell || !provider.value) {
      clearShellDataScope(shellToken);
      return;
    }
    registerShellDataScope(shellToken, {
      provider: provider.value,
      options: scopeOptions.value,
      modelValue: value.value,
      summary: summary.value ? `${summary.value.label}：${summary.value.value}` : '',
      loading: options.loading ? Boolean(toValue(options.loading)) : false,
      onChange: patchQuery,
    });
  });

  onBeforeUnmount(() => {
    clearShellDataScope(shellToken);
  });

  return {
    provider,
    options: scopeOptions,
    value,
    selectedOption,
    summary,
    setValue: patchQuery,
  };
}
