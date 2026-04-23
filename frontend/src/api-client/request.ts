export async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  });
  const rawText = await response.text();
  let payload: any = null;
  try {
    payload = rawText ? JSON.parse(rawText) : null;
  } catch {
    payload = null;
  }

  if (!response.ok) {
    throw new Error(payload?.message || rawText || `Request failed: ${response.status}`);
  }

  if (payload && typeof payload === 'object' && 'success' in payload) {
    if (!payload.success) {
      throw new Error(payload.message || 'Request failed');
    }
    return payload.data as T;
  }

  return payload as T;
}
