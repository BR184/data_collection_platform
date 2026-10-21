import type {
  OptionItemResponse,
  RealtimeWorkspaceStatusResponse,
  StatisticBoardRuleExplanationResponse,
  StatisticFilterField,
  StatisticFilterGroup,
} from '../../types/api';
import type { RecordTableColumn, RecordTableFilterField } from '../../types/record-table';
import type { DataScopeOption, DataScopeProvider } from '../../types/data-scope';
import type { ReadViewTransport } from '../../types/api/read-view';

export interface IssueIllegalRecordRow {
  issueId?: number;
  issueIid: number;
  issueLink?: string | null;
  sourceInstance?: string | null;
  projectId?: number;
  projectName: string;
  title: string;
  issueState: string;
  testingPhase?: string;
  illegalReason: string;
  severityLevel: string;
  priorityLevel?: string;
  bugStatus: string;
  category: string;
  milestoneTitle: string;
  authorName: string;
  assigneeName: string;
  moduleNames: string;
  functionName?: string;
  delayReason?: string;
  delayCause?: string;
  createdAt?: string | null;
  updatedAt?: string | null;
  closedAt?: string | null;
  labels: string[];
}

export interface IssueIllegalRecordListResponse<Row extends IssueIllegalRecordRow = IssueIllegalRecordRow> {
  records: Row[];
  total: number;
  page: number;
  size: number;
  sortField: string;
  sortOrder: 'asc' | 'desc';
}

export interface IssueIllegalRecordFilterOptions {
  projectNames: OptionItemResponse[];
  moduleNames: OptionItemResponse[];
  illegalReasons: OptionItemResponse[];
  severityLevels: OptionItemResponse[];
  issueStates: OptionItemResponse[];
  bugStatuses: OptionItemResponse[];
  categories: OptionItemResponse[];
  milestoneTitles: OptionItemResponse[];
  priorityLevels?: OptionItemResponse[];
  testingPhases?: OptionItemResponse[];
  authorNames?: OptionItemResponse[];
  assigneeNames?: OptionItemResponse[];
}

export interface IssueIllegalRecordQueryParams {
  read?: ReadViewTransport;
  projectId?: string | number | null;
  keyword?: string;
  issueIid?: string;
  title?: string;
  projectName?: string;
  moduleName?: string;
  testingPhase?: string;
  illegalReason?: string;
  severityLevel?: string;
  priorityLevel?: string;
  issueState?: string;
  bugStatus?: string;
  category?: string;
  milestoneTitle?: string;
  authorName?: string;
  assigneeName?: string;
  createdAtStart?: string;
  createdAtEnd?: string;
  updatedAtStart?: string;
  updatedAtEnd?: string;
  filterGroup?: StatisticFilterGroup | null;
  page?: number;
  size?: number;
  sortBy?: string;
  sortOrder?: 'asc' | 'desc';
}

export interface IssueIllegalRecordsPageConfig<Row extends IssueIllegalRecordRow = IssueIllegalRecordRow> {
  pageKey: string;
  loadRecord: (source: string, projectId: number, issueId: number, read: ReadViewTransport) => Promise<Row | null>;
  workspaceKey: string;
  title: string;
  description: string;
  detailKicker: string;
  ruleTitle: string;
  emptyDescription: string;
  totalTagText: (total: number) => string;
  loadRecords: (params: IssueIllegalRecordQueryParams) => Promise<IssueIllegalRecordListResponse<Row>>;
  exportRecords?: (params: IssueIllegalRecordQueryParams) => Promise<Blob>;
  exportFilenamePrefix?: string;
  loadFilterOptions: (projectId?: string | number | null, read?: ReadViewTransport) => Promise<IssueIllegalRecordFilterOptions>;
  loadRuleExplanation: (projectId?: string | number | null, read?: ReadViewTransport) => Promise<StatisticBoardRuleExplanationResponse>;
  loadRealtimeStatus?: (params?: IssueIllegalRecordQueryParams) => Promise<RealtimeWorkspaceStatusResponse>;
  requestRealtimeRefresh?: () => Promise<RealtimeWorkspaceStatusResponse>;
  initialFilterOptions: IssueIllegalRecordFilterOptions;
  buildConditionFields: (options: IssueIllegalRecordFilterOptions) => StatisticFilterField[];
  buildPrimaryFilters?: (options: IssueIllegalRecordFilterOptions) => RecordTableFilterField[];
  nativePrimarySelectKeys?: string[];
  columns: RecordTableColumn[];
  mapRow: (row: Row) => Record<string, unknown>;
  resetClearKeys: string[];
  queryClearKeys: string[];
  defaultSortBy?: string;
  defaultSortOrder?: 'asc' | 'desc';
  defaultProjectId?: string | number;
  /** 页面业务契约固定项目时，路由不能改变真实读取范围。 */
  fixedProjectId?: string | number;
  scopeProvider?: DataScopeProvider;
  buildScopeOptions?: (options: IssueIllegalRecordFilterOptions) => DataScopeOption[];
  createdAtDetailLabel?: string;
  updatedAtDetailLabel?: string;
  issueStateDetailLabel?: string;
  moduleDetailLabel?: string;
  authorDetailLabel?: string;
  assigneeDetailLabel?: string;
  severityDetailLabel?: string;
  bugStatusDetailLabel?: string;
}
