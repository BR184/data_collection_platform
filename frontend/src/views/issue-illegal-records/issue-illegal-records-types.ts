import type {
  OptionItemResponse,
  StatisticBoardRuleExplanationResponse,
  StatisticFilterField,
  StatisticFilterGroup,
} from '../../types/api';
import type { RecordTableColumn } from '../../types/record-table';

export interface IssueIllegalRecordRow {
  issueId?: number;
  issueIid: number;
  issueLink?: string | null;
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
  workspaceKey: string;
  title: string;
  description: string;
  detailKicker: string;
  ruleTitle: string;
  emptyDescription: string;
  totalTagText: (total: number) => string;
  loadRecords: (params: IssueIllegalRecordQueryParams) => Promise<IssueIllegalRecordListResponse<Row>>;
  loadFilterOptions: (projectId?: string | number | null) => Promise<IssueIllegalRecordFilterOptions>;
  loadRuleExplanation: (projectId?: string | number | null) => Promise<StatisticBoardRuleExplanationResponse>;
  initialFilterOptions: IssueIllegalRecordFilterOptions;
  buildConditionFields: (options: IssueIllegalRecordFilterOptions) => StatisticFilterField[];
  columns: RecordTableColumn[];
  mapRow: (row: Row) => Record<string, unknown>;
  resetClearKeys: string[];
  queryClearKeys: string[];
  defaultSortBy?: string;
  defaultSortOrder?: 'asc' | 'desc';
}
