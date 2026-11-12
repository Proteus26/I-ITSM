import { describe, expect, it } from 'vitest';
import { isCookieSecure, sessionCookieOptions } from '../src/lib/session';

describe('sessionCookieOptions', () => {
  const expiresAt = '2030-01-02T03:04:05Z';

  it('is httpOnly, SameSite=Lax, path / and expires at the token expiry', () => {
    const o = sessionCookieOptions(expiresAt, {});
    expect(o).toEqual({ httpOnly: true, sameSite: 'lax', path: '/', secure: false, expires: new Date(expiresAt) });
  });

  it('is Secure only when COOKIE_SECURE is true', () => {
    expect(sessionCookieOptions(expiresAt, { COOKIE_SECURE: 'true' }).secure).toBe(true);
    expect(sessionCookieOptions(expiresAt, { COOKIE_SECURE: 'false' }).secure).toBe(false);
  });
});

describe('sessionCookieOptions with a bad expiry', () => {
  it.each(['', 'not a date', 'undefined'])('falls back to a session cookie for %j', (bad) => {
    const o = sessionCookieOptions(bad, {});
    expect('expires' in o).toBe(false);
    expect(o).toEqual({ httpOnly: true, sameSite: 'lax', path: '/', secure: false });
  });
});

describe('isCookieSecure', () => {
  it.each([
    ['true', true],
    ['TRUE', true],
    [' 1 ', true],
    ['false', false],
    ['0', false],
    ['yes', false],
    ['', false],
    [undefined, false],
  ])('%s -> %s', (value, expected) => {
    expect(isCookieSecure({ COOKIE_SECURE: value })).toBe(expected);
  });
});
