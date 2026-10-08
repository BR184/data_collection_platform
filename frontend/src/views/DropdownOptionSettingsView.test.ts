import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { defineComponent } from 'vue';
import type { DropdownOptionFieldConfig } from '../types/api';

const mocks = vi.hoisted(() => ({
  listFields: vi.fn(),
  getFieldConfig: vi.fn(),
  listAcquiredOptions: vi.fn(),
  previewOptions: vi.fn(),
  saveConfig: vi.fn(),
  bindField: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
  warning: vi.fn(),
  confirm: vi.fn(),
}));

vi.mock('../api-client/dropdown-option-api', () => ({
  dropdownOptionApi: {
    listFields: mocks.listFields,
    getFieldConfig: mocks.getFieldConfig,
    listAcquiredOptions: mocks.listAcquiredOptions,
    previewOptions: mocks.previewOptions,
    saveConfig: mocks.saveConfig,
    bindField: mocks.bindField,
  },
}));

vi.mock('../api-client/request', () => ({ isApiBizError: vi.fn(() => false) }));
vi.mock('../element-plus-services', () => ({
  ElMessage: { success: mocks.success, error: mocks.error, warning: mocks.warning },
  ElMessageBox: { confirm: mocks.confirm },
}));

import DropdownOptionSettingsView from './DropdownOptionSettingsView.vue';

const fieldA = 'test.field-a';
const fieldB = 'test.field-b';

function config(fieldKey: string, configId: number, option: string): DropdownOptionFieldConfig {
  return {
    fieldKey,
    displayName: fieldKey,
    configId,
    configLabel: `config-${configId}`,
    rules: { acquiredRules: [], manualRules: [] },
    manualOptions: [option],
    version: 7,
    consumerFields: [fieldKey],
  };
}

