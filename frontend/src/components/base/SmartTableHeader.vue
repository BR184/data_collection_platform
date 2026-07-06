<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { QuestionFilled } from '@element-plus/icons-vue';
import { compactTableHeaderLines, normalizeTableHeaderLines } from './table-header-layout';

const props = defineProps<{
  label: string;
  lines?: readonly string[];
  tooltip?: string;
  align?: 'left' | 'center' | 'right';
}>();

const rootRef = ref<HTMLElement>();
const measureRef = ref<HTMLElement>();
const firstLineMeasureRef = ref<HTMLElement>();
const compactFirstLineMeasureRef = ref<HTMLElement>();
const stacked = ref(false);
const compacted = ref(false);
const lines = computed(() => normalizeTableHeaderLines(props.label, props.lines));
const compactLines = computed(() => compactTableHeaderLines(props.label, props.lines));
const displayedLines = computed(() => (compacted.value ? compactLines.value : lines.value));
const hasStackedCandidate = computed(() => lines.value.length > 1);
let resizeObserver: ResizeObserver | undefined;
const singleLineUnlockPx = 52;
const compactTolerancePx = 8;

function updateLayout() {
  const root = rootRef.value;
  const measure = measureRef.value;
  if (!root || !measure || !hasStackedCandidate.value) {
    stacked.value = false;
    compacted.value = false;
    return;
  }
  const availableWidth = root.clientWidth - (props.tooltip ? 18 : 0);
  stacked.value = availableWidth > 0 && availableWidth < measure.scrollWidth + singleLineUnlockPx;
  if (!stacked.value) {
    compacted.value = false;
    return;
  }
  const firstLineWidth = firstLineMeasureRef.value?.scrollWidth ?? 0;
  const compactFirstLineWidth = compactFirstLineMeasureRef.value?.scrollWidth ?? 0;
  compacted.value =
    compactLines.value[0] !== lines.value[0]
    && firstLineWidth > availableWidth
    && compactFirstLineWidth <= availableWidth + compactTolerancePx;
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
    <span ref="firstLineMeasureRef" class="smart-table-header__measure">{{ lines[0] }}</span>
    <span ref="compactFirstLineMeasureRef" class="smart-table-header__measure">{{ compactLines[0] }}</span>
    <span v-if="!stacked" class="smart-table-header__single">
      <span class="smart-table-header__text">{{ label }}</span>
      <el-tooltip v-if="tooltip" :content="tooltip" placement="top">
        <el-icon class="smart-table-header__help">
          <QuestionFilled />
        </el-icon>
      </el-tooltip>
    </span>
    <span v-else class="smart-table-header__stack">
      <span v-for="(line, index) in displayedLines" :key="`${line}-${index}`" class="smart-table-header__line">
        <span class="smart-table-header__text">{{ line }}</span>
        <el-tooltip v-if="tooltip && index === displayedLines.length - 1" :content="tooltip" placement="top">
          <el-icon class="smart-table-header__help">
            <QuestionFilled />
          </el-icon>
        </el-tooltip>
      </span>
    </span>
  </span>
</template>

<style scoped>
.smart-table-header {
  position: relative;
  display: block;
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
}

.smart-table-header--center {
  text-align: center;
}

.smart-table-header--right {
  text-align: center;
}

.smart-table-header--left .smart-table-header__single,
.smart-table-header--left .smart-table-header__line {
  justify-content: flex-start;
}

.smart-table-header__single,
.smart-table-header__stack {
  min-width: 0;
  max-width: 100%;
  overflow: hidden;
}

.smart-table-header__single {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
  white-space: nowrap;
}

.smart-table-header__stack {
  display: grid;
  justify-items: center;
  gap: 1px;
  line-height: 1.16;
}

.smart-table-header__line {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
  min-width: 0;
  max-width: 100%;
  overflow: hidden;
  white-space: nowrap;
}

.smart-table-header__text {
  display: block;
  min-width: 0;
  max-width: 100%;
  overflow: hidden;
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
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: rgba(100, 116, 139, 0.86);
  font-size: 13px;
  line-height: 1;
  transform: translateY(0.5px);
}
</style>
