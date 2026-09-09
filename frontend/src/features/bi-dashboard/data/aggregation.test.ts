import { describe, expect, it } from 'vitest';
import {
  aggregateCodeTrendByWeek,
  aggregateFrequenciesByWeek,
  aggregateSubmissionTrendByWeek,
  getMondayOfWeek,
} from './aggregation';
import type { CodingTrendData, NamedValue, SubmissionTrendData } from '../charts/chart-data';

describe('BI dashboard trend aggregation', () => {
  describe('getMondayOfWeek', () => {
    it('keeps a Monday unchanged', () => {
      expect(getMondayOfWeek('2026-08-03')).toBe('2026-08-03');
    });

    it('maps Tuesday through Saturday to the preceding Monday', () => {
      expect(getMondayOfWeek('2026-08-04')).toBe('2026-08-03'); // Tuesday
      expect(getMondayOfWeek('2026-08-05')).toBe('2026-08-03'); // Wednesday
      expect(getMondayOfWeek('2026-08-07')).toBe('2026-08-03'); // Friday
      expect(getMondayOfWeek('2026-08-01')).toBe('2026-07-27'); // Saturday
    });

    it('maps Sunday to the preceding Monday (not following Monday)', () => {
      expect(getMondayOfWeek('2026-08-02')).toBe('2026-07-27'); // Sunday
      expect(getMondayOfWeek('2026-08-09')).toBe('2026-08-03'); // Sunday
    });

    it('handles year boundary correctly', () => {
      // 2026-01-01 is Thursday, preceding Monday is 2025-12-29
      expect(getMondayOfWeek('2026-01-01')).toBe('2025-12-29');
    });

    it('gracefully handles malformed input strings', () => {
      expect(getMondayOfWeek('invalid-date')).toBe('invalid-date');
      expect(getMondayOfWeek('')).toBe('');
    });
  });

  describe('aggregateCodeTrendByWeek', () => {
    it('aggregates daily added lines and computes correct cumulative lines by week', () => {
      const daily: CodingTrendData = {
        periods: ['2026-08-01', '2026-08-02', '2026-08-03', '2026-08-04'],
        addedLines: [100, 200, 300, 400],
        cumulativeLines: [100, 300, 600, 1000],
      };
      // 2026-08-01 (Sat) and 2026-08-02 (Sun) -> week of 2026-07-27 (sum: 300, cumulative: 300)
      // 2026-08-03 (Mon) and 2026-08-04 (Tue) -> week of 2026-08-03 (sum: 700, cumulative: 1000)
      const weekly = aggregateCodeTrendByWeek(daily);

      expect(weekly.periods).toEqual(['2026-07-27', '2026-08-03']);
      expect(weekly.addedLines).toEqual([300, 700]);
      expect(weekly.cumulativeLines).toEqual([300, 1000]);
    });

    it('returns empty lists for empty daily input', () => {
      const empty = aggregateCodeTrendByWeek({ periods: [], addedLines: [], cumulativeLines: [] });
      expect(empty.periods).toEqual([]);
      expect(empty.addedLines).toEqual([]);
      expect(empty.cumulativeLines).toEqual([]);
    });
  });

  describe('aggregateSubmissionTrendByWeek', () => {
    it('aggregates commits and merge requests by week', () => {
      const daily: SubmissionTrendData = {
        periods: ['2026-08-01', '2026-08-02', '2026-08-03'],
        commits: [5, 3, 10],
        mergeRequests: [1, 2, 4],
      };
      // 2026-08-01 and 2026-08-02 -> 2026-07-27 (commits: 8, MRs: 3)
      // 2026-08-03 -> 2026-08-03 (commits: 10, MRs: 4)
      const weekly = aggregateSubmissionTrendByWeek(daily);

      expect(weekly.periods).toEqual(['2026-07-27', '2026-08-03']);
      expect(weekly.commits).toEqual([8, 10]);
      expect(weekly.mergeRequests).toEqual([3, 4]);
    });

    it('returns empty lists for empty daily input', () => {
      const empty = aggregateSubmissionTrendByWeek({ periods: [], commits: [], mergeRequests: [] });
      expect(empty.periods).toEqual([]);
      expect(empty.commits).toEqual([]);
      expect(empty.mergeRequests).toEqual([]);
    });
  });

  describe('aggregateFrequenciesByWeek', () => {
    it('aggregates commit frequencies by week', () => {
      const daily: NamedValue[] = [
        { name: '2026-08-01', value: 5 },
        { name: '2026-08-02', value: 3 },
        { name: '2026-08-03', value: 12 },
      ];
      const weekly = aggregateFrequenciesByWeek(daily);

      expect(weekly).toEqual([
        { name: '2026-07-27', value: 8 },
        { name: '2026-08-03', value: 12 },
      ]);
    });

    it('returns empty array for empty input', () => {
      expect(aggregateFrequenciesByWeek([])).toEqual([]);
    });
  });
});
