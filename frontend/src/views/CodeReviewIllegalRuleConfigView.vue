<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
// 代码走查规则配置页负责把规则草稿、预览和保存动作串成一个可回滚的编辑流程。
// 本页只维护前端编辑状态，实际规则解释和非法判定仍以后端保存后的配置为准。
import { ArrowLeft, RefreshLeft, Select, Setting } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from '../element-plus-services';
import { useRoute, useRouter } from 'vue-router';
import CodeReviewRuleConfigEditor from '../components/rule-config/CodeReviewRuleConfigEditor.vue';
import CodeReviewRuleConfigPreview from '../components/rule-config/CodeReviewRuleConfigPreview.vue';
import { api } from '../api';
import type { CodeReviewIllegalRecordFilterOptionsResponse } from '../types/api';
import type { CodeReviewRuleConfig, CodeReviewRulePreviewResponse } from '../types/code-review-rule-config';
import { buildCodeReviewRuleFields } from './code-review-rule-config-schema';
import {
  loadStoredCodeReviewRuleConfig,
  saveStoredCodeReviewRuleConfig,
} from './code-review-rule-config-storage';
import {
  cloneCodeReviewRuleConfig,
  createDefaultCodeReviewRuleConfig,
  normalizeCodeReviewRuleConfig,
} from './code-review-rule-config-utils';

const router = useRouter();
const route = useRoute();

const filterOptions = ref<CodeReviewIllegalRecordFilterOptionsResponse>({
  requestTypes: [{ label: '合并请求', value: 'merge_request' }],
  repositoryNames: [],
  illegalTypes: [],
  targetBranches: [],
  mergedBys: [],
  moduleNames: [],
  projectNames: [],
});
const filterLoading = ref(false);
const previewLoading = ref(false);
const saving = ref(false);
const preview = ref<CodeReviewRulePreviewResponse | null>(null);
const draftConfig = ref<CodeReviewRuleConfig>(createDefaultCodeReviewRuleConfig());
const savedConfig = ref<CodeReviewRuleConfig>(createDefaultCodeReviewRuleConfig());
let previewTimer: number | null = null;
let removeLeaveGuard: (() => void) | null = null;

const fields = computed(() => buildCodeReviewRuleFields(filterOptions.value));
const dirty = computed(() => JSON.stringify(draftConfig.value) !== JSON.stringify(savedConfig.value));
const scopeTags = computed(() => {
  const tags: Array<{ label: string; value: string }> = [];
  const append = (label: string, key: string) => {
    const value = String(route.query[key] ?? '').trim();
    if (value) {
      tags.push({ label, value });
    }
  };
  append('代码库', 'repositoryName');
  append('非法类型', 'illegalType');
  append('关键字', 'keyword');
  append('项目', 'projectName');
  append('请求类型', 'requestType');
  append('目标分支', 'targetBranch');
  append('合并人', 'mergedBy');
  append('模块名', 'moduleName');
  append('合并请求编号', 'mergeRequestIid');
  append('责任人', 'owner');
  append('数据源', 'source');

  const mergedAtStart = String(route.query.mergedAtStart ?? '').trim();
  const mergedAtEnd = String(route.query.mergedAtEnd ?? '').trim();
  if (mergedAtStart || mergedAtEnd) {
    tags.push({
      label: '合并时间',
      value: [mergedAtStart || '不限', mergedAtEnd || '不限'].join(' ~ '),
    });
  }
  return tags;
});

const scopeDescription = computed(() => {
  if (!scopeTags.value.length) {
    return '按代码走查默认数据范围预览。';
  }
  return '沿用列表页带入的数据范围，再按编辑区规则重新判定。';
});

async function loadFilterOptions() {
  filterLoading.value = true;
  try {
    filterOptions.value = await api.getCodeReviewIllegalRecordFilterOptions(
      route.query.projectId as string | undefined,
      String(route.query.source ?? '') || undefined,
    );
  } finally {
    filterLoading.value = false;
  }
}

