import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { createRouter, createWebHashHistory, type Router } from 'vue-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { HttpRequestError } from '../api-client/request';
import type {
  FormAdminDraftView,
  FormAdminPreviewView,
  FormAdminPublicationView,
  FormAdminScopeDetail,
  FormAdminScopeSummary,
  FormAdminTemplateSummary,
  FormTemplateSpecView,
} from '../types/api';
import FormTemplateAdminView from './FormTemplateAdminView.vue';

const mocks = vi.hoisted(() => ({
  api: {
    getFormTemplates: vi.fn(),
    getFormScope: vi.fn(),
    createFormScope: vi.fn(),
    saveFormDraft: vi.fn(),
    previewFormScope: vi.fn(),
    createFormPublication: vi.fn(),
    getFormPublication: vi.fn(),
    recoverFormPublication: vi.fn(),
    retireFormScope: vi.fn(),
    deleteFormScope: vi.fn(),
    getFormBindingOptions: vi.fn(),
    getFormMilestones: vi.fn(),
  },
  message: {
    success: vi.fn(),
    info: vi.fn(),
    error: vi.fn(),
    warning: vi.fn(),
  },
  messageBox: {
    confirm: vi.fn(),
  },
}));

vi.mock('../api-client/collect-forms-api', () => ({ collectFormsApi: mocks.api }));
vi.mock('../element-plus-services', () => ({
  ElMessage: mocks.message,
  ElMessageBox: mocks.messageBox,
}));

const spec: FormTemplateSpecView = {
  spec_version: 1,
  template_key: 'issue_reply',
  fields: [
    {
      name: 'fix_status',
      apiKey: 'fixStatus',
      label: '修复状态',
      type: 'enum',
      control: 'radio',
      required: true,
      options: [{ value: 'RESOLVED', label: '已解决' }],
      storage: { column: 'fix_status', columnType: 'varchar(32)' },
    },
    {
      name: 'specific_reason',
      apiKey: 'specificReason',
      label: '具体原因',
      type: 'string',
      control: 'textarea',
      required: true,
      storage: { column: 'specific_reason', columnType: 'text' },
    },
  ],
  conditions: [],
  dictionaries: [],
};

function scopeSummary(): FormAdminScopeSummary {
  return {
    id: 7,
    templateKey: 'issue_reply',
    scopeKey: 'w4-scope',
    status: 'ACTIVE',
    resourceType: 'issue',
    sourceConfigId: 1,
    sourceInstance: 'default',
    canonicalGitlabBaseUrl: 'http://gitlab.w4.local',
    projectIds: [9],
    versionProjectId: 9,
    bindingHash: 'binding-hash',
    bindingRevision: 1,
    currentVersionId: 2,
    currentVersionLabel: 'CC2026R1',
    currentVersionStatus: 'PUBLISHED',
    draftRevision: 2,
    writeFenced: false,
    activePublications: 0,
    updatedAt: '2026-10-23T00:00:00',
  };
}

function draft(revision = 2, label = '修复状态'): FormAdminDraftView {
  return {
    revision,
    fieldSpec: {
      ...spec,
      fields: [{ ...spec.fields[0], label }, spec.fields[1]],
    },
    binding: {
      sourceConfigId: 1,
      sourceInstance: 'default',
      canonicalGitlabBaseUrl: 'http://gitlab.w4.local',
      projectIds: [9],
      versionProjectId: 9,
      bindingHash: 'binding-hash',
      bound: true,
    },
    versionLabel: 'CC2026R1',
    versionEvidence: { milestoneId: 1001, milestoneTitle: 'CC2026R1' },
    migrationSpec: null,
    updatedBy: 'admin',
    updatedAt: '2026-10-23T00:00:00',
  };
}

function detail(scopeDraft: FormAdminDraftView = draft()): FormAdminScopeDetail {
  return {
    scope: scopeSummary(),
    draft: scopeDraft,
    versions: [
      {
        id: 2,
        versionLabel: 'CC2026R1',
        storageKind: 'RECORDS',
        versionStatus: 'PUBLISHED',
        instanceTable: 'form_s7_v2_instances',
        recordTable: 'form_s7_v2_records',
        publishedAt: '2026-10-23T00:00:00',
      },
    ],
    preview: null,
    latestPublication: null,
    recordCount: 0,
    instanceCount: 0,
  };
}

