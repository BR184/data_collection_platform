<script setup lang="ts">
import type { PropType } from 'vue';
import type { BiMetricTrace } from '../data/types';

defineProps({
  modelValue: { type: Boolean, required: true },
  traces: { type: Array as PropType<BiMetricTrace[]>, required: true },
});

const emit = defineEmits<{ (event: 'update:modelValue', value: boolean): void }>();
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    title="数据来源与计算口径"
    size="560px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="bi-trace-list">
      <section v-for="(trace, index) in traces" :key="`${trace.metricIds.join('-')}-${index}`" class="bi-trace-item">
        <div class="bi-trace-item__ids">
          <el-tag v-for="id in trace.metricIds" :key="id" size="small" effect="plain">{{ id }}</el-tag>
        </div>
        <el-descriptions :column="1" size="small" border>
          <el-descriptions-item label="来源平台">{{ trace.sourcePlatform }}</el-descriptions-item>
          <el-descriptions-item label="来源字段">
            <span v-for="(field, fieldIndex) in trace.sourceFields" :key="`${field.chineseName}-${field.englishName}`">
              {{ field.chineseName }}（{{ field.englishName }}）<template v-if="fieldIndex < trace.sourceFields.length - 1">、</template>
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="公式 / 规则">{{ trace.formula }}</el-descriptions-item>
          <el-descriptions-item label="计算责任">{{ trace.calculationOwner }}</el-descriptions-item>
        </el-descriptions>
      </section>
      <el-empty v-if="traces.length === 0" description="当前页面暂无追溯信息" />
    </div>
  </el-drawer>
</template>

<style scoped>
.bi-trace-list { display: grid; gap: 14px; }
.bi-trace-item { display: grid; gap: 8px; }
.bi-trace-item__ids { display: flex; flex-wrap: wrap; gap: 6px; }
</style>

