<script setup lang="ts">
import { ElMessage } from '../element-plus-services';
// 采集表单页面面向外部评审入口，重点是把 GitLab 上下文转成稳定的表单提交参数。
// 页面中的默认值来自路由和接口组合，提交前仍由后端做最终校验和落库。
import { computed, reactive, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { api } from '../api';
import type { CollectFormDetailResponse } from '../types/api';

const route = useRoute();

function queryText(value: unknown) {
  if (Array.isArray(value)) {
    return String(value[0] ?? '').trim();
  }
  return String(value ?? '').trim();
}

function queryNumber(value: unknown) {
  const raw = queryText(value);
  if (!raw) {
    return null;
  }
  const parsed = Number(raw);
  return Number.isFinite(parsed) ? parsed : null;
}

const context = computed(() => ({
  gitlabBaseUrl: queryText(route.query.gitlabBaseUrl),
  projectId: queryNumber(route.query.projectId),
  mrIid: queryNumber(route.query.mrIid),
}));

const formModel = reactive({
  formTitle: '代码走查表',
  reviewer: '',
  reviewDurationMinutes: 1,
  specification: 0,
  logic: 0,
  performance: 0,
  design: 0,
  other: 0,
  remark: '',
});

const loading = ref(false);
const saving = ref(false);
const deleting = ref(false);
const lastLoadedRecord = ref<CollectFormDetailResponse | null>(null);

const hasSavedRecord = computed(() => Boolean(lastLoadedRecord.value && !lastLoadedRecord.value.deleted));
const contextReady = computed(
  () => Boolean(context.value.gitlabBaseUrl) && Boolean(context.value.projectId) && Boolean(context.value.mrIid),
);

function applyRecord(record: CollectFormDetailResponse | null) {
  formModel.formTitle = record?.formTitle || '代码走查表';
  formModel.reviewer = record?.reviewer || '';
  formModel.reviewDurationMinutes = record?.reviewDurationMinutes ?? 1;
  formModel.specification = record?.specificationScore ?? 0;
  formModel.logic = record?.logicScore ?? 0;
  formModel.performance = record?.performanceScore ?? 0;
  formModel.design = record?.designScore ?? 0;
  formModel.other = record?.otherScore ?? 0;
  formModel.remark = record?.remark || '';
}

async function loadRecord() {
  if (!contextReady.value) {
    lastLoadedRecord.value = null;
    applyRecord(null);
    return;
  }
  loading.value = true;
  try {
    const record = await api.getCollectFormDetail({
      gitlabBaseUrl: context.value.gitlabBaseUrl,
      projectId: context.value.projectId!,
      resourceType: 'merge_request',
      resourceId: String(context.value.mrIid),
      templateCode: 'code_review',
    });
    lastLoadedRecord.value = record;
    applyRecord(record);
  } catch (error) {
    lastLoadedRecord.value = null;
    applyRecord(null);
    ElMessage.error(error instanceof Error ? error.message : '表单记录加载失败');
  } finally {
    loading.value = false;
  }
}

async function saveForm() {
  if (!contextReady.value) {
    ElMessage.warning('缺少必要的链接上下文参数');
    return;
  }
  saving.value = true;
  try {
    const record = await api.saveCollectForm({
      gitlabBaseUrl: context.value.gitlabBaseUrl,
      projectId: context.value.projectId!,
      requestIid: context.value.mrIid,
      resourceType: 'merge_request',
      resourceId: String(context.value.mrIid),
      templateCode: 'code_review',
      formTitle: formModel.formTitle,
      reviewer: formModel.reviewer,
      reviewDurationMinutes: formModel.reviewDurationMinutes,
      specificationScore: formModel.specification,
      logicScore: formModel.logic,
      performanceScore: formModel.performance,
      designScore: formModel.design,
      otherScore: formModel.other,
      remark: formModel.remark,
    });
    lastLoadedRecord.value = record;
    applyRecord(record);
    ElMessage.success('表单已保存到平台正式数据表');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '表单保存失败');
  } finally {
    saving.value = false;
  }
}

async function deleteForm() {
  if (!contextReady.value) {
    ElMessage.warning('缺少必要的链接上下文参数');
    return;
  }
  deleting.value = true;
  try {
    const deleted = await api.deleteCollectForm({
      gitlabBaseUrl: context.value.gitlabBaseUrl,
      projectId: context.value.projectId!,
      resourceType: 'merge_request',
      resourceId: String(context.value.mrIid),
      templateCode: 'code_review',
    });
    if (deleted) {
      await loadRecord();
    }
    ElMessage.success('表单记录已作废');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '表单删除失败');
  } finally {
    deleting.value = false;
  }
}

