<script setup lang="ts">
import { Expand, Fold, Lock, User } from '@element-plus/icons-vue';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
// 应用壳只负责全局导航和路由出口，业务页面状态继续留在各自模块内维护。
// 这里的登录态控制保持轻量，避免把领域页面的加载和筛选逻辑耦合进根组件。
import { ElMessage } from './element-plus-services';
import { api } from './api';
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { RouterView, useRoute, useRouter } from 'vue-router';
import {
  canAccessPageKey,
  getFirstAccessiblePagePath,
  getVisibleModules,
  moduleByKey,
  pageByKey,
  type ModuleKey,
  type PageKey,
  type ShellModule,
} from './feature-manifest';
import { shellDataScopeState } from './composables/shell-data-scope';
import { authState, loadCurrentUser, login, logout, setGuestUser } from './composables/auth-state';
import { routerState } from './router-state';
import { AUTH_REQUIRED_EVENT } from './api-client/request';
import GlobalProgressIndicator from './components/GlobalProgressIndicator.vue';

const DataScopeBar = defineAsyncComponent(() => import('./components/data-scope/DataScopeBar.vue'));

const route = useRoute();
const router = useRouter();
const loginDialogVisible = ref(false);
const SIDEBAR_COLLAPSED_STORAGE_KEY = 'platform-shell-sidebar-collapsed';
const AUTH_REQUIRED_EVENT_NAME = AUTH_REQUIRED_EVENT;
const loginForm = reactive({
  username: '',
  password: '',
});
type InputFocusTarget = { focus: () => void };
const usernameInputRef = ref<InputFocusTarget>();
const passwordInputRef = ref<InputFocusTarget>();

const currentUser = computed(() => authState.currentUser);
// 登录态切换时重新挂载当前页面，让仅在首次挂载时加载数据的业务页重新请求数据。
const pageRenderKey = computed(
  () => `${currentUser.value.authenticated ? 'authenticated' : 'guest'}:${currentUser.value.username}`,
);
const matchModeEnabled = ref(true);
const matchModeStatusLoaded = ref(false);
let matchModeStatusRequestId = 0;
const hideCodeReviewMultiBoard = computed(() => !matchModeStatusLoaded.value || matchModeEnabled.value);
const visibleModules = computed(() =>
  getVisibleModules(currentUser.value)
    .map(filterModulePagesForRuntime)
    .filter((module): module is ShellModule => module !== null),
);

const activeModule = computed(
  () => {
    const routeModule = moduleByKey.get((route.meta.moduleKey as ModuleKey | undefined) ?? 'quality-board');
    const visibleRouteModule = visibleModules.value.find((module) => module.key === routeModule?.key);
    return visibleRouteModule ?? visibleModules.value[0] ?? routeModule ?? moduleByKey.get('quality-board')!;
  },
);
const activePageKey = computed(() => String(route.meta.pageKey ?? activeModule.value.pages[0]?.key ?? ''));
const isStandalonePage = computed(() => Boolean(route.meta.standalone));
const shellDataScope = computed(() => shellDataScopeState.registration);
const authModeLabel = computed(() => {
  if (!currentUser.value.authenticated) {
    return '未登录';
  }
  return currentUser.value.displayName || currentUser.value.username;
});
const authModeTagType = computed(() => {
  return currentUser.value.authenticated ? 'success' : 'info';
});
const sidebarCollapsed = ref(readSidebarCollapsedPreference());

function filterModulePagesForRuntime(module: ShellModule): ShellModule | null {
  if (module.key !== 'code-review' || !hideCodeReviewMultiBoard.value) {
    return module;
  }
  const pages = module.pages.filter((page) => page.key !== 'code-review-multi-board');
  return pages.length ? { ...module, pages } : null;
}

function readSidebarCollapsedPreference() {
  try {
    return window.localStorage.getItem(SIDEBAR_COLLAPSED_STORAGE_KEY) === 'true';
  } catch {
    return false;
  }
}

