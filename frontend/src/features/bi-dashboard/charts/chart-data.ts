export interface NamedValue {
  name: string;
  value: number;
  color?: string;
  category?: string;
}

export interface CategorySeries {
  name: string;
  values: number[];
  color?: string;
}

export interface CategorySeriesData {
  categories: string[];
  series: CategorySeries[];
}

export interface CodingTrendData {
  periods: string[];
  addedLines: number[];
  cumulativeLines: number[];
}

export interface SubmissionTrendData {
  periods: string[];
  commits: Array<number | null>;
  mergeRequests: number[];
}

export interface OverlayBarRow {
  name: string;
  total: number;
  overlay: number;
}

export interface DeveloperWorkloadRow {
  name: string;
  total: number;
  open: number;
  fixed: number;
}

export interface RoundQualityRow {
  name: string;
  levelOne: number;
  levelTwo: number;
  levelThree: number;
  submitted: number;
  closed: number;
  open: number;
  closeRate: number | null;
}

export interface ModuleRepairRow {
  name: string;
  fixRate: number | null;
  levelOneRate: number | null;
  p1Rate: number | null;
  p2Rate: number | null;
  openCount?: number;
  totalCount?: number;
}

export interface TestAttainmentRow {
  id: string;
  name: string;
  passRate: number | null;
  targetRate: number | null;
  counts: {
    label: string;
    attained: number;
    total: number;
  } | null;
  achieved: boolean | null;
}

export interface ReviewQualityRow {
  name: string;
  density: number | null;
  rate: number | null;
  achieved: boolean | null;
}

export interface ReviewScatterPoint {
  name: string;
  date: string;
  rate: number | null;
  density: number | null;
  achieved: boolean | null;
}

export interface QualityTrendData {
  periods: string[];
  commentRates: Array<number | null>;
  defectDensities: Array<number | null>;
}

export interface DelayHeatmapData {
  reasons: string[];
  severities: string[];
  values: Array<[number, number, number]>;
}

export interface DefectCauseBreakdownItem {
  id: string;
  name: string;
  groupId: string;
  groupName: string;
  count: number;
  sharePercent: number | null;
  color: string;
}

export interface DefectCauseBreakdownData {
  items: DefectCauseBreakdownItem[];
  unclassifiedCount: number;
  unclassifiedSharePercent: number | null;
}
