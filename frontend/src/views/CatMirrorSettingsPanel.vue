<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { Refresh, RefreshRight } from '@element-plus/icons-vue';
import { api } from '../api';
import { ElMessage } from '../element-plus-services';
import { authState } from '../composables/auth-state';
import { hasPermission } from '../feature-manifest';
import type {
  CatMirrorCatalogNode,
  CatMirrorCatalogProject,
  CatMirrorConfig,
  CatMirrorMapping,
  CatMirrorMappingSuggestion,
  CatMirrorProductVersion,
  CatMirrorRun,
  CatMirrorSaveMapping,
  CatMirrorSettings,
} from '../types/api';
import { formatBeijingDateTime } from '../utils/beijing-time';
import { getErrorMessage } from '../utils/user-message';

const emit = defineEmits<{
  dirtyChange: [dirty: boolean];
}>();

const DEFAULT_CONFIG: CatMirrorConfig = {
  enabled: false,
  baseUrl: 'http://172.22.10.56:88',
  autoSyncEnabled: false,
  syncIntervalMinutes: 20,
  fullCompensationEnabled: true,
  fullCompensationTime: '02:00:00',
};

interface MappingRow extends CatMirrorSaveMapping {
  productVersionKey: string;
  productVersionName: string;
  suggested: boolean;
}

const loading = ref(true);
const refreshing = ref(false);
const savingConfig = ref(false);
const savingMappings = ref(false);
const testing = ref(false);
const submitting = ref(false);
const settings = ref<CatMirrorSettings | null>(null);
const config = ref<CatMirrorConfig>({ ...DEFAULT_CONFIG });
const mappingRows = ref<MappingRow[]>([]);
const savedFingerprint = ref('');
let pollingTimer: ReturnType<typeof setInterval> | undefined;

const canConfigure = computed(() => hasPermission(authState.currentUser, 'system.mirror.config'));
const canSync = computed(() => hasPermission(authState.currentUser, 'system.mirror.sync'));
const catalog = computed(() => settings.value?.catalog ?? null);
const recentRuns = computed<CatMirrorRun[]>(() => settings.value?.recentRuns ?? []);
const isSynchronizing = computed(() => Boolean(settings.value?.synchronizing));
const isDirty = computed(() => savedFingerprint.value !== '' && fingerprint() !== savedFingerprint.value);
watch(isDirty, (value) => emit('dirtyChange', value), { immediate: true });
watch(isSynchronizing, (value) => {
  if (value) startPolling();
  else stopPolling();
});

onMounted(() => loadSettings(true));
onBeforeUnmount(() => {
  stopPolling();
  emit('dirtyChange', false);
});

async function loadSettings(initial = false) {
  if (initial) loading.value = true;
  else refreshing.value = true;
  try {
    const result = await api.getCatMirrorSettings();
    settings.value = result;
    config.value = { ...DEFAULT_CONFIG, ...result.config };
    mappingRows.value = buildMappingRows(
      result.productVersions,
      result.mappings,
      result.mappingSuggestions ?? [],
    );
    savedFingerprint.value = fingerprint();
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '加载 CAT 镜像设置失败'));
  } finally {
    loading.value = false;
    refreshing.value = false;
  }
}

async function saveConfig() {
  if (!validateConfig()) return;
  savingConfig.value = true;
  try {
    config.value = await api.saveCatMirrorConfig({ ...config.value });
    savedFingerprint.value = fingerprint();
    ElMessage.success('CAT 镜像配置已保存');
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '保存 CAT 镜像配置失败'));
  } finally {
    savingConfig.value = false;
  }
}

async function saveMappings() {
  const configured = mappingRows.value.filter(hasAnyMappingValue);
  const incomplete = configured.find((row) => !isCompleteMapping(row));
  if (incomplete) {
    ElMessage.warning(`${incomplete.productVersionName} 的 CAT 映射尚未填写完整`);
    return;
  }
  savingMappings.value = true;
  try {
    const saved = await api.saveCatMirrorMappings(configured.map((row) => ({
      productVersionId: row.productVersionId,
      catProjectId: row.catProjectId,
      catVersionId: row.catVersionId,
      unitTestingPhaseId: row.unitTestingPhaseId,
      integrationTestingPhaseId: row.integrationTestingPhaseId,
    })));
    if (settings.value) {
      settings.value.mappings = saved;
      mappingRows.value = buildMappingRows(
        settings.value.productVersions,
        saved,
        settings.value.mappingSuggestions ?? [],
      );
    }
    savedFingerprint.value = fingerprint();
    ElMessage.success('CAT 产品版本映射已保存');
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '保存 CAT 产品版本映射失败'));
  } finally {
    savingMappings.value = false;
  }
}