function templates(): FormAdminTemplateSummary[] {
  return [
    {
      templateKey: 'issue_reply',
      displayName: 'Issue 回复',
      status: 'ACTIVE',
      scopes: [scopeSummary()],
    },
  ];
}

function preview(overrides: Partial<FormAdminPreviewView> = {}): FormAdminPreviewView {
  return {
    id: 3,
    draftRevision: 2,
    targetVersionId: 4,
    previewHash: 'hash',
    publicationKind: 'MIGRATION',
    diff: { added: 0, removed: 0, modified: 0, fields: [] },
    ddlSummary: [],
    ddlHash: 'ddl',
    impact: { records: 0, instances: 0 },
    blockingErrors: [],
    createdAt: '2026-10-23T00:00:00',
    ...overrides,
  };
}

function publication(stage: FormAdminPublicationView['stage']): FormAdminPublicationView {
  return {
    id: 5,
    scopeId: 7,
    requestKey: '11111111-1111-4111-8111-111111111111',
    publicationKind: 'MIGRATION',
    stage,
    attemptNo: 1,
    recoveryCycle: 0,
    expectedCurrentVersionId: 2,
    fromVersionId: 2,
    toVersionId: 4,
    lastError: null,
    selectionCounts: null,
    verificationSummary: null,
    createdAt: '2026-10-23T00:00:00',
    updatedAt: '2026-10-23T00:00:00',
    publishedAt: stage === 'PUBLISHED' ? '2026-10-23T00:00:05' : null,
  };
}


function detailFor(scopeId: number, scopeKey: string, label: string): FormAdminScopeDetail {
  const base = detail(draft(2, label));
  return {
    ...base,
    scope: { ...base.scope, id: scopeId, scopeKey },
  };
}

let wrapper: VueWrapper | null = null;
let router: Router | null = null;

beforeEach(() => {
  vi.clearAllMocks();
  document.body.innerHTML = '';
  mocks.api.getFormTemplates.mockResolvedValue(templates());
  mocks.api.getFormScope.mockResolvedValue(detail());
  mocks.api.getFormBindingOptions.mockResolvedValue([
    {
      sourceConfigId: 1,
      name: 'w4-source',
      sourceInstance: 'default',
      canonicalGitlabBaseUrl: 'http://gitlab.w4.local',
    },
  ]);
  mocks.api.getFormMilestones.mockResolvedValue({
    available: true,
    items: [{ id: 1001, title: 'CC2026R1' }],
  });
  mocks.messageBox.confirm.mockResolvedValue('confirm');
  vi.stubGlobal('crypto', {
    ...globalThis.crypto,
    randomUUID: () => '11111111-1111-4111-8111-111111111111',
  });
});

