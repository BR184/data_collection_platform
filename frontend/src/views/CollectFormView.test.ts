import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { defineComponent, h } from 'vue';
import { createRouter, createWebHashHistory, createMemoryHistory, RouterView, type Router } from 'vue-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiBizError, HttpRequestError } from '../api-client/request';
import { authState } from '../composables/auth-state';
import {
  formContextKey,
  saveFormDraft,
  type FormDraftRecord,
} from '../features/collect-forms/draft-store';
import type { FormDetailResponse, FormRecordView, FormTemplateSpecView } from '../types/api';
import CollectFormView from './CollectFormView.vue';

const mocks = vi.hoisted(() => ({
  api: {
    getFormDetail: vi.fn(),
    createFormRecord: vi.fn(),
    updateFormRecord: vi.fn(),
    voidFormRecord: vi.fn(),
    getFormReviewerCandidates: vi.fn(),
  },
  message: {
    success: vi.fn(),
    info: vi.fn(),
    error: vi.fn(),
    warning: vi.fn(),
  },
  messageBox: {
    confirm: vi.fn(),
    prompt: vi.fn(),
  },
}));

vi.mock('../api', () => ({ api: mocks.api }));
vi.mock('../element-plus-services', () => ({
  ElMessage: mocks.message,
  ElMessageBox: mocks.messageBox,
}));

const codeReviewSpec: FormTemplateSpecView = {
  spec_version: 1,
  template_key: 'code_review',
  fields: [
    { name: 'reviewer_user_id', apiKey: 'reviewerUserId', label: '走查人', type: 'string', control: 'person-select', required: true, maxLength: 128 },
    { name: 'supplement_note', apiKey: 'supplementNote', label: '补充说明', type: 'string', control: 'textarea', required: false, maxLength: 8192 },
  ],
  conditions: [],
  dictionaries: [],
};

const issueReplySpec: FormTemplateSpecView = {
  spec_version: 1,
  template_key: 'issue_reply',
  fields: [
    { name: 'supplement_note', apiKey: 'supplementNote', label: '补充说明', type: 'string', control: 'textarea', required: false, maxLength: 8192 },
  ],
  conditions: [],
  dictionaries: [],
};

function detail(overrides: Partial<FormDetailResponse> = {}): FormDetailResponse {
  return {
    templateKey: 'code_review',
    fieldSpec: codeReviewSpec,
    scopeId: '11',
    currentVersionId: '22',
    storageVersionId: '22',
    versionLabel: 'v2026.10',
    scopeStatus: 'ACTIVE',
    writable: true,
    instanceId: '31',
    readableVersions: [],
    versionSpecs: { '22': codeReviewSpec },
    sourceSetRevision: '0',
    sourceSetHash: '0'.repeat(64),
    records: [],
    resource: {
      gitlabBaseUrl: 'https://gitlab.example.com',
      projectId: '7',
      resourceType: 'merge_request',
      resourceId: '3',
      rootId: '41',
      webUrl: 'https://gitlab.example.com/group/project/-/merge_requests/3',
      severityLevel: null,
    },
    account: { userId: 'alice', displayName: 'Alice' },
    capabilities: { canCreate: true, canEditOwn: true, canVoidOwn: true },
    ...overrides,
  };
}

function inlineRecord(id: string, note: string): FormRecordView {
  return {
    id, recordVersion: 1, deleted: false, legacyIncomplete: false, legacyMissingFields: [],
    fields: { reviewerUserId: 'alice', supplementNote: note }, fieldNames: { reviewerUserId: 'Alice' }, snapshotValues: {},
    createdBy: 'alice', createdByName: 'Alice', templateVersion: 'v2026.10',
    originVersionId: '22', storageVersionId: '22', createdAt: '2026-10-28T07:25:43', updatedAt: '2026-10-28T07:25:43',
    canEdit: true, canVoid: true,
  };
}

let wrapper: VueWrapper | null = null;
let activeRouter: Router | null = null;

async function mountView(path: string, mode: 'hash' | 'memory' = 'hash'): Promise<VueWrapper> {
  const router = createRouter({
    history:
      mode === 'memory'
        ? createMemoryHistory()
        : createWebHashHistory(),
    routes: [
      { path: '/external/forms', component: CollectFormView },
      { path: '/external/code-review-form', component: CollectFormView },
    ],
  });
  activeRouter = router;
  await router.push(path);
  await router.isReady();
  const Root = defineComponent({ render: () => h(RouterView) });
  wrapper = mount(Root, {
    attachTo: document.body,
    global: { plugins: [router, ElementPlus] },
  });
  await flushPromises();
  await flushPromises();
  return wrapper;
}

function findButton(current: VueWrapper, text: string) {
  return current.findAll('button').find((button) => button.text().includes(text));
}

