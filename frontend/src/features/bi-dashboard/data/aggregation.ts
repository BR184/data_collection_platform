import type { CodingTrendData, NamedValue, SubmissionTrendData } from '../charts/chart-data';

/**
 * 计算给定 ISO 日期字符串（YYYY-MM-DD）所在周的周一对应日期。
 * 使用 UTC 时间避免客户端本地时区或夏令时干扰，严格对齐后端 DayOfWeek.MONDAY 口径。
 */
export function getMondayOfWeek(dateStr: string): string {
  const parts = dateStr.split('-');
  if (parts.length !== 3) return dateStr;
  const year = parseInt(parts[0], 10);
  const month = parseInt(parts[1], 10) - 1;
  const day = parseInt(parts[2], 10);
  if (Number.isNaN(year) || Number.isNaN(month) || Number.isNaN(day)) {
    return dateStr;
  }
  const d = new Date(Date.UTC(year, month, day));
  const dayOfWeek = d.getUTCDay(); // 0 是周日，1 是周一，...，6 是周六
  const diff = (dayOfWeek === 0 ? -6 : 1) - dayOfWeek;
  d.setUTCDate(d.getUTCDate() + diff);
  const y = d.getUTCFullYear();
  const m = String(d.getUTCMonth() + 1).padStart(2, '0');
  const dt = String(d.getUTCDate()).padStart(2, '0');
  return `${y}-${m}-${dt}`;
}

/**
 * 将日级代码增量趋势聚合为周级（以每周周一作为周期标识）。
 * 保持时间升序，每周新增行数为当周各日累加值，累计新增行数按周持续累加。
 */
export function aggregateCodeTrendByWeek(daily: CodingTrendData): CodingTrendData {
  if (!daily || daily.periods.length === 0) {
    return { periods: [], addedLines: [], cumulativeLines: [] };
  }
  const weeklyAdded = new Map<string, number>();
  for (let i = 0; i < daily.periods.length; i++) {
    const monday = getMondayOfWeek(daily.periods[i]);
    const added = daily.addedLines[i] ?? 0;
    weeklyAdded.set(monday, (weeklyAdded.get(monday) ?? 0) + added);
  }

  const periods = Array.from(weeklyAdded.keys()).sort();
  let cumulative = 0;
  const addedLines: number[] = [];
  const cumulativeLines: number[] = [];

  for (const monday of periods) {
    const added = weeklyAdded.get(monday) ?? 0;
    cumulative += added;
    addedLines.push(added);
    cumulativeLines.push(cumulative);
  }

  return { periods, addedLines, cumulativeLines };
}

/**
 * 将日级提交趋势（提交数与合并请求数）聚合为周级。
 * 保持时间升序，每周提交数与合并请求数分别为当周各日求和。
 */
export function aggregateSubmissionTrendByWeek(daily: SubmissionTrendData): SubmissionTrendData {
  if (!daily || daily.periods.length === 0) {
    return { periods: [], commits: [], mergeRequests: [] };
  }
  const weeklyMap = new Map<string, { commits: number; mergeRequests: number }>();
  for (let i = 0; i < daily.periods.length; i++) {
    const monday = getMondayOfWeek(daily.periods[i]);
    const current = weeklyMap.get(monday) ?? { commits: 0, mergeRequests: 0 };
    current.commits += daily.commits[i] ?? 0;
    current.mergeRequests += daily.mergeRequests[i] ?? 0;
    weeklyMap.set(monday, current);
  }

  const periods = Array.from(weeklyMap.keys()).sort();
  const commits: number[] = [];
  const mergeRequests: number[] = [];

  for (const monday of periods) {
    const item = weeklyMap.get(monday)!;
    commits.push(item.commits);
    mergeRequests.push(item.mergeRequests);
  }

  return { periods, commits, mergeRequests };
}

/**
 * 将日级提交频次聚合为周级。
 * 保持时间升序，name 为周一日期字符串，value 为当周提交总频次。
 */
export function aggregateFrequenciesByWeek(daily: NamedValue[]): NamedValue[] {
  if (!daily || daily.length === 0) return [];
  const weeklyMap = new Map<string, number>();
  for (const item of daily) {
    const monday = getMondayOfWeek(item.name);
    weeklyMap.set(monday, (weeklyMap.get(monday) ?? 0) + (item.value ?? 0));
  }

  const periods = Array.from(weeklyMap.keys()).sort();
  return periods.map((monday) => ({
    name: monday,
    value: weeklyMap.get(monday) ?? 0,
  }));
}
