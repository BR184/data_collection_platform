import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import ElementPlus, { ElDropdown, ElSelect } from 'element-plus';
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
  vi.resetAllMocks();
  vi.useFakeTimers();
  document.body.innerHTML = '';
  mocks.api.getFormTemplates.mockResolvedValue(templates());
  mocks.api.getFormScope.mockResolvedValue(detail());
  mocks.api.previewFormScope.mockImplementation(async (_id: number, revision: number) => preview({ draftRevision: revision }));
  mocks.api.saveFormDraft.mockImplementation(async (_id: number, payload: { expectedDraftRevision: number; fieldSpec: FormTemplateSpecView }) => ({ ...draft(payload.expectedDraftRevision + 1), fieldSpec: payload.fieldSpec }));
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
  const found = wrapper.findAll('button').find((item) => item.text() === label);
  if (!found) {
    throw new Error(`Missing button: ${label}`);
  }
  return found;
}


function fieldLabelInputs(wrapper: VueWrapper) {
  return wrapper.findAll('.field-name input');
}


async function publish(view: VueWrapper) {
  await button(view,'发布').trigger('click'); await flushPromises();
  await button(view,'确认发布').trigger('click'); await flushPromises();
}
function names(view: VueWrapper) {
  return fieldLabelInputs(view).map(input => (input.element as HTMLInputElement).value);
}
function setupTwoScopes() {
  const a=detailFor(7,'hidden-a','修复状态');
  const b=detailFor(8,'hidden-b','问题分类');
  a.draft!.versionEvidence={...a.draft!.versionEvidence,scopeDisplayName:'CCProduct'};
  b.draft!.versionEvidence={...b.draft!.versionEvidence,scopeDisplayName:'移动端产品'};
  mocks.api.getFormTemplates.mockResolvedValue([{templateKey:'issue_reply',displayName:'Issue 回复',status:'ACTIVE',scopes:[a.scope,b.scope]}]);
  mocks.api.getFormScope.mockImplementation(async (id:number) => id===7?a:b);
  return {a,b};
}

