import { describe, expect, it } from 'vitest';
import { RequestFence } from './request-fence';

describe('request fence', () => {
  it('only lets the latest request write back state', () => {
    const fence = new RequestFence();
    const first = fence.next('context-a');
    const second = fence.next('context-a');
    expect(fence.isCurrent(first)).toBe(false);
    expect(fence.isCurrent(second)).toBe(true);
  });

  it('invalidates outstanding work and rejects everything after close', () => {
    const fence = new RequestFence();
    const token = fence.next('context-a');
    fence.invalidate();
    expect(fence.isCurrent(token)).toBe(false);

    const afterInvalidate = fence.next('context-a');
    expect(fence.isCurrent(afterInvalidate)).toBe(true);
    fence.close();
    expect(fence.isCurrent(afterInvalidate)).toBe(false);
  });
});
