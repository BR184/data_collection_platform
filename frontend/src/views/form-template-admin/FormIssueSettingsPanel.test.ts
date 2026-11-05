import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus, { ElSwitch } from 'element-plus';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import FormIssueSettingsPanel from './FormIssueSettingsPanel.vue';

const api = vi.hoisted(() => ({ getIssueSettings: vi.fn(), saveIssueSettings: vi.fn() }));
vi.mock('../../api-client/collect-forms-api', () => ({ collectFormsApi: api }));
const initial = { enabled: false, enabledAt: null, linkEnabled: false, externalBaseUrl: '', revision: 0 };

describe('议题表单设置', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.getIssueSettings.mockResolvedValue({ ready: true, settings: { ...initial } });
    api.saveIssueSettings.mockImplementation(async (_scope, _project, input) => ({
      ready: true, settings: { ...input, revision: 1, enabledAt: input.enabledAt || '2026-11-05T12:00:00' },
    }));
  });

  it('在页面开启并保存立即生效配置，链接使用当前平台地址', async () => {
    const wrapper = mount(FormIssueSettingsPanel, {
      props: { scopeId: '1', projectId: '325', projectName: 'CCProduct' }, global: { plugins: [ElementPlus] },
    });
    await flushPromises();
    wrapper.findAllComponents(ElSwitch)[0].vm.$emit('update:modelValue', true);
    await flushPromises();
    wrapper.findAllComponents(ElSwitch)[1].vm.$emit('update:modelValue', true);
    await flushPromises();
    const save = wrapper.findAll('button').find(button => button.text() === '保存设置');
    await save!.trigger('click');
    await flushPromises();
    expect(api.saveIssueSettings).toHaveBeenCalledWith('1', '325', {
      enabled: true, enabledAt: null, linkEnabled: true, externalBaseUrl: window.location.origin, expectedRevision: 0,
    });
    expect(wrapper.text()).toContain('设置已保存');
    wrapper.unmount();
  });

  it('关闭已开启配置时同步关闭投放，并保留服务器冲突提示和本地输入', async () => {
    api.getIssueSettings.mockResolvedValue({ ready: true, settings: {
      ...initial, enabled: true, enabledAt: '2026-11-01T00:00:00', linkEnabled: true, revision: 4,
    } });
    api.saveIssueSettings.mockRejectedValue(new Error('配置已被其他管理员修改'));
    const wrapper = mount(FormIssueSettingsPanel, {
      props: { scopeId: '2', projectId: '325', projectName: 'CCProduct' }, global: { plugins: [ElementPlus] },
    });
    await flushPromises();
    wrapper.findAllComponents(ElSwitch)[0].vm.$emit('update:modelValue', false);
    await flushPromises();
    await wrapper.findAll('button').find(button => button.text() === '保存设置')!.trigger('click');
    await flushPromises();
    expect(api.saveIssueSettings.mock.calls[0][2]).toMatchObject({ enabled: false, linkEnabled: false, expectedRevision: 4 });
    expect(wrapper.text()).toContain('配置已被其他管理员修改');
    expect(wrapper.text()).toContain('设置尚未保存');
    wrapper.unmount();
  });
});
