import { defineComponent, h, nextTick, ref } from 'vue';
import { mount, flushPromises } from '@vue/test-utils';
import { createRouter, createWebHashHistory } from 'vue-router';
import { ElMessage } from './element-plus-services';
import { api } from './api';
import { authState } from './composables/auth-state';
import { afterEach, describe, expect, it, vi } from 'vitest';
import App from './App.vue';
import appSource from './App.vue?raw';

function jsonResponse(data: unknown) {
  return Promise.resolve({
    ok: true,
    text: () => Promise.resolve(JSON.stringify({ success: true, data })),
  } as Response);
}

const ElDialogStub = defineComponent({
  props: {
    modelValue: Boolean,
  },
  setup(props, { slots }) {
    return () =>
      props.modelValue
        ? h('div', { class: 'el-dialog-stub' }, [slots.default?.(), slots.footer?.()])
        : null;
  },
});

const ElInputStub = defineComponent({
  props: {
    modelValue: [String, Number],
    size: String,
    type: String,
    placeholder: String,
  },
  emits: ['update:modelValue', 'keydown'],
  setup(props, { emit, expose, attrs }) {
    const inputRef = ref<HTMLInputElement | null>(null);
    expose({
      focus: () => inputRef.value?.focus(),
    });
    return () =>
      h('input', {
        ...attrs,
        ref: inputRef,
        type: props.type ?? 'text',
        placeholder: props.placeholder,
        value: props.modelValue ?? '',
        onInput: (event: Event) =>
          emit('update:modelValue', (event.target as HTMLInputElement).value),
        onKeydown: (event: KeyboardEvent) => emit('keydown', event),
      });
  },
});

const passthroughStub = defineComponent({
  setup(_, { slots }) {
    return () => h('div', slots.default?.());
  },
});

const RouterViewStub = defineComponent({
  setup(_, { slots }) {
    return () => h('div', slots.default?.({ Component: { template: '<div />' } }));
  },
});

const buttonStub = defineComponent({
  props: {
    loading: Boolean,
  },
  emits: ['click'],
  setup(_, { slots, emit }) {
    return () => h('button', { type: 'button', onClick: () => emit('click') }, slots.default?.());
  },
});

describe('App auth dialog', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    document.body.innerHTML = '';
    authState.currentUser = {
      username: 'guest',
      displayName: '游客',
      roleCodes: [],
      roleNames: [],
      permissions: [],
      authenticated: false,
    };
    authState.status = 'unknown';
    authState.loading = false;
    authState.error = '';
    authState.initialized = false;
  });

  it('prompts for password and focuses password input when pressing Enter from username', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        jsonResponse({
          username: 'guest',
          displayName: '游客',
          roleCodes: [],
          roleNames: [],
          permissions: [],
          authenticated: false,
        }),
      ),
    );
    const warningSpy = vi.spyOn(ElMessage, 'warning').mockImplementation(() => undefined as never);
    const router = createRouter({
      history: createWebHashHistory(),
      routes: [
        {
          path: '/quality-board/rd-quality-board',
          component: { template: '<div />' },
          meta: { pageKey: 'quality-board-rd-quality-board', moduleKey: 'quality-board' },
        },
      ],
    });
    await router.push('/quality-board/rd-quality-board');
    await router.isReady();

    const wrapper = mount(App, {
      attachTo: document.body,
      global: {
        plugins: [router],
        stubs: {
          RouterView: RouterViewStub,
          'el-alert': passthroughStub,
          'el-button': buttonStub,
          'el-config-provider': passthroughStub,
          'el-dialog': ElDialogStub,
          'el-form': passthroughStub,
          'el-form-item': passthroughStub,
          'el-icon': passthroughStub,
          'el-input': ElInputStub,
          'el-tag': passthroughStub,
        },
      },
    });
    await flushPromises();

    const loginButton = wrapper.findAll('button').find((button) => button.text().trim() === '登录');
    expect(loginButton).toBeTruthy();
    await loginButton!.trigger('click');
    await nextTick();

    const usernameInput = wrapper.get('input[placeholder="请输入账号"]');
    const passwordInput = wrapper.get('input[placeholder="请输入密码"]');
    await usernameInput.setValue('admin');
    await usernameInput.trigger('keydown', { key: 'Enter' });
    await nextTick();

    expect(warningSpy).toHaveBeenCalledWith('请输入密码');
    expect(document.activeElement).toBe(passwordInput.element);
    wrapper.unmount();
  });

  it('wraps the application with Element Plus Chinese locale', () => {
    expect(appSource).toContain('el-config-provider');
    expect(appSource).toContain('zhCn');
  });

  it('shows the LDAP display name instead of role names in the shell', () => {
    expect(appSource).toContain('return currentUser.value.displayName || currentUser.value.username;');
    expect(appSource).not.toContain('(currentUser.value.roleNames ?? []).join');
  });

  it('remounts the active page when the authentication session changes', () => {
    expect(appSource).toContain('const pageRenderKey = computed(');
    expect(appSource).toContain('<component :is="Component" :key="pageRenderKey" />');
  });

  it('loads match mode once per authenticated session instead of once per route', async () => {
    authState.currentUser = {
      username: 'tester',
      displayName: '测试用户',
      roleCodes: ['TESTER'],
      roleNames: ['测试用户'],
      permissions: ['quality.rd.view', 'quality.other.view'],
      authenticated: true,
    };
    authState.status = 'authenticated';
    authState.initialized = true;
    const matchModeSpy = vi.spyOn(api, 'getCodeReviewMatchModeStatus').mockResolvedValue({
      enabled: false,
      codeReviewReadMode: 'formal',
      codeReviewCompatibilityRead: false,
    });
    const router = createRouter({
      history: createWebHashHistory(),
      routes: [
        {
          path: '/quality-board/rd-quality-board',
          component: { template: '<div />' },
          meta: { pageKey: 'quality-board-rd-quality-board', moduleKey: 'quality-board' },
        },
        {
          path: '/quality-board/other-board',
          component: { template: '<div />' },
          meta: { pageKey: 'quality-board-other-board', moduleKey: 'quality-board' },
        },
      ],
    });
    await router.push('/quality-board/rd-quality-board');
    await router.isReady();
    const wrapper = mount(App, {
      global: {
        plugins: [router],
        stubs: {
          RouterView: RouterViewStub,
          'el-alert': passthroughStub,
          'el-button': buttonStub,
          'el-config-provider': passthroughStub,
          'el-dialog': ElDialogStub,
          'el-form': passthroughStub,
          'el-form-item': passthroughStub,
          'el-icon': passthroughStub,
          'el-input': ElInputStub,
          'el-tag': passthroughStub,
        },
      },
    });
    await flushPromises();

    expect(matchModeSpy).toHaveBeenCalledTimes(1);

    await router.push('/quality-board/other-board');
    await flushPromises();

    expect(matchModeSpy).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });
});
