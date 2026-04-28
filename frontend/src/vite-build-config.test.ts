import { describe, expect, it } from 'vitest';
import { manualChunks } from '../build/manual-chunks';

describe('vite manual chunk strategy', () => {
  it('does not force entry-heavy UI/search libraries into shared vendor chunks', () => {
    expect(manualChunks('D:/repo/node_modules/element-plus/es/components/button/index.mjs')).toBeUndefined();
    expect(manualChunks('D:/repo/node_modules/pinyin-pro/dist/index.js')).toBeUndefined();
  });

  it('keeps large chart runtime isolated from non-chart pages', () => {
    expect(manualChunks('D:/repo/node_modules/echarts/core.js')).toBe('vendor-echarts');
    expect(manualChunks('D:/repo/node_modules/zrender/lib/core/util.js')).toBe('vendor-echarts');
  });
});
