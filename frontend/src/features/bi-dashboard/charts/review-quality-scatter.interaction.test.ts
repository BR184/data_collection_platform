import { afterAll, afterEach, describe, expect, it, vi } from 'vitest';
import { use, type ECElementEvent } from 'echarts/core';
import { SVGRenderer } from 'echarts/renderers';
import { init, type EChartsType } from './bi-echarts-runtime';
import { ReviewQualityScatterChart } from './types/ReviewQualityScatterChart';

const originalAnimationFrame = vi.hoisted(() => {
  const original = globalThis.requestAnimationFrame;
  Object.defineProperty(globalThis, 'requestAnimationFrame', {
    configurable: true,
    writable: true,
    value: () => 1,
  });
  Object.defineProperty(globalThis, 'cancelAnimationFrame', {
    configurable: true,
    writable: true,
    value: () => undefined,
  });
  return original;
});

use([SVGRenderer]);

afterAll(() => {
  Object.defineProperty(globalThis, 'requestAnimationFrame', {
    configurable: true,
    writable: true,
    value: originalAnimationFrame,
  });
});

describe('review quality scatter axis-break interaction', () => {
  let chart: EChartsType | null = null;
  let root: HTMLDivElement | null = null;

  afterEach(() => {
    chart?.dispose();
    root?.remove();
    vi.restoreAllMocks();
    chart = null;
    root = null;
  });

  it('routes a real mousemove on a compressed outlier to the scatter point', () => {
    root = document.createElement('div');
    document.body.append(root);
    const canvasContext = {
      font: '',
      measureText: (text: string) => ({ width: text.length * 7 }),
    } as unknown as CanvasRenderingContext2D;
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext')
      .mockReturnValue(canvasContext);
    chart = init(root, undefined, { renderer: 'svg', width: 640, height: 420, ssr: true });

    const option = new ReviewQualityScatterChart({
      densityRange: [2, 10],
      densityUnit: '个/KLOC',
      rateUnit: '行/小时',
    }).build([
      { name: '正常记录', date: '2026-08-01', rate: 2, density: 3, achieved: true },
      { name: '异常记录', date: '2026-08-02', rate: 180, density: 1_000, achieved: false },
    ], { mode: 'view', width: 640, height: 420 });
    chart.setOption({ ...option, animation: false }, true);

    let hoveredName: string | undefined;
    chart.on('mouseover', (params: ECElementEvent) => {
      const value = Array.isArray(params.value) ? params.value : [];
      hoveredName = String(value[2] ?? '');
    });
    const point = chart.convertToPixel({ xAxisIndex: 0, yAxisIndex: 0 }, [180, 1_000]) as [number, number];

    const hovered = chart.getZr().handler.findHover(point[0], point[1]);
    const pointerEvent = Object.assign(new MouseEvent('mousemove'), {
      zrX: point[0],
      zrY: point[1],
      zrDelta: 0,
      zrEventControl: 'no_globalout' as const,
      zrByTouch: false,
    });
    chart.getZr().handler.dispatchToElement(hovered, 'mouseover', pointerEvent);

    expect(hoveredName).toBe('异常记录');
  });
});
