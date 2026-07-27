import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import TestingPhaseDefinitionView from './TestingPhaseDefinitionView.vue';

const api = vi.hoisted(() => ({
  getIssueScopeCatalogs: vi.fn(),
  getIssueScopeGroups: vi.fn(),
  getUnassignedIssueScopeValues: vi.fn(),
  createIssueScopeGroup: vi.fn(),
  updateIssueScopeGroup: vi.fn(),
}));

vi.mock('../api', () => ({ api }));

const elementStubs = {
  ElCard: defineComponent({
    name: 'ElCard',
    template: '<section><header><slot name="header" /></header><slot /></section>',
  }),
  ElSelect: defineComponent({ name: 'ElSelect', template: '<select><slot /></select>' }),
  ElOption: defineComponent({
    name: 'ElOption',
    props: ['label', 'value'],
    template: '<option :value="value">{{ label }}</option>',
  }),
  ElInput: defineComponent({
    name: 'ElInput',
    props: ['modelValue', 'placeholder'],
    emits: ['update:modelValue', 'clear', 'keyup'],
    template: '<input :value="modelValue" :placeholder="placeholder" @input="$emit(\'update:modelValue\', $event.target.value)">',
  }),
  ElButton: defineComponent({
    name: 'ElButton',
    props: ['disabled'],
    emits: ['click'],
    template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
  }),
  ElDialog: defineComponent({
    name: 'ElDialog',
    props: ['modelValue', 'title'],
    emits: ['update:modelValue'],
    template: '<div v-if="modelValue"><h2>{{ title }}</h2><slot /><footer><slot name="footer" /></footer></div>',
  }),
  ElForm: defineComponent({ name: 'ElForm', template: '<form><slot /></form>' }),
  ElFormItem: defineComponent({
    name: 'ElFormItem',
    props: ['label'],
    template: '<label>{{ label }}<slot /></label>',
  }),
  ElSwitch: defineComponent({ name: 'ElSwitch', template: '<input type="checkbox">' }),
  ElTable: defineComponent({ name: 'ElTable', template: '<table><slot /></table>' }),
  ElTableColumn: defineComponent({ name: 'ElTableColumn', template: '<template />' }),
  ElEmpty: defineComponent({
    name: 'ElEmpty',
    props: ['description'],
    template: '<p>{{ description }}</p>',
  }),
};

function mountView() {
  return mount(TestingPhaseDefinitionView, {
    global: {
      stubs: elementStubs,
      directives: { loading: () => undefined },
    },
  });
}

const catalog = {
  id: 1,
  projectId: 9,
  projectName: 'CrownCAD',
  dimension: 'TESTING_PHASE' as const,
  dimensionName: '测试阶段',
  enabled: true,
  remark: '',
  groupCount: 1,
  unassignedValueCount: 1,
};

const group = {
  id: 11,
  catalogId: 1,
  projectId: 9,
  projectName: 'CrownCAD',
  dimension: 'TESTING_PHASE' as const,
  businessKey: 'CC2026R4',
  displayName: 'CC2026R4',
  sortOrder: 1,
  enabled: true,
  remark: '',
  issueCount: 0,
  members: [],
};

const milestoneCatalog = {
  ...catalog,
  id: 2,
  projectId: 325,
  projectName: 'CCProduct',
  dimension: 'MILESTONE' as const,
  dimensionName: '里程碑',
};

const milestoneGroup = {
  ...group,
  id: 21,
  catalogId: 2,
  projectId: 325,
  projectName: 'CCProduct',
  dimension: 'MILESTONE' as const,
  businessKey: 'CC2026R3',
  displayName: 'CC2026 R3',
};

describe('TestingPhaseDefinitionView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.getIssueScopeCatalogs.mockResolvedValue([catalog]);
    api.getIssueScopeGroups.mockResolvedValue([group]);
    api.getUnassignedIssueScopeValues.mockResolvedValue([{ value: '待配置阶段', issueCount: 1 }]);
    api.createIssueScopeGroup.mockResolvedValue(group);
  });

  it('uses the old business wording and hides storage concepts', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.text()).toContain('阶段与默认顺序');
    expect(wrapper.text()).toContain('测试阶段名称');
    expect(wrapper.text()).toContain('新增阶段');
    expect(wrapper.text()).not.toContain('稳定业务键');
    expect(wrapper.text()).not.toContain('精确事实值');
    expect(wrapper.text()).not.toContain('未纳入目录');
    expect(api.getUnassignedIssueScopeValues).not.toHaveBeenCalled();
  });

  it('creates the stable key internally from the stage name', async () => {
    const wrapper = mountView();
    await flushPromises();

    const addButton = wrapper.findAll('button').find((button) => button.text().includes('新增阶段'));
    expect(addButton).toBeDefined();
    await addButton!.trigger('click');

    const dialog = wrapper.findAllComponents({ name: 'ElDialog' })[0]!;
    const input = dialog.findComponent({ name: 'ElInput' });
    input.vm.$emit('update:modelValue', 'CC 2027 R1');
    await wrapper.vm.$nextTick();
    const saveButton = dialog.findAll('button').find((button) => button.text().includes('保存'));
    await saveButton!.trigger('click');
    await flushPromises();

    expect(api.createIssueScopeGroup).toHaveBeenCalledWith(expect.objectContaining({
      businessKey: 'CC2027R1',
      displayName: 'CC 2027 R1',
    }));
  });

  it('uses milestone wording for the customer-issue catalog', async () => {
    api.getIssueScopeCatalogs.mockResolvedValue([milestoneCatalog]);
    api.getIssueScopeGroups.mockResolvedValue([milestoneGroup]);

    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.text()).toContain('里程碑与默认顺序');
    expect(wrapper.text()).toContain('里程碑名称');
    expect(wrapper.text()).toContain('新增里程碑');
    expect(wrapper.text()).not.toContain('测试阶段名称');
  });
});
