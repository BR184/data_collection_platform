<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { QuestionFilled } from '@element-plus/icons-vue';
import { compactTableHeaderLines, normalizeTableHeaderLines, visualTextUnits } from './table-header-layout';

const props = defineProps<{
  label: string;
  lines?: readonly string[];
  tooltip?: string;
  align?: 'left' | 'center' | 'right';
  preferStacked?: boolean;
}>();

// 表头智能展示规则：
// 1. 默认按语义短语拆成最多两行，避免“一级缺陷数量(个)”变成“一级缺陷数量/(个)”这类低效换行。
// 2. 第一行承载核心对象，不能主动显示省略号；第二行只有在极窄宽度下才允许省略。
// 3. 表格正文优先使用可用空间，真正溢出时才省略；不能出现单元格右侧空着但文字已经被截断。
// 4. 人名类列只保留三字左右空间，长姓名允许截断；标签类列应尽量展示多个标签。
// 5. 平台普通表、统计表、下钻表和子表都应复用本组件，表头与正文默认居中对齐。

const rootRef = ref<HTMLElement>();
const measureRef = ref<HTMLElement>();
const firstLineMeasureRef = ref<HTMLElement>();
const compactFirstLineMeasureRef = ref<HTMLElement>();
const lines = computed(() => normalizeTableHeaderLines(props.label, props.lines));
const compactLines = computed(() => compactTableHeaderLines(props.label, props.lines));
const stacked = ref(lines.value.length > 1);
const compacted = ref(false);
const displayedLines = computed(() => (compacted.value ? compactLines.value : lines.value));
const hasStackedCandidate = computed(() => lines.value.length > 1);
const shortSingleLine = computed(() => {
  const characters = Array.from(String(props.label ?? '').trim()).length;
  return !stacked.value && (characters <= 4 || visualTextUnits(props.label) <= 8);
});
let resizeObserver: ResizeObserver | undefined;
const layoutTolerancePx = 8;

function updateLayout() {
  const root = rootRef.value;
  if (!root || !hasStackedCandidate.value) {
    stacked.value = false;
    compacted.value = false;
    return;
  }
  const styles = window.getComputedStyle(root);
  const horizontalPadding = Number.parseFloat(styles.paddingLeft || '0') + Number.parseFloat(styles.paddingRight || '0');
  const availableWidth = root.clientWidth - horizontalPadding - (props.tooltip ? 18 : 0);
  const fullLabelWidth = measureRef.value?.scrollWidth ?? 0;
  const stackedComfortReserve = props.preferStacked ? 48 : 0;
  if (availableWidth > 0 && fullLabelWidth + stackedComfortReserve <= availableWidth + layoutTolerancePx) {
    stacked.value = false;
    compacted.value = false;
    return;
  }
  stacked.value = true;
  const firstLineWidth = firstLineMeasureRef.value?.scrollWidth ?? 0;
  const compactFirstLineWidth = compactFirstLineMeasureRef.value?.scrollWidth ?? 0;
  compacted.value =
    compactLines.value[0] !== lines.value[0]
    && availableWidth > 0
    && firstLineWidth > availableWidth + layoutTolerancePx
    && compactFirstLineWidth <= availableWidth + layoutTolerancePx;
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
      { 'is-stacked': stacked, 'is-short-single': shortSingleLine },
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
      <span
        v-for="(line, index) in displayedLines"
        :key="`${line}-${index}`"
        class="smart-table-header__line"
        :class="{ 'smart-table-header__line--primary': index === 0 }"
      >
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
  text-align: center;
}

.smart-table-header--center {
  text-align: center;
}

.smart-table-header--right {
  text-align: center;
}

.smart-table-header--left .smart-table-header__single,
.smart-table-header--left .smart-table-header__line {
  justify-content: center;
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
  overflow: visible;
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

.smart-table-header__line--primary {
  overflow: visible;
}

.smart-table-header__text {
  display: block;
  min-width: 0;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
}

.smart-table-header__line--primary .smart-table-header__text {
  min-width: max-content;
  overflow: visible;
  text-overflow: clip;
}

.smart-table-header.is-short-single .smart-table-header__single,
.smart-table-header.is-short-single .smart-table-header__text {
  overflow: visible;
  text-overflow: clip;
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
