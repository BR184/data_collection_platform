export interface FormFieldOptionView {
  value: string;
  label: string;
}

export interface FormDictionaryItemView {
  key: string;
  label: string;
  group?: string | null;
}

export interface FormDictionaryView {
  name: string;
  version?: string;
  items: FormDictionaryItemView[];
}

export type FormConditionView = Record<string, unknown>;

/** 已发布 field_spec 中一个字段的冻结规格；未使用的可选键可能不出现。 */
export interface FormFieldSpecView {
  name: string;
  apiKey: string;
  label: string;
  type: 'string' | 'integer' | 'boolean' | 'date' | 'enum' | 'enum-array';
  control?: string;
  required?: boolean;
  maxLength?: number;
  min?: number;
  max?: number;
  options?: FormFieldOptionView[];
  dictionary?: string | null;
  maxItems?: number;
  minYear?: number;
  maxYear?: number;
  format?: string;
  optionsSource?: string;
  requiredWhen?: FormConditionView | null;
  clearWhen?: FormConditionView | null;
  storage?: Record<string, unknown>;
}

export interface FormSpecConditionView extends FormConditionView {
  type: string;
}

export interface FormTemplateSpecView {
  spec_version: number;
  template_key: string;
  fields: FormFieldSpecView[];
  conditions?: FormSpecConditionView[];
  dictionaries?: FormDictionaryView[];
}

/**
 * 统一表单记录接口的返回类型；bigint ID 一律使用十进制字符串。
 */
export interface FormRecordView {
  id: string;
  recordVersion: number;
  deleted: boolean;
  legacyIncomplete: boolean;
  legacyMissingFields: string[];
  fields: Record<string, unknown>;
  fieldNames: Record<string, string>;
  snapshotValues: Record<string, boolean>;
  createdBy: string | null;
  createdByName: string | null;
  templateVersion: string | null;
  originVersionId: string;
  storageVersionId: string;
  createdAt: string | null;
  updatedAt: string | null;
  canEdit: boolean;
  canVoid: boolean;
}

export interface FormResourceView {
  gitlabBaseUrl: string;
  projectId: string;
  resourceType: string;
  resourceId: string;
  rootId: string | null;
  webUrl: string | null;
  severityLevel: string | null;
}

export interface FormAccountView {
  userId: string;
  displayName: string;
}

export interface FormCapabilitiesView {
  canCreate: boolean;
  canEditOwn: boolean;
  canVoidOwn: boolean;
}

export interface FormReadableVersionView {
  versionId: string;
  versionLabel: string | null;
  versionStatus: string;
  instanceMode: 'MATERIALIZED' | 'NONE';
  fieldSpecHash: string;
}

export interface FormDetailResponse {
  templateKey: string;
  fieldSpec: FormTemplateSpecView;
  scopeId: string;
  currentVersionId: string;
  storageVersionId: string;
  versionLabel: string | null;
  scopeStatus: string;
  writable: boolean;
  instanceId: string | null;
  readableVersions: FormReadableVersionView[];
  versionSpecs: Record<string, FormTemplateSpecView>;
  sourceSetRevision: string;
  sourceSetHash: string;
  records: FormRecordView[];
  resource: FormResourceView;
  account: FormAccountView;
  capabilities: FormCapabilitiesView;
}

export type FormCreateOutcome = 'CREATED' | 'REPLAYED' | 'CONFIRM_REQUIRED';

export interface FormCreateResponse {
  outcome: FormCreateOutcome;
  record: FormRecordView | null;
  reviewerUserId: string | null;
}

export interface FormReviewerCandidate {
  userId: string;
  realName: string;
  deptName: string | null;
}

export interface FormReviewerCandidatesResponse {
  items: FormReviewerCandidate[];
  nextCursor: string | null;
  currentUserCandidate: FormReviewerCandidate | null;
}

export interface CollectFormNotificationPayloadResponse {
  sourceAddress: string;
  projectId: number;
  requestIid: number;
  resourceType: string;
}