function resetForm() {
  applyRecord(lastLoadedRecord.value);
  ElMessage.info(hasSavedRecord.value ? '已恢复到最新保存版本' : '已恢复到空白模板');
}

watch(
  () => `${context.value.gitlabBaseUrl}|${context.value.projectId}|${context.value.mrIid}`,
  () => {
    void loadRecord();
  },
  { immediate: true },
);
</script>

<template>
  <div class="external-form-shell">
    <div class="external-form-page">
      <div class="external-form-hero">
        <div>
          <div class="external-form-eyebrow">独立表单链接入口</div>
          <h1 class="external-form-title">{{ formModel.formTitle }}</h1>
          <p class="external-form-desc">
            当前页面通过独立链接直接打开，填写结果会保存到平台正式数据表，并可在数据库查看模块中直接浏览。
          </p>
        </div>

        <div class="external-form-hero-meta">
          <el-tag type="primary" effect="plain">code_review</el-tag>
          <el-tag v-if="hasSavedRecord" type="success" effect="plain">已保存</el-tag>
          <el-tag v-else type="info" effect="plain">未保存</el-tag>
        </div>
      </div>

      <el-card class="external-form-card" shadow="never">
        <template #header>
          <div class="external-form-card-title">上下文信息</div>
        </template>

        <div class="external-context-grid">
          <div class="external-context-item">
            <span class="external-context-label">GitLab 来源地址</span>
            <span class="external-context-value">{{ context.gitlabBaseUrl || '-' }}</span>
          </div>
          <div class="external-context-item">
            <span class="external-context-label">Project ID</span>
            <span class="external-context-value">{{ context.projectId ?? '-' }}</span>
          </div>
          <div class="external-context-item">
            <span class="external-context-label">Merge Request IID</span>
            <span class="external-context-value">{{ context.mrIid ?? '-' }}</span>
          </div>
          <div class="external-context-item">
            <span class="external-context-label">最近保存时间</span>
            <span class="external-context-value">{{ lastLoadedRecord?.updatedAt || '-' }}</span>
          </div>
        </div>
      </el-card>

      <el-card v-loading="loading" class="external-form-card" shadow="never">
        <template #header>
          <div class="external-form-card-header">
            <div class="external-form-card-title">模板内容</div>
            <div class="external-form-actions">
              <el-button @click="resetForm">重置</el-button>
              <el-button :disabled="!hasSavedRecord" :loading="deleting" type="danger" plain @click="deleteForm">
                作废
              </el-button>
              <el-button :loading="saving" type="primary" @click="saveForm">保存</el-button>
            </div>
          </div>
        </template>

        <el-form label-position="top" class="external-form-layout">
          <div class="external-form-grid">
            <el-form-item label="走查人">
              <el-input v-model="formModel.reviewer" placeholder="请输入走查人" />
            </el-form-item>
            <el-form-item label="走查时间（分钟）">
              <el-input-number
                v-model="formModel.reviewDurationMinutes"
                :min="0"
                :step="1"
                controls-position="right"
              />
            </el-form-item>
          </div>

          <div class="external-score-grid">
            <el-form-item label="规范">
              <el-input-number v-model="formModel.specification" :min="0" :step="1" controls-position="right" />
            </el-form-item>
            <el-form-item label="逻辑">
              <el-input-number v-model="formModel.logic" :min="0" :step="1" controls-position="right" />
            </el-form-item>
            <el-form-item label="性能">
              <el-input-number v-model="formModel.performance" :min="0" :step="1" controls-position="right" />
            </el-form-item>
            <el-form-item label="设计">
              <el-input-number v-model="formModel.design" :min="0" :step="1" controls-position="right" />
            </el-form-item>
            <el-form-item label="其他">
              <el-input-number v-model="formModel.other" :min="0" :step="1" controls-position="right" />
            </el-form-item>
          </div>

          <el-form-item label="备注">
            <el-input v-model="formModel.remark" type="textarea" :rows="4" placeholder="请输入补充说明" />
          </el-form-item>

          <div class="external-form-footnote">
            当前表单保存后会写入平台正式业务表 <code>collect_form_records</code>，并在数据库查看模块中默认可见。
          </div>
        </el-form>
      </el-card>
    </div>
  </div>
</template>
