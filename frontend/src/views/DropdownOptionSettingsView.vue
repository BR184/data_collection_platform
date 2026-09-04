<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { ArrowDown, ArrowUp, Delete, Plus, Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from '../element-plus-services';
import { dropdownOptionApi } from '../api-client/dropdown-option-api';
import { isApiBizError } from '../api-client/request';
import { getErrorMessage } from '../utils/user-message';
import StatisticFilterBuilder from '../components/StatisticFilterBuilder.vue';
import {
  createEmptyFilterGroup,
  normalizeFilterDraftGroup,
  sanitizeFilterDraftGroup,
  type StatisticFilterDraftGroup,
} from '../components/statistic-board-filters';
import type {
  DropdownOptionFieldConfig,
  DropdownOptionFieldSummary,
  DropdownOptionRule,
  DropdownOptionRulesPayload,
  StatisticFilterField,
  StatisticFilterGroup,
} from '../types/api';

/** 下拉规则条件专用伪字段：条件一律作用于"选项值"本身。 */
const OPTION_FIELDS: StatisticFilterField[] = [
  {
    key: 'optionValue',
    label: '选项值',
    type: 'text',
    operators: ['eq', 'ne', 'contains', 'notContains', 'isEmpty', 'isNotEmpty'],
    options: [],
  },
];

interface RuleDraft {
  key: number;
  listType: 'BLACKLIST' | 'WHITELIST';
  name: string;
  group: StatisticFilterDraftGroup;
}

let ruleSeed = 0;
let previewTimer: ReturnType<typeof setTimeout> | null = null;

const fields = ref<DropdownOptionFieldSummary[]>([]);
const fieldsLoading = ref(false);
const selectedFieldKey = ref('');
const configLoading = ref(false);
const saving = ref(false);
const config = ref<DropdownOptionFieldConfig | null>(null);

const acquiredRuleDrafts = ref<RuleDraft[]>([]);
const manualRuleDrafts = ref<RuleDraft[]>([]);
const manualOptions = ref<string[]>([]);
const configVersion = ref(0);
const loadedSignature = ref('');

const acquiredCandidates = ref<string[]>([]);
const acquiredLoading = ref(false);

const selectedField = computed(() =>
  fields.value.find((item) => item.fieldKey === selectedFieldKey.value) ?? null,
);
const consumerCount = computed(() => config.value?.consumerFields.length ?? 1);
const sharedWithOthers = computed(() => consumerCount.value > 1);

/** 既有配置去重清单（来自字段绑定），供"绑定到既有配置"选择；排除当前绑定。 */
const otherConfigs = computed(() => {
  const byId = new Map<number, string>();
  for (const item of fields.value) {
    if (item.configId != null && item.configId !== config.value?.configId) {
      byId.set(item.configId, item.configLabel ?? item.displayName);
    }
  }
  return Array.from(byId, ([configId, label]) => ({ configId, label }));
});
const sharedTargetConfigId = ref<number | null>(null);

const acquiredRulesValid = computed(() => acquiredRuleDrafts.value.every((rule) => sanitizeRule(rule) !== null));
const manualRulesValid = computed(() => manualRuleDrafts.value.every((rule) => sanitizeRule(rule) !== null));
const rulesEditable = computed(() => acquiredRulesValid.value && manualRulesValid.value);

const draftSignature = computed(() => {
  const acquired = acquiredRuleDrafts.value.map((rule) => serializeRuleForSignature(rule));
  const manual = manualRuleDrafts.value.map((rule) => serializeRuleForSignature(rule));
  return JSON.stringify({ acquired, manual, manualOptions: manualOptions.value });
});
const dirty = computed(() => draftSignature.value !== loadedSignature.value);

const previewOptions = ref<string[]>([]);
const previewUnavailable = computed(() => !rulesEditable.value);

onMounted(async () => {
  await reloadFields();
  if (!selectedFieldKey.value && fields.value.length) {
    selectedFieldKey.value = fields.value[0]!.fieldKey;
  }
  await reloadConfig();
  void loadAcquiredCandidates('');
});

onBeforeUnmount(() => {
  if (previewTimer) {
    clearTimeout(previewTimer);
  }
});

watch(selectedFieldKey, () => {
  void reloadConfig();
  void loadAcquiredCandidates('');
});

watch([acquiredRuleDrafts, manualRuleDrafts, manualOptions], () => {
  if (previewTimer) {
    clearTimeout(previewTimer);
  }
  previewTimer = setTimeout(() => void refreshPreview(), 600);
}, { deep: true });

async function reloadFields() {
  fieldsLoading.value = true;
  try {
    fields.value = await dropdownOptionApi.listFields();
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '加载下拉字段清单失败'));
  } finally {
    fieldsLoading.value = false;
  }
}

