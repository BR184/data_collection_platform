import type { ECElementEvent } from 'echarts/core';

export interface EChartPointClickEvent {
  componentType?: string;
  seriesName?: string;
  seriesIndex?: number;
  dataIndex?: number;
  name?: string;
  value: unknown;
  pointKey?: string;
  detailViewKey?: string;
  detailParams: Record<string, string>;
  data: unknown;
}

function stringRecord(value: unknown): Record<string, string> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return {};
  }
  return Object.fromEntries(
    Object.entries(value)
      .filter(([, item]) => item !== null && item !== undefined)
      .map(([key, item]) => [key, String(item)]),
  );
}

/** Keeps backend-provided point identity and filters intact; no label-based inference is allowed. */
export function normalizeEChartPointClick(event: ECElementEvent): EChartPointClickEvent {
  const data = event.data;
  const pointData = data && typeof data === 'object' && !Array.isArray(data)
    ? data as Record<string, unknown>
    : {};
  const pointKey = pointData.pointKey ?? pointData.key;
  const detailViewKey = pointData.detailViewKey;
  return {
    componentType: event.componentType,
    seriesName: event.seriesName,
    seriesIndex: event.seriesIndex,
    dataIndex: event.dataIndex,
    name: event.name,
    value: event.value,
    pointKey: pointKey === null || pointKey === undefined ? undefined : String(pointKey),
    detailViewKey: detailViewKey === null || detailViewKey === undefined
      ? undefined
      : String(detailViewKey),
    detailParams: stringRecord(pointData.detailParams),
    data,
  };
}
