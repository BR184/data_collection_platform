<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
// 问题项表单弹窗同时服务新增和编辑，专家选项由当前评审记录上下文决定。
// 保存后的列表刷新留给父级流程处理，避免弹窗知道过多页面结构。
import { ElMessage } from '../../element-plus-services';
import type { FormInstance, FormRules } from 'element-plus';
import type { ReviewDataFilterOptionsResponse, ReviewDataProblemItemSaveRequest } from '../../types/api';
import SmartSelect from '../../components/base/SmartSelect.vue';
import type { ReviewProblemItemFormModel } from '../review-data-management';

const props = defineProps<{
  visible: boolean;
  saving: boolean;
  modelValue: ReviewProblemItemFormModel;
  filterOptions: ReviewDataFilterOptionsResponse;
  expertOptionsOverride?: string[];
  tipText?: string;
  editMode: boolean;
}>();

const emit = defineEmits<{
  (event: 'update:visible', value: boolean): void;
  (event: 'submit', value: ReviewDataProblemItemSaveRequest): void;
}>();

const formRef = ref<FormInstance>();
const form = reactive<ReviewProblemItemFormModel>({
  reviewerName: '',
  workloadHours: 0,
  reviewCategory: '',
  documentPosition: '',
  problemCategory: '',
  problemDescription: '',
  suggestedSolution: '',
  ownerName: '',
  rejectionReason: '',
  problemStatus: '',
});

watch(
  () => props.modelValue,
  (value) => {
    Object.assign(form, {
      reviewerName: value.reviewerName,
      workloadHours: value.workloadHours,
      reviewCategory: value.reviewCategory,
      documentPosition: value.documentPosition,
      problemCategory: value.problemCategory,
      problemDescription: value.problemDescription,
      suggestedSolution: value.suggestedSolution,
      ownerName: value.ownerName,
      rejectionReason: value.rejectionReason,
      problemStatus: value.problemStatus,
    });
  },
  { immediate: true, deep: true },
);

const reviewerOptions = computed(() =>
  (props.expertOptionsOverride ?? []).length
    ? props.expertOptionsOverride!.map((item) => ({ label: item, value: item }))
    : props.filterOptions.reviewExperts,
);
const reviewCategoryOptions = computed(() => props.filterOptions.reviewCategories);
const problemCategoryOptions = computed(() => props.filterOptions.problemCategories);
const problemStatusOptions = computed(() => props.filterOptions.problemStatuses);
const ownerOptions = computed(() => props.filterOptions.reviewOwners);

const rules: FormRules<ReviewProblemItemFormModel> = {
  reviewerName: [{ required: true, message: '请选择评审专家', trigger: 'change' }],
  workloadHours: [{ required: true, message: '请输入评审工作量', trigger: 'change' }],
  reviewCategory: [{ required: true, message: '请选择评审类别', trigger: 'change' }],
  documentPosition: [{ required: true, message: '请输入在文档中的位置', trigger: 'blur' }],
  problemCategory: [{ required: true, message: '请选择问题类别', trigger: 'change' }],
  problemDescription: [{ required: true, message: '请输入问题描述', trigger: 'blur' }],
  ownerName: [{ required: true, message: '请选择责任人', trigger: 'change' }],
  problemStatus: [{ required: true, message: '请选择问题状态', trigger: 'change' }],
};

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid) {
    ElMessage.warning('请先补全评审问题信息');
    return;
  }
  emit('submit', {
    reviewerName: form.reviewerName.trim(),
    workloadHours: Number(form.workloadHours ?? 0),
    reviewCategory: form.reviewCategory.trim(),
    documentPosition: form.documentPosition.trim(),
    problemCategory: form.problemCategory.trim(),
    problemDescription: form.problemDescription.trim(),
    suggestedSolution: form.suggestedSolution.trim(),
    ownerName: form.ownerName.trim(),
    rejectionReason: form.rejectionReason.trim(),
    problemStatus: form.problemStatus.trim(),
  });
}

function handleClose() {
  emit('update:visible', false);
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    :title="editMode ? '编辑评审问题' : '新增评审问题'"
    width="820px"
    destroy-on-close
    @close="handleClose"
  >
    <el-alert
      class="problem-form-tip"
      type="info"
      :closable="false"
      show-icon
      title="先选评审专家和问题状态，再补充位置、问题描述和建议方案。保存后会立即回写到当前评审记录下的清单。"
    />
    <div v-if="tipText" class="problem-form-tip-note">{{ tipText }}</div>

    <el-form ref="formRef" :model="form" :rules="rules" label-width="108px">
      <div class="problem-form-grid">
        <el-form-item label="评审专家" prop="reviewerName">
          <SmartSelect v-model="form.reviewerName" :options="reviewerOptions" compact placeholder="请选择评审专家" />
        </el-form-item>
        <el-form-item label="评审工作量" prop="workloadHours">
          <el-input-number v-model="form.workloadHours" :min="0" :step="0.1" class="problem-form-number" />
        </el-form-item>
        <el-form-item label="评审类别" prop="reviewCategory">
          <SmartSelect v-model="form.reviewCategory" :options="reviewCategoryOptions" compact placeholder="请选择评审类别" />
        </el-form-item>
        <el-form-item label="问题状态" prop="problemStatus">
          <SmartSelect v-model="form.problemStatus" :options="problemStatusOptions" compact placeholder="请选择问题状态" />
        </el-form-item>
        <el-form-item label="文档中的位置" prop="documentPosition" class="span-2">
          <el-input v-model="form.documentPosition" placeholder="请输入文档中的位置" />
        </el-form-item>
        <el-form-item label="问题类别" prop="problemCategory">
          <SmartSelect v-model="form.problemCategory" :options="problemCategoryOptions" compact placeholder="请选择问题类别" />
        </el-form-item>
        <el-form-item label="责任人" prop="ownerName">
          <SmartSelect v-model="form.ownerName" :options="ownerOptions" compact placeholder="请选择责任人" />
        </el-form-item>
        <el-form-item label="问题描述" prop="problemDescription" class="span-2">
          <el-input v-model="form.problemDescription" type="textarea" :rows="3" placeholder="请输入问题描述" />
        </el-form-item>
        <el-form-item label="建议解决方案" class="span-2">
          <el-input v-model="form.suggestedSolution" type="textarea" :rows="3" placeholder="请输入建议解决方案" />
        </el-form-item>
        <el-form-item label="不接受理由" class="span-2">
          <el-input v-model="form.rejectionReason" type="textarea" :rows="2" placeholder="请输入不接受理由" />
        </el-form-item>
      </div>
    </el-form>

    <template #footer>
      <div class="problem-form-actions">
        <el-button @click="handleClose">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSubmit">{{ editMode ? '保存修改' : '新增问题' }}</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.problem-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px 16px;
}

.problem-form-tip {
  margin-bottom: 16px;
}

.problem-form-tip-note {
  margin: -4px 0 16px;
  padding: 10px 12px;
  border-radius: 10px;
  background: rgba(37, 99, 235, 0.06);
  font-size: 12px;
  line-height: 1.7;
  color: rgba(30, 64, 175, 0.92);
}

.span-2 {
  grid-column: span 2;
}

.problem-form-number {
  width: 100%;
}

.problem-form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

:deep(.el-input-number .el-input__wrapper) {
  width: 100%;
}
</style>