async function testConnection() {
  testing.value = true;
  try {
    const result = await api.testCatMirrorConnection();
    ElMessage.success(result.message);
  } catch (error) {
    ElMessage.error(getErrorMessage(error, 'CAT 连接测试失败'));
  } finally {
    testing.value = false;
  }
}

async function startSync(compensation: boolean) {
  submitting.value = true;
  try {
    const result = compensation
      ? await api.startCatMirrorFullCompensationSync()
      : await api.startCatMirrorFullSync();
    if (result.accepted) {
      ElMessage.success(result.message);
    } else {
      ElMessage.info(result.message);
    }
    await loadSettings(false);
    startPolling();
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '提交 CAT 同步失败'));
  } finally {
    submitting.value = false;
  }
}

function startPolling() {
  if (pollingTimer) return;
  pollingTimer = setInterval(() => void loadSettings(false), 3000);
}

function stopPolling() {
  if (!pollingTimer) return;
  clearInterval(pollingTimer);
  pollingTimer = undefined;
}

function validateConfig() {
  if (!/^https?:\/\//i.test(config.value.baseUrl.trim())) {
    ElMessage.warning('CAT 地址必须以 http:// 或 https:// 开头');
    return false;
  }
  return true;
}

function buildMappingRows(
  versions: CatMirrorProductVersion[],
  mappings: CatMirrorMapping[],
  suggestions: CatMirrorMappingSuggestion[],
): MappingRow[] {
  const byVersion = new Map(mappings.map((item) => [item.productVersionId, item]));
  const suggestionsByVersion = new Map(
    suggestions.map((item) => [item.productVersionId, item]),
  );
  return versions.map((version) => {
    const mapping = byVersion.get(version.id);
    const suggestion = suggestionsByVersion.get(version.id);
    const source = mapping ?? suggestion;
    return {
      productVersionId: version.id,
      productVersionKey: version.businessKey,
      productVersionName: version.displayName,
      catProjectId: source?.catProjectId ?? '',
      catVersionId: source?.catVersionId ?? '',
      unitTestingPhaseId: source?.unitTestingPhaseId ?? '',
      integrationTestingPhaseId: source?.integrationTestingPhaseId ?? '',
      suggested: !mapping && Boolean(
        suggestion && (
          suggestion.catProjectId
          || suggestion.catVersionId
          || suggestion.unitTestingPhaseId
          || suggestion.integrationTestingPhaseId
        ),
      ),
    };
  });
}

function projectOptions() {
  return catalog.value?.projects ?? [];
}

function versionOptions(row: MappingRow) {
  return nodesFor(row, 'VERSION');
}

function phaseOptions(row: MappingRow) {
  return nodesFor(row, 'TEST_PHASE').filter((node) => node.versionId === row.catVersionId);
}

function nodesFor(row: MappingRow, type: CatMirrorCatalogNode['nodeType']) {
  return (catalog.value?.nodes ?? []).filter((node) =>
    node.nodeType === type && node.projectId === row.catProjectId,
  );
}

function resetProject(row: MappingRow) {
  row.suggested = false;
  row.catVersionId = '';
  row.unitTestingPhaseId = '';
  row.integrationTestingPhaseId = '';
}

function resetVersion(row: MappingRow) {
  row.suggested = false;
  row.unitTestingPhaseId = '';
  row.integrationTestingPhaseId = '';
}

function clearMapping(row: MappingRow) {
  row.suggested = false;
  row.catProjectId = '';
  resetProject(row);
}

function projectLabel(project: CatMirrorCatalogProject) {
  return project.defaultProject ? `${project.name}（默认）` : project.name;
}

function versionLabel(version: CatMirrorCatalogNode) {
  return version.currentVersion ? `${version.name}（当前）` : version.name;
}

function markMappingEdited(row: MappingRow) {
  row.suggested = false;
}

