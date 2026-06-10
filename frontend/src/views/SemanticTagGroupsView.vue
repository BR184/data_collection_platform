<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { api } from '../api';
import type { SemanticTagGroupCatalogResponse } from '../types/api';

const catalog = ref<SemanticTagGroupCatalogResponse | null>(null);
const loading = ref(false);
const errorMessage = ref('');

const groupCount = computed(() => catalog.value?.groups.length ?? 0);
const valueCount = computed(() =>
  catalog.value?.groups.reduce((total, group) => total + group.values.length, 0) ?? 0,
);

onMounted(() => {
  void loadCatalog();
});

async function loadCatalog() {
  loading.value = true;
  errorMessage.value = '';
  try {
    catalog.value = await api.getStaticSemanticTagGroups('issue');
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '加载语义标签组失败';
  } finally {
    loading.value = false;
  }
}

function entityTypeText(entityType?: string) {
  return {
    issue: '议题',
    merge_request: '合并请求',
    review_record: '评审记录',
    module: '模块',
  }[entityType ?? ''] ?? (entityType || '议题');
}

function sourceModeText(sourceMode: string) {
  return {
    STATIC: '静态规则',
    DYNAMIC: '动态归类',
    HYBRID: '规则 + 动态',
  }[sourceMode] ?? sourceMode;
}

function selectionModeText(selectionMode: string) {
  return {
    SINGLE: '单选',
    MULTIPLE: '多选',
  }[selectionMode] ?? selectionMode;
}

function matchStrategyText(matchStrategyName: string) {
  return {
    EXACT: '精确匹配',
    PREFIX: '前缀匹配',
    CONTAINS: '包含匹配',
    REGEX: '规则匹配',
  }[matchStrategyName] ?? matchStrategyName;
}
</script>

<template>
  <section class="semantic-tag-page">
    <el-card class="panel-card semantic-tag-summary-card">
      <div class="semantic-tag-summary">
        <div>
          <h2 class="content-title">语义标签组</h2>
          <p class="semantic-tag-subtitle">
            当前展示平台按规则维护的标签类型和值目录，供业务标签组和表格筛选复用。
          </p>
        </div>
        <el-space>
          <el-button :icon="Refresh" :loading="loading" @click="loadCatalog">刷新</el-button>
        </el-space>
      </div>

      <el-alert
        v-if="errorMessage"
        class="semantic-tag-alert"
        type="error"
        :title="errorMessage"
        show-icon
        :closable="false"
      />

      <div class="semantic-tag-metrics">
        <div class="semantic-tag-metric">
          <span>对象</span>
          <strong>{{ entityTypeText(catalog?.entityType) }}</strong>
        </div>
        <div class="semantic-tag-metric">
          <span>标签组</span>
          <strong>{{ groupCount }}</strong>
        </div>
        <div class="semantic-tag-metric">
          <span>选项值</span>
          <strong>{{ valueCount }}</strong>
        </div>
        <div class="semantic-tag-metric">
          <span>目录状态</span>
          <strong>{{ catalog ? '可用' : '待加载' }}</strong>
        </div>
      </div>
    </el-card>

    <el-skeleton v-if="loading && !catalog" :rows="8" animated />

    <div v-else class="semantic-tag-grid">
      <el-card
        v-for="group in catalog?.groups ?? []"
        :key="group.groupKey"
        class="panel-card semantic-tag-group-card"
      >
        <div class="semantic-tag-group-head">
          <div>
            <h3>{{ group.label }}</h3>
            <p>{{ group.values.length }} 个可选值</p>
          </div>
          <el-tag size="small" :type="group.selectionMode === 'SINGLE' ? 'warning' : 'info'">
            {{ selectionModeText(group.selectionMode) }}
          </el-tag>
        </div>
        <div class="semantic-tag-group-meta">
          <span>来源：{{ sourceModeText(group.sourceMode) }}</span>
          <span>匹配：{{ matchStrategyText(group.matchStrategyName) }}</span>
          <span>规则已绑定</span>
        </div>
        <div class="semantic-tag-values">
          <el-tag
            v-for="value in group.values"
            :key="value.valueKey"
            class="semantic-tag-value"
            effect="plain"
          >
            {{ value.label }}
          </el-tag>
        </div>
      </el-card>
    </div>
  </section>
</template>

<style scoped>
.semantic-tag-page {
  display: grid;
  gap: 12px;
}

.semantic-tag-summary-card {
  min-height: 150px;
}

.semantic-tag-summary {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.semantic-tag-subtitle {
  margin: 6px 0 0;
  color: #64748b;
  line-height: 1.5;
}

.semantic-tag-alert {
  margin-top: 12px;
}

.semantic-tag-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(120px, 1fr));
  gap: 10px;
  margin-top: 16px;
}

.semantic-tag-metric {
  display: grid;
  gap: 6px;
  padding: 10px 12px;
  border: 1px solid var(--panel-border);
  border-radius: 8px;
  background: #f8fafc;
}

.semantic-tag-metric span {
  color: #64748b;
  font-size: 12px;
}

.semantic-tag-metric strong {
  color: #0f172a;
  font-size: 18px;
  font-weight: 700;
}

.semantic-tag-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 12px;
}

.semantic-tag-group-card {
  min-height: 190px;
}

.semantic-tag-group-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}

.semantic-tag-group-head h3 {
  margin: 0;
  color: #0f172a;
  font-size: 16px;
}

.semantic-tag-group-head p {
  margin: 4px 0 0;
  color: #64748b;
  font-size: 12px;
}

.semantic-tag-group-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 12px;
  color: #64748b;
  font-size: 12px;
}

.semantic-tag-group-meta span {
  padding: 3px 7px;
  border-radius: 6px;
  background: #eef2ff;
}

.semantic-tag-values {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 14px;
}

.semantic-tag-value {
  max-width: 100%;
}

@media (max-width: 760px) {
  .semantic-tag-summary {
    display: grid;
  }

  .semantic-tag-metrics {
    grid-template-columns: repeat(2, minmax(120px, 1fr));
  }

}
</style>
