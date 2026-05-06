<script setup lang="ts">
import { Lock, Loading, User } from '@element-plus/icons-vue';
// 应用壳只负责全局导航和路由出口，业务页面状态继续留在各自模块内维护。
// 这里的登录态控制保持轻量，避免把领域页面的加载和筛选逻辑耦合进根组件。
import { ElMessage } from 'element-plus';
import { computed, defineAsyncComponent, onMounted, reactive, ref, watch } from 'vue';
import { RouterView, useRoute, useRouter } from 'vue-router';
import {
  canAccessPageKey,
  getFirstAccessiblePagePath,
  getVisibleModules,
  moduleByKey,
  pageByKey,
  type ModuleKey,
  type PageKey,
} from './feature-manifest';
import { shellDataScopeState } from './composables/shell-data-scope';
import { authState, loadCurrentUser, login, logout } from './composables/auth-state';
import { routerState } from './router-state';

const DataScopeBar = defineAsyncComponent(() => import('./components/data-scope/DataScopeBar.vue'));

const route = useRoute();
const router = useRouter();
const loginDialogVisible = ref(false);
const loginForm = reactive({
  username: '',
  password: '',
});

const currentUser = computed(() => authState.currentUser);
const visibleModules = computed(() => getVisibleModules(currentUser.value));

const activeModule = computed(
  () => {
    const routeModule = moduleByKey.get((route.meta.moduleKey as ModuleKey | undefined) ?? 'quality-board');
    const visibleRouteModule = visibleModules.value.find((module) => module.key === routeModule?.key);
    return visibleRouteModule ?? visibleModules.value[0];
  },
);
const activePageKey = computed(() => String(route.meta.pageKey ?? activeModule.value.pages[0]?.key ?? ''));
const isStandalonePage = computed(() => Boolean(route.meta.standalone));
const shellDataScope = computed(() => shellDataScopeState.registration);
const authModeLabel = computed(() => {
  if (currentUser.value.role === 'ADMIN') {
    return '管理员模式';
  }
  if (currentUser.value.role === 'APPROVAL') {
    return '审批模式';
  }
  return '游客模式';
});
const authModeTagType = computed(() => {
  if (currentUser.value.role === 'ADMIN') {
    return 'success';
  }
  if (currentUser.value.role === 'APPROVAL') {
    return 'warning';
  }
  return 'info';
});

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

function ensureRouteAccess() {
  if (isStandalonePage.value) {
    return;
  }
  const pageKey = route.meta.pageKey as PageKey | undefined;
  if (!pageKey || !pageByKey.has(pageKey)) {
    return;
  }
  if (canAccessPageKey(pageKey, currentUser.value)) {
    return;
  }
  void router.replace(getFirstAccessiblePagePath(currentUser.value));
}

async function handleLogin() {
  try {
    await login(loginForm.username, loginForm.password);
    loginDialogVisible.value = false;
    loginForm.password = '';
    ElMessage.success('登录成功');
    ensureRouteAccess();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '登录失败');
  }
}

async function handleLogout() {
  await logout();
  ElMessage.success('已退出登录');
  ensureRouteAccess();
}

onMounted(async () => {
  await loadCurrentUser();
  ensureRouteAccess();
});

watch(
  () => [route.path, currentUser.value.role, currentUser.value.authenticated] as const,
  () => ensureRouteAccess(),
);
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
        <div class="brand-mark">
          <img src="/crowncad-logo.png" alt="CrownCAD" />
        </div>
        <div class="brand-copy">
          <div class="brand-title">数据采集平台</div>
        </div>
      </div>

      <nav class="top-nav">
        <button
          v-for="module in visibleModules"
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
        <el-tag :type="authModeTagType" size="small" round>{{ authModeLabel }}</el-tag>
        <el-button
          v-if="currentUser.authenticated"
          size="small"
          :loading="authState.loading"
          @click="handleLogout"
        >
          退出
        </el-button>
        <el-button
          v-else
          size="small"
          type="primary"
          :loading="authState.loading"
          @click="loginDialogVisible = true"
        >
          管理员登录
        </el-button>
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

    <el-dialog
      v-model="loginDialogVisible"
      class="auth-dialog"
      width="420px"
      :show-close="false"
      align-center
    >
      <div class="auth-card-head">
        <div class="auth-brand-mark">
          <img src="/crowncad-logo.png" alt="CrownCAD" />
        </div>
        <div class="auth-title-group">
          <div class="auth-title">数据采集平台</div>
          <div class="auth-subtitle">权限登录</div>
        </div>
      </div>

      <el-form class="auth-form" label-position="top" @submit.prevent="handleLogin">
        <el-form-item label="账号">
          <el-input
            v-model="loginForm.username"
            autocomplete="username"
            placeholder="请输入账号"
            size="large"
          >
            <template #prefix>
              <el-icon><User /></el-icon>
            </template>
          </el-input>
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="loginForm.password"
            autocomplete="current-password"
            placeholder="请输入密码"
            show-password
            size="large"
            type="password"
            @keydown.enter="handleLogin"
          >
            <template #prefix>
              <el-icon><Lock /></el-icon>
            </template>
          </el-input>
        </el-form-item>
      </el-form>

      <div class="auth-mode-note">
        <span>当前为游客模式</span>
        <span>登录后显示管理入口</span>
      </div>

      <template #footer>
        <div class="auth-footer">
          <el-button size="large" @click="loginDialogVisible = false">取消</el-button>
          <el-button type="primary" size="large" :loading="authState.loading" @click="handleLogin">
            登录
          </el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>
