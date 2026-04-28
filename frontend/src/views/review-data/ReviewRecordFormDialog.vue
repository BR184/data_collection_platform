<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { ElMessage } from '../../element-plus-services';
import type { FormInstance, FormRules } from 'element-plus';
import type { ReviewDataFilterOptionsResponse, ReviewDataRecordSaveRequest } from '../../types/api';
import SmartSelect from '../../components/base/SmartSelect.vue';
import type { ReviewRecordFormModel } from '../review-data-management';

const props = defineProps<{
  visible: boolean;
  saving: boolean;
  modelValue: ReviewRecordFormModel;
  filterOptions: ReviewDataFilterOptionsResponse;
  tipText?: string;
  editMode: boolean;
}>();

const emit = defineEmits<{
  (event: 'update:visible', value: boolean): void;
  (event: 'submit', value: ReviewDataRecordSaveRequest): void;
}>();

const formRef = ref<FormInstance>();
const form = reactive<ReviewRecordFormModel>({
  projectName: '',
  title: '',
  moduleName: '',
  reviewType: '',
  reviewDate: '',
  reviewOwner: '',
  reviewExperts: [],
  reviewScalePages: 0,
  reviewProduct: '',
  authorName: '',
  reviewVersion: '',
});

watch(
  () => props.modelValue,
  (value) => {
    Object.assign(form, {
      projectName: value.projectName,
      title: value.title,
      moduleName: value.moduleName,
      reviewType: value.reviewType,
      reviewDate: value.reviewDate,
      reviewOwner: value.reviewOwner,
      reviewExperts: [...value.reviewExperts],
      reviewScalePages: value.reviewScalePages,
      reviewProduct: value.reviewProduct,
      authorName: value.authorName,
      reviewVersion: value.reviewVersion,
    });
  },
  { immediate: true, deep: true },
);

const projectOptions = computed(() => props.filterOptions.projectNames);
const moduleOptions = computed(() => props.filterOptions.moduleNames);
const reviewOwnerOptions = computed(() => props.filterOptions.reviewOwners);
const reviewTypeOptions = computed(() => props.filterOptions.reviewTypes);
const expertOptions = computed(() => props.filterOptions.reviewExperts);

const rules: FormRules<ReviewRecordFormModel> = {
  projectName: [{ required: true, message: '请选择项目名称', trigger: 'change' }],
  title: [{ required: true, message: '请输入标题', trigger: 'blur' }],
  moduleName: [{ required: true, message: '请选择模块', trigger: 'change' }],
  reviewType: [{ required: true, message: '请选择评审类型', trigger: 'change' }],
  reviewDate: [{ required: true, message: '请选择评审日期', trigger: 'change' }],
  reviewOwner: [{ required: true, message: '请选择评审负责人', trigger: 'change' }],
  reviewExperts: [{ required: true, message: '请选择评审专家', trigger: 'change' }],
  reviewScalePages: [{ required: true, message: '请输入评审规模', trigger: 'change' }],
  reviewProduct: [{ required: true, message: '请输入评审的工作产品', trigger: 'blur' }],
  authorName: [{ required: true, message: '请选择作者', trigger: 'change' }],
  reviewVersion: [{ required: true, message: '请输入评审版本', trigger: 'blur' }],
};

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid) {
    ElMessage.warning('请先补全评审信息');
    return;
  }
  emit('submit', {
    projectName: form.projectName.trim(),
    title: form.title.trim(),
    moduleName: form.moduleName.trim(),
    reviewType: form.reviewType.trim(),
    reviewDate: form.reviewDate,
    reviewOwner: form.reviewOwner.trim(),
    reviewExperts: [...form.reviewExperts],
    reviewScalePages: Number(form.reviewScalePages ?? 0),
    reviewProduct: form.reviewProduct.trim(),
    authorName: form.authorName.trim(),
    reviewVersion: form.reviewVersion.trim(),
  });
}

function handleClose() {
  emit('update:visible', false);
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    :title="editMode ? '编辑评审' : '新增评审'"
    width="760px"
    destroy-on-close
    @close="handleClose"
  >
    <el-alert
      class="review-form-tip"
      type="info"
      :closable="false"
      show-icon
      title="先补齐评审主记录，保存后再到列表展开行里维护“评审问题清单”。项目、模块、负责人和专家都支持首字母搜索。"
    />
    <div v-if="tipText" class="review-form-tip-note">{{ tipText }}</div>

    <el-form ref="formRef" :model="form" :rules="rules" label-width="112px" class="review-form">
      <div class="review-form-grid">
        <el-form-item label="项目名称" prop="projectName">
          <SmartSelect v-model="form.projectName" :options="projectOptions" compact placeholder="请选择项目名称" />
        </el-form-item>
        <el-form-item label="标题" prop="title">
          <el-input v-model="form.title" placeholder="请输入标题" />
        </el-form-item>
        <el-form-item label="模块" prop="moduleName">
          <SmartSelect v-model="form.moduleName" :options="moduleOptions" compact placeholder="请选择模块" />
        </el-form-item>
        <el-form-item label="评审类型" prop="reviewType">
          <SmartSelect v-model="form.reviewType" :options="reviewTypeOptions" compact placeholder="请选择评审类型" />
        </el-form-item>
        <el-form-item label="评审日期" prop="reviewDate">
          <el-date-picker v-model="form.reviewDate" type="date" value-format="YYYY-MM-DD" placeholder="请选择评审日期" />
        </el-form-item>
        <el-form-item label="评审负责人" prop="reviewOwner">
          <SmartSelect v-model="form.reviewOwner" :options="reviewOwnerOptions" compact placeholder="请选择评审负责人" />
        </el-form-item>
        <el-form-item label="评审专家" prop="reviewExperts">
          <SmartSelect
            v-model="form.reviewExperts"
            :options="expertOptions"
            compact
            multiple
            collapse-tags
            placeholder="请选择评审专家"
          />
        </el-form-item>
        <el-form-item label="评审规模(页)" prop="reviewScalePages">
          <el-input-number v-model="form.reviewScalePages" :min="0" :step="1" class="review-form-number" />
        </el-form-item>
        <el-form-item label="评审工作产品" prop="reviewProduct">
          <el-input v-model="form.reviewProduct" placeholder="请输入评审的工作产品" />
        </el-form-item>
        <el-form-item label="作者" prop="authorName">
          <SmartSelect v-model="form.authorName" :options="expertOptions" compact placeholder="请选择作者" />
        </el-form-item>
        <el-form-item label="评审版本" prop="reviewVersion">
          <el-input v-model="form.reviewVersion" placeholder="请输入评审版本" />
        </el-form-item>
      </div>
    </el-form>

    <template #footer>
      <div class="review-form-actions">
        <el-button @click="handleClose">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSubmit">{{ editMode ? '保存修改' : '保存评审' }}</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.review-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px 16px;
}

.review-form-tip {
  margin-bottom: 16px;
}

.review-form-tip-note {
  margin: -4px 0 16px;
  padding: 10px 12px;
  border-radius: 10px;
  background: rgba(37, 99, 235, 0.06);
  font-size: 12px;
  line-height: 1.7;
  color: rgba(30, 64, 175, 0.92);
}

.review-form-number {
  width: 100%;
}

.review-form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

:deep(.el-input-number .el-input__wrapper) {
  width: 100%;
}
</style>
