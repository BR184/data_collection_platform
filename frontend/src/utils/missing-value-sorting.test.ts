import { describe, expect, it } from 'vitest';
import { compareNumericNullsLast, compareTextNullsLast } from './missing-value-sorting';

describe('compareNumericNullsLast', () => {
  it('puts missing values after real zero in both directions', () => {
    expect(compareNumericNullsLast(null, 0, 'desc')).toBeGreaterThan(0);
    expect(compareNumericNullsLast(0, null, 'desc')).toBeLessThan(0);
    expect(compareNumericNullsLast(null, 0, 'asc')).toBeGreaterThan(0);
    expect(compareNumericNullsLast(undefined, 0, 'asc')).toBeGreaterThan(0);
  });

  it('orders present values by direction only', () => {
    expect(compareNumericNullsLast(1, 2, 'asc')).toBeLessThan(0);
    expect(compareNumericNullsLast(1, 2, 'desc')).toBeGreaterThan(0);
    expect(compareNumericNullsLast(5000, 3333, 'desc')).toBeLessThan(0);
  });

  it('treats non-finite values as missing and equal to each other', () => {
    expect(compareNumericNullsLast(Number.NaN, null, 'desc')).toBe(0);
    expect(compareNumericNullsLast(Number.POSITIVE_INFINITY, 1, 'desc')).toBeGreaterThan(0);
  });

  it('returns zero when both sides hold the same value', () => {
    expect(compareNumericNullsLast(0, 0, 'asc')).toBe(0);
    expect(compareNumericNullsLast(7, 7, 'desc')).toBe(0);
  });
});

describe('compareTextNullsLast', () => {
  it('puts blank and no-data slash after real text in both directions', () => {
    expect(compareTextNullsLast('', '装配', 'asc')).toBeGreaterThan(0);
    expect(compareTextNullsLast('/', '装配', 'asc')).toBeGreaterThan(0);
    expect(compareTextNullsLast('', '装配', 'desc')).toBeGreaterThan(0);
    expect(compareTextNullsLast(undefined, '装配', 'desc')).toBeGreaterThan(0);
  });

  it('orders present text by direction', () => {
    expect(compareTextNullsLast('A', 'B', 'asc')).toBeLessThan(0);
    expect(compareTextNullsLast('A', 'B', 'desc')).toBeGreaterThan(0);
  });

  it('treats two missing texts as equal so callers keep stable order', () => {
    expect(compareTextNullsLast('', '/', 'asc')).toBe(0);
    expect(compareTextNullsLast(null, undefined, 'desc')).toBe(0);
  });
});