async function reloadConfig() {
  if (!selectedFieldKey.value) {
    config.value = null;
    return;
  }
  configLoading.value = true;
  try {
    const loaded = await dropdownOptionApi.getFieldConfig(selectedFieldKey.value);
    applyConfig(loaded);
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '加载下拉框配置失败'));
  } finally {
    configLoading.value = false;
  }
}

function applyConfig(loaded: DropdownOptionFieldConfig) {
  config.value = loaded;
  acquiredRuleDrafts.value = toRuleDrafts(loaded.rules.acquiredRules);
  manualRuleDrafts.value = toRuleDrafts(loaded.rules.manualRules);
  manualOptions.value = [...loaded.manualOptions];
  configVersion.value = loaded.version;
  loadedSignature.value = JSON.stringify(signatureSource());
  void refreshPreview();
}

function signatureSource() {
  return {
    acquired: acquiredRuleDrafts.value.map((rule) => serializeRuleForSignature(rule)),
    manual: manualRuleDrafts.value.map((rule) => serializeRuleForSignature(rule)),
    manualOptions: manualOptions.value,
  };
}

function toRuleDrafts(rules: DropdownOptionRule[]): RuleDraft[] {
  return rules.map((rule) => {
    ruleSeed += 1;
    return {
      key: ruleSeed,
      listType: rule.listType,
      name: rule.name ?? '',
      group: normalizeFilterDraftGroup(rule.filterGroup, OPTION_FIELDS),
    };
  });
}

function addRule(target: RuleDraft[]) {
  ruleSeed += 1;
  target.push({ key: ruleSeed, listType: 'BLACKLIST', name: '', group: createEmptyFilterGroup() });
}

function removeRule(rules: RuleDraft[], key: number) {
  const index = rules.findIndex((rule) => rule.key === key);
  if (index >= 0) {
    rules.splice(index, 1);
  }
}

function moveRule(rules: RuleDraft[], index: number, offset: number) {
  const target = index + offset;
  if (target < 0 || target >= rules.length) {
    return;
  }
  const [moved] = rules.splice(index, 1);
  rules.splice(target, 0, moved!);
}

/** 草稿 → 线格式；条件不完整（未选操作符/未填值）时返回 null，由调用方拦截。 */
function sanitizeRule(rule: RuleDraft): StatisticFilterGroup | null {
  return sanitizeFilterDraftGroup(rule.group);
}

function serializeRuleForSignature(rule: RuleDraft): { listType: string; name: string; group: StatisticFilterGroup | null } {
  return {
    listType: rule.listType,
    name: rule.name.trim(),
    group: sanitizeRule(rule),
  };
}

function buildRulesPayload(): DropdownOptionRulesPayload | null {
  const invalidLabels: string[] = [];
  const serializeSet = (rules: RuleDraft[], label: string): DropdownOptionRule[] => {
    const result: DropdownOptionRule[] = [];
    for (const rule of rules) {
      const group = sanitizeRule(rule);
      if (group === null) {
        invalidLabels.push(label + (rule.name.trim() ? `「${rule.name.trim()}」` : ''));
        continue;
      }
      result.push({ listType: rule.listType, name: rule.name.trim() || null, filterGroup: group });
    }
    return result;
  };
  const acquiredRules = serializeSet(acquiredRuleDrafts.value, '自动获取值规则');
  const manualRules = serializeSet(manualRuleDrafts.value, '手动添加值规则');
  if (invalidLabels.length) {
    ElMessage.warning(`以下规则存在未填完整的条件，请补全或删除：${invalidLabels.join('、')}`);
    return null;
  }
  return { acquiredRules, manualRules };
}

async function saveConfig() {
  if (!selectedFieldKey.value) {
    return;
  }
  const rules = buildRulesPayload();
  if (rules === null) {
    return;
  }
  saving.value = true;
  try {
    const updated = await dropdownOptionApi.saveConfig(selectedFieldKey.value, {
      rules,
      manualOptions: manualOptions.value,
      version: configVersion.value,
    });
    applyConfig(updated);
    ElMessage.success('下拉框配置已保存，相关页面下次加载候选时生效');
    await reloadFields();
  } catch (error) {
    if (isApiBizError(error) && error.code === 'A0409') {
      try {
        await ElMessageBox.confirm(error.message, '配置已被他人修改', {
          type: 'warning',
          confirmButtonText: '重新加载',
          cancelButtonText: '留在当前编辑',
        });
        await reloadConfig();
      } catch {
        // 用户选择留在当前编辑。
      }
    } else {
      ElMessage.error(getErrorMessage(error, '保存下拉框配置失败'));
    }
  } finally {
    saving.value = false;
  }
}

