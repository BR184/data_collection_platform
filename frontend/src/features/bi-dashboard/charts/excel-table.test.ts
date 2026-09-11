import { describe, expect, it } from 'vitest';
import type { BiExcelTableData } from './BiChart';
import { excelAchieved, excelPercent, excelSharePercent } from './excel-format';
import {
  CodingTrendComboChart,
  DefectCauseBreakdownChart,
  DelayHeatmapChart,
  DeveloperWorkloadChart,
  DistributionDonutChart,
  ModuleRepairMatrixChart,
  OverlayCategoryBarChart,
  QualityRoundTrackChart,
  QualityTrendSmallMultiplesChart,
  ReviewQualityDualPanelChart,
  ReviewQualityScatterChart,
  StackedCategoryBarChart,
  SubmissionTrendComboChart,
  TestQualityAttainmentChart,
  VerticalCategoryBarChart,
} from './types';

// 编码页评审质量图使用 KLOC/行 口径，用于验证表头单位取自图表配置而非写死。
const CODING_REVIEW_CONFIG = { densityRange: [2, 10] as const, densityUnit: '个/KLOC', rateUnit: '行/小时' };

describe('BI chart excelTable extraction', () => {
  it('provides a rectangular, headed table for every concrete chart', () => {
    const tables: BiExcelTableData[] = [
      new DistributionDonutChart().excelTable([{ name: '设计规范', value: 12 }, { name: '逻辑规范', value: 8 }]),
      new VerticalCategoryBarChart().excelTable([{ name: '张三', value: 1200 }]),
      new StackedCategoryBarChart().excelTable({
        categories: ['模块A'],
        series: [{ name: '新增', values: [10] }, { name: '修改', values: [5] }],
      }),
      new CodingTrendComboChart().excelTable({ periods: ['07-01'], addedLines: [100], cumulativeLines: [100] }),
      new SubmissionTrendComboChart().excelTable({ periods: ['07-01'], commits: [10], mergeRequests: [2] }),
      new OverlayCategoryBarChart().excelTable([{ name: '草图', total: 12, overlay: 3 }]),
      new DeveloperWorkloadChart().excelTable([{ name: '张三', total: 10, open: 2, fixed: 8 }]),
      new QualityRoundTrackChart().excelTable([
        { name: '第一轮', levelOne: 1, levelTwo: 2, levelThree: 3, submitted: 6, closed: 5, open: 1, closeRate: 83.33 },
      ]),
      new ModuleRepairMatrixChart().excelTable([
        { name: '权限', fixRate: 95.5, levelOneRate: 100, p1Rate: 90, p2Rate: 80, openCount: 2, totalCount: 40 },
      ]),
      new TestQualityAttainmentChart().excelTable([
        { id: 'm', name: '权限管理', passRate: 88.8, targetRate: 95, counts: { label: '达标功能 / 统计功能', attained: 18, total: 20 }, achieved: true },
      ]),
      new ReviewQualityDualPanelChart(CODING_REVIEW_CONFIG).excelTable([{ name: '草图', density: 3.32, rate: 480, achieved: true }]),
      new ReviewQualityScatterChart({ densityRange: [2, 10], densityUnit: '个/KLOC', rateUnit: 'KLOC/小时' })
        .excelTable([{ name: '草图', date: '2026-08-01', rate: 0.8, density: 3, achieved: true }]),
      new QualityTrendSmallMultiplesChart().excelTable({ periods: ['07-01'], commentRates: [20], defectDensities: [3.2] }),
      new DelayHeatmapChart().excelTable({ reasons: ['需求遗漏'], severities: ['一级缺陷'], values: [[0, 0, 5]] }),
      new DefectCauseBreakdownChart().excelTable({
        items: [{ id: 'demand', name: '需求遗漏', groupId: 'requirement', groupName: '需求问题', count: 5, sharePercent: 1.54, color: '#5470C6' }],
        unclassifiedCount: 3175,
        unclassifiedSharePercent: 99.28,
      }),
    ];

    // 15 个图表类共用 14 个 templateId（缺陷原因分布复用 vertical-category-bar），此处按类逐一验证。
    expect(tables).toHaveLength(15);
    tables.forEach((table) => {
      expect(table.headers.length).toBeGreaterThanOrEqual(2);
      table.rows.forEach((row) => expect(row).toHaveLength(table.headers.length));
    });
  });

  it('expands the defect-cause object payload instead of crashing on a shared template id', () => {
    // 该图 templateId 与 VerticalCategoryBarChart 相同，但数据是对象；旧的 switch 提取会当数组 .map() 崩溃。
    const table = new DefectCauseBreakdownChart().excelTable({
      items: [
        { id: 'demand', name: '需求遗漏', groupId: 'requirement', groupName: '需求问题', count: 5, sharePercent: 1.54, color: '#5470C6' },
        { id: 'design', name: '设计方案不合理', groupId: 'design', groupName: '设计问题', count: 14, sharePercent: 24.21, color: '#F2A66F' },
      ],
      unclassifiedCount: 3175,
      unclassifiedSharePercent: 99.28,
    });

    expect(table.headers).toEqual(['缺陷原因大类', '缺陷原因子类', '缺陷数 (个)', '占比 (%)']);
    expect(table.rows).toEqual([
      ['需求问题', '需求遗漏', 5, 1.54],
      ['设计问题', '设计方案不合理', 14, 24.21],
      ['未归类', '未归类', 3175, 99.28],
    ]);
  });

  it('omits the unclassified row when nothing is unclassified', () => {
    const table = new DefectCauseBreakdownChart().excelTable({
      items: [{ id: 'demand', name: '需求遗漏', groupId: 'requirement', groupName: '需求问题', count: 5, sharePercent: 100, color: '#5470C6' }],
      unclassifiedCount: 0,
      unclassifiedSharePercent: 0,
    });

    expect(table.rows).toEqual([['需求问题', '需求遗漏', 5, 100]]);
  });

  it('takes review-quality header units from chart config rather than hardcoding 个/页', () => {
    const dual = new ReviewQualityDualPanelChart(CODING_REVIEW_CONFIG).excelTable([
      { name: '草图', density: 3.32, rate: 480, achieved: true },
      { name: '装配', density: null, rate: null, achieved: null },
    ]);
    expect(dual.headers).toEqual(['模块名称', '缺陷密度 (个/KLOC)', '评审速率 (行/小时)', '达标状态']);
    expect(dual.rows).toEqual([
      ['草图', 3.32, 480, '已达标'],
      ['装配', '--', '--', '未判定'],
    ]);

    const scatter = new ReviewQualityScatterChart({ densityRange: [2, 10], densityUnit: '个/KLOC', rateUnit: 'KLOC/小时' }).excelTable([
      { name: '草图', date: '2026-08-01', rate: 0.8, density: 3, achieved: false },
    ]);
    expect(scatter.headers).toEqual(['模块名称', '评审日期', '评审速率 (KLOC/小时)', '缺陷密度 (个/KLOC)', '达标状态']);
    expect(scatter.rows).toEqual([['草图', '2026-08-01', 0.8, 3, '未达标']]);
  });

  it('keeps ratio fields on their percent scale without multiplying by 100 again', () => {
    // closeRate/passRate/commentRates 等在页面数据中已是百分数（0-100），必须原样输出。
    const round = new QualityRoundTrackChart().excelTable([
      { name: '第一轮', levelOne: 1, levelTwo: 2, levelThree: 3, submitted: 6, closed: 5, open: 1, closeRate: 83.33 },
    ]);
    expect(round.rows[0]).toEqual(['第一轮', 6, 5, 1, 83.3, 1, 2, 3]);

    const attainment = new TestQualityAttainmentChart().excelTable([
      { id: 'm', name: '权限管理', passRate: 88.8, targetRate: 95, counts: null, achieved: false },
    ]);
    expect(attainment.rows[0]).toEqual(['权限管理', 88.8, 95, '未达标', '--', '--']);

    const trend = new QualityTrendSmallMultiplesChart().excelTable({
      periods: ['07-01', '07-02'],
      commentRates: [20, null],
      defectDensities: [3.2, 4.1],
    });
    expect(trend.rows).toEqual([
      ['07-01', 20, 3.2],
      ['07-02', '--', 4.1],
    ]);

    const matrix = new ModuleRepairMatrixChart().excelTable([
      { name: '权限', fixRate: 95.5, levelOneRate: 100, p1Rate: 90, p2Rate: 80, openCount: 2, totalCount: 40 },
    ]);
    expect(matrix.rows[0]).toEqual(['权限', 2, 40, 95.5, 100, 90, 80]);
  });

  it('reads heatmap tuples as [severityIndex, reasonIndex, count] to match the axes', () => {
    const table = new DelayHeatmapChart().excelTable({
      reasons: ['需求遗漏', '设计缺陷'],
      severities: ['一级缺陷', '二级缺陷'],
      values: [[0, 1, 7], [1, 0, 3]],
    });

    expect(table.headers).toEqual(['原因分类', '缺陷级别', '延期缺陷数']);
    expect(table.rows).toEqual([
      ['设计缺陷', '一级缺陷', 7],
      ['需求遗漏', '二级缺陷', 3],
    ]);
  });

  it('derives fix rate from counts and treats an empty denominator as fully repaired', () => {
    const workload = new DeveloperWorkloadChart().excelTable([
      { name: '张三', total: 10, open: 2, fixed: 8 },
      { name: '李四', total: 0, open: 0, fixed: 0 },
    ]);
    expect(workload.headers).toEqual(['指派责任人', '缺陷总数', '已修复缺陷数', '待修复缺陷数', '修复率 (%)']);
    expect(workload.rows).toEqual([
      ['张三', 10, 8, 2, 80],
      ['李四', 0, 0, 0, 100],
    ]);

    const overlay = new OverlayCategoryBarChart().excelTable([{ name: '草图', total: 12, overlay: 3 }]);
    expect(overlay.rows[0]).toEqual(['草图', 12, 3, 9, 75]);
  });

  it('computes donut share against the total of every category', () => {
    const table = new DistributionDonutChart().excelTable([
      { name: '设计规范', value: 12 },
      { name: '逻辑规范', value: 8 },
    ]);
    expect(table.headers).toEqual(['分类名称', '数量', '占比 (%)']);
    expect(table.rows).toEqual([
      ['设计规范', 12, 60],
      ['逻辑规范', 8, 40],
    ]);
  });
});

describe('excel-format helpers', () => {
  it('preserves the percent scale and keeps null as an uncomputable marker', () => {
    expect(excelPercent(83.33, 1)).toBe(83.3);
    expect(excelPercent(96.95)).toBe(96.95);
    expect(excelPercent(null)).toBe('--');
    expect(excelPercent(undefined)).toBe('--');
  });

  it('computes a share from counts and guards against a zero denominator', () => {
    expect(excelSharePercent(12, 20)).toBe(60);
    expect(excelSharePercent(5, 0)).toBe(0);
  });

  it('maps attainment to explicit Chinese copy', () => {
    expect(excelAchieved(true)).toBe('已达标');
    expect(excelAchieved(false)).toBe('未达标');
    expect(excelAchieved(null)).toBe('未判定');
  });
});
