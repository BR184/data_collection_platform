import type {
  CategorySeriesData,
  DeveloperWorkloadRow,
  ModuleRepairRow,
  NamedValue,
  ReviewQualityRow,
  ReviewScatterPoint,
  RoundQualityRow,
} from '../charts/chart-data';

export type BiSortOrder = 'asc' | 'desc';

export interface BiSortOption {
  label: string;
  value: string;
}

/** 对 NamedValue 列表（柱图/饼图）按数值或名称进行排序 */
export function sortNamedValues(
  items: NamedValue[],
  sortBy: string,
  order: BiSortOrder = 'desc',
): NamedValue[] {
  return items.slice().sort((a, b) => {
    if (sortBy === 'name') {
      const cmp = a.name.localeCompare(b.name, 'zh-CN');
      return order === 'asc' ? cmp : -cmp;
    }
    const cmp = (a.value ?? 0) - (b.value ?? 0);
    return order === 'asc' ? cmp : -cmp;
  });
}

/** 对模块评审质量列表进行多字段排序 */
export function sortReviewQualityRows(
  items: ReviewQualityRow[],
  sortBy: string,
  order: BiSortOrder = 'desc',
): ReviewQualityRow[] {
  return items.slice().sort((a, b) => {
    if (sortBy === 'status') {
      const cmp = Number(a.achieved ?? true) - Number(b.achieved ?? true);
      if (cmp !== 0) return cmp;
      return (b.density ?? -1) - (a.density ?? -1);
    }
    if (sortBy === 'rate') {
      const cmp = (a.rate ?? -1) - (b.rate ?? -1);
      return order === 'asc' ? cmp : -cmp;
    }
    if (sortBy === 'name') {
      const cmp = a.name.localeCompare(b.name, 'zh-CN');
      return order === 'asc' ? cmp : -cmp;
    }
    // 默认 density
    const cmp = (a.density ?? -1) - (b.density ?? -1);
    return order === 'asc' ? cmp : -cmp;
  });
}

/** 对评审散点进行排序 */
export function sortReviewScatterPoints(
  items: ReviewScatterPoint[],
  sortBy: string,
  order: BiSortOrder = 'desc',
): ReviewScatterPoint[] {
  return items.slice().sort((a, b) => {
    if (sortBy === 'date') {
      const cmp = (a.date ?? '').localeCompare(b.date ?? '');
      return order === 'asc' ? cmp : -cmp;
    }
    if (sortBy === 'rate') {
      const cmp = (a.rate ?? -1) - (b.rate ?? -1);
      return order === 'asc' ? cmp : -cmp;
    }
    if (sortBy === 'name') {
      const cmp = a.name.localeCompare(b.name, 'zh-CN');
      return order === 'asc' ? cmp : -cmp;
    }
    const cmp = (a.density ?? -1) - (b.density ?? -1);
    return order === 'asc' ? cmp : -cmp;
  });
}

/** 对 CategorySeriesData（如静态代码扫描）按各系列数值或分类名排序 */
export function sortCategorySeriesData(
  data: CategorySeriesData,
  sortBy: string,
  order: BiSortOrder = 'desc',
): CategorySeriesData {
  if (!data.categories.length) return data;
  const indices = data.categories.map((_, i) => i);
  indices.sort((a, b) => {
    if (sortBy === 'name') {
      const cmp = data.categories[a]!.localeCompare(data.categories[b]!, 'zh-CN');
      return order === 'asc' ? cmp : -cmp;
    }
    if (sortBy === 'records') {
      const seriesRecords = data.series.find((s) => s.name.includes('记录')) ?? data.series[0];
      const valA = seriesRecords?.values[a] ?? 0;
      const valB = seriesRecords?.values[b] ?? 0;
      const cmp = valA - valB;
      return order === 'asc' ? cmp : -cmp;
    }
    // 默认 bugs 或总数
    const seriesBugs = data.series.find((s) => s.name.includes('问题') || s.name.includes('违规')) ?? data.series[1] ?? data.series[0];
    const valA = seriesBugs?.values[a] ?? 0;
    const valB = seriesBugs?.values[b] ?? 0;
    const cmp = valA - valB;
    return order === 'asc' ? cmp : -cmp;
  });

  return {
    categories: indices.map((i) => data.categories[i]!),
    series: data.series.map((s) => ({
      name: s.name,
      values: indices.map((i) => s.values[i] ?? 0),
    })),
  };
}