function hasAnyMappingValue(row: MappingRow) {
  return Boolean(
    row.catProjectId || row.catVersionId || row.unitTestingPhaseId || row.integrationTestingPhaseId,
  );
}

function isCompleteMapping(row: MappingRow) {
  return Boolean(
    row.catProjectId && row.catVersionId
      && row.unitTestingPhaseId && row.integrationTestingPhaseId,
  );
}

function fingerprint() {
  return JSON.stringify({
    config: config.value,
    mappings: mappingRows.value.map((row) => ({
      productVersionId: row.productVersionId,
      catProjectId: row.catProjectId,
      catVersionId: row.catVersionId,
      unitTestingPhaseId: row.unitTestingPhaseId,
      integrationTestingPhaseId: row.integrationTestingPhaseId,
    })),
  });
}

function runTypeLabel(value: CatMirrorRun['runType']) {
  return value === 'FULL_COMPENSATION' ? '全量补偿' : '全量同步';
}

function runTriggerLabel(value: CatMirrorRun['triggerType']) {
  return value === 'SCHEDULED' ? '定时' : '手动';
}

function runStatusLabel(value: CatMirrorRun['status']) {
  return {
    RUNNING: '同步中',
    SUCCEEDED: '成功',
    PARTIAL_SUCCESS: '部分成功',
    FAILED: '失败',
  }[value];
}

function runStatusType(value: CatMirrorRun['status']) {
  if (value === 'SUCCEEDED') return 'success';
  if (value === 'FAILED') return 'danger';
  if (value === 'PARTIAL_SUCCESS') return 'warning';
  return 'info';
}
</script>