afterEach(() => {
  wrapper?.unmount();
  wrapper = null;
  router = null;
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

async function mountView() {
  router = createRouter({
    history: createWebHashHistory(),
    routes: [{ path: '/system-settings/form-templates', component: FormTemplateAdminView }],
  });
  await router.push('/system-settings/form-templates');
  await router.isReady();
  wrapper = mount(FormTemplateAdminView, {
    global: { plugins: [ElementPlus, router] },
  });
  await flushPromises();
  return wrapper;
}

function button(wrapper: VueWrapper, label: string) {
  const found = wrapper.findAll('button').find((item) => item.text().includes(label));
  if (!found) {
    throw new Error(`Missing button: ${label}`);
  }
  return found;
}


function fieldLabelInputs(wrapper: VueWrapper) {
  return wrapper.findAll('.field-table input');
}

describe('FormTemplateAdminView', () => {
  it('shows business fields without internal keys or advanced JSON', async () => {
    const view = await mountView();
    expect(view.text()).toContain('表单管理');
    expect(view.text()).toContain('w4-scope');
    const labels = fieldLabelInputs(view).map(
      (input) => (input.element as HTMLInputElement).value,
    );
    expect(labels).toContain('修复状态');
    expect(labels).toContain('具体原因');
    expect(view.text()).not.toContain('fix_status');
    expect(view.text()).not.toContain('高级 JSON');
    expect(view.text()).not.toContain('API 键');
  });

  it('saves the draft preserving stable identity after a label rename', async () => {
    mocks.api.saveFormDraft.mockResolvedValue(draft(3, '修复结果'));
    const view = await mountView();
    const labelInput = fieldLabelInputs(view)[0];
    await labelInput.setValue('修复结果');
    await button(view, '保存草稿').trigger('click');
    await flushPromises();

    const payload = mocks.api.saveFormDraft.mock.calls[0][1];
    expect(mocks.api.saveFormDraft).toHaveBeenCalledWith(
      7,
      expect.objectContaining({ expectedDraftRevision: 2 }),
    );
    expect(payload.fieldSpec.fields[0]).toMatchObject({
      name: 'fix_status',
      apiKey: 'fixStatus',
      label: '修复结果',
    });
    expect(mocks.message.success).toHaveBeenCalledWith('草稿已保存');
  });

  it('keeps local edits on CAS conflict and never reloads automatically', async () => {
    mocks.api.saveFormDraft.mockRejectedValue(
      new HttpRequestError('草稿已被他人修改', 409, 'A0409'),
    );
    mocks.api.getFormScope
      .mockResolvedValueOnce(detail())
      .mockResolvedValueOnce(detail(draft(5, '服务器字段名')));
    const view = await mountView();
    const labelInput = fieldLabelInputs(view)[0];
    await labelInput.setValue('本地字段名');
    const callsBefore = mocks.api.getFormTemplates.mock.calls.length;
    await button(view, '保存草稿').trigger('click');
    await flushPromises();

    expect(mocks.message.warning).toHaveBeenCalledWith('草稿已被他人修改，本地修改已保留');
    expect(mocks.api.getFormTemplates.mock.calls.length).toBe(callsBefore);
    expect((fieldLabelInputs(view)[0].element as HTMLInputElement).value).toBe('本地字段名');
    expect(view.text()).toContain('草稿已被他人修改');
    expect(view.text()).toContain('服务器字段名');

    await button(view, '保留双方，稍后处理').trigger('click');
    await flushPromises();
    expect((fieldLabelInputs(view)[0].element as HTMLInputElement).value).toBe('本地字段名');
  });

  it('replaces local edits only after explicitly loading the server draft', async () => {
    mocks.api.saveFormDraft.mockRejectedValue(
      new HttpRequestError('草稿已被他人修改', 409, 'A0409'),
    );
    mocks.api.getFormScope
      .mockResolvedValueOnce(detail())
      .mockResolvedValueOnce(detail(draft(5, '服务器字段名')));
    const view = await mountView();
    await fieldLabelInputs(view)[0].setValue('本地字段名');
    await button(view, '保存草稿').trigger('click');
    await flushPromises();

    await button(view, '载入服务器版本').trigger('click');
    await flushPromises();
    expect((fieldLabelInputs(view)[0].element as HTMLInputElement).value).toBe('服务器字段名');
  });


  it('keeps input typed while a save request is still in flight', async () => {
    let resolveSave: (value: FormAdminDraftView) => void = () => {};
    mocks.api.saveFormDraft.mockReturnValue(
      new Promise<FormAdminDraftView>((resolve) => {
        resolveSave = resolve;
      }),
    );
    const view = await mountView();
    await fieldLabelInputs(view)[0].setValue('已发送名称');
    await button(view, '保存草稿').trigger('click');
    await fieldLabelInputs(view)[0].setValue('等待期间继续输入');
    resolveSave(draft(3, '已发送名称'));
    await flushPromises();

    expect((fieldLabelInputs(view)[0].element as HTMLInputElement).value).toBe(
      '等待期间继续输入',
    );
    expect(mocks.message.warning).toHaveBeenCalledWith(
      '草稿已保存；等待期间的新输入尚未保存，请再次保存',
    );
    expect(view.text()).toContain('有未保存修改');
    expect(mocks.message.success).not.toHaveBeenCalled();
  });

  it('does not apply a late save response to another scope', async () => {
    const second = { ...scopeSummary(), id: 8, scopeKey: 'second-scope' };
    mocks.api.getFormTemplates.mockResolvedValue([
      {
        templateKey: 'issue_reply',
        displayName: 'Issue 回复',
        status: 'ACTIVE',
        scopes: [scopeSummary(), second],
      },
    ]);
    mocks.api.getFormScope
      .mockResolvedValueOnce(detail())
      .mockResolvedValueOnce(detailFor(8, 'second-scope', '范围八字段'));
    let resolveSave: (value: FormAdminDraftView) => void = () => {};
    mocks.api.saveFormDraft.mockReturnValue(
      new Promise<FormAdminDraftView>((resolve) => {
        resolveSave = resolve;
      }),
    );
    const view = await mountView();
    await fieldLabelInputs(view)[0].setValue('范围七在途输入');
    await button(view, '保存草稿').trigger('click');
    await view.findAll('.scope-item')[1].trigger('click');
    await flushPromises();
    expect(view.text()).toContain('second-scope');

    resolveSave(draft(3, '范围七已发送'));
    await flushPromises();
    expect((fieldLabelInputs(view)[0].element as HTMLInputElement).value).toBe('范围八字段');
    expect(view.text()).not.toContain('范围七已发送');
  });
  it('confirms before switching scope or refreshing with unsaved edits', async () => {
    const second = { ...scopeSummary(), id: 8, scopeKey: 'second-scope' };
    mocks.api.getFormTemplates.mockResolvedValue([
      { templateKey: 'issue_reply', displayName: 'Issue 回复', status: 'ACTIVE', scopes: [scopeSummary(), second] },
    ]);
    mocks.messageBox.confirm.mockResolvedValue(false);
    const view = await mountView();
    await fieldLabelInputs(view)[0].setValue('未保存名称');
    const callsBefore = mocks.api.getFormScope.mock.calls.length;

    await view.findAll('.scope-item')[1].trigger('click');
    await flushPromises();
    expect(mocks.messageBox.confirm).toHaveBeenCalledWith(
      expect.stringContaining('未保存的修改'),
      '未保存修改',
      expect.any(Object),
    );
    expect(mocks.api.getFormScope.mock.calls.length).toBe(callsBefore);

    await button(view, '刷新').trigger('click');
    await flushPromises();
    expect(mocks.messageBox.confirm).toHaveBeenCalledTimes(2);
    expect(mocks.api.getFormScope.mock.calls.length).toBe(callsBefore);
  });


  it('keeps edits typed while publication polling completes', async () => {
    vi.useFakeTimers();
    mocks.api.previewFormScope.mockResolvedValue(preview());
    mocks.api.createFormPublication.mockResolvedValue(publication('COPYING'));
    mocks.api.getFormPublication
      .mockResolvedValueOnce(publication('COPYING'))
      .mockResolvedValueOnce(publication('PUBLISHED'));
    mocks.api.getFormScope.mockResolvedValue(detail(draft(2, '服务器刷新字段')));
    const view = await mountView();
    await button(view, '发布结构').trigger('click');
    await flushPromises();

    await fieldLabelInputs(view)[0].setValue('发布期间输入');
    await vi.advanceTimersByTimeAsync(1100);
    await flushPromises();

    expect((fieldLabelInputs(view)[0].element as HTMLInputElement).value).toBe(
      '发布期间输入',
    );
    expect(mocks.message.warning).toHaveBeenCalledWith(
      '版本已发布；本地未保存修改仍保留，请再次保存',
    );
    expect(view.text()).toContain('有未保存修改');
  });
  it('confirms before deleting an existing field and keeps it when cancelled', async () => {
    mocks.messageBox.confirm.mockResolvedValueOnce(false);
    const view = await mountView();
    const deleteButtons = view.findAll('.field-table button');
    const dangerous = deleteButtons.filter((item) =>
      item.classes().some((name) => name.includes('danger')),
    );
    await dangerous[0].trigger('click');
    await flushPromises();
    expect(mocks.messageBox.confirm).toHaveBeenCalledWith(
      expect.stringContaining('删除字段「修复状态」'),
      '删除字段',
      expect.any(Object),
    );
    expect(fieldLabelInputs(view).length).toBeGreaterThanOrEqual(2);
  });


  it('edits business conditions and never renders physical tables or DDL', async () => {
    const conditionSpec: FormTemplateSpecView = {
      ...spec,
      conditions: [
        {
          type: 'conditionalRequired',
          target: 'specific_reason',
          requiredWhen: { field: 'fix_status', equals: 'RESOLVED' },
          clearWhen: { field: 'fix_status', equals: 'FIXED' },
        },
      ],
    };
    mocks.api.getFormScope.mockResolvedValue(detail({ ...draft(), fieldSpec: conditionSpec }));
    mocks.api.saveFormDraft.mockImplementation(async (_id: number, payload: never) => {
      const body = payload as unknown as { fieldSpec: FormTemplateSpecView };
      return { ...draft(3), fieldSpec: body.fieldSpec };
    });
    const view = await mountView();

    expect(view.text()).toContain('字段条件');
    expect(view.text()).toContain('已解决');
    expect(view.text()).toContain('具体原因');
    expect(view.text()).not.toContain('fix_status');
    expect(view.text()).not.toContain('RESOLVED');
    expect(view.text()).not.toContain('实例表');
    expect(view.text()).not.toContain('记录表');
    expect(view.text()).not.toContain('form_s7_v2_instances');
    expect(view.text()).not.toContain('工程校验信息');

    const targetSelect = view
      .findAllComponents({ name: 'ElSelect' })
      .find((item) => item.classes().includes('condition-target'));
    expect(targetSelect).toBeTruthy();
    targetSelect!.vm.$emit('change', 'fix_status');
    await flushPromises();
    await button(view, '保存草稿').trigger('click');
    await flushPromises();

    const payload = mocks.api.saveFormDraft.mock.calls[0][1];
    expect(payload.fieldSpec.conditions).toEqual([
      expect.objectContaining({
        type: 'conditionalRequired',
        target: 'fix_status',
        requiredWhen: { field: 'fix_status', equals: 'RESOLVED' },
      }),
    ]);
    expect(
      payload.fieldSpec.fields.every(
        (field: { requiredWhen?: unknown; clearWhen?: unknown }) =>
          !field.requiredWhen && !field.clearWhen,
      ),
    ).toBe(true);
  });
  it('refuses to preview or publish unsaved local edits', async () => {
    const view = await mountView();
    await fieldLabelInputs(view)[0].setValue('未保存名称');
    await button(view, '生成预览').trigger('click');
    await flushPromises();
    expect(mocks.message.warning).toHaveBeenCalledWith('请先保存草稿，再进行预览或发布');
    expect(mocks.api.previewFormScope).not.toHaveBeenCalled();
    expect(mocks.api.createFormPublication).not.toHaveBeenCalled();
  });

  it('reuses the same request key and preview when publish retries', async () => {
    vi.useFakeTimers();
    mocks.api.previewFormScope.mockResolvedValue(preview());
    mocks.api.createFormPublication
      .mockRejectedValueOnce(new Error('network down'))
      .mockResolvedValueOnce(publication('PUBLISHED'));
    mocks.api.getFormPublication.mockResolvedValue(publication('PUBLISHED'));
    const view = await mountView();
    await button(view, '发布结构').trigger('click');
    await flushPromises();
    await button(view, '发布结构').trigger('click');
    await flushPromises();
    await vi.advanceTimersByTimeAsync(1100);

    expect(mocks.api.previewFormScope).toHaveBeenCalledTimes(1);
    const first = mocks.api.createFormPublication.mock.calls[0][1];
    const second = mocks.api.createFormPublication.mock.calls[1][1];
    expect(second.requestKey).toBe(first.requestKey);
    expect(second.previewId).toBe(first.previewId);
    expect(mocks.message.success).toHaveBeenCalledWith('结构版本已发布');
  });

  it('renders blockers and Chinese business changes instead of technical names', async () => {
    mocks.api.previewFormScope.mockResolvedValue(
      preview({
        blockingErrors: ['字段 fix_status 的存储列不可变更；请新增字段并迁移数据'],
        diff: {
          added: 1,
          removed: 1,
          modified: 1,
          fields: [
            {
              name: 'new_opinion',
              change: 'ADDED',
              newLabel: '走查意见',
              newControl: 'textarea',
              newRequired: true,
            },
            {
              name: 'supplement_note',
              change: 'REMOVED',
              oldLabel: '补充说明',
              oldControl: 'textarea',
            },
            {
              name: 'fix_status',
              change: 'LABEL',
              changes: ['LABEL'],
              oldLabel: '修复状态',
              newLabel: '修复状态说明',
              oldControl: 'radio',
              newControl: 'select',
            },
          ],
        },
        impact: { records: 12, instances: 4 },
        ddlSummary: [{ table: 'form_s7_v4_records', name: 'fix_status', type: 'varchar(64)', nullable: false }],
      }),
    );
    const view = await mountView();
    await button(view, '生成预览').trigger('click');
    await flushPromises();

    expect(view.text()).toContain('存储列不可变更');
    expect(view.text()).toContain('走查意见');
    expect(view.text()).toContain('新增');
    expect(view.text()).toContain('删除');
    expect(view.text()).toContain('名称调整');
    expect(view.text()).toContain('历史记录影响');
    expect(view.text()).not.toContain('工程校验信息');
    expect(view.text()).not.toContain('form_s7_v4_records');
    expect(view.text()).toContain('12 条历史记录');
  });
});