/** 管理端范围摘要；当前版本为空表示尚未完成首次发布。 */
export interface FormAdminScopeSummary {
  id: number;
  templateKey: string;
  scopeKey: string;
  status: 'DRAFT' | 'ACTIVE' | 'RETIRED';
  resourceType: string;
  sourceConfigId: number | null;
  sourceInstance: string | null;
  canonicalGitlabBaseUrl: string | null;
  projectIds: number[];
  versionProjectId: number | null;
  bindingHash: string | null;
  bindingRevision: number;
  currentVersionId: number | null;
  currentVersionLabel: string | null;
  currentVersionStatus: string | null;
  draftRevision: number | null;
  writeFenced: boolean;
  activePublications: number;
  updatedAt: string;
}

export interface FormAdminTemplateSummary {
  templateKey: string;
  displayName: string;
  status: string;
  scopes: FormAdminScopeSummary[];
}

export interface FormAdminBindingView {
  sourceConfigId: number | null;
  sourceInstance: string | null;
  canonicalGitlabBaseUrl: string | null;
  projectIds: number[];
  versionProjectId: number | null;
  bindingHash: string | null;
  bound: boolean;
}

export interface FormAdminDraftView {
  revision: number;
  fieldSpec: FormTemplateSpecView;
  binding: FormAdminBindingView;
  versionLabel: string | null;
  versionEvidence: Record<string, unknown> | null;
  migrationSpec: Record<string, unknown> | null;
  updatedBy: string;
  updatedAt: string;
}

export interface FormAdminVersionView {
  id: number;
  versionLabel: string | null;
  storageKind: 'LEGACY' | 'RECORDS';
  versionStatus: 'INSTALLED' | 'PUBLISHED' | 'RETIRED';
  instanceTable: string;
  recordTable: string | null;
  publishedAt: string | null;
}

export interface FormAdminPreviewView {
  id: number;
  draftRevision: number;
  targetVersionId: number;
  previewHash: string;
  publicationKind?: FormPublicationKind;
  diff: {
    fields?: Array<Record<string, unknown>>;
    added?: number;
    removed?: number;
    modified?: number;
  };
  ddlSummary: Array<Record<string, unknown>>;
  ddlHash: string | null;
  impact: { records?: number; instances?: number; resources?: number; sourceRows?: number };
  blockingErrors: string[];
  createdAt: string;
}

export type FormPublicationKind = 'INITIAL_EMPTY' | 'INITIAL_MIGRATION' | 'MIGRATION';
export type FormPublicationStage = 'PREPARED' | 'COPYING' | 'VERIFIED' | 'PUBLISHED' | 'FAILED';

export interface FormAdminPublicationView {
  id: number;
  scopeId: number;
  requestKey: string;
  publicationKind: FormPublicationKind;
  stage: FormPublicationStage;
  attemptNo: number;
  recoveryCycle: number;
  expectedCurrentVersionId: number | null;
  fromVersionId: number | null;
  toVersionId: number;
  lastError: string | null;
  selectionCounts: Record<string, unknown> | null;
  verificationSummary: Record<string, unknown> | null;
  createdAt: string;
  updatedAt: string;
  publishedAt: string | null;
}

export interface FormAdminScopeDetail {
  scope: FormAdminScopeSummary;
  draft: FormAdminDraftView | null;
  versions: FormAdminVersionView[];
  preview: FormAdminPreviewView | null;
  latestPublication: FormAdminPublicationView | null;
  recordCount: number;
  instanceCount: number;
}

export interface FormAdminBindingOption {
  sourceConfigId: number;
  name: string;
  sourceInstance: string;
  canonicalGitlabBaseUrl: string | null;
}

export interface FormAdminMilestone {
  id: number;
  title: string;
}

export interface FormAdminMilestoneAvailability {
  available: boolean;
  items: FormAdminMilestone[];
}

export interface FormDraftUpdatePayload {
  expectedDraftRevision: number;
  fieldSpec: FormTemplateSpecView;
  binding?: {
    sourceConfigId: number;
    projectIds: number[];
    versionProjectId: number;
  };
  versionLabel: string | null;
  versionEvidence: Record<string, unknown> | null;
  migrationSpec?: Record<string, unknown> | null;
}

export interface FormPublicationCreatePayload {
  draftRevision: number;
  previewId: number;
  expectedCurrentVersionId: number | null;
  requestKey: string;
}