<template>
  <section class="cat-mirror-panel" aria-labelledby="cat-mirror-title">
    <header class="cat-mirror-header">
      <div>
        <h2 id="cat-mirror-title">CAT 数据镜像</h2>
        <p>单元测试与集成测试只读取已发布快照；同步失败时继续使用上一份成功数据。</p>
      </div>
      <div class="cat-mirror-header__actions">
        <el-tag :type="isSynchronizing ? 'warning' : config.enabled ? 'success' : 'info'">
          {{ isSynchronizing ? '同步中' : config.enabled ? '已启用' : '未启用' }}
        </el-tag>
        <el-tooltip content="刷新 CAT 镜像状态" placement="top">
          <el-button
            circle
            :icon="Refresh"
            :loading="refreshing"
            aria-label="刷新 CAT 镜像状态"
            @click="loadSettings(false)"
          />
        </el-tooltip>
      </div>
    </header>

    <el-skeleton v-if="loading" animated :rows="7" />
    <template v-else>
      <div class="cat-mirror-config-grid">
        <el-form class="cat-mirror-form" label-position="top">
          <div class="cat-mirror-section-heading">
            <strong>连接与同步策略</strong>
            <span>当前 CAT 协议仅支持可验证的全量采集。</span>
          </div>
          <div class="cat-mirror-form-grid">
            <el-form-item label="CAT 地址" class="cat-mirror-form-item--wide">
              <el-input
                v-model="config.baseUrl"
                :disabled="!canConfigure"
                placeholder="例如 http://172.22.10.56:88"
              />
            </el-form-item>
            <el-form-item label="启用 CAT 镜像">
              <el-switch v-model="config.enabled" :disabled="!canConfigure" />
            </el-form-item>
            <el-form-item label="自动同步">
              <el-switch v-model="config.autoSyncEnabled" :disabled="!canConfigure" />
            </el-form-item>
            <el-form-item label="同步间隔（分钟）">
              <el-input-number
                v-model="config.syncIntervalMinutes"
                :min="5"
                :max="10080"
                :step="5"
                controls-position="right"
                :disabled="!canConfigure"
              />
            </el-form-item>
            <el-form-item label="每日全量补偿">
              <el-switch v-model="config.fullCompensationEnabled" :disabled="!canConfigure" />
            </el-form-item>
            <el-form-item label="补偿时间">
              <el-time-picker
                v-model="config.fullCompensationTime"
                value-format="HH:mm:ss"
                format="HH:mm"
                :clearable="false"
                :disabled="!canConfigure || !config.fullCompensationEnabled"
              />
            </el-form-item>
          </div>
          <div class="cat-mirror-actions">
            <el-button
              type="primary"
              :loading="savingConfig"
              :disabled="!canConfigure"
              @click="saveConfig"
            >保存配置</el-button>
            <el-button
              :loading="testing"
              :disabled="!canConfigure || isDirty"
              @click="testConnection"
            >测试连接</el-button>
          </div>
        </el-form>

        <div class="cat-mirror-runtime">
          <div class="cat-mirror-section-heading">
            <strong>采集与发布</strong>
            <span>目录和每个测试阶段独立校验，成功后原子切换。</span>
          </div>
          <div class="cat-mirror-runtime__facts">
            <div><span>目录快照</span><strong>{{ catalog?.snapshotId ? '已发布' : '尚未发布' }}</strong></div>
            <div><span>项目</span><strong>{{ catalog?.projects.length ?? 0 }}</strong></div>
            <div><span>版本</span><strong>{{ catalog?.nodes.filter((node) => node.nodeType === 'VERSION').length ?? 0 }}</strong></div>
            <div><span>测试阶段</span><strong>{{ catalog?.nodes.filter((node) => node.nodeType === 'TEST_PHASE').length ?? 0 }}</strong></div>
          </div>
          <div class="cat-mirror-collected-at">
            最近目录采集：{{ formatBeijingDateTime(catalog?.collectedAt, '尚未采集') }}
          </div>
          <el-alert
            v-if="!catalog"
            type="info"
            :closable="false"
            show-icon
            title="先保存并启用配置，再执行一次全量同步获取 CAT 真实目录"
          />
          <div class="cat-mirror-actions">
            <el-button
              type="primary"
              :icon="RefreshRight"
              :loading="submitting"
              :disabled="!canSync || !config.enabled || isSynchronizing || isDirty"
              @click="startSync(false)"
            >立即全量同步</el-button>
            <el-button
              :loading="submitting"
              :disabled="!canSync || !config.enabled || isSynchronizing || isDirty"
              @click="startSync(true)"
            >全量补偿</el-button>
          </div>
        </div>
      </div>

      <div class="cat-mirror-mapping">
        <div class="cat-mirror-section-heading cat-mirror-section-heading--row">
          <div>
            <strong>BI 产品版本映射</strong>
            <span>优先展示系统从 CAT 真实目录生成的唯一建议；确认保存后仍可随时手动修正。</span>
          </div>
          <el-button
            type="primary"
            plain
            :loading="savingMappings"
            :disabled="!canConfigure || !catalog"
            @click="saveMappings"
          >保存映射</el-button>
        </div>
        <el-table :data="mappingRows" empty-text="暂无可配置的 BI 产品版本">
          <el-table-column label="BI 产品版本" min-width="160" fixed="left">
            <template #default="{ row }">
              <div class="cat-version-cell">
                <strong>{{ row.productVersionName }}</strong>
                <span>{{ row.productVersionKey }}</span>
                <el-tag v-if="row.suggested" size="small" type="info">系统建议</el-tag>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="CAT 项目" min-width="210">
            <template #default="{ row }">
              <el-select
                v-model="row.catProjectId"
                filterable
                clearable
                placeholder="选择项目"
                :disabled="!canConfigure || !catalog"
                @change="resetProject(row)"
              >
                <el-option
                  v-for="project in projectOptions()"
                  :key="project.id"
                  :label="projectLabel(project)"
                  :value="project.id"
                />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="CAT 版本" min-width="190">
            <template #default="{ row }">
              <el-select
                v-model="row.catVersionId"
                filterable
                clearable
                placeholder="选择版本"
                :disabled="!canConfigure || !row.catProjectId"
                @change="resetVersion(row)"
              >
                <el-option
                  v-for="version in versionOptions(row)"
                  :key="version.id"
                  :label="versionLabel(version)"
                  :value="version.id"
                />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="单元测试阶段" min-width="210">
            <template #default="{ row }">
              <el-select
                v-model="row.unitTestingPhaseId"
                filterable
                clearable
                placeholder="选择单元测试阶段"
                :disabled="!canConfigure || !row.catVersionId"
                @change="markMappingEdited(row)"
              >
                <el-option
                  v-for="phase in phaseOptions(row)"
                  :key="phase.id"
                  :label="phase.name"
                  :value="phase.id"
                />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="集成测试阶段" min-width="210">
            <template #default="{ row }">
              <el-select
                v-model="row.integrationTestingPhaseId"
                filterable
                clearable
                placeholder="选择集成测试阶段"
                :disabled="!canConfigure || !row.catVersionId"
                @change="markMappingEdited(row)"
              >
                <el-option
                  v-for="phase in phaseOptions(row)"
                  :key="phase.id"
                  :label="phase.name"
                  :value="phase.id"
                />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="80" align="center">
            <template #default="{ row }">
              <el-button
                link
                type="primary"
                :disabled="!canConfigure || !hasAnyMappingValue(row)"
                @click="clearMapping(row)"
              >清空</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div class="cat-mirror-runs">
        <div class="cat-mirror-section-heading">
          <strong>最近同步</strong>
          <span>阶段失败不会覆盖该阶段上一份成功快照。</span>
        </div>
        <el-table :data="recentRuns" empty-text="暂无 CAT 同步记录">
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="runStatusType(row.status)">{{ runStatusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="120">
            <template #default="{ row }">{{ runTypeLabel(row.runType) }}</template>
          </el-table-column>
          <el-table-column label="触发" width="90">
            <template #default="{ row }">{{ runTriggerLabel(row.triggerType) }}</template>
          </el-table-column>
          <el-table-column label="开始时间" width="180">
            <template #default="{ row }">{{ formatBeijingDateTime(row.startedAt) }}</template>
          </el-table-column>
          <el-table-column label="发布 / 失败阶段" width="140">
            <template #default="{ row }">{{ row.publishedStageCount }} / {{ row.failedStageCount }}</template>
          </el-table-column>
          <el-table-column label="结果" min-width="320" show-overflow-tooltip prop="message" />
        </el-table>
      </div>
    </template>
  </section>
</template>

<style scoped>
.cat-mirror-panel {
  min-width: 0;
  margin-top: 16px;
  padding: 18px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-bg-color);
}

.cat-mirror-header,
.cat-mirror-section-heading--row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.cat-mirror-header h2 {
  margin: 0;
  color: var(--el-text-color-primary);
  font-size: 18px;
  line-height: 26px;
}

.cat-mirror-header p,
.cat-mirror-section-heading span,
.cat-mirror-collected-at {
  margin: 3px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 20px;
}

.cat-mirror-header__actions,
.cat-mirror-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.cat-mirror-config-grid {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(320px, 1fr);
  gap: 24px;
  margin-top: 20px;
}

.cat-mirror-form,
.cat-mirror-runtime {
  min-width: 0;
}

.cat-mirror-runtime {
  padding-inline-start: 24px;
  border-inline-start: 1px solid var(--el-border-color-lighter);
}

.cat-mirror-section-heading {
  margin-bottom: 14px;
}

.cat-mirror-section-heading strong,
.cat-version-cell strong {
  display: block;
  color: var(--el-text-color-primary);
  font-size: 14px;
  line-height: 22px;
}

.cat-mirror-form-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  column-gap: 16px;
}