async function bindTarget(target: 'NEW' | 'COPY') {
  if (!selectedFieldKey.value) {
    return;
  }
  if (target === 'COPY' && !config.value?.configId) {
    ElMessage.warning('当前字段尚未绑定配置，请先保存一份配置再复制拆分');
    return;
  }
  const confirmText = target === 'COPY'
    ? '将复制当前配置为新配置并绑定到本字段（原配置继续服务其他字段），确认拆分？'
    : '将新建一份空白配置并绑定到本字段，确认？';
  try {
    await ElMessageBox.confirm(confirmText, '确认改绑', { type: 'warning', confirmButtonText: '确认改绑' });
  } catch {
    return;
  }
  configLoading.value = true;
  try {
    const updated = await dropdownOptionApi.bindField(selectedFieldKey.value, { target });
    applyConfig(updated);
    ElMessage.success('字段绑定已更新');
    await reloadFields();
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '更新字段绑定失败'));
  } finally {
    configLoading.value = false;
  }
}

async function bindToExistingConfig() {
  if (!selectedFieldKey.value || sharedTargetConfigId.value == null) {
    return;
  }
  const target = otherConfigs.value.find((item) => item.configId === sharedTargetConfigId.value);
  try {
    await ElMessageBox.confirm(
      `将本字段绑定到配置「${target?.label ?? sharedTargetConfigId.value}」并与其共用，确认？`,
      '确认共用',
      { type: 'warning', confirmButtonText: '确认共用' },
    );
  } catch {
    return;
  }
  configLoading.value = true;
  try {
    const updated = await dropdownOptionApi.bindField(selectedFieldKey.value, {
      target: 'CONFIG',
      configId: sharedTargetConfigId.value,
    });
    applyConfig(updated);
    ElMessage.success('字段绑定已更新');
    await reloadFields();
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '更新字段绑定失败'));
  } finally {
    configLoading.value = false;
  }
}

async function loadAcquiredCandidates(keyword: string) {
  if (!selectedFieldKey.value) {
    acquiredCandidates.value = [];
    return;
  }
  acquiredLoading.value = true;
  try {
    acquiredCandidates.value = await dropdownOptionApi.listAcquiredOptions(selectedFieldKey.value, keyword);
  } catch (error) {
    acquiredCandidates.value = [];
    ElMessage.error(getErrorMessage(error, '搜索自动获取值失败'));
  } finally {
    acquiredLoading.value = false;
  }
}

async function refreshPreview() {
  if (!selectedFieldKey.value) {
    previewOptions.value = [];
    return;
  }
  if (previewUnavailable.value) {
    previewOptions.value = [];
    return;
  }
  const rules = buildRulesPayload();
  if (rules === null) {
    previewOptions.value = [];
    return;
  }
  try {
    const result = await dropdownOptionApi.previewOptions(selectedFieldKey.value, {
      rules,
      manualOptions: manualOptions.value,
    });
    previewOptions.value = result.finalOptions;
  } catch (error) {
    previewOptions.value = [];
    ElMessage.error(getErrorMessage(error, '预览下拉选项失败'));
  }
}
</script>

