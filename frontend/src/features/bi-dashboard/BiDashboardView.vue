<script setup lang="ts">
import { Refresh } from '@element-plus/icons-vue';
import { computed, nextTick, onMounted, ref, shallowRef, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import type { PageKey } from '../../feature-manifest';
import { biDashboardApi, type BiCodingQuery } from '../../api-client/bi-dashboard-api';
import { getErrorMessage } from '../../utils/user-message';
import type {
  BiCodingPageData,
  BiPageKey,
  BiPageResponse,
  BiProductVersionCatalog,
  BiReviewPageData,
  BiSystemTestPageData,
  BiTestQualityPageData,
} from './data/types';
import { pageErrorMessage, responseMatchesPage } from './data/presentation';
import CodingStageContent from './stages/CodingStageContent.vue';
import ReviewStageContent from './stages/ReviewStageContent.vue';
import SystemTestStageContent from './stages/SystemTestStageContent.vue';
import TestQualityStageContent from './stages/TestQualityStageContent.vue';

type AnyBiPageResponse =
  | BiPageResponse<BiReviewPageData>
  | BiPageResponse<BiCodingPageData>
  | BiPageResponse<BiTestQualityPageData>
  | BiPageResponse<BiSystemTestPageData>;

interface StageConfig {
  pageKey: BiPageKey;
  label: string;
  stageName: string;
}

const STAGE_BY_ROUTE: Partial<Record<PageKey, StageConfig>> = {
  'bi-dashboard-requirements': { pageKey: 'requirements', label: '需求评审质量', stageName: '需求' },
  'bi-dashboard-design': { pageKey: 'design', label: '设计评审质量', stageName: '设计' },
  'bi-dashboard-coding': { pageKey: 'coding', label: '代码走查与研发效能', stageName: '编码' },
  'bi-dashboard-unit-test': { pageKey: 'unit-test', label: '单元测试质量达成', stageName: '单元测试' },
  'bi-dashboard-integration-test': { pageKey: 'integration-test', label: '集成测试质量达成', stageName: '集成测试' },
  'bi-dashboard-system-test': { pageKey: 'system-test', label: '系统测试质量', stageName: '系统测试' },
};

const route = useRoute();
const router = useRouter();
const catalog = ref<BiProductVersionCatalog | null>(null);
const response = shallowRef<AnyBiPageResponse | null>(null);
const loadingVersions = ref(false);
const loadingPage = ref(false);
const loadError = ref('');
let pageRequestId = 0;

const stage = computed<StageConfig>(() => STAGE_BY_ROUTE[route.meta.pageKey as PageKey] ?? STAGE_BY_ROUTE['bi-dashboard-requirements']!);
// 路由只决定当前一级阶段，数据响应仍需通过 pageKey 校验，避免切换页面时短暂显示上一页数据。
// 本层流程：从路由确定阶段，从查询参数确定版本和代码筛选条件，
// 再由请求签名触发对应 API；响应校验通过后才交给阶段内容组件渲染。
const selectedVersionId = computed<number | null>(() => {
  const value = Number(route.query.productVersionId);
  return Number.isSafeInteger(value) && value > 0 ? value : null;
});
// 导出 Excel/PNG 时需要可读的产品版本名（而非稳定 ID），从已加载的版本目录按当前选中 ID 取显示名。
const productVersionName = computed<string>(() => {
  const id = selectedVersionId.value;
  if (id == null || !catalog.value) return '';
  return catalog.value.versions.find((item) => item.id === id)?.displayName ?? '';
});
const codingFilters = computed<BiCodingQuery>(() => ({
  granularity: route.query.granularity === 'week' ? 'week' : 'day',
  source: route.query.source === 'cc' || route.query.source === 'dgm' ? route.query.source : 'all',
  repositoryId: typeof route.query.repositoryId === 'string' && route.query.repositoryId ? route.query.repositoryId : undefined,
}));
const requestSignature = computed(() => [
  stage.value.pageKey,
  selectedVersionId.value,
  stage.value.pageKey === 'coding' ? codingFilters.value.granularity : '',
  stage.value.pageKey === 'coding' ? codingFilters.value.source : '',
  stage.value.pageKey === 'coding' ? codingFilters.value.repositoryId ?? '' : '',
].join(':'));
const activeResponse = computed<AnyBiPageResponse | null>(() => responseMatchesPage(
  response.value as BiPageResponse<unknown> | null,
  stage.value.pageKey,
) ? response.value : null);
const pageFailure = computed(() => pageErrorMessage(
  activeResponse.value as BiPageResponse<unknown> | null,
));
const reviewResponse = computed(() => stage.value.pageKey === 'requirements' || stage.value.pageKey === 'design'
  ? activeResponse.value as BiPageResponse<BiReviewPageData> | null : null);
const codingResponse = computed(() => stage.value.pageKey === 'coding'
  ? activeResponse.value as BiPageResponse<BiCodingPageData> | null : null);
const testResponse = computed(() => stage.value.pageKey === 'unit-test' || stage.value.pageKey === 'integration-test'
  ? activeResponse.value as BiPageResponse<BiTestQualityPageData> | null : null);
const systemResponse = computed(() => stage.value.pageKey === 'system-test'
  ? activeResponse.value as BiPageResponse<BiSystemTestPageData> | null : null);

async function loadVersions(): Promise<void> {
  loadingVersions.value = true;
  loadError.value = '';
  try {
    // 先加载可选版本并选定稳定 ID，再发起阶段数据请求，避免用显示名称查询事实。
    catalog.value = await biDashboardApi.loadVersions();
    const requested = selectedVersionId.value;
    const validRequested = catalog.value.versions.some((item) => item.id === requested);
    const nextVersionId = validRequested ? requested : catalog.value.defaultVersionId ?? catalog.value.versions[0]?.id ?? null;
    if (nextVersionId != null && nextVersionId !== requested) {
      await updateQuery({ productVersionId: String(nextVersionId) });
    } else if (nextVersionId != null) {
      await loadPage();
    }
  } catch (error) {
    loadError.value = getErrorMessage(error, '产品版本加载失败');
  } finally {
    loadingVersions.value = false;
  }
}

let preservedScrollY: number | null = null;

async function loadPage(): Promise<void> {
  const versionId = selectedVersionId.value;
  if (versionId == null || !catalog.value) return;
  const requestId = ++pageRequestId;
  // 请求序号保证较早返回的响应不能覆盖用户随后选择的版本或阶段。
  loadingPage.value = true;
  loadError.value = '';
  try {
    const nextResponse = await loadStagePage(stage.value.pageKey, versionId);
    if (requestId === pageRequestId) response.value = nextResponse;
  } catch (error) {
    if (requestId === pageRequestId) {
      response.value = null;
      loadError.value = getErrorMessage(error, 'BI 看板加载失败');
    }
  } finally {
    if (requestId === pageRequestId) {
      loadingPage.value = false;
      if (preservedScrollY != null) {
        const y = preservedScrollY;
        preservedScrollY = null;
        void nextTick(() => {
          window.scrollTo({ top: y, behavior: 'instant' });
        });
      }
    }
  }
}

async function loadStagePage(pageKey: BiPageKey, productVersionId: number): Promise<AnyBiPageResponse> {
  // 页面调度只做阶段到 API 的映射；公式和聚合全部在后端完成，前端只负责呈现。
  switch (pageKey) {
    case 'requirements': return biDashboardApi.loadRequirements(productVersionId);
    case 'design': return biDashboardApi.loadDesign(productVersionId);
    case 'coding': return biDashboardApi.loadCoding(productVersionId, codingFilters.value);
    case 'unit-test': return biDashboardApi.loadUnitTest(productVersionId);
    case 'integration-test': return biDashboardApi.loadIntegrationTest(productVersionId);
    case 'system-test': return biDashboardApi.loadSystemTest(productVersionId);
  }
}

async function updateQuery(values: Record<string, string | undefined>): Promise<void> {
  const query = { ...route.query };
  for (const [key, value] of Object.entries(values)) {
    if (value) query[key] = value;
    else delete query[key];
  }
  await router.replace({ path: route.path, query });
}

function selectVersion(value: number): void {
  void updateQuery({ productVersionId: String(value) });
}

function setCodingFilter(key: 'granularity' | 'source', value: string | number | boolean | undefined): void {
  if (typeof value === 'string') {
    preservedScrollY = window.scrollY || document.documentElement.scrollTop || 0;
    void updateQuery({ [key]: value }).then(() => {
      if (preservedScrollY != null) {
        window.scrollTo({ top: preservedScrollY, behavior: 'instant' });
      }
    });
  }
}

watch(requestSignature, () => {
  // 版本、阶段或编码筛选发生变化时重新取整页响应，保证同一页面使用同一来源版本。
  if (catalog.value) void loadPage();
});

onMounted(() => {
  void loadVersions();
});
</script>

<template>
  <main class="bi-dashboard" data-bottom-scroll-safe="true">
    <header class="bi-page-head">
      <div class="bi-page-heading">
        <h1>{{ stage.label }}</h1>
      </div>
      <div class="bi-page-controls">
        <div v-if="stage.pageKey === 'coding'" class="bi-control-group" aria-label="编码页面筛选">
          <el-segmented
            :model-value="codingFilters.source"
            :options="[{ label: '全部来源', value: 'all' }, { label: 'CC', value: 'cc' }, { label: 'DGM', value: 'dgm' }]"
            @change="setCodingFilter('source', $event)"
          />
        </div>
        <el-select
          class="bi-version-select"
          :model-value="selectedVersionId"
          filterable
          :loading="loadingVersions"
          placeholder="选择产品版本"
          aria-label="选择产品版本"
          @change="selectVersion"
        >
          <el-option v-for="version in catalog?.versions ?? []" :key="version.id" :label="version.displayName" :value="version.id" />
        </el-select>
        <el-tooltip content="刷新当前页面" placement="top">
          <el-button :icon="Refresh" circle :loading="loadingPage" aria-label="刷新当前页面" @click="loadPage" />
        </el-tooltip>
      </div>
    </header>

    <el-alert v-if="loadError" type="error" :closable="false" show-icon :title="loadError">
      <template #default><el-button size="small" @click="loadPage">重试</el-button></template>
    </el-alert>

    <div v-else-if="loadingPage && !activeResponse" class="bi-page-loading" v-loading="true" aria-label="正在加载 BI 看板" />

    <el-result
      v-else-if="pageFailure"
      class="bi-page-failure"
      icon="error"
      title="BI 页面加载失败"
      :sub-title="pageFailure"
    >
      <template #extra>
        <el-button type="primary" :loading="loadingPage" @click="loadPage">重新加载</el-button>
      </template>
    </el-result>

    <ReviewStageContent
      v-else-if="reviewResponse"
      :response="reviewResponse"
      :product-version-id="selectedVersionId!"
      :product-version-name="productVersionName"
      :page-key="stage.pageKey"
      :stage-label="stage.stageName"
    />
    <CodingStageContent
      v-else-if="codingResponse"
      :response="codingResponse"
      :product-version-id="selectedVersionId!"
      :product-version-name="productVersionName"
      :granularity="codingFilters.granularity"
    />
    <TestQualityStageContent
      v-else-if="testResponse"
      :response="testResponse"
      :product-version-id="selectedVersionId!"
      :product-version-name="productVersionName"
      :page-key="stage.pageKey"
      :stage-label="stage.stageName"
    />
    <SystemTestStageContent v-else-if="systemResponse" :response="systemResponse" :product-version-id="selectedVersionId!" :product-version-name="productVersionName" />

    <el-empty v-else-if="!loadingVersions" description="当前没有可展示的 BI 数据" />
  </main>
</template>

<style scoped>
.bi-dashboard {
  width: 100%;
  max-width: 1680px;
  margin: 0 auto;
  /* Reserve room above browser and operating-system chrome on the supported
     1920x1080 desktop display without changing the shell scroll model. */
  --bi-dashboard-bottom-safe-space: 80px;
  padding: 0 2px var(--bi-dashboard-bottom-safe-space);
  min-width: 0;
  display: grid;
  gap: 12px;
}

.bi-page-head {
  min-height: 52px;
  padding: 0 2px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.bi-page-heading { display: flex; align-items: baseline; gap: 10px; }
.bi-page-heading span { color: #5470c6; font-size: 12px; font-weight: 650; }
.bi-page-heading h1 { margin: 0; color: #1d2939; font-size: 20px; font-weight: 700; line-height: 28px; letter-spacing: 0; }

.bi-page-controls,
.bi-control-group {
  display: flex;
  align-items: center;
  gap: 8px;
}

.bi-version-select { width: 210px; }

.bi-page-loading { min-height: 420px; }
.bi-page-failure { min-height: 420px; }

:deep(.bi-stage-stack) {
  min-width: 0;
  display: grid;
  gap: 12px;
}

:deep(.bi-grid),
:deep(.bi-charts-grid) {
  min-width: 0;
  display: grid;
  grid-template-columns: repeat(12, minmax(0, 1fr));
  gap: 12px;
  align-items: stretch;
}

@media (max-width: 1280px) {
  .bi-page-head { align-items: flex-start; }
  .bi-page-controls { flex-wrap: wrap; justify-content: flex-end; }
}

@media (max-width: 760px) {
  .bi-dashboard {
    padding: 0 0 var(--bi-dashboard-bottom-safe-space);
  }
  .bi-page-head { display: grid; gap: 10px; }
  .bi-page-controls { justify-content: flex-start; }
  .bi-version-select { width: min(100%, 240px); }
  :deep(.bi-grid),
  :deep(.bi-charts-grid) { grid-template-columns: minmax(0, 1fr); }
}
</style>