.cat-mirror-form-item--wide {
  grid-column: span 2;
}

.cat-mirror-form :deep(.el-input-number),
.cat-mirror-form :deep(.el-date-editor),
.cat-mirror-mapping :deep(.el-select) {
  width: 100%;
}

.cat-mirror-runtime__facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  border-top: 1px solid var(--el-border-color-lighter);
  border-inline-start: 1px solid var(--el-border-color-lighter);
}

.cat-mirror-runtime__facts > div {
  min-width: 0;
  padding: 12px;
  border-inline-end: 1px solid var(--el-border-color-lighter);
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.cat-mirror-runtime__facts span,
.cat-version-cell span {
  display: block;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 18px;
}

.cat-mirror-runtime__facts strong {
  display: block;
  margin-top: 4px;
  color: var(--el-text-color-primary);
  font-size: 20px;
  line-height: 28px;
}

.cat-mirror-collected-at,
.cat-mirror-runtime :deep(.el-alert),
.cat-mirror-actions {
  margin-top: 14px;
}

.cat-mirror-mapping,
.cat-mirror-runs {
  margin-top: 24px;
  padding-top: 20px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.cat-version-cell {
  min-width: 0;
}

@media (max-width: 1366px) {
  .cat-mirror-config-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .cat-mirror-runtime {
    padding-inline-start: 0;
    padding-top: 20px;
    border-inline-start: 0;
    border-top: 1px solid var(--el-border-color-lighter);
  }

  .cat-mirror-form-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