<template>
  <div class="dropdown-option-page">
    <div class="layout">
      <el-card class="fields-card" v-loading="fieldsLoading">
        <template #header><b>可配置下拉字段</b></template>
        <div class="field-list">
          <div
            v-for="item in fields"
            :key="item.fieldKey"
            class="field-row"
            :class="{ selected: item.fieldKey === selectedFieldKey }"
            @click="selectedFieldKey = item.fieldKey"
          >
            <span class="main">
              <b>{{ item.displayName }}</b>
              <small>{{ item.configured ? `已配置 · ${item.configLabel ?? ''}` : '未配置（直通现状）' }}</small>
            </span>
            <el-tag v-if="item.configured" size="small" type="success" effect="plain">已配置</el-tag>
            <el-tag v-else size="small" type="info" effect="plain">未配置</el-tag>
          </div>
          <el-empty v-if="!fieldsLoading && !fields.length" description="尚未注册可配置下拉字段" />
        </div>
      </el-card>

      <el-card class="config-card" v-loading="configLoading">
        <template #header>
          <div class="config-header">
            <div class="config-title">
              <b>{{ selectedField?.displayName ?? '下拉框选项设置' }}</b>
              <el-tag v-if="config?.configLabel" size="small" effect="plain">{{ config.configLabel }}</el-tag>
            </div>
            <div class="config-actions">
              <el-button :icon="Refresh" :disabled="saving" @click="reloadConfig">重置</el-button>
              <el-button type="primary" :icon="Plus" :loading="saving" :disabled="!selectedFieldKey || !dirty" @click="saveConfig">
                保存配置
              </el-button>
            </div>
          </div>
        </template>

        <template v-if="selectedField && config">
          <div class="consumer-banner">
            <span class="consumer-label">正在使用该配置的字段：</span>
            <el-tag v-for="consumer in config.consumerFields" :key="consumer" size="small" effect="plain">
              {{ consumer }}
            </el-tag>
            <el-tag v-if="sharedWithOthers" size="small" type="warning" effect="plain">
              多字段共用：修改将同时影响以上全部字段
            </el-tag>
          </div>

          <div class="binding-bar">
            <span class="binding-label">拆分 / 共用：</span>
            <el-button size="small" :disabled="saving" @click="bindTarget('COPY')">复制当前配置并拆分</el-button>
            <el-button size="small" :disabled="saving" @click="bindTarget('NEW')">新建空白配置</el-button>
            <el-select
              v-model="sharedTargetConfigId"
              size="small"
              class="shared-select"
              placeholder="绑定到其他字段当前使用的配置"
              :disabled="saving || !otherConfigs.length"
            >
              <el-option v-for="item in otherConfigs" :key="item.configId" :value="item.configId" :label="item.label" />
            </el-select>
            <el-button
              size="small"
              type="primary"
              plain
              :disabled="saving || sharedTargetConfigId == null"
              @click="bindToExistingConfig"
            >
              绑定
            </el-button>
          </div>

          <el-divider content-position="left">自动获取值规则（作用于 GitLab 等自动获取的候选值）</el-divider>
          <div class="rule-list">
            <div v-for="(rule, index) in acquiredRuleDrafts" :key="rule.key" class="rule-card">
              <div class="rule-head">
                <el-segmented
                  v-model="rule.listType"
                  class="rule-type"
                  :options="[
                    { label: '黑名单（命中剔除）', value: 'BLACKLIST' },
                    { label: '白名单（命中保留）', value: 'WHITELIST' },
                  ]"
                  size="small"
                />
                <el-input v-model="rule.name" class="rule-name" size="small" placeholder="规则名（可选）" maxlength="50" />
                <span class="rule-order">#{{ index + 1 }}</span>
                <span class="rule-actions">
                  <el-button link :icon="ArrowUp" :disabled="index === 0" title="上移（优先级提高）" @click="moveRule(acquiredRuleDrafts, index, -1)" />
                  <el-button link :icon="ArrowDown" :disabled="index === acquiredRuleDrafts.length - 1" title="下移" @click="moveRule(acquiredRuleDrafts, index, 1)" />
                  <el-button link type="danger" :icon="Delete" title="删除规则" @click="removeRule(acquiredRuleDrafts, rule.key)" />
                </span>
              </div>
              <StatisticFilterBuilder
                :model-value="rule.group"
                :fields="OPTION_FIELDS"
                add-button-text="添加条件"
                :show-apply-actions="false"
                :expanded="true"
              />
            </div>
            <el-button plain :icon="Plus" class="add-rule" @click="addRule(acquiredRuleDrafts)">添加自动值规则</el-button>
            <div class="rule-hint">规则自上而下生效：逐值找第一条命中的规则决定去留；只要存在白名单，未命中任何规则的值默认隐藏。</div>
          </div>

          <el-divider content-position="left">手动添加选项（自动获取值池之外的补充选项）</el-divider>
          <div class="manual-editor">
            <el-select
              v-model="manualOptions"
              multiple
              filterable
              remote
              reserve-keyword
              allow-create
              default-first-option
              fit-input-width
              :remote-method="loadAcquiredCandidates"
              :loading="acquiredLoading"
              class="manual-select"
              placeholder="输入关键词搜索已获取值并选中，或直接输入新选项后回车"
              no-data-text="暂无候选，可直接输入新选项"
            >
              <el-option v-for="item in acquiredCandidates" :key="item" :label="item" :value="item" />
            </el-select>
            <div class="manual-count">已添加 {{ manualOptions.length }} 个手动选项</div>
          </div>
          <div class="rule-list">
            <div v-for="(rule, index) in manualRuleDrafts" :key="rule.key" class="rule-card">
              <div class="rule-head">
                <el-segmented
                  v-model="rule.listType"
                  class="rule-type"
                  :options="[
                    { label: '黑名单（命中剔除）', value: 'BLACKLIST' },
                    { label: '白名单（命中保留）', value: 'WHITELIST' },
                  ]"
                  size="small"
                />
                <el-input v-model="rule.name" class="rule-name" size="small" placeholder="规则名（可选）" maxlength="50" />
                <span class="rule-order">#{{ index + 1 }}</span>
                <span class="rule-actions">
                  <el-button link :icon="ArrowUp" :disabled="index === 0" title="上移（优先级提高）" @click="moveRule(manualRuleDrafts, index, -1)" />
                  <el-button link :icon="ArrowDown" :disabled="index === manualRuleDrafts.length - 1" title="下移" @click="moveRule(manualRuleDrafts, index, 1)" />
                  <el-button link type="danger" :icon="Delete" title="删除规则" @click="removeRule(manualRuleDrafts, rule.key)" />
                </span>
              </div>
              <StatisticFilterBuilder
                :model-value="rule.group"
                :fields="OPTION_FIELDS"
                add-button-text="添加条件"
                :show-apply-actions="false"
                :expanded="true"
              />
            </div>
            <el-button plain :icon="Plus" class="add-rule" @click="addRule(manualRuleDrafts)">添加手动值规则</el-button>
            <div class="rule-hint">手动选项单独走这一套规则，与自动获取值规则互不影响；最终下拉显示 = 自动值保留结果 ∪ 手动值保留结果。</div>
          </div>

          <el-divider content-position="left">预览（保存前的最终显示效果）</el-divider>
          <div class="preview-box">
            <template v-if="previewUnavailable">
              <div class="preview-hint">存在未填写完整的规则条件，补全后即可预览。</div>
            </template>
            <template v-else-if="previewOptions.length">
              <el-tag v-for="item in previewOptions" :key="item" class="preview-chip" effect="plain">{{ item }}</el-tag>
              <div class="preview-count">共 {{ previewOptions.length }} 个选项</div>
            </template>
            <div v-else class="preview-hint">预览结果为空：按当前规则，下拉框将没有任何选项。</div>
          </div>
        </template>
        <el-empty v-else description="请选择左侧字段" />
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.dropdown-option-page {
  display: grid;
  gap: 12px;
}

