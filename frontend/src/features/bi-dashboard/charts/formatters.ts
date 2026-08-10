interface ChartCallbackShape {
  dataIndex?: unknown;
  value?: unknown;
}

export function callbackItem(params: unknown): ChartCallbackShape | null {
  const candidate = Array.isArray(params) ? params[0] : params;
  return typeof candidate === 'object' && candidate !== null ? candidate as ChartCallbackShape : null;
}

export function callbackDataIndex(params: unknown): number {
  const value = callbackItem(params)?.dataIndex;
  return typeof value === 'number' && Number.isInteger(value) ? value : 0;
}

export function callbackNumericValue(params: unknown): number | null {
  const value = callbackItem(params)?.value;
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

export function callbackTupleValue(params: unknown): [number, number, number] | null {
  const value = callbackItem(params)?.value;
  return Array.isArray(value)
    && value.length >= 3
    && value.slice(0, 3).every((item) => typeof item === 'number' && Number.isFinite(item))
    ? [value[0], value[1], value[2]] as [number, number, number]
    : null;
}