/** 带双套规则的字段配置：用于断言页面下发给筛选组件的定制契约。 */
function configWithRules(fieldKey: string): DropdownOptionFieldConfig {
  return {
    fieldKey,
    displayName: fieldKey,
    configId: 1,
    configLabel: 'config-1',
    rules: {
      acquiredRules: [
        {
          listType: 'BLACKLIST',
          name: '剔除测试',
          filterGroup: { logic: 'AND', conditions: [{ fieldKey: 'optionValue', operator: 'eq', value: 'X' }] },
        },
      ],
      manualRules: [
        {
          listType: 'WHITELIST',
          name: '',
          filterGroup: { logic: 'OR', conditions: [{ fieldKey: 'optionValue', operator: 'contains', value: '2026' }] },
        },
      ],
    },
    manualOptions: [],
    version: 7,
    consumerFields: [fieldKey],
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

const ElCardStub = defineComponent({
  name: 'ElCardStub',
  template: '<section><header><slot name="header" /></header><slot /></section>',
});

const ElButtonStub = defineComponent({
  name: 'ElButtonStub',
  props: { disabled: Boolean, loading: Boolean },
  emits: ['click'],
  template: '<button :disabled="disabled || loading" @click="$emit(\'click\')"><slot /></button>',
});

const ElSelectStub = defineComponent({
  name: 'ElSelectStub',
  props: {
    modelValue: { type: [Array, String, Number], default: () => [] },
    multiple: Boolean,
    remoteMethod: Function,
    loading: Boolean,
  },
  emits: ['update:modelValue'],
  template: '<div><input :value="Array.isArray(modelValue) ? modelValue.join(\',\') : modelValue" @input="$emit(\'update:modelValue\', multiple ? [$event.target.value] : $event.target.value)" /><slot /></div>',
});

const ElOptionStub = defineComponent({
  name: 'ElOptionStub',
  props: { label: String, value: [String, Number] },
  template: '<span class="option-stub">{{ label }}</span>',
});

const ElInputStub = defineComponent({
  name: 'ElInputStub',
  props: { modelValue: String },
  emits: ['update:modelValue'],
  template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
});

const ElTagStub = defineComponent({ name: 'ElTagStub', template: '<span><slot /></span>' });
const ElSegmentedStub = defineComponent({
  name: 'ElSegmentedStub',
  props: { modelValue: String },
  emits: ['update:modelValue'],
  template: '<span />',
});

// 断言页面下发给筛选组件的定制契约：关系清单、文案覆盖与单条件隐藏开关。
const StatisticFilterBuilderStub = defineComponent({
  name: 'StatisticFilterBuilderStub',
  props: {
    modelValue: { type: Object, default: () => ({ logic: 'AND', conditions: [] }) },
    fields: { type: Array, default: () => [] },
    labelGroupOperatorOptions: { type: Array, default: () => [] },
    operatorLabels: { type: Object, default: () => ({}) },
    hideLogicSelectorWhenSingleCondition: Boolean,
  },
  template: '<div class="filter-builder-stub" />',
});

const stubs = {
  'el-card': ElCardStub,
  'el-button': ElButtonStub,
  'el-select': ElSelectStub,
  'el-option': ElOptionStub,
  'el-input': ElInputStub,
  'el-tag': ElTagStub,
  'el-segmented': ElSegmentedStub,
  'el-divider': true,
  'el-empty': true,
  StatisticFilterBuilder: StatisticFilterBuilderStub,
};

let wrapper: VueWrapper | null = null;

function mountView() {
  const mounted = mount(DropdownOptionSettingsView, { global: { stubs } });
  wrapper = mounted;
  return mounted;
}

async function settle() {
  await flushPromises();
  await flushPromises();
}

beforeEach(() => {
  vi.clearAllMocks();
  mocks.listFields.mockResolvedValue([
    { fieldKey: fieldA, displayName: fieldA, configured: true, configId: 1, configLabel: 'A' },
    { fieldKey: fieldB, displayName: fieldB, configured: true, configId: 2, configLabel: 'B' },
  ]);
  mocks.getFieldConfig.mockResolvedValue(config(fieldA, 1, 'initial'));
  mocks.listAcquiredOptions.mockResolvedValue([]);
  mocks.previewOptions.mockResolvedValue({ finalOptions: [] });
  mocks.saveConfig.mockImplementation((_fieldKey: string, payload: { configId: number | null }) =>
    Promise.resolve(config(fieldA, payload.configId ?? 77, 'saved')));
  mocks.bindField.mockResolvedValue(config(fieldA, 77, 'bound'));
  mocks.confirm.mockResolvedValue(undefined);
});

afterEach(() => {
  wrapper?.unmount();
  wrapper = null;
  vi.useRealTimers();
});

describe('DropdownOptionSettingsView config session', () => {
  it('saves the active A session identity after A-to-B-to-A loads resolve out of order', async () => {
    const requests: ReturnType<typeof deferred<DropdownOptionFieldConfig>>[] = [];
    mocks.getFieldConfig.mockImplementation(() => {
      const request = deferred<DropdownOptionFieldConfig>();
      requests.push(request);
      return request.promise;
    });
    const view = mountView();
    await settle();
    expect(requests).toHaveLength(1);

    await view.findAll('.field-row')[1]!.trigger('click');
    await settle();
    await view.findAll('.field-row')[0]!.trigger('click');
    await settle();
    expect(requests).toHaveLength(3);

    requests[2]!.resolve(config(fieldA, 103, 'active-A'));
    requests[1]!.resolve(config(fieldB, 202, 'stale-B'));
    requests[0]!.resolve(config(fieldA, 101, 'stale-A'));
    await settle();

    const manualInput = view.get('.manual-select input');
    expect((manualInput.element as HTMLInputElement).value).toBe('active-A');
    await manualInput.setValue('edited-A');
    const saveButton = view.findAll('button').find((button) => button.text().includes('保存配置'))!;
    await saveButton.trigger('click');
    await settle();

    expect(mocks.saveConfig).toHaveBeenCalledWith(fieldA, expect.objectContaining({
      configId: 103,
      version: 7,
      manualOptions: ['edited-A'],
    }));
  });

  it('keeps only the newest acquired-candidate search result', async () => {
    const older = deferred<string[]>();
    const newer = deferred<string[]>();
    mocks.listAcquiredOptions.mockImplementation((_fieldKey: string, keyword: string) => {
      if (keyword === 'old') return older.promise;
      if (keyword === 'new') return newer.promise;
      return Promise.resolve([]);
    });
    const view = mountView();
    await settle();
    const manualSelect = view.findAllComponents(ElSelectStub)
      .find((select) => select.classes().includes('manual-select'))!;
    const search = manualSelect.props('remoteMethod') as (keyword: string) => void;

    search('old');
    search('new');
    newer.resolve(['new candidate']);
    await settle();
    older.resolve(['stale candidate']);
    await settle();

    expect(view.text()).toContain('new candidate');
    expect(view.text()).not.toContain('stale candidate');
  });

  it('does not let an earlier preview replace the result for the latest draft', async () => {
    vi.useFakeTimers();
    const older = deferred<{ finalOptions: string[] }>();
    const newer = deferred<{ finalOptions: string[] }>();
    mocks.previewOptions.mockReturnValueOnce(older.promise).mockReturnValueOnce(newer.promise);
    const view = mountView();
    await settle();
    await vi.advanceTimersByTimeAsync(600);
    expect(mocks.previewOptions).toHaveBeenCalledTimes(1);

    await view.get('.manual-select input').setValue('changed draft');
    await vi.advanceTimersByTimeAsync(600);
    expect(mocks.previewOptions).toHaveBeenCalledTimes(2);

    newer.resolve({ finalOptions: ['latest preview'] });
    await settle();
    older.resolve({ finalOptions: ['stale preview'] });
    await settle();
    expect(view.find('.preview-box').text()).toContain('latest preview');
    expect(view.find('.preview-box').text()).not.toContain('stale preview');
  });

  it('scopes rule relations to what a single option value can express', async () => {
    mocks.getFieldConfig.mockResolvedValue(configWithRules(fieldA));
    const view = mountView();
    await settle();

    const builders = view.findAllComponents(StatisticFilterBuilderStub);
    expect(builders).toHaveLength(2);
    for (const builder of builders) {
      expect(builder.props('fields')).toEqual([
        expect.objectContaining({ key: 'optionValue', operators: ['eq', 'ne', 'contains', 'notContains'] }),
      ]);
      expect(builder.props('labelGroupOperatorOptions')).toEqual(['intersects', 'notIntersects', 'partialContainsAny']);
      expect(builder.props('operatorLabels')).toEqual({
        intersects: '属于该组',
        notIntersects: '不属于该组',
        partialContainsAny: '包含组内任一成员',
      });
      expect(builder.props('hideLogicSelectorWhenSingleCondition')).toBe(true);
    }
  });
});

// 组件销毁后，作用域整体失效：任何 await 之后的续点都不得再发写入、回填状态或提示消息。
// 这些用例锁定的是"用户已经离开页面"这一条件，与会话代次无关。
describe('DropdownOptionSettingsView destroyed scope', () => {
  it('does not start a bind write when the confirmation resolves after the view was destroyed', async () => {
    const confirmation = deferred<void>();
    mocks.confirm.mockReturnValue(confirmation.promise);
    const view = mountView();
    await settle();

    const newConfigButton = view.findAll('button').find((button) => button.text().includes('新建空白配置'))!;
    await newConfigButton.trigger('click');
    await settle();
    expect(mocks.bindField).not.toHaveBeenCalled();

    view.unmount();
    confirmation.resolve();
    await settle();

    expect(mocks.bindField).not.toHaveBeenCalled();
  });

  it('stays silent when an in-flight config load fails after the view was destroyed', async () => {
    const view = mountView();
    await settle();

    const pending = deferred<DropdownOptionFieldConfig>();
    mocks.getFieldConfig.mockReturnValue(pending.promise);
    await view.findAll('.field-row')[1]!.trigger('click');
    await settle();

    view.unmount();
    pending.reject(new Error('配置加载失败'));
    await settle();

    expect(mocks.error).not.toHaveBeenCalled();
  });

  it('stays silent when the field list request fails after the view was destroyed', async () => {
    const pending = deferred<Awaited<ReturnType<typeof mocks.listFields>>>();
    mocks.listFields.mockReturnValue(pending.promise);
    const view = mountView();
    await settle();

    view.unmount();
    pending.reject(new Error('字段清单加载失败'));
    await settle();

    expect(mocks.error).not.toHaveBeenCalled();
  });

  it('does not report a saved configuration after the view was destroyed', async () => {
    const pending = deferred<DropdownOptionFieldConfig>();
    mocks.saveConfig.mockReturnValue(pending.promise);
    const view = mountView();
    await settle();
    await view.get('.manual-select input').setValue('编辑后的值');
    const saveButton = view.findAll('button').find((button) => button.text().includes('保存配置'))!;
    await saveButton.trigger('click');
    await settle();
    expect(mocks.saveConfig).toHaveBeenCalledTimes(1);

    view.unmount();
    pending.resolve(config(fieldA, 1, 'saved'));
    await settle();

    expect(mocks.success).not.toHaveBeenCalled();
  });
});