describe('表单管理单页工作区', () => {
  it('removes an empty unpublished range without adding another confirmation', async () => {
    const d=detail(); d.scope={...d.scope,status:'DRAFT',currentVersionId:null};
    mocks.api.getFormTemplates.mockResolvedValueOnce([{templateKey:'issue_reply',displayName:'Issue 回复',status:'ACTIVE',scopes:[d.scope]}]).mockResolvedValue([]);
    mocks.api.getFormScope.mockResolvedValue(d); mocks.api.deleteFormScope.mockResolvedValue(undefined);
    const view=await mountView(); view.getComponent(ElDropdown).vm.$emit('command','delete'); await flushPromises();
    expect(mocks.api.deleteFormScope).toHaveBeenCalledWith(7);
    expect(mocks.messageBox.confirm).not.toHaveBeenCalled();
    expect(view.find('.empty-workspace').text()).toContain('选择一个表单');
  });
  it('edits a numeric condition in the expanded field and preserves its typed value', async () => {
    const d=detail(); d.draft!.fieldSpec={...spec,fields:[...spec.fields,{name:'count',apiKey:'count',label:'次数',type:'integer',control:'number',storage:{column:'count',columnType:'integer'}}]};
    mocks.api.getFormScope.mockResolvedValue(d);
    const view=await mountView(); await view.get('button[aria-label="展开具体原因"]').trigger('click');
    await button(view,'添加必填条件').trigger('click');
    view.get('.condition-editor').getComponent(ElSelect).vm.$emit('change','count'); await flushPromises();
    await view.get('input[aria-label="条件值"]').setValue('5');
    expect(view.find('.condition-description').text()).toContain('「5」');
    await vi.advanceTimersByTimeAsync(650); await flushPromises();
    expect(mocks.api.saveFormDraft.mock.lastCall?.[1].fieldSpec.conditions).toContainEqual(expect.objectContaining({target:'specific_reason',requiredWhen:{field:'count',equals:5}}));
  });
  it('keeps failed autosave local and does not retry in a loop', async () => {
    const view = await mountView();
    mocks.api.saveFormDraft.mockRejectedValue(new Error('连接中断'));
    await fieldLabelInputs(view)[0].setValue('处理状态');
    await vi.advanceTimersByTimeAsync(650); await flushPromises();
    expect(view.find('.notice').text()).toContain('修改暂未保存');
    await vi.advanceTimersByTimeAsync(5000); await flushPromises();
    expect(mocks.api.saveFormDraft).toHaveBeenCalledTimes(1);
    expect(names(view)[0]).toBe('处理状态');
  });
  it('uses one page with business fields and only one primary action', async () => {
    const view=await mountView();
    expect(view.findAll('.field-card')).toHaveLength(2);
    expect(names(view)).toEqual(['修复状态','具体原因']);
    expect(view.find('.el-tabs').exists()).toBe(false);
    expect(view.findAll('.workspace-header .el-button--primary')).toHaveLength(1);
    expect(button(view,'发布').text()).toBe('发布');
    expect(view.text()).not.toMatch(/fix_status|issue_reply|w4-scope|草稿修订|实例|DDL/);
    expect(view.find('.change-summary').text()).toContain('尚无修改');
  });

  it('shows a rename immediately and does not repeat unchanged attributes', async () => {
    const view=await mountView();
    await fieldLabelInputs(view)[0].setValue('处理状态');
    const summary=view.find('.change-summary');
    expect(summary.text()).toContain('修复状态：名称改为「处理状态」');
    expect(summary.text()).not.toContain('单选');
    expect(view.find('.field-card').text()).toContain('已改');
    expect(view.find('.notice').text()).toContain('1 项修改未发布');
    expect(mocks.messageBox.confirm).not.toHaveBeenCalled();
  });

  it('saves edits quietly without confirmation and preserves stable field identity', async () => {
    const view=await mountView();
    await fieldLabelInputs(view)[0].setValue('处理状态');
    await vi.advanceTimersByTimeAsync(700); await flushPromises();
    expect(mocks.api.saveFormDraft).toHaveBeenCalledWith(7,expect.objectContaining({fieldSpec:expect.objectContaining({fields:expect.arrayContaining([expect.objectContaining({name:'fix_status',label:'处理状态'})])})}));
    expect(mocks.message.success).not.toHaveBeenCalledWith('草稿已保存');
    expect(mocks.messageBox.confirm).not.toHaveBeenCalled();
  });

  it('confirms an existing field deletion once and offers inline undo', async () => {
    const view=await mountView();
    await view.find('button[aria-label="删除具体原因"]').trigger('click'); await flushPromises();
    expect(mocks.messageBox.confirm).toHaveBeenCalledTimes(1);
    expect(names(view)).toEqual(['修复状态']);
    expect(view.find('.removed-card').text()).toContain('待删除');
    expect(view.find('.change-summary').text()).toContain('具体原因：删除，发布后从当前表单移除');
    await button(view,'撤销删除').trigger('click');
    expect(names(view)).toEqual(['修复状态','具体原因']);
  });

  it('deletes a newly added field without a confirmation dialog', async () => {
    const view=await mountView();
    await button(view,'添加字段').trigger('click');
    const cards=view.findAll('.field-card');
    await cards[2].find('.field-actions button:last-child').trigger('click'); await flushPromises();
    expect(view.findAll('.field-card')).toHaveLength(2);
    expect(mocks.messageBox.confirm).not.toHaveBeenCalled();
  });

  it('reorders fields by dragging and keeps record identities', async () => {
    const view=await mountView();
    await view.find('.drag-handle').trigger('dragstart',{dataTransfer:{setData:vi.fn(),effectAllowed:''}});
    await view.findAll('.field-card')[1].trigger('drop');
    expect(names(view)).toEqual(['具体原因','修复状态']);
    expect(view.find('.change-summary').text()).toContain('字段顺序：已重新排列');
    await vi.advanceTimersByTimeAsync(700); await flushPromises();
    const payload=mocks.api.saveFormDraft.mock.calls[0][1];
    expect(payload.fieldSpec.fields.map((field:{name:string})=>field.name)).toEqual(['specific_reason','fix_status']);
  });

  it('edits options within the expanded field without a second dialog', async () => {
    const view=await mountView();
    await view.find('button[aria-label="展开修复状态"]').trigger('click');
    await view.find('.option-row input').setValue('已处理');
    expect(view.find('.change-summary').text()).toContain('「已解决」改为「已处理」');
    expect(mocks.messageBox.confirm).not.toHaveBeenCalled();
  });

  it('saves the current input before opening one publish summary and creates only after confirmation', async () => {
    const view=await mountView();
    await fieldLabelInputs(view)[0].setValue('处理状态');
    await button(view,'发布').trigger('click'); await flushPromises();
    expect(mocks.api.saveFormDraft).toHaveBeenCalled();
    expect(view.find('.confirm-changes').text()).toContain('名称改为「处理状态」');
    expect(mocks.api.createFormPublication).not.toHaveBeenCalled();
    mocks.api.createFormPublication.mockResolvedValue(publication('FAILED'));
    mocks.api.getFormPublication.mockResolvedValue(publication('FAILED'));
    await button(view,'确认发布').trigger('click'); await flushPromises();
    expect(mocks.api.createFormPublication).toHaveBeenCalledWith(7,expect.objectContaining({requestKey:expect.any(String)}));
    expect(mocks.messageBox.confirm).not.toHaveBeenCalled();
  });

  it('uses a single top failure message with direct retry and no engineering log', async () => {
    const d=detail(); d.latestPublication={...publication('FAILED'),lastError:'SQLException deadlock 40P01'};
    mocks.api.getFormScope.mockResolvedValue(d);
    const view=await mountView();
    expect(view.findAll('.notice')).toHaveLength(1);
    expect(view.find('.notice').text()).toContain('上次发布失败：服务暂时繁忙');
    expect(view.find('.publication-card').exists()).toBe(false);
    expect(view.text()).not.toMatch(/SQLException|40P01/);
    mocks.api.recoverFormPublication.mockResolvedValue(publication('FAILED'));
    mocks.api.getFormPublication.mockResolvedValue(publication('FAILED'));
    await button(view,'重试').trigger('click'); await flushPromises();
    expect(mocks.api.recoverFormPublication).toHaveBeenCalledWith(5);
    expect(mocks.messageBox.confirm).not.toHaveBeenCalled();
  });

  it('opens history in a drawer without exposing physical table names', async () => {
    const view=await mountView();
    await button(view,'历史版本').trigger('click'); await flushPromises();
    expect(view.find('.version-list').text()).toContain('CC2026R1');
    expect(document.body.textContent).not.toContain('form_s7_v2_records');
  });
});

