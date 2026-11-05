<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { collectFormsApi, type FormIssueSettings } from '../../api-client/collect-forms-api';
import { getErrorMessage } from '../../utils/user-message';

const props = defineProps<{ scopeId: string; projectId: string; projectName: string }>();
const settings = ref<FormIssueSettings | null>(null);
const saved = ref('');
const ready = ref(false);
const loading = ref(false);
const saving = ref(false);
const error = ref('');
const timeMode = ref<'now' | 'scheduled'>('now');
const selectedTime = computed<Date | null>({
  get: () => settings.value?.enabledAt ? new Date(settings.value.enabledAt) : null,
  set: value => { if (settings.value) settings.value.enabledAt = value ? value.toISOString() : null; },
});
let generation = 0;
const dirty = computed(() => settings.value !== null && JSON.stringify(settings.value) !== saved.value);

async function load(): Promise<void> {
  const current = ++generation;
  loading.value = true;
  error.value = '';
  settings.value = null;
  try {
    const result = await collectFormsApi.getIssueSettings(props.scopeId, props.projectId);
    if (current !== generation) return;
    settings.value = { ...result.settings, externalBaseUrl: result.settings.externalBaseUrl || window.location.origin };
    ready.value = result.ready;
    timeMode.value = settings.value.enabledAt ? 'scheduled' : 'now';
    saved.value = JSON.stringify(settings.value);
  } catch (cause) {
    if (current === generation) error.value = getErrorMessage(cause, '设置加载失败');
  } finally { if (current === generation) loading.value = false; }
}

function toggleEnabled(value: string | number | boolean): void {
  if (!settings.value) return;
  settings.value.enabled = value === true;
  if (!settings.value.enabled) settings.value.linkEnabled = false;
  else { timeMode.value = 'now'; settings.value.enabledAt = null; }
}
function changeTimeMode(): void {
  if (settings.value && timeMode.value === 'now') settings.value.enabledAt = null;
}

/** 抽屉完成按钮与本区保存共用，失败保留输入并阻止关闭。 */
async function saveIfDirty(): Promise<boolean> {
  if (loading.value || saving.value) return false;
  if (!settings.value) return false;
  if (!dirty.value) return true;
  if (settings.value.enabled && timeMode.value === 'scheduled' && !settings.value.enabledAt) {
    error.value = '请选择生效时间'; return false;
  }
  const current = generation;
  const input = { ...settings.value };
  saving.value = true;
  error.value = '';
  try {
    const { revision, ...fields } = input;
    const result = await collectFormsApi.saveIssueSettings(props.scopeId, props.projectId, { ...fields, expectedRevision: revision });
    if (current !== generation) return false;
    settings.value = result.settings;
    saved.value = JSON.stringify(settings.value);
    ready.value = result.ready;
    timeMode.value = settings.value.enabledAt ? 'scheduled' : 'now';
    return true;
  } catch (cause) {
    if (current === generation) error.value = getErrorMessage(cause, '设置保存失败');
    return false;
  } finally { if (current === generation) saving.value = false; }
}
watch(() => [props.scopeId, props.projectId], load, { immediate: true });
onBeforeUnmount(() => { generation++; });
defineExpose({ saveIfDirty });
</script>

<template>
  <section class="issue-settings" :aria-label="`${projectName}议题表单设置`">
    <h3>{{ projectName }} · 议题表单</h3>
    <p v-if="loading" class="settings-note">正在加载设置…</p>
    <template v-if="settings">
      <div class="switch-row"><label :for="`issue-enabled-${projectId}`">新议题使用表单</label><el-switch :id="`issue-enabled-${projectId}`" :model-value="settings.enabled" :disabled="saving || (!ready && !settings.enabled)" @update:model-value="toggleEnabled" /></div>
      <p v-if="!ready" class="settings-note">先发布此项目的问题响应和问题回复表单。</p>
      <template v-if="settings.enabled">
        <el-form-item label="生效时间">
          <el-radio-group v-model="timeMode" :disabled="saving" @change="changeTimeMode"><el-radio value="now">从现在开始</el-radio><el-radio value="scheduled">指定时间</el-radio></el-radio-group>
          <el-date-picker v-if="timeMode === 'scheduled'" v-model="selectedTime" type="datetime" placeholder="选择生效时间" :disabled="saving" class="settings-date" />
        </el-form-item>
        <div class="switch-row"><label :for="`issue-links-${projectId}`">自动发送填写链接</label><el-switch :id="`issue-links-${projectId}`" v-model="settings.linkEnabled" :disabled="saving" /></div>
        <el-form-item v-if="settings.linkEnabled" label="平台访问地址"><el-input v-model="settings.externalBaseUrl" :disabled="saving" placeholder="开发人员可以访问的平台地址" /></el-form-item>
      </template>
      <p class="settings-note">响应与回复共用此设置；已有议题的数据来源保持不变。</p>
      <div class="settings-actions"><span class="settings-note">{{ dirty ? '设置尚未保存' : '设置已保存' }}</span><el-button :loading="saving" :disabled="!dirty" @click="saveIfDirty">保存设置</el-button></div>
    </template>
    <p v-if="error" role="alert" class="settings-error">{{ error }} <el-button v-if="!saving" link @click="load">重新加载</el-button></p>
  </section>
</template>

<style scoped>
.issue-settings { margin-top: 24px; padding-top: 20px; border-top: 1px solid var(--el-border-color-light); }
.issue-settings h3 { margin: 0 0 16px; font-size: 15px; font-weight: 600; color: var(--el-text-color-primary); }
.switch-row { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 16px; font-size: 14px; }
.settings-note { font-size: 13px; line-height: 1.6; color: var(--el-text-color-secondary); }
.settings-date { margin-top: 8px; max-width: 100%; }
.settings-actions { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.settings-error { color: var(--el-color-danger); font-size: 13px; line-height: 1.6; }
</style>
