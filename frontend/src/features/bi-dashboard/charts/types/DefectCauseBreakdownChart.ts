import type { EChartsOption } from 'echarts';
import { BiChart, type BiChartRenderContext, type BiChartSize, type BiExcelTableData } from '../BiChart';
import type { DefectCauseBreakdownData } from '../chart-data';
import { BI_PALETTE } from '../palette';
import { excelPercent } from '../excel-format';

interface GroupRange {
  name: string;
  start: number;
  end: number;
  center: number;
}

export class DefectCauseBreakdownChart extends BiChart<DefectCauseBreakdownData> {
  readonly templateId = 'vertical-category-bar' as const;

  hasData(data: DefectCauseBreakdownData): boolean {
    return data.items.some((item) => item.count > 0) || data.unclassifiedCount > 0;
  }

  exportSize(): BiChartSize {
    return { width: 1680, height: 780 };
  }

  excelTable(data: DefectCauseBreakdownData): BiExcelTableData {
    const rows: Array<Array<string | number>> = data.items.map((item) => [
      item.groupName,
      item.name,
      item.count,
      excelPercent(item.sharePercent, 2),
    ]);
    // 未归类与图表一致作为独立一行显式呈现，不隐入已归类子类。
    if (data.unclassifiedCount > 0) {
      rows.push(['未归类', '未归类', data.unclassifiedCount, excelPercent(data.unclassifiedSharePercent, 2)]);
    }
    return {
      headers: ['缺陷原因大类', '缺陷原因子类', '缺陷数 (个)', '占比 (%)'],
      rows,
    };
  }

  build(data: DefectCauseBreakdownData, context: BiChartRenderContext): EChartsOption {
    // 阶段一：先按原因大类计算连续区间，第二条 X 轴只显示大类名称，避免子类标签拥挤。
    const groups = groupRanges(data);
    const values = data.items.map((item) => item.sharePercent).filter((value): value is number => value != null);
    const maxValue = Math.max(0, ...values);
    const axisMax = percentageAxisMaximum(maxValue);
    const compact = (context.width ?? 1200) < 900;
    const groupCenters = new Map(groups.map((group) => [group.center, group.name]));
    const groupStarts = new Set(groups.map((group) => group.start));
    const unclassifiedTextStyle = {
      text: `未归类占比 ${formatPercent(data.unclassifiedSharePercent)}  ·  ${data.unclassifiedCount.toLocaleString('zh-CN')} 条`,
      fill: '#475467',
      font: '600 13px -apple-system, BlinkMacSystemFont, "Segoe UI", "Microsoft YaHei", sans-serif',
      textAlign: 'right' as const,
      textVerticalAlign: 'top' as const,
    };

    // 阶段二：保留未归类数量的独立提示，同时让柱体仅表达已分类子类的占比。
    // 未归类不是某个子类，不能伪装成正常原因参与大类区间计算。
    return {
      ...this.baseOption(`系统测试缺陷原因子类占比，共 ${data.items.length} 个固定子类。`),
      grid: {
        top: 54,
        right: 18,
        bottom: compact ? 190 : 178,
        left: 58,
        containLabel: false,
      },
      graphic: [{
        type: 'text',
        right: 18,
        top: 8,
        silent: true,
        style: unclassifiedTextStyle,
      }],
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
        formatter: (params: unknown) => causeTooltip(params),
      },
      xAxis: [
        {
          type: 'category',
          data: data.items.map((item) => item.name),
          axisTick: { alignWithLabel: true },
          axisLabel: {
            interval: 0,
            rotate: compact ? 66 : 58,
            margin: 12,
            color: BI_PALETTE.subtleText,
            fontSize: 11,
            width: compact ? 110 : 124,
            overflow: 'truncate',
          },
        },
        {
          type: 'category',
          position: 'bottom',
          offset: compact ? 154 : 142,
          data: data.items.map((item) => item.name),
          axisLine: { show: true, lineStyle: { color: '#D0D5DD' } },
          axisTick: {
            show: true,
            alignWithLabel: false,
            interval: (index: number) => groupStarts.has(index),
            length: 12,
            lineStyle: { color: '#D0D5DD' },
          },
          axisLabel: {
            interval: (index: number) => groupCenters.has(index),
            formatter: (_value: string, index: number) => groupCenters.get(index) ?? '',
            margin: 10,
            color: '#475467',
            fontSize: 12,
            fontWeight: 600,
          },
        },
      ],
      yAxis: {
        type: 'value',
        name: '缺陷占比',
        min: 0,
        max: axisMax,
        splitNumber: 5,
        axisLabel: { formatter: (value: number) => `${formatAxisPercent(value)}%` },
      },
      series: [{
        name: '缺陷占比',
        type: 'bar',
        xAxisIndex: 0,
        barMaxWidth: 28,
        data: data.items.map((item) => ({
          name: item.name,
          value: item.sharePercent,
          count: item.count,
          groupName: item.groupName,
          itemStyle: { color: item.color, borderRadius: [3, 3, 0, 0] },
          label: {
            show: item.sharePercent != null && item.sharePercent > 0,
            formatter: formatPercent(item.sharePercent),
          },
        })),
        label: {
          show: true,
          position: 'top',
          distance: 5,
          color: '#344054',
          fontSize: 11,
          fontWeight: 600,
        },
      }],
    };
  }
}

function groupRanges(data: DefectCauseBreakdownData): GroupRange[] {
  // 数据必须按大类连续排列；这里只计算展示区间，不改变后端返回的业务顺序。
  const groups: GroupRange[] = [];
  data.items.forEach((item, index) => {
    const previous = groups.at(-1);
    if (previous?.name === item.groupName) {
      previous.end = index;
      previous.center = Math.floor((previous.start + index) / 2);
      return;
    }
    groups.push({ name: item.groupName, start: index, end: index, center: index });
  });
  return groups;
}

function formatPercent(value: number | null): string {
  return value == null ? '--' : `${value.toFixed(2)}%`;
}

function formatAxisPercent(value: number): string {
  return value >= 10 || Number.isInteger(value) ? value.toFixed(0) : value.toFixed(1);
}

function percentageAxisMaximum(maxValue: number): number {
  if (maxValue <= 1) return 1;
  if (maxValue <= 5) return Math.ceil(maxValue * 1.2 * 2) / 2;
  if (maxValue <= 10) return Math.ceil(maxValue * 1.2);
  return Math.ceil(maxValue * 1.2 / 5) * 5;
}

function causeTooltip(params: unknown): string {
  if (!Array.isArray(params)) return '';
  const first = params[0];
  if (typeof first !== 'object' || first == null || !('data' in first)) return '';
  const data = first.data;
  if (typeof data !== 'object' || data == null) return '';
  const record = data as { name?: unknown; value?: unknown; count?: unknown; groupName?: unknown };
  const name = typeof record.name === 'string' ? record.name : '';
  const groupName = typeof record.groupName === 'string' ? record.groupName : '';
  const count = typeof record.count === 'number' ? record.count : 0;
  const share = typeof record.value === 'number' ? formatPercent(record.value) : '--';
  return `${groupName} · ${name}<br/>缺陷数：${count.toLocaleString('zh-CN')}<br/>缺陷占比：${share}`;
}
