import { BarChart, CustomChart, HeatmapChart, LineChart, PieChart, ScatterChart } from 'echarts/charts';
import { AxisBreak } from 'echarts/features';
import {
  AriaComponent,
  GraphicComponent,
  DataZoomComponent,
  GridComponent,
  LegendComponent,
  MarkAreaComponent,
  MarkLineComponent,
  TooltipComponent,
  VisualMapComponent,
} from 'echarts/components';
import { init, registerTheme, use, type EChartsType } from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';

use([
  BarChart,
  CustomChart,
  HeatmapChart,
  LineChart,
  PieChart,
  ScatterChart,
  AxisBreak,
  AriaComponent,
  GraphicComponent,
  DataZoomComponent,
  GridComponent,
  LegendComponent,
  MarkAreaComponent,
  MarkLineComponent,
  TooltipComponent,
  VisualMapComponent,
  CanvasRenderer,
]);

export { init, registerTheme, type EChartsType };