.layout {
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr);
  gap: 12px;
  align-items: start;
}

.field-list {
  display: grid;
  gap: 6px;
}

.field-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 6px;
  cursor: pointer;
}

.field-row.selected {
  border-color: rgba(37, 99, 235, 0.55);
  background: rgba(37, 99, 235, 0.05);
}

.field-row .main {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.field-row .main small {
  color: rgba(15, 23, 42, 0.55);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.config-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.config-title {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex-wrap: wrap;
}

.config-actions {
  display: flex;
  gap: 8px;
}

.consumer-banner {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  padding: 8px 10px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 6px;
  background: #f8fafc;
}

.consumer-label {
  color: rgba(15, 23, 42, 0.56);
  font-size: 13px;
}

.binding-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 10px;
}

.binding-label {
  color: rgba(15, 23, 42, 0.56);
  font-size: 13px;
}

.shared-select {
  width: 280px;
}

.rule-list {
  display: grid;
  gap: 10px;
}

.rule-card {
  display: grid;
  gap: 8px;
  padding: 10px;
  border: 1px solid rgba(15, 23, 42, 0.1);
  border-radius: 6px;
  background: #fff;
}

.rule-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.rule-type {
  flex: 0 0 auto;
}

.rule-name {
  width: 200px;
}

.rule-order {
  color: rgba(15, 23, 42, 0.45);
  font-size: 12px;
}

.rule-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
}

.add-rule {
  width: fit-content;
}

.rule-hint {
  color: rgba(15, 23, 42, 0.5);
  font-size: 12px;
}

.manual-editor {
  display: grid;
  gap: 6px;
}

.manual-select {
  width: 100%;
}

.manual-count {
  color: rgba(15, 23, 42, 0.5);
  font-size: 12px;
}

.preview-box {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  padding: 10px;
  border: 1px dashed rgba(15, 23, 42, 0.16);
  border-radius: 6px;
  min-height: 44px;
}

.preview-chip {
  max-width: 320px;
}

.preview-count {
  color: rgba(15, 23, 42, 0.5);
  font-size: 12px;
}

.preview-hint {
  color: rgba(15, 23, 42, 0.5);
  font-size: 13px;
}

@media (max-width: 1100px) {
  .layout {
    grid-template-columns: 1fr;
  }
}
</style>
