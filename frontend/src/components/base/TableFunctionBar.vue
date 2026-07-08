<script setup lang="ts">
import { computed, useSlots } from 'vue';

const props = withDefaults(
  defineProps<{
    quickVisible?: boolean;
  }>(),
  {
    quickVisible: false,
  },
);

const slots = useSlots();

const hasFilter = computed(() => Boolean(slots.filter));
const hasQuick = computed(() => Boolean(slots.quick));
const hasStatus = computed(() => Boolean(slots.status));
const hasActions = computed(() => Boolean(slots.actions));
const hasMetaRow = computed(() => hasStatus.value || hasActions.value);
</script>

<template>
  <div
    class="table-function-bar"
    :class="{ 'table-function-bar--quick-visible': quickVisible && hasQuick }"
  >
    <section v-if="hasFilter" class="table-function-bar__filter">
      <slot name="filter" />
    </section>

    <el-collapse-transition>
      <section v-show="quickVisible && hasQuick" class="table-function-bar__quick">
        <slot name="quick" />
      </section>
    </el-collapse-transition>

    <section v-if="hasMetaRow" class="table-function-bar__meta">
      <div v-if="hasStatus" class="table-function-bar__status">
        <slot name="status" />
      </div>
      <div v-if="hasActions" class="table-function-bar__actions">
        <slot name="actions" />
      </div>
    </section>
  </div>
</template>

<style scoped>
.table-function-bar {
  display: grid;
  gap: 10px;
  width: 100%;
  min-width: 0;
}

.table-function-bar__filter {
  min-width: 0;
}

.table-function-bar__quick {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  min-width: 0;
  padding: 8px;
  border: 1px solid rgba(15, 23, 42, 0.06);
  border-radius: 8px;
  background: rgba(248, 250, 252, 0.72);
}

.table-function-bar__meta {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.table-function-bar__status,
.table-function-bar__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  max-width: 100%;
  min-width: 0;
}

.table-function-bar__status {
  justify-content: flex-start;
}

.table-function-bar__actions {
  justify-content: flex-end;
}

.table-function-bar__actions :deep(.el-button + .el-button),
.table-function-bar__actions :deep(.el-dropdown + .el-button),
.table-function-bar__actions :deep(.el-button + .el-dropdown),
.table-function-bar__actions :deep(.el-dropdown + .el-dropdown) {
  margin-left: 0;
}

@media (max-width: 1180px) {
  .table-function-bar__meta {
    grid-template-columns: 1fr;
  }

  .table-function-bar__actions {
    justify-content: flex-start;
    width: 100%;
  }
}
</style>
