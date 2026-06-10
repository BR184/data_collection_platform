<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
import { Filter, Refresh } from '@element-plus/icons-vue';
import { ElMessage } from '../element-plus-services';
import { api } from '../api';
import type { BusinessTagGroupApplyResponse, BusinessTagGroupResponse } from '../types/api';

const props = withDefaults(
  defineProps<{
    entityType?: string;
    scenarioKey?: string;
    ownerUserId?: string;
    currentTagSchemaHash?: string;
  }>(),
  {
    entityType: 'issue',
    scenarioKey: undefined,
    ownerUserId: undefined,
    currentTagSchemaHash: undefined,
  },
);

const emit = defineEmits<{
  (event: 'applied', result: BusinessTagGroupApplyResponse): void;
  (event: 'cleared'): void;
}>();

const tagGroups = ref<BusinessTagGroupResponse[]>([]);
const selectedId = ref<number | null>(null);
const loading = ref(false);
const applying = ref(false);
const appliedName = ref('');

onMounted(() => {
  void loadTagGroups();
});

watch(
  () => [props.entityType, props.scenarioKey, props.ownerUserId],
  () => {
    selectedId.value = null;
    appliedName.value = '';
    void loadTagGroups();
  },
);

async function loadTagGroups() {
  loading.value = true;
  try {
    tagGroups.value = await api.listBusinessTagGroups({
      entityType: props.entityType,
      scenarioKey: props.scenarioKey,
      ownerUserId: props.ownerUserId,
    });
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载业务标签组失败');
  } finally {
    loading.value = false;
  }
}

async function applySelectedTagGroup() {
  if (selectedId.value == null) {
    ElMessage.warning('请选择要应用的业务标签组');
    return;
  }
  applying.value = true;
  try {
    const result = await api.applyBusinessTagGroup(selectedId.value, props.currentTagSchemaHash);
    appliedName.value = result.tagGroup.tagGroupName;
    if (!result.schemaCompatible) {
      ElMessage.warning('该标签组与当前页面字段版本不完全一致，请复核筛选结果');
    }
    emit('applied', result);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '应用业务标签组失败');
  } finally {
    applying.value = false;
  }
}

function clearAppliedTagGroup() {
  selectedId.value = null;
  appliedName.value = '';
  emit('cleared');
}

function fieldScopeText(scopeKey: string) {
  return {
    all_fields: '全部可筛选字段',
    people_fields: '人员字段',
    project_fields: '项目字段',
    module_fields: '模块字段',
    milestone_fields: '里程碑/轮次字段',
  }[scopeKey] ?? '适用范围待确认';
}
</script>

<template>
  <div class="business-tag-apply">
    <el-select
      v-model="selectedId"
      class="business-tag-apply-select"
      :loading="loading"
      clearable
      filterable
      placeholder="应用业务标签组"
    >
      <el-option
        v-for="tagGroup in tagGroups"
        :key="tagGroup.id"
        :label="tagGroup.tagGroupName"
        :value="tagGroup.id"
      >
        <div class="business-tag-option">
          <span>{{ tagGroup.tagGroupName }}</span>
          <small>{{ fieldScopeText(tagGroup.scopeKey) }}</small>
        </div>
      </el-option>
    </el-select>
    <el-button :icon="Filter" type="primary" :loading="applying" @click="applySelectedTagGroup">
      应用
    </el-button>
    <el-button :icon="Refresh" :loading="loading" @click="loadTagGroups">刷新</el-button>
    <el-tag v-if="appliedName" closable effect="plain" @close="clearAppliedTagGroup">
      已应用：{{ appliedName }}
    </el-tag>
  </div>
</template>

<style scoped>
.business-tag-apply {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  min-width: 0;
}

.business-tag-apply-select {
  width: 220px;
}

.business-tag-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.business-tag-option small {
  color: #64748b;
  font-size: 12px;
}

@media (max-width: 640px) {
  .business-tag-apply,
  .business-tag-apply-select {
    width: 100%;
  }
}
</style>
