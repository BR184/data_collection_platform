import { computed, ref, type ComputedRef } from 'vue';
import { flattenStatisticColumnLeavesFromGroup, type StatisticColumnGroup } from '../types/api';

export function useStatisticViewSettings(
  columnGroups: ComputedRef<StatisticColumnGroup[]>,
  visibleColumnKeys: ComputedRef<string[]>,
) {
  const settingsVisible = ref(false);
  const draftVisibleColumnKeys = ref<string[]>([]);
  const expandedViewSettingGroups = ref<string[]>([]);

  const allColumnKeys = computed(() =>
    columnGroups.value.flatMap((group) => flattenStatisticColumnLeavesFromGroup(group).map((column) => column.key)),
  );
  const currentVisibleColumnCount = computed(() => visibleColumnKeys.value.length);

  const allColumnsSelected = computed(
    () => allColumnKeys.value.length > 0 && allColumnKeys.value.every((key) => draftVisibleColumnKeys.value.includes(key)),
  );
  const partiallySelectedColumns = computed(
    () => draftVisibleColumnKeys.value.length > 0 && draftVisibleColumnKeys.value.length < allColumnKeys.value.length,
  );

  const groupCheckAllStates = computed<Record<string, boolean>>(() =>
    Object.fromEntries(
      columnGroups.value.map((group) => [
        group.key,
        flattenStatisticColumnLeavesFromGroup(group).length > 0
          && flattenStatisticColumnLeavesFromGroup(group).every((column) => draftVisibleColumnKeys.value.includes(column.key)),
      ]),
    ),
  );

  const groupIndeterminateStates = computed<Record<string, boolean>>(() =>
    Object.fromEntries(
      columnGroups.value.map((group) => {
        const leafColumns = flattenStatisticColumnLeavesFromGroup(group);
        const selectedCount = leafColumns.filter((column) => draftVisibleColumnKeys.value.includes(column.key)).length;
        return [group.key, selectedCount > 0 && selectedCount < leafColumns.length];
      }),
    ),
  );

  function syncDraftFromVisible() {
    draftVisibleColumnKeys.value = [...visibleColumnKeys.value];
  }

  function openSettings() {
    syncDraftFromVisible();
    expandedViewSettingGroups.value = [];
    settingsVisible.value = true;
  }

  function closeSettings() {
    settingsVisible.value = false;
  }

  function toggleAllColumns(checked: boolean | string | number) {
    draftVisibleColumnKeys.value = checked ? [...allColumnKeys.value] : [];
  }

  function toggleGroupColumns(group: StatisticColumnGroup, checked: boolean | string | number) {
    const groupKeys = new Set(flattenStatisticColumnLeavesFromGroup(group).map((column) => column.key));
    if (checked) {
      draftVisibleColumnKeys.value = [...new Set([...draftVisibleColumnKeys.value, ...groupKeys])];
      return;
    }
    draftVisibleColumnKeys.value = draftVisibleColumnKeys.value.filter((key) => !groupKeys.has(key));
  }

  function isColumnSelected(columnKey: string) {
    return draftVisibleColumnKeys.value.includes(columnKey);
  }

  function toggleColumnSelection(columnKey: string, checked: boolean | string | number) {
    if (checked) {
      if (!draftVisibleColumnKeys.value.includes(columnKey)) {
        draftVisibleColumnKeys.value = [...draftVisibleColumnKeys.value, columnKey];
      }
      return;
    }
    draftVisibleColumnKeys.value = draftVisibleColumnKeys.value.filter((key) => key !== columnKey);
  }

  return {
    settingsVisible,
    draftVisibleColumnKeys,
    expandedViewSettingGroups,
    allColumnKeys,
    currentVisibleColumnCount,
    allColumnsSelected,
    partiallySelectedColumns,
    groupCheckAllStates,
    groupIndeterminateStates,
    syncDraftFromVisible,
    openSettings,
    closeSettings,
    toggleAllColumns,
    toggleGroupColumns,
    isColumnSelected,
    toggleColumnSelection,
  };
}
