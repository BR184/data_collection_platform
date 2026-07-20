<script setup lang="ts">
import { computed } from 'vue';
import { ArrowDown, Download } from '@element-plus/icons-vue';

type ExportAction = {
  key: string;
  label: string;
  disabled?: boolean;
  loading?: boolean;
};

const props = withDefaults(
  defineProps<{
    actions?: ExportAction[];
    loading?: boolean;
    disabled?: boolean;
    menuLabel?: string;
  }>(),
  {
    actions: () => [],
    loading: false,
    disabled: false,
    menuLabel: '导出...',
  },
);

const emit = defineEmits<{
  (event: 'select', key: string): void;
}>();

const availableActions = computed(() =>
  props.actions.filter((action) => Boolean(action.key?.trim()) && Boolean(action.label?.trim())),
);
const singleAction = computed(() => availableActions.value[0] ?? null);
const hasMultipleActions = computed(() => availableActions.value.length > 1);
const actionLoading = computed(() =>
  Boolean(props.loading) || availableActions.value.some((action) => Boolean(action.loading)),
);
const actionDisabled = computed(() => Boolean(props.disabled) || actionLoading.value);

function selectAction(action: ExportAction | null | undefined) {
  if (!action || action.disabled || actionDisabled.value) {
    return;
  }
  emit('select', action.key);
}

function handleDropdownCommand(command: string | number | object) {
  const key = String(command);
  selectAction(availableActions.value.find((action) => action.key === key));
}
</script>

<template>
  <el-dropdown
    v-if="hasMultipleActions"
    trigger="click"
    popper-class="app-export-dropdown-menu"
    :disabled="actionDisabled"
    @command="handleDropdownCommand"
  >
    <el-button
      class="app-action-button app-action-button--export"
      plain
      :icon="Download"
      :loading="actionLoading"
      :disabled="actionDisabled"
      aria-label="导出选项"
    >
      {{ menuLabel }}
      <el-icon class="el-icon--right"><ArrowDown /></el-icon>
    </el-button>
    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item
          v-for="action in availableActions"
          :key="action.key"
          :command="action.key"
          :disabled="actionDisabled || action.disabled"
        >
          {{ action.label }}
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
  <el-button
    v-else-if="singleAction"
    class="app-action-button app-action-button--export"
    plain
    :icon="Download"
    :loading="actionLoading"
    :disabled="actionDisabled || singleAction.disabled"
    :aria-label="singleAction.label"
    @click.stop="selectAction(singleAction)"
  >
    {{ singleAction.label }}
  </el-button>
</template>