function toggleSidebarCollapsed() {
  sidebarCollapsed.value = !sidebarCollapsed.value;
  try {
    window.localStorage.setItem(SIDEBAR_COLLAPSED_STORAGE_KEY, String(sidebarCollapsed.value));
  } catch {
    // Browser privacy modes can reject localStorage writes; the in-memory state still works.
  }
}

async function handleAuthRequired(event: Event) {
  if (!currentUser.value.authenticated) {
    return;
  }
  const message = event instanceof CustomEvent && typeof event.detail?.message === 'string'
    ? event.detail.message
    : '登录状态已过期，请重新登录';
  setGuestUser(message);
  loginDialogVisible.value = true;
  ElMessage.warning(message.includes('登录') ? message : '登录状态已过期，请重新登录');
  ensureRouteAccess();
  await nextTick();
  await focusUsernameInput();
}

function openModule(moduleKey: string) {
  const targetModule = visibleModules.value.find((module) => module.key === moduleKey);
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
  if (matchModeStatusLoaded.value && matchModeEnabled.value && pageKey === 'code-review-multi-board') {
    void router.replace('/code-review/illegal-records');
    return;
  }
  if (!pageKey || !pageByKey.has(pageKey) || canAccessPageKey(pageKey, currentUser.value)) {
    return;
  }
  void router.replace(getFirstAccessiblePagePath(currentUser.value));
}

async function loadMatchModeMenuState() {
  const requestId = ++matchModeStatusRequestId;
  matchModeStatusLoaded.value = false;
  if (!currentUser.value.authenticated || isStandalonePage.value) {
    matchModeEnabled.value = true;
    matchModeStatusLoaded.value = true;
    ensureRouteAccess();
    return;
  }
  try {
    const status = await api.getCodeReviewMatchModeStatus();
    if (requestId === matchModeStatusRequestId) {
      //兼容模式-MatchMode：兼容数据源开启时隐藏代码走查多元看板，避免和老平台临时表口径混用。
      matchModeEnabled.value = status.enabled;
    }
  } catch {
    if (requestId === matchModeStatusRequestId) {
      matchModeEnabled.value = true;
    }
  } finally {
    if (requestId === matchModeStatusRequestId) {
      matchModeStatusLoaded.value = true;
      ensureRouteAccess();
    }
  }
}

async function focusUsernameInput() {
  await nextTick();
  usernameInputRef.value?.focus();
}

async function focusPasswordInput() {
  await nextTick();
  passwordInputRef.value?.focus();
}

async function validateLoginForm() {
  if (!loginForm.username.trim()) {
    ElMessage.warning('请输入账号');
    await focusUsernameInput();
    return false;
  }
  if (!loginForm.password) {
    ElMessage.warning('请输入密码');
    await focusPasswordInput();
    return false;
  }
  return true;
}

async function handleLogin() {
  if (!(await validateLoginForm())) {
    return;
  }
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
  try {
    await logout();
    ElMessage.success('已退出登录');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '退出登录失败');
  } finally {
    loginDialogVisible.value = true;
  }
  ensureRouteAccess();
}

async function retryAuthState() {
  authState.initialized = false;
  authState.error = '';
  await loadCurrentUser();
  if (authState.status === 'unavailable') {
    ElMessage.error('登录服务暂时不可用，请稍后重试');
    return;
  }
  loginDialogVisible.value = !currentUser.value.authenticated;
}

onMounted(async () => {
  if (!authState.initialized) {
    await loadCurrentUser();
  }
  if (!currentUser.value.authenticated && authState.status !== 'unavailable') {
    loginDialogVisible.value = true;
  }
  window.addEventListener(AUTH_REQUIRED_EVENT_NAME, handleAuthRequired);
});

onBeforeUnmount(() => {
  window.removeEventListener(AUTH_REQUIRED_EVENT_NAME, handleAuthRequired);
});

watch(
  () => [currentUser.value.authenticated, currentUser.value.username, isStandalonePage.value] as const,
  () => {
    void loadMatchModeMenuState();
  },
  { immediate: true },
);

