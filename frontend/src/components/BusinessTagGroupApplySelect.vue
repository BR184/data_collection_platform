<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
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
    supportedFieldKeys?: string[];
    allEntities?: boolean;
  }>(),
  {
    entityType: 'issue',
    scenarioKey: undefined,
    ownerUserId: undefined,
    currentTagSchemaHash: undefined,
    supportedFieldKeys: () => [],
    allEntities: false,
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
const displayTagGroups = computed(() => {
  if (!props.supportedFieldKeys.length) {
    return tagGroups.value;
  }
  return tagGroups.value.filter((tagGroup) => isTagGroupSupported(tagGroup));
});

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
      entityType: props.allEntities ? undefined : props.entityType,
      scenarioKey: props.allEntities ? undefined : props.scenarioKey,
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

function isTagGroupSupported(tagGroup: BusinessTagGroupResponse) {
  const fieldKeys = parseFieldKeys(tagGroup.dslJson);
  return fieldKeys.length > 0 && fieldKeys.every((fieldKey) => props.supportedFieldKeys.includes(fieldKey));
}

function parseFieldKeys(dslJson: string) {
  try {
    const parsed = JSON.parse(dslJson) as { conditions?: Array<{ fieldKey?: unknown; fieldFamily?: unknown }> };
    return Array.from(new Set((parsed.conditions ?? [])
      .map((condition) => normalizeFieldKey(String(condition.fieldKey || condition.fieldFamily || '').trim()))
      .filter(Boolean)));
  } catch {
    return [];
  }
}

function normalizeFieldKey(fieldKey: string) {
  return fieldKey === 'people' ? 'person' : fieldKey;
}

function fieldScopeText(scopeKey: string) {
  return {
    all_fields: '复合条件',
    people_fields: '人员',
    project_fields: '项目/版本',
    module_fields: '模块',
    milestone_fields: '里程碑/轮次',
  }[scopeKey] ?? '系统自动判断';
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
        v-for="tagGroup in displayTagGroups"
        :key="tagGroup.id"
        :label="tagGroup.tagGroupName"
        :value="tagGroup.id"
      >
        <div class="business-tag-option">
          <span>{{ tagGroup.tagGroupName }}</span>
          <small>{{ fieldScopeText(tagGroup.scopeKey) }}</small>
        </div>
      </el-option>
      <template #empty>
        <span class="business-tag-empty">暂无可应用标签组</span>
      </template>
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

.business-tag-empty {
  display: block;
  padding: 8px 12px;
  color: #64748b;
  font-size: 13px;
}

@media (max-width: 640px) {
  .business-tag-apply,
  .business-tag-apply-select {
    width: 100%;
  }
}
</style>