/** 对轮次质量数据进行排序 */
export function sortRoundQualityRows(
  items: RoundQualityRow[],
  sortBy: string,
  order: BiSortOrder = 'desc',
): RoundQualityRow[] {
  return items.slice().sort((a, b) => {
    if (sortBy === 'submitted') {
      const cmp = a.submitted - b.submitted;
      return order === 'asc' ? cmp : -cmp;
    }
    if (sortBy === 'closed') {
      const cmp = a.closed - b.closed;
      return order === 'asc' ? cmp : -cmp;
    }
    if (sortBy === 'open') {
      const cmp = a.open - b.open;
      return order === 'asc' ? cmp : -cmp;
    }
    if (sortBy === 'closeRate') {
      const cmp = (a.closeRate ?? 0) - (b.closeRate ?? 0);
      return order === 'asc' ? cmp : -cmp;
    }
    // 默认 round 顺序 (name)
    const cmp = a.name.localeCompare(b.name, 'zh-CN', { numeric: true });
    return order === 'asc' ? cmp : -cmp;
  });
}

/** 对模块系统测试修复率达成数据进行排序 */
export function sortModuleRepairRows(
  items: ModuleRepairRow[],
  sortBy: string,
  order: BiSortOrder = 'desc',
): ModuleRepairRow[] {
  return items.slice().sort((a, b) => {
    if (sortBy === 'open') {
      const cmp = (a.openCount ?? 0) - (b.openCount ?? 0);
      return order === 'asc' ? cmp : -cmp;
    }
    if (sortBy === 'total') {
      const cmp = (a.totalCount ?? 0) - (b.totalCount ?? 0);
      return order === 'asc' ? cmp : -cmp;
    }
    if (sortBy === 'rate') {
      const cmp = (a.fixRate ?? 0) - (b.fixRate ?? 0);
      return order === 'asc' ? cmp : -cmp;
    }
    if (sortBy === 'levelOneRate') {
      const cmp = (a.levelOneRate ?? 0) - (b.levelOneRate ?? 0);
      return order === 'asc' ? cmp : -cmp;
    }
    if (sortBy === 'p1Rate') {
      const cmp = (a.p1Rate ?? 0) - (b.p1Rate ?? 0);
      return order === 'asc' ? cmp : -cmp;
    }
    if (sortBy === 'p2Rate') {
      const cmp = (a.p2Rate ?? 0) - (b.p2Rate ?? 0);
      return order === 'asc' ? cmp : -cmp;
    }
    if (sortBy === 'name') {
      const cmp = a.name.localeCompare(b.name, 'zh-CN');
      return order === 'asc' ? cmp : -cmp;
    }
    // 默认 status 异常优先
    const achievedA = a.fixRate != null && a.fixRate >= 0.95;
    const achievedB = b.fixRate != null && b.fixRate >= 0.95;
    const statusCmp = Number(achievedA) - Number(achievedB);
    if (statusCmp !== 0) return statusCmp;
    const cmp = (a.fixRate ?? 0) - (b.fixRate ?? 0);
    return order === 'asc' ? cmp : -cmp;
  });
}

/** 对指派人统计缺陷数列表进行排序 */
export function sortDeveloperWorkloadRows(
  items: DeveloperWorkloadRow[],
  sortBy: string,
  order: BiSortOrder = 'desc',
): DeveloperWorkloadRow[] {
  return items.slice().sort((a, b) => {
    if (sortBy === 'total') {
      const cmp = a.total - b.total;
      return order === 'asc' ? cmp : -cmp;
    }
    if (sortBy === 'fixed') {
      const cmp = a.fixed - b.fixed;
      return order === 'asc' ? cmp : -cmp;
    }
    if (sortBy === 'rate') {
      const rateA = a.total > 0 ? a.fixed / a.total : 1;
      const rateB = b.total > 0 ? b.fixed / b.total : 1;
      const cmp = rateA - rateB;
      return order === 'asc' ? cmp : -cmp;
    }
    if (sortBy === 'name') {
      const cmp = a.name.localeCompare(b.name, 'zh-CN');
      return order === 'asc' ? cmp : -cmp;
    }
    // 默认 open 待修复降序
    const cmp = a.open - b.open;
    return order === 'asc' ? cmp : -cmp;
  });
}