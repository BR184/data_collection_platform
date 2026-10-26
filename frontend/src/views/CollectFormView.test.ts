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
import type { FormDetailResponse, FormTemplateSpecView } from '../types/api';
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
    storageVersionId: '22',
    versionLabel: 'v2026.10',
    scopeStatus: 'ACTIVE',
    writable: true,
    instanceId: '31',
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
  it('renders the published field spec as a single column and adds independent blocks', async () => {
    mocks.api.getFormDetail.mockResolvedValue(detail());
    const current = await mountView(
      '/external/forms?gitlabBaseUrl=https%3A%2F%2Fgitlab.example.com&projectId=7&resourceType=merge_request&resourceId=3&templateKey=code_review',
    );

    expect(current.text()).toContain('代码走查表');
    expect(current.text()).toContain('v2026.10');
    expect(current.text()).toContain('已保存记录（0）');
    expect(current.text()).toContain('本机填写草稿（0）');

    const add = findButton(current, '新增填写块');
    expect(add).toBeTruthy();
    await add!.trigger('click');
    await flushPromises();

    expect(current.findAll('.form-single-column .el-form-item')).toHaveLength(2);
    expect(current.text()).toContain('走查人');
    expect(current.text()).toContain('补充说明');
    expect(current.text()).toContain('尚未填写');
    expect(current.text()).toContain('草稿仅保存在当前浏览器');
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
    const add = findButton(current, '新增填写块');
    await add!.trigger('click');
    await flushPromises();

    const textarea = current.find('textarea');
    await textarea.setValue('保留焦点');
    const element = textarea.element as HTMLTextAreaElement;
    element.focus();
    element.setSelectionRange(2, 2);

    const refresh = findButton(current, '刷新列表');
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
    await findButton(current, '新增填写块')!.trigger('click');
    await flushPromises();
    await current.find('textarea').setValue('待核验内容');

    await findButton(current, '保存')!.trigger('click');
    await flushPromises();
    await flushPromises();

    expect(current.text()).toContain('提交结果待确认');
    expect(current.text()).toContain('核验提交结果');
    const discard = findButton(current, '丢弃草稿')!;
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
    await findButton(current, '新增填写块')!.trigger('click');
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
    await findButton(current, '新增填写块')!.trigger('click');
    await flushPromises();
    await current.find('textarea').setValue('确认离开前的内容');

    await findButton(current, '返回模板入口')!.trigger('click');
    await flushPromises();
    await flushPromises();

    expect(mocks.messageBox.confirm).toHaveBeenCalledTimes(1);
    expect(activeRouter!.currentRoute.value.query.templateKey).toBeUndefined();
    expect(current.text()).toContain('选择填写模板');
    const stored = Object.keys(localStorage).filter((key) =>
      key.startsWith('qaflex.form.draft.v1'),
    );
    expect(stored).toHaveLength(1);
    expect(localStorage.getItem(stored[0])).toContain('确认离开前的内容');
  });

  it('asks once before switching templates inside the same route', async () => {
    mockIssueDetails();
    const current = await mountView(ISSUE_REPLY_PATH);
    await findButton(current, '新增填写块')!.trigger('click');
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
    await findButton(current, '新增填写块')!.trigger('click');
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
    await findButton(current, '新增填写块')!.trigger('click');
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
    await findButton(current, '新增填写块')!.trigger('click');
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
    await findButton(current, '新增填写块')!.trigger('click');
    await flushPromises();
    await current.find('textarea').setValue('刷新前的内容');

    mocks.api.getFormDetail.mockResolvedValue(detail({ storageVersionId: '23' }));
    await findButton(current, '刷新列表')!.trigger('click');
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
    await findButton(current, '新增填写块')!.trigger('click');
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
    await findButton(current, '刷新列表')!.trigger('click');
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
    await findButton(current, '新增填写块')!.trigger('click');
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
    await findButton(current, '刷新列表')!.trigger('click');
    await flushPromises();
    await flushPromises();

    await findButton(current, '核验提交结果')!.trigger('click');
    await flushPromises();
    await flushPromises();

    expect(current.text()).toContain('核验未完成');
    expect(current.text()).toContain('提交结果待确认');
    expect(findButton(current, '丢弃草稿')!.attributes('disabled')).toBeDefined();
    expect(findButton(current, '复制内容到新填写块')!.attributes('disabled')).toBeDefined();
    expect(current.findAll('.form-block')).toHaveLength(1);
    expect((current.find('textarea').element as HTMLTextAreaElement).value).toBe(
      '核验被拒的内容',
    );
  });
});
