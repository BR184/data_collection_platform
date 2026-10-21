import { afterEach, describe, expect, it, vi } from 'vitest';
import { createUuid } from './uuid';

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

function withCrypto(partial: Partial<Crypto>) {
  vi.stubGlobal('crypto', partial);
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('createUuid', () => {
  it('prefers the native randomUUID when the browser exposes it', () => {
    const native = vi.fn(() => '11111111-2222-4333-8444-555555555555');
    withCrypto({ randomUUID: native } as unknown as Crypto);

    expect(createUuid()).toBe('11111111-2222-4333-8444-555555555555');
    expect(native).toHaveBeenCalledTimes(1);
  });

  it('falls back to getRandomValues on insecure origins where randomUUID is missing', () => {
    const getRandomValues = vi.fn((array: Uint8Array) => {
      array.fill(0xab);
      return array;
    });
    withCrypto({ getRandomValues } as unknown as Crypto);

    const value = createUuid();

    expect(getRandomValues).toHaveBeenCalledTimes(1);
    expect(value).toMatch(UUID_V4);
  });

  it('still returns a UUID v4 shape when no Web Crypto API exists at all', () => {
    withCrypto({} as Crypto);

    expect(createUuid()).toMatch(UUID_V4);
  });

  it('returns a distinct identifier on every call', () => {
    withCrypto({} as Crypto);

    const values = new Set(Array.from({ length: 32 }, () => createUuid()));

    expect(values.size).toBe(32);
  });

  it('sets the version and variant bits required by RFC 4122', () => {
    withCrypto({ getRandomValues: (array: Uint8Array) => array.fill(0xff) } as unknown as Crypto);

    const [version, variant] = createUuid().split('-').slice(2);

    expect(version[0]).toBe('4');
    expect('89ab').toContain(variant[0]);
  });
});