function syncDraftFromStorage() {
  const stored = loadStoredCodeReviewRuleConfig();
  const normalized = normalizeCodeReviewRuleConfig(
    stored ?? createDefaultCodeReviewRuleConfig(fields.value),
    fields.value,
  );
  draftConfig.value = normalized;
  savedConfig.value = cloneCodeReviewRuleConfig(normalized);
}

function schedulePreview() {
  if (previewTimer != null) {
    window.clearTimeout(previewTimer);
  }
  previewTimer = window.setTimeout(() => {
    previewTimer = null;
    void loadPreview();
  }, 250);
}

async function loadPreview() {
  previewLoading.value = true;
  try {
    preview.value = await api.previewCodeReviewIllegalRecordRuleConfig({
      projectId: route.query.projectId != null && route.query.projectId !== '' ? Number(route.query.projectId) : null,
      repositoryName: String(route.query.repositoryName ?? ''),
      mergedAtStart: String(route.query.mergedAtStart ?? ''),
      mergedAtEnd: String(route.query.mergedAtEnd ?? ''),
      keyword: String(route.query.keyword ?? ''),
      projectName: String(route.query.projectName ?? ''),
      requestType: String(route.query.requestType ?? ''),
      targetBranch: String(route.query.targetBranch ?? ''),
      mergedBy: String(route.query.mergedBy ?? ''),
      moduleName: String(route.query.moduleName ?? ''),
      illegalType: String(route.query.illegalType ?? ''),
      mergeRequestIid: String(route.query.mergeRequestIid ?? ''),
      owner: String(route.query.owner ?? ''),
      source: String(route.query.source ?? ''),
      ruleConfig: {
        ...draftConfig.value,
        enabled: true,
      },
    });
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '规则配置预览失败');
  } finally {
    previewLoading.value = false;
  }
}

async function initialize() {
  try {
    await loadFilterOptions();
    syncDraftFromStorage();
    await loadPreview();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '规则配置页面加载失败');
  }
}

function handleReset() {
  draftConfig.value = createDefaultCodeReviewRuleConfig(fields.value);
  ElMessage.info('已恢复到默认规则，保存或应用后生效');
}

function handleSave() {
  saving.value = true;
  const nextConfig: CodeReviewRuleConfig = {
    ...cloneCodeReviewRuleConfig(draftConfig.value),
    enabled: false,
    updatedAt: new Date().toISOString(),
  };
  saveStoredCodeReviewRuleConfig(nextConfig);
  savedConfig.value = cloneCodeReviewRuleConfig(nextConfig);
  draftConfig.value = cloneCodeReviewRuleConfig(nextConfig);
  saving.value = false;
  ElMessage.success('已保存为我的规则');
}

function handleApply() {
  const nextConfig: CodeReviewRuleConfig = {
    ...cloneCodeReviewRuleConfig(draftConfig.value),
    enabled: true,
    updatedAt: new Date().toISOString(),
  };
  saveStoredCodeReviewRuleConfig(nextConfig);
  savedConfig.value = cloneCodeReviewRuleConfig(nextConfig);
  draftConfig.value = cloneCodeReviewRuleConfig(nextConfig);
  ElMessage.success('已保存并应用到列表');
  void router.push({
    path: '/code-review/illegal-records',
    query: {
      ...route.query,
      page: '1',
    },
  });
}

function handleBack() {
  void router.push({
    path: '/code-review/illegal-records',
    query: route.query,
  });
}

function handleBeforeUnload(event: BeforeUnloadEvent) {
  if (!dirty.value) {
    return;
  }
  event.preventDefault();
  event.returnValue = '';
}

async function confirmLeaveWithUnsavedChanges() {
  if (!dirty.value) {
    return true;
  }
  try {
    await ElMessageBox.confirm(
      '当前规则有未保存修改，离开后这些修改不会保留。是否继续离开？',
      '离开规则配置',
      {
        confirmButtonText: '继续离开',
        cancelButtonText: '留在当前页',
        type: 'warning',
      },
    );
    return true;
  } catch {
    return false;
  }
}

