import { describe, expect, it } from 'vitest';
import { loginUrl, safeNext } from '../src/lib/redirect';

describe('safeNext', () => {
  it.each(['/dashboard', '/admin/users?page=2&q=a', '/a/b#frag', '/'])('accepts %s', (p) => {
    expect(safeNext(p)).toBe(p);
  });

  it.each([
    ['//evil.example', 'protocol-relative'],
    ['/\\evil.example', 'slash-backslash'],
    ['\\\\evil.example', 'backslashes'],
    ['https://evil.example', 'absolute'],
    ['javascript:alert(1)', 'javascript scheme'],
    ['evil.example', 'no leading slash'],
    ['/\t/evil.example', 'tab stripped by browsers'],
    ['/\n/evil.example', 'newline stripped by browsers'],
    ['/a\\b', 'backslash inside'],
    ['', 'empty'],
  ])('rejects %s (%s)', (p) => {
    expect(safeNext(p)).toBe('/dashboard');
  });

  it('falls back for null and undefined', () => {
    expect(safeNext(null)).toBe('/dashboard');
    expect(safeNext(undefined)).toBe('/dashboard');
  });

  it('uses a custom fallback', () => {
    expect(safeNext('//x', '/home')).toBe('/home');
  });
});

describe('loginUrl', () => {
  it('encodes the return path', () => {
    expect(loginUrl('/admin/users?page=1')).toBe('/login?next=%2Fadmin%2Fusers%3Fpage%3D1');
  });
});