const ISSUE_BASE_QUERY = {
  gitlabBaseUrl: 'https://gitlab.example.com',
  projectId: '7',
  resourceType: 'issue',
  resourceId: '31',
};
const ISSUE_REPLY_PATH =
  '/external/forms?gitlabBaseUrl=https%3A%2F%2Fgitlab.example.com&projectId=7&resourceType=issue&resourceId=31&templateKey=issue_reply';

function issueDetail(templateKey: string): FormDetailResponse {
  return detail({
    templateKey,
    fieldSpec: { ...issueReplySpec, template_key: templateKey },
    resource: {
      gitlabBaseUrl: 'https://gitlab.example.com',
      projectId: '7',
      resourceType: 'issue',
      resourceId: '31',
      rootId: '41',
      webUrl: 'https://gitlab.example.com/group/project/-/issues/31',
      severityLevel: null,
    },
  });
}

function mockIssueDetails() {
  mocks.api.getFormDetail.mockImplementation(
    async (params: { templateKey: string }) => issueDetail(params.templateKey),
  );
}

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  document.body.innerHTML = '';
  Object.defineProperty(window, 'scrollTo', {
    configurable: true,
    writable: true,
    value: vi.fn(),
  });
  mocks.api.getFormDetail.mockReset();
  mocks.api.updateFormRecord.mockReset();
  mocks.api.getFormReviewerCandidates.mockReset();
  mocks.api.getFormReviewerCandidates.mockResolvedValue({
    items: [],
    nextCursor: null,
    currentUserCandidate: { userId: 'alice', realName: 'Alice', deptName: null },
  });
  mocks.message.success.mockReset();
  mocks.message.info.mockReset();
  mocks.message.error.mockReset();
  mocks.message.warning.mockReset();
  mocks.messageBox.confirm.mockReset();
  mocks.messageBox.prompt.mockReset();
  authState.currentUser = {
    username: 'alice',
    displayName: 'Alice',
    roleCodes: ['ADMIN'],
    roleNames: ['管理员'],
    permissions: ['form.record.create', 'form.record.edit_own', 'form.record.delete_own'],
    authenticated: true,
  };
  authState.initialized = true;
  authState.status = 'authenticated';
});

afterEach(() => {
  wrapper?.unmount();
  wrapper = null;
  document.body.innerHTML = '';
  localStorage.clear();
  sessionStorage.clear();
});

