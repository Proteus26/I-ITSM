import { describe, expect, it } from 'vitest';
import {
  EXPIRED_NOTICE,
  formatRetryAfter,
  loginErrorMessage,
  loginNotice,
  REGISTERED_NOTICE,
  LOGGED_OUT_ALL_NOTICE,
  tooManyAttemptsMessage,
} from '../src/lib/loginMessages';
import { ApiError, parseRetryAfter } from '../src/lib/errors';
import { loginUrl } from '../src/lib/redirect';

describe('formatRetryAfter', () => {
  it.each([
    [1, 'less than a minute'],
    [59, 'less than a minute'],
    [60, '1 minute'],
    [61, '2 minutes'],
    [120, '2 minutes'],
    [900, '15 minutes'],
    [7200, '120 minutes'],
    [7201, '3 hours'],
    [10800, '3 hours'],
    [10801, '4 hours'],
    [86400, '24 hours'],
    [undefined, 'a few minutes'],
    [0, 'a few minutes'],
  ])('%s s -> %s', (s, text) => expect(formatRetryAfter(s)).toBe(text));

  it('builds the login message', () => {
    expect(tooManyAttemptsMessage(300)).toBe('Too many failed attempts. Try again in 5 minutes.');
    expect(tooManyAttemptsMessage(30)).toBe('Too many failed attempts. Try again in less than a minute.');
  });
});

describe('loginNotice', () => {
  const n = (q: string) => loginNotice(new URLSearchParams(q));
  it('maps flags to constants', () => {
    expect(n('expired=1')).toEqual({ text: EXPIRED_NOTICE, kind: 'info' });
    expect(n('loggedOut=all')).toEqual({ text: LOGGED_OUT_ALL_NOTICE, kind: 'info' });
    expect(n('registered=1')).toEqual({ text: REGISTERED_NOTICE, kind: 'success' });
  });
  it('never reflects query text', () => {
    expect(n('expired=<b>hi</b>')).toBeNull();
    expect(n('loggedOut=evil')).toBeNull();
    expect(n('')).toBeNull();
  });
});

describe('parseRetryAfter / ApiError', () => {
  it('accepts whole seconds only', () => {
    expect(parseRetryAfter('42')).toBe(42);
    expect(parseRetryAfter('0')).toBeUndefined();
    expect(parseRetryAfter('-3')).toBeUndefined();
    expect(parseRetryAfter('1.5')).toBeUndefined();
    expect(parseRetryAfter(null)).toBeUndefined();
  });
  it('fromBody carries it', () => {
    expect(ApiError.fromBody(429, {}, '', 10).retryAfterSeconds).toBe(10);
  });
});

describe('loginUrl', () => {
  it('adds expired only on request', () => {
    expect(loginUrl('/a')).toBe('/login?next=%2Fa');
    expect(loginUrl('/a', { expired: true })).toBe('/login?next=%2Fa&expired=1');
  });
});

describe('loginErrorMessage', () => {
  const e = (status: number, detail = 'Invalid email or password', retry?: number) => new ApiError(status, 'T', detail, {}, false, retry);
  it('429 shows the wait, 400 a fixed hint, anything else the backend message', () => {
    expect(loginErrorMessage(e(429, 'x', 300))).toBe('Too many failed attempts. Try again in 5 minutes.');
    expect(loginErrorMessage(e(429, 'x'))).toBe('Too many failed attempts. Try again in a few minutes.');
    expect(loginErrorMessage(e(400, 'Validation failed'))).toBe('Enter a valid email address and your password.');
    expect(loginErrorMessage(e(401))).toBe('Invalid email or password');
    expect(loginErrorMessage(e(403, 'Account is disabled'))).toBe('Account is disabled');
  });
  it('hides server error details', () => {
    expect(loginErrorMessage(e(500, 'stack trace'))).toBe('Something went wrong on the server. Try again.');
  });
});
