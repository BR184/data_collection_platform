<script setup lang="ts">
import { Loading } from '@element-plus/icons-vue';
import { computed } from 'vue';
import { RouterView, useRoute, useRouter } from 'vue-router';
import DataScopeBar from './components/data-scope/DataScopeBar.vue';
import { modules, moduleByKey } from './feature-manifest';
import { shellDataScopeState } from './composables/shell-data-scope';
import { routerState } from './router-state';

const route = useRoute();
const router = useRouter();

const activeModule = computed(
  () => moduleByKey.get((route.meta.moduleKey as never) ?? 'quality-board') ?? modules[0],
);
const activePageKey = computed(() => String(route.meta.pageKey ?? activeModule.value.pages[0]?.key ?? ''));
const isStandalonePage = computed(() => Boolean(route.meta.standalone));
const shellDataScope = computed(() => shellDataScopeState.registration);

function openModule(moduleKey: string) {
  const targetModule = moduleByKey.get(moduleKey as never);
  if (!targetModule?.pages.length) {
    return;
  }
  void router.push(targetModule.pages[0].path);
}

function openPage(path: string) {
  void router.push(path);
}
</script>

<template>
  <div v-if="isStandalonePage" class="standalone-app-shell">
    <main class="standalone-app-main">
      <RouterView v-slot="{ Component }">
        <component :is="Component" />
      </RouterView>
    </main>
  </div>

  <div v-else class="app-shell">
    <header class="shell-header">
      <div class="brand-wrap">
        <div class="brand-mark">数</div>
        <div class="brand-copy">
          <div class="brand-title">数据采集平台</div>
        </div>
      </div>

      <nav class="top-nav">
        <button
          v-for="module in modules"
          :key="module.key"
          class="top-nav-item"
          :class="{ active: activeModule.key === module.key }"
          @click="openModule(module.key)"
        >
          {{ module.label }}
        </button>
      </nav>

      <div class="header-actions">
        <el-tag v-if="routerState.routeLoading" size="small" type="warning" round>页面切换中</el-tag>
        <el-tag v-else-if="routerState.routeError" size="small" type="danger" round>连接异常</el-tag>
      </div>
    </header>

    <div class="shell-body">
      <aside class="shell-sidebar">
        <div class="sidebar-title">
          <component :is="activeModule.icon" class="sidebar-title-icon" />
          <span>{{ activeModule.title }}</span>
        </div>

        <div class="sidebar-menu">
          <button
            v-for="page in activeModule.pages"
            :key="page.key"
            class="sidebar-menu-item"
            :class="{ active: activePageKey === page.key }"
            @click="openPage(page.path)"
          >
            {{ page.label }}
          </button>
        </div>
      </aside>

      <main class="shell-content">
        <section class="content-head">
          <div class="content-head-main">
            <DataScopeBar
              v-if="shellDataScope"
              :provider="shellDataScope.provider"
              :options="shellDataScope.options"
              :model-value="shellDataScope.modelValue"
              :summary="shellDataScope.summary"
              :loading="shellDataScope.loading"
              @change="shellDataScope.onChange"
            />
          </div>
          <div class="content-head-actions">
            <el-alert
              v-if="routerState.routeError"
              type="error"
              :closable="false"
              title="页面资源加载失败，当前保留基础壳子，请稍后重试。"
            />
          </div>
        </section>

        <RouterView v-slot="{ Component }">
          <component :is="Component" />
        </RouterView>

        <div v-if="routerState.routeLoading" class="route-loading-mask">
          <el-icon class="is-loading"><Loading /></el-icon>
          <span>页面切换中，请稍候</span>
        </div>
      </main>
    </div>
  </div>
</template>