describe('CollectFormView', () => {
  it('edits the first record inline and restores its card in the same order after saving', async () => {
    const first = inlineRecord('1', '第一条原值');
    const second = inlineRecord('2', '第二条原值');
    const updated = { ...first, recordVersion: 2, fields: { ...first.fields, supplementNote: '第一条更新值' } };
    mocks.api.getFormDetail.mockResolvedValueOnce(detail({ records: [first, second] })).mockResolvedValue(detail({ records: [updated, second] }));
    mocks.api.updateFormRecord.mockResolvedValue(updated);
    const current = await mountView('/external/code-review-form?gitlabBaseUrl=https%3A%2F%2Fgitlab.example.com&projectId=7&mrIid=3');
    await current.find('[data-record-id="1"] button.el-button--primary').trigger('click');
    await flushPromises();

    expect(current.findAll('.form-record-list .form-block')).toHaveLength(1);
    expect(current.findAll('.form-draft-section .form-block')).toHaveLength(0);
    expect(current.findAll('.form-record-list [data-record-id]').map((item) => item.attributes('data-record-id'))).toEqual(['1', '2']);
    await current.find('.form-record-list .form-block textarea').setValue('第一条更新值');
    await current.find('.form-record-list .form-block .form-block-footer .el-button--primary').trigger('click');
    await flushPromises();

    expect(current.findAll('.form-record-list .form-block')).toHaveLength(0);
    expect(current.findAll('.form-record-list [data-record-id]').map((item) => item.attributes('data-record-id'))).toEqual(['1', '2']);
    expect(current.find('[data-record-id="1"]').text()).toContain('第一条更新值');
  });

  it('restores the inline record on cancel while leaving the independent new draft intact', async () => {
    const first = inlineRecord('1', '第一条原值');
    const second = inlineRecord('2', '第二条原值');
    mocks.api.getFormDetail.mockResolvedValue(detail({ records: [first, second] }));
    mocks.messageBox.confirm.mockResolvedValue('confirm');
    const current = await mountView('/external/code-review-form?gitlabBaseUrl=https%3A%2F%2Fgitlab.example.com&projectId=7&mrIid=3');
    await findButton(current, '新增填写')!.trigger('click'); await flushPromises();
    await current.find('.form-draft-section textarea').setValue('新建草稿保留');
    const draftKey = current.find('.form-draft-section .form-block').attributes('data-block-key');
    await current.find('[data-record-id="1"] button.el-button--primary').trigger('click'); await flushPromises();

    expect(current.findAll('.form-record-list .form-block')).toHaveLength(1);
    await current.find('.form-record-list .form-block textarea').setValue('准备取消的修改');
    await current.find('.form-record-list .form-block [aria-label="丢弃草稿"]').trigger('click'); await flushPromises();

    expect(current.findAll('.form-record-list .form-block')).toHaveLength(0);
    expect(current.findAll('.form-record-list [data-record-id]').map((item) => item.attributes('data-record-id'))).toEqual(['1', '2']);
    expect(current.find('[data-record-id="1"]').text()).toContain('第一条原值');
    expect(current.find('.form-draft-section .form-block').attributes('data-block-key')).toBe(draftKey);
    expect((current.find('.form-draft-section textarea').element as HTMLTextAreaElement).value).toBe('新建草稿保留');
  });

  it('renders issue response enum, date, branch and text fields by their own published controls', async () => {
    const responseSpec: FormTemplateSpecView = {
      spec_version: 1, template_key: 'issue_response', fields: [
        { name: 'problem_type', apiKey: 'problemType', label: '问题类型', type: 'enum', control: 'radio', required: true, options: [{ value: 'DEFECT', label: '缺陷' }] },
        { name: 'problem_cause', apiKey: 'problemCause', label: '问题原因', type: 'string', control: 'textarea', required: true },
        { name: 'modification_plan', apiKey: 'modificationPlan', label: '修改方案', type: 'string', control: 'textarea', required: true },
        { name: 'owner_confirmation', apiKey: 'ownerConfirmation', label: '模块负责人确认', type: 'string', control: 'textarea' },
        { name: 'planned_resolution_date', apiKey: 'plannedResolutionDate', label: '计划解决时间', type: 'date', control: 'date', required: true },
        { name: 'planned_merge_version_branch', apiKey: 'plannedMergeVersionBranch', label: '计划合并的版本分支', type: 'string', control: 'version-members', required: true },
        { name: 'supplement_note', apiKey: 'supplementNote', label: '补充说明', type: 'string', control: 'textarea' },
      ],
    };
    const record: FormRecordView = {
      id: '101', recordVersion: 1, deleted: false, legacyIncomplete: false, legacyMissingFields: [],
      fields: { problemType: 'DEFECT', problemCause: '边界校验遗漏', modificationPlan: '补齐边界处理', ownerConfirmation: null, plannedResolutionDate: '2026-11-05', plannedMergeVersionBranch: 'CC2026R4', supplementNote: null },
      fieldNames: {}, snapshotValues: {}, createdBy: 'alice', createdByName: '张明', templateVersion: '2026.10',
      originVersionId: '22', storageVersionId: '22', createdAt: '2026-10-28T07:25:43', updatedAt: '2026-10-28T07:25:43', canEdit: true, canVoid: true,
    };
    mocks.api.getFormDetail.mockResolvedValue(detail({ templateKey: 'issue_response', fieldSpec: responseSpec, versionSpecs: { '22': responseSpec }, records: [record], resource: { ...issueDetail('issue_response').resource, severityLevel: '二级缺陷' } }));
    const current = await mountView(ISSUE_REPLY_PATH.replace('issue_reply', 'issue_response'));
    const card = current.find('[data-record-id="101"]');
    expect(card.find('.form-record-author').text()).toBe('张明');
    expect(card.findAll('.form-record-short-fields .form-record-field')).toHaveLength(3);
    expect(card.findAll('.form-record-long')).toHaveLength(2);
    expect(card.find('.form-record-numbers').exists()).toBe(false);
    expect(card.text()).toContain('缺陷'); expect(card.text()).toContain('2026-11-05'); expect(card.text()).toContain('CC2026R4');
    expect(card.find('.form-record-empty-fields').text()).toBe('未填写：模块负责人确认、补充说明');
    expect(current.findAll('[data-action="add-block"]')).toHaveLength(1);
    expect(current.find('.external-context-grid').exists()).toBe(false);
  });

  it('copies the complete resource URL while displaying a short link label', async () => {
    const previous = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
    let clipboardText = '';
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async (value: string) => { clipboardText = value; } } });
    try {
      mocks.api.getFormDetail.mockResolvedValue(detail());
      const current = await mountView('/external/code-review-form?gitlabBaseUrl=https%3A%2F%2Fgitlab.example.com&projectId=7&mrIid=3');
      await current.find('[aria-label="复制资源链接"]').trigger('click'); await flushPromises();
      expect(clipboardText).toBe(detail().resource.webUrl);
      expect(current.find('.form-resource-link a').text()).toBe('在 GitLab 打开 ↗');
      expect(current.find('.form-resource-link a').attributes('title')).toBe(detail().resource.webUrl);
      expect(mocks.message.success).toHaveBeenCalledWith('链接已复制');
    } finally {
      if (previous) Object.defineProperty(navigator, 'clipboard', previous); else Reflect.deleteProperty(navigator, 'clipboard');
    }
  });

  it('keeps the full URL available when clipboard access fails', async () => {
    const previous = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async () => { throw new Error('clipboard denied'); } } });
    try {
      mocks.api.getFormDetail.mockResolvedValue(detail());
      const current = await mountView('/external/code-review-form?gitlabBaseUrl=https%3A%2F%2Fgitlab.example.com&projectId=7&mrIid=3');
      await current.find('[aria-label="复制资源链接"]').trigger('click'); await flushPromises();
      expect(mocks.message.warning).toHaveBeenCalledWith('未能复制链接，请从地址提示中复制');
      expect(current.find('.form-resource-link a').attributes('href')).toBe(detail().resource.webUrl);
    } finally {
      if (previous) Object.defineProperty(navigator, 'clipboard', previous); else Reflect.deleteProperty(navigator, 'clipboard');
    }
  });

  it('renders the published field spec as a single column and adds independent blocks', async () => {
    mocks.api.getFormDetail.mockResolvedValue(detail());
    const current = await mountView(
      '/external/forms?gitlabBaseUrl=https%3A%2F%2Fgitlab.example.com&projectId=7&resourceType=merge_request&resourceId=3&templateKey=code_review',
    );

    expect(current.text()).toContain('代码走查表');
    expect(current.text()).toContain('v2026.10');
    expect(current.text()).toContain('已保存记录（0）');
    expect(current.text()).toContain('新建草稿（0）');

    const add = findButton(current, '新增填写');
    expect(add).toBeTruthy();
    await add!.trigger('click');
    await flushPromises();

    expect(current.findAll('.form-single-column .el-form-item')).toHaveLength(2);
    expect(current.text()).toContain('走查人');
    expect(current.text()).toContain('补充说明');
    expect(current.text()).toContain('尚未填写');
    expect(current.text()).toContain('草稿仅保存在当前浏览器');
  });

  it('renders and edits a record with its own storage version spec', async () => {
    const v2Spec: FormTemplateSpecView = {
      spec_version: 2,
      template_key: 'code_review',
      fields: [
        { name: 'reviewer_user_id', apiKey: 'reviewerUserId', label: '走查人', type: 'string', control: 'person-select', required: true, maxLength: 128 },
        { name: 'security_count', apiKey: 'securityCount', label: '安全', type: 'integer', required: true, min: 0, max: 2147483647 },
      ],
      conditions: [],
      dictionaries: [],
    };
    const oldRecord: FormRecordView = {
      id: '302',
      recordVersion: 1,
      deleted: false,
      legacyIncomplete: false,
      legacyMissingFields: [],
      fields: { reviewerUserId: 'alice', securityCount: 2, supplementNote: '不应显示的旧值' },
      fieldNames: { reviewerUserId: 'Alice' },
      snapshotValues: {},
      createdBy: 'alice',
      createdByName: 'Alice',
      templateVersion: 'v2',
      originVersionId: '22',
      storageVersionId: '33',
      createdAt: '2026-10-23T01:00:00.000Z',
      updatedAt: '2026-10-23T01:00:00.000Z',
      canEdit: true,
      canVoid: true,
    };
    mocks.api.getFormDetail.mockResolvedValue(
      detail({
        currentVersionId: '22',
        storageVersionId: '22',
        readableVersions: [
          { versionId: '22', versionLabel: 'v1', versionStatus: 'PUBLISHED', instanceMode: 'MATERIALIZED', fieldSpecHash: 'a'.repeat(64) },
          { versionId: '33', versionLabel: 'v2', versionStatus: 'PUBLISHED', instanceMode: 'MATERIALIZED', fieldSpecHash: 'b'.repeat(64) },
        ],
        versionSpecs: { '22': codeReviewSpec, '33': v2Spec },
        records: [oldRecord],
      }),
    );
    const current = await mountView(
      '/external/forms?gitlabBaseUrl=https%3A%2F%2Fgitlab.example.com&projectId=7&resourceType=merge_request&resourceId=3&templateKey=code_review',
    );

    expect(current.text()).toContain('安全');
    expect(current.text()).toContain('2');
    expect(current.text()).not.toContain('补充说明');
    expect(current.text()).not.toContain('不应显示的旧值');

    const edit = findButton(current, '编辑');
    expect(edit).toBeTruthy();
    await edit!.trigger('click');
    await flushPromises();

    expect(current.findAll('[data-field-id$=":securityCount"]')).toHaveLength(1);
    expect(current.findAll('[data-field-id$=":supplementNote"]')).toHaveLength(0);
    expect(current.text()).toContain('安全');
  });

  it('marks a record without its storage version spec as unavailable instead of using the current spec', async () => {
    const orphan: FormRecordView = {
      id: '303',
      recordVersion: 1,
      deleted: false,
      legacyIncomplete: false,
      legacyMissingFields: [],
      fields: { reviewerUserId: 'alice', supplementNote: '不应套用当前字段' },
      fieldNames: { reviewerUserId: 'Alice' },
      snapshotValues: {},
      createdBy: 'alice',
      createdByName: 'Alice',
      templateVersion: 'v9',
      originVersionId: '22',
      storageVersionId: '77',
      createdAt: '2026-10-23T01:00:00.000Z',
      updatedAt: '2026-10-23T01:00:00.000Z',
      canEdit: true,
      canVoid: true,
    };
    mocks.api.getFormDetail.mockResolvedValue(
      detail({
        readableVersions: [
          { versionId: '22', versionLabel: 'v1', versionStatus: 'PUBLISHED', instanceMode: 'MATERIALIZED', fieldSpecHash: 'a'.repeat(64) },
        ],
        versionSpecs: { '22': codeReviewSpec },
        records: [orphan],
      }),
    );
    const current = await mountView(
      '/external/forms?gitlabBaseUrl=https%3A%2F%2Fgitlab.example.com&projectId=7&resourceType=merge_request&resourceId=3&templateKey=code_review',
    );

    expect(current.text()).toContain('记录版本不可用');
    expect(current.text()).not.toContain('补充说明');
    expect(current.text()).not.toContain('不应套用当前字段');
    expect(findButton(current, '编辑')).toBeUndefined();
    expect(findButton(current, '作废')).toBeUndefined();
  });

  it('keeps verification available for an unknown edit whose storage version spec is missing', async () => {
    sessionStorage.setItem('qaflex.form.editing-session', 'session-view');
    const context = {
      gitlabBaseUrl: 'https://gitlab.example.com',
      projectId: 7,
      resourceType: 'merge_request',
      resourceId: '3',
      templateKey: 'code_review',
    };
    saveFormDraft({
      draftSpecVersion: 2,
      originVersionId: '22',
      baseStorageVersionId: '77',
      versionSpecHash: 'b'.repeat(64),
      accountUserId: 'alice',
      contextKey: formContextKey(context),
      context: { ...context, scopeId: '11', storageVersionId: '77' },
      draftId: 'draft-unknown-missing-spec',
      editingSessionId: 'session-view',
      kind: 'edit',
      targetRecordId: '303',
      baseRecordVersion: 3,
      baseFields: { reviewerUserId: 'alice', supplementNote: '原值' },
      fields: { reviewerUserId: 'alice', supplementNote: '未知结果内容' },
      requestKey: null,
      firstPendingSnapshot: null,
      inputRevision: 2,
      persistedRevision: 2,
      savedAt: '2026-10-23T01:00:00.000Z',
      status: 'editing',
      submitState: 'unknown',
      submitSnapshot: {
        requestKey: null,
        fields: { reviewerUserId: 'alice', supplementNote: '未知结果内容' },
        confirmSameReviewer: false,
        baseRecordVersion: 3,
        capturedAt: '2026-10-23T01:00:00.000Z',
      },
      savedElsewhere: null,
      updatedAt: '2026-10-23T01:00:00.000Z',
    });
    const orphan: FormRecordView = {
      id: '303',
      recordVersion: 4,
      deleted: false,
      legacyIncomplete: false,
      legacyMissingFields: [],
      fields: { reviewerUserId: 'alice', supplementNote: '未知结果内容' },
      fieldNames: { reviewerUserId: 'Alice' },
      snapshotValues: {},
      createdBy: 'alice',
      createdByName: 'Alice',
      templateVersion: 'v9',
      originVersionId: '22',
      storageVersionId: '77',
      createdAt: '2026-10-23T01:00:00.000Z',
      updatedAt: '2026-10-23T01:00:00.000Z',
      canEdit: true,
      canVoid: true,
    };
    mocks.api.getFormDetail.mockResolvedValue(
      detail({
        readableVersions: [
          { versionId: '22', versionLabel: 'v1', versionStatus: 'PUBLISHED', instanceMode: 'MATERIALIZED', fieldSpecHash: 'a'.repeat(64) },
        ],
        versionSpecs: { '22': codeReviewSpec },
        records: [orphan],
      }),
    );
    const current = await mountView(
      '/external/forms?gitlabBaseUrl=https%3A%2F%2Fgitlab.example.com&projectId=7&resourceType=merge_request&resourceId=3&templateKey=code_review',
    );

    expect(current.text()).toContain('记录版本不可用');
    const verify = findButton(current, '核验提交结果');
    expect(verify).toBeTruthy();
    const cancel = findButton(current, '取消')!;
    expect(cancel.attributes('disabled')).toBeDefined();

    await verify!.trigger('click');
    await flushPromises();
    await flushPromises();

    expect(current.text()).toContain('所属结构版本当前不可用');
    expect(current.text()).not.toContain('记录已有新的保存结果');
    expect(findButton(current, '核验提交结果')).toBeTruthy();
    expect(findButton(current, '取消')!.attributes('disabled')).toBeDefined();
  });

  it('restores the local draft for the same account and editing session', async () => {
    sessionStorage.setItem('qaflex.form.editing-session', 'session-view');
    const context = {
      gitlabBaseUrl: 'https://gitlab.example.com',
      projectId: 7,
      resourceType: 'merge_request',
      resourceId: '3',
      templateKey: 'code_review',
    };
    const draft: FormDraftRecord = {
      draftSpecVersion: 1,
      originVersionId: null,
      baseStorageVersionId: '22',
      versionSpecHash: null,
      accountUserId: 'alice',
      contextKey: formContextKey(context),
      context: { ...context, scopeId: '11', storageVersionId: '22' },
      draftId: 'draft-view',
      editingSessionId: 'session-view',
      kind: 'create',
      targetRecordId: null,
      baseRecordVersion: null,
      baseFields: { reviewerUserId: null, supplementNote: null },
      fields: { reviewerUserId: 'alice', supplementNote: '本机草稿内容' },
      requestKey: 'request-view',
      firstPendingSnapshot: null,
      inputRevision: 2,
      persistedRevision: 2,
      savedAt: '2026-10-23T01:00:00.000Z',
      status: 'editing',
      submitState: 'none',
      submitSnapshot: null,
      savedElsewhere: null,
      updatedAt: '2026-10-23T01:00:00.000Z',
    };
    saveFormDraft(draft);
    mocks.api.getFormDetail.mockResolvedValue(detail());

    const current = await mountView(
      '/external/forms?gitlabBaseUrl=https%3A%2F%2Fgitlab.example.com&projectId=7&resourceType=merge_request&resourceId=3&templateKey=code_review',
    );

    expect(current.text()).toContain('已恢复本机草稿，尚未提交');
    expect(current.text()).toContain('尚未提交');
    const textarea = current.find('textarea');
    expect((textarea.element as HTMLTextAreaElement).value).toBe('本机草稿内容');
  });

  it('lists registered issue templates and marks unregistered ones', async () => {
    mocks.api.getFormDetail.mockImplementation(async (params: { templateKey: string }) => {
      if (params.templateKey === 'issue_response') {
        return detail({
          templateKey: 'issue_reply',
          fieldSpec: issueReplySpec,
          resource: {
            gitlabBaseUrl: 'https://gitlab.example.com',
            projectId: '7',
            resourceType: 'issue',
            resourceId: '31',
            rootId: '41',
            webUrl: 'https://gitlab.example.com/group/project/-/issues/31',
            severityLevel: null,
          },
        });
      }
      throw new ApiBizError('该资源尚未登记表单模板范围', 'A0400');
    });

    const current = await mountView(
      '/external/forms?gitlabBaseUrl=https%3A%2F%2Fgitlab.example.com&projectId=7&resourceType=issue&resourceId=31',
    );

    expect(current.text()).toContain('问题响应');
    expect(current.text()).toContain('问题回复');
    expect(current.text()).toContain('进入填写');
    expect(current.text()).toContain('未登记模板');
  });

  it('keeps focus and selection in a block while the saved list refreshes', async () => {
    mocks.api.getFormDetail.mockResolvedValue(detail());
    const current = await mountView(
      '/external/forms?gitlabBaseUrl=https%3A%2F%2Fgitlab.example.com&projectId=7&resourceType=merge_request&resourceId=3&templateKey=code_review',
    );
    const add = findButton(current, '新增填写');
    await add!.trigger('click');
    await flushPromises();

    const textarea = current.find('textarea');
    await textarea.setValue('保留焦点');
    const element = textarea.element as HTMLTextAreaElement;
    element.focus();
    element.setSelectionRange(2, 2);

    const refresh = findButton(current, '刷新');
    expect(refresh).toBeTruthy();
    await refresh!.trigger('click');
    await flushPromises();

    expect(mocks.api.getFormDetail).toHaveBeenCalledTimes(2);
    expect(document.activeElement).toBe(element);
    expect(element.selectionStart).toBe(2);
    expect(current.find('textarea').element).toBe(element);
  });

  it('blocks the ordinary discard button while a submission result is unknown', async () => {
    mockIssueDetails();
    mocks.api.createFormRecord.mockRejectedValueOnce(
      new HttpRequestError('服务暂不可用', 503),
    );
    const current = await mountView(ISSUE_REPLY_PATH);
    await findButton(current, '新增填写')!.trigger('click');
    await flushPromises();
    await current.find('textarea').setValue('待核验内容');

    await findButton(current, '保存')!.trigger('click');
    await flushPromises();
    await flushPromises();

    expect(current.text()).toContain('提交结果待确认');
    expect(current.text()).toContain('核验提交结果');
    const discard = findButton(current, '取消')!;
    expect(discard.attributes('disabled')).toBeDefined();
    await discard.trigger('click');
    await flushPromises();
    expect(current.findAll('.form-block')).toHaveLength(1);
    expect((current.find('textarea').element as HTMLTextAreaElement).value).toBe('待核验内容');
    expect(mocks.messageBox.confirm).not.toHaveBeenCalled();
  });

  it('asks before returning to the template entry and keeps url, input and focus when cancelled', async () => {
    mockIssueDetails();
    const current = await mountView(ISSUE_REPLY_PATH);
    await findButton(current, '新增填写')!.trigger('click');
    await flushPromises();
    const textarea = current.find('textarea');
    await textarea.setValue('尚未提交的问题回复');
    const element = textarea.element as HTMLTextAreaElement;
    element.focus();
    element.setSelectionRange(2, 2);

    let rejectConfirm: ((reason?: unknown) => void) | null = null;
    mocks.messageBox.confirm.mockImplementation(
      () => new Promise((_resolve, reject) => {
        rejectConfirm = reject;
      }),
    );
    const backButton = findButton(current, '返回模板入口')!;
    // 鼠标按下会把焦点移到按钮；取消后应恢复到刚才编辑的字段。
    (backButton.element as HTMLElement).focus();
    await backButton.trigger('click');
    await flushPromises();

    expect(mocks.messageBox.confirm).toHaveBeenCalledTimes(1);
    element.blur();
    rejectConfirm!(new Error('cancel'));
    await flushPromises();
    await flushPromises();

    expect(activeRouter!.currentRoute.value.query.templateKey).toBe('issue_reply');
    expect(current.find('textarea').element).toBe(element);
    expect(element.value).toBe('尚未提交的问题回复');
    expect(document.activeElement).toBe(element);
    expect(element.selectionStart).toBe(2);
  });

  it('saves the draft and switches to the template entry once confirmed', async () => {
    mockIssueDetails();
    mocks.messageBox.confirm.mockResolvedValue(undefined);
    const current = await mountView(ISSUE_REPLY_PATH);
    await findButton(current, '新增填写')!.trigger('click');
    await flushPromises();
    await current.find('textarea').setValue('确认离开前的内容');

    await findButton(current, '返回模板入口')!.trigger('click');
    await flushPromises();
    await flushPromises();

    expect(mocks.messageBox.confirm).toHaveBeenCalledTimes(1);
    expect(activeRouter!.currentRoute.value.query.templateKey).toBeUndefined();
    expect(current.text()).toContain('选择填写模板');
    const stored = Object.keys(localStorage).filter((key) =>
      key.startsWith('qaflex.form.draft.v2'),
    );
    expect(stored).toHaveLength(1);
    expect(localStorage.getItem(stored[0])).toContain('确认离开前的内容');
  });

  it('asks once before switching templates inside the same route', async () => {
    mockIssueDetails();
    const current = await mountView(ISSUE_REPLY_PATH);
    await findButton(current, '新增填写')!.trigger('click');
    await flushPromises();
    await current.find('textarea').setValue('切换模板前的内容');
    mocks.messageBox.confirm.mockRejectedValue(new Error('cancel'));

    await activeRouter!.push({
      path: '/external/forms',
      query: { ...ISSUE_BASE_QUERY, templateKey: 'issue_response' },
    });
    await flushPromises();

    expect(mocks.messageBox.confirm).toHaveBeenCalledTimes(1);
    expect(activeRouter!.currentRoute.value.query.templateKey).toBe('issue_reply');
    expect(current.find('textarea').exists()).toBe(true);
    expect((current.find('textarea').element as HTMLTextAreaElement).value).toBe(
      '切换模板前的内容',
    );
  });

  it('restores the url and input when browser back is cancelled', async () => {
    mockIssueDetails();
    const current = await mountView(
      '/external/forms?gitlabBaseUrl=https%3A%2F%2Fgitlab.example.com&projectId=7&resourceType=issue&resourceId=31',
      'memory',
    );
    const replyCard = current
      .findAll('.form-entry-card')
      .find((card) => card.text().includes('问题回复'));
    expect(replyCard).toBeTruthy();
    await replyCard!.find('button').trigger('click');
    await flushPromises();
    await flushPromises();
    await findButton(current, '新增填写')!.trigger('click');
    await flushPromises();
    await current.find('textarea').setValue('后退前的内容');
    mocks.messageBox.confirm.mockRejectedValue(new Error('cancel'));

    await activeRouter!.back();
    await flushPromises();
    await flushPromises();

    expect(mocks.messageBox.confirm).toHaveBeenCalledTimes(1);
    expect(activeRouter!.currentRoute.value.query.templateKey).toBe('issue_reply');
    expect(current.find('textarea').exists()).toBe(true);
    expect((current.find('textarea').element as HTMLTextAreaElement).value).toBe('后退前的内容');
  });

  it('warns that the content is not persisted locally when leaving after a draft write failure', async () => {
    mockIssueDetails();
    const current = await mountView(ISSUE_REPLY_PATH);
    await findButton(current, '新增填写')!.trigger('click');
    await flushPromises();
    await current.find('textarea').setValue('写入失败的内容');
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('QuotaExceededError');
    });
    mocks.messageBox.confirm.mockResolvedValue(undefined);

    try {
      await findButton(current, '返回模板入口')!.trigger('click');
      await flushPromises();
      await flushPromises();
    } finally {
      setItem.mockRestore();
    }

    expect(mocks.messageBox.confirm).toHaveBeenCalledTimes(1);
    const [message, , options] = mocks.messageBox.confirm.mock.calls[0];
    expect(String(message)).toContain('部分内容未能自动保存到本机');
    expect(options.confirmButtonText).toBe('仍然离开');
    expect(activeRouter!.currentRoute.value.query.templateKey).toBeUndefined();
  });

  it('leaves without asking when a block has no real unsaved content', async () => {
    mockIssueDetails();
    const current = await mountView(ISSUE_REPLY_PATH);
    await findButton(current, '新增填写')!.trigger('click');
    await flushPromises();
    await current.find('textarea').setValue('临时内容');
    await current.find('textarea').setValue('');

    await findButton(current, '返回模板入口')!.trigger('click');
    await flushPromises();
    await flushPromises();

    expect(mocks.messageBox.confirm).not.toHaveBeenCalled();
    expect(activeRouter!.currentRoute.value.query.templateKey).toBeUndefined();
    expect(current.text()).toContain('选择填写模板');
  });

  it('marks a draft read-only when a refresh returns a new structure version', async () => {
    mocks.api.getFormDetail.mockResolvedValue(detail());
    const current = await mountView(
      '/external/forms?gitlabBaseUrl=https%3A%2F%2Fgitlab.example.com&projectId=7&resourceType=merge_request&resourceId=3&templateKey=code_review',
    );
    await findButton(current, '新增填写')!.trigger('click');
    await flushPromises();
    await current.find('textarea').setValue('刷新前的内容');

    mocks.api.getFormDetail.mockResolvedValue(detail({ storageVersionId: '23' }));
    await findButton(current, '刷新')!.trigger('click');
    await flushPromises();
    await flushPromises();

    expect(current.text()).toContain('模板结构已变化，仅可查看');
    expect(current.text()).toContain('当前草稿仅可查看或复制到新填写块');
    expect(current.text()).toContain('复制内容到新填写块');
    const save = findButton(current, '保存')!;
    expect(save.attributes('disabled')).toBeDefined();
    const copy = findButton(current, '复制内容到新填写块')!;
    expect(copy.attributes('disabled')).toBeUndefined();
    expect((current.find('textarea').element as HTMLTextAreaElement).value).toBe(
      '刷新前的内容',
    );
  });

  it('blocks copying an unknown result after a structure change until verified', async () => {
    mockIssueDetails();
    mocks.api.createFormRecord.mockRejectedValueOnce(
      new HttpRequestError('服务暂不可用', 503),
    );
    const current = await mountView(ISSUE_REPLY_PATH);
    await findButton(current, '新增填写')!.trigger('click');
    await flushPromises();
    await current.find('textarea').setValue('待核验的复制内容');
    await findButton(current, '保存')!.trigger('click');
    await flushPromises();
    await flushPromises();
    expect(current.text()).toContain('提交结果待确认');

    mocks.api.getFormDetail.mockResolvedValue({
      ...issueDetail('issue_reply'),
      storageVersionId: '23',
    });
    await findButton(current, '刷新')!.trigger('click');
    await flushPromises();
    await flushPromises();

    expect(current.text()).toContain('模板结构版本已变化，当前草稿仅可查看或复制到新填写块');
    expect(current.findAll('.form-block')).toHaveLength(1);
    const copy = findButton(current, '复制内容到新填写块')!;
    expect(copy.attributes('disabled')).toBeDefined();
    await copy.trigger('click');
    await flushPromises();
    expect(current.findAll('.form-block')).toHaveLength(1);
    expect((current.find('textarea').element as HTMLTextAreaElement).value).toBe(
      '待核验的复制内容',
    );
  });

  it('keeps the unknown basis and explains why verification is blocked', async () => {
    mockIssueDetails();
    mocks.api.createFormRecord
      .mockRejectedValueOnce(new HttpRequestError('服务暂不可用', 503))
      .mockRejectedValueOnce(new HttpRequestError('模板结构版本已变化', 409));
    const current = await mountView(ISSUE_REPLY_PATH);
    await findButton(current, '新增填写')!.trigger('click');
    await flushPromises();
    await current.find('textarea').setValue('核验被拒的内容');
    await findButton(current, '保存')!.trigger('click');
    await flushPromises();
    await flushPromises();
    expect(current.text()).toContain('提交结果待确认');

    mocks.api.getFormDetail.mockResolvedValue({
      ...issueDetail('issue_reply'),
      storageVersionId: '23',
    });
    await findButton(current, '刷新')!.trigger('click');
    await flushPromises();
    await flushPromises();

    await findButton(current, '核验提交结果')!.trigger('click');
    await flushPromises();
    await flushPromises();

    expect(current.text()).toContain('核验未完成');
    expect(current.text()).toContain('提交结果待确认');
    expect(findButton(current, '取消')!.attributes('disabled')).toBeDefined();
    expect(findButton(current, '复制内容到新填写块')!.attributes('disabled')).toBeDefined();
    expect(current.findAll('.form-block')).toHaveLength(1);
    expect((current.find('textarea').element as HTMLTextAreaElement).value).toBe(
      '核验被拒的内容',
    );
  });
});