describe('单页工作区的异步归属', () => {
  it('keeps local input on a real transport CAS conflict until the administrator chooses', async () => {
    mocks.api.saveFormDraft.mockRejectedValue(new HttpRequestError('已被修改',409,'A0409'));
    mocks.api.getFormScope.mockResolvedValueOnce(detail()).mockResolvedValueOnce(detail(draft(5,'最新名称')));
    const view=await mountView(); await fieldLabelInputs(view)[0].setValue('我的名称');
    await vi.advanceTimersByTimeAsync(650); await flushPromises();
    expect(names(view)[0]).toBe('我的名称');
    expect(view.find('.conflict-fields').text()).toContain('最新名称');
    await button(view,'使用最新内容').trigger('click'); await flushPromises();
    expect(names(view)[0]).toBe('最新名称');
  });

  it('keeps input typed during an autosave and saves that newer input next', async () => {
    let finish!:(value:FormAdminDraftView)=>void;
    mocks.api.saveFormDraft.mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve;}));
    const view=await mountView(); await fieldLabelInputs(view)[0].setValue('已发送名称');
    await vi.advanceTimersByTimeAsync(650); await flushPromises();
    await fieldLabelInputs(view)[0].setValue('后续名称'); finish(draft(3,'已发送名称')); await flushPromises();
    expect(names(view)[0]).toBe('后续名称');
    await vi.advanceTimersByTimeAsync(650); await flushPromises();
    expect(mocks.api.saveFormDraft.mock.lastCall?.[1].fieldSpec.fields[0].label).toBe('后续名称');
    expect(view.find('.notice').text()).toContain('已保存');
  });

  it('does not publish when the scope changes while its confirmation is open', async () => {
    setupTwoScopes(); const view=await mountView();
    await button(view,'发布').trigger('click'); await flushPromises();
    await view.findAll('.scope-item')[1].trigger('click'); await flushPromises();
    await button(view,'确认发布').trigger('click'); await flushPromises();
    expect(mocks.api.createFormPublication).not.toHaveBeenCalled();
  });

  it('keeps the same publication intent after a lost creation response', async () => {
    mocks.api.createFormPublication.mockRejectedValueOnce(new Error('连接中断')).mockResolvedValueOnce(publication('FAILED'));
    mocks.api.getFormPublication.mockResolvedValue(publication('FAILED'));
    const view=await mountView(); await publish(view);
    const first=mocks.api.createFormPublication.mock.calls[0][1]; await publish(view);
    expect(mocks.api.createFormPublication.mock.calls[1][1]).toEqual(first);
  });

  it('drops a delayed poll after returning A→B→A', async () => {
    setupTwoScopes(); mocks.api.createFormPublication.mockResolvedValue(publication('COPYING'));
    let finish!:(value:FormAdminPublicationView)=>void;
    mocks.api.getFormPublication.mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve;}));
    const view=await mountView(); await publish(view);
    await view.findAll('.scope-item')[1].trigger('click'); await flushPromises();
    await view.findAll('.scope-item')[0].trigger('click'); await flushPromises();
    finish({...publication('FAILED'),lastError:'旧结果'}); await flushPromises();
    expect(view.find('.notice-warning').exists()).toBe(false);
  });
  it('drops delayed preview after A→B→A', async () => {
    setupTwoScopes();
    let finish!:(value:FormAdminPreviewView)=>void;
    mocks.api.previewFormScope.mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve;}));
    const view=await mountView();
    await button(view,'发布').trigger('click'); await flushPromises();
    await view.findAll('.scope-item')[1].trigger('click'); await flushPromises();
    await view.findAll('.scope-item')[0].trigger('click'); await flushPromises();
    finish(preview({blockingErrors:['过期预览']})); await flushPromises();
    expect(view.text()).not.toContain('过期预览');
    expect(mocks.api.createFormPublication).not.toHaveBeenCalled();
    expect(view.find('.confirm-changes').exists()).toBe(false);
  });

  it('drops a delayed creation and never renders the task in B', async () => {
    setupTwoScopes();
    let finish!:(value:FormAdminPublicationView)=>void;
    mocks.api.createFormPublication.mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve;}));
    const view=await mountView(); await publish(view);
    await view.findAll('.scope-item')[1].trigger('click'); await flushPromises();
    finish(publication('FAILED')); await flushPromises();
    expect(mocks.api.getFormPublication).not.toHaveBeenCalled();
    expect(view.find('.notice-warning').exists()).toBe(false);
  });

  it('drops a delayed poll and cannot recover A from B', async () => {
    setupTwoScopes();
    mocks.api.createFormPublication.mockResolvedValue(publication('COPYING'));
    let finish!:(value:FormAdminPublicationView)=>void;
    mocks.api.getFormPublication.mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve;}));
    const view=await mountView(); await publish(view);
    await view.findAll('.scope-item')[1].trigger('click'); await flushPromises();
    finish({...publication('FAILED'),lastError:'A任务错误'}); await flushPromises();
    expect(view.find('.notice-warning').exists()).toBe(false);
    expect(mocks.api.recoverFormPublication).not.toHaveBeenCalled();
  });

  it('does not let the final refresh overwrite new input', async () => {
    mocks.api.createFormPublication.mockResolvedValue(publication('COPYING'));
    mocks.api.getFormPublication.mockResolvedValue(publication('PUBLISHED'));
    const view=await mountView();
    let finish!:(value:FormAdminScopeDetail)=>void;
    mocks.api.getFormScope.mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve;}));
    await publish(view);
    await fieldLabelInputs(view)[0].setValue('处理结论');
    finish(detail()); await flushPromises();
    expect(names(view)[0]).toBe('处理结论');
  });

  it('stops a delayed retry from updating another scope', async () => {
    const {a}=setupTwoScopes(); a.latestPublication=publication('FAILED');
    let finish!:(value:FormAdminPublicationView)=>void;
    mocks.api.recoverFormPublication.mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve;}));
    const view=await mountView();
    await button(view,'重试').trigger('click'); await flushPromises();
    await view.findAll('.scope-item')[1].trigger('click'); await flushPromises();
    finish(publication('COPYING')); await flushPromises();
    expect(mocks.api.getFormPublication).not.toHaveBeenCalled();
    expect(view.find('.notice-warning').exists()).toBe(false);
  });
});
