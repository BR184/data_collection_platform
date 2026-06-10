import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { afterEach, describe, expect, it, vi } from 'vitest';
import BusinessTagGroupApplySelect from './BusinessTagGroupApplySelect.vue';
import { authState } from '../composables/auth-state';

function jsonResponse(data: unknown) {
  return Promise.resolve({
    ok: true,
    text: () => Promise.resolve(JSON.stringify({ success: true, data })),
  } as Response);
}

describe('BusinessTagGroupApplySelect', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    authState.currentUser = {
      username: 'guest',
      displayName: '游客',
      role: 'GUEST',
      authenticated: false,
    };
  });

  it('loads current user private business tag groups without scenario hard filtering', async () => {
    authState.currentUser = {
      username: 'admin',
      displayName: '管理员',
      role: 'ADMIN',
      authenticated: true,
    };
    const fetchSpy = vi.fn(() => jsonResponse([]));
    vi.stubGlobal('fetch', fetchSpy);

    const wrapper = mount(BusinessTagGroupApplySelect, {
      props: {
        entityType: 'issue',
        scenarioKey: 'question_metrics_home',
      },
      global: { plugins: [ElementPlus] },
    });

    await flushPromises();

    expect(fetchSpy).toHaveBeenCalledWith(
      '/api/business-tag-groups?entityType=issue&ownerUserId=admin',
      expect.any(Object),
    );

    wrapper.unmount();
  });
});
