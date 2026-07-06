<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { QuestionFilled } from '@element-plus/icons-vue';
import { normalizeTableHeaderLines } from './table-header-layout';

const props = defineProps<{
  label: string;
  lines?: readonly string[];
  tooltip?: string;
  align?: 'left' | 'center' | 'right';
}>();

const rootRef = ref<HTMLElement>();
const measureRef = ref<HTMLElement>();
const stacked = ref(false);
const lines = computed(() => normalizeTableHeaderLines(props.label, props.lines));
const hasStackedCandidate = computed(() => lines.value.length > 1);
let resizeObserver: ResizeObserver | undefined;

function updateLayout() {
  const root = rootRef.value;
  const measure = measureRef.value;
  if (!root || !measure || !hasStackedCandidate.value) {
    stacked.value = false;
    return;
  }
  const availableWidth = root.clientWidth - (props.tooltip ? 18 : 0);
  stacked.value = availableWidth > 0 && measure.scrollWidth > availableWidth;
}

onMounted(() => {
  resizeObserver = new ResizeObserver(() => updateLayout());
  if (rootRef.value) {
    resizeObserver.observe(rootRef.value);
  }
  void nextTick(updateLayout);
});

onBeforeUnmount(() => {
  resizeObserver?.disconnect();
});

watch(
  () => [props.label, props.tooltip, props.lines?.join('\n')],
  () => void nextTick(updateLayout),
);
</script>

<template>
  <span
    ref="rootRef"
    class="smart-table-header"
    :class="[
      `smart-table-header--${align ?? 'center'}`,
      { 'is-stacked': stacked },
    ]"
    :title="label"
  >
    <span ref="measureRef" class="smart-table-header__measure">{{ label }}</span>
    <span v-if="!stacked" class="smart-table-header__single">{{ label }}</span>
    <span v-else class="smart-table-header__stack">
      <span v-for="(line, index) in lines" :key="`${line}-${index}`" class="smart-table-header__line">
        {{ line }}
      </span>
    </span>
    <el-tooltip v-if="tooltip" :content="tooltip" placement="top">
      <el-icon class="smart-table-header__help">
        <QuestionFilled />
      </el-icon>
    </el-tooltip>
  </span>
</template>

<style scoped>
.smart-table-header {
  position: relative;
  display: inline-grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 4px;
  width: 100%;
  min-width: 0;
  max-width: 100%;
  color: inherit;
  font: inherit;
  line-height: 1.2;
  vertical-align: middle;
}

.smart-table-header--left {
  text-align: left;
  justify-items: start;
}

.smart-table-header--center {
  text-align: center;
  justify-items: center;
}

.smart-table-header--right {
  text-align: center;
  justify-items: center;
}

.smart-table-header__single,
.smart-table-header__stack {
  display: block;
  min-width: 0;
  max-width: 100%;
  overflow: hidden;
}

.smart-table-header__single {
  white-space: nowrap;
  text-overflow: ellipsis;
}

.smart-table-header__stack {
  display: grid;
  gap: 1px;
  line-height: 1.16;
}

.smart-table-header__line {
  display: block;
  min-width: 0;
  max-width: 100%;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.smart-table-header__measure {
  position: absolute;
  inset: auto auto 0 0;
  z-index: -1;
  width: max-content;
  max-width: none;
  overflow: visible;
  white-space: nowrap;
  pointer-events: none;
  opacity: 0;
}

.smart-table-header__help {
  flex: 0 0 auto;
  color: rgba(100, 116, 139, 0.86);
  font-size: 14px;
}
</style>
