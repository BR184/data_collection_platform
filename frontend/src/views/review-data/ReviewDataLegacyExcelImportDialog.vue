<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { UploadFilled } from '@element-plus/icons-vue';
import type {
  ReviewDataFilterOptionsResponse,
  ReviewDataLegacyExcelConfirmResponse,
  ReviewDataLegacyExcelImportRequest,
  ReviewDataLegacyExcelPreviewResponse,
} from '../../types/api';

const visible = defineModel<boolean>('visible', { default: false });

const props = defineProps<{
  filterOptions: ReviewDataFilterOptionsResponse;
  previewImport: (file: File, payload: ReviewDataLegacyExcelImportRequest) => Promise<ReviewDataLegacyExcelPreviewResponse>;
  confirmImport: (
    previewToken: string,
    payload?: ReviewDataLegacyExcelImportRequest,
  ) => Promise<ReviewDataLegacyExcelConfirmResponse>;
}>();

const emit = defineEmits<{
  (event: 'success', value: ReviewDataLegacyExcelConfirmResponse): void;
  (event: 'error', message: string): void;
}>();

const file = ref<File | null>(null);
const preview = ref<ReviewDataLegacyExcelPreviewResponse | null>(null);
const previewLoading = ref(false);
const confirmLoading = ref(false);
const form = reactive({
  defaultReviewDate: new Date().toISOString().slice(0, 10),
  defaultReviewOwner: '',
  defaultReviewExperts: [] as string[],
  defaultAuthorName: '',
  defaultReviewVersion: '',
  defaultProblemStatus: '已关闭',
  duplicateStrategy: 'SKIP',
});

const expertOptions = computed(() => props.filterOptions.reviewExperts);
const ownerOptions = computed(() => props.filterOptions.reviewOwners);
const statusOptions = computed(() => props.filterOptions.problemStatuses);
const previewRows = computed(() => preview.value?.rows.slice(0, 50) ?? []);
const hasImportableRows = computed(() => Boolean(preview.value && preview.value.importableRows > 0));

watch(visible, (next) => {
  if (!next) {
    reset();
  }
});

watch(
  () => ({ ...form, defaultReviewExperts: [...form.defaultReviewExperts] }),
  () => {
    preview.value = null;
  },
);

function reset() {
  file.value = null;
  preview.value = null;
  previewLoading.value = false;
  confirmLoading.value = false;
}

function handleFileChange(uploadFile: { raw?: File }) {
  file.value = uploadFile.raw ?? null;
  preview.value = null;
}

function buildImportPayload(): ReviewDataLegacyExcelImportRequest {
  return {
    defaultReviewDate: form.defaultReviewDate,
    defaultReviewOwner: form.defaultReviewOwner,
    defaultReviewExperts: form.defaultReviewExperts,
    defaultAuthorName: form.defaultAuthorName,
    defaultReviewVersion: form.defaultReviewVersion,
    defaultProblemStatus: form.defaultProblemStatus,
    duplicateStrategy: form.duplicateStrategy,
  };
}

async function handlePreview() {
  if (!file.value) {
    emit('error', '请选择旧平台导出的 Excel 文件');
    return;
  }
  previewLoading.value = true;
  try {
    preview.value = await props.previewImport(file.value, buildImportPayload());
  } catch (error) {
    emit('error', error instanceof Error ? error.message : '旧平台 Excel 解析失败');
  } finally {
    previewLoading.value = false;
  }
}

async function handleConfirm() {
  if (!preview.value?.previewToken) {
    emit('error', '请先完成预览');
    return;
  }
  confirmLoading.value = true;
  try {
    const result = await props.confirmImport(preview.value.previewToken, buildImportPayload());
    visible.value = false;
    emit('success', result);
  } catch (error) {
    emit('error', error instanceof Error ? error.message : '旧平台 Excel 导入失败');
  } finally {
    confirmLoading.value = false;
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="导入旧平台 Excel" width="920px" destroy-on-close>
    <div class="legacy-import-layout">
      <el-upload
        drag
        :auto-upload="false"
        :limit="1"
        accept=".xlsx"
        :on-change="handleFileChange"
      >
        <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
        <div class="el-upload__text">拖入旧平台导出的 Excel，或点击选择</div>
      </el-upload>

      <el-form class="legacy-import-form" label-width="118px">
        <el-form-item label="默认评审日期">
          <el-date-picker v-model="form.defaultReviewDate" value-format="YYYY-MM-DD" type="date" />
        </el-form-item>
        <el-form-item label="默认负责人">
          <el-select v-model="form.defaultReviewOwner" clearable filterable allow-create placeholder="缺失时使用">
            <el-option v-for="item in ownerOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="默认专家">
          <el-select v-model="form.defaultReviewExperts" multiple clearable filterable allow-create placeholder="缺失时使用">
            <el-option v-for="item in expertOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="默认作者">
          <el-input v-model="form.defaultAuthorName" placeholder="缺失时使用" />
        </el-form-item>
        <el-form-item label="默认版本">
          <el-input v-model="form.defaultReviewVersion" placeholder="缺失时使用所属项目" />
        </el-form-item>
        <el-form-item label="问题状态">
          <el-select v-model="form.defaultProblemStatus" filterable>
            <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
      </el-form>

      <section v-if="preview" class="legacy-import-preview">
        <div class="legacy-import-summary">
          <el-statistic title="总行数" :value="preview.totalRows" />
          <el-statistic title="可导入" :value="preview.importableRows" />
          <el-statistic title="错误行" :value="preview.errorRows" />
          <el-statistic title="预计问题项" :value="preview.estimatedProblemItemCount" />
        </div>

        <el-table :data="previewRows" height="260" size="small">
          <el-table-column prop="rowNumber" label="行" width="70" />
          <el-table-column prop="record.title" label="标题" min-width="220" show-overflow-tooltip />
          <el-table-column prop="record.projectName" label="项目" width="120" />
          <el-table-column prop="record.moduleName" label="模块" width="130" show-overflow-tooltip />
          <el-table-column prop="record.reviewType" label="评审类型" width="150" show-overflow-tooltip />
          <el-table-column label="问题项" width="90">
            <template #default="{ row }">{{ row.problemItems.length }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="row.importable ? 'success' : 'danger'" effect="plain">
                {{ row.importable ? '可导入' : '有错误' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="提示" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">{{ row.issues.map((item: any) => item.message).join('；') }}</template>
          </el-table-column>
        </el-table>
      </section>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button :loading="previewLoading" @click="handlePreview">解析预览</el-button>
      <el-button type="primary" :disabled="!hasImportableRows" :loading="confirmLoading" @click="handleConfirm">
        确认导入
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.legacy-import-layout {
  display: grid;
  gap: 16px;
}

.legacy-import-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 16px;
}

.legacy-import-form :deep(.el-form-item) {
  margin-bottom: 12px;
}

.legacy-import-form :deep(.el-select),
.legacy-import-form :deep(.el-input),
.legacy-import-form :deep(.el-date-editor) {
  width: 100%;
}

.legacy-import-preview {
  display: grid;
  gap: 12px;
}

.legacy-import-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}
</style>
