import type { EChartsOption } from 'echarts';
import type { BiChartTemplateId } from '../data/types';
import { BI_PALETTE, BI_SERIES_COLORS } from './palette';

export type BiChartRenderMode = 'view' | 'export';

export interface BiChartRenderContext {
  mode: BiChartRenderMode;
  width?: number;
  height?: number;
}

export interface BiChartSize {
  width: number;
  height: number;
}

/** 图表导出为 Excel 数据表时的结构化表格：表头文本 + 数据行（单元格为文本或数值）。 */
export interface BiExcelTableData {
  headers: string[];
  rows: Array<Array<string | number>>;
}

/** BI 图表类型的公共契约；具体类型类只实现自己的图形语义。 */
export abstract class BiChart<TData> {
  // 每个具体图表必须声明稳定模板 ID，供页面绑定、下载授权和回归测试共同识别。
  abstract readonly templateId: BiChartTemplateId;

  /**
   * 依据同一份完整数据构造当前视图或导出视图。
   * view/export 只影响布局和尺寸，不改变数据筛选或计算口径。
   */
  abstract build(data: TData, context: BiChartRenderContext): EChartsOption;

  // 本层流程：具体图表类只把强类型页面数据转换为 ECharts 配置，
  // 基类统一提供公共配置、页面/导出模式和尺寸复用能力。
  /** 页面先调用此方法，再决定渲染图表还是显示空数据/不完整状态。 */
  abstract hasData(data: TData): boolean;

  /** 计算完整数据 PNG 的离屏画布尺寸。 */
  exportSize(_data: TData): BiChartSize {
    return { width: 1440, height: 720 };
  }

  /**
   * 把当前图表的强类型数据提取为可导出的 Excel 表格（表头 + 数据行）。
   *
   * 每个图表类基于自身数据结构与配置（如单位）实现，取代过去按 templateId 分支的脆弱提取；
   * 因 templateId 与数据结构并非一一对应（多个图表可共用同一 templateId），只有图表类自身
   * 才拥有所需的类型信息。前端只负责"导出哪些单元格"，后端负责序列化为标准 .xlsx。
   */
  abstract excelTable(data: TData): BiExcelTableData;

  protected baseOption(description: string): EChartsOption {
    // 公共视觉、无障碍和交互配置集中在基类，具体图表只补充自身 series/grid 等配置。
    return {
      color: [...BI_SERIES_COLORS],
      animationDuration: 500,
      animationEasing: 'cubicOut',
      textStyle: {
        color: BI_PALETTE.text,
        fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", "Microsoft YaHei", sans-serif',
      },
      aria: { enabled: true, decal: { show: false }, label: { description } },
      tooltip: {
        trigger: 'item',
        confine: true,
        backgroundColor: 'rgba(255, 255, 255, 0.98)',
        borderColor: '#E4E7EC',
        borderWidth: 1,
        extraCssText: 'box-shadow:0 8px 24px rgba(16,24,40,.10);border-radius:8px;padding:10px 12px;',
        textStyle: { color: '#344054', fontSize: 12 },
      },
    };
  }

  protected categoryZoom(itemCount: number, mode: BiChartRenderMode, visibleCount = 12) {
    // 页面视图限制可见分类数量以保持可读性；PNG 导出保留完整分类，不复用这个限制。
    if (mode === 'export' || itemCount <= visibleCount) {
      return [];
    }
    return [{ type: 'slider' as const, startValue: 0, endValue: visibleCount - 1, height: 16, bottom: 4 }];
  }

  protected horizontalExportSize(itemCount: number): BiChartSize {
    return { width: 1440, height: Math.max(620, itemCount * 46 + 180) };
  }

  protected verticalExportSize(itemCount: number): BiChartSize {
    return { width: Math.max(1440, itemCount * 96 + 180), height: 720 };
  }
}