removeLeaveGuard = router.beforeEach(async (_to, from) => {
  if (from.path !== route.path) {
    return true;
  }
  return confirmLeaveWithUnsavedChanges();
});

watch(
  draftConfig,
  () => {
    schedulePreview();
  },
  { deep: true },
);

onMounted(() => {
  window.addEventListener('beforeunload', handleBeforeUnload);
});

onBeforeUnmount(() => {
  if (previewTimer != null) {
    window.clearTimeout(previewTimer);
  }
  window.removeEventListener('beforeunload', handleBeforeUnload);
  removeLeaveGuard?.();
});

void initialize();
</script>

<template>
  <section class="rule-config-page">
    <header class="rule-config-page-head">
      <div class="rule-config-page-head-main">
        <div class="rule-config-title-row">
          <div class="rule-config-page-kicker">规则配置</div>
          <el-button class="rule-config-back" :icon="ArrowLeft" link @click="handleBack">返回列表</el-button>
        </div>
        <h1 class="rule-config-page-title">代码走查判定规则工作台</h1>
        <p class="rule-config-page-desc">
          调整个人判定规则并预览结果。保存只保留配置；应用后返回列表展示。
        </p>
      </div>
      <div class="rule-config-page-head-actions">
        <el-button :icon="RefreshLeft" @click="handleReset">恢复默认规则</el-button>
        <el-button :icon="Select" :loading="saving" @click="handleSave">保存为我的规则</el-button>
        <el-button type="primary" :icon="Setting" @click="handleApply">保存并应用到列表</el-button>
      </div>
    </header>

    <div class="rule-config-layout">
      <main class="rule-config-main">
        <div class="rule-config-context-grid">
          <section class="rule-config-context-strip" v-loading="filterLoading">
            <div class="rule-config-context-group">
              <span class="rule-config-context-title">配置状态</span>
              <span class="rule-config-status-chip" :class="{ 'is-dirty': dirty }">
                {{ dirty ? '有未保存修改' : '已保存' }}
              </span>
              <span class="rule-config-context-meta">列表：{{ savedConfig.enabled ? '我的规则' : '当前规则' }}</span>
              <span class="rule-config-context-meta">
                最近：{{ savedConfig.updatedAt ? savedConfig.updatedAt.replace('T', ' ').slice(0, 16) : '未保存' }}
              </span>
            </div>

            <div class="rule-config-context-group rule-config-scope-group">
              <span class="rule-config-context-title">数据范围</span>
              <span class="rule-config-scope-desc">{{ scopeDescription }}</span>
              <div class="rule-config-scope-tags">
                <el-tag v-for="tag in scopeTags" :key="`${tag.label}-${tag.value}`" effect="plain" class="rule-config-scope-tag">
                  {{ tag.label }}：{{ tag.value }}
                </el-tag>
                <span v-if="!scopeTags.length" class="rule-config-scope-empty">无额外限制</span>
              </div>
            </div>
          </section>
        </div>

        <section class="rule-config-panel">
          <CodeReviewRuleConfigEditor v-model="draftConfig" :fields="fields" />
        </section>
      </main>

      <aside class="rule-config-preview">
        <section class="rule-config-panel">
          <CodeReviewRuleConfigPreview :loading="previewLoading" :preview="preview" />
        </section>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.rule-config-page {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  gap: 10px;
  height: calc(100vh - 98px);
  min-height: 0;
  overflow: hidden;
}

.rule-config-title-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.rule-config-back {
  height: 26px;
  padding: 0 8px;
  border-radius: 999px;
  color: #2563eb;
  background: rgba(239, 246, 255, 0.86);
  font-size: 12px;
  font-weight: 700;
}

