export function manualChunks(id: string) {
  const normalizedId = id.replace(/\\/g, '/');
  if (!normalizedId.includes('node_modules')) {
    return undefined;
  }
  if (normalizedId.includes('/echarts/') || normalizedId.includes('/zrender/')) {
    return 'vendor-echarts';
  }
  if (normalizedId.includes('/vue/') || normalizedId.includes('/vue-router/')) {
    return 'vendor-vue';
  }
  return undefined;
}