watch(
  () => [
    (currentUser.value.permissions ?? []).join(','),
    currentUser.value.authenticated,
    route.meta.pageKey,
    matchModeStatusLoaded.value,
    matchModeEnabled.value,
  ] as const,
  () => {
    ensureRouteAccess();
  },
);
</script>

<template>
  <el-config-provider :locale="zhCn" :z-index="3000">
    <div v-if="isStandalonePage" class="standalone-app-shell">
    <main class="standalone-app-main">
      <div v-if="authState.status === 'unavailable'" class="auth-service-state">
        <el-alert
          type="error"
          :closable="false"
          title="登录服务暂时不可用"
          description="请检查网络连接后重试。"
        />
        <el-button type="primary" @click="retryAuthState">重试</el-button>
      </div>
      <RouterView v-else-if="currentUser.authenticated" v-slot="{ Component }">
        <component :is="Component" :key="pageRenderKey" />
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
        <GlobalProgressIndicator />
        <el-tag v-if="routerState.routeError" size="small" type="danger" round>连接异常</el-tag>
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
          登录
        </el-button>
      </div>
    </header>

    <div class="shell-body" :class="{ 'sidebar-collapsed': sidebarCollapsed }">
      <aside class="shell-sidebar" :class="{ collapsed: sidebarCollapsed }">
        <div class="sidebar-title">
          <component :is="activeModule.icon" class="sidebar-title-icon" />
          <span v-if="!sidebarCollapsed" class="sidebar-title-text">{{ activeModule.title }}</span>
          <button
            type="button"
            class="sidebar-toggle"
            :aria-label="sidebarCollapsed ? '展开副模块栏' : '收起副模块栏'"
            :aria-expanded="!sidebarCollapsed"
            @click="toggleSidebarCollapsed"
          >
            <component :is="sidebarCollapsed ? Expand : Fold" class="sidebar-toggle-icon" />
          </button>
        </div>

        <div v-if="currentUser.authenticated && !sidebarCollapsed" class="sidebar-menu">
          <button
            v-for="page in activeModule.pages"
            :key="page.key"
            class="sidebar-menu-item"
            :class="{ active: activePageKey === page.key }"
            @click="openPage(page.path)"
          >
            <component :is="page.icon" class="sidebar-menu-item-icon" />
            <span class="sidebar-menu-item-label">{{ page.label }}</span>
          </button>
        </div>
      </aside>

      <main class="shell-content">
        <section class="content-head">
          <div class="content-head-main">
            <DataScopeBar
              v-if="currentUser.authenticated && shellDataScope"
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

        <div v-if="authState.status === 'unavailable'" class="auth-service-state">
          <el-alert
            type="error"
            :closable="false"
            title="登录服务暂时不可用"
            description="请检查网络连接后重试。"
          />
          <el-button type="primary" @click="retryAuthState">重试</el-button>
        </div>
        <RouterView v-else-if="currentUser.authenticated" v-slot="{ Component }">
          <component :is="Component" :key="pageRenderKey" />
        </RouterView>
      </main>
    </div>

    </div>

    <el-dialog
      v-model="loginDialogVisible"
      class="auth-dialog"
      width="420px"
      :show-close="false"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
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
            ref="usernameInputRef"
            v-model="loginForm.username"
            autocomplete="username"
            placeholder="请输入账号"
            size="large"
            @keydown.enter.prevent="handleLogin"
          >
            <template #prefix>
              <el-icon><User /></el-icon>
            </template>
          </el-input>
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            ref="passwordInputRef"
            v-model="loginForm.password"
            autocomplete="current-password"
            placeholder="请输入密码"
            show-password
            size="large"
            type="password"
            @keydown.enter.prevent="handleLogin"
          >
            <template #prefix>
              <el-icon><Lock /></el-icon>
            </template>
          </el-input>
        </el-form-item>
      </el-form>

      <template #footer>
        <div class="auth-footer">
          <el-button type="primary" size="large" :loading="authState.loading" @click="handleLogin">
            登录
          </el-button>
        </div>
      </template>
    </el-dialog>
  </el-config-provider>
</template>
