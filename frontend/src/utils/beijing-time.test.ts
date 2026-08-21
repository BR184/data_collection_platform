import { describe, expect, it } from 'vitest';
import {
  formatBeijingDateTime,
  formatLocalDate,
  formatLocalDateTime,
  formatLocalDateTimeMinute,
} from './beijing-time';

describe('date display utilities', () => {
  it('preserves local ISO text without applying a timezone conversion', () => {
    expect(formatLocalDateTime('2026-04-24T10:20:30')).toBe('2026-04-24 10:20:30');
    expect(formatLocalDateTimeMinute('2026-04-24T10:20:30')).toBe('2026-04-24 10:20');
    expect(formatLocalDateTime(null)).toBe('-');
  });

  it('formats date-only values independently from datetime values', () => {
    expect(formatLocalDate('2026-04-24T10:20:30')).toBe('2026-04-24');
    expect(formatLocalDate(undefined, '暂无')).toBe('暂无');
  });

  it('converts offset-bearing values to Beijing time', () => {
    expect(formatBeijingDateTime('2026-04-24T02:20:30Z')).toBe('2026-04-24 10:20:30');
  });
});