.rule-config-back:hover {
  color: #1d4ed8;
  background: rgba(219, 234, 254, 0.92);
}

.rule-config-back :deep(.el-icon) {
  width: 16px;
  height: 16px;
  margin-right: 3px;
  border-radius: 999px;
  background: rgba(37, 99, 235, 0.12);
}

.rule-config-page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  padding: 12px 16px;
  border-radius: 18px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  background:
    radial-gradient(circle at top left, rgba(219, 234, 254, 0.72), transparent 30%),
    linear-gradient(180deg, rgba(255, 255, 255, 0.98), rgba(248, 250, 252, 0.98));
  box-shadow:
    0 1px 2px rgba(15, 23, 42, 0.04),
    0 18px 36px rgba(15, 23, 42, 0.05);
}

.rule-config-page-head-main {
  display: grid;
  gap: 5px;
}

.rule-config-page-kicker {
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.04em;
  color: rgba(37, 99, 235, 0.86);
}

.rule-config-page-title {
  margin: 0;
  font-size: 25px;
  line-height: 1.1;
  color: #111827;
}

.rule-config-page-desc {
  margin: 0;
  max-width: 700px;
  font-size: 14px;
  line-height: 1.35;
  color: rgba(31, 41, 55, 0.7);
}

.rule-config-page-head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.rule-config-layout {
  display: grid;
  grid-template-columns: minmax(720px, 1fr) minmax(330px, 380px);
  gap: 12px;
  align-items: start;
  min-height: 0;
  overflow: hidden;
}

.rule-config-main,
.rule-config-preview {
  display: grid;
  gap: 12px;
  min-height: 0;
}

.rule-config-main {
  grid-template-rows: auto minmax(0, 1fr);
}

.rule-config-preview > .rule-config-panel {
  overflow-x: hidden;
  overflow-y: visible;
}

.rule-config-context-grid {
  display: grid;
  grid-template-columns: 1fr;
}

.rule-config-panel {
  display: grid;
  gap: 10px;
  min-width: 0;
  padding: 14px;
  border-radius: 18px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.99), rgba(249, 250, 251, 0.96));
  box-shadow:
    0 1px 2px rgba(15, 23, 42, 0.04),
    0 12px 28px rgba(15, 23, 42, 0.04);
}

.rule-config-context-strip {
  display: flex;
  align-items: center;
  gap: 14px;
  min-height: 48px;
  padding: 9px 12px;
  border-radius: 16px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  background: rgba(255, 255, 255, 0.94);
  box-shadow: 0 8px 20px rgba(15, 23, 42, 0.035);
}

.rule-config-context-group {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  white-space: nowrap;
}

.rule-config-scope-group {
  flex: 1;
}

.rule-config-context-title {
  font-size: 13px;
  font-weight: 700;
  color: #111827;
}

.rule-config-status-chip {
  padding: 3px 8px;
  border-radius: 999px;
  background: rgba(22, 163, 74, 0.1);
  color: #15803d;
  font-size: 12px;
  font-weight: 700;
}

.rule-config-status-chip.is-dirty {
  background: rgba(245, 158, 11, 0.12);
  color: #b45309;
}

.rule-config-context-meta,
.rule-config-scope-desc {
  font-size: 12px;
  color: rgba(31, 41, 55, 0.62);
}

.rule-config-scope-tags {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  overflow: hidden;
}

.rule-config-scope-tag {
  border-radius: 999px;
}

.rule-config-scope-empty {
  font-size: 12px;
  color: rgba(31, 41, 55, 0.56);
}

@media (max-width: 1180px) {
  .rule-config-page {
    height: auto;
    overflow: visible;
  }

  .rule-config-layout {
    grid-template-columns: 1fr;
  }

  .rule-config-context-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 900px) {
  .rule-config-context-strip {
    align-items: flex-start;
    flex-direction: column;
  }

  .rule-config-context-group {
    flex-wrap: wrap;
    white-space: normal;
  }
}
</style>
